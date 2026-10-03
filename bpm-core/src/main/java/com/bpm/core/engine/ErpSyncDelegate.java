package com.bpm.core.engine;

import com.bpm.core.http.SafeRestClients;
import com.bpm.core.webhook.WebhookUrlPolicy;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import org.flowable.bpmn.model.ServiceTask;
import org.flowable.engine.delegate.BpmnError;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.Locale;
import java.util.Set;

/**
 * ERP 同步 delegate（#46）：把流程資料以 JSON 送到 ERP 端點。
 *
 * <pre>{@code
 * <serviceTask id="syncErp" flowable:delegateExpression="${erpSyncDelegate}">
 *   <extensionElements>
 *     <flowable:field name="url" stringValue="https://erp.example.com/api/leave"/>
 *     <flowable:field name="payload" stringValue="{&quot;caseNo&quot;:&quot;${caseNo}&quot;}"/>
 *     <flowable:field name="resultVariable" stringValue="erpResponse"/>
 *   </extensionElements>
 * </serviceTask>
 * }</pre>
 *
 * <h2>欄位</h2>
 * <ul>
 *   <li>{@code url}（必填）：支援 {@code ${var}} 替換。</li>
 *   <li>{@code method}（可選）：POST／PUT，預設 POST。payload 是必填，
 *       因此只允許帶 body 的 method —— GET 的 body 不可互通（與 #49
 *       同一條理由）；PATCH 在 {@code HttpURLConnection}
 *       （{@link SafeRestClients} 的底層）不支援，會直接拋
 *       {@code Invalid HTTP method: PATCH}（單元測試實測），因此也擋在
 *       驗證層、不讓它變成一句誤導的「連線失敗」。</li>
 *   <li>{@code payload}（必填）：JSON 字串，支援 {@code ${var}} 替換，
 *       以 {@code application/json} 送出。<b>送出前會先驗證是合法 JSON</b>
 *       —— 非 JSON 的 payload 不會發請求，直接 BpmnError。驗證的是
 *       「是不是一份 JSON」，不限制形狀（物件／陣列／純量都接受）。</li>
 *   <li>{@code resultVariable}（可選）：2xx 時把回應 body 字串寫入該流程
 *       變數（body 為空 → 空字串；變數仍會被設定）。</li>
 * </ul>
 *
 * <h2>🔴 SSRF 閘門：{@link WebhookUrlPolicy} 是安全相依</h2>
 *
 * <p>與 {@link ExternalApiDelegate}（#49）完全同一條規則：url 一律先過
 * {@link WebhookUrlPolicy#rejectionReason(String)}，非 null 時丟
 * {@code BpmnError("ERP_SYNC_BLOCKED", 原因)}，<b>不發請求</b>。閘門
 * 必須在 payload 驗證之前 —— 被拒絕的目標連「payload 合不合法」都不該
 * 問，更不該碰。少了這一步，任何能部署 BPMN 的人就能讓伺服器去打
 * loopback／內網／雲端 metadata（169.254.169.254）。
 *
 * <p>閘門只檢查原始 URL，因此 client 必須不跟隨 3xx（由
 * {@link SafeRestClients} 集中保證）—— 否則被允許的主機可以用
 * {@code Location} 把請求帶去打 loopback，等於繞過政策。3xx 原樣回給
 * 這裡，落到下面的 {@code ERP_SYNC_FAILED}。
 *
 * <h2>失敗語意：全部是可建模的 BpmnError</h2>
 *
 * <ul>
 *   <li>{@code ERP_SYNC_BLOCKED}：被 SSRF 政策拒絕（非暫時性，重試
 *       沒有意義）。</li>
 *   <li>{@code ERP_SYNC_FAILED}：非 2xx／逾時／連線失敗，以及 url／
 *       payload 缺失、method 不合法、payload 非 JSON 這類設定錯誤。
 *       設計師用 boundary error 接住即可走補償路徑（改走人工、通知、
 *       稍後重試）。與 #48／#49 同一條分界：delegate 的失敗要嘛可建模、
 *       要嘛明顯 —— 這裡選可建模，因為 ERP 的失敗是<b>預期內</b>的業務
 *       情境。</li>
 * </ul>
 *
 * <p>訊息一律帶 url／method／狀態，讓「哪一個同步失敗」看得出來。
 * 刻意<b>不</b>把回應 body 放進訊息，也不放 payload 的解析器原文：兩者
 * 都可能含對方系統或案件的敏感資料，而 BpmnError 訊息會進 log 與流程
 * 歷史。payload 非 JSON 的訊息只帶行列位置（解析器原文可能含 payload
 * 片段）。
 *
 * <h2>逾時</h2>
 *
 * <p>沿用 {@code bpm.webhook.connect-timeout-ms}／{@code read-timeout-ms}
 * （與 {@code WebhookConsumer}、{@link ExternalApiDelegate} 同一份設定；
 * 預設 2000／5000 ms）。沒有逾時的 HTTP 呼叫會把引擎執行緒佔到 TCP
 * 逾時 —— 流程卡住，而 ERP 只是「慢」。
 *
 * <h2>與 #49 的關係：獨立實作，共用同一組安全縫</h2>
 *
 * <p>ERP 同步是外部 API 呼叫的特化，但契約不同：method 預設 POST
 * （#49 預設 GET）、payload 必填且必須是合法 JSON（#49 的 body 可選、
 * 不驗）、錯誤碼是 {@code ERP_SYNC_*}、method 集合不同。共用的部分是
 * 三個既有類別 —— {@link BpmnFieldSupport}（欄位解析）、
 * {@link WebhookUrlPolicy}（SSRF 閘門）、{@link SafeRestClients}
 * （不跟 3xx＋逾時），也就是<b>全部的安全規則</b>。沒有再抽一層帶
 * 多個開關的共用 helper：那會讓 #49 的行為取決於旗標，而兩個 delegate
 * 的流程差異（預設值、必填、驗證、錯誤碼）比共用片段多。規則仍然
 * 只有一份 —— 它們都在上面三個類別裡。
 *
 * <h2>⚠️ 不依賴 {@code flowable:field} 的 setter 注入</h2>
 *
 * <p>欄位一律用 {@link BpmnFieldSupport} 從 model 讀取；單例 bean 的
 * 注入競態與 7.2.0 預設 MIXED 模式的位元碼證據寫在該類別註解，通用
 * delegate 共用同一份說明。
 */
