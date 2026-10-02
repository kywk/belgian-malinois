package com.bpm.core.dlq;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.bpm.core.audit.consumer.DeadLetterConsumer;
import com.bpm.core.security.GatewayAuthenticationFilter;
import com.bpm.core.support.IntegrationTestBase;
import com.bpm.core.support.WebhookTestSink;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.AbstractMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * #51 的端到端驗證：DLQ 告警真的寫進稽核，人工重放真的回到原始目的地。
 *
 * <h2>⚠️ x-death 不能用「手工組一筆訊息」製造</h2>
 *
 * <p>RabbitMQ 3.13+ 把 {@code x-death} 視為 broker 自己的標頭：
 * 客戶端發布的 {@code x-death} 會被<b>剝掉</b>（實測本測試的
 * {@code rabbitmq:3-management}＝3.13.7 確認）。所以「重放怎麼解讀
 * x-death」的主要路徑在這裡是<b>走真實死信</b>：讓 webhook 投遞持續失敗
 * （{@code WebhookTestSink.alwaysFail}）、retry 耗盡後由 broker 加上
 * x-death 進 DLQ，再重放。多筆 x-death 的順序、dlx 防呆等形狀由
 * {@code DlqReplayServiceDestinationTest} 以單元測試釘住。
 *
 * <h2>為什麼要停 listener 而不是只看回應</h2>
 *
 * <p>{@code DeadLetterConsumer}（dlq）與 {@code WebhookConsumer}（目標佇列）
 * 都是活的：不停掉的話，測試放進去的訊息會被搶著消費，斷言
 * 「訊息出現在原佇列」變成看誰先跑 —— 間歇性紅燈。停掉之後
 * {@code rabbitTemplate.receive()} 是唯一消費者，斷言才有意義，
 * 並在 {@link #resumeListeners()} 保證復原（即使測試失敗）。
 */
class DlqAlertReplayTest extends IntegrationTestBase {

    @Autowired private RabbitTemplate rabbitTemplate;
    @Autowired private RabbitListenerEndpointRegistry listenerRegistry;

    /** 本測試停掉的 listener；@AfterEach 一律復原。 */
    private final Map<String, AbstractMessageListenerContainer> paused = new LinkedHashMap<>();

    @BeforeEach
    void resetSink() {
        WebhookTestSink.reset();
    }

    @AfterEach
    void resumeListeners() {
        // 先恢復 sink 的正常回應，再啟動 listener —— 否則殘留在佇列裡的
        // 訊息會被重試後又打回 DLQ，留給下一個測試。
        WebhookTestSink.reset();
        paused.values().forEach(c -> {
            if (!c.isRunning()) c.start();
        });
        paused.clear();
    }

    // ── 工具 ────────────────────────────────────────────────────────

    private void pauseListener(String queue) {
        AbstractMessageListenerContainer container = listenerRegistry.getListenerContainers().stream()
                .filter(AbstractMessageListenerContainer.class::isInstance)
                .map(AbstractMessageListenerContainer.class::cast)
                .filter(c -> Arrays.asList(c.getQueueNames()).contains(queue))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "找不到 " + queue + " 的 listener container："
                                + listenerRegistry.getListenerContainerIds()));
        container.stop();
        paused.put(queue, container);
    }

    /** 清掉佇列裡既有殘留，讓斷言只看到本測試的訊息。 */
    private void drain(String queue) {
        while (rabbitTemplate.receive(queue, 100) != null) {
            // 丟棄：不是本測試的證據。
        }
    }

    private long messageCount(String queue) {
        Long count = rabbitTemplate.execute(
                channel -> (long) channel.queueDeclarePassive(queue).getMessageCount());
        return count == null ? -1 : count;
    }

    private void awaitMessageCount(String queue, int atLeast) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(40));
        while (Instant.now().isBefore(deadline)) {
            if (messageCount(queue) >= atLeast) return;
            sleep(200);
        }
        assertThat(messageCount(queue))
                .as("等待 40 秒後 %s 仍未達 %d 筆（webhook retry 耗盡才會有死信）", queue, atLeast)
                .isGreaterThanOrEqualTo(atLeast);
    }

    private String sinkUrl(String name) {
        // 主機名必須是 localhost（application-test.yml 的允許清單只列了它）。
        return "http://localhost:" + SERVLET_PORT + "/mock/test-webhook-sink/" + name;
    }

    /** 經 bpm.exchange 發一則會投遞失敗的 webhook 事件（真實死信路徑的起點）。 */
    private void publishWebhook(String name, String businessKey, String event,
                                Map<String, Object> extra) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("event", event);
        payload.put("businessKey", businessKey);
        payload.put("__webhookUrl", sinkUrl(name));
        payload.put("__webhookMethod", "POST");
        if (extra != null) payload.putAll(extra);
        rabbitTemplate.convertAndSend("bpm.exchange", "bpm.webhook." + name, payload);
    }

    /** 直接放一筆沒有 x-death 的訊息進 DLQ（測 fallback／max 用）。 */
    private void sendToDlqDirect(String routingKey, Map<String, Object> payload) {
        rabbitTemplate.convertAndSend("dlx.exchange", routingKey, payload);
    }

    private static String utf8(Message message) {
        return new String(message.getBody(), StandardCharsets.UTF_8);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private record AuditRow(String operatorId, String operatorSource, String detail) {
    }

    /** 等到稽核表出現符合條件的列（publishDetached 是同步寫入，但容許排程抖動）。 */
    private static AuditRow awaitAudit(String operationType, String detailContains) {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            AuditRow row = auditRow(operationType, detailContains);
            if (row != null) return row;
            sleep(100);
        }
        return auditRow(operationType, detailContains);
    }

    private static AuditRow auditRow(String operationType, String detailContains) {
        AtomicReference<AuditRow> out = new AtomicReference<>();
        withAuditConnection(c -> {
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT TOP 1 operator_id, operator_source, detail FROM bpm_audit_log "
                                 + "WHERE operation_type = '" + operationType + "' "
                                 + "AND detail LIKE '%" + detailContains + "%' "
                                 + "ORDER BY id DESC")) {
                if (rs.next()) {
                    out.set(new AuditRow(rs.getString(1), rs.getString(2), rs.getString(3)));
                }
            }
        });
        return out.get();
    }

    // ── 重放：真實死信路徑 ─────────────────────────────────────────

    @Test
    @DisplayName("重放：真實 x-death 的 exchange／routing key 回原始佇列、DLQ 清空、寫 DLQ_REPLAY 稽核")
    void replayRedeliversToOriginalDestination() throws Exception {
        truncateAuditLog();
        pauseListener("dlq.bpm");
        drain("dlq.bpm");

        // 讓 webhook 投遞持續失敗：retry 耗盡 → broker 加上 x-death → dlq.bpm。
        // 這一則的 x-death 是 broker 產生的（queue=bpm.webhook.queue、
        // exchange=bpm.exchange、routing-keys=[bpm.webhook.replay-primary]）。
        WebhookTestSink.alwaysFail("replay-primary");
        publishWebhook("replay-primary", "REPLAY-PRIMARY-1", "task.create", null);
        awaitMessageCount("dlq.bpm", 1);

        // 死信已就位；停掉目標 listener 讓重放後的訊息留在原佇列供斷言。
        pauseListener("bpm.webhook.queue");
        drain("bpm.webhook.queue");

        mockMvc.perform(post("/api/admin/dlq/replay")
                        .header("X-User-Id", "admin001")
                        .param("queue", "bpm"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.queue").value("dlq.bpm"))
                .andExpect(jsonPath("$.replayed").value(1))
                .andExpect(jsonPath("$.failed").value(0))
                .andExpect(jsonPath("$.remaining").value(0));

        Message replayed = rabbitTemplate.receive("bpm.webhook.queue", 2000);
        assertThat(replayed)
                .as("重放後訊息必須出現在 x-death 指出的原始佇列（bpm.webhook.queue）")
                .isNotNull();
        assertThat(utf8(replayed)).contains("REPLAY-PRIMARY-1");
        assertThat(rabbitTemplate.receive("dlq.bpm", 200))
                .as("重放成功必須 ack，DLQ 應清空")
                .isNull();

        AuditRow row = awaitAudit("DLQ_REPLAY", "\"queue\":\"dlq.bpm\"");
        assertThat(row).as("重放必須留下 DLQ_REPLAY 稽核").isNotNull();
        assertThat(row.operatorId()).isEqualTo("admin001");
        assertThat(row.operatorSource()).isEqualTo("user");
        assertThat(row.detail())
                .as("真實 x-death 必須走 x-death 路徑（fallbackUsed=0），而不是猜的")
                .contains("\"replayed\":1")
                .contains("\"fallbackUsed\":0");
    }

    // ── 重放：x-death 缺失的 fallback ───────────────────────────────

    @Test
    @DisplayName("無 x-death fallback：payload 帶 __webhookUrl → bpm.webhook.queue，並記 WARN")
    void fallbackWithoutXDeathRoutesWebhookByPayload() throws Exception {
        pauseListener("dlq.bpm");
        pauseListener("bpm.webhook.queue");
        drain("dlq.bpm");
        drain("bpm.webhook.queue");

        Logger serviceLogger = (Logger) LoggerFactory.getLogger(DlqReplayService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        serviceLogger.addAppender(appender);
        try {
            sendToDlqDirect("bpm", Map.of(
                    "event", "task.create",
                    "__webhookUrl", "http://localhost:1/hook",
                    "businessKey", "REPLAY-FALLBACK-WEBHOOK-1"));

            mockMvc.perform(post("/api/admin/dlq/replay")
                            .header("X-User-Id", "admin001")
                            .param("queue", "bpm"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.replayed").value(1));

            assertThat(appender.list)
                    .as("fallback 必須留下可分辨的 WARN（正常重放不會有這行）")
                    .anyMatch(e -> e.getLevel() == Level.WARN
                            && e.getFormattedMessage().contains("沒有 x-death"));

            Message replayed = rabbitTemplate.receive("bpm.webhook.queue", 2000);
            assertThat(replayed)
                    .as("有 __webhookUrl 的訊息 fallback 應回 bpm.webhook.queue")
                    .isNotNull();
            assertThat(utf8(replayed)).contains("REPLAY-FALLBACK-WEBHOOK-1");
        } finally {
            serviceLogger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    @DisplayName("無 x-death fallback：沒有 __webhookUrl → bpm.notify.queue")
    void fallbackWithoutXDeathRoutesNotifyByPayload() throws Exception {
        pauseListener("dlq.bpm");
        pauseListener("bpm.notify.queue");
        drain("dlq.bpm");
        drain("bpm.notify.queue");

        sendToDlqDirect("bpm", Map.of(
                "event", "task_assigned",
                "assignee", "user001",
                "businessKey", "REPLAY-FALLBACK-NOTIFY-1"));

        mockMvc.perform(post("/api/admin/dlq/replay")
                        .header("X-User-Id", "admin001")
                        .param("queue", "bpm"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(1));

        Message replayed = rabbitTemplate.receive("bpm.notify.queue", 2000);
        assertThat(replayed)
                .as("沒有 __webhookUrl 的訊息 fallback 應回 bpm.notify.queue")
                .isNotNull();
        assertThat(utf8(replayed)).contains("REPLAY-FALLBACK-NOTIFY-1");

        // 清掉重放的訊息：否則 listener 復原後 EmailConsumer 會失敗重試
        // 再打回 DLQ，干擾下一個測試。
        drain("bpm.notify.queue");
    }

    // ── max／remaining ─────────────────────────────────────────────

    @Test
    @DisplayName("max 是硬上限：3 筆只放 2 筆，remaining 回報 1")
    void maxLimitsReplayAndReportsRemaining() throws Exception {
        pauseListener("dlq.bpm");
        pauseListener("bpm.webhook.queue");
        drain("dlq.bpm");
        drain("bpm.webhook.queue");

        for (int i = 1; i <= 3; i++) {
            sendToDlqDirect("bpm", Map.of(
                    "event", "task.create",
                    "__webhookUrl", "http://localhost:1/hook-" + i,
                    "businessKey", "REPLAY-MAX-" + i));
        }
        awaitMessageCount("dlq.bpm", 3);

        mockMvc.perform(post("/api/admin/dlq/replay")
                        .header("X-User-Id", "admin001")
                        .param("queue", "bpm")
                        .param("max", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(2))
                .andExpect(jsonPath("$.failed").value(0))
                .andExpect(jsonPath("$.remaining").value(1));

        assertThat(rabbitTemplate.receive("bpm.webhook.queue", 2000)).isNotNull();
        assertThat(rabbitTemplate.receive("bpm.webhook.queue", 2000)).isNotNull();
        assertThat(rabbitTemplate.receive("bpm.webhook.queue", 200)).isNull();
        assertThat(messageCount("dlq.bpm"))
                .as("max 之外的 1 筆必須留在 DLQ")
                .isEqualTo(1);
        drain("dlq.bpm");
    }

    // ── 權限與參數 ─────────────────────────────────────────────────

    @Test
    @DisplayName("未登入 401；一般使用者 403；queue 非白名單／max 超界 400；audit 空佇列可重放")
    void authorizationAndValidation() throws Exception {
        // 未登入：蓋掉 defaultRequest 的閘道身分。
        mockMvc.perform(post("/api/admin/dlq/replay")
                        .header(GatewayAuthenticationFilter.SECRET_HEADER, "")
                        .header(GatewayAuthenticationFilter.USER_HEADER, "")
                        .param("queue", "bpm"))
                .andExpect(status().isUnauthorized());

        // user001 在權限 fixture 裡沒有任何權限碼。
        mockMvc.perform(post("/api/admin/dlq/replay")
                        .header("X-User-Id", "user001")
                        .param("queue", "bpm"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/admin/dlq/replay")
                        .header("X-User-Id", "admin001")
                        .param("queue", "foo"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/admin/dlq/replay")
                        .header("X-User-Id", "admin001")
                        .param("queue", "bpm")
                        .param("max", "0"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/admin/dlq/replay")
                        .header("X-User-Id", "admin001")
                        .param("queue", "bpm")
                        .param("max", "1001"))
                .andExpect(status().isBadRequest());

        // audit 佇列（先清空）走完整端點路徑，回 0 筆而不是錯誤。
        pauseListener("dlq.audit");
        drain("dlq.audit");
        mockMvc.perform(post("/api/admin/dlq/replay")
                        .header("X-User-Id", "admin001")
                        .param("queue", "audit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.queue").value("dlq.audit"))
                .andExpect(jsonPath("$.replayed").value(0));
    }

    // ── 告警 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("DLQ 進來：ERROR log 留 payload，稽核寫 DLQ_MESSAGE（含真實 x-death 摘要）但不含 payload")
    void deadLetterWritesAuditWithoutPayloadContent() {
        truncateAuditLog();

        String sinkName = "alert-" + UUID.randomUUID();
        String secret = "SECRET-PAYLOAD-DO-NOT-AUDIT-" + UUID.randomUUID();
        String event = "dlq-alert-test-" + UUID.randomUUID();

        Logger consumerLogger = (Logger) LoggerFactory.getLogger(DeadLetterConsumer.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        consumerLogger.addAppender(appender);
        try {
            // dlq.bpm 的 listener 沒有停：死信一進 DLQ 就被 DeadLetterConsumer 消費。
            WebhookTestSink.alwaysFail(sinkName);
            publishWebhook(sinkName, "REPLAY-ALERT-1", event, Map.of("secret", secret));

            // 先等 log：它證明 consumer 真的處理了這筆（而不是訊息沒到，
            // 讓後面「稽核有寫」的斷言變成假綠）。
            long deadline = System.currentTimeMillis() + 30_000;
            while (System.currentTimeMillis() < deadline
                    && appender.list.stream().noneMatch(e -> e.getLevel() == Level.ERROR
                            && e.getFormattedMessage().contains(secret))) {
                sleep(200);
            }
            assertThat(appender.list)
                    .as("payload 全文仍照既有行為留在 ERROR log（截斷 2000 字）")
                    .anyMatch(e -> e.getLevel() == Level.ERROR
                            && e.getFormattedMessage().contains(secret));

            AuditRow row = awaitAudit("DLQ_MESSAGE", event);
            assertThat(row).as("DLQ 訊息必須寫一筆 DLQ_MESSAGE 稽核").isNotNull();
            assertThat(row.operatorId()).isEqualTo("system");
            assertThat(row.operatorSource()).isEqualTo("engine");
            assertThat(row.detail())
                    .contains("\"queue\":\"dlq.bpm\"")
                    .contains("\"event\":\"" + event + "\"")
                    .as("x-death 摘要必須指出訊息從哪來")
                    .contains("bpm.webhook.queue")
                    .as("稽核不得複製 payload 內容")
                    .doesNotContain(secret);
        } finally {
            consumerLogger.detachAppender(appender);
            appender.stop();
        }
    }
}
