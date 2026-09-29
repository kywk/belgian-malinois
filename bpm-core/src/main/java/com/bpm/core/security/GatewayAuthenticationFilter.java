package com.bpm.core.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Server 之間呼叫的認證：信任閘道注入的身分。
 *
 * <h2>這條路徑與 JWT 的分工</h2>
 *
 * <ul>
 *   <li><b>使用者直接使用</b> → JWT（{@code Authorization: Bearer}）。
 *       身分由企業 IdP 簽章，本服務只驗證。</li>
 *   <li><b>Server 之間透過 API 觸發</b> → 本過濾器。呼叫方是機器，
 *       沒有人類憑證；由閘道負責認證，並注入 {@code X-User-Id}
 *       （代某人執行）或 {@code X-System-Id}（系統自身）。</li>
 * </ul>
 *
 * <h2>⚠️ 為什麼不能只看標頭</h2>
 *
 * <p>改動前整個平台的身分就是<b>裸的 {@code X-User-Id} 標頭</b> ——
 * 任何能連到應用 port 的人填什麼就是什麼。所有 P0／P1／P2 修好的授權檢查
 * 都建立在這個假前提上：檢查邏輯正確，但輸入可以偽造。
 *
 * <p>所以「閘道注入的身分」必須先證明<b>請求真的來自閘道</b>。本過濾器要求：
 * <ol>
 *   <li>共用密鑰標頭比對成功（常數時間比對，避免計時側通道）；<b>且</b></li>
 *   <li>若設定了來源 IP 白名單，來源必須在名單內。</li>
 * </ol>
 *
 * <p>密鑰是必要條件而非選項。純 IP 白名單在容器環境不可靠（IP 是動態的），
 * 而且同一個 Docker 網段內的任何容器都會通過 —— 那不是「信任閘道」，
 * 是「信任整個網段」。
 *
 * <h2>預設關閉</h2>
 *
 * <p>{@code bpm.security.gateway.enabled} 預設 false。漏設的結果是
 * 「server 之間的路徑不可用」而非「任何人都能偽造身分」——
 * 遺漏設定必須倒向安全的那一邊（同 P2-7 對 mock 的處理）。
 */
