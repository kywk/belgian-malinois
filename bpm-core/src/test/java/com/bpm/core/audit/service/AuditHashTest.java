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
    @DisplayName("hash 必須是 64 字元的十六進位（SHA-256）")
    void shapeIsSha256Hex() {
        assertThat(AuditLogService.computeHash(sample(), "PREV"))
                .hasSize(64)
                .matches("[0-9a-f]{64}");
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
        assertThat(AuditLogService.computeHash(sparse, "GENESIS")).hasSize(64);
    }

    @Test
    @DisplayName("⚠️ 已知缺陷（P0-3）：hash 未涵蓋全部欄位，這些欄位可被篡改而查不出來")
    void hashDoesNotCoverAllFields() {
        String base = AuditLogService.computeHash(sample(), "PREV");

        // 這三個欄位都是稽核報告會顯示、也是調查時會採信的資訊，
        // 但它們不在 hash 的計算範圍內 → 可以被改掉而 integrityCheck 仍回報 intact。
        AuditLog changedName = sample();
        changedName.setOperatorName("有人改了姓名");
        assertThat(AuditLogService.computeHash(changedName, "PREV"))
                .as("operatorName 未被 hash 涵蓋（現況）").isEqualTo(base);

        AuditLog changedIp = sample();
        changedIp.setIpAddress("1.2.3.4");
        assertThat(AuditLogService.computeHash(changedIp, "PREV"))
                .as("ipAddress 未被 hash 涵蓋（現況）").isEqualTo(base);

        AuditLog changedBusinessKey = sample();
        changedBusinessKey.setBusinessKey("改成別的案件");
        assertThat(AuditLogService.computeHash(changedBusinessKey, "PREV"))
                .as("businessKey 未被 hash 涵蓋（現況）").isEqualTo(base);

        // P0-3 修復（把 hash 涵蓋範圍擴到全部欄位）之後，
        // 上面三個斷言都應改成 isNotEqualTo(base)。
    }
}
