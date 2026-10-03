package com.bpm.core.audit.anomaly;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.bpm.core.audit.AuditEventPublisher;
import com.bpm.core.audit.model.AuditLog;
import com.bpm.core.audit.model.OperationType;
import com.bpm.core.audit.repository.AuditLogRepository;
import com.bpm.core.dto.AuditEvent;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link AnomalyDetector} 的邊界行為（#41）：門檻（等於／低於／高於）、
 * 窗口、冷卻、denied 過濾、enabled=false、告警內容紅線。
 *
 * <h2>為什麼是單元測試而不是全部靠整合測試</h2>
 *
 * <p>門檻語意（{@code >=}）、窗口起訖、冷卻窗口與 JSON 判定都是純邏輯，
 * 用 mock repository 就能把每一條邊界釘死；整合測試（
 * {@code AnomalyDetectionIntegrationTest}）只驗「真的查得到、寫得進」。
 * 兩者分開，紅燈時一眼就知道是邏輯錯還是查詢／寫入錯。
 *
 * <p>負控：把 {@code scanMassApproval} 的門檻比較、{@code alert} 的冷卻
 * 判斷、或 {@code isDenied} 的解析任一拿掉，對應測試立刻紅。
 */
class AnomalyDetectorTest {

    private static final Duration MASS_WINDOW = Duration.ofMinutes(10);
    private static final Duration DENIED_WINDOW = Duration.ofMinutes(10);
    private static final Duration COOLDOWN = Duration.ofMinutes(30);

    private final AuditLogRepository repository = mock(AuditLogRepository.class);
    private final AuditEventPublisher auditPublisher = mock(AuditEventPublisher.class);
    private final JavaMailSender mailSender = mock(JavaMailSender.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 保守的預設形狀，但門檻調小讓測試好安排。 */
    private static AnomalyProperties props() {
        AnomalyProperties p = new AnomalyProperties();
        p.setEnabled(true);
        p.setCooldown(COOLDOWN);
        p.setAlertRecipients("");
        p.getMassApproval().setEnabled(true);
        p.getMassApproval().setThreshold(3);
        p.getMassApproval().setWindow(MASS_WINDOW);
        p.getDeniedAccess().setEnabled(true);
        p.getDeniedAccess().setThreshold(2);
        p.getDeniedAccess().setWindow(DENIED_WINDOW);
        return p;
    }

    private AnomalyDetector detector(AnomalyProperties p) {
        return new AnomalyDetector(repository, auditPublisher, mailSender, objectMapper, p);
    }

    private static OperatorHitCount hit(String operatorId, long count) {
        return new OperatorHitCount() {
            @Override
            public String getOperatorId() { return operatorId; }

            @Override
            public long getHitCount() { return count; }
        };
    }

    /** 模擬稽核 DB 裡的 DATA_ACCESS 列（detail 是 ObjectMapper 寫出的 JSON）。 */
    private AuditLog dataAccess(String operatorId, Map<String, Object> detail) {
        AuditLog log = new AuditLog();
        log.setOperationType(OperationType.DATA_ACCESS);
        log.setOperatorId(operatorId);
        log.setDetail(objectMapper.writeValueAsString(detail));
        return log;
    }

    private void stubMass(OperatorHitCount... hits) {
        when(repository.countOperatorsByOperationTypes(any(), any(), any()))
                .thenReturn(List.of(hits));
    }

    private void stubDenied(AuditLog... logs) {
        when(repository.findDeniedAccessCandidates(any(), any(), any(), any()))
                .thenReturn(List.of(logs));
    }

    private AuditEvent capturedAlert() {
        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditPublisher).publishDetached(captor.capture());
        return captor.getValue();
    }

    // ── 門檻邊界 ────────────────────────────────────────────────

    @Test
    @DisplayName("審批筆數等於門檻 → 告警（>= 的等號在異常側）")
    void massApprovalAtThresholdAlerts() {
        stubMass(hit("user001", 3L));
        stubDenied();

        detector(props()).scanAt(Instant.parse("2026-10-04T10:00:00Z"));

        AuditEvent event = capturedAlert();
        assertThat(event.operationType()).isEqualTo("ANOMALY_DETECTED");
        assertThat(event.operatorId()).isEqualTo("system");
        assertThat(event.operatorSource()).isEqualTo("engine");
        assertThat(event.detail())
                .containsEntry("mode", "MASS_APPROVAL")
                .containsEntry("operatorId", "user001")
                .containsEntry("hitCount", 3L)
                .containsEntry("threshold", 3L)
                .containsEntry("windowSeconds", MASS_WINDOW.toSeconds())
                .as("告警只放偵測中介資料，不得夾帶案件內容")
                .containsOnlyKeys("mode", "operatorId", "hitCount", "threshold",
                        "windowSeconds", "windowStart", "windowEnd");
    }

