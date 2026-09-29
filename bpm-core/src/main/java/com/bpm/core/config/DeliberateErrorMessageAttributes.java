package com.bpm.core.config;

import jakarta.servlet.RequestDispatcher;
import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.boot.web.servlet.error.DefaultErrorAttributes;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * #73：只把<b>刻意丟出</b>的錯誤訊息回給呼叫端。
 *
 * <h2>問題</h2>
 *
 * <p>本 repo 未設 {@code server.error.include-message}，Spring Boot 3 的預設是
 * {@code never}。而 {@code DefaultErrorAttributes} 會照著那個選項把
 * {@code message} 從回應中<b>移除</b>（{@code ErrorAttributeOptions.retainIncluded}
 * 逐一刪掉未被包含的鍵，javap 確認）。於是 30 處精心寫的
 * {@code ResponseStatusException(...)} 理由只存在於伺服器端日誌，
 * 呼叫端只看到 {@code "error":"Bad Request"}。
 *
 * <p>這讓 #66 選定的政策失去實務意義：#66 的整個重點就是
 * 「明確 400 拒絕，並在訊息裡說清楚要改什麼」，而呼叫端看不到那句話，
 * 只會知道「400」，然後不知道該改 payload 還是改流程選擇。
 *
 * <h2>為什麼不用 {@code server.error.include-message=always}</h2>
 *
 * <p>那是最短的路，但它會把<b>所有</b>例外訊息都送出去，包含 NPE 的
 * 「Cannot invoke ... because ... is null」、SQL 例外裡的表名與欄位值、
 * 連線字串裡的主機與帳號。那是資訊外洩，不是錯誤訊息改善。
 *
 * <h2>為什麼不用全域 {@code @RestControllerAdvice}</h2>
 *
 * <p>2026-09-29（#69）已決定<b>不</b>加全域 advice：它會改變全 repo 每個端點的
 * 回應形狀，且與本項無關。{@code ErrorAttributes} 是不同機制 ——
 * 它只影響「錯誤回應怎麼組」，不影響任何成功路徑，也不會攔截請求。
 *
 * <h2>這個類別怎麼分辨「刻意」與「意外」</h2>
 *
 * <p>實測（javap 對照 Spring Framework 6.2.19 的
 * {@code ResponseStatusExceptionResolver}）後得到的判準：
 * <ul>
 *   <li>{@code ResponseStatusException} → resolver 只呼叫
 *       {@code response.sendError(status, reason)}，
 *       <b>不會</b>把例外掛到 {@code jakarta.servlet.error.exception}。
 *       到了 {@code /error} 那次 ERROR dispatch，兩個例外屬性都是 null，
 *       但 {@code jakarta.servlet.error.message} 就是那句 reason。</li>
 *   <li>NPE／SQL 錯誤 → 沒有人呼叫 {@code sendError}，例外直接穿出 servlet，
 *       由容器掛上 {@code jakarta.servlet.error.exception}。
 *       這時只認「有例外傳到容器」→ 不回 {@code message}。</li>
 * </ul>
 *
 * <p>因此規則是：<b>只有在「沒有任何例外傳到容器」時</b>才回傳
 * {@code jakarta.servlet.error.message}。這個判準對意外例外是
 * <b>結構性不可能</b>成立 —— 它一定伴隨例外屬性。
 *
 * <p>⚠️ 同時支援「例外屬性本身就是 {@code ResponseStatusException}」：
 * 那條路徑存在於 MockMvc 與將來自訂的 resolver，目前用不到，
 * 但它讓規則不必依賴 servlet 容器把錯誤屬性搬到什麼位置。
 *
 * <h2>這個規則實際涵蓋的範圍（比「只有 ResponseStatusException」略寬）</h2>
 *
 * <p>javap 對照後確認，{@code sendError(status, reason)} 有<b>兩個</b>呼叫端：
 * <ol>
 *   <li>{@code ResponseStatusExceptionResolver.applyStatusAndReason} ——
 *       本 repo 全部刻意丟出的訊息走這條（也就是 #73 的目標）。</li>
 *   <li>{@code DefaultHandlerExceptionResolver.handleErrorResponse} ——
 *       Spring 自己的 {@code ErrorResponse} 例外走這條，
 *       例如 {@code NoResourceFoundException}（URL 不存在）會帶出
 *       「No static resource …」。</li>
 * </ol>
 *
 * <p>兩者在 {@code /error} 的屬性上<b>完全無法區分</b>，要區分就必須攔截
 * 例外解析器本身，而那正是 #69 已決定不採用的全域 advice 路線。
 *
 * <p>所以這裡採取的實際不變量是：
 * <b>永不外洩 {@code Throwable.getMessage()}；只外洩「已經被寫成給人看的
 * 診斷字串」</b>。第二類的字串來自框架或本 repo，都是低敏的
 * （「No static resource …」、「Request method … not supported」），
 * 而第一類才是 NPE 的 {@code Cannot invoke …}、SQL 例外裡的表名與欄位值。
 * 這個不變量<b>嚴格強於</b> {@code server.error.include-message=always}
 * （後者用 {@code error.getMessage()}，即第一類）。
 *
 * <p>若政策要求「一個字都不能多」，唯一剩下的做法是在解析器層標記
 * 「這是 RSE」，代價是新增全域機制 —— 已列為需要政策決定的取捨。
 *
 * <h2>⚠️ 不改變狀態碼</h2>
 *
 * <p>本類別只往回應的 map 加一個鍵。狀態碼在原本那次請求（或那次
 * ERROR dispatch 的進入點）就已經決定，且完全取決於
 * {@code jakarta.servlet.error.status_code}。非預期例外仍然是 500。
 */
