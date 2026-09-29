package com.bpm.core.security;

import com.bpm.core.service.BpmPermissionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 把使用者身分轉換成 Spring Security 的 authorities。
 *
 * <h2>兩個來源，JWT claim 優先</h2>
 *
 * <ol>
 *   <li><b>JWT 的 {@code roles} claim</b>：企業 IdP 已經知道這個人的角色時，
 *       那是權威來源 —— 它經過簽章，而且不需要再打一次權限中心。</li>
 *   <li><b>權限中心</b>（{@link BpmPermissionService}）：claim 裡沒有 roles 時
 *       回頭查。這條路在 dev 與「IdP 只管認證、不管授權」的部署下是主要路徑。</li>
 * </ol>
 *
 * <h2>為什麼權限碼直接當 authority，而不映射成少數幾個角色</h2>
 *
 * <p>本專案的權限碼是階層式的（{@code finance:payment:approve}），
 * 映射成 ROLE_FINANCE 之類的粗粒度角色會丟失資訊，而且映射表本身就是
 * 一份會漂移的政策複本。直接把權限碼當 authority，授權規則就能寫成
 * {@code hasAuthority("audit:log:read")} —— 與權限中心用的是同一個詞彙。
 *
 * <h2>⚠️ 通配權限 {@code *} 必須特別處理</h2>
 *
 * <p>mock fixture 給 admin001 的權限是 {@code ["*"]}，而
 * {@code BpmPermissionService.hasPermission} 認得這個通配。但如果照字面當成
 * authority，就會產生一個名字叫 {@code *} 的 authority ——
 * 而 {@code hasAuthority("audit:log:read")} 不會命中它。
 *
 * <p>結果會是：擁有全部權限的管理員<b>被拒絕存取</b>。這種 bug 很難察覺，
 * 因為它只影響通配持有者，而開發時通常就是用管理員在測。
 * 所以 {@code *} 明確轉成 {@link #ROLE_ADMIN}，授權規則再一併接受它。
 */
@Component
public class AuthorityResolver {

    private static final Logger log = LoggerFactory.getLogger(AuthorityResolver.class);

    /** 通配權限持有者。授權規則一律同時接受這個角色。 */
    public static final String ROLE_ADMIN = "ROLE_ADMIN";

    /** 權限中心用的通配符號，見類別註解。 */
    static final String WILDCARD = "*";

    /** 可檢視稽核紀錄。依 2026-09-29 的政策決策，由權限中心指派。 */
    public static final String PERM_AUDIT_READ = "audit:log:read";

    private final BpmPermissionService permissionService;

    public AuthorityResolver(BpmPermissionService permissionService) {
        this.permissionService = permissionService;
    }

    /**
     * JWT 的 {@code roles} claim → authorities。
     *
     * <p>claim 為空時回空集合，呼叫端據此決定要不要退回權限中心 ——
     * 這裡不自己做那個決定，因為「claim 是空的」與「claim 不存在」
     * 在不同 IdP 上的表現不同，判斷放在一處比較清楚。
     */
    public Collection<GrantedAuthority> fromJwtRoles(List<String> roles) {
        if (roles == null || roles.isEmpty()) return List.of();
        Set<GrantedAuthority> out = new LinkedHashSet<>();
        for (String role : roles) {
            if (role == null || role.isBlank()) continue;
            String r = role.trim();
            if (WILDCARD.equals(r) || "admin".equalsIgnoreCase(r)) {
                out.add(new SimpleGrantedAuthority(ROLE_ADMIN));
                continue;
            }
            // claim 裡若已經帶 ROLE_ 前綴就不重複加，否則 hasRole 會找不到。
            out.add(new SimpleGrantedAuthority(
                    r.startsWith("ROLE_") ? r : "ROLE_" + r.toUpperCase(java.util.Locale.ROOT)));
            // 同時保留原字串，讓 hasAuthority 也能用同一個詞彙命中。
            out.add(new SimpleGrantedAuthority(r));
        }
        return out;
    }

    /**
     * 權限中心 → authorities。
     *
     * <p>權限中心不可用時回空集合而非拋例外：那會讓<b>認證</b>因為<b>授權</b>
     * 來源故障而失敗，使用者看到的是 401（帳號有問題）而不是 403（權限不足），
     * 排查方向完全被誤導。空集合的結果是「已認證但無權限」——
     * 需要權限的端點回 403，只需要登入的端點仍可用。
     *
     * <p>這是刻意的降級方向：權限中心掛掉時，簽核類端點會擋下來（安全），
     * 但使用者至少能登入並看到錯誤訊息。
     */
    public Collection<GrantedAuthority> fromPermissionCentre(String userId) {
        if (userId == null || userId.isBlank()) return List.of();
        List<String> perms;
        try {
            perms = permissionService.getUserPermissions(userId);
        } catch (Exception e) {
            log.warn("權限中心查詢失敗（userId={}），本次以「已認證但無權限」處理: {}",
                    userId, e.toString());
            return List.of();
        }

        Set<GrantedAuthority> out = new LinkedHashSet<>();
        for (String perm : perms) {
            if (perm == null || perm.isBlank()) continue;
            String p = perm.trim();
            if (WILDCARD.equals(p)) {
                out.add(new SimpleGrantedAuthority(ROLE_ADMIN));
                continue;
            }
            out.add(new SimpleGrantedAuthority(p));
        }
        return out;
    }
}
