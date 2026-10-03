package com.bpm.core.client;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.PortUnreachableException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

/**
 * 組織／權限系統呼叫失敗的統一型別（#8／#9 正式化）。
 *
 * <h2>為什麼要有自己的型別</h2>
 *
 * <p>改動前失敗的形狀完全由 Spring 的預設錯誤處理決定：
 * 404 → {@code HttpClientErrorException.NotFound}、5xx →
 * {@code HttpServerErrorException}、逾時／拒線 → {@code ResourceAccessException}。
 * 這些型別<b>沒有任何一個欄位說得出「是哪個系統、哪一條路徑」</b> ——
 * 服務名只能從例外類型猜（org 與 perm 的失敗長得一模一樣），
 * 路徑只能從訊息字串裡撈。當兩個外部系統同時存在，日誌與告警無法分辨
 * 「組織系統掛了」與「權限系統掛了」，而這兩者的處置不同。
 *
 * <p>本型別把三件事變成<b>結構化欄位</b>：{@link #service()}、
 * {@link #status()}、{@link #path()}。呼叫端與維運不必解析訊息字串。
 *
 * <h2>⚠️ 絕不複製 response body</h2>
 *
 * <p>外部系統的錯誤回應可能包含內部細節（堆疊、SQL、人員資料）。
 * 本專案對外的錯誤訊息紅線是「只回自己刻意丟出的理由」（見
 * {@code ErrorMessageDisclosureTest}）。因此：
 * <ul>
 *   <li>HTTP 失敗路徑<b>不保留</b>原始例外，也不讀取 body ——
 *       連 {@code getResponseBodyAsString()} 都不會有東西可拿。</li>
 *   <li>連線／逾時路徑保留的 cause 是 JDK 的 {@link IOException}
 *       （沒有 response body 這種東西）。</li>
 *   <li>{@link #getMessage()} 只含服務名、路徑、狀態碼與例外類名，
 *       不含 header（token 在 header 裡）也不含 body。</li>
 * </ul>
 *
 * <h2>為什麼 extends {@link RestClientException}</h2>
 *
 * <p>既有呼叫端用 {@code catch (RestClientException)} 把「外部系統失敗」
 * 一律翻成 503（例如 {@code TaskController.urgeApplicantOf} 與
 * {@code SubstituteForwardController}）。本型別是那條規則的替代品，
 * 必須落在同一個 catch 網裡，否則會出現「換了型別、失敗卻變成裸 500」
 * 的回歸。繼承既有的例外樹是為了保留呼叫端的 catch 語意，不是為了
 * 沿用它的內容。
 *
 * <h2>與 404 的關係（服務層語意不變）</h2>
 *
 * <p>404 仍然是「明確的拒絕」（查無此人／查無此權限碼），
 * 由 {@link #isNotFound()} 表達；服務層既有的 fail-closed 行為不變：
 * {@code OrgService.getDirectManager} 不吞例外、不快取、不回 null。
 * 「鏈頂人員沒有主管」是 200 + {@code {}}，不是 404 —— 這個區分
 * 是 {@code ExternalActorGuard} 判斷 400（改 payload）與 503（重試）的依據，
 * 見該類別註解「分界線是 HTTP 404」。
 */
public class ExternalApiException extends RestClientException {

    /** 失敗發生在哪一個外部系統。 */
    public enum Service {
        ORG("org", "組織系統"),
        PERM("perm", "權限系統");

        private final String code;
        private final String label;

        Service(String code, String label) {
            this.code = code;
            this.label = label;
        }

        /** 日誌與訊息用的短名（{@code org}／{@code perm}）。 */
        public String code() {
            return code;
        }

        /** 中文全名，給錯誤訊息用。 */
        public String label() {
            return label;
        }
    }