@Component
public class DeliberateErrorMessageAttributes extends DefaultErrorAttributes {

    /**
     * Boot 3.x 的簽章是 {@code (WebRequest, ErrorAttributeOptions)}；
     * 3.x 之前是 {@code (HttpServletRequest, ...)}。
     * 已用 javap 對 {@code spring-boot-3.5.16}.jar 確認（{@code javap
     * org.springframework.boot.web.servlet.error.ErrorAttributes}）。
     */
    @Override
    public Map<String, Object> getErrorAttributes(WebRequest webRequest, ErrorAttributeOptions options) {
        Map<String, Object> attributes = super.getErrorAttributes(webRequest, options);
        if (webRequest instanceof ServletWebRequest servlet) {
            String deliberate = deliberateReason(servlet);
            if (deliberate != null) {
                // 刻意丟出的訊息是為了讓呼叫端知道要改什麼，不是系統內部細節。
                attributes.put("message", deliberate);
            }
        }
        return attributes;
    }

    /** 刻意丟出的理由；不是刻意丟出的（以及沒有錯誤脈絡的）回 {@code null}。 */
    private String deliberateReason(ServletWebRequest webRequest) {
        Throwable error = resolveThrowable(webRequest);
        // 規則一：例外本身就是刻意丟出的 —— 直接取它的 reason。
        if (error instanceof ResponseStatusException rse) {
            return rse.getReason();
        }
        // 規則二：有例外傳到容器（NPE、SQL 錯誤…）→ 維持不外洩。
        if (error != null) {
            return null;
        }
        // 規則三：沒有錯誤脈絡（有人直接 GET /error）→ 什麼都不加。
        if (webRequest.getRequest().getAttribute(RequestDispatcher.ERROR_STATUS_CODE) == null) {
            return null;
        }
        // 規則四：狀態碼是例外解析器用 sendError(status, reason) 設定的。
        // 也就是「已經被寫成給人看的診斷字串」—— 本 repo 全部刻意丟出的
        // 訊息走這條，加上 Spring 自己的 ErrorResponse 理由字串
        // （NoResourceFoundException 的「No static resource …」等）。
        // 精確範圍與取捨見類別註解。
        //
        // 沒有 reason 的 ResponseStatusException 會走 sendError(status)，
        // 這個屬性因而缺席 → 回 null（不放入 message），不會 NPE。
        Object reason = webRequest.getRequest().getAttribute(RequestDispatcher.ERROR_MESSAGE);
        return reason instanceof String s && !s.isBlank() ? s : null;
    }

    /**
     * 取出這次錯誤的例外，沒有就回 {@code null}。
     *
     * <p>用 {@code RequestDispatcher.ERROR_EXCEPTION}（即字串
     * {@code jakarta.servlet.error.exception}）。Spring 6.1 移除了
     * {@code DispatcherServlet.ERROR_EXCEPTION_ATTRIBUTE}，而
     * {@code DefaultErrorAttributes} 自己讀的也正是同一個字串
     * （javap 確認），所以這裡與框架保持一致。
     *
     * <p>兩條路徑都由這一個屬性涵蓋：例外直接穿出 servlet 時是容器放的，
     * 而被 handler／resolver 正常處理掉時是 Spring 放的。
     */
    private static Throwable resolveThrowable(ServletWebRequest webRequest) {
        Object attr = webRequest.getRequest().getAttribute(RequestDispatcher.ERROR_EXCEPTION);
        return attr instanceof Throwable t ? t : null;
    }
}
