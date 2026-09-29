package com.bpm.core.audit.service;

import com.bpm.core.audit.consumer.AuditEventConsumer;
import com.bpm.core.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Statement;
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
 */
class AuditDeliveryTest extends IntegrationTestBase {

    @Autowired
    private AuditEventConsumer consumer;

    @Autowired
    private AuditLogService auditLogService;

    @BeforeEach
    void clean() {
        truncateAuditLog();
    }

    private static Map<String, Object> event(String eventId) {
        Map<String, Object> m = new HashMap<>();
        m.put("operationType", "TASK_APPROVE");
        m.put("operatorId", "mgr001");
        m.put("processInstanceId", "proc-1");
        m.put("taskId", "task-1");
        m.put("timestamp", "2026-09-28T10:00:00Z");
        if (eventId != null) m.put("eventId", eventId);
        return m;
    }

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

    @Test
    @DisplayName("broker 重投同一筆事件時不得重複寫入（幂等）")
    void redeliveryIsIdempotent() {
        consumer.handle(event("evt-001"));
        assertThat(rowCount()).isEqualTo(1);

        // 模擬 broker 重投：完全相同的訊息再來一次
        consumer.handle(event("evt-001"));
        consumer.handle(event("evt-001"));

        assertThat(rowCount())
                .as("重複 append 會在 hash chain 多出節點，而該鏈在數學上完全合法 "
                        + "→ integrityCheck 察覺不到。必須在寫入前去重")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("不同 eventId 的事件必須都寫入")
    void distinctEventsAreAllStored() {
        consumer.handle(event("evt-001"));
        consumer.handle(event("evt-002"));
        consumer.handle(event("evt-003"));
        assertThat(rowCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("去重之後 hash chain 必須仍然完整")
    void chainStaysIntactAfterDeduplication() {
        consumer.handle(event("evt-001"));
        consumer.handle(event("evt-001"));   // 重投
        consumer.handle(event("evt-002"));

        var r = auditLogService.integrityCheck(
                java.time.Instant.parse("2020-01-01T00:00:00Z"),
                java.time.Instant.parse("2030-01-01T00:00:00Z"));
        assertThat(r.get("checked")).isEqualTo(2);
        assertThat(r.get("broken")).isEqualTo(0);
        assertThat(r.get("intact")).isEqualTo(true);
    }

    @Test
    @DisplayName("沒有 eventId 的事件仍須寫入（in-process 路徑不經 broker）")
    void eventsWithoutIdStillStored() {
        consumer.handle(event(null));
        assertThat(rowCount()).isEqualTo(1);
    }
}
