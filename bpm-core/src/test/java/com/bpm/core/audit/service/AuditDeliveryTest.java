package com.bpm.core.audit.service;

import com.bpm.core.audit.consumer.AuditEventConsumer;
import com.bpm.core.support.IntegrationTestBase;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.listener.AbstractMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 稽核事件的投遞可靠性（security-audit P1-14）。
 *
 * <p>三個問題：
 * <ul>
 *   <li>{@code @Async} 未指定 executor → 用 Boot 預設的
 *       {@code applicationTaskExecutor}，佇列容量 {@code Integer.MAX_VALUE}
 *       → 稽核 DB 變慢時事件無上限堆在 heap，重啟／OOM 全部遺失，
 *       而業務操作早已回 200。
 *       （2026-09-29 起稽核改為同步 fail-closed，此項已不存在，
 *       見 {@code AuditFailClosedTest}。）</li>
 *   <li>{@code dlq.audit} / {@code dlq.bpm} 沒有任何 consumer、沒有告警
 *       → 沉進死信的稽核永遠沒人知道。</li>
 *   <li>{@code AuditEventConsumer} 無幂等鍵 → broker 重投會 append 兩次，
 *       而重複節點產生的 hash chain 在數學上完全合法 →
 *       <b>integrityCheck 察覺不到</b>。</li>
 * </ul>
 *
 * <h2>背景訊息隔離（2026-10-04 flake 修復）</h2>
 *
 * <p>本類別的斷言是「精確列數」，而 {@code audit.log.queue} 的
 * {@code @RabbitListener} 會把<b>其他測試類別</b>留下的背景訊息寫進同一張表。
 * 舊版在每個測試前後 stop／start 該 listener，仍會在全套件偶發
 * {@code expected 1 but was 2}。根因有兩層：
 *
 * <ol>
 *   <li><b>stop() 不等 consumer thread 結束。</b>
 *       {@code SimpleMessageListenerContainer.stop()} 只等到「正在處理的那一筆」
 *       完成（shutdownTimeout），prefetched／未 ack 的訊息是 consumer thread
 *       退出時關閉 channel 才由 broker 非同步歸還佇列 —— 可能落在
 *       {@code drainAuditQueue()} 之後。舊版 {@code @AfterEach} 緊接著 start()，
 *       於是把剛歸還的訊息在下一個測試的 truncate 之後才寫進表。</li>
 *   <li><b>start()／stop() 之間的窗口。</b>舊版每個測試都重新 start，
 *       歸還的訊息會在測試之間被消費；其寫入可能晚於下一個測試的 truncate。</li>
 * </ol>
 *
 * <p>修法（確定性，不用 sleep 賭）：
 * <ul>
 *   <li>{@code @TestInstance(PER_CLASS)} ＋ {@code @BeforeAll}：<b>整個類別期間
 *       保持 listener 停止</b>，類別內不再有 start／stop 窗口 —— 這才是隔離的
 *       主體。類別結束才恢復。</li>
 *   <li>停止後以 {@link AmqpAdmin} purge ＋ 輪詢「連續兩次佇列為 0」：
 *       只為把非同步歸還的殘留清乾淨，讓 {@code @AfterAll} 恢復 listener 時
 *       不會立刻把別人的舊訊息消費進來。輪詢而非固定等待的理由：歸還是
 *       非同步事件，時間不可知；而佇列在 listener 停止後只會因歸還而增加，
 *       連續兩次 0 已足以涵蓋它。</li>
 *   <li>斷言本身再以 {@code event_id}／{@code process_instance_id} 限定範圍。
 *       即使 shutdownTimeout 逾時這種極端情況留下遲到的 handler 寫入，
 *       也不會污染精確列數斷言 —— 隔離與斷言各有一層，不互相依賴。</li>
 * </ul>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuditDeliveryTest extends IntegrationTestBase {

    @Autowired
    private AuditEventConsumer consumer;

    @Autowired
    private AuditLogService auditLogService;

    @Autowired
    private RabbitListenerEndpointRegistry listenerRegistry;

    @Autowired
    private AmqpAdmin amqpAdmin;

    /** 本測試類別停用的 listener（@AfterAll 復原）。 */
    private AbstractMessageListenerContainer pausedAuditListener;

    @BeforeAll
    void pauseAuditListenerForTheWholeClass() {
        // ── 為什麼是「整個類別」而不是每個測試前後 ────────────────────
        // 見類別 javadoc。簡言之：每次 stop 後被非同步歸還的訊息，
        // 只要類別內還有一次 start，就有機會在下一個測試的 truncate
        // 之後才落地。整個類別停著，就沒有那個窗口。
        pausedAuditListener = listenerRegistry.getListenerContainers().stream()
                .filter(AbstractMessageListenerContainer.class::isInstance)
                .map(AbstractMessageListenerContainer.class::cast)
                .filter(c -> Arrays.asList(c.getQueueNames()).contains("audit.log.queue"))
                .findFirst()
                .orElse(null);
        if (pausedAuditListener != null) {
            pausedAuditListener.stop();
        }
        purgeAuditQueueUntilStable();
        truncateAuditLog();
    }

    @BeforeEach
    void clean() {
        // listener 在整個類別期間都是停的，所以這裡只需要清掉上一個測試
        // 直接呼叫 consumer.handle() 寫入的列；不會有背景寫入進來。
        truncateAuditLog();
    }

    @AfterAll
    void resumeAuditListener() {
        // 先清殘留再恢復 —— 否則恢復後會把別人的舊訊息寫進來，留給下一個類別。
        purgeAuditQueueUntilStable();
        if (pausedAuditListener != null && !pausedAuditListener.isRunning()) {
            pausedAuditListener.start();
        }
        pausedAuditListener = null;
    }

    /**
     * 清空 {@code audit.log.queue} 並確認歸還已結束。
     *
     * <p>stop() 之後，consumer thread 的 finally 才關閉 channel，broker 才把
     * prefetched／未 ack 的訊息歸還佇列 —— 歸還時間不可知，且可能晚於一次
     * purge。這裡 purge 後輪詢訊息數，直到連續兩次讀到 0。listener 已停止，
     * 佇列只會因歸還而增加，不會有新的發布者，所以「連續 0」代表歸還結束。
     */
    private void purgeAuditQueueUntilStable() {
        int consecutiveEmpty = 0;
        long deadline = System.currentTimeMillis() + 10_000;
        while (consecutiveEmpty < 2 && System.currentTimeMillis() < deadline) {
            amqpAdmin.purgeQueue("audit.log.queue", false);
            var info = amqpAdmin.getQueueInfo("audit.log.queue");
            long count = info != null ? info.getMessageCount() : -1;
            consecutiveEmpty = (count == 0) ? consecutiveEmpty + 1 : 0;
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private static Map<String, Object> event(String eventId, String processInstanceId, String timestamp) {
        Map<String, Object> m = new HashMap<>();
        m.put("operationType", "TASK_APPROVE");
        m.put("operatorId", "mgr001");
        m.put("processInstanceId", processInstanceId);
        m.put("taskId", "task-1");
        m.put("timestamp", timestamp);
        if (eventId != null) m.put("eventId", eventId);
        return m;
    }

    /** 全部列數（只用在對照；精確斷言一律用限定範圍的版本）。 */
    private static int rowCount() {
        int[] n = {-1};
        withAuditConnection(c -> {
            try (Statement st = c.createStatement();
                 var rs = st.executeQuery("SELECT COUNT(*) FROM bpm_audit_log")) {
                rs.next();
                n[0] = rs.getInt(1);
            }
        });
        return n[0];
    }

    /**
     * 以 SQL 條件限定範圍的列數。
     *
     * <p>為什麼不只用 {@code COUNT(*)}：本類別與其他測試共用同一張表，
     * 精確列數的斷言必須只看到<b>本測試自己的</b>資料。範圍由呼叫端給
     * （event_id 或 process_instance_id），值全部是測試自造的，不會誤中
     * 別人的列。
     */
    private static int rowCountWhere(String where, String... params) {
        int[] n = {-1};
        withAuditConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COUNT(*) FROM bpm_audit_log WHERE " + where)) {
                for (int i = 0; i < params.length; i++) {
                    ps.setString(i + 1, params[i]);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    n[0] = rs.getInt(1);
                }
            }
        });
        return n[0];
    }

    @Test
    @DisplayName("broker 重投同一筆事件時不得重複寫入（幂等）")
    void redeliveryIsIdempotent() {
        consumer.handle(event("evt-001", "proc-idem", "2026-09-28T10:00:00Z"));
        assertThat(rowCountWhere("event_id = ?", "evt-001")).isEqualTo(1);

        // 模擬 broker 重投：完全相同的訊息再來一次
        consumer.handle(event("evt-001", "proc-idem", "2026-09-28T10:00:00Z"));
        consumer.handle(event("evt-001", "proc-idem", "2026-09-28T10:00:00Z"));

        assertThat(rowCountWhere("event_id = ?", "evt-001"))
                .as("重複 append 會在 hash chain 多出節點，而該鏈在數學上完全合法 "
                        + "→ integrityCheck 察覺不到。必須在寫入前去重")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("不同 eventId 的事件必須都寫入")
    void distinctEventsAreAllStored() {
        consumer.handle(event("evt-001", "proc-distinct", "2026-09-28T10:00:00Z"));
        consumer.handle(event("evt-002", "proc-distinct", "2026-09-28T10:00:01Z"));
        consumer.handle(event("evt-003", "proc-distinct", "2026-09-28T10:00:02Z"));
        assertThat(rowCountWhere(
                "event_id IN ('evt-001','evt-002','evt-003')")).isEqualTo(3);
    }

    @Test
    @DisplayName("去重之後 hash chain 必須仍然完整")
    void chainStaysIntactAfterDeduplication() {
        // 這兩個事件用固定且唯一的時間戳（2003 年），integrityCheck 的區間
        // 因此只涵蓋本測試的列 —— 與上面兩條同一層防護，避免背景列讓
        // checked 數不精確。
        String ts = "2003-03-03T03:03:03Z";
        consumer.handle(event("evt-001", "proc-chain", ts));
        consumer.handle(event("evt-001", "proc-chain", ts));   // 重投
        consumer.handle(event("evt-002", "proc-chain", ts));

        var r = auditLogService.integrityCheck(
                java.time.Instant.parse("2003-01-01T00:00:00Z"),
                java.time.Instant.parse("2004-01-01T00:00:00Z"));
        assertThat(r.get("checked")).isEqualTo(2);
        assertThat(r.get("broken")).isEqualTo(0);
        assertThat(r.get("intact")).isEqualTo(true);
    }

    @Test
    @DisplayName("沒有 eventId 的事件仍須寫入（in-process 路徑不經 broker）")
    void eventsWithoutIdStillStored() {
        // 沒有 eventId 就無法用 event_id 限定範圍，改用本測試自造的
        // processInstanceId —— 斷言的仍然是「這一筆被寫進去了」。
        consumer.handle(event(null, "proc-no-event-id", "2026-09-28T10:00:00Z"));
        assertThat(rowCountWhere("process_instance_id = ?", "proc-no-event-id")).isEqualTo(1);
        // 對照：整張表若多出別人的列，不影響上面的結論；這裡只是把
        // 「至少這一筆」與「精確一筆」的差異留在 log 裡，方便事故調查。
        assertThat(rowCount()).isGreaterThanOrEqualTo(1);
    }
}
