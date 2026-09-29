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
 * 所以 {@code *} 明確轉成 {@link #ROLE_ADMIN}。
 *
 * <h2>「授權規則要不要一併接受 ROLE_ADMIN」是<b>每一條規則自己的</b>政策決定</h2>
 *
 * <p>本類別只負責把 {@code *} 轉成 {@code ROLE_ADMIN}，至於某條規則是否
 * 順帶接受這個角色，<b>不在這裡決定</b> —— 見 {@code SecurityConfig}：
 *
 * <ul>
 *   <li>{@code /api/audit-logs/**}：<b>不接受</b>。稽核紀錄含全公司薪資、
 *       簽核意見與核決金額，而 {@code ProcessAccessGuard.requireReadAccess}
 *       刻意只認 {@code #PERM_AUDIT_READ}。若這裡放行 {@code ROLE_ADMIN}，
 *       「不能讀別人案件變數」就會被「可以翻全公司稽核紀錄」從側門繞過
 *       （{@code AuditEvent.detail} 會帶整包 variables，
 *       見 {@code ExternalApiController.completeTask}）。</li>
 *   <li>{@code /api/forms/**} 的寫入：<b>接受</b>。表單 schema 是設定資產，
 *       納入系統管理的職責合理；而通配持有者本來就是超級使用者，
 *       剝奪他的表單設計權只會造成實際困擾，換不到任何安全收益
 *       （form schema 沒有跨部門的隱私欄位，它影響的是流程行為而非個人資料）。</li>
 * </ul>
 *
 * <p>換言之，「管理員可不可以看全部資料」與「管理員可不可以改系統設定」
 * 是兩個問題。這兩條規則刻意給出不同答案，理由分別寫在
 * {@code SecurityConfig} 對應的規則旁。
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

    /**
     * 可設計表單：建立 draft、改 schemaJson、發布、封存、刪除。
     *
     * <h2>為什麼是新的權限碼，而不是直接綁 {@code ROLE_ADMIN}</h2>
     *
     * <p>改動前 {@code FormDefinitionController} 的 {@code @RequestMapping} 是
     * {@code /api/forms}（<b>不是</b> {@code /api/admin/forms}），所以它落在
     * {@code SecurityConfig} 最後的 {@code /api/** → authenticated()} ——
     * 任何登入者都能建立 draft、改 schemaJson、發布、封存與刪除。
     * 而 {@code GET /api/forms} 直接回傳所有 draft 的 id，不需要猜 UUID。
     *
     * <p>後果不是「表單被弄亂」，而是<b>流程本身的完整性</b>：依 spec §8.5，
     * 表單欄位 id 就是流程變數名，所以一個有權改 schema 的人只要加一個欄位
     * id 叫 {@code approved}，送件人在填表時就能決定簽核結果 ——
     * 而流程定義與稽核都不會留下這條路徑被使用的痕跡。
     *
     * <p>但直接綁 {@code ROLE_ADMIN} 會<b>直接擋掉產品目標</b>：本專案的定位是
     * 「讓業務人員自行設計、部署、維運 BPMN 流程」
     * （{@code FormService.createNextDraft} 的註解以它為前提）。
     * 綁 ADMIN 等於把表單設計收回給 IT，而 IT 從來不是流程內容的專家 ——
     * 那正是這個低程式碼平台要解決的問題。所以需要一個<b>可以指派給業務人員</b>
     * 的權限碼，這是它存在的唯一理由。
     *
     * <p>命名沿用 {@code domain:resource:action} 三段式（見
     * {@code docs/rbac-enterprise-backlog.md} §13.2），
     * {@code bpm} 是本系統自己的命名空間 —— 它不是任何一個業務網域的簽核權限，
     * 混進 {@code hr:*}／{@code finance:*} 會讓權限中心的管理者誤以為它可被
     * 依部門指派。
     */
    public static final String PERM_FORM_DESIGN = "bpm:form:design";

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