    @Test
    @DisplayName("審批筆數低於門檻 → 不告警")
    void massApprovalBelowThresholdDoesNotAlert() {
        stubMass(hit("user001", 2L));
        stubDenied();

        detector(props()).scanAt(Instant.parse("2026-10-04T10:00:00Z"));

        verify(auditPublisher, never()).publishDetached(any());
        verify(mailSender, never()).send(any(SimpleMailMessage.class));
    }

    @Test
    @DisplayName("審批筆數高於門檻 → 告警，且命中筆數照實記")
    void massApprovalAboveThresholdAlerts() {
        stubMass(hit("user001", 4L));
        stubDenied();

        detector(props()).scanAt(Instant.parse("2026-10-04T10:00:00Z"));

        assertThat(capturedAlert().detail()).containsEntry("hitCount", 4L);
    }

    @Test
    @DisplayName("窗口起訖必須是 [now-window, now]；審批只看 APPROVE／REJECT")
    void windowAndOperationTypesArePassedToTheQuery() {
        stubMass();
        stubDenied();

        Instant now = Instant.parse("2026-10-04T10:00:00Z");
        detector(props()).scanAt(now);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<OperationType>> types = ArgumentCaptor.forClass(Collection.class);
        ArgumentCaptor<Instant> start = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> end = ArgumentCaptor.forClass(Instant.class);
        verify(repository).countOperatorsByOperationTypes(types.capture(), start.capture(), end.capture());

        assertThat(types.getValue())
                .as("退回／補件不是快速蓋章的形狀，不該計入")
                .containsExactlyInAnyOrder(OperationType.TASK_APPROVE, OperationType.TASK_REJECT);
        assertThat(start.getValue()).isEqualTo(now.minus(MASS_WINDOW));
        assertThat(end.getValue()).isEqualTo(now);
    }

    // ── 冷卻 ────────────────────────────────────────────────────

    @Test
    @DisplayName("冷卻期內同（模式＋operator）只告警一次；冷卻到期後可再告警")
    void cooldownSuppressesRepeatedAlerts() {
        stubMass(hit("user001", 3L));
        stubDenied();

        AnomalyDetector detector = detector(props());
        Instant first = Instant.parse("2026-10-04T10:00:00Z");

        detector.scanAt(first);
        verify(auditPublisher).publishDetached(any());

        // 掃描每分鐘一次、窗口 10 分鐘 —— 沒有冷卻會重複告警約 10 次。
        detector.scanAt(first.plus(Duration.ofMinutes(29)));
        verify(auditPublisher).publishDetached(any()); // 仍只有第一次

        // 剛好到期（>= cooldown）就算新的一次異常。
        detector.scanAt(first.plus(COOLDOWN));
        verify(auditPublisher, org.mockito.Mockito.times(2)).publishDetached(any());
    }

    // ── denied 過濾 ─────────────────────────────────────────────

    @Test
    @DisplayName("denied 只認布林 true；非 denied、字串值、壞 JSON 都不計")
    void deniedAccessCountsOnlyBooleanTrue() {
        stubMass();
        stubDenied(
                dataAccess("user001", Map.of("denied", true)),
                dataAccess("user001", Map.of("action", "search")),
                dataAccess("user001", Map.of("denied", "yes")),
                dataAccess("user001", Map.of("note", "bad")));

        AnomalyDetector detector = detector(props()); // 門檻 2
        detector.scanAt(Instant.parse("2026-10-04T10:00:00Z"));

        verify(auditPublisher, never()).publishDetached(any());

        // 再一筆真的 denied → 達門檻，告警。
        stubDenied(
                dataAccess("user001", Map.of("denied", true)),
                dataAccess("user001", Map.of("denied", true)));
        detector.scanAt(Instant.parse("2026-10-04T10:00:01Z"));

        AuditEvent event = capturedAlert();
        assertThat(event.detail())
                .containsEntry("mode", "DENIED_ACCESS")
                .containsEntry("hitCount", 2L);
    }

    @Test
    @DisplayName("denied 解析壞 JSON 不得中斷掃描（其他 operator 照常偵測）")
    void malformedDetailDoesNotAbortScan() {
        stubMass();
        stubDenied(
                logWithDetail("broken", "{not-json"),
                logWithDetail("user001", "{\"denied\":true}"),
                logWithDetail("user001", "{\"denied\":true}"));

        detector(props()).scanAt(Instant.parse("2026-10-04T10:00:00Z"));

        assertThat(capturedAlert().detail()).containsEntry("operatorId", "user001");
    }