@Component("erpSyncDelegate")
public class ErpSyncDelegate implements JavaDelegate {

    private static final Logger log = LoggerFactory.getLogger(ErpSyncDelegate.class);

    /** SSRF 政策拒絕；不會發請求。 */
    public static final String ERROR_CODE_BLOCKED = "ERP_SYNC_BLOCKED";

    /** 非 2xx／逾時／連線失敗／設定錯誤。 */
    public static final String ERROR_CODE_FAILED = "ERP_SYNC_FAILED";

    /**
     * 允許的 method。payload 必填，所以只收帶 body、且
     * {@code HttpURLConnection} 支援的 method —— 見類別註解。
     */
    private static final Set<HttpMethod> ALLOWED_METHODS =
            Set.of(HttpMethod.POST, HttpMethod.PUT);

    private final WebhookUrlPolicy urlPolicy;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    /**
     * 沒有引擎依賴，因此不需要 {@code @Lazy}（理由同
     * {@link ExternalApiDelegate}）。{@code @Autowired} 是必要的：
     * 類別有兩個建構子（另一個給測試），Spring 需要知道用哪一個。
     */
    @Autowired
    public ErpSyncDelegate(WebhookUrlPolicy urlPolicy,
                           ObjectMapper objectMapper,
                           @Value("${bpm.webhook.connect-timeout-ms:2000}") long connectTimeoutMs,
                           @Value("${bpm.webhook.read-timeout-ms:5000}") long readTimeoutMs) {
        this(urlPolicy, objectMapper, SafeRestClients.create(connectTimeoutMs, readTimeoutMs));
    }

    /**
     * 測試用：直接注入建好的 {@link RestClient}（逾時由呼叫端設定）。
     * package-private，不給 Spring 用。
     */
    ErpSyncDelegate(WebhookUrlPolicy urlPolicy, ObjectMapper objectMapper, RestClient restClient) {
        this.urlPolicy = urlPolicy;
        this.objectMapper = objectMapper;
        this.restClient = restClient;
    }

    @Override
    public void execute(DelegateExecution execution) {
        call(execution);
    }

