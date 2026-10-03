package com.bpm.core.webhook;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * webhook 自訂 body 模板（#28）。
 *
 * <h2>語法：{@code {{field}}}</h2>
 *
 * <p>刻意<b>不用</b> BPMN 的 {@code ${...}}（Flowable EL）：模板存在 BPMN 屬性裡，
 * 用 {@code ${}} 會讓 Flowable 嘗試對它求值 —— 而本模板的欄位是 webhook payload
 * 的欄位名，不是流程變數。用 {@code {{}}} 把兩件事在語法上分開，就不會有人
 * （或引擎）誤會。
 *
 * <p>只支援<b>最上層欄位名</b>（{@code {{taskName}}}），不支援路徑（{@code {{a.b}}}）。
 * 未知的 placeholder <b>原樣保留</b>並記 debug log —— 保留而不是清成空字串，
 * 是因為「模板寫錯」在接收端會是一個明顯可見的 {@code {{typo}}}，
 * 而清掉之後接收端只會看到一個空欄位，無從得知是設定錯了。
 *
 * <p>值做 JSON 字串<b>內容</b>轉義（不含外引號）：使用者自己寫
 * {@code "task": "{{taskName}}"}，值裡若有 {@code "}／反斜線／換行，
 * 會在代換時被轉義成合法的 JSON 字串內容。{@code null} 代換成空字串
 * （鍵留著，接收端 schema 固定）；數字／日期等非字串值用其 JSON 表示
 * （例如 {@code 3}、ISO-8601 時間），讓它們在模板裡放在引號內外都成立。
 *
 * <h2>⚠️ 安全紅線（security-audit P2-1）：模板<b>不得</b>存取流程變數</h2>
 *
 * <p>模板只查得到呼叫端傳進來的 {@code payload} map，而成員只有下列
 * {@link #KNOWN_FIELDS}（任務層與流程層的聯集）。流程變數、表單欄位、
 * {@code execution} 或任何引擎物件<b>結構上</b>不在 map 裡，所以模板寫
 * {@code {{salary}}} 也只會原樣留在 body 裡，不會外洩任何資料。
 * 這個「不是靠黑名單過濾，而是靠根本拿不到」的性質是刻意的：黑名單會被
 * 下一個新增的變數名稱繞過，而這裡沒有任何路徑可以拿到它們。
 *
 * <p>{@link #KNOWN_FIELDS} 的用途是 <b>lint</b>（部署前的 warning，見
 * {@code BpmnLintService}），不是執行期的閘門。執行期的「未知」判定是
 * 「這個欄位在<b>本次事件</b>的 payload 裡存不存在」—— 例如流程層模板引用
 * {@code taskId} 不會被 lint 擋（它是聯集的一員），但結案 payload 裡沒有它，
 * 執行期會原樣保留。兩者刻意分開：lint 是諮詢性的，不能變成誤擋。
 */
public final class WebhookPayloadTemplate {

    private static final Logger log = LoggerFactory.getLogger(WebhookPayloadTemplate.class);

    /**
     * listener 與 {@link WebhookConsumer} 之間的第三個契約欄位：自訂 body 的原始字串。
     *
     * <p>與 {@code __webhookUrl}／{@code __webhookMethod} 同樣不可改名：
     * consumer 用 {@code payload.remove("__webhookBody")} 讀它，改名等於
     * 自訂 body 靜默失效（送出的會是預設 JSON，而且沒有任何錯誤）。
     */
    public static final String BODY_KEY = "__webhookBody";

    /**
     * 任務層（{@link WebhookTaskListener#buildPayload}）與流程層
     * （{@code ProcessCompletedListener}）payload 欄位的<b>聯集</b>。
     *
     * <p>這份清單是給 lint 用的。⚠️ 它與兩個 listener 實際 put 的欄位是
     * 「同一份事實的兩個寫法」，沒有機制強制同步 —— 漂移由兩條測試守住：
     * {@code WebhookTaskPayloadTest} 的 {@code KnownFieldsMirror}
     * 與 {@code ProcessCompletedListenerTest} 的 {@code processPayloadFieldsAreKnown}
     * 都用實際的 payload map 逐欄比對，新增欄位忘了同步時會紅。
     *
     * <p>清單裡沒有、也不該有流程變數（P2-1 紅線，見類別註解）。
     */
    public static final Set<String> KNOWN_FIELDS = Set.of(
            // 兩層共用
            "event", "timestamp", "processInstanceId", "processDefinitionKey", "businessKey",
            // 節點層
            "taskId", "taskName", "assignee", "dueDate", "operatorId",
            "action", "rejectReason", "overdueHours",
            // 流程層
            "result");

    /** {@code {{field}}}；名稱不含大括號，允許內部空白（比對前 trim）。 */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([^{}]+)}}");

    /**
     * 靜態共用一份即可：{@link ObjectMapper} 設定完後是 thread-safe 的，
     * 而這裡只需要「值 → JSON 文字」這個無狀態轉換。
     */
    private static final ObjectMapper JSON = new ObjectMapper();

    private WebhookPayloadTemplate() {
    }

    /**
     * 把模板裡的 {@code {{field}}} 代換成 payload 的對應值。
     *
     * <p>package-private：只有同套件的兩個 listener 會呼叫。讓測試直接測
     * 這段（不必繞 RabbitMQ），轉義的邊界才釘得住。
     *
     * @param template 模板；{@code null} → 回 {@code null}（呼叫端據此不設 body）
     * @param payload  事件 payload（尚未加入 {@code __webhookUrl} 等契約欄位）
     * @return 渲染後的字串；模板為 null 時為 null
     */
    static String render(String template, Map<String, Object> payload) {
        if (template == null) return null;

        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder(template.length() + 32);
        while (m.find()) {
            String name = m.group(1).trim();
            if (!payload.containsKey(name)) {
                // ⚠️ 原樣保留（不是清成空字串）：接收端看到 {{typo}} 才知道
                // 是模板寫錯，看到空字串只會以為那個欄位本來就沒值。
                log.debug("payload 模板引用了本次事件沒有的欄位 '{}'，原樣保留", name);
                m.appendReplacement(out, Matcher.quoteReplacement(m.group(0)));
                continue;
            }
            m.appendReplacement(out, Matcher.quoteReplacement(jsonContent(payload.get(name))));
        }
        m.appendTail(out);
        return out.toString();
    }

    /**
     * 值 → 可直接放進模板的 JSON 文字。
     *
     * <p>字串：取 JSON 序列化結果並去掉外引號 —— 使用者已在模板裡自己寫引號，
     * 我們只提供「引號內的安全內容」。非字串（數字、日期）：用其 JSON 表示，
     * 這樣 {@code "hours": {{overdueHours}}} 與 {@code "dueDate": "{{dueDate}}"}
     * 兩種寫法都成立。
     *
     * <p>⚠️ 序列化失敗時退到 {@code String.valueOf} 並轉義，而<b>不</b>讓例外
     * 往外炸：這段是在引擎的 command 裡執行的，任何未捕捉的例外都會讓
     * 「建立任務」整個失敗 —— 與 resolver 的取捨相同（fail-open 於流程）。
     */
    private static String jsonContent(Object value) {
        if (value == null) return "";
        try {
            String json = JSON.writeValueAsString(value);
            if (json.length() >= 2 && json.charAt(0) == '"' && json.charAt(json.length() - 1) == '"') {
                return json.substring(1, json.length() - 1);
            }
            return json;
        } catch (Exception e) {
            log.warn("payload 模板的值無法序列化為 JSON（{}），改用 toString：{}",
                    value.getClass().getSimpleName(), e.toString());
            return escapeString(String.valueOf(value));
        }
    }

    /**
     * JSON 字串內容轉義（不含外引號）—— 只用在 {@link #jsonContent} 的
     * 序列化失敗退路。正常路徑由 Jackson 負責，兩者行為一致。
     */
    private static String escapeString(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    /**
     * 模板裡出現、但不在 {@link #KNOWN_FIELDS} 的欄位名（依出現順序去重）。
     *
     * <p>供 lint 使用；執行期<b>不</b>呼叫它（執行期的判定是 payload map 的鍵，
     * 見類別註解）。刻意回傳集合而不是直接產生錯誤訊息：訊息措辭留在 lint。
     */
    public static Set<String> unknownFields(String template) {
        Set<String> unknown = new LinkedHashSet<>();
        if (template == null) return unknown;
        Matcher m = PLACEHOLDER.matcher(template);
        while (m.find()) {
            String name = m.group(1).trim();
            if (!name.isEmpty() && !KNOWN_FIELDS.contains(name)) unknown.add(name);
        }
        return unknown;
    }
}
