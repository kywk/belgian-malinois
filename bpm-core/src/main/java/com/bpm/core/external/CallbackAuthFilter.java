package com.bpm.core.external;

import com.bpm.core.model.ExternalSystem;
import com.bpm.core.repository.ExternalSystemRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * {@code /api/callback/**} 的認證過濾器（工項 #21，per-system callback secret）。
 *
 * <h2>認證鏈</h2>
 *
 * <ol>
 *   <li>三個標頭必須齊全：{@code X-System-Id}、{@code X-Callback-Signature}、
 *       {@code X-Callback-Timestamp}。</li>
 *   <li>依 systemId 查系統。<b>查不到 → 401</b>。</li>
 *   <li>系統必須已設定 callback secret（{@code callbackSecret != null}）。
 *       <b>未設定 → 401</b>。</li>
 *   <li>時間戳必須在 ±5 分鐘內（防重放）。<b>超窗 → 401</b>。</li>
 *   <li>HMAC-SHA256（密鑰＝該系統的 callback secret，對<b>原始 body 位元組</b>）
 *       必須等於 {@code sha256=<hex>}。<b>不符 → 401</b>。</li>
 * </ol>
 *
 * <p>通過認證後才進入授權：停用／IP 白名單／allowedActions 由
 * {@link ExternalSystemAccessGuard} 判定（<b>403</b>，規則與 {@code /api/external/**}
 * 同一份）。{@code allowedActions} 的空值＝不限制，沿用
 * {@link ExternalSystemPolicy}。
 *
 * <h2>⚠️ 為什麼所有 401 都回同一句 {@code Authentication failed}</h2>
 *
 * <p>規格要求「不可洩漏系統是否存在的差異訊息」。上面的第 2～5 步若各自回
 * 不同訊息（「查無此系統」／「未設定密鑰」／「時間戳過期」／「簽章錯誤」），
 * 攻擊者不必持有任何密鑰就能用回應內容把 systemId 一個一個分類出來。
 * 因此對外一律同一句；<b>真正的原因只寫進 WARN log</b>（含「未設定密鑰，
 * 請管理員 rotate-callback-secret」這種運維線索）。
 *
 * <p>唯一例外是「標頭缺漏」：它與系統存不存在無關，且不區分會讓正常呼叫端
 * 拿不到「你少帶了什麼」的線索，所以單獨回一句缺哪些標頭。
 *
 * <h2>為什麼停用／IP 的檢查排在簽章驗證<b>之後</b></h2>
 *
 * <p>它們會透露「這個系統存在且被停用」與「你的來源 IP 是什麼」。
 * 排在簽章之後，只有持有密鑰的呼叫端看得到這些訊息（與
 * {@code ExternalApiAuthFilter} 的處境相同：那裡也要先有正確的 API key）。
 *
 * <h2>為什麼過濾器要自己讀 body</h2>
 *
 * <p>簽章是對原始位元組計算的（見 {@link CallbackSignatureUtil}），
 * 而 servlet 的 input stream 只能讀一次。這裡把請求包成
 * {@link CachedBodyRequest}，先讀完再驗章，之後 controller 仍能讀到同一份
 * 位元組。快取上限 1 MiB —— 回呼 payload 是流程變數，不是檔案上傳。
 *
 * <h2>被拒絕的請求只寫 log、不寫稽核</h2>
 *
 * <p>與 {@code ExternalApiAuthFilter} 的拒絕稽核不同（工項 #21 的明確決策）：
 * 回呼的 401／403 是未經驗證的流量，寫進稽核只會讓稽核庫被掃描流量灌滿。
 * 成功（含 duplicate 的第一次）才寫 {@code EXTERNAL_API_CALL}。
 */
@Component
public class CallbackAuthFilter extends OncePerRequestFilter {

    static final String PREFIX = "/api/callback/";
    static final String SYSTEM_HEADER = "X-System-Id";
    static final String SIGNATURE_HEADER = "X-Callback-Signature";
    static final String TIMESTAMP_HEADER = "X-Callback-Timestamp";

    /** 回呼在 allowedActions 裡的 action 名稱。 */
    static final String CALLBACK_ACTION = "callback";

    /** 時間戳允許的時鐘偏移（過去與未來都適用）。 */
    static final Duration MAX_TIMESTAMP_SKEW = Duration.ofMinutes(5);

    /** body 快取上限：回呼只帶流程變數，1 MiB 遠超合理用量。 */
    static final int MAX_BODY_BYTES = 1024 * 1024;

    /** 所有認證失敗的對外訊息。刻意只有一句，見類別註解。 */
    static final String AUTH_FAILED = "Authentication failed";

    private static final Logger log = LoggerFactory.getLogger(CallbackAuthFilter.class);

    private final ExternalSystemRepository repo;
    private final ExternalSystemAccessGuard accessGuard;
    private final ObjectMapper objectMapper;

    public CallbackAuthFilter(ExternalSystemRepository repo,
                              ExternalSystemAccessGuard accessGuard,
                              ObjectMapper objectMapper) {
        this.repo = repo;
        this.accessGuard = accessGuard;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        // 同時涵蓋不帶尾斜線的 "/api/callback"，避免 prefix 檢查的常見破口。
        return !uri.startsWith(PREFIX) && !uri.equals("/api/callback");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String systemId = request.getHeader(SYSTEM_HEADER);
        String signature = request.getHeader(SIGNATURE_HEADER);
        String timestamp = request.getHeader(TIMESTAMP_HEADER);
        if (isBlank(systemId) || isBlank(signature) || isBlank(timestamp)) {
            // 缺標頭與系統存不存在無關，可以講清楚；其餘一律同一句。
            reject(response, 401,
                    "Missing " + SYSTEM_HEADER + ", " + SIGNATURE_HEADER + " or " + TIMESTAMP_HEADER);
            return;
        }

        ExternalSystem sys = repo.findBySystemId(systemId).orElse(null);
        if (sys == null) {
            log.warn("回呼認證失敗：查無系統 systemId={} ip={}", systemId, request.getRemoteAddr());
            reject(response, 401, AUTH_FAILED);
            return;
        }
        if (isBlank(sys.getCallbackSecret())) {
            // 運維線索只進 log：對外若區分這個分支，就等於回答了「這個系統存在嗎」。
            log.warn("回呼認證失敗：系統 {} 尚未設定 callback secret（管理員可呼叫 "
                    + "POST /api/admin/external-systems/{}/rotate-callback-secret 產生）",
                    systemId, systemId);
            reject(response, 401, AUTH_FAILED);
            return;
        }

        Instant ts = CallbackSignatureUtil.parseTimestamp(timestamp);
        if (ts == null || exceedsSkew(ts)) {
            log.warn("回呼認證失敗：時間戳無效或超出 ±{} 分鐘 systemId={} timestamp={}",
                    MAX_TIMESTAMP_SKEW.toMinutes(), systemId, timestamp);
            reject(response, 401, AUTH_FAILED);
            return;
        }

        CachedBodyRequest cached;
        try {
            cached = new CachedBodyRequest(request, MAX_BODY_BYTES);
        } catch (BodyTooLargeException e) {
            reject(response, 413, "Request body too large");
            return;
        } catch (IOException e) {
            log.warn("回呼請求 body 讀取失敗 systemId={}: {}", systemId, e.toString());
            reject(response, 400, "Unable to read request body");
            return;
        }

        if (!CallbackSignatureUtil.verify(sys.getCallbackSecret(), cached.cachedBody(), signature)) {
            log.warn("回呼簽章驗證失敗 systemId={} ip={}", systemId, request.getRemoteAddr());
            reject(response, 401, AUTH_FAILED);
            return;
        }

        // ── 認證完成，進入授權（403）───────────────────────────────
        var denied = accessGuard.rejectSystemOrIp(sys, request.getRemoteAddr());
        if (denied.isEmpty()) {
            denied = accessGuard.rejectAction(sys, CALLBACK_ACTION);
        }
        if (denied.isPresent()) {
            log.warn("回呼授權拒絕 systemId={} ip={}: {}",
                    systemId, request.getRemoteAddr(), denied.get().reason());
            reject(response, denied.get().status(), denied.get().reason());
            return;
        }

        // 與 ExternalApiAuthFilter 相同的 lastUsedAt 維護（R-09 的已知效能債）。
        sys.setLastUsedAt(Instant.now());
        repo.save(sys);

        request.setAttribute("externalSystem", sys);
        request.setAttribute("externalSystemId", systemId);

        chain.doFilter(cached, response);
    }

    private static boolean exceedsSkew(Instant ts) {
        return Duration.between(ts, Instant.now()).abs().compareTo(MAX_TIMESTAMP_SKEW) > 0;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private void reject(HttpServletResponse response, int status, String reason) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write(objectMapper.writeValueAsString(
                Map.of("error", reason, "status", status)));
    }

    /**
     * 把 body 讀進記憶體，讓「驗章」與「controller 反序列化」看到同一份位元組。
     *
     * <p>{@link HttpServletRequestWrapper} 的 {@code getInputStream()} 每次回傳
     * 一個新的 {@link ByteArrayInputStream}，所以過濾器讀過之後 Spring MVC 仍能
     * 再讀一次。超過上限時在建構子就擋掉，不把整個 body 讀進來。
     */
    static final class CachedBodyRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        CachedBodyRequest(HttpServletRequest request, int maxBytes)
                throws IOException, BodyTooLargeException {
            super(request);
            byte[] read = request.getInputStream().readNBytes(maxBytes + 1);
            if (read.length > maxBytes) {
                throw new BodyTooLargeException();
            }
            this.body = read;
        }

        byte[] cachedBody() {
            return body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return in.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener readListener) {
                    throw new UnsupportedOperationException(
                            "回呼 body 已完整快取，不支援非同步讀取");
                }

                @Override
                public int read() {
                    return in.read();
                }
            };
        }

        @Override
        public BufferedReader getReader() throws IOException {
            return new BufferedReader(new InputStreamReader(getInputStream(), charset()));
        }

        private Charset charset() {
            String encoding = getCharacterEncoding();
            // 與 Spring MVC 的 JSON 解析一致：沒有指定時用 UTF-8，
            // 而不是容器預設的 ISO-8859-1。
            return isBlank(encoding) ? StandardCharsets.UTF_8 : Charset.forName(encoding);
        }
    }

    /** body 超過 {@link #MAX_BODY_BYTES}。 */
    static final class BodyTooLargeException extends Exception {
        BodyTooLargeException() {
            super("callback body exceeds " + MAX_BODY_BYTES + " bytes");
        }
    }
}
