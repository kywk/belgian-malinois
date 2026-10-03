package com.bpm.core.client;

import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.ResponseErrorHandler;

import java.io.IOException;
import java.net.URI;

/**
 * 組織／權限 client 的失敗映射（#8／#9）。
 *
 * <h2>兩個掛載點，剛好對應兩種失敗</h2>
 *
 * <p>Spring 的 {@code RestClient} 把失敗分成兩條路徑，而它們發生的位置不同：
 *
 * <ul>
 *   <li><b>有回應、但狀態碼非 2xx</b>：由 {@code retrieve()} 的錯誤處理器
 *       在回應之後觸發 → 用 {@code defaultStatusHandler} 換掉。</li>
 *   <li><b>沒有回應</b>（拒線／逾時／I/O）：在請求執行時就拋出 →
 *       用 request interceptor 攔下。⚠️ 這裡收到的是<b>原始
 *       {@link IOException}</b>（例如 {@code SocketTimeoutException}），
 *       不是 {@code ResourceAccessException} —— 後者由
 *       {@code DefaultRequestBodyUriSpec.exchange} 在 interceptor 鏈
 *       <b>之外</b>才包裝。兩者都接住是為了不依賴那個實作細節。</li>
 * </ul>
 *
 * <h2>為什麼集中在這裡</h2>
 *
 * <p>org 與 perm 各六／四個方法，逐一 try/catch 會有十份長得幾乎一樣的
 * 映射程式碼 —— 任何一份漏掉或寫歪，那個端點的失敗就會退回 Spring 的
 * 原始型別（服務名與路徑消失）。掛在 client 建構時，映射與方法數量無關，
 * 新增端點自動套用（「規則只有一份」）。
 */
final class ExternalApiErrors {

    private ExternalApiErrors() {
    }

    /**
     * 非 2xx 一律映射成 {@link ExternalApiException}。
     *
     * <p>⚠️ {@code hasError} 用 {@code !is2xxSuccessful()} 而不是 Spring 預設的
     * {@code isError()}：{@code isError()} 只涵蓋 4xx／5xx，3xx 會被當成成功
     * 而進入 body 解析 —— 一個不該出現的 3xx（例如未帶 Location 的 302）
     * 會變成難以理解的「無法解析內容」而不是「外部系統回了非預期狀態」。
     * 逾時與拒線不受影響（它們走 interceptor）。
     */
    static ResponseErrorHandler statusHandler(ExternalApiException.Service service) {
        return new ResponseErrorHandler() {
            @Override
            public boolean hasError(ClientHttpResponse response) throws IOException {
                return !response.getStatusCode().is2xxSuccessful();
            }

            @Override
            public void handleError(URI url, HttpMethod method, ClientHttpResponse response)
                    throws IOException {
                // 只取狀態碼與路徑；不呼叫 response.getBody()，
                // 所以外部系統的錯誤內容不會進入本服務（見 ExternalApiException 的紅線）。
                throw ExternalApiException.httpStatus(service, response.getStatusCode(), url.getPath());
            }
        };
    }

    /** 連線／逾時／其他 I/O 失敗一律映射成 {@link ExternalApiException}。 */
    static org.springframework.http.client.ClientHttpRequestInterceptor connectionInterceptor(
            ExternalApiException.Service service) {
        return (request, body, execution) -> {
            try {
                return execution.execute(request, body);
            } catch (IOException | ResourceAccessException e) {
                throw ExternalApiException.io(service, request.getURI().getPath(), e);
            }
        };
    }
}
