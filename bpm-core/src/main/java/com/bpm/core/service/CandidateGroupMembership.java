package com.bpm.core.service;

import com.bpm.core.security.AuthorityResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 「這個呼叫者屬於哪些候選群組」（#71）。
 *
 * <h2>為什麼需要這個元件</h2>
 *
 * <p>修掉 {@code GET /api/tasks} 的授權缺口時，有一個看似無解的問題：
 * BPMN 的 {@code flowable:candidateGroups} 會產生<b>沒有受理人</b>的任務，
 * 而呼叫端不能自稱「我是某個群組」。若因此完全不查群組，
 * 設計器的「所屬單位」選項（{@code assigneeExpressions.js} 的 ownDept →
 * {@code ${orgService.getDeptId(initiator)}}）所產生的任務，就會從所有人的
 * 收件匣消失。
 *
 * <p>而「查不到就不查」正是本專案已經付出過代價的錯誤取捨：見
 * {@code DuplicateApprovalFilterTest} —— 候選任務被靜默隱藏會讓案件卡死，
 * 而且沒有任何錯誤訊息。因此正確方向是<b>由伺服器算出呼叫端所屬的群組</b>。
 *
 * <h2>候選群組的字串從哪裡來</h2>
 *
 * <p>本 repo 裡 {@code candidateGroups} 的值只有三個來源：
 * <ol>
 *   <li><b>部門代碼</b>：{@code ${orgService.getDeptId(initiator)}}（ownDept），
 *       或業務人員在「特定單位（指定代碼）」手填的字面值。
 *       這類群組的名字就是部門代碼 → {@link OrgService#getDeptId} 直接給出。</li>
 *   <li><b>權限碼</b>：手填進「特定單位」的欄位可能被填成權限碼
 *       （本專案的權限碼是階層式命名，用來命名群組是常見的）。
 *       → {@link BpmPermissionService#getUserPermissions}。</li>
 *   <li><b>SecurityContext 的 authorities</b>：補上前兩者查不到的路徑 ——
 *       IdP 在 JWT 的 {@code roles} claim 裡帶了角色時，
 *       {@code AuthorityResolver} 就<b>不會</b>去查權限中心，
 *       此時 {@code getUserPermissions} 回空集合，但那個角色確實是群組。</li>
 * </ol>
 *
 * <p>三個來源都取聯集（而不是挑一個）。少算一個群組的後果是
 * 「某人看不到分給他群組的任務」—— 那是靜默卡死；多算一個的後果只是
 * 多查一批本來也沒結果的群組。兩者不對稱，所以寧可多不可少。
 *
 * <h2>⚠️ 為什麼沿用 OrgService／BpmPermissionService 而不是直接打 org service</h2>
 *
 * <p>{@code application.yml:175-184} 記載了那條自我呼叫的 HTTP 路徑：
 * bpm-core 對自己發同步請求、每次任務建立占用 2 個 Tomcat 執行緒、
 * 快取失效瞬間有自我死鎖的疑慮。<b>本呼叫不在交易內</b>（待辦是唯讀查詢），
 * 風險比簽核路徑低得多，但仍不可繞過快取：繞過就是在每一筆待辦查詢都製造
 * 那個風險，而不是每 60 分鐘一次。
 *
 * <p>而且這兩個方法<b>已經</b>是既有元件的公開 API：
 * {@code getDeptId} 有 60 分鐘 TTL、{@code getUserPermissions} 有 5 分鐘 TTL，
 * 且都已有失效路徑（{@code CacheInvalidateController}）。繞過它們等於
 * 另外寫一份沒有失效機制的查詢。
 */
@Service
public class CandidateGroupMembership {

    private static final Logger log = LoggerFactory.getLogger(CandidateGroupMembership.class);

    /**
     * 權限中心的通配符號。
     *
     * <p>持有 {@code *} 的人不會因此看到任何群組任務 —— 通配是授權語意，
     * 不是群組名稱。把它放進查詢只會產生一個永遠查不到東西的 {@code IN} 項目。
     */
    private static final String WILDCARD = "*";

    private final OrgService orgService;
    private final BpmPermissionService permissionService;

    public CandidateGroupMembership(OrgService orgService, BpmPermissionService permissionService) {
        this.orgService = orgService;
        this.permissionService = permissionService;
    }

    /**
     * 呼叫者所屬的候選群組（部門代碼 ∪ 權限碼 ∪ 非 {@code ROLE_} 開頭的 authority）。
     *
     * <h2>⚠️ 為什麼外部系統故障時<b>不</b>讓整個待辦端點 500</h2>
     *
     * <p>待辦清單的主要內容（指派給我的 + 我是候選人的）全部來自本地 Flowable
     * 查詢，完全不需要外部系統。讓組織系統的一次逾時把整個收件匣變成
     * 500，代價遠大於收益：使用者會以為系統壞了，而且群組任務本來就是
     * 較少見的那一類。
     *
     * <p>但也不能假裝沒發生 —— 記 warn。所以這裡的失敗方向是
     * <b>「這一次看不到群組任務」</b>（fail-closed，不外洩），
     * 而非「看到不該看到的」或「整個功能不可用」。
     */
    public Set<String> groupsOf(String callerId) {
        Set<String> out = new LinkedHashSet<>();
        if (callerId == null || callerId.isBlank()) return out;

        try {
            addIfPresent(out, orgService.getDeptId(callerId));
        } catch (Exception e) {
            // fail-closed 的 mock 對「fixture 裡沒有的人」丟 404；
            // 真實組織系統掛掉時則是連線錯誤。兩者都不該讓待辦整個不可用。
            log.warn("查不到 {} 的所屬部門，本次待辦不會包含「所屬單位」群組的任務: {}",
                    callerId, e.toString());
        }

        try {
            for (String perm : permissionService.getUserPermissions(callerId)) {
                addIfPresent(out, perm);
            }
        } catch (Exception e) {
            log.warn("查不到 {} 的權限清單，本次待辦不會包含以權限碼命名的群組任務: {}",
                    callerId, e.toString());
        }

        // JWT roles claim 那條路不會查權限中心（見類別註解），
        // 那些 authority 就是唯一已知的群組線索。框架標記要排除：
        // ROLE_（Spring 的角色表示法）與 FACTOR_（Security 7 的認證因子）
        // —— 判斷只有 AuthorityResolver.isPermissionCode 一份。
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated()) {
            for (var a : auth.getAuthorities()) {
                String v = a.getAuthority();
                if (AuthorityResolver.isPermissionCode(v)) {
                    addIfPresent(out, v);
                }
            }
        }
        return out;
    }

    private static void addIfPresent(Set<String> target, String raw) {
        if (raw == null) return;
        for (String v : List.of(raw.split(","))) {
            String t = v.trim();
            if (t.isEmpty() || WILDCARD.equals(t)) continue;
            target.add(t);
        }
    }
}
