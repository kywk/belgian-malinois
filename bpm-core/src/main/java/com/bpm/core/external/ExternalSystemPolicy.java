package com.bpm.core.external;

import com.bpm.core.model.ExternalSystem;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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
}
