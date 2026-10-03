package com.bpm.core.audit.anomaly;

import com.bpm.core.audit.AuditEventPublisher;
import com.bpm.core.audit.model.AuditLog;
import com.bpm.core.audit.model.OperationType;
import com.bpm.core.audit.repository.AuditLogRepository;
import com.bpm.core.dto.AuditEvent;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 異常操作模式偵測（#41，ISO 27001 A.16.1.2）。
 *
 * <h2>為什麼需要它</h2>
 *
 * <p>稽核紀錄回答了「發生了什麼」，但不會自己指出「哪些操作不正常」。
 * 兩種最典型、也最容易用規則描述的異常：
 * <ul>
 *   <li><b>短時間大量審批</b>：同一個 operator 在窗口內完成大量
 *       {@code TASK_APPROVE}／{@code TASK_REJECT}。正常的批次消化有，
 *       但每分鐘數件的節奏通常代表有人把關卡當橡皮圖章、
 *       或帳號被拿去批次代簽。</li>
 *   <li><b>異常存取</b>：同一個 operator 在窗口內大量
 *       {@code DATA_ACCESS {denied:true}}。一次 404 是走錯門；
 *       連續數十次是有人在探測他無權的案件（枚舉攻擊的指紋）。</li>
 * </ul>
 *
 * <p>偵測結果走與 #51 DLQ 告警相同的三條路徑：{@code ANOMALY_DETECTED}
 * 稽核、ERROR log、以及選配的 email（{@code bpm.audit.anomaly.alert-recipients}
 * 預設空＝不寄）。
 *
 * <h2>⚠️ 紅線：告警不複製案件內容</h2>
 *
 * <p>稽核 detail 與告警信<b>只放偵測中介資料</b>（模式、operatorId、
 * 命中筆數、門檻、窗口）。{@code DATA_ACCESS} 的 detail 可能帶
 * {@code reason}／{@code parameter}／{@code claimed}，這些一律不進告警。
 * 理由與 #51 相同：稽核庫與外寄郵件是兩個最不該複製業務內容的地方。
 * 要查「他當時在探測什麼」，用 operatorId + 時間範圍回稽核庫查。
 *
 * <h2>掃描成本</h2>
 *
 * <p>預設每 60 秒掃一次（{@code bpm.audit.anomaly.scan-interval}），
 * 每次只做兩個查詢：審批是 DB 端 {@code GROUP BY operatorId} 的聚合
 * （每個 operator 一列，不是每筆一列）；存取是窗口內候選列
 * （operationType＋時間範圍＋detail LIKE 前置篩選），再於 Java 端
 * 解析 JSON 精確判定。沒有任何全表載入。
 *
 * <h2>冷卻與已知限制</h2>
 *
 * <p>同一（模式＋operatorId）在 {@code bpm.audit.anomaly.cooldown}
 * （預設 30 分鐘）內只告警一次。冷卻狀態存在 JVM 記憶體：
 * <ul>
 *   <li><b>重啟即遺失</b> —— 最壞情況是重啟後同一個異常再告一次。
 *       可接受：多一則告警不會掩蓋訊號，為此引入 Redis／DB 狀態反而
 *       讓一個唯讀偵測器有了寫入依賴。</li>
 *   <li><b>多實例各自告警一次</b> —— 每個節點都有自己的冷卻表。
 *       代價是同一異常可能有多筆 ANOMALY_DETECTED 稽核（各自合法），
 *       需要跨節點去重時再說。</li>
 * </ul>
 *
 * <h2>失敗處理</h2>
 *
 * <p>排程方法不往外拋例外：稽核 DB 或 SMTP 暫時故障時，下一輪
 * （60 秒後）會自動重試，不需要讓排程執行緒帶著錯誤狀態結束。
 * 個別告警路徑（稽核／寄信）也各自 try/catch —— 稽核失敗不該
 * 阻止寄信，寄信失敗也不該讓整輪掃描停擺。
 */
@Component
public class AnomalyDetector {

    private static final Logger log = LoggerFactory.getLogger(AnomalyDetector.class);

    /** 與 {@code DeadLetterConsumer}／{@code EmailConsumer} 一致的寄件者。 */
    private static final String FROM = "bpm-noreply@company.com";

    /**
     * 大量審批只看「完成」的兩種結果。
     *
     * <p>刻意不含 {@code TASK_RETURN}／{@code TASK_RETURN_INITIATOR}：
     * 退回通常需要理由且會產生後續任務，不是「快速蓋章」的形狀；
     * 把它們算進來會讓正常的補件往返誤觸門檻。
     */
    private static final List<OperationType> APPROVAL_TYPES =
            List.of(OperationType.TASK_APPROVE, OperationType.TASK_REJECT);

    /**
     * denied 候選列的 SQL 前置篩選。
     *
     * <p>{@code detail} 由 {@code AuditEventPublisher} 以 ObjectMapper
     * 序列化 {@code Map.of("denied", true, ...)}，鍵一定帶引號。LIKE 只是
     * 便宜的粗篩（detail 是 NVARCHAR(MAX)）；真正的判定是
     * {@link #isDenied(String)} 的 JSON 解析 —— 值裡剛好含有
     * {@code "denied"} 字樣的列不會被誤計。
     */
    private static final String DENIED_LIKE_PATTERN = "%\"denied\"%";

