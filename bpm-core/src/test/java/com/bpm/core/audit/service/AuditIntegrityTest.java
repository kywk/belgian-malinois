package com.bpm.core.audit.service;

import com.bpm.core.audit.model.AuditLog;
import com.bpm.core.audit.model.OperationType;
import com.bpm.core.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 稽核不可篡改性：hash chain 的驗證（security-audit P0-3）。
 *
 * <p><b>修復前的狀況。</b>{@code integrityCheck()} 只對每一筆做
 * {@code computeHash(log, log.getPreviousHash())} 與自己的 {@code hashValue}
 * 比對 —— 用的是<b>該筆自己儲存的</b> {@code previousHash}，
 * <b>從未比對 {@code log[n].previousHash == log[n-1].hashValue}</b>。
 *
 * <p>後果：
 * <ul>
 *   <li>刪除中間任一筆 → 其餘每筆仍自我一致 → 回報 {@code intact: true}</li>
 *   <li>篡改 detail 後重算該筆 hash（不動 previousHash）→ 通過</li>
 *   <li>hash 只涵蓋 17 個欄位中的 7 個 → operatorName／ipAddress／businessKey
 *       等「誰、從哪裡、改了什麼」的欄位可任意篡改而查不出來</li>
 * </ul>
 *
 * <p>也就是說「不可篡改的稽核」在修復前是一個<b>會主動回報 intact 的空殼</b>
 * —— 比沒有完整性檢查更糟，因為它製造虛假的信心。
 */
class AuditIntegrityTest extends IntegrationTestBase {

    @Autowired
    private AuditLogService auditLogService;

    private static final Instant BASE = Instant.parse("2026-09-28T00:00:00Z");

    @BeforeEach
    void clean() {
        truncateAuditLog();
    }

    /** 附加 n 筆合法記錄，形成一條完整的鏈。 */
    private void appendChain(int n) {
        for (int i = 0; i < n; i++) {
            AuditLog log = new AuditLog();
            log.setOperationType(OperationType.TASK_APPROVE);
            log.setOperatorId("mgr00" + (i + 1));
            log.setOperatorName("主管 " + (i + 1));
            log.setProcessInstanceId("proc-" + i);
            log.setTaskId("task-" + i);
            log.setBusinessKey("BK-" + i);
            log.setIpAddress("10.0.0." + (i + 1));
            log.setDetail("{\"approved\":true,\"seq\":" + i + "}");
            log.setCreatedAt(BASE.plus(i, ChronoUnit.MINUTES));
            auditLogService.append(log);
        }
    }

    private Map<String, Object> check() {
        return auditLogService.integrityCheck(
                BASE.minus(1, ChronoUnit.DAYS), BASE.plus(1, ChronoUnit.DAYS));
    }

    /** 直接改 DB —— 模擬繞過應用層的篡改（append-only 觸發器需暫時停用）。 */
    private static void tamper(String sql) {
        withAuditConnection(c -> {
            try (Statement st = c.createStatement()) {
                st.execute("DISABLE TRIGGER trg_audit_log_no_update ON bpm_audit_log");
                st.execute("DISABLE TRIGGER trg_audit_log_no_delete ON bpm_audit_log");
                st.execute(sql);
                st.execute("ENABLE TRIGGER trg_audit_log_no_update ON bpm_audit_log");
                st.execute("ENABLE TRIGGER trg_audit_log_no_delete ON bpm_audit_log");
            }
        });
    }

