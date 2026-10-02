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
 * #51 的端到端驗證：DLQ 告警真的寫進稽核、訊息真的 parking、人工重放真的
 * 回到原始目的地。
 *
 * <h2>⚠️ x-death 不能用「手工組一筆訊息」製造</h2>
 *
 * <p>客戶端無法偽造 broker 產生的 {@code x-death}；RabbitMQ 3.13 起也
 * 不再把客戶端重發布的 x-death 當死信紀錄維護（實測本測試的
 * {@code rabbitmq:3-management}＝3.13.7：重發布後標頭還在、但 count
 * 不再累加；4.x 不再解讀）。所以「重放怎麼解讀 origin」的主要路徑在
 * 這裡是<b>走真實死信</b>：讓 webhook 投遞持續失敗
 * （{@code WebhookTestSink.alwaysFail}）、retry 耗盡後由 broker 加上
 * x-death 進 DLQ，consumer 告警後 parking，再重放。origin 標頭的優先序、
 * 多筆 x-death 的順序、dlx 防呆等形狀由
 * {@code DlqReplayServiceDestinationTest} 以單元測試釘住。
 *
 * <h2>為什麼要停 listener 而不是只看回應</h2>
 *
 * <p>{@code WebhookConsumer}（目標佇列）是活的：不停掉的話，測試放進去的
 * 訊息會被搶著消費，斷言「訊息出現在原佇列」變成看誰先跑 —— 間歇性紅燈。
 * 停掉之後 {@code rabbitTemplate.receive()} 是唯一消費者，斷言才有意義，
 * 並在 {@link #resumeListeners()} 保證復原（即使測試失敗）。
 *
 * <p>{@code DeadLetterConsumer}（dlq）在正常流程<b>不</b>停 —— 它正是
 * 「告警＋parking」的執行者。只有要模擬「服務中斷期間累積死信」時才停它。
 */
class DlqAlertReplayTest extends IntegrationTestBase {

    @Autowired private RabbitTemplate rabbitTemplate;
    @Autowired private RabbitListenerEndpointRegistry listenerRegistry;

    /** 本測試停掉的 listener；@AfterEach 一律復原。 */
    private final Map<String, AbstractMessageListenerContainer> paused = new LinkedHashMap<>();

    @BeforeEach
    void resetSink() {
        WebhookTestSink.reset();
        // ── 背景噪音隔離（2026-10-03 PM 收尾）─────────────────────────
        //
        // 其他測試類別產生的通知會被真實 EmailConsumer 消費，而測試環境
        // 的 SMTP 不通 → 重試後掉進 dlq.bpm → 本類別的 DeadLetterConsumer
        // 再 parking 進 dlq.parking.bpm。本類別的斷言全是「精確佇列計數」，
        // 一筆遲到的背景訊息就會讓 remaining／messageCount 間歇性多 1。
        //
        // 因此在整個類別期間停掉 EmailConsumer（paused 會在 @AfterEach
        // 復原），並清空它與兩層 DLQ 的殘留，讓斷言只看到本測試的訊息。
        pauseListener("bpm.notify.queue");
        drain("bpm.notify.queue");
        drain("dlq.bpm");
        drain("dlq.parking.bpm");
    }

    @AfterEach
    void resumeListeners() {
        // 先恢復 sink 的正常回應、清掉 notify 佇列殘留，再啟動 listener
        // —— 否則殘留在佇列裡的訊息會被重試後又打回 DLQ，留給下一個測試。
        WebhookTestSink.reset();
        drain("bpm.notify.queue");
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

    /** 恢復先前暫停的 listener（例如模擬服務恢復後 consumer 開始 parking）。 */
    private void resumeListener(String queue) {
        AbstractMessageListenerContainer container = paused.remove(queue);
        if (container != null && !container.isRunning()) container.start();
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

    /** 直接放一筆沒有 x-death 的訊息進 DLQ（測 fallback／legacy parking 用）。 */
    private void sendToDlqDirect(String routingKey, Map<String, Object> payload) {
        rabbitTemplate.convertAndSend("dlx.exchange", routingKey, payload);
    }

    /** 直接放一筆訊息進 parking queue（無 origin、無 x-death，測 fallback 用）。 */
    private void sendToParkingDirect(String parkingQueue, Map<String, Object> payload) {
        rabbitTemplate.convertAndSend(parkingQueue, payload);
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

    // ── 告警＋parking ───────────────────────────────────────────────

    @Test
    @DisplayName("真實死信：告警後訊息 parking（dlq.bpm 清空）、帶 origin 標頭、稽核不含 payload")
    void deadLetterIsAlertedAndParked() {
        truncateAuditLog();
        drain("dlq.bpm");
        drain("dlq.parking.bpm");

        String sinkName = "alert-" + UUID.randomUUID();
        String secret = "SECRET-PAYLOAD-DO-NOT-AUDIT-" + UUID.randomUUID();
        String event = "dlq-alert-test-" + UUID.randomUUID();

        Logger consumerLogger = (Logger) LoggerFactory.getLogger(DeadLetterConsumer.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        consumerLogger.addAppender(appender);
        try {
            // dlq.bpm 的 listener 沒有停：死信一進 DLQ 就被 DeadLetterConsumer 消費
            // （告警＋parking），這正是 production 的常態路徑。
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

            // parking：訊息必須留下來，且帶著 origin 標頭（重放端靠這組
            // 標頭回原始目的地，不依賴版本行為不一的 x-death）。
            awaitMessageCount("dlq.parking.bpm", 1);
            Message parked = rabbitTemplate.receive("dlq.parking.bpm", 2000);
            assertThat(parked).as("告警後訊息必須 parking 到 dlq.parking.bpm").isNotNull();
            assertThat(utf8(parked)).contains(secret);
            assertThat(parked.getMessageProperties().getHeaders().get(DlqReplayService.ORIGIN_EXCHANGE_HEADER))
                    .isEqualTo("bpm.exchange");
            assertThat(parked.getMessageProperties().getHeaders().get(DlqReplayService.ORIGIN_ROUTING_KEY_HEADER))
                    .isEqualTo("bpm.webhook." + sinkName);
            assertThat(parked.getMessageProperties().getHeaders().get(DlqReplayService.ORIGIN_QUEUE_HEADER))
                    .isEqualTo("bpm.webhook.queue");
            // 不 assert x-death 的存在或消失：實測 3.13.7 重發布後它仍在
            // （只是不再被 broker 更新），4.x 不再解讀 —— 版本行為不一，
            // 重放靠的是上面的 origin 標頭，不是它。

            assertThat(rabbitTemplate.receive("dlq.bpm", 200))
                    .as("parking 成功後原訊息必須 ack，dlq.bpm 應清空")
                    .isNull();
        } finally {
            consumerLogger.detachAppender(appender);
            appender.stop();
        }
    }

    // ── 重放：真實死信路徑 ─────────────────────────────────────────

    @Test
    @DisplayName("重放 parking：origin 標頭的 exchange／routing key 回原始佇列、parking 清空、寫 DLQ_REPLAY 稽核")
    void replayRedeliversToOriginalDestination() throws Exception {
        truncateAuditLog();
        drain("dlq.bpm");
        drain("dlq.parking.bpm");
        drain("bpm.webhook.queue");

        // 讓 webhook 投遞持續失敗：retry 耗盡 → broker 加上 x-death → dlq.bpm
        // → consumer 告警後 parking（origin 標頭＝bpm.exchange／bpm.webhook.replay-primary）。
        WebhookTestSink.alwaysFail("replay-primary");
        publishWebhook("replay-primary", "REPLAY-PRIMARY-1", "task.create", null);
        awaitMessageCount("dlq.parking.bpm", 1);

        // 停掉目標 listener 讓重放後的訊息留在原佇列供斷言。
        pauseListener("bpm.webhook.queue");
        drain("bpm.webhook.queue");

        mockMvc.perform(post("/api/admin/dlq/replay")
                        .header("X-User-Id", "admin001")
                        .param("queue", "bpm"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.queue").value("dlq.parking.bpm"))
                .andExpect(jsonPath("$.replayed").value(1))
                .andExpect(jsonPath("$.failed").value(0))
                .andExpect(jsonPath("$.remaining").value(0));

        Message replayed = rabbitTemplate.receive("bpm.webhook.queue", 2000);
        assertThat(replayed)
                .as("重放後訊息必須出現在 origin 標頭指出的原始佇列（bpm.webhook.queue）")
                .isNotNull();
        assertThat(utf8(replayed)).contains("REPLAY-PRIMARY-1");
        assertThat(rabbitTemplate.receive("dlq.parking.bpm", 200))
                .as("重放成功必須 ack，parking 應清空")
                .isNull();
        assertThat(rabbitTemplate.receive("dlq.bpm", 200))
                .as("重放不得把訊息丟回有 consumer 的舊 DLQ")
                .isNull();

        AuditRow row = awaitAudit("DLQ_REPLAY", "\"queue\":\"dlq.parking.bpm\"");
        assertThat(row).as("重放必須留下 DLQ_REPLAY 稽核").isNotNull();
        assertThat(row.operatorId()).isEqualTo("admin001");
        assertThat(row.operatorSource()).isEqualTo("user");
        assertThat(row.detail())
                .as("必須走正常路徑（origin 標頭；3.13 的 x-death 也可能還在），不是 fallback 猜的")
                .contains("\"replayed\":1")
                .contains("\"fallbackUsed\":0");
    }

    // ── 重放：legacy DLQ 的訊息 ─────────────────────────────────────

    @Test
    @DisplayName("consumer 停用期間累積在 dlq.bpm 的訊息：服務恢復後自動 parking，重放端只看 parking")
    void legacyDlqMessagesAreParkedWhenConsumerResumes() {
        pauseListener("dlq.bpm");
        drain("dlq.bpm");
        drain("dlq.parking.bpm");

        // 模擬服務中斷期間：死信進 dlq.bpm，但沒有 consumer 處理。
        sendToDlqDirect("bpm", Map.of(
                "event", "task.create",
                "__webhookUrl", "http://localhost:1/hook",
                "businessKey", "LEGACY-PARK-1"));
        awaitMessageCount("dlq.bpm", 1);
        assertThat(messageCount("dlq.parking.bpm"))
                .as("consumer 停用時不該有 parking")
                .isZero();

        // 服務恢復：consumer 消費舊 DLQ 的訊息 → 告警 → parking。
        resumeListener("dlq.bpm");
        awaitMessageCount("dlq.parking.bpm", 1);
        assertThat(messageCount("dlq.bpm"))
                .as("consumer 恢復後必須把 legacy 死信 parking 並 ack")
                .isZero();

        Message parked = rabbitTemplate.receive("dlq.parking.bpm", 2000);
        assertThat(parked).as("legacy 死信必須出現在 parking queue").isNotNull();
        assertThat(utf8(parked)).contains("LEGACY-PARK-1");
        assertThat(parked.getMessageProperties().getHeaders().get(DlqReplayService.ORIGIN_EXCHANGE_HEADER))
                .as("legacy 訊息沒有 x-death，重放會走 fallback，因此不該有 origin 標頭")
                .isNull();
    }

    // ── 重放：origin／x-death 缺失的 fallback ───────────────────────

    @Test
    @DisplayName("無 origin 也無 x-death fallback：payload 帶 __webhookUrl → bpm.webhook.queue，並記 WARN")
    void fallbackWithoutXDeathRoutesWebhookByPayload() throws Exception {
        pauseListener("bpm.webhook.queue");
        drain("dlq.parking.bpm");
        drain("bpm.webhook.queue");

        Logger serviceLogger = (Logger) LoggerFactory.getLogger(DlqReplayService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        serviceLogger.addAppender(appender);
        try {
            sendToParkingDirect("dlq.parking.bpm", Map.of(
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
                            && e.getFormattedMessage().contains("沒有 origin 標頭也沒有 x-death"));

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
    @DisplayName("無 origin 也無 x-death fallback：沒有 __webhookUrl → bpm.notify.queue")
    void fallbackWithoutXDeathRoutesNotifyByPayload() throws Exception {
        pauseListener("bpm.notify.queue");
        drain("dlq.parking.bpm");
        drain("bpm.notify.queue");

        sendToParkingDirect("dlq.parking.bpm", Map.of(
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
        pauseListener("bpm.webhook.queue");
        drain("dlq.parking.bpm");
        drain("bpm.webhook.queue");

        for (int i = 1; i <= 3; i++) {
            sendToParkingDirect("dlq.parking.bpm", Map.of(
                    "event", "task.create",
                    "__webhookUrl", "http://localhost:1/hook-" + i,
                    "businessKey", "REPLAY-MAX-" + i));
        }
        awaitMessageCount("dlq.parking.bpm", 3);

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
        assertThat(messageCount("dlq.parking.bpm"))
                .as("max 之外的 1 筆必須留在 parking")
                .isEqualTo(1);
        drain("dlq.parking.bpm");
    }

    // ── 權限與參數 ─────────────────────────────────────────────────

    @Test
    @DisplayName("未登入 401；一般使用者 403；queue 非白名單／max 超界 400；audit 空 parking 可重放")
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

        // audit parking（先清空）走完整端點路徑，回 0 筆而不是錯誤。
        drain("dlq.parking.audit");
        mockMvc.perform(post("/api/admin/dlq/replay")
                        .header("X-User-Id", "admin001")
                        .param("queue", "audit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.queue").value("dlq.parking.audit"))
                .andExpect(jsonPath("$.replayed").value(0));
    }
}
