package com.bpm.core.audit.service;

import com.bpm.core.audit.model.AuditLog;
import com.bpm.core.audit.model.OperationType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 稽核 hash 計算的單元測試。
 *
 * <p>hash chain 是稽核不可篡改性的基礎，因此「相同輸入產生相同 hash、
 * 任一欄位變動就改變 hash」必須被釘死。
 *
 * <p>⚠️ 本測試同時記錄一個<b>已知缺陷</b>：hash 只涵蓋 17 個欄位中的 7 個
 * （security-audit P0-3）。因此改動 operatorName／ipAddress／businessKey 等
 * 欄位<b>不會</b>改變 hash —— 也就是這些欄位可以被篡改而 integrityCheck
 * 查不出來。{@link #hashDoesNotCoverAllFields()} 刻意斷言此現況，
 * 作為修復後的變更偵測點。
 */
class AuditHashTest {

    private static AuditLog sample() {
        AuditLog log = new AuditLog();
        log.setOperationType(OperationType.TASK_APPROVE);
        log.setOperatorId("mgr001");
        log.setProcessInstanceId("proc-1");
        log.setTaskId("task-1");
        log.setDetail("{\"approved\":true}");
        log.setCreatedAt(Instant.parse("2026-09-28T10:00:00Z"));
        return log;
    }

    @Test
    @DisplayName("相同輸入必須產生相同 hash")
    void deterministic() {
        assertThat(AuditLogService.computeHash(sample(), "PREV"))
                .isEqualTo(AuditLogService.computeHash(sample(), "PREV"));
    }

    @Test
    @DisplayName("hash 必須是 'v2:' + 64 字元十六進位")
    void shapeIsVersionedSha256Hex() {
        // 版本前綴的用途見 AuditLogService.V2：換演算法時必須能區分新舊記錄，
        // 否則 integrityCheck 會把所有歷史資料誤報為遭篡改。
        assertThat(AuditLogService.computeHash(sample(), "PREV"))
                .startsWith("v2:")
                .hasSize(67)
                .matches("v2:[0-9a-f]{64}");
    }

    @Test
    @DisplayName("v1（無前綴）的舊演算法必須保留，讓既有記錄仍可驗證")
    void legacyAlgorithmStillAvailable() {
        assertThat(AuditLogService.legacyHash(sample(), "PREV"))
                .as("既有記錄是純 64 位十六進位、無版本前綴")
                .hasSize(64)
                .matches("[0-9a-f]{64}");
        assertThat(AuditLogService.legacyHash(sample(), "PREV"))
                .isNotEqualTo(AuditLogService.computeHash(sample(), "PREV"));
    }

    @Test
    @DisplayName("previousHash 不同 → hash 必須不同（這才構成鏈結）")
    void previousHashIsPartOfTheChain() {
        assertThat(AuditLogService.computeHash(sample(), "PREV_A"))
                .isNotEqualTo(AuditLogService.computeHash(sample(), "PREV_B"));
    }

    @Test
    @DisplayName("被涵蓋的欄位任一變動都必須改變 hash")
    void coveredFieldsAffectHash() {
        String base = AuditLogService.computeHash(sample(), "PREV");

        AuditLog changedOperator = sample();
        changedOperator.setOperatorId("attacker");
        assertThat(AuditLogService.computeHash(changedOperator, "PREV")).isNotEqualTo(base);

        AuditLog changedDetail = sample();
        changedDetail.setDetail("{\"approved\":false}");
        assertThat(AuditLogService.computeHash(changedDetail, "PREV")).isNotEqualTo(base);

        AuditLog changedTime = sample();
        changedTime.setCreatedAt(Instant.parse("2026-09-28T11:00:00Z"));
        assertThat(AuditLogService.computeHash(changedTime, "PREV")).isNotEqualTo(base);

        AuditLog changedType = sample();
        changedType.setOperationType(OperationType.TASK_REJECT);
        assertThat(AuditLogService.computeHash(changedType, "PREV")).isNotEqualTo(base);
    }

    @Test
    @DisplayName("null 欄位不得使 hash 計算爆掉")
    void nullFieldsAreTolerated() {
        AuditLog sparse = new AuditLog();
        sparse.setOperationType(OperationType.PROCESS_START);
        sparse.setCreatedAt(Instant.parse("2026-09-28T10:00:00Z"));
        assertThat(AuditLogService.computeHash(sparse, "GENESIS")).hasSize(67);
    }

    @Test
    @DisplayName("欄位邊界不得有分隔符歧義（避免免費碰撞）")
    void fieldBoundariesAreUnambiguous() {
        // 單純以 | 串接時，("a|b", "c") 與 ("a", "b|c") 會產生相同字串 ——
        // 等於送給篡改者一個免費的碰撞。v2 以「長度:內容」前綴消除此歧義。
        AuditLog a = new AuditLog();
        a.setOperationType(OperationType.TASK_APPROVE);
        a.setCreatedAt(Instant.parse("2026-09-28T10:00:00Z"));
        a.setOperatorId("a|b");
        a.setOperatorName("c");

        AuditLog b = new AuditLog();
        b.setOperationType(OperationType.TASK_APPROVE);
        b.setCreatedAt(Instant.parse("2026-09-28T10:00:00Z"));
        b.setOperatorId("a");
        b.setOperatorName("b|c");

        assertThat(AuditLogService.computeHash(a, "PREV"))
                .isNotEqualTo(AuditLogService.computeHash(b, "PREV"));
    }

    @Test
    @DisplayName("P0-3 已修：hash 現在涵蓋全部內容欄位")
    void hashNowCoversAllContentFields() {
        // 這個測試原本斷言「這些欄位未被涵蓋」，作為 P0-3 修復後的
        // 變更偵測點。v2 演算法已把涵蓋範圍擴到所有持久化的內容欄位，
        // 因此斷言依當初註明的方式反轉。
        String base = AuditLogService.computeHash(sample(), "PREV");

        AuditLog changedName = sample();
        changedName.setOperatorName("有人改了姓名");
        assertThat(AuditLogService.computeHash(changedName, "PREV"))
                .as("operatorName 必須被涵蓋").isNotEqualTo(base);

        AuditLog changedIp = sample();
        changedIp.setIpAddress("1.2.3.4");
        assertThat(AuditLogService.computeHash(changedIp, "PREV"))
                .as("ipAddress 必須被涵蓋").isNotEqualTo(base);

        AuditLog changedBusinessKey = sample();
        changedBusinessKey.setBusinessKey("改成別的案件");
        assertThat(AuditLogService.computeHash(changedBusinessKey, "PREV"))
                .as("businessKey 必須被涵蓋").isNotEqualTo(base);

        AuditLog changedTrace = sample();
        changedTrace.setTraceId("forged-trace");
        assertThat(AuditLogService.computeHash(changedTrace, "PREV"))
                .as("traceId 必須被涵蓋").isNotEqualTo(base);

        AuditLog changedUa = sample();
        changedUa.setUserAgent("curl/8");
        assertThat(AuditLogService.computeHash(changedUa, "PREV"))
                .as("userAgent 必須被涵蓋").isNotEqualTo(base);
    }
}