    @Test
    @DisplayName("v1 記錄分類為 unverifiable，不得誤報為篡改，但鏈結仍須驗證")
    void legacyRecordsAreUnverifiableNotBroken() {
        // 模擬修復前寫入的記錄：純 64 位十六進位、無版本前綴，
        // 且 hash 內容與欄位不符（v1 的 hash 用的是未持久化的值，
        // 本來就無法重現 —— 這是既有資料的性質，不是篡改的證據）。
        //
        // 刻意只用一筆：若鏈上還有後續記錄，改動這筆的 hash_value 會讓
        // 後一筆的 previousHash 不再相符，那是「正確地」被判為鏈結斷裂，
        // 會混淆本測試要驗的事。
        appendChain(1);
        tamper("UPDATE bpm_audit_log SET hash_value = "
                + "'0000000000000000000000000000000000000000000000000000000000000000'");

        Map<String, Object> r = check();
        assertThat(r.get("unverifiable"))
                .as("v1 記錄必須被明確計為 unverifiable")
                .isEqualTo(1);
        assertThat(r.get("broken"))
                .as("v1 記錄不得被誤報為篡改 —— 否則報告永遠是紅的，訊號被雜訊淹沒")
                .isEqualTo(0);
        assertThat(r.get("intact"))
                .as("可驗證的部分沒有問題，但讀者須自行看 unverifiable 判斷涵蓋範圍")
                .isEqualTo(true);
    }

    @Test
    @DisplayName("未被篡改的鏈必須回報 intact")
    void cleanChainIsIntact() {
        appendChain(5);
        Map<String, Object> r = check();
        assertThat(r.get("checked")).isEqualTo(5);
        assertThat(r.get("broken")).isEqualTo(0);
        assertThat(r.get("unverifiable")).as("新寫入的記錄都應可驗證").isEqualTo(0);
        assertThat(r.get("intact")).isEqualTo(true);
    }

    @Test
    @DisplayName("篡改被 hash 涵蓋的欄位必須被偵測")
    void tamperingCoveredFieldIsDetected() {
        appendChain(5);
        tamper("UPDATE bpm_audit_log SET detail = '{\"approved\":false}' "
                + "WHERE id = (SELECT MIN(id) + 2 FROM bpm_audit_log)");

        Map<String, Object> r = check();
        assertThat(r.get("intact")).as("篡改 detail 必須被偵測").isEqualTo(false);
        assertThat((int) r.get("broken")).isGreaterThan(0);
    }

    @Test
    @DisplayName("篡改先前未被涵蓋的欄位（operatorName／ipAddress／businessKey）也必須被偵測")
    void tamperingPreviouslyUncoveredFieldIsDetected() {
        appendChain(5);
        // 這三個欄位是稽核報告會顯示、調查時會採信的「誰、從哪裡、哪個案件」，
        // 修復前完全不在 hash 範圍內 → 可任意改動而回報 intact。
        tamper("UPDATE bpm_audit_log SET operator_name = N'別人', "
                + "ip_address = '1.2.3.4', business_key = 'OTHER-CASE' "
                + "WHERE id = (SELECT MIN(id) + 1 FROM bpm_audit_log)");

        assertThat(check().get("intact"))
                .as("operatorName／ipAddress／businessKey 的篡改必須被偵測")
                .isEqualTo(false);
    }

    @Test
    @DisplayName("刪除中間一筆必須被偵測（這是修復前最嚴重的破口）")
    void deletingMiddleRecordIsDetected() {
        appendChain(5);
        // 修復前：刪掉中間一筆，其餘每筆仍與自己儲存的 previousHash 一致
        // → 回報 intact: true。稽核等於可以被靜默刪除。
        tamper("DELETE FROM bpm_audit_log WHERE id = (SELECT MIN(id) + 2 FROM bpm_audit_log)");

        Map<String, Object> r = check();
        assertThat(r.get("intact")).as("刪除中間記錄必須被偵測").isEqualTo(false);
    }