public class GatewayAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(GatewayAuthenticationFilter.class);

    public static final String USER_HEADER = "X-User-Id";
    public static final String SYSTEM_HEADER = "X-System-Id";
    public static final String SECRET_HEADER = "X-Gateway-Secret";

    /** 經由閘道認證的呼叫者都帶這個 authority，授權規則可據此區分。 */
    public static final String ROLE_GATEWAY = "ROLE_GATEWAY";

    private final boolean enabled;
    private final byte[] expectedSecret;
    private final List<String> trustedProxies;
    private final AuthorityResolver authorityResolver;

    public GatewayAuthenticationFilter(boolean enabled, String sharedSecret,
                                        List<String> trustedProxies,
                                        AuthorityResolver authorityResolver) {
        this.enabled = enabled;
        this.expectedSecret = sharedSecret == null
                ? new byte[0] : sharedSecret.getBytes(StandardCharsets.UTF_8);
        // ⚠️ 空白項目必須濾掉。@Value("${...:}") 綁到 List<String> 時，
        // 未設定會變成 [""] 而不是空清單 —— 那會讓 isEmpty() 為 false、
        // 而唯一的樣式又比對不到任何位址，於是「未設定白名單」被誤解成
        // 「白名單裡沒有任何人」，所有閘道請求全被拒。
        this.trustedProxies = trustedProxies == null ? List.of()
                : trustedProxies.stream().filter(p -> p != null && !p.isBlank()).map(String::trim).toList();
        this.authorityResolver = authorityResolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                     FilterChain chain) throws ServletException, IOException {
        // 已經有認證（例如 JWT 先通過）就不覆蓋 —— 使用者的 JWT 身分
        // 優先於閘道注入的標頭，否則一個能碰到閘道密鑰的人就能冒用任何人。
        if (!enabled || SecurityContextHolder.getContext().getAuthentication() != null) {
            chain.doFilter(request, response);
            return;
        }

        String presented = request.getHeader(SECRET_HEADER);
        if (presented == null) {
            chain.doFilter(request, response);
            return;
        }

        if (!secretMatches(presented)) {
            // 不要在這裡回 401 —— 讓它以未認證的身分繼續，由授權層決定。
            // 這樣「密鑰錯」與「沒帶密鑰」對外表現相同，不洩漏密鑰是否接近。
            log.warn("閘道密鑰比對失敗（來源 {}），本次請求視為未認證", request.getRemoteAddr());
            chain.doFilter(request, response);
            return;
        }

        if (!sourceAllowed(request.getRemoteAddr())) {
            log.warn("閘道密鑰正確但來源 {} 不在信任名單內，本次請求視為未認證",
                    request.getRemoteAddr());
            chain.doFilter(request, response);
            return;
        }

        String userId = firstNonBlank(request.getHeader(USER_HEADER), request.getHeader(SYSTEM_HEADER));
        if (userId == null) {
            log.warn("閘道請求缺少 {} 與 {}，無法判斷身分", USER_HEADER, SYSTEM_HEADER);
            chain.doFilter(request, response);
            return;
        }

        // authorities = ROLE_GATEWAY + 該身分在權限中心的權限。
        //
        // 只給 ROLE_GATEWAY 是不夠的：閘道說「這是 admin001」時，
        // admin001 應該就擁有 admin001 的權限，否則管理端點會對
        // 經由閘道的呼叫一律 403 —— 那不是「更安全」，
        // 而是讓 server 之間的路徑無法完成它該做的事，
        // 逼人把規則放寬到 authenticated()。
        //
        // ROLE_GATEWAY 額外保留，讓授權規則能區分「機器代為呼叫」
        // 與「使用者本人操作」（/api/internal/** 就只接受前者）。
        var authorities = new java.util.ArrayList<org.springframework.security.core.GrantedAuthority>();
        authorities.add(new org.springframework.security.core.authority.SimpleGrantedAuthority(ROLE_GATEWAY));
        authorities.addAll(authorityResolver.fromPermissionCentre(userId));

        var auth = new UsernamePasswordAuthenticationToken(userId, null, authorities);
        auth.setDetails(request.getRemoteAddr());
        SecurityContextHolder.getContext().setAuthentication(auth);

        chain.doFilter(request, response);
    }

    /**
     * 常數時間比對。
     *
     * <p>{@code String.equals} 會在第一個不同的位元組就返回，
     * 讓攻擊者能用回應時間逐位元組猜出密鑰。
     * {@code MessageDigest.isEqual} 是為此設計的。
     */
    private boolean secretMatches(String presented) {
        if (expectedSecret.length == 0) return false;   // 未設定密鑰 → 一律不通過
        return MessageDigest.isEqual(expectedSecret, presented.getBytes(StandardCharsets.UTF_8));
    }

    /** 未設定白名單時不限制來源（密鑰仍是必要條件）。 */
    private boolean sourceAllowed(String remoteAddr) {
        if (trustedProxies.isEmpty()) return true;
        if (remoteAddr == null) return false;
        return trustedProxies.stream().anyMatch(p -> matches(p.trim(), remoteAddr));
    }

    /**
     * 只支援完整位址與前綴比對，刻意不實作 CIDR。
     *
     * <p>自己寫 CIDR 解析容易出錯（掩碼邊界、IPv6 縮寫），而錯的方向是
     * 「放行了不該放行的網段」。前綴比對的行為一眼可驗證，
     * 真正需要 CIDR 時應該交給閘道或防火牆 —— 那是它們的職責。
     */
    private static boolean matches(String pattern, String remoteAddr) {
        if (pattern.isEmpty()) return false;
        return pattern.endsWith(".")
                ? remoteAddr.startsWith(pattern)
                : pattern.equals(remoteAddr);
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a.trim();
        if (b != null && !b.isBlank()) return b.trim();
        return null;
    }
}