    private final AuditLogRepository repository;
    private final AuditEventPublisher auditPublisher;
    private final JavaMailSender mailSender;
    private final ObjectMapper objectMapper;
    private final AnomalyProperties properties;

    /**
     * 冷卻狀態：{@code mode|operatorId → 上次告警時間}。
     *
     * <p>見類別註解「冷卻與已知限制」。用 ConcurrentHashMap 是防禦性的：
     * 目前排程是單執行緒，但偵測方法也可能被維運端手動呼叫。
     */
    private final Map<String, Instant> lastAlertAt = new ConcurrentHashMap<>();

    public AnomalyDetector(AuditLogRepository repository,
                           AuditEventPublisher auditPublisher,
                           JavaMailSender mailSender,
                           ObjectMapper objectMapper,
                           AnomalyProperties properties) {
        this.repository = repository;
        this.auditPublisher = auditPublisher;
        this.mailSender = mailSender;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    /**
     * 排程入口。
     *
     * <p>{@code initial-delay} 預設 60 秒：應用啟動時先讓 Flyway、Flowable
     * 與其他初始化完成，不要在最忙的一刻去查稽核 DB。
     */
    @Scheduled(fixedDelayString = "${bpm.audit.anomaly.scan-interval:60s}",
            initialDelayString = "${bpm.audit.anomaly.initial-delay:60s}")
    public void scan() {
        if (!properties.isEnabled()) {
            log.debug("異常操作偵測已停用（bpm.audit.anomaly.enabled=false），跳過掃描");
            return;
        }
        try {
            scanAt(Instant.now());
        } catch (Exception e) {
            // 排程例外不會被任何人接住。記下來，下一輪重試。
            log.error("異常操作偵測掃描失敗，下一輪會再試：{}", e.getMessage(), e);
        }
    }

    /**
     * 對指定時間點做一次掃描（使用設定的門檻與窗口）。抽出來是為了讓
     * 測試能控制「現在」—— 窗口與冷卻的行為都取決於時間，
     * 用 {@code Instant.now()} 測不準。
     */
    void scanAt(Instant now) {
        scanAt(now, properties.getMassApproval(), properties.getDeniedAccess());
    }

    /**
     * 以指定的模式設定掃描（測試用）。
     *
     * <p>為什麼要有這個多載：整合測試必須共用 {@code IntegrationTestBase}
     * 的預設 Spring context，而門檻在 application-test.yml 裡。
     * 在測試類別加 {@code @TestPropertySource} 會多開一個 context，
     * 兩個 context 會搶同一個 servlet port 而讓其他測試整組紅掉
     * （2026-10-04 實測 BindException，見 {@code IntegrationTestBase} 的說明）。
     * 因此改由參數把小平檻注入，不動共用設定。
     */
    void scanAt(Instant now, AnomalyProperties.Mode massApproval,
                AnomalyProperties.Mode deniedAccess) {
        scanMassApproval(now, massApproval);
        scanDeniedAccess(now, deniedAccess);
    }

    /** 模式一：窗口內同一 operator 的審批筆數。 */
    private void scanMassApproval(Instant now, AnomalyProperties.Mode mode) {
        if (!mode.isEnabled()) return;

        Instant start = now.minus(mode.getWindow());
        List<OperatorHitCount> hits = repository.countOperatorsByOperationTypes(
                APPROVAL_TYPES, start, now);
        for (OperatorHitCount hit : hits) {
            if (hit.getHitCount() < mode.getThreshold()) continue;
            alert(AnomalyMode.MASS_APPROVAL, hit.getOperatorId(), hit.getHitCount(),
                    mode, start, now);
        }
    }

    /** 模式二：窗口內同一 operator 被拒絕的存取筆數。 */
    private void scanDeniedAccess(Instant now, AnomalyProperties.Mode mode) {
        if (!mode.isEnabled()) return;

        Instant start = now.minus(mode.getWindow());
        List<AuditLog> candidates = repository.findDeniedAccessCandidates(
                OperationType.DATA_ACCESS, start, now, DENIED_LIKE_PATTERN);

        Map<String, Long> counts = new LinkedHashMap<>();
        for (AuditLog candidate : candidates) {
            if (!isDenied(candidate.getDetail())) continue;
            String operatorId = candidate.getOperatorId();
            if (operatorId == null || operatorId.isBlank()) continue;
            counts.merge(operatorId, 1L, Long::sum);
        }

        counts.forEach((operatorId, count) -> {
            if (count < mode.getThreshold()) return;
            alert(AnomalyMode.DENIED_ACCESS, operatorId, count, mode, start, now);
        });
    }

    /**
     * 這筆 {@code DATA_ACCESS} 的 detail 是不是拒絕事件。
     *
     * <p>解析失敗（非 JSON／壞掉的內容）一律視為「不是拒絕事件」：
     * 一筆格式異常的 detail 不該讓整輪掃描中斷，也不該被當成拒絕證據。
     */
    private boolean isDenied(String detail) {
        if (detail == null || detail.isBlank()) return false;
        try {
            JsonNode node = objectMapper.readTree(detail);
            JsonNode denied = node.isObject() ? node.get("denied") : null;
            return denied != null && denied.isBoolean() && denied.booleanValue();
        } catch (Exception e) {
            log.debug("稽核 detail 不是合法 JSON，略過：{}", e.getMessage());
            return false;
        }
    }

    /**
     * 告警：冷卻檢查 → ERROR log → 稽核 → 選配 email。
     *
     * <p>冷卻在第一步就判定並記錄：先記再送，避免稽核或寄信失敗後
     * 每一輪掃描都重試同一則告警（寄信失敗本身已記 ERROR log）。
     */
    private void alert(AnomalyMode mode, String operatorId, long hitCount,
                       AnomalyProperties.Mode settings, Instant windowStart, Instant windowEnd) {
        String cooldownKey = mode.name() + "|" + operatorId;
        Instant last = lastAlertAt.get(cooldownKey);
        if (last != null && last.plus(properties.getCooldown()).isAfter(windowEnd)) {
            log.debug("異常告警仍在冷卻期內，略過：mode={} operatorId={}", mode, operatorId);
            return;
        }
        lastAlertAt.put(cooldownKey, windowEnd);

        // 只放非敏感的偵測中介資料，見類別註解「紅線」。
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("mode", mode.name());
        detail.put("operatorId", operatorId);
        detail.put("hitCount", hitCount);
        detail.put("threshold", settings.getThreshold());
        detail.put("windowSeconds", settings.getWindow().toSeconds());
        detail.put("windowStart", windowStart.toString());
        detail.put("windowEnd", windowEnd.toString());

        log.error("偵測到異常操作模式：mode={}（{}）operatorId={} hitCount={} threshold={} window={}s",
                mode.name(), mode.label(), operatorId, hitCount,
                settings.getThreshold(), settings.getWindow().toSeconds());

        try {
            auditPublisher.publishDetached(new AuditEvent(
                    OperationType.ANOMALY_DETECTED.name(),
                    // 由排程偵測觸發，不是人類操作。與 DLQ_MESSAGE／TASK_UNREACHABLE 相同記 "system"。
                    "system", "engine",
                    null, null, null, null,
                    detail, windowEnd));
        } catch (Exception e) {
            // publishDetached 本身不拋；這裡是防禦性的第二道。
            log.error("異常偵測稽核寫入失敗（不影響掃描）：mode={} operatorId={} 原因={}",
                    mode, operatorId, e.getMessage(), e);
        }

        try {
            sendAlertEmail(mode, detail);
        } catch (Exception e) {
            log.error("異常偵測告警信寄送失敗（不影響掃描）：mode={} operatorId={} 原因={}",
                    mode, operatorId, e.getMessage(), e);
        }
    }

    /**
     * 選配的告警信。{@code bpm.audit.anomaly.alert-recipients} 為空（預設）時完全不寄。
     */
    private void sendAlertEmail(AnomalyMode mode, Map<String, Object> detail) {
        List<String> recipients = parseRecipients(properties.getAlertRecipients());
        if (recipients.isEmpty()) return;

        SimpleMailMessage mail = new SimpleMailMessage();
        mail.setTo(recipients.toArray(String[]::new));
        mail.setFrom(FROM);
        mail.setSubject("【BPM】異常操作告警：" + mode.label());
        mail.setText(buildAlertText(mode, detail));
        mailSender.send(mail);
        log.info("異常操作告警信已寄出：mode={} operatorId={} recipients={}",
                mode, detail.get("operatorId"), recipients);
    }

    /** 告警信內容：與稽核同一組非敏感欄位。 */
    private static String buildAlertText(AnomalyMode mode, Map<String, Object> detail) {
        StringBuilder sb = new StringBuilder();
        sb.append("偵測到異常操作模式，請查詢稽核紀錄確認"
                + "（operationType=ANOMALY_DETECTED）。\n\n");
        sb.append("模式: ").append(mode.name()).append("（").append(mode.label()).append("）\n");
        sb.append("operatorId: ").append(detail.getOrDefault("operatorId", "")).append('\n');
        sb.append("命中筆數: ").append(detail.getOrDefault("hitCount", 0)).append('\n');
        sb.append("門檻: ").append(detail.getOrDefault("threshold", 0)).append('\n');
        sb.append("窗口(秒): ").append(detail.getOrDefault("windowSeconds", 0)).append('\n');
        sb.append("窗口起: ").append(detail.getOrDefault("windowStart", "")).append('\n');
        sb.append("窗口迄: ").append(detail.getOrDefault("windowEnd", "")).append('\n');
        sb.append("\n（本信只含偵測中介資料，不含案件內容與被拒絕存取的細節。）");
        return sb.toString();
    }

    private static List<String> parseRecipients(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .distinct()
                .toList();
    }
}
