package com.bpm.core.controller;

import com.bpm.core.security.AuthorityResolver;
import com.bpm.core.security.CallerId;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 「我是誰、我有什麼權限」—— 呼叫者查自己的身分。
 *
 * <h2>為什麼需要這個端點（#82）</h2>
 *
 * <p>授權規則用<b>權限碼</b>寫，但前端看不到權限碼：
 * {@code bpm-frontend/src/services/session.js} 的 {@code decodeToken()}
 * 只讀 JWT 的 {@code roles} claim。而後端 {@code AuthorityResolver} 的順序是
 * 「<b>JWT 的 roles claim 優先，沒有才回頭查權限中心</b>」
 * （見 {@code SecurityConfig.authoritiesFromJwt}）。於是 dev 與正式環境
 * 都會出現同一種斷裂，而且<b>方向相反</b>：
 *
 * <table border="1">
 *   <caption>三種身分、兩套答案</caption>
 *   <tr><th>身分</th><th>後端</th><th>前端（改動前）</th><th>UI 的結果</th></tr>
 *   <tr><td>{@code mgr001}（有 {@code bpm:form:design}）</td>
 *       <td>{@code POST /api/forms} 放行</td>
 *       <td>route 要求 {@code requiresRole: 'admin'} → 擋掉</td>
 *       <td>業務人員<b>看不到</b>表單設計功能</td></tr>
 *   <tr><td>{@code dir001}（有 {@code audit:log:read}）</td>
 *       <td>{@code /api/audit-logs/**} 放行</td>
 *       <td>route 要求 {@code requiresRole: 'auditor'} → 擋掉</td>
 *       <td>稽核職能<b>進不了頁面</b></td></tr>
 *   <tr><td>{@code admin001}（{@code *} → {@code ROLE_ADMIN}）</td>
 *       <td>管理頁與表單設計放行，但 {@code /api/audit-logs} 刻意只認
 *           {@code audit:log:read} → 403</td>
 *       <td>roles claim 為空 → {@code isAdmin=false} → 擋掉</td>
 *       <td>後端放行的管理頁前端看不到；稽核連 admin 都進不去</td></tr>
 * </table>
 *
 * <p>三列的成因都是同一個：<b>前端在猜</b>。前端猜錯時的症狀是
 * 「看得到選項、點下去被擋」（壞體驗）或「看不到自己明明有的功能」
 * （功能消失）。而 {@code session.js} 的檔案註解早已寫下當時的取捨：
 * 「前端猜錯只會產生看得到選項但點下去被擋的壞體驗，那種情況下選單少顯示
 * 一項比顯示一個點不動的項目好」—— 那是<b>沒有資料來源</b>時的降級策略。
 *
 * <p>本端點就是那份資料來源。有了它，前端不必再猜，
 * {@code session.js} 的態度也從「不推測」升級成「以權限中心／簽章 claim 為準」。
 * 後端則<b>零外部相依</b>：資料已經在手上。
 *
 * <h2>🔴 為什麼<b>沒有</b> {@code ?userId=} 參數</h2>
 *
 * <p><b>這是本端點最重要的約束，不要加參數。</b>
 *
 * <p><b>負向控制組實測（2026-10-01）</b>：刻意把實作換成
 * 「接受 {@code ?userId=} 並回傳那個人的權限碼」，
 * {@code MePermissionsEndpointTest} 的 10 條中 <b>7 條轉紅</b>
 * （含整組參數枚舉）。仍綠的 3 條是與本端點無關的既有政策
 * （未認證 401、閘道密鑰錯誤不構成身分、{@code /api/audit-logs}
 * 拒絕 ROLE_ADMIN）—— 它們本來就該綠，也正因如此才留著當對照組。
 *
 * <p>加了 {@code ?userId=其他人} 就是<b>一條新的枚舉通道</b>：
 * 「誰有哪些權限碼」本身就是敏感資訊 —— 它能畫出組織結構與職能配置
 * （誰是稽核職、誰有表單設計權、誰是管理員、部門主管有誰）。
 * 枚舉之後攻擊者能精準挑選攻擊目標（去攻沒有表單設計權但有
 * {@code hr:leave:approve} 的人），而這些資訊<b>不需要</b>付出任何
 * 被拒絕的代價。
 *
 * <p>這正是本專案過去幾輪關閉的每一條路：
 * {@code ProcessAccessGuard} 對可枚舉 id 回 404 而非 403
 * （403 會確認物件存在）、{@code AttachmentController} 不讓呼叫端指定
 * 「要看誰的附件」、{@code ExternalActorGuard} 不接受呼叫端自報身分。
 *
 * <p>本 repo 的對外 API 一律只認 JWT（以及閘道密鑰）帶的身分，
 * <b>沒有任何端點接受呼叫端指定「要看誰」</b>。本端點刻意維持同一條線：
 * 呼叫者身分只來自 {@link CallerId}（已認證的 principal），
 * 整個方法<b>沒有任何</b> {@code @RequestParam}。
 *
 * <p>「那讓管理員查別人的權限」呢？管理員要查組織／權限配置，
 * 該走<b>權限中心自己的介面</b>（{@code GET /api/permissions/{code}/users}，
 * 見 {@code docs/rbac-enterprise-backlog.md} §13.3），那份介面有它自己的
 * 授權政策與稽核，不該由 BPM 開一個後門轉送。
 *
 * <h2>資料來源：SecurityContext 的 authorities，不是權限中心</h2>
 *
 * <p>回的是<b>這一次請求實際被授權的 authorities</b>，而不是
 * {@code BpmPermissionService.getUserPermissions()}。差別只在
 * 「JWT 帶了 roles claim」時才出現，而那正是兩者會打架的情況：
 *
 * <ul>
 *   <li>JWT <b>沒有</b> roles claim → authorities 由權限中心轉換而來，
 *       與 {@code getUserPermissions()} 完全相同。</li>
 *   <li>JWT <b>有</b> roles claim → {@code authoritiesFromJwt} 採用 claim
 *       <b>並且不去查權限中心</b>。此時權限中心的答案是<b>權限中心自己
 *       沒被問到</b>的東西：回它等於回一個與實際授權無關的清單，
 *       前端會據此顯示與後端行為不一致的選單 —— 正是本端點要消滅的那種症狀。</li>
 * </ul>
 *
 * <p>所以「權限中心是授權的事實來源」這句話在 claim 存在時不成立；
 * 唯一能保證「前端看到的 == 後端實際用來判斷的」就是 authorities 本身。
 *
 * <h2>回應欄位的切分：{@code ROLE_} 前綴</h2>
 *
 * <p>{@link AuthorityResolver} 會為每個 claim 項目同時加上
 * {@code ROLE_<大寫>} 與原字串，並把通配 {@code *} 轉成
 * {@link AuthorityResolver#ROLE_ADMIN}。本端點把它拆成兩個欄位：
 *
 * <ul>
 *   <li>{@code permissions}：<b>不以 {@code ROLE_} 開頭</b>的 authorities
 *       —— 權限碼（{@code bpm:form:design}）與非角色字串。
 *       閘道認證附帶的 {@code ROLE_GATEWAY} 也因此不會外洩出去。</li>
 *   <li>{@code admin}：是否持有 {@link AuthorityResolver#ROLE_ADMIN}。</li>
 * </ul>
 *
 * <p>⚠️ <b>{@code admin} 不是「什麼都能做」。</b>它只對應
 * {@code hasRole(ADMIN)} 那幾條規則。{@code /api/audit-logs/**}
 * 刻意不接受它（見 {@code SecurityConfig} 該條規則旁的註解），
 * 所以 {@code admin001} 會拿到 {@code admin=true} 而
 * {@code permissions} 不含 {@code audit:log:read} —— 那正是前端要複製的差異。
 */
@RestController
@RequestMapping("/api/me")
public class MeController {

    /**
     * Spring Security 內部用來表示「角色」的前綴。
     *
     * <p>與 {@code SecurityConfig.hasRole} 組出 {@code ROLE_} 的那個字面值
     * 是同一個約定；放在這裡常數化，是為了讓「哪些 authority 是權限碼」
     * 這個判斷只有一處。
     */
    private static final String ROLE_PREFIX = "ROLE_";

    /**
     * 呼叫者自己的權限。
     *
     * <p>授權：只需要登入。刻意<b>不</b>要求任何權限碼 ——
     * 一個連自己的權限都查不到的人，無法判斷自己該不該做某件事，
     * 只能靠試。而且「查自己的權限」不可能洩漏任何東西：
     * 答案與 {@code SecurityConfig} 對同一個請求的判斷完全相同。
     *
     * <p>未認證時回 401（不是 403）：目前 {@code SecurityConfig} 沒有
     * 為本路徑加規則，它是落在 {@code /api/** → authenticated()} 上被擋的
     * —— 也就是說<b>這個端點沒有為自己新增任何授權規則</b>，
     * 授權矩陣完全沒有被修改過（見類別註解與本方法的說明）。
     *
     * <p>⚠️ <b>沒有 {@code @RequestParam}。</b>理由見類別註解的 🔴 段落：
     * 一旦接受「要看誰」，本端點就變成組織結構的枚舉通道。
     *
     * @param callerId 已認證的呼叫者（JWT 的 {@code sub} 或閘道認證過的身分）
     */
    @GetMapping("/permissions")
    public MyPermissions myPermissions(@CallerId String callerId) {
        if (callerId == null || callerId.isBlank()) {
            // 正常情況下到不了這裡（/api/** 是 authenticated()）。
            // 真的到了代表授權層被改動過，那時寧可明確失敗，也不要
            // 用 null 身分去查權限 —— 那是「回傳一份沒有意義的權限清單」。
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }

        // ⚠️ 這裡讀 SecurityContext 而非只用 @CallerId：
        // @CallerId 只給 principal 名稱，權限碼在 authorities 裡。
        // 身分的名稱仍以 @CallerId 為準 —— 推導身分的定義只有
        // CallerIdArgumentResolver 一處（見該類別註解）。
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }

        // TreeSet：排序讓回應是決定性的（測試與 diff 都看得出差別），
        // 而 hasAuthority 的比對本來就與順序無關。
        Set<String> permissions = new TreeSet<>();
        boolean admin = false;
        for (GrantedAuthority a : auth.getAuthorities()) {
            String value = a.getAuthority();
            if (value == null || value.isBlank()) continue;
            if (AuthorityResolver.ROLE_ADMIN.equals(value)) {
                admin = true;
                continue;
            }
            if (value.startsWith(ROLE_PREFIX)) continue;   // ROLE_GATEWAY 等角色標記
            permissions.add(value);
        }
        return new MyPermissions(callerId, List.copyOf(permissions), admin);
    }

    /**
     * 呼叫者自己的權限清單。
     *
     * @param userId      已認證的呼叫者 id
     * @param permissions 權限碼（不含 {@code ROLE_*} 角色標記）
     * @param admin       是否持有 {@code ROLE_ADMIN}（由權限中心的 {@code *} 轉換而來）
     */
    public record MyPermissions(String userId, List<String> permissions, boolean admin) {
    }
}