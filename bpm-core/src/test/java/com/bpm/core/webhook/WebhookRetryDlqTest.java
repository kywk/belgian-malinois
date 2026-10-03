package com.bpm.core.webhook;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import org.springframework.boot.amqp.autoconfigure.RabbitProperties;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #26／#27 的收尾驗證：失敗重試與死信<b>真的會發生</b>（2026-10-02）。
 *
 * <h2>為什麼既有測試證明不了重試</h2>
 *
 * <p>#67 的端到端測試證明的是「成功投遞會發生」。而重試與 DLQ 只在
 * <b>接收端失敗</b>時才存在 —— {@code WebhookTestSink} 原本永遠回 200，
 * 所以整條失敗路徑（throw → listener retry → 重試耗盡 → {@code dlq.bpm}）
 * 在測試層從來沒有被走過一次。backlog #26／#27 的「已實作」因此
 * 只被讀碼支持，沒有被執行支持。
 *
 * <h2>重試參數從 {@link RabbitProperties} 讀，不寫死</h2>
 *
 * <p>application.yml 的設定是 {@code initial-interval: 1000}、
 * {@code multiplier: 2.0}、{@code max-retries: 2}（Spring Framework 的語意：
 * 總嘗試 = 1 次初始 + max-retries = 3，等同升級前的 {@code max-attempts: 3}）。
 * 測試若把 1000／2000／3 寫死，改設定時測試會與實作一起漂移；這裡改讀
 * Spring 綁定後的實際值，證明的是「執行期行為對得上執行期設定」。
 *
 * <h2>⚠️ 為什麼要暫停 dlq.bpm 的 listener</h2>
 *
 * <p>{@code DeadLetterConsumer} 有一個 {@code @RabbitListener(queues = "dlq.bpm")}，
 * 會立刻把死信吃掉並記 log。不停掉它的話，訊息在測試有機會 {@code receive()}
 * 之前就被競爭消費掉了 —— 測試會變成間歇性紅燈。停掉之後直接從
 * {@code dlq.bpm} 取，並在 {@link #restartDlqListener()} 保證復原
 * （即使測試失敗）。
 *
 * <h2>⚠️ 為什麼需要「等下一次 backoff 再加餘裕」</h2>
 *
 * <p>「只嘗試 3 次」不能只看「第 3 次之後有沒有第 4 次」的即時快照 ——
 * 若 max-retries 被誤設成 3（總嘗試變 4），第 4 次會在最後一次失敗後的
 * 下一個 backoff（initial×multiplier^maxRetries）才出現。所以斷言前要多等
 * 一個完整的 backoff 週期，否則「3 次」的斷言會在錯誤的設定下照樣綠。
 */
class WebhookRetryDlqTest extends IntegrationTestBase {

    @Autowired private RabbitTemplate rabbitTemplate;
    @Autowired private RabbitListenerEndpointRegistry listenerRegistry;
    @Autowired private RabbitProperties rabbitProperties;

    private AbstractMessageListenerContainer dlqListener;
    private boolean stoppedDlqListener;

    @BeforeEach
    void resetSink() {
        WebhookTestSink.reset();
    }

    @AfterEach
    void restartDlqListener() {
        if (stoppedDlqListener && dlqListener != null && !dlqListener.isRunning()) {
            dlqListener.start();
        }
        stoppedDlqListener = false;
        dlqListener = null;
    }

    // ── 工具 ────────────────────────────────────────────────────────

    private String sinkUrl(String name) {
        // 主機名必須是 localhost（application-test.yml 的允許清單只列了它）。
        return "http://localhost:" + SERVLET_PORT + "/mock/test-webhook-sink/" + name;
    }

    private void publish(String routingSuffix, String url, String businessKey) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("event", "task.create");
        payload.put("businessKey", businessKey);
        payload.put("__webhookUrl", url);
        payload.put("__webhookMethod", "POST");
        rabbitTemplate.convertAndSend("bpm.exchange", "bpm.webhook." + routingSuffix, payload);
    }

    private void awaitAttempts(String name, int attempts) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
        while (Instant.now().isBefore(deadline)) {
            if (WebhookTestSink.receivedTo(name).size() >= attempts) return;
            sleep(50);
        }
        assertThat(WebhookTestSink.receivedTo(name))
                .as("等待 20 秒後仍未達到 %d 次嘗試：%s", attempts, WebhookTestSink.receivedTo(name))
                .hasSizeGreaterThanOrEqualTo(attempts);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 總嘗試次數。
     *
     * <p>⚠️ Spring AMQP 4 的重試底層換成 Spring Framework 的 {@code RetryPolicy}，
     * 語意是「總嘗試 = 1 次初始 + max-retries」—— Boot 4 已移除 max-attempts。
     * application.yml 設 {@code max-retries: 2}，因此總嘗試次數是 3，
     * 與升級前 {@code max-attempts: 3} 的行為一致。
     */
    private int totalAttempts() {
        return (int) (rabbitProperties.getListener().getSimple().getRetry().getMaxRetries() + 1);
    }

    /**
     * 停掉 dlq.bpm 的 listener 並清空佇列中既有的死信。
     *
     * <p>不清空的話，「找不到本測試的訊息」無法判讀 —— 佇列裡本來就可能有
     * 其他測試留下的死信。停掉 listener 則是為了不讓 {@code DeadLetterConsumer}
     * 與測試搶著消費同一筆訊息。
     */
    private void stopDlqListenerAndDrain() {
        dlqListener = listenerRegistry.getListenerContainers().stream()
                .filter(AbstractMessageListenerContainer.class::isInstance)
                .map(AbstractMessageListenerContainer.class::cast)
                .filter(c -> Arrays.asList(c.getQueueNames()).contains("dlq.bpm"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "找不到 dlq.bpm 的 listener container："
                                + listenerRegistry.getListenerContainerIds()));
        dlqListener.stop();
        stoppedDlqListener = true;

        // 先 drain 既有死信（其他測試或先前案例留下的），再看新訊息。
        while (rabbitTemplate.receive("dlq.bpm", 100) != null) {
            // 丟棄：不是本測試的證據。
        }
    }

    /** 從 dlq.bpm 取出含指定 businessKey 的死信；其餘訊息丟棄。 */
    private Message awaitDeadLetter(String businessKey, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            Message message = rabbitTemplate.receive("dlq.bpm", 500);
            if (message == null) continue;
            Object body = rabbitTemplate.getMessageConverter().fromMessage(message);
            if (body instanceof Map<?, ?> map && businessKey.equals(map.get("businessKey"))) {
                return message;
            }
        }
        return null;
    }

    /** 重試耗盡所需的時間：max-retries 個 backoff（initial×multiplier^k）之和。 */
    private long retryWindowMillis() {
        var retry = rabbitProperties.getListener().getSimple().getRetry();
        long window = 0;
        for (int k = 0; k < retry.getMaxRetries(); k++) {
            window += (long) (retry.getInitialInterval().toMillis() * Math.pow(retry.getMultiplier(), k));
        }
        return window;
    }

    // ── 測試 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("暫時性失敗會重試：失敗 2 次後成功，次數與間隔對得上設定")
    void transientFailureIsRetriedWithConfiguredBackoff() {
        var retry = rabbitProperties.getListener().getSimple().getRetry();
        assertThat(retry.isEnabled())
                .as("這個測試的前提是 application.yml 啟用了 listener retry")
                .isTrue();

        WebhookTestSink.failFirst("retry-ok", 2);
        publish("retry-ok", sinkUrl("retry-ok"), "RETRY-OK-1");

        awaitAttempts("retry-ok", totalAttempts());
        List<WebhookTestSink.Received> attempts = WebhookTestSink.receivedTo("retry-ok");

        assertThat(attempts)
                .as("max-retries=%d 代表總共 %d 次嘗試（1 次初始 + %d 次重試），實測 %s",
                        retry.getMaxRetries(), totalAttempts(), retry.getMaxRetries(), attempts)
                .hasSize(totalAttempts());
        assertThat(attempts)
                .extracting(WebhookTestSink.Received::status)
                .as("前兩次是 sink 注入的 500，最後一次必須成功 —— 失敗後沒有重試的話只會有一筆 500")
                .containsExactly(500, 500, 200);

        long gap1 = Duration.between(attempts.get(0).receivedAt(), attempts.get(1).receivedAt()).toMillis();
        long gap2 = Duration.between(attempts.get(1).receivedAt(), attempts.get(2).receivedAt()).toMillis();
        long expectedGap1 = retry.getInitialInterval().toMillis();
        long expectedGap2 = (long) (retry.getInitialInterval().toMillis() * retry.getMultiplier());

        // 上下界刻意寬：排程與 HTTP 往返會有抖動，但「完全沒有 backoff」（gap≈0）
        // 或「設定值沒被套用」（例如 multiplier 失效，gap2≈gap1）仍然會紅。
        assertThat(gap1)
                .as("第 1→2 次嘗試的間隔應約為 initial-interval=%dms（實測 %dms）", expectedGap1, gap1)
                .isBetween(expectedGap1 * 7 / 10, expectedGap1 * 2 + 3000);
        assertThat(gap2)
                .as("第 2→3 次嘗試的間隔應約為 initial-interval×multiplier=%dms（實測 %dms）",
                        expectedGap2, gap2)
                .isBetween(expectedGap2 * 7 / 10, expectedGap2 * 2 + 3000);
    }

    @Test
    @DisplayName("持續失敗：重試 max-retries 次後進 dlq.bpm，且不再嘗試")
    void persistentFailureEndsInDlqAfterMaxAttempts() {
        var retry = rabbitProperties.getListener().getSimple().getRetry();
        stopDlqListenerAndDrain();

        WebhookTestSink.alwaysFail("retry-dlq");
        publish("retry-dlq", sinkUrl("retry-dlq"), "RETRY-DLQ-1");

        awaitAttempts("retry-dlq", totalAttempts());

        Message dead = awaitDeadLetter("RETRY-DLQ-1", Duration.ofSeconds(15));
        assertThat(dead)
                .as("重試耗盡後必須進 dlq.bpm；實際 sink 嘗試：%s", WebhookTestSink.receivedTo("retry-dlq"))
                .isNotNull();

        Object converted = rabbitTemplate.getMessageConverter().fromMessage(dead);
        assertThat(converted).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> deadPayload = (Map<String, Object>) converted;
        assertThat(deadPayload)
                .as("死信必須保留原始投遞資訊，否則人工重放時不知道要送去哪")
                .containsEntry("businessKey", "RETRY-DLQ-1")
                .containsEntry("__webhookUrl", sinkUrl("retry-dlq"))
                .containsEntry("event", "task.create");

        // x-death 證明它是被 bpm.webhook.queue 以 rejected 丟到 DLX，
        // 而不是被別條路徑或人工塞進 dlq.bpm。
        List<Map<String, ?>> xDeath = dead.getMessageProperties().getXDeathHeader();
        assertThat(xDeath).as("死信必須帶 x-death 標頭").isNotNull().isNotEmpty();
        @SuppressWarnings("unchecked")
        Map<String, Object> firstDeath = (Map<String, Object>) xDeath.get(0);
        assertThat(firstDeath)
                .as("x-death 必須指出來源是 bpm.webhook.queue、原因是 rejected（非 requeue 或 expire）")
                .containsEntry("queue", "bpm.webhook.queue")
                .containsEntry("reason", "rejected");

        // 不會有第 4 次嘗試：等下一次 backoff（initial×multiplier^maxRetries）再加餘裕。
        // 少了這一段，「max-retries 被誤設成 3（總嘗試 4）」時這個測試會在 size 3 的瞬間照樣綠。
        long nextBackoff = (long) (retry.getInitialInterval().toMillis()
                * Math.pow(retry.getMultiplier(), retry.getMaxRetries()));
        sleep(nextBackoff + 1000);
        assertThat(WebhookTestSink.receivedTo("retry-dlq"))
                .as("重試耗盡後不得再嘗試（實測 %s）", WebhookTestSink.receivedTo("retry-dlq"))
                .hasSize(totalAttempts());
        assertThat(WebhookTestSink.receivedTo("retry-dlq"))
                .extracting(WebhookTestSink.Received::status)
                .containsOnly(500);
    }

    @Test
    @DisplayName("⚠️ SSRF 拒絕：log ERROR、不重試、不進 DLQ")
    void ssrfRejectionIsNotRetriedAndNotDeadLettered() {
        var retry = rabbitProperties.getListener().getSimple().getRetry();
        stopDlqListenerAndDrain();

        // 127.0.0.1 不在允許清單（application-test.yml 只列 localhost）→ 閘門拒絕。
        // 若閘門壞掉，這個 URL 其實打得到測試自己的 sink，所以 sink 端也驗得到；
        // 而若拒絕改成「拋例外」，重試窗內會有 3 次嘗試並在 dlq.bpm 留下一筆。
        Logger consumerLogger = (Logger) LoggerFactory.getLogger(WebhookConsumer.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        consumerLogger.addAppender(appender);
        try {
            publish("ssrf-no-retry",
                    "http://127.0.0.1:" + SERVLET_PORT + "/mock/test-webhook-sink/ssrf-no-retry",
                    "SSRF-NO-RETRY-1");

            // 先等 log：這證明 consumer 真的處理了這筆訊息（而不是訊息根本沒到，
            // 讓後面「什麼都沒有」的斷言變成假綠）。
            Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
            while (Instant.now().isBefore(deadline)
                    && appender.list.stream().noneMatch(e -> e.getLevel() == Level.ERROR
                            && e.getFormattedMessage().contains("Webhook 目標位址被拒絕"))) {
                sleep(50);
            }
            assertThat(appender.list)
                    .as("SSRF 拒絕必須留下 ERROR 級別的 log（該事件不投遞是刻意的，但必須可見）")
                    .anyMatch(e -> e.getLevel() == Level.ERROR
                            && e.getFormattedMessage().contains("Webhook 目標位址被拒絕"));

            // 完整重試窗（1s+2s）再加餘裕。若 rejection 是「拋例外」而非 return，
            // 這裡會看到 3 次 HTTP 嘗試與一筆死信。
            sleep(retryWindowMillis() + 3000);

            assertThat(WebhookTestSink.receivedTo("ssrf-no-retry"))
                    .as("SSRF 閘門必須在送出前攔下 —— 不得有任何 HTTP 嘗試")
                    .isEmpty();
            assertThat(awaitDeadLetter("SSRF-NO-RETRY-1", Duration.ofMillis(500)))
                    .as("被 SSRF 閘門拒絕不是暫時性失敗：不重試，也不該進 DLQ")
                    .isNull();
        } finally {
            consumerLogger.detachAppender(appender);
            appender.stop();
        }
    }
}