    @Test
    @DisplayName("篡改後重算該筆自己的 hash（不修鏈結）仍必須被偵測")
    void recomputingOwnHashWithoutFixingChainIsDetected() {
        appendChain(4);

        // 攻擊者改了 detail，並且「聰明地」重算該筆自己的 hashValue，
        // 讓它與自己儲存的 previousHash 一致 —— 修復前這樣就能通過檢查。
        // 但下一筆的 previousHash 仍指向舊的 hashValue，因此走鏈時必然斷裂。
        AuditLog target = new AuditLog();
        target.setOperationType(OperationType.TASK_APPROVE);
        target.setOperatorId("mgr002");
        target.setOperatorName("主管 2");
        target.setProcessInstanceId("proc-1");
        target.setTaskId("task-1");
        target.setBusinessKey("BK-1");
        target.setIpAddress("10.0.0.2");
        target.setDetail("{\"approved\":false}");   // ← 篡改後的值
        target.setCreatedAt(BASE.plus(1, ChronoUnit.MINUTES));

        String prevHash = fetchScalar(
                "SELECT previous_hash FROM bpm_audit_log WHERE id = (SELECT MIN(id) + 1 FROM bpm_audit_log)");
        target.setPreviousHash(prevHash);
        String forged = AuditLogService.computeHash(target, prevHash);

        tamper("UPDATE bpm_audit_log SET detail = '{\"approved\":false}', "
                + "hash_value = '" + forged + "' "
                + "WHERE id = (SELECT MIN(id) + 1 FROM bpm_audit_log)");

        assertThat(check().get("intact"))
                .as("重算自己的 hash 但鏈結斷裂，必須被偵測")
                .isEqualTo(false);
    }

    @Test
    @DisplayName("奈秒精度的 createdAt 必須被截斷，否則每筆新記錄都會被誤報")
    void nanosecondCreatedAtIsTruncatedBeforeHashing() {
        // MSSQL 的 datetimeoffset 精度為 100 奈秒；Linux 上的 Instant.now()
        // 會給到奈秒。若不先截斷就算 hash，寫入被 DB 捨入後讀回來的值
        // 與算 hash 用的值不同 → 每一筆新記錄都被誤報為篡改。
        //
        // 這個缺陷在 macOS 上抓不到（Instant.now() 精度較粗），
        // 只有 Linux 容器才顯現。此處用明確帶奈秒的值讓它在任何平台都可重現。
        AuditLog log = new AuditLog();
        log.setOperationType(OperationType.TASK_APPROVE);
        log.setOperatorId("mgr001");
        log.setCreatedAt(Instant.parse("2026-09-28T00:00:00.123456789Z"));
        auditLogService.append(log);

        Map<String, Object> r = check();
        assertThat(r.get("checked")).isEqualTo(1);
        assertThat(r.get("broken"))
                .as("帶奈秒的 createdAt 不得造成誤報")
                .isEqualTo(0);
        assertThat(r.get("intact")).isEqualTo(true);
    }

    @Test
    @DisplayName("未指定 createdAt 的記錄不得被永久誤報為篡改")
    void recordWithoutExplicitCreatedAtVerifiesCorrectly() {
        // 修復前：append() 在 save() 之前算 hash，但 createdAt 是 @PrePersist
        // 才填。凡未預設 createdAt 的呼叫端（AuditEventConsumer 在訊息缺
        // timestamp 時就是）存進去的 hash 是用字串 "null" 算的 →
        // integrityCheck 重算時用真實時間戳 → 永久誤報該筆遭篡改。
        AuditLog log = new AuditLog();
        log.setOperationType(OperationType.PROCESS_START);
        log.setOperatorId("user001");
        // 刻意不設 createdAt
        auditLogService.append(log);

        Map<String, Object> r = auditLogService.integrityCheck(
                Instant.now().minus(1, ChronoUnit.HOURS),
                Instant.now().plus(1, ChronoUnit.HOURS));
        assertThat(r.get("checked")).isEqualTo(1);
        assertThat(r.get("intact"))
                .as("未指定 createdAt 的記錄必須能正確驗證，不得誤報")
                .isEqualTo(true);
    }

    private static String fetchScalar(String sql) {
        String[] out = new String[1];
        withAuditConnection(c -> {
            try (Statement st = c.createStatement(); var rs = st.executeQuery(sql)) {
                rs.next();
                out[0] = rs.getString(1);
            }
        });
        return out[0];
    }
}
