package com.bpm.core.external;

import com.bpm.core.model.ExternalSystem;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 外部系統的授權判定。
 *
 * <p>集中在一處的原因：改動前 allowedActions / allowedProcessKeys 都是以
 * NVARCHAR(MAX) 存放的 JSON 陣列「字串」，而判定是直接對該字串做
 * {@code String.contains()}。這是子串比對，不是集合成員比對：
 *
 * <pre>
 *   allowedActions = ["query_status_extended"]
 *   要求的 action  = "query_status"
 *   -&gt; "[\"query_status_extended\"]".contains("query_status") == true  // 誤放行
 * </pre>
 *
 * <p>本類別一律解析成集合後做精確比對，並把解析結果明確分成四種狀態
 * （見 {@link Kind}）—— 不要把「不限制」「拒絕全部」「格式錯誤」混為一談，
 * 否則運維看到的錯誤訊息會指向不存在的問題。
 */
@Component
public class ExternalSystemPolicy {

    private static final Logger log = LoggerFactory.getLogger(ExternalSystemPolicy.class);
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

    /** 設定值的語意分類。 */
    enum Kind {
        /** 欄位為 null 或空白 —— 不限制（維持改動前語意，避免既有整合被鎖死）。 */
        UNRESTRICTED,
        /** 明確的空清單（{@code []}）—— 拒絕全部。這是合法設定，不是錯誤。 */
        DENY_ALL,
        /** 有內容的清單 —— 精確比對。 */
        LIST,
        /** 欄位有值但無法解析 —— 拒絕全部，並記錄警告。 */
        INVALID
    }

    record Parsed(Kind kind, Set<String> values) {
        boolean permits(String candidate) {
            return switch (kind) {
                case UNRESTRICTED -> true;
                case DENY_ALL, INVALID -> false;
                case LIST -> candidate != null && values.contains(candidate);
            };
        }
    }

    private static final Parsed UNRESTRICTED = new Parsed(Kind.UNRESTRICTED, Set.of());
    private static final Parsed DENY_ALL = new Parsed(Kind.DENY_ALL, Set.of());
    private static final Parsed INVALID = new Parsed(Kind.INVALID, Set.of());

    private final ObjectMapper objectMapper;

    /**
     * 解析結果的快取（R-25 順手收的效能債）。
     *
     * <p>改動前每個請求都對同一組設定值做 1～3 次 Jackson 解析：
     * 一次請求要過 allowedActions、allowedProcessKeys、IP 白名單等多道檢查，
     * 而這些字串在管理員改設定之前<b>完全沒有變化</b>。parse 是純函式
     * （只依賴 raw 字串），所以按 raw 快取不需要任何失效邏輯 ——
     * 管理員改值時 key 自然不同，舊條目無害。
     *
     * <p>鍵的數量＝admin 歷史上寫過的不同設定字串數，量級極小；
     * 仍然設一個上限自我保護（見 {@link #MAX_CACHE_ENTRIES}）。
     */
    private final ConcurrentHashMap<String, Parsed> parseCache = new ConcurrentHashMap<>();

    /** 快取條目上限；超過就整體清空（純函式快取，清空永遠安全）。 */
    static final int MAX_CACHE_ENTRIES = 256;

