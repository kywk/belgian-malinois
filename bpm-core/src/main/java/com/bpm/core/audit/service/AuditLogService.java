package com.bpm.core.audit.service;

import com.bpm.core.audit.model.AuditLog;
import com.bpm.core.audit.model.OperationType;
import com.bpm.core.audit.repository.AuditLogRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AuditLogService {

    /** 建鏈起點的哨兵值。 */
    static final String GENESIS = "GENESIS";

    /**
     * hash 演算法版本前綴。
     *
     * <p>為什麼需要版本：v1 只涵蓋 17 個欄位中的 7 個（見 {@link #legacyHash}）。
     * 換演算法會讓所有既有記錄在重算時對不上 —— 若不區分版本，
     * integrityCheck 會把歷史資料全部誤報為遭篡改，
     * 於是這個功能在第一次真正被使用時就失去可信度，反而訓練使用者忽略它。
     *
     * <p>因此新記錄寫 {@code v2:} 前綴，驗證時依前綴選擇演算法：
     * 有前綴用 v2、純 64 位十六進位視為 v1。
     */
    static final String V2 = "v2:";

    private final AuditLogRepository repository;

    public AuditLogService(AuditLogRepository repository) {
        this.repository = repository;
    }

    /**
     * 附加一筆稽核記錄。
     *
     * <p>⚠️ {@code @Transactional} 必須限定 {@code auditTransactionManager}。
     * 未限定的 {@code @Transactional} 會開在 {@code bpm_core_db} 上（primary 是
     * {@code @Primary}），稽核寫入就失去原子性 —— 見 CLAUDE.md 已知事實 #7。
     *
     * <p>把 read-modify-write（讀前一筆的 hash → 算新 hash → 寫入）包在<b>同一個</b>
     * 交易裡也是必要的：{@code synchronized} 只在單一 JVM 內有效，多實例部署時
     * 兩個節點可以同時讀到同一個 previousHash，產生分叉的 hash chain。
     * 交易本身不解決跨實例競爭（那需要序列化隔離或 DB 端序號），
     * 但至少讓單一節點內的鏈結是一致的。
     */
    @Transactional("auditTransactionManager")
    public synchronized AuditLog append(AuditLog log) {
        // ⚠️ createdAt 必須在算 hash 之前填好。
        // 改動前是靠 @PrePersist 在 save() 時才填，而 hash 在那之前就算完了 ——
        // 凡未預設 createdAt 的呼叫端（AuditEventConsumer 在訊息缺 timestamp
        // 時就是）存進去的 hash 是用字串 "null" 算的，
        // 於是 integrityCheck 重算時用真實時間戳，永久誤報該筆遭篡改。
        if (log.getCreatedAt() == null) {
            log.setCreatedAt(Instant.now());
        }
        // ⚠️ 必須先截斷到 DB 能表示的精度，再算 hash。
        //
        // MSSQL 的 datetimeoffset 精度為 100 奈秒，而 Linux 上的 Instant.now()
        // 會給到奈秒。若直接用未截斷的值算 hash，寫入時被 DB 捨入，
        // 讀回來的值就與當初算 hash 用的值不同 → 每一筆新記錄都會被
        // integrityCheck 誤報為遭篡改。
        //
        // 這個錯誤在 macOS 上的測試抓不到（該平台的 Instant.now() 精度較粗，
        // 剛好塞得進 100 奈秒），只有在 Linux 容器上才會顯現 ——
        // 因此是由 docker 環境的實測發現的。
        //
        // 截斷到微秒（而非 100 奈秒）是刻意的：微秒必定能被 datetimeoffset
        // 精確表示，且不依賴特定 scale 設定，換 DB 或改欄位精度時仍成立。
        log.setCreatedAt(log.getCreatedAt().truncatedTo(ChronoUnit.MICROS));

        String previousHash = repository.findLastRecord()
                .map(AuditLog::getHashValue).orElse(GENESIS);
        log.setPreviousHash(previousHash);
        log.setHashValue(computeHash(log, previousHash));
        return repository.save(log);
    }

    public Page<AuditLog> search(String processInstanceId, String operatorId,
                                  OperationType operationType, Instant startDate,
                                  Instant endDate, Pageable pageable) {
        return repository.search(processInstanceId, operatorId, operationType,
                startDate, endDate, pageable);
    }

    /**
     * 完整性檢查：真正走一次 hash chain。
     *
     * <p>改動前只對每筆做「用<b>該筆自己儲存的</b> previousHash 重算 hash」
     * 與自己的 hashValue 比對，<b>從未比對 {@code log[n].previousHash ==
     * log[n-1].hashValue}</b>。因此刪除中間任一筆、或篡改後重算該筆自己的
     * hash，都會回報 intact —— 一個會主動宣稱「完好」的空殼。
     *
     * <p>現在做三件事：
     * <ol>
     *   <li><b>逐筆自我一致</b>：重算 hash 與儲存值比對（依版本前綴選演算法）</li>
     *   <li><b>鏈結連續</b>：每筆的 previousHash 必須等於前一筆的 hashValue</li>
     *   <li><b>id 連續</b>：id 之間不得有缺口 —— 這是偵測「整段被刪除」的唯一
     *       方法。若刪掉的是查詢區間邊界外的記錄，鏈結比對抓不到，但 id 缺口會。</li>
     * </ol>
     *
     * <p>⚠️ 仍然抓不到的情況：從<b>某一點之後全部刪除</b>（尾端截斷）。
     * 鏈與 id 都仍然連續。要防這個需要外部錨點（例如定期把最後一筆 hash
     * 寫到別的系統），不在本次範圍。
     */
    @Transactional(value = "auditTransactionManager", readOnly = true)
    public Map<String, Object> integrityCheck(Instant startDate, Instant endDate) {
        List<AuditLog> logs = repository.findByDateRange(startDate, endDate);

        int checked = 0;
        int broken = 0;
        int unverifiable = 0;
        Long firstBrokenId = null;
        List<String> reasons = new ArrayList<>();

        AuditLog previous = null;
        for (AuditLog log : logs) {
            checked++;
            boolean thisBroken = false;

            // (1) 自我一致 —— 只對 v2 記錄做。
            //
            // v1 記錄無法驗證，而且不是因為被篡改：舊的 append() 在 save() 之前
            // 算 hash，用的是「尚未持久化」的欄位值。其中 createdAt 是
            // Instant.now()（奈秒精度），寫入 datetimeoffset 時會被捨入
            // → 讀回來的值與當初算 hash 用的值不同 → 必然對不上。
            // 也就是說這些記錄的 hash 從寫下的那一刻起就無法重現。
            //
            // 把它們報成 broken 會讓完整性報告永遠是紅的，訊號被雜訊淹沒，
            // 反而訓練使用者忽略這份報告。因此分類為 unverifiable 並明確計數
            // —— 不隱藏，但也不假裝是篡改。
            if (isVerifiable(log)) {
                String expected = recompute(log);
                if (!expected.equals(log.getHashValue())) {
                    thisBroken = true;
                    reasons.add("id=" + log.getId() + " 內容與 hash 不符（欄位被篡改）");
                }
            } else {
                unverifiable++;
            }

            // (2) 鏈結連續
            if (previous == null) {
                // 區間的第一筆：只有當它真的是全表第一筆時才該是 GENESIS。
                // 否則它的 previousHash 指向區間外的記錄，無法在此驗證 ——
                // 不能因此判為斷裂，但也不能假裝驗過了。
                if (GENESIS.equals(log.getPreviousHash())) {
                    reasons.add("id=" + log.getId() + " 為鏈起點（GENESIS）");
                }
            } else if (!previous.getHashValue().equals(log.getPreviousHash())) {
                thisBroken = true;
                reasons.add("id=" + log.getId() + " 的 previousHash 不等於前一筆（id="
                        + previous.getId() + "）的 hash —— 中間有記錄被刪除或改寫");
            }

            // (3) id 連續
            if (previous != null && previous.getId() != null && log.getId() != null
                    && log.getId() != previous.getId() + 1) {
                thisBroken = true;
                reasons.add("id 缺口：" + previous.getId() + " → " + log.getId()
                        + "，中間的記錄已不存在");
            }

            if (thisBroken) {
                broken++;
                if (firstBrokenId == null) firstBrokenId = log.getId();
            }
            previous = log;
        }

        // 用 LinkedHashMap 而非 Map.of：需要保留鍵的順序（報告可讀性），
        // 且 Map.of 不接受 null value。
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("checked", checked);
        result.put("broken", broken);
        // 明確揭露無法驗證的筆數。intact 只代表「可驗證的部分沒有被篡改」，
        // 若 unverifiable > 0，報告的讀者必須知道涵蓋範圍不完整。
        result.put("unverifiable", unverifiable);
        result.put("intact", broken == 0);
        result.put("firstBrokenId", firstBrokenId != null ? firstBrokenId : "none");
        result.put("findings", reasons);
        result.put("startDate", startDate.toString());
        result.put("endDate", endDate.toString());
        return result;
    }

    /**
     * 該筆記錄的 hash 是否可被重現驗證。
     *
     * <p>只有 v2 可以。v1 的 hash 是用「尚未持久化」的欄位值算的
     * （見 integrityCheck 的說明），無法重現 —— 這是既有資料的性質，
     * 不是篡改的證據。
     */
    private static boolean isVerifiable(AuditLog log) {
        return log.getHashValue() != null && log.getHashValue().startsWith(V2);
    }

    /** 依儲存值的版本前綴選擇演算法重算。 */
    private static String recompute(AuditLog log) {
        String stored = log.getHashValue();
        if (stored != null && stored.startsWith(V2)) {
            return computeHash(log, log.getPreviousHash());
        }
        return legacyHash(log, log.getPreviousHash());
    }

    /**
     * v2：涵蓋所有持久化的內容欄位。
     *
     * <p>改動前只涵蓋 7 個欄位，未涵蓋 traceId／operatorName／operatorSource／
     * processDefinitionKey／businessKey／previousState／newState／ipAddress／
     * userAgent —— 而這些正是事件調查要用的「誰、從哪裡、改了什麼」。
     *
     * <p>每個欄位以「長度 + 冒號 + 內容」串接。用長度前綴而非單純分隔符，
     * 是為了避免分隔符歧義：{@code ("a|b", "c")} 與 {@code ("a", "b|c")}
     * 在單純以 {@code |} 串接時會produce相同字串，等於給篡改者一個免費的碰撞。
     */
    static String computeHash(AuditLog log, String previousHash) {
        StringBuilder sb = new StringBuilder();
        appendField(sb, log.getOperationType() == null ? null : log.getOperationType().name());
        appendField(sb, log.getTraceId());
        appendField(sb, log.getOperatorId());
        appendField(sb, log.getOperatorName());
        appendField(sb, log.getOperatorSource());
        appendField(sb, log.getProcessDefinitionKey());
        appendField(sb, log.getProcessInstanceId());
        appendField(sb, log.getTaskId());
        appendField(sb, log.getBusinessKey());
        appendField(sb, log.getDetail());
        appendField(sb, log.getPreviousState());
        appendField(sb, log.getNewState());
        appendField(sb, log.getIpAddress());
        appendField(sb, log.getUserAgent());
        appendField(sb, log.getCreatedAt() == null ? null : log.getCreatedAt().toString());
        appendField(sb, previousHash);
        return V2 + sha256Hex(sb.toString());
    }

    /**
     * v1：原本的 7 欄位演算法。保留<b>僅供驗證既有記錄</b>，不再用於新記錄。
     */
    static String legacyHash(AuditLog log, String previousHash) {
        String content = log.getOperationType() + "|"
                + nullSafe(log.getOperatorId()) + "|"
                + nullSafe(log.getProcessInstanceId()) + "|"
                + nullSafe(log.getTaskId()) + "|"
                + nullSafe(log.getDetail()) + "|"
                + log.getCreatedAt() + "|"
                + previousHash;
        return sha256Hex(content);
    }

    private static void appendField(StringBuilder sb, String value) {
        String v = value == null ? "" : value;
        sb.append(v.length()).append(':').append(v).append('|');
    }

    private static String sha256Hex(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Hash computation failed", e);
        }
    }

    private static String nullSafe(String s) {
        return s != null ? s : "";
    }
}
