package com.bpm.core.form.service;

import com.bpm.core.http.SafeRestClients;
import com.bpm.core.webhook.WebhookUrlPolicy;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 表單 select 欄位的動態選項來源（#56）。
 *
 * <p>schema 的 select 欄位可帶 {@code optionsUrl}；前端不直接打那個 URL，
 * 而是呼叫 {@code GET /api/forms/options?url=...} 由<b>後端代理</b>抓取。
 * 前端直連的問題有兩個：瀏覽器會把使用者的網路位置曝露給外部系統，
 * 而 CORS 讓大部分企業內網 API 根本讀不到。
 *
 * <h2>🔴 SSRF 閘門：{@link WebhookUrlPolicy} 是安全相依</h2>
 *
 * <p>{@code optionsUrl} 由表單設計者在 schema 裡自由填寫，等於「讓伺服器
 * 去打任意 URL」—— 與 #49 的 {@code ExternalApiDelegate} 同一個攻擊面。
 * 因此 URL 一律先過 {@link WebhookUrlPolicy#rejectionReason(String)}
 * （本 repo 唯一一份 SSRF 閘門），非 null 時回 403，<b>不發請求</b>。
 *
 * <p><b>policy 檢查在快取之前。</b>快取只快取「抓取結果」，不快取「能不能抓」。
 * 若順序顛倒，一條曾被允許、後來被政策擋下的 URL 仍會由快取供應內容
 * —— 閘門就形同虛設（快取不得掩蓋拒絕）。
 *
 * <p>⚠️ <b>重導不跟隨</b>（由 {@link SafeRestClients} 集中保證）：政策只檢查
 * 原始 URL 的 host，若跟隨 3xx，一個被允許的主機可以用 {@code Location} 把
 * 伺服器帶去打 loopback —— 閘門被繞過。3xx 因此原樣落到「非 2xx → 502」。
 *
 * <h2>回應形狀：兩種上游格式都正規化成 {@code [{label,value}]}</h2>
 *
 * <ul>
 *   <li>{@code [{"label":"特休","value":"annual"}, ...]} → 原樣採用；
 *       {@code label} 缺席／null／空白時以 {@code value} 當顯示文字。</li>
 *   <li>{@code ["annual","personal"]} → 每個字串變成
 *       {@code {"label":"annual","value":"annual"}}。</li>
 *   <li>{@code value} 可以是字串、數字或布林（後兩者用 {@code asText()}
 *       轉成字串，與 spec §8.4 的「value 是字串」契約一致）；
 *       物件／陣列／空白值視為格式錯誤。</li>
 *   <li>空陣列是合法的「目前沒有選項」。</li>
 *   <li>其他形狀（最外層不是陣列、元素不是字串／物件、缺 value）→ 502：
 *       上游的契約與本平台不合，靜默丟掉元素會讓設計者以為選項只是「少了幾個」。</li>
 * </ul>
 *
 * <h2>錯誤語意：上游失敗一律 502 Bad Gateway</h2>
 *
 * <p>非 2xx、逾時、連線失敗、非 JSON、形狀不合都回 502，語意是
 * 「<b>這個上游</b>不可用／回應無效」—— 失敗歸因於外部選項來源，不是
 * bpm-core 自己的依賴。repo 既有的 503 用在另一種情境：稽核 DB／RabbitMQ／
 * 組織系統這些<b>平台自身</b>的依賴暫時不可用（見 {@code AuditWriteException}
 * 的註解）。兩者刻意區分，讓維運能從狀態碼判斷「該去查外部系統，還是查平台」。
 *
 * <p>前端把任何失敗都當成「改用靜態 {@code options}」，所以 502／503 的選擇
 * 不影響表單可用性；它影響的是診斷方向。
 *
 * <h2>快取：60 秒、fail-open、key 含完整 URL</h2>
 *
 * <p>選項清單變動不頻繁，但表單開啟頻繁；每次開啟都打上游會讓外部系統
 * 成為表單可用性的瓶頸。TTL 固定 60 秒（{@link #CACHE_TTL}）：夠短，
 * 上游改資料一分鐘內可見；夠長，重複開表不再打上游。
 *
 * <p>Redis 故障一律退化為「直接抓取」：讀不到當 cache miss、寫不進只記 log，
 * 與 {@code BpmPermissionService} 的快取容錯同一取向 —— 快取的用途是加速，
 * 它掛掉不該讓功能不可用。⚠️ 這與政策閘門的 fail-closed 方向相反，是刻意的：
 * 閘門擋的是攻擊，快取只是加速。
 *
 * <p>key 是 {@code form:options:} ＋完整 URL。URL 是 key 的一部分（而不是
 * 雜湊），讓維運能直接看出快取內容屬於哪個來源。
 *
 * <h2>逾時</h2>
 *
 * <p>沿用 {@code bpm.webhook.connect-timeout-ms}／{@code read-timeout-ms}
 * （與 {@code WebhookConsumer}／{@code ExternalApiDelegate} 同一份設定；
 * 預設 2000／5000 ms）。沒有逾時的抓取會把 web 執行緒佔到 TCP 逾時 ——
 * 而外部選項來源只是「慢」。
 */
@Service
public class FormOptionsService {

    private static final Logger log = LoggerFactory.getLogger(FormOptionsService.class);

    /** 快取 key 前綴；後面直接接完整 URL。 */
    static final String CACHE_PREFIX = "form:options:";

    /** 選項快取的存活時間。見類別註解「快取」一節的取捨。 */
    static final Duration CACHE_TTL = Duration.ofSeconds(60);

    private final WebhookUrlPolicy urlPolicy;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    /**
     * {@code @Autowired} 是必要的：類別有兩個建構子（另一個給測試），
     * Spring 需要知道用哪一個。
     */
    @Autowired
    public FormOptionsService(WebhookUrlPolicy urlPolicy,
                              StringRedisTemplate redis,
                              ObjectMapper objectMapper,
                              @Value("${bpm.webhook.connect-timeout-ms:2000}") long connectTimeoutMs,
                              @Value("${bpm.webhook.read-timeout-ms:5000}") long readTimeoutMs) {
        this(urlPolicy, redis, objectMapper, SafeRestClients.create(connectTimeoutMs, readTimeoutMs));
    }

    /**
     * 測試用：直接注入建好的 {@link RestClient}（逾時由呼叫端設定）。
     * package-private，不給 Spring 用。
     */
    FormOptionsService(WebhookUrlPolicy urlPolicy, StringRedisTemplate redis,
                       ObjectMapper objectMapper, RestClient restClient) {
        this.urlPolicy = urlPolicy;
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.restClient = restClient;
    }

    /** 一個正規化後的選項；{@code value} 一律是字串（spec §8.4 的欄位契約）。 */
    public record FormOption(String label, String value) {
    }

    /**
     * 取得 {@code url} 的選項清單（快取優先，miss 才抓上游）。
     *
     * @throws ResponseStatusException 400：url 空白；403：被
     *         {@link WebhookUrlPolicy} 拒絕（不發請求、也不讀快取）；
     *         502：上游非 2xx／逾時／非 JSON／形狀不合
     */
    public List<FormOption> load(String url) {
        if (url == null || url.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "url 為必填 —— 動態選項必須指定來源位址。");
        }

        // ── SSRF 閘門：必須在讀快取與建立請求之前 ─────────────────────
        String reason = urlPolicy.rejectionReason(url);
        if (reason != null) {
            log.warn("動態選項來源被拒絕，不發請求也不讀快取：url={} 原因={}", url, reason);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "動態選項來源被拒絕：" + reason + "（url=" + url + "）。"
                            + "若這是內部系統，請將 host 加入 bpm.webhook.allowed-hosts。");
        }

        List<FormOption> cached = readCache(url);
        if (cached != null) {
            return cached;
        }

        List<FormOption> options = fetch(url);
        writeCache(url, options);
        return options;
    }

    // ── 抓取與正規化 ──────────────────────────────────────────────

    private List<FormOption> fetch(String url) {
        try {
            // 用 URI 而不是字串：RestClient.uri(String) 會把 {…} 當模板變數，
            // 任意 URL 帶大括號時會拋錯或意外展開。政策已驗證過格式。
            ResponseEntity<String> response = restClient.get()
                    .uri(URI.create(url))
                    .retrieve()
                    .toEntity(String.class);

            if (!response.getStatusCode().is2xxSuccessful()) {
                // RestClient 對 4xx／5xx 預設拋例外；3xx 等會到這裡，仍要擋。
                throw upstream("上游回應非 2xx：HTTP " + response.getStatusCode().value(), url);
            }
            List<FormOption> options = normalize(response.getBody(), url);
            log.info("動態選項抓取成功：url={} 選項數={}", url, options.size());
            return options;
        } catch (ResponseStatusException e) {
            throw e; // 上面的明確失敗（含形狀錯誤）原樣往外
        } catch (RestClientResponseException e) {
            throw upstream("上游回應非 2xx：HTTP " + e.getStatusCode().value(), url);
        } catch (Exception e) {
            // 逾時／連線失敗／DNS 等。message 帶底層原因，方便排查。
            throw upstream("連線或逾時失敗：" + e.getMessage(), url);
        }
    }

    /**
     * 上游回應 → {@code [{label,value}]}。規則的完整說明在類別註解
     * 「回應形狀」一節；這裡是它唯一的實作。
     */
    private List<FormOption> normalize(String body, String url) {
        if (body == null || body.isBlank()) {
            throw upstream("上游回應 body 為空，無法取得選項", url);
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (JsonProcessingException e) {
            throw upstream("上游回應不是合法 JSON：" + e.getOriginalMessage(), url);
        }
        if (root == null || !root.isArray()) {
            throw upstream("上游回應必須是 JSON 陣列（[{label,value}] 或 [\"a\",\"b\"]）", url);
        }

        List<FormOption> options = new ArrayList<>(root.size());
        for (JsonNode node : root) {
            FormOption option = toOption(node);
            if (option == null) {
                throw upstream("陣列元素必須是字串，或含非空白 value 的 {label,value} 物件；"
                        + "實際為 " + node.getNodeType(), url);
            }
            options.add(option);
        }
        return List.copyOf(options);
    }

    /** 單一元素 → 選項；無法正規化時回 {@code null}（由呼叫端報 502）。 */
    private static FormOption toOption(JsonNode node) {
        if (node.isTextual()) {
            String value = node.textValue();
            return value.isBlank() ? null : new FormOption(value, value);
        }
        if (node.isObject()) {
            JsonNode valueNode = node.get("value");
            if (valueNode == null || valueNode.isNull() || valueNode.isContainerNode()) {
                return null;
            }
            String value = valueNode.asText();
            if (value.isBlank()) {
                return null;
            }
            // label 只是顯示文字：缺席時用 value 代替，不讓整個來源失敗。
            JsonNode labelNode = node.get("label");
            String label = (labelNode == null || labelNode.isNull() || labelNode.asText().isBlank())
                    ? value : labelNode.asText();
            return new FormOption(label, value);
        }
        return null;
    }

    private ResponseStatusException upstream(String detail, String url) {
        String message = "動態選項載入失敗（url=" + url + "）：" + detail;
        log.warn("FormOptionsService 上游失敗：{}", message);
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, message);
    }

    // ── 快取（fail-open，見類別註解）────────────────────────────────

    private static String cacheKey(String url) {
        return CACHE_PREFIX + url;
    }

    /** 讀快取；任何 Redis 故障或壞值都視為 cache miss（退化為直接抓取）。 */
    private List<FormOption> readCache(String url) {
        String key = cacheKey(url);
        String cached;
        try {
            cached = redis.opsForValue().get(key);
        } catch (Exception e) {
            log.warn("讀取動態選項快取失敗，退化為直接抓取（key={}）: {}", key, e.toString());
            return null;
        }
        if (cached == null) {
            return null;
        }
        try {
            // 快取值是本服務自己序列化的 JSON 陣列，用同一顆 ObjectMapper 讀回。
            return objectMapper.readValue(cached, new TypeReference<List<FormOption>>() {
            });
        } catch (Exception e) {
            log.warn("動態選項快取值無法解析，視為未命中（key={}）: {}", key, e.toString());
            return null;
        }
    }

    /** 寫快取；失敗只記錄，不影響已取得的正確結果。 */
    private void writeCache(String url, List<FormOption> options) {
        String key = cacheKey(url);
        try {
            redis.opsForValue().set(key, objectMapper.writeValueAsString(options), CACHE_TTL);
        } catch (Exception e) {
            log.warn("寫入動態選項快取失敗，本次結果仍然有效（key={}）: {}", key, e.toString());
        }
    }
}