    /**
     * 可單獨測試的本文（package-private）：失敗丟 BpmnError，不吞例外。
     */
    void call(DelegateExecution execution) {
        ServiceTask task = execution.getCurrentFlowElement() instanceof ServiceTask st ? st : null;

        String url = BpmnFieldSupport.field(task, "url", execution);
        if (url == null || url.isBlank()) {
            throw failed(execution, "未設定 url（欄位不存在或替換後為空白）", null, null);
        }

        // ── SSRF 閘門：必須在建立請求、也在 payload 驗證之前 ───────────
        String reason = urlPolicy.rejectionReason(url);
        if (reason != null) {
            log.warn("ErpSyncDelegate 目標被拒絕，不發請求：processInstanceId={} url={} 原因={}",
                    execution.getProcessInstanceId(), url, reason);
            throw new BpmnError(ERROR_CODE_BLOCKED,
                    "ERP 同步目標被拒絕：" + reason + "（url=" + url + "）");
        }

        String methodRaw = BpmnFieldSupport.field(task, "method", execution);
        HttpMethod method = parseMethod(methodRaw);
        if (method == null) {
            throw failed(execution,
                    "不支援的 method '" + methodRaw + "'（只允許 POST／PUT）", url, null);
        }

        String payload = BpmnFieldSupport.field(task, "payload", execution);
        if (payload == null || payload.isBlank()) {
            throw failed(execution, "未設定 payload（欄位不存在或替換後為空白）", url, method);
        }
        String jsonError = jsonError(payload);
        if (jsonError != null) {
            // 不送一份對方一定解不開的 body：失敗要在「我們的」邊界發生，
            // 而不是變成 ERP 端一筆來路不明的 400。
            throw failed(execution, jsonError, url, method);
        }

        String resultVariable = BpmnFieldSupport.field(task, "resultVariable", execution);

        try {
            ResponseEntity<String> response = restClient.method(method).uri(url)
                    .contentType(MediaType.APPLICATION_JSON).body(payload)
                    .retrieve().toEntity(String.class);

            HttpStatusCode status = response.getStatusCode();
            if (!status.is2xxSuccessful()) {
                // RestClient 預設對 4xx／5xx 拋例外，正常走不到這裡；3xx 等
                // 其他狀態則會到這裡。仍要擋下，因為規格是「2xx 才算成功」。
                throw failed(execution, "非 2xx 回應：HTTP " + status.value(), url, method);
            }

            if (resultVariable != null && !resultVariable.isBlank()) {
                String responseBody = response.getBody() == null ? "" : response.getBody();
                execution.setVariable(resultVariable, responseBody);
            }
            log.info("ErpSyncDelegate 同步成功：processInstanceId={} method={} url={} status={}",
                    execution.getProcessInstanceId(), method, url, status.value());
        } catch (BpmnError e) {
            throw e; // 上面的明確失敗（含 policy）原樣往外
        } catch (RestClientResponseException e) {
            // 4xx／5xx。刻意不放 response body（可能含敏感資料）。
            throw failed(execution, "非 2xx 回應：HTTP " + e.getStatusCode().value(), url, method);
        } catch (Exception e) {
            // 逾時／連線失敗／DNS 等。message 帶底層原因，方便排查。
            throw failed(execution, "連線或逾時失敗：" + e.getMessage(), url, method);
        }
    }

    /**
     * 空白＝預設 POST；POST／PUT 不分大小寫；其他值（含 GET／DELETE／
     * PATCH）回 {@code null} 由呼叫端報錯。
     */
    static HttpMethod parseMethod(String raw) {
        if (raw == null || raw.isBlank()) return HttpMethod.POST;
        HttpMethod method;
        try {
            method = HttpMethod.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null; // 不是任何 HTTP method token
        }
        return ALLOWED_METHODS.contains(method) ? method : null;
    }

    /**
     * payload 的 JSON 驗證。
     *
     * <p>用 {@code FAIL_ON_TRAILING_TOKENS} 的 reader：{@code {"a":1} 垃圾}
     * 這種「開頭是 JSON、後面還有東西」也必須擋下 —— 那送到 ERP 一樣是
     * 壞的。共用注入的 {@link ObjectMapper} 但不改它的設定（reader 的
     * with 只影響這一份 reader）。
     *
     * @return 不合法時的訊息（只帶行列，不含解析器原文）；合法回
     *         {@code null}
     */
    private String jsonError(String payload) {
        try {
            objectMapper.reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(payload);
            return null;
        } catch (JacksonException e) {
            var location = e.getLocation();
            String where = location == null
                    ? ""
                    : "（第 " + location.getLineNr() + " 行第 " + location.getColumnNr() + " 欄）";
            return "payload 不是合法 JSON" + where;
        }
    }

    private BpmnError failed(DelegateExecution execution, String detail, String url, HttpMethod method) {
        String target = url == null ? ""
                : "（url=" + url + (method == null ? "" : "，method=" + method) + "）";
        String message = "ERP 同步失敗" + target + "：" + detail;
        log.warn("ErpSyncDelegate 失敗：processInstanceId={} {}",
                execution.getProcessInstanceId(), message);
        return new BpmnError(ERROR_CODE_FAILED, message);
    }
}