    private static AuditLog logWithDetail(String operatorId, String detail) {
        AuditLog log = new AuditLog();
        log.setOperationType(OperationType.DATA_ACCESS);
        log.setOperatorId(operatorId);
        log.setDetail(detail);
        return log;
    }

    @Test
    @DisplayName("operatorId 空白的 denied 事件不計（系統事件不該歸給誰）")
    void blankOperatorIsIgnored() {
        stubMass();
        stubDenied(
                logWithDetail(" ", "{\"denied\":true}"),
                logWithDetail(null, "{\"denied\":true}"));

        detector(props()).scanAt(Instant.parse("2026-10-04T10:00:00Z"));

        verify(auditPublisher, never()).publishDetached(any());
    }

    // ── enabled ─────────────────────────────────────────────────

    @Test
    @DisplayName("總開關 false → 完全不碰稽核 DB")
    void disabledDetectorDoesNotScan() {
        AnomalyProperties p = props();
        p.setEnabled(false);

        detector(p).scan();

        verifyNoInteractions(repository);
        verifyNoInteractions(auditPublisher);
        verifyNoInteractions(mailSender);
    }

    @Test
    @DisplayName("單一模式 enabled=false → 該模式不查，另一模式照常")
    void disabledModeIsSkipped() {
        AnomalyProperties p = props();
        p.getMassApproval().setEnabled(false);
        stubDenied();

        detector(p).scanAt(Instant.parse("2026-10-04T10:00:00Z"));

        verify(repository, never()).countOperatorsByOperationTypes(any(), any(), any());
        verify(repository).findDeniedAccessCandidates(any(), any(), any(), any());
    }

    // ── 告警內容與 email ────────────────────────────────────────

    @Test
    @DisplayName("email：內容只有非敏感欄位、收件人照設定；案件細節不得外洩")
    void alertEmailCarriesOnlyNonSensitiveFields() {
        String secret = "SECRET-CASE-CONTENT-" + UUID.randomUUID();
        AnomalyProperties p = props();
        p.setAlertRecipients("ops@company.com, security@company.com");

        stubMass();
        stubDenied(
                dataAccess("user001", Map.of("denied", true, "reason", secret)),
                dataAccess("user001", Map.of("denied", true, "reason", secret)));

        detector(p).scanAt(Instant.parse("2026-10-04T10:00:00Z"));

        ArgumentCaptor<SimpleMailMessage> mail = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(mail.capture());
        assertThat(mail.getValue().getTo())
                .containsExactly("ops@company.com", "security@company.com");
        assertThat(mail.getValue().getSubject())
                .contains("異常操作告警").contains(AnomalyMode.DENIED_ACCESS.label());
        assertThat(mail.getValue().getText())
                .contains("user001")
                .contains("DENIED_ACCESS")
                .contains("2")
                .as("被拒絕存取的 reason 可能含案件線索，不得複製到外寄郵件")
                .doesNotContain(secret);

        assertThat(capturedAlert().detail().toString())
                .as("稽核 detail 同樣不得複製被拒絕存取的細節")
                .doesNotContain(secret);
    }

    @Test
    @DisplayName("收件人預設空＝不寄信，但稽核照寫")
    void emptyRecipientsMeansNoMail() {
        stubMass(hit("user001", 3L));
        stubDenied();

        detector(props()).scanAt(Instant.parse("2026-10-04T10:00:00Z"));

        verify(mailSender, never()).send(any(SimpleMailMessage.class));
        verify(auditPublisher).publishDetached(any());
    }

    @Test
    @DisplayName("告警必須留一行 ERROR log（log 是最即時的一條路徑）")
    void alertWritesErrorLog() {
        stubMass(hit("user001", 3L));
        stubDenied();

        Logger detectorLogger = (Logger) LoggerFactory.getLogger(AnomalyDetector.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        detectorLogger.addAppender(appender);
        try {
            detector(props()).scanAt(Instant.parse("2026-10-04T10:00:00Z"));
        } finally {
            detectorLogger.detachAppender(appender);
            appender.stop();
        }

        assertThat(appender.list)
                .anyMatch(e -> e.getLevel() == Level.ERROR
                        && e.getFormattedMessage().contains("MASS_APPROVAL")
                        && e.getFormattedMessage().contains("user001"));
    }
}
