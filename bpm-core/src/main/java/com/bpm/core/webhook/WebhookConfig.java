package com.bpm.core.webhook;

/**
 * 一筆節點層的 webhook 設定（spec §11.4）。
 *
 * <p>設定存在 BPMN XML 的 {@code extensionElements} 裡，長相是：
 *
 * <pre>{@code
 * <bpmn:extensionElements>
 *   <flowable:webhooks>
 *     <flowable:webhook event="create" url="https://erp.example/hook" method="POST"/>
 *   </flowable:webhooks>
 * </bpmn:extensionElements>
 * }</pre>
 *
 * <h2>⚠️ 為什麼 tag 名刻意是全小寫</h2>
 *
 * <p>前端用 moddle 寫出這些元素，而 moddle 的 tag 名是由<b>型別名</b>決定的
 * （{@code xml.tagAlias: 'lowerCase'} 只把首字元小寫化，因此
 * {@code Webhook} → {@code webhook}、{@code TaskListener} → {@code taskListener}）。
 * 所以 spec 裡 camelCase 的 {@code taskListener} 沒問題，
 * 但只要型別名本身有多字首字母組成的字（例如 {@code WebHook}）就會對不上 ——
 * 寫出去的名字 Flowable 不認得，設定會<b>安靜地</b>失效。
 * 全小寫的 {@code webhook}／{@code webhooks} 是這條鏈路上最不容易出錯的形狀。
 *
 * @param event  事件名：{@code create}／{@code complete}／{@code timeout}／
 *               {@code reject}／{@code all}。見 {@link WebhookTaskListener#matches}
 * @param url    投遞位址。<b>必須</b>過 {@link WebhookUrlPolicy} 才會被投遞
 * @param method {@code POST} 或 {@code PUT}；其他值一律當 POST
 */
public record WebhookConfig(String event, String url, String method) {

    /** 外層容器的 element 名。 */
    public static final String ELEMENT = "webhooks";

    /** 每一筆設定的 element 名。 */
    public static final String CHILD = "webhook";

    /** 屬性名。三個都是無前置的，因此讀取時 namespace 必須傳 null。 */
    public static final String ATTR_EVENT = "event";
    public static final String ATTR_URL = "url";
    public static final String ATTR_METHOD = "method";

    /** 未指定 method 時的預設值，與前端 {@code METHODS} 的第一項一致。 */
    public static final String DEFAULT_METHOD = "POST";

    /** 未指定 event 時的預設值，與前端 {@code EVENTS} 的第一項一致。 */
    public static final String DEFAULT_EVENT = "create";

    /**
     * 流程層（{@code <process>}）未指定 event 時的預設值。
     *
     * <p>⚠️ 不可與 {@link #DEFAULT_EVENT} 合併。節點層的前端選單第一項是
     * {@code create}，但流程層（#67）只有結案這一個事件；沿用 {@code create}
     * 會讓「省略 event」的流程層設定被存成一筆永遠對不上事件的 {@code create}，
     * 症狀是設定成功、畫面正常、卻永遠不投遞 —— 正是本專案反覆在對付的靜默失效。
     */
    public static final String DEFAULT_PROCESS_EVENT = "process.completed";

    /**
     * 整理成一筆可用的設定。
     *
     * <p>URL 空白 → 回 {@code null}（代表「這一筆不成立」）。
     * 刻意<b>不在這裡</b>做 URL 格式或 SSRF 的判定：
     * {@link WebhookUrlPolicy} 是整條鏈路上<b>唯一</b>的閘門，
     * 判定邏輯放兩份就會出現「lint 過了但執行期炸掉」或反之（handoff §5：
     * 規則只能有一份）。這裡只做「有沒有填」這種無歧義的整理。
     */
    static WebhookConfig of(String event, String url, String method) {
        return of(event, url, method, DEFAULT_EVENT);
    }

    /**
     * 與 {@link #of(String, String, String)} 相同，但可指定「未填 event」時用的預設值。
     *
     * <p>存在的唯一理由是流程層：{@code <process>} 上省略 event 代表
     * {@link #DEFAULT_PROCESS_EVENT}（{@code process.completed}），
     * 而不是節點層的 {@code create}。兩層的預設值不同是<b>語意</b>不同，
     * 不是重複；把節點層的預設直接改成 {@code process.completed} 會弄壞節點層。
     */
    static WebhookConfig of(String event, String url, String method, String defaultEvent) {
        if (url == null || url.isBlank()) return null;
        String e = (event == null || event.isBlank()) ? defaultEvent : event.trim();
        String m = (method == null || method.isBlank()) ? DEFAULT_METHOD : method.trim();
        return new WebhookConfig(e, url.trim(), m);
    }
}
