package com.bpm.core.engine;

import com.bpm.core.webhook.WebhookUrlPolicy;
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
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.Locale;

/**
 * 通用外部 API 呼叫 delegate（#49）：流程走到節點時發一個 HTTP 請求。
 *
 * <pre>{@code
 * <serviceTask id="callApi" flowable:delegateExpression="${externalApiDelegate}">
 *   <extensionElements>
 *     <flowable:field name="url" stringValue="https://erp.example.com/api/leave/${caseNo}"/>
 *     <flowable:field name="method" stringValue="POST"/>
 *     <flowable:field name="body" stringValue="{"days":${days}}"/>
 *     <flowable:field name="resultVariable" stringValue="erpResponse"/>
 *   </extensionElements>
 * </serviceTask>
 * }</pre>
 *
 * <h2>欄位</h2>
 * <ul>
 *   <li>{@code url}（必填）：支援 {@code ${var}} 替換。</li>
 *   <li>{@code method}（可選）：GET／POST／PUT，預設 GET；其他值直接
 *       BpmnError，不發請求。</li>
 *   <li>{@code body}（可選）：支援 {@code ${var}} 替換，以
 *       {@code application/json} 送出。<b>GET ＋ body 是明確錯誤</b>
 *       （HTTP GET 的 body 不可互通，且 HttpURLConnection 對 GET 寫 output
 *       的行為依實作而異）—— 丟 BpmnError 而不是靜默丟掉 body。</li>
 *   <li>{@code resultVariable}（可選）：2xx 時把回應 body 字串寫入該流程
 *       變數（body 為空 → 空字串；變數仍會被設定）。</li>
 * </ul>
 *
 * <h2>🔴 SSRF 閘門：{@link WebhookUrlPolicy} 是安全相依</h2>
 *
 * <p>BPMN 由業務人員在設計器編輯，這個 delegate 等於「讓 BPMN 能發任意
 * HTTP」。因此 url 一律先過 {@link WebhookUrlPolicy#rejectionReason(String)}
 * —— 本 repo 唯一一份 SSRF 閘門（與 #67 同一句話）。非 null 時丟
 * {@code BpmnError("EXTERNAL_API_BLOCKED", 原因)}，<b>不發請求</b>。
 * 少了這一步，任何能部署 BPMN 的人就能讓伺服器去打 loopback／內網／
 * 雲端 metadata（169.254.169.254）。
 *
 * <h2>失敗語意：全部是可建模的 BpmnError</h2>
 *
 * <ul>
 *   <li>{@code EXTERNAL_API_BLOCKED}：被 SSRF 政策拒絕（非暫時性，重試
 *       沒有意義）。</li>
 *   <li>{@code EXTERNAL_API_FAILED}：非 2xx／逾時／連線失敗，以及
 *       url 缺失、method 不合法、GET＋body 這類設定錯誤。設計師用
 *       boundary error 接住即可走補償路徑（改走人工、通知、稍後重試）。
 *       與 #48 同一條分界：delegate 的失敗要嘛可建模、要嘛明顯 ——
 *       這裡選可建模，因為外部系統的失敗是<b>預期內</b>的業務情境。</li>
 * </ul>
 *
 * <p>訊息一律帶 method／url／狀態，讓「哪一個呼叫失敗」看得出來。
 * 刻意<b>不</b>把回應 body 放進訊息：body 可能含對方系統的敏感資料，
 * 而 BpmnError 訊息會進 log 與流程歷史。
 *
 * <h2>逾時</h2>
 *
 * <p>沿用 {@code bpm.webhook.connect-timeout-ms}／{@code read-timeout-ms}
 * （與 {@code WebhookConsumer} 同一份設定；預設 2000／5000 ms）。
 * 沒有逾時的 HTTP 呼叫會把引擎執行緒佔到 TCP 逾時 —— 流程卡住，
 * 而外部系統只是「慢」。
 *
 * <h2>⚠️ 不依賴 {@code flowable:field} 的 setter 注入</h2>
 *
 * <p>欄位一律用 {@link BpmnFieldSupport} 從 model 讀取；單例 bean 的注入
 * 競態與 7.2.0 預設 MIXED 模式的位元碼證據寫在該類別註解，三個通用
 * delegate 共用同一份說明。
 */
@Component("externalApiDelegate")
public class ExternalApiDelegate implements JavaDelegate {

    private static final Logger log = LoggerFactory.getLogger(ExternalApiDelegate.class);

