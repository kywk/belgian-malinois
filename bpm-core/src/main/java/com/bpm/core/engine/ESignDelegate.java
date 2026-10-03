package com.bpm.core.engine;

import com.bpm.core.http.SafeRestClients;
import com.bpm.core.webhook.WebhookUrlPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flowable.bpmn.model.ServiceTask;
import org.flowable.engine.delegate.BpmnError;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * 通用電子簽章 delegate（#45）：觸發外部電子簽核服務，流程改由 message
 * catch event 等待回呼。
 *
 * <h2>🔴 本 delegate 不等待回呼</h2>
 *
 * <p>它只做「把簽核請求送出去」這一件同步的事。等待是非同步的：
 * BPMN 的標準畫法是 serviceTask（本 delegate）→ <b>message catch event</b>
 * → 後續。外部簽核服務完成後呼叫平台既有的回呼接收端
 * {@code POST /api/callback/{type}}（#21：X-System-Id＋HMAC＋時間戳窗、
 * Redis 冪等、correlation 後 {@code messageEventReceived}），流程才被喚醒。
 * delegate 裡<b>沒有</b>輪詢、<b>沒有</b>阻塞等待 —— 那會佔住引擎執行緒，
 * 而且等待時間由外部系統決定，不該是引擎交易的一部分。
 *
 * <pre>{@code
 * <message id="esignCompleted" name="esign-completed"/>
 *
 * <serviceTask id="startEsign" name="觸發電子簽章"
 *              flowable:delegateExpression="${esignDelegate}">
 *   <extensionElements>
 *     <flowable:field name="url" stringValue="https://esign.example.com/api/sign-requests"/>
 *     <!-- expression 形式才拿得到 processInstanceId；見下方「payload 與回呼」 -->
 *     <flowable:field name="payload">
 *       <flowable:expression><![CDATA[{"processInstanceId":"${execution.processInstanceId}","docId":"${docId}","signer":"${signer}"}]]></flowable:expression>
 *     </flowable:field>
 *     <flowable:field name="resultVariable" stringValue="esignResponse"/>
 *   </extensionElements>
 * </serviceTask>
 * <sequenceFlow id="f2" sourceRef="startEsign" targetRef="waitEsign"/>
 * <intermediateCatchEvent id="waitEsign" name="等待電子簽章回呼">
 *   <messageEventDefinition messageRef="esignCompleted"/>
 * </intermediateCatchEvent>
 * <sequenceFlow id="f3" sourceRef="waitEsign" targetRef="afterEsign"/>
 * }</pre>
 *
 * <p>上例的 message name {@code esign-completed} 就是回呼路徑的
 * {@code {type}}；外部系統完成簽核後以該 type 回呼，並帶上流程實例 id
 * （回呼的 correlation 條件是 processInstanceId＋message name）。
 * 等待中的流程若逾時仍要往下走，在 serviceTask 或 catch event 上掛
 * boundary timer 即可，不在本 delegate 範圍。
 *
 * <h2>欄位</h2>
 * <ul>
 *   <li>{@code url}（必填）：電子簽核服務的觸發端點。支援 {@code ${var}}
 *       替換（解析規則見 {@link BpmnFieldSupport}）。</li>
 *   <li>{@code payload}（必填）：送出的 JSON 字串，支援 {@code ${var}}
 *       替換 —— 例如文件 id、簽署人。<b>必須是合法 JSON</b>：空字串／純文字
 *       在發請求之前就被擋下（見下方失敗語意），不把解析錯誤留給對方系統
 *       去猜。</li>
 *   <li>{@code resultVariable}（可選）：2xx 時把<b>回應 body 的原字串</b>
 *       寫入該流程變數（body 為空 → 空字串；變數仍會被設定）。刻意採
 *       「整段 body」而不是解析特定欄位（例如 {@code requestId}）：那會
 *       發明一個本平台無法保證的回應形狀契約 —— 不同簽核服務的識別碼
 *       欄位名不同，解析失敗還得決定算不算失敗。整段 body 是簡單且
 *       可靠的一邊；設計師若需要特定欄位，在後續節點用 EL 或表單處理。
 *       （與 {@code ExternalApiDelegate} 的 {@code resultVariable} 同一條規則。）</li>
 * </ul>
 *
 * <h2>payload 與回呼：怎麼讓外部系統知道 processInstanceId</h2>
 *
 * <p>回呼的 correlation 需要 processInstanceId（#21），而
 * {@code processInstanceId} <b>不是</b>流程變數 —— {@code stringValue} 的
 * {@code ${processInstanceId}} 會替換成空字串，外部系統就永遠無法喚醒
 * 這個流程。要帶上它，用 {@code expression} 形式的
 * {@code ${execution.processInstanceId}}（上例）；其餘欄位照常用流程變數
 * （{@code ${docId}}）。這是 {@link BpmnFieldSupport} 兩種來源的既有語意，
 * 不是本 delegate 額外加的規則。
 *
 * <h2>🔴 SSRF 閘門：{@link WebhookUrlPolicy} 是安全相依</h2>
 *
 * <p>與 {@code ExternalApiDelegate}（#49）完全同一條規則：BPMN 由業務人員
 * 在設計器編輯，url 一律先過 {@link WebhookUrlPolicy#rejectionReason(String)}
 * —— 本 repo 唯一一份 SSRF 閘門。非 null 時丟
 * {@code BpmnError("ESIGN_BLOCKED", 原因)}，<b>不發請求</b>。
 * client 必須不跟隨 3xx（由 {@link SafeRestClients} 集中保證），否則被
 * 允許的主機可以用 {@code Location} 把請求帶去打 loopback，等於繞過政策。
 *
 * <h2>失敗語意：全部是可建模的 BpmnError</h2>
 *
 * <ul>
 *   <li>{@code ESIGN_BLOCKED}：被 SSRF 政策拒絕（非暫時性，重試沒有意義）。</li>
 *   <li>{@code ESIGN_FAILED}：非 2xx／逾時／連線失敗，以及 url 缺失、
 *       payload 缺失或非 JSON 這類設定錯誤。設計師用 boundary error
 *       （{@code errorCode="ESIGN_FAILED"}）接住即可走補償路徑（改走人工
 *       簽核、通知、稍後重試）。與 #48／#49 同一條分界：delegate 的失敗
 *       要嘛可建模、要嘛明顯 —— 這裡選可建模，因為外部系統的失敗是
 *       <b>預期內</b>的業務情境。</li>
 * </ul>
 *
 * <p>訊息一律帶 url／狀態，讓「哪一個呼叫失敗」看得出來。刻意<b>不</b>把
 * 回應 body 放進訊息：body 可能含對方系統的敏感資料（簽核內容、簽署人
 * 個資），而 BpmnError 訊息會進 log 與流程歷史。
 *
 * <h2>逾時</h2>
 *
 * <p>沿用 {@code bpm.webhook.connect-timeout-ms}／{@code read-timeout-ms}
 * （與 {@code WebhookConsumer}／{@code ExternalApiDelegate} 同一份設定；
 * 預設 2000／5000 ms）。沒有逾時的 HTTP 呼叫會把引擎執行緒佔到 TCP 逾時
 * —— 流程卡住，而外部系統只是「慢」。
 *
 * <h2>⚠️ 不依賴 {@code flowable:field} 的 setter 注入</h2>
 *
 * <p>欄位一律用 {@link BpmnFieldSupport} 從 model 讀取；單例 bean 的注入
 * 競態與 7.2.0 預設 MIXED 模式的位元碼證據寫在該類別註解，通用 delegate
 * 共用同一份說明。
 */
@Component("esignDelegate")
public class ESignDelegate implements JavaDelegate {

    private static final Logger log = LoggerFactory.getLogger(ESignDelegate.class);

    /** SSRF 政策拒絕；不會發請求。 */
    public static final String ERROR_CODE_BLOCKED = "ESIGN_BLOCKED";

    /** 非 2xx／逾時／連線失敗／設定錯誤（url 缺失、payload 非 JSON）。 */
    public static final String ERROR_CODE_FAILED = "ESIGN_FAILED";

    private final WebhookUrlPolicy urlPolicy;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    /**
     * {@code @Autowired} 是必要的：類別有兩個建構子（另一個給測試），
     * Spring 需要知道用哪一個。沒有引擎依賴，因此不需要 {@code @Lazy}。
     */
    @Autowired
    public ESignDelegate(WebhookUrlPolicy urlPolicy,
                         ObjectMapper objectMapper,
                         @Value("${bpm.webhook.connect-timeout-ms:2000}") long connectTimeoutMs,
                         @Value("${bpm.webhook.read-timeout-ms:5000}") long readTimeoutMs) {
        this(urlPolicy, objectMapper, SafeRestClients.create(connectTimeoutMs, readTimeoutMs));
    }

    /**
     * 測試用：直接注入建好的 {@link RestClient}（逾時由呼叫端設定）。
     * package-private，不給 Spring 用。
     */
    ESignDelegate(WebhookUrlPolicy urlPolicy, ObjectMapper objectMapper, RestClient restClient) {
        this.urlPolicy = urlPolicy;
        this.objectMapper = objectMapper;
        this.restClient = restClient;
    }

    @Override
    public void execute(DelegateExecution execution) {
        trigger(execution);
    }

    /**
     * 可單獨測試的本文（package-private）：失敗丟 BpmnError，不吞例外。
     */
    void trigger(DelegateExecution execution) {
        ServiceTask task = execution.getCurrentFlowElement() instanceof ServiceTask st ? st : null;

        String url = BpmnFieldSupport.field(task, "url", execution);
        if (url == null || url.isBlank()) {
            throw failed(execution, "未設定 url（欄位不存在或替換後為空白）", null);
        }

        // ── SSRF 閘門：必須在建立請求之前（也比 payload 驗證先）──────
        String reason = urlPolicy.rejectionReason(url);
        if (reason != null) {
            log.warn("ESignDelegate 目標被拒絕，不發請求：processInstanceId={} url={} 原因={}",
                    execution.getProcessInstanceId(), url, reason);
            throw new BpmnError(ERROR_CODE_BLOCKED,
                    "電子簽章服務目標被拒絕：" + reason + "（url=" + url + "）");
        }

        String payload = BpmnFieldSupport.field(task, "payload", execution);
        if (payload == null || payload.isBlank()) {
            throw failed(execution, "未設定 payload（欄位不存在或替換後為空白）", url);
        }
        if (!isValidJson(payload)) {
            // 不把 Jackson 的原始訊息整段放進來：它會附上來源字串（payload
            // 可能含簽核內容）。只留「不是合法 JSON」這個可行動的事實。
            throw failed(execution, "payload 不是合法 JSON", url);
        }

        String resultVariable = BpmnFieldSupport.field(task, "resultVariable", execution);

        try {
            ResponseEntity<String> response = restClient.post().uri(url)
                    .contentType(MediaType.APPLICATION_JSON).body(payload)
                    .retrieve().toEntity(String.class);

            HttpStatusCode status = response.getStatusCode();
            if (!status.is2xxSuccessful()) {
                // RestClient 預設對 4xx／5xx 拋例外，正常走不到這裡；3xx 等
                // 其他狀態則會到這裡。仍要擋下，因為規格是「2xx 才算成功」。
                throw failed(execution, "非 2xx 回應：HTTP " + status.value(), url);
            }

            if (resultVariable != null && !resultVariable.isBlank()) {
                String responseBody = response.getBody() == null ? "" : response.getBody();
                execution.setVariable(resultVariable, responseBody);
            }
            log.info("ESignDelegate 觸發成功，流程應停在等待回呼的 message catch event："
                            + "processInstanceId={} url={} status={}",
                    execution.getProcessInstanceId(), url, status.value());
        } catch (BpmnError e) {
            throw e; // 上面的明確失敗（含 policy）原樣往外
        } catch (RestClientResponseException e) {
            // 4xx／5xx。刻意不放 response body（可能含敏感資料）。
            throw failed(execution, "非 2xx 回應：HTTP " + e.getStatusCode().value(), url);
        } catch (Exception e) {
            // 逾時／連線失敗／DNS 等。message 帶底層原因，方便排查。
            throw failed(execution, "連線或逾時失敗：" + e.getMessage(), url);
        }
    }

    /**
     * payload 是不是合法 JSON。
     *
     * <p>只驗「可解析」：物件、陣列、字串、數字、布林、null 都算合法
     * JSON —— 形狀由電子簽核服務的 API 決定，本 delegate 不發明額外契約。
     * 呼叫端已先排除空白字串。
     */
    private boolean isValidJson(String payload) {
        try {
            objectMapper.readTree(payload);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private BpmnError failed(DelegateExecution execution, String detail, String url) {
        String message = "電子簽章觸發失敗"
                + (url == null ? "" : "（url=" + url + "）")
                + "：" + detail;
        log.warn("ESignDelegate 失敗：processInstanceId={} {}",
                execution.getProcessInstanceId(), message);
        return new BpmnError(ERROR_CODE_FAILED, message);
    }
}
