package com.bpm.core.audit.anomaly;

import com.bpm.core.audit.model.AuditLog;
import com.bpm.core.audit.model.OperationType;
import com.bpm.core.audit.service.AuditLogService;
import com.bpm.core.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #41 的端到端驗證：真的寫進稽核 DB 的列、真的被偵測到、真的寫出
 * {@code ANOMALY_DETECTED} 稽核。
 *
 * <h2>為什麼要另開 JDBC 連線直接查</h2>
 *
 * <p>與 {@code AuditCoverageTest} 同一個理由：斷言「detector 呼叫了
 * publisher」抓不到「稽核其實沒落地」。本專案發生過呼叫全都正常、
 * 交易也 commit，但稽核一筆都沒寫進去的缺陷 —— 唯一可靠的驗證是
 * 另開連線到 {@code bpm_audit_db} 確認資料列真的在那裡。
 *
 * <h2>⚠️ 為什麼沒有 {@code @TestPropertySource}（2026-10-04 實測）</h2>
 *
 * <p>門檻必須調小才測得到，但<b>不能</b>用類別層設定覆蓋：
 * {@code IntegrationTestBase.SERVLET_PORT} 是 static 的單一 port，
 * 另開 context 的第二個 Tomcat 會 BindException，把其他測試整組弄紅
 * （已實測：{@code Address already in use}）。因此改用
 * {@link AnomalyDetector#scanAt(Instant, AnomalyProperties.Mode, AnomalyProperties.Mode)}
 * 把小平檻直接注入，共用預設 context。
 *
 * <p>背景排程在測試 profile 是關閉的（application-test.yml 的
 * {@code bpm.audit.anomaly.enabled=false}），所以精確的列數斷言不會被
 * 別的測試留下的資料干擾。排程「有沒有被註冊」由
 * {@link #schedulingIsEnabled()} 單獨守住。
 */
class AnomalyDetectionIntegrationTest extends IntegrationTestBase {

    private static final Duration WINDOW = Duration.ofMinutes(30);
    private static final long THRESHOLD = 3;

    @Autowired private AnomalyDetector detector;
    @Autowired private AuditLogService auditLogService;
    @Autowired private ApplicationContext applicationContext;

    @BeforeEach
    void clean() {
        truncateAuditLog();
    }

    private static AnomalyProperties.Mode mode() {
        return new AnomalyProperties.Mode(true, THRESHOLD, WINDOW);
    }

    /** 透過正式的 append 路徑寫入（含 hash chain），不是 raw INSERT。 */
    private void append(OperationType type, String operatorId, String detail, Instant createdAt) {
        AuditLog log = new AuditLog();
        log.setOperationType(type);
        log.setOperatorId(operatorId);
        log.setDetail(detail);
        log.setCreatedAt(createdAt);
        auditLogService.append(log);
    }

    /** 直接讀 audit DB 的 ANOMALY_DETECTED 列。 */
    private static List<String> anomalyAudits() {
        List<String> out = new ArrayList<>();
        withAuditConnection(c -> {
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT operator_id, operator_source, detail FROM bpm_audit_log "
                                 + "WHERE operation_type = 'ANOMALY_DETECTED' ORDER BY id")) {
                while (rs.next()) {
                    out.add(rs.getString("operator_id") + "|" + rs.getString("operator_source")
                            + "|" + rs.getString("detail"));
                }
            }
        });
        return out;
    }

    @Test
    @DisplayName("@EnableScheduling 必須真的註冊排程後處理器（否則偵測器永遠不會自己跑）")
    void schedulingIsEnabled() {
        assertThat(applicationContext.getBeanNamesForType(ScheduledAnnotationBeanPostProcessor.class))
                .as("少了 @EnableScheduling，@Scheduled 只是註解，掃描永遠不會被觸發")
                .isNotEmpty();
    }

    @Test
    @DisplayName("窗口內達門檻的大量審批告警；低於門檻與窗口外不計；冷卻內不重複")
    void massApprovalDetectedAtThresholdWithCooldown() {
        Instant now = Instant.now();
        String over = "anomaly-mass-" + UUID.randomUUID();
        String below = "anomaly-below-" + UUID.randomUUID();
        String outside = "anomaly-old-" + UUID.randomUUID();

        // 4 筆：3 核准 + 1 退回 → 超過門檻 3，且證明 REJECT 也計入。
        append(OperationType.TASK_APPROVE, over, null, now.minusSeconds(1));
        append(OperationType.TASK_APPROVE, over, null, now.minusSeconds(2));
        append(OperationType.TASK_APPROVE, over, null, now.minusSeconds(3));
        append(OperationType.TASK_REJECT, over, null, now.minusSeconds(4));
        // 2 筆 → 低於門檻。
        append(OperationType.TASK_APPROVE, below, null, now.minusSeconds(1));
        append(OperationType.TASK_APPROVE, below, null, now.minusSeconds(2));
        // 窗口（30 分鐘）之外 → 不計。
        append(OperationType.TASK_APPROVE, outside, null, now.minus(31, ChronoUnit.MINUTES));
        append(OperationType.TASK_APPROVE, outside, null, now.minus(32, ChronoUnit.MINUTES));
        append(OperationType.TASK_APPROVE, outside, null, now.minus(33, ChronoUnit.MINUTES));

        detector.scanAt(now.plusSeconds(1), mode(), mode());

        List<String> audits = anomalyAudits();
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0))
                .contains("system|engine")
                .contains(over)
                .contains("MASS_APPROVAL")
                .contains("\"hitCount\":4")
                .contains("\"threshold\":3")
                .as("低於門檻與窗口外的 operator 不該出現在告警裡")
                .doesNotContain(below)
                .doesNotContain(outside);

        // 冷卻：下一輪掃描仍在窗口內、同一 operator，不得再寫一筆。
        detector.scanAt(now.plusSeconds(2), mode(), mode());
        assertThat(anomalyAudits()).hasSize(1);
    }

    @Test
    @DisplayName("大量被拒絕存取才告警；只有 denied 字樣的誘餌不計；被拒絕細節不外洩")
    void deniedAccessRequiresActualDeniedTrue() {
        Instant now = Instant.now();
        String secret = "SECRET-DENIED-REASON-" + UUID.randomUUID();
        String attacker = "anomaly-deny-" + UUID.randomUUID();
        String decoyOnly = "anomaly-decoy-" + UUID.randomUUID();

        // 3 筆真的 denied:true → 達門檻 3。
        for (int i = 0; i < 3; i++) {
            append(OperationType.DATA_ACCESS, attacker,
                    "{\"denied\":true,\"reason\":\"" + secret + "\"}", now.minusSeconds(i));
        }
        // 2 筆真的 + 3 筆 SQL 粗篩會命中的誘餌 → 精確判定後只有 2 筆，不達門檻。
        // 誘餌必須是「detail 含 \"denied\" 字樣但不是布林 true」，
        // 才能證明 LIKE 之後的 Java 解析真的在把關。
        append(OperationType.DATA_ACCESS, decoyOnly, "{\"denied\":true}", now.minusSeconds(1));
        append(OperationType.DATA_ACCESS, decoyOnly, "{\"denied\":true}", now.minusSeconds(2));
        append(OperationType.DATA_ACCESS, decoyOnly, "{\"denied\":\"yes\"}", now.minusSeconds(3));
        append(OperationType.DATA_ACCESS, decoyOnly, "{\"action\":\"denied\"}", now.minusSeconds(4));
        append(OperationType.DATA_ACCESS, decoyOnly, "{\"action\":\"search\"}", now.minusSeconds(5));

        detector.scanAt(now.plusSeconds(1), mode(), mode());

        List<String> audits = anomalyAudits();
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0))
                .contains(attacker)
                .contains("DENIED_ACCESS")
                .contains("\"hitCount\":3")
                .as("只有 2 筆真拒絕的 operator 不該告警")
                .doesNotContain(decoyOnly)
                .as("被拒絕存取的 reason 可能含案件線索，不得複製進稽核告警")
                .doesNotContain(secret);
    }
}