    /** SSRF 政策拒絕；不會發請求。 */
    public static final String ERROR_CODE_BLOCKED = "EXTERNAL_API_BLOCKED";

    /** 非 2xx／逾時／連線失敗／設定錯誤。 */
    public static final String ERROR_CODE_FAILED = "EXTERNAL_API_FAILED";

    private final WebhookUrlPolicy urlPolicy;
    private final RestClient restClient;

    /**
     * 沒有引擎依賴，因此不需要 {@code @Lazy}（理由同
     * {@link DataValidationDelegate}）。{@code @Autowired} 是必要的：
     * 類別有兩個建構子（另一個給測試），Spring 需要知道用哪一個。
     */
    @Autowired
    public ExternalApiDelegate(WebhookUrlPolicy urlPolicy,
                               @Value("${bpm.webhook.connect-timeout-ms:2000}") long connectTimeoutMs,
                               @Value("${bpm.webhook.read-timeout-ms:5000}") long readTimeoutMs) {
        this(urlPolicy, buildRestClient(connectTimeoutMs, readTimeoutMs));
    }

    /**
     * 測試用：直接注入建好的 {@link RestClient}（逾時由呼叫端設定）。
     * package-private，不給 Spring 用。
     */
    ExternalApiDelegate(WebhookUrlPolicy urlPolicy, RestClient restClient) {
        this.urlPolicy = urlPolicy;
        this.restClient = restClient;
    }

    /**
     * 與 {@code WebhookConsumer} 相同的形狀：{@code RestClient.create()}
     * 沒有逾時，這裡明確設定。
     */
    static RestClient buildRestClient(long connectTimeoutMs, long readTimeoutMs) {
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        requestFactory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        return RestClient.builder().requestFactory(requestFactory).build();
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
            throw failed(execution, "未設定 url（欄位不存在或替換後為空白）", null);
        }

        // ── SSRF 閘門：必須在建立請求之前 ─────────────────────────────
        String reason = urlPolicy.rejectionReason(url);
        if (reason != null) {
            log.warn("ExternalApiDelegate 目標被拒絕，不發請求：processInstanceId={} url={} 原因={}",
                    execution.getProcessInstanceId(), url, reason);
            throw new BpmnError(ERROR_CODE_BLOCKED,
                    "外部 API 目標被拒絕：" + reason + "（url=" + url + "）");
        }

        String methodRaw = BpmnFieldSupport.field(task, "method", execution);
        HttpMethod method = parseMethod(methodRaw);
        if (method == null) {
            throw failed(execution,
                    "不支援的 method '" + methodRaw + "'（只允許 GET／POST／PUT）", url);
        }

        String body = BpmnFieldSupport.field(task, "body", execution);
        if (method == HttpMethod.GET && body != null && !body.isBlank()) {
            // 靜默丟掉 body 會讓設計師以為送出去了；HttpURLConnection 對 GET
            // 寫 output 的行為也不可互通。明確失敗。
            throw failed(execution, "GET 不支援 body；請改用 POST／PUT", url);
        }

        String resultVariable = BpmnFieldSupport.field(task, "resultVariable", execution);

        try {
            RestClient.RequestBodySpec request = restClient.method(method).uri(url);
            if (body != null && !body.isBlank()) {
                request = request.contentType(MediaType.APPLICATION_JSON).body(body);
            }
            ResponseEntity<String> response = request.retrieve().toEntity(String.class);

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
            log.info("ExternalApiDelegate 呼叫成功：processInstanceId={} method={} url={} status={}",
                    execution.getProcessInstanceId(), method, url, status.value());
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

    /** GET／POST／PUT；空白＝預設 GET；其他值回 {@code null} 由呼叫端報錯。 */
    static HttpMethod parseMethod(String raw) {
        if (raw == null || raw.isBlank()) return HttpMethod.GET;
        return switch (raw.trim().toUpperCase(Locale.ROOT)) {
            case "GET" -> HttpMethod.GET;
            case "POST" -> HttpMethod.POST;
            case "PUT" -> HttpMethod.PUT;
            default -> null;
        };
    }

    private BpmnError failed(DelegateExecution execution, String detail, String url) {
        String message = "外部 API 呼叫失敗"
                + (url == null ? "" : "（url=" + url + "）")
                + "：" + detail;
        log.warn("ExternalApiDelegate 失敗：processInstanceId={} {}",
                execution.getProcessInstanceId(), message);
        return new BpmnError(ERROR_CODE_FAILED, message);
    }
}