    public ExternalSystemPolicy(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 解析設定值。
     *
     * <p>優先當成 JSON 陣列解析（{@code ExternalSystem} 的欄位註解如此宣告）；
     * 若不是 JSON，退回逗號分隔解析 —— 實務上 admin API 由前端自由填寫，
     * 兩種格式都可能進到資料庫，兩者都支援比在執行期炸掉安全。
     */
    Parsed parse(String raw) {
        if (raw == null || raw.isBlank()) return UNRESTRICTED;
        Parsed cached = parseCache.get(raw);
        if (cached != null) return cached;
        if (parseCache.size() >= MAX_CACHE_ENTRIES) parseCache.clear();
        // computeIfAbsent 讓同一 raw 的併發解析也只留一份；
        // 極端競爭下可能多算一次，結果相同，無副作用。
        return parseCache.computeIfAbsent(raw, this::parseUncached);
    }

    private Parsed parseUncached(String raw) {
        String trimmed = raw.trim();

        if (trimmed.startsWith("[")) {
            try {
                return toParsed(objectMapper.readValue(trimmed, STRING_LIST));
            } catch (Exception e) {
                log.warn("外部系統授權設定不是合法的 JSON 字串陣列，將拒絕存取: {}", trimmed, e);
                return INVALID;
            }
        }

        return toParsed(List.of(trimmed.split(",")));
    }

    /** 快取目前有幾筆（測試用；快取命中與否不影響對外行為）。 */
    int cachedParseEntries() {
        return parseCache.size();
    }

    private Parsed toParsed(List<String> values) {
        Set<String> out = new LinkedHashSet<>();
        for (String v : values) {
            if (v == null) continue;
            String s = v.trim();
            if (!s.isEmpty()) out.add(s);
        }
        if (out.isEmpty()) {
            // 區分 "[]"（合法的「拒絕全部」）與 " , "（垃圾值）。
            // 兩者結果都是拒絕，但診斷訊息必須不同。
            return values.isEmpty() ? DENY_ALL : INVALID;
        }
        return new Parsed(Kind.LIST, out);
    }

    private boolean allowed(String raw, String candidate, String what, String systemId) {
        Parsed parsed = parse(raw);
        if (parsed.kind() == Kind.INVALID) {
            log.warn("系統 {} 的 {} 設定無法解析，拒絕存取（原始值: {}）", systemId, what, raw);
        } else if (parsed.kind() == Kind.DENY_ALL) {
            log.debug("系統 {} 的 {} 為空清單，依設定拒絕 {}", systemId, what, candidate);
        }
        return parsed.permits(candidate);
    }

    /** action 是否在 allowedActions 內（精確比對）。 */
    public boolean isActionAllowed(ExternalSystem sys, String action) {
        return allowed(sys.getAllowedActions(), action, "allowedActions", sys.getSystemId());
    }

    /**
     * processDefinitionKey 是否在 allowedProcessKeys 內（精確比對）。
     *
     * <p>⚠️ 改動前這個欄位<b>完全沒有任何程式碼在檢查</b> —— 外部系統即使
     * 只被授權 leave-approval，也能啟動任何流程。
     *
     * <p>⚠️ 注意：欄位為空代表「不限制」，而 admin UI 的此欄位是自由文字且
     * 無必填驗證，因此照 UI 正常流程建立的系統預設是「可啟動任何流程」。
     * 要讓這項檢查真正生效，需在寫入端強制必填，見 backlog R-21。
     */
    public boolean isProcessKeyAllowed(ExternalSystem sys, String processDefinitionKey) {
        return allowed(sys.getAllowedProcessKeys(), processDefinitionKey,
                "allowedProcessKeys", sys.getSystemId());
    }

    /**
     * IP 是否在白名單內。白名單為逗號分隔字串。
     *
     * <p>⚠️ 呼叫端目前傳入的是 {@code request.getRemoteAddr()}，在 nginx 後方
     * 取到的是 nginx 容器位址而非真實 client IP，見 backlog R-22。
     */
    public boolean isIpAllowed(ExternalSystem sys, String clientIp) {
        return allowed(sys.getIpWhitelist(), clientIp == null ? null : clientIp.trim(),
                "ipWhitelist", sys.getSystemId());
    }

    /**
     * 候選群組是否在 {@code allowedCandidateGroups} 內（精確比對）。
     *
     * <h2>為什麼是白名單而不是「驗證群組存在」（#88 政策 B）</h2>
     *
     * <p>{@code firstTaskCandidateGroups} 有兩種危害，性質完全不同：
     * <ul>
     *   <li><b>卡死</b>：群組不存在 → 群組成員看不到任務 → 靜默卡死。
     *       這個形狀<b>無法</b>用白名單根治 —— 見下一節。</li>
     *   <li><b>越權</b>：把案件丟進任意<b>特權</b>群組的待辦池。白名單直接根治它。</li>
     * </ul>
     *
     * <h2>為什麼不驗證群組存在性</h2>
     *
     * <p>候選群組名稱在本 repo 有<b>三個互質的來源</b>（見
     * {@code CandidateGroupMembership} 類別註解）：① 部門代碼 ② 權限碼
     * （{@code hr:leave:approve}）③ JWT roles claim 帶進來的 authority。
     * 只有 ① 能用 {@code getDeptMembers} 驗，而 ②③ <b>沒有任何
     * 「這群組存在嗎」的 API</b>（權限中心只能由人反查權限清單，列不出權限碼全集）。
     * 也就是說：要驗就必須<b>假設每個群組都是部門</b>，那會擋掉 ②③ 這兩種
     * 合法用法，而本專案自己產生的 BPMN 就用 ②。
     * <b>一個會擋掉合法用法的驗證比沒有驗證更糟。</b>
     *
     * <p>換句話說這兩個危害要用兩種不同的工具，而「存在性」那一半目前沒有
     * 工具 —— <b>不該用一個做不到的檢查去假裝解決了問題</b>。
     * 它的可行替代是權限中心提供群組存在性 API，屬跨系統工程。
     *
     * <h2>⚠️ 欄位為空代表「不限制」</h2>
     *
     * <p>沿用 {@link Kind#UNRESTRICTED}，與 {@code allowedProcessKeys} 完全一致 ——
     * <b>規則只能有一份</b>，而四態分類（不限制／拒絕全部／清單／格式錯誤）
     * 已經把「空」與「空清單」分開了。見 {@code allowedProcessKeys} 關於
     * 「管理員照 UI 正常流程建立的系統預設是可啟動任何流程」的警告。
     */
    public boolean isCandidateGroupAllowed(ExternalSystem sys, String group) {
        return allowed(sys.getAllowedCandidateGroups(), group,
                "allowedCandidateGroups", sys.getSystemId());
    }

    /**
     * worker topic 是否在 {@code allowedWorkerTopics} 內（精確比對）。
     *
     * <h2>為什麼需要（#22 收尾）</h2>
     *
     * <p>Flowable 的 acquire 只按 topic ＋「尚未鎖定」挑 job
     * （{@code selectExternalWorkerJobsToExecute} 的 {@code LOCK_EXP_TIME_ is null}），
     * 沒有任何「這個 job 屬於哪個系統」的維度。改動前任何被授權
     * {@code external_worker} 的系統都能認領任何 topic 的未鎖定 job，
     * 而 acquire 會帶回流程變數 —— 跨系統洩漏。需要隔離時只能用系統專屬的
     * topic 名稱，但那是<b>約定</b>而不是<b>強制</b>。
     *
     * <p>本檢查把「這個系統能用哪些 topic」變成與 {@code allowedActions}／
     * {@code allowedCandidateGroups} 同一種授權決定，而判定仍然只有這一份
     * （{@link #allowed}）。刻意<b>不</b>驗 topic 是否存在：topic 是 BPMN 部署者
     * 自由選的字串，引擎沒有全集可查；而且別人的 topic 存在，不代表本系統
     * 有權使用它 —— 白名單回答的是授權問題，不是存在性問題。
     *
     * <h2>⚠️ 欄位為空代表「不限制」</h2>
     *
     * <p>沿用 {@link Kind#UNRESTRICTED}，與 {@code allowedProcessKeys} 完全一致 ——
     * <b>規則只能有一份</b>。migration 之後既有系統的這個欄位是 null，
     * 所以它們的行為完全不變，不需要回填；反過來說，這個檢查對既有系統
     * 沒有效果，直到管理員逐一設定（與 {@code allowedProcessKeys} 同一個
     * 已知狀況，R-21）。
     */
    public boolean isWorkerTopicAllowed(ExternalSystem sys, String topic) {
        return allowed(sys.getAllowedWorkerTopics(), topic,
                "allowedWorkerTopics", sys.getSystemId());
    }
}