    /**
     * 失敗的種類。
     *
     * <p>「HTTP 有回應但狀態碼非 2xx」與「根本沒拿到回應」必須分得開：
     * 前者可能是對方明確拒絕（404），後者一律是基礎設施故障。
     * {@code TIMEOUT} 與 {@code CONNECTION} 分開的理由是處置不同 ——
     * 逾時通常代表對方忙或網路慢（重試可能成功），拒線代表位址或埠
     * 設定錯了（重試不會成功）。
     */
    public enum Kind {
        /** 非 2xx 的 HTTP 回應。 */
        HTTP_STATUS,
        /** 連線失敗：拒線／DNS 查不到／不可達。 */
        CONNECTION,
        /** 連線或讀取逾時。 */
        TIMEOUT,
        /** 其他 I/O 失敗（連線被重置、SSL 失敗等）。 */
        IO
    }

    private final Service service;
    private final Kind kind;
    private final HttpStatusCode status;
    private final String path;

    private ExternalApiException(Service service, Kind kind, HttpStatusCode status,
                                 String path, String detail, Throwable cause) {
        super("[%s] %s 呼叫失敗：%s".formatted(service.code(), path, detail), cause);
        this.service = service;
        this.kind = kind;
        this.status = status;
        this.path = path;
    }

    /**
     * 非 2xx 的 HTTP 回應。
     *
     * <p>⚠️ 刻意<b>不</b>接收 {@code ClientHttpResponse} 或原始例外：
     * 不讀 body、不保留能讀 body 的參照（見類別註解「絕不複製 response body」）。
     * 除錯需要的 stack 由拋出點自己提供，不需要把對方的回應一起帶著走。
     */
    public static ExternalApiException httpStatus(Service service, HttpStatusCode status, String path) {
        String reason = status instanceof HttpStatus httpStatus ? " " + httpStatus.getReasonPhrase() : "";
        return new ExternalApiException(service, Kind.HTTP_STATUS, status, path,
                "HTTP " + status.value() + reason, null);
    }

    /**
     * 連線／逾時／其他 I/O 失敗。
     *
     * <p>cause 是 JDK 的 {@link IOException}（或包著它的 Spring 例外），
     * 不是含 response body 的 {@code HttpStatusCodeException} —— 這條路徑
     * 根本沒有 HTTP 回應可洩漏。
     */
    public static ExternalApiException io(Service service, String path, Exception cause) {
        return new ExternalApiException(service, classify(cause), null, path, describe(cause), cause);
    }

    /**
     * 由 cause 鏈判斷種類。
     *
     * <p>走訪整個 cause 鏈而不是只看最外層：Spring 可能用
     * {@code ResourceAccessException} 包住真正的 {@code SocketTimeoutException}，
     * 而分類需要的是裡面的那個。
     */
    private static Kind classify(Throwable cause) {
        for (Throwable current = cause; current != null; current = current.getCause()) {
            if (current instanceof SocketTimeoutException) return Kind.TIMEOUT;
            if (current instanceof ConnectException
                    || current instanceof UnknownHostException
                    || current instanceof NoRouteToHostException
                    || current instanceof PortUnreachableException) {
                return Kind.CONNECTION;
            }
        }
        return Kind.IO;
    }

    /** 只取類名與 JDK 訊息；IOException 的訊息不含 response body。 */
    private static String describe(Throwable cause) {
        String message = cause.getMessage();
        return cause.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }

    /** 失敗發生在哪一個外部系統。 */
    public Service service() {
        return service;
    }

    /** 失敗種類。 */
    public Kind kind() {
        return kind;
    }

    /** HTTP 狀態碼；連線／逾時等「沒有回應」的情形為 {@code null}。 */
    public HttpStatusCode status() {
        return status;
    }

    /** 被呼叫的路徑（不含 query 與 base URL）。 */
    public String path() {
        return path;
    }

    /**
     * 外部系統是否<b>明確拒絕</b>（HTTP 404）。
     *
     * <p>這是服務層語意的唯一判準：404 = 查無此人／查無此權限碼，
     * 其餘（含 401／403／5xx／逾時）都是故障，不可混為一談。
     */
    public boolean isNotFound() {
        return status != null && status.value() == 404;
    }
}
