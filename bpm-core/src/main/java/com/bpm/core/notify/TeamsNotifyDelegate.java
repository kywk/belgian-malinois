package com.bpm.core.notify;

import com.bpm.core.engine.BpmnFieldSupport;
import com.bpm.core.http.SafeRestClients;
import com.bpm.core.webhook.WebhookUrlPolicy;
import tools.jackson.databind.ObjectMapper;
import org.flowable.bpmn.model.ServiceTask;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 通用 Teams 通知 delegate（#44）：流程走到節點時對 Microsoft Teams
 * Incoming Webhook 送一則訊息。
 *
 * <pre>{@code
 * <serviceTask id="notifyTeams" flowable:delegateExpression="${teamsNotifyDelegate}">
 *   <extensionElements>
 *     <flowable:field name="webhookUrl" stringValue="https://xxx.webhook.office.com/webhookb2/..."/>
 *     <flowable:field name="title" stringValue="案件 ${caseNo}"/>
 *     <flowable:field name="message" stringValue="流程已受理，請盡快處理。"/>
 *   </extensionElements>
 * </serviceTask>
 * }</pre>
 *
 * <h2>欄位</h2>
 * <ul>
 *   <li>{@code webhookUrl}（必填）：Teams Incoming Webhook 的完整 URL。
 *       支援 {@code ${var}}（規則見 {@link BpmnFieldSupport}）。空白／
 *       替換後為空 → log warn + no-op：沒有目標的請求不是請求。</li>
 *   <li>{@code title}（選填）：非空白時以換行接在 {@code message} 前
 *       （{@code title + "\n" + message}）；空白／缺欄位＝只有訊息本文。</li>
 *   <li>{@code message}（必填）：訊息本文，支援 {@code ${var}}。
 *       <b>空白／替換後為空 → log warn + no-op</b>：空的 Teams 訊息不是
 *       訊息，送出去只會在頻道留下一則空白通知。</li>
 * </ul>
 *
 * <h2>payload：只送 {@code {"text": ...}}</h2>
 *
 * <p>Teams Incoming Webhook 最單純、官方也接受的形狀是
 * {@code {"text": "..."}}；完整的 MessageCard 需要 {@code @type}／
 * {@code @context} 等欄位，而本專案刻意<b>不引入新依賴</b>、也不為單行
 * 通知長出一套卡片模型。{@code title} 因此不是獨立欄位，而是接進
 * {@code text}（Teams 的 {@code text} 支援 markdown，設計師要粗體等效果
 * 可直接寫在欄位裡）。JSON 由 {@link ObjectMapper} 產生，不手寫跳脫 ——
 * 內容來自 BPMN 與流程變數，可能含引號／反斜線／換行。
 *
 * <h2>🔴 SSRF 閘門：{@link WebhookUrlPolicy} 是安全相依</h2>
 *
 * <p>BPMN 由業務人員在設計器編輯，這個 delegate 等於「讓 BPMN 能發任意
 * HTTP POST」。因此 webhookUrl 一律先過
 * {@link WebhookUrlPolicy#rejectionReason(String)} —— 本 repo 唯一一份
 * SSRF 閘門（與 {@code ExternalApiDelegate} 同一句話）。非 null 時
 * log warn + no-op，<b>不發請求</b>。少了這一步，任何能部署 BPMN 的人
 * 就能讓伺服器去打 loopback／內網／雲端 metadata（169.254.169.254）。
 *
 * <p>閘門只檢查原始 URL，因此 client 必須不跟隨 3xx（由
 * {@link SafeRestClients} 集中保證）—— 否則被允許的主機可以用
 * {@code Location} 把請求帶去打 loopback，等於繞過政策。3xx 原樣回到
 * 這裡，落到下面的「非 2xx＝未送達」。
 *
 * <h2>⚠️ fail-open：通知失敗絕不影響流程（與 ExternalApiDelegate 的對比）</h2>
 *
 * <p>{@link #execute} 吞掉<b>所有</b>例外，只記 log（含非 2xx、逾時、
 * 連線失敗）。與 {@link EmailNotifyDelegate} 同一語意，也與
 * {@code ExternalApiDelegate} 刻意不同：那邊的外部 API 呼叫是流程的
 * <b>業務步驟</b>，失敗可建模（BpmnError ＋ boundary error 走補償）；
 * 通知是<b>旁路</b>，送不出去不是業務錯誤，往外丟只會讓 job 失敗、重試，
 * 甚至讓案件卡在通知不出去的那一步。
 *
 * <h2>逾時</h2>
 *
 * <p>沿用 {@code bpm.webhook.connect-timeout-ms}／{@code read-timeout-ms}
 * （與 {@code WebhookConsumer}／{@code ExternalApiDelegate} 同一份設定；
 * 預設 2000／5000 ms）。沒有逾時的 HTTP 呼叫會把引擎執行緒佔到 TCP
 * 逾時 —— 流程卡住，而 Teams 只是「慢」。
 *
 * <h2>⚠️ 不依賴 {@code flowable:field} 的 setter 注入</h2>
 *
 * <p>欄位一律用 {@link BpmnFieldSupport} 從 model 讀取。理由（單例 bean
 * 的注入競態、7.2.0 預設 MIXED 模式的位元碼證據）寫在
 * {@link BpmnFieldSupport} 的類別註解 —— 通用 delegate 共用同一份說明，
 * 不在此複製。
 */
@Component("teamsNotifyDelegate")
public class TeamsNotifyDelegate implements JavaDelegate {

    private static final Logger log = LoggerFactory.getLogger(TeamsNotifyDelegate.class);

    private final WebhookUrlPolicy urlPolicy;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    /**
     * 沒有引擎依賴，因此不需要 {@link TimeoutNotifyDelegate} 那種
     * {@code @Lazy}：本 bean 被 {@code FlowableConfig} 收進引擎的 beans map，
     * 但建構它只用到 Spring 的 {@code ObjectMapper}／{@code WebhookUrlPolicy}，
     * 不會回頭依賴 processEngine，沒有循環。
     */
    public TeamsNotifyDelegate(WebhookUrlPolicy urlPolicy,
                               ObjectMapper objectMapper,
                               @Value("${bpm.webhook.connect-timeout-ms:2000}") long connectTimeoutMs,
                               @Value("${bpm.webhook.read-timeout-ms:5000}") long readTimeoutMs) {
        this.urlPolicy = urlPolicy;
        this.objectMapper = objectMapper;
        // 逾時與「不跟隨 3xx」都集中在 SafeRestClients（規則只有一份；
        // 為什麼逾時不可省略、為什麼重導是 SSRF 缺口，見該類別註解）。
        this.restClient = SafeRestClients.create(connectTimeoutMs, readTimeoutMs);
    }

    /** Flowable 進入點：所有失敗都吞在這裡，理由見類別註解。 */
    @Override
    public void execute(DelegateExecution execution) {
        try {
            send(execution);
        } catch (Exception e) {
            log.warn("TeamsNotifyDelegate 發送失敗（不影響流程）：processInstanceId={} activityId={}",
                    execution.getProcessInstanceId(), execution.getCurrentActivityId(), e);
        }
    }

    /**
     * 可單獨測試的本文（package-private）：不吞例外，讓單元測試看得到行為。
     */
    void send(DelegateExecution execution) {
        ServiceTask task = execution.getCurrentFlowElement() instanceof ServiceTask st ? st : null;
        if (task == null) {
            log.warn("TeamsNotifyDelegate 不在 ServiceTask 上（currentFlowElement 不是 ServiceTask），"
                    + "讀不到欄位，略過：processInstanceId={}", execution.getProcessInstanceId());
            return;
        }

        String webhookUrl = BpmnFieldSupport.field(task, "webhookUrl", execution);
        if (webhookUrl == null || webhookUrl.isBlank()) {
            log.warn("TeamsNotifyDelegate 的 webhookUrl 為空（欄位不存在或替換後為空白），不發請求，略過："
                    + "processInstanceId={} activityId={}",
                    execution.getProcessInstanceId(), execution.getCurrentActivityId());
            return;
        }

        // ── SSRF 閘門：必須在建立請求之前 ─────────────────────────────
        String reason = urlPolicy.rejectionReason(webhookUrl);
        if (reason != null) {
            // 通知類 fail-open：拒絕只記 warn 不發請求，不丟 BpmnError
            // （通知失敗不擋流程；與 ExternalApiDelegate 的對比見類別註解）。
            log.warn("TeamsNotifyDelegate 目標被拒絕，不發請求：processInstanceId={} url={} 原因={}",
                    execution.getProcessInstanceId(), webhookUrl, reason);
            return;
        }

        String message = BpmnFieldSupport.field(task, "message", execution);
        if (message == null || message.isBlank()) {
            log.warn("TeamsNotifyDelegate 的 message 為空（欄位不存在或替換後為空白），不發空訊息，略過："
                    + "processInstanceId={} activityId={}",
                    execution.getProcessInstanceId(), execution.getCurrentActivityId());
            return;
        }

        String title = BpmnFieldSupport.field(task, "title", execution);
        String payload = payload(title, message);

        ResponseEntity<String> response = restClient.post()
                .uri(webhookUrl)
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .toEntity(String.class);

        if (!response.getStatusCode().is2xxSuccessful()) {
            // RestClient 預設對 4xx／5xx 拋例外，正常走不到這裡；3xx 等
            // 其他狀態則會到這裡。仍要擋下，因為規格是「2xx 才算送達」。
            log.warn("TeamsNotifyDelegate 非 2xx，視為未送達（不影響流程）："
                    + "processInstanceId={} url={} status={}",
                    execution.getProcessInstanceId(), webhookUrl, response.getStatusCode().value());
            return;
        }

        log.info("TeamsNotifyDelegate 已送出：processInstanceId={} activityId={} url={}",
                execution.getProcessInstanceId(), execution.getCurrentActivityId(), webhookUrl);
    }

    /**
     * 組出 Teams Incoming Webhook 的 payload（package-private 供單元測試
     * 釘住 JSON 形狀與跳脫）。
     *
     * <p>形狀固定是單一欄位 {@code text}；{@code title} 非空白時以換行
     * 接在前面。用 {@link ObjectMapper} 而不是字串串接 —— 內容來自 BPMN
     * 與流程變數，可能含引號／反斜線／換行，手寫跳脫是 bug 溫床。
     */
    String payload(String title, String message) {
        String text = title == null || title.isBlank() ? message : title + "\n" + message;
        return objectMapper.createObjectNode().put("text", text).toString();
    }
}
