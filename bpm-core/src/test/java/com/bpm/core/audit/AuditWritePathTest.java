package com.bpm.core.audit;

import com.bpm.core.audit.model.AuditLog;
import com.bpm.core.audit.model.OperationType;
import com.bpm.core.audit.service.AuditLogService;
import com.bpm.core.dto.AuditEvent;
import com.bpm.core.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 稽核寫入路徑的迴歸測試。
 *
 * <p><b>這組測試的由來。</b>2026-09-28 在 dev 環境實測發現：跑完 7 個驗收案例
 * 外加手動啟動並簽核案件之後，{@code bpm_audit_log} 是 <b>0 筆</b>，而且 log 裡
 * 連一行錯誤都沒有（{@code AuditEventPublisher} 的 catch 會 {@code log.error}）。
 * security-audit 的 P0-3 說的是「{@code integrityCheck()} 不驗證鏈結所以篡改查不出來」，
 * 但實際情況更基本：<b>根本沒有資料</b>。稽核報告不是不可信，是空的。
 *
 * <p><b>為什麼分兩層測。</b>寫入路徑是
 * {@code Controller → AuditEventPublisher.publish() → AuditLogService.append() → repository.save()}。
 * （publish() 在 2026-09-29 前是 @Async；現在是同步 fail-closed，見 {@code AuditFailClosedTest}。）
 * 零筆資料可能出在任一層，而兩層的修法完全不同：
 * <ul>
 *   <li>{@link #appendPersistsDirectly()} 直接呼叫 {@code append()} —— 繞過 publisher。
 *       過了代表持久化層正常，問題在 publisher 那一層。</li>
 *   <li>{@link #publishPersists()} 走 {@code publish()}。
 *       只有這個失敗，就證明是 publisher 的投遞有問題。</li>
 * </ul>
 * 兩者一起看才能定位，缺一個就只能猜。
 */
class AuditWritePathTest extends IntegrationTestBase {

    @Autowired
    private AuditLogService auditLogService;

    @Autowired
    private AuditEventPublisher auditEventPublisher;

    @BeforeEach
    void clean() {
        truncateAuditLog();
    }

    private static int countAuditRows() {
        AtomicInteger n = new AtomicInteger(-1);
        withAuditConnection(c -> {
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM bpm_audit_log")) {
                rs.next();
                n.set(rs.getInt(1));
            }
        });
        return n.get();
    }

    @Test
    @DisplayName("Flyway 必須建出稽核表與 append-only 觸發器")
    void flywayCreatedAuditSchema() {
        withAuditConnection(c -> {
            try (Statement st = c.createStatement()) {
                try (ResultSet rs = st.executeQuery(
                        "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = 'bpm_audit_log'")) {
                    rs.next();
                    assertThat(rs.getInt(1)).as("bpm_audit_log 應由 V1 建立").isEqualTo(1);
                }
                // V2 的觸發器過去是「手動執行」，因此每個新環境預設都沒有防篡改。
                // 這個斷言就是為了讓它不可能再被忘記。
                try (ResultSet rs = st.executeQuery(
                        "SELECT COUNT(*) FROM sys.triggers WHERE name IN "
                                + "('trg_audit_log_no_update', 'trg_audit_log_no_delete')")) {
                    rs.next();
                    assertThat(rs.getInt(1)).as("V2 應建立兩個 append-only 觸發器").isEqualTo(2);
                }
            }
        });
    }

    @Test
    @DisplayName("append() 應同步寫入稽核 DB（繞過 @Async，隔離持久化層）")
    void appendPersistsDirectly() {
        AuditLog log = new AuditLog();
        log.setOperationType(OperationType.TASK_APPROVE);
        log.setOperatorId("mgr001");
        log.setProcessInstanceId("proc-direct-1");
        log.setTaskId("task-direct-1");
        log.setCreatedAt(Instant.now());

        auditLogService.append(log);

        assertThat(countAuditRows())
                .as("append() 回傳後資料必須已經在 DB 裡；若為 0，問題在持久化層（交易管理器／EntityManager）")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("publish() 在交易外呼叫時應立即寫入稽核 DB")
    void publishPersists() {
        auditEventPublisher.publish(new AuditEvent(
                "TASK_APPROVE", "mgr001", "proc-sync-1", "task-sync-1",
                Map.of("approved", true)));

        // 同步寫入：publish() 回傳時資料就必須已在 DB，不需要等待。
        assertThat(countAuditRows())
                .as("publish() 回傳後必須已落地；若為 0 而 append() 測試是綠的，問題就在 publisher")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("append-only 觸發器必須擋掉 UPDATE 與 DELETE")
    void appendOnlyTriggersBlockMutation() {
        AuditLog log = new AuditLog();
        log.setOperationType(OperationType.TASK_APPROVE);
        log.setOperatorId("mgr001");
        log.setCreatedAt(Instant.now());
        auditLogService.append(log);

        withAuditConnection(c -> {
            try (Statement st = c.createStatement()) {
                assertThatThrows(() -> st.execute("UPDATE bpm_audit_log SET operator_id = 'attacker'"));
            }
            try (Statement st = c.createStatement()) {
                assertThatThrows(() -> st.execute("DELETE FROM bpm_audit_log"));
            }
        });

        assertThat(countAuditRows()).as("被擋掉之後資料必須還在").isEqualTo(1);
    }

    private static void assertThatThrows(ThrowingRunnable r) {
        try {
            r.run();
        } catch (Exception expected) {
            return;
        }
        throw new AssertionError("預期要被 append-only 觸發器擋下，但敘述成功執行了");
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
