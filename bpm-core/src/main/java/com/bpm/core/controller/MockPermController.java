package com.bpm.core.controller;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/**
 * 開發用的權限系統替身。
 *
 * <h2>⚠️ 這個 mock 必須 fail-closed（security-audit P2-7）</h2>
 *
 * <p>改動前 {@code getUsersByPermission} 對<b>不認識的權限碼</b>回
 * {@code ["mgr001"]}。所以一個打錯的、或尚未在權限系統建好的權限碼，
 * 會靜默地把簽核任務派給 mgr001 —— 流程順利跑完，權責卻是錯的。
 *
 * <p>驗收測試因此永遠會過（任何權限碼都拿得到人），而上線後對接真實權限系統，
 * 同一個權限碼拿到空集合或 404，流程才卡住。<b>綠燈掩蓋了問題。</b>
 * 見 {@link MockOrgController} 的類別註解。
 *
 * <h2>解析型與判定型端點的差別</h2>
 *
 * <ul>
 *   <li><b>解析型</b>（「誰持有這個權限」）查不到就回 404。這類回答決定任務派給誰，
 *       猜錯的代價是簽核權責錯置。</li>
 *   <li><b>判定型</b>（「這個人有沒有這個權限」）查不到就回 {@code false}／空集合。
 *       那本身就是 fail-closed（拒絕），而且維持了 API 契約的單純 ——
 *       不要讓一次權限拒絕變成 500。</li>
 * </ul>
 */
/**
 * ⚠️ 必須用 mock-enabled 明確開啟（security-audit P2-7）。
 *
 * <p>改動前這個 controller 沒有任何 profile 或條件限制，任何環境都會註冊；
 * 而三個 compose 檔都用同一個 {@code docker} profile，所以 profile 擋不住它。
 * 用預設 false 的明確開關，漏設的結果是「mock 不存在」而非「mock 生效」——
 * 遺漏設定時必須倒向安全的那一邊。
 *
 * <p>設定不一致（開關與 URL 對不上）會在啟動時被
 * {@link com.bpm.core.config.ExternalSystemUrlValidator} 攔下。
 */
@ConditionalOnProperty(name = "bpm.external.mock-enabled", havingValue = "true")
@RestController
@RequestMapping("/mock/perm/api")
public class MockPermController {

    private static final Map<String, List<String>> PERM_USERS = new HashMap<>();
    private static final Map<String, List<String>> USER_PERMS = new HashMap<>();

    static {
        PERM_USERS.put("hr:leave:approve",      List.of("mgr001", "mgr002", "dir001"));
        PERM_USERS.put("finance:payment:approve", List.of("mgr001", "dir001"));
        PERM_USERS.put("purchase:order:approve",  List.of("mgr001", "mgr002", "dir001"));
        PERM_USERS.put("legal:contract:review",   List.of("dir001"));
        PERM_USERS.put("purchase:self:approve",   List.of("dir001"));
        // 稽核檢視權。政策決策（2026-09-29）：由權限中心指派，
        // 不用寫死的使用者清單也不收斂成「只有管理員」。
        // dir001 代表稽核職能。
        //
        // ⚠️ 這張表回答的是「誰該被指派這個權限所守的任務」
        // （解析型端點，BPMN 的 candidateUsers 會查它），
        // **不是**「誰能讀 /api/audit-logs」—— 後者是 SecurityConfig 的規則，
        // 而且刻意不接受 ROLE_ADMIN。
        //
        // 所以這裡<b>刻意不含 admin001</b>：政策決定「* 不等於 audit:log:read」，
        // 若解析型清單仍把通配持有者算進去，就會出現一種荒謬的狀況 ——
        // 管理員被指派了一個他打不開的任務。兩張表必須回答同一個政策問題，
        // 否則只是把拒絕從一處搬到另一處。
        // （實務上沒有任何 BPMN 引用這個權限碼，因此改動不影響既有流程。）
        PERM_USERS.put("audit:log:read",          List.of("dir001"));

        // 表單設計權。mgr001 是部門主管 —— 這個專案的產品目標是
        // 「業務人員自行設計、部署、維運流程與表單」，所以 dev fixture 裡
        // 必須存在一個<b>不是</b>管理員、卻能設計表單的帳號，
        // 否則那條規則的價值在開發時完全看不出來
        // （每個人都會用 admin001 測，然後以為權限碼沒生效）。
        PERM_USERS.put("bpm:form:design",         List.of("mgr001"));

        // ⚠️ 補件關卡受理權（#83）。**不是**一個可以隨手拿掉的 fixture 條目 ——
        //
        // leave-approval 的 applicantRevision 與 purchase-approval 的
        // revisionFromManager／revisionFromFinance 都用
        // ${applicantResolver.resolve(execution)} 指派，而該 resolver 在
        // 「發起人是 system:<id>（不是人）且沒有 onBehalfOf」時會查這個權限碼
        // （見 ApplicantResolver 的第三段）。
        //
        // MockPermController 刻意 fail-closed：查不到的權限碼回 404 而不是
        // 捏造一個人（見類別註解）。所以少了這一條，外部系統發起的案件
        // **連「退回」都會失敗**（500），而且錯誤訊息指向「權限碼不存在」
        // 而不是「這張單沒有申請人」—— 那正是本註解要防的那種誤導。
        //
        // 正式環境對接真實權限中心時，同一個權限碼必須在那裡指派給
        // 「承辦外部系統案件的人」。這是本專案對真實權限中心新增的一項要求。
        //
        // 刻意只給 dir001（總監）一個人：在測試裡「被派給受理人」必須是
        // 一個明確、可與審核人（mgr001）區別開的身分，否則斷言分辨不出
        // 「resolver 生效了」與「剛好指給同一個審核人」。
        PERM_USERS.put("bpm:external:revision",  List.of("dir001"));

        USER_PERMS.put("mgr001", List.of("hr:leave:approve", "finance:payment:approve", "purchase:order:approve",
                                          // 表單設計權，見上方 PERM_USERS 的說明
                                          "bpm:form:design"));
        USER_PERMS.put("mgr002", List.of("hr:leave:approve", "purchase:order:approve"));
        USER_PERMS.put("dir001", List.of("hr:leave:approve", "finance:payment:approve", "purchase:order:approve",
                                          "legal:contract:review", "purchase:self:approve",
                                          // 稽核檢視權，見上方 PERM_USERS 的說明
                                          "audit:log:read",
                                          // 補件關卡受理權，見上方 PERM_USERS 的說明（#83）
                                          "bpm:external:revision"));
        // admin001 刻意只有通配權限 *，不逐一列出 —— 這樣「* 不等於具名權限碼」
        // 這件事在 fixture 裡就成立。SecurityConfig 的稽核規則因此不接受
        // ROLE_ADMIN；要讀稽核必須由權限中心明確指派 audit:log:read。
        USER_PERMS.put("admin001", List.of("*"));
    }

    @GetMapping("/permissions/{permCode}/users")
    public List<String> getUsersByPermission(@PathVariable String permCode,
                                              @RequestParam(required = false) String deptId) {
        List<String> users = PERM_USERS.get(permCode);
        if (users == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    ("開發用權限 fixture 中沒有這個權限碼：%s"
                     + "（MockPermController 刻意不回預設的 [\"mgr001\"]，見類別註解）")
                            .formatted(permCode));
        }
        if (deptId == null) return users;
        // 簡單過濾：dept001 → mgr001/dir001, dept002 → mgr002
        return users.stream().filter(u -> {
            if ("dept001".equals(deptId)) return List.of("mgr001", "dir001", "user001", "user002", "user003").contains(u);
            if ("dept002".equals(deptId)) return List.of("mgr002", "user004", "user005").contains(u);
            return true;
        }).toList();
    }

    /**
     * 判定型：不認識的使用者回空集合，也就是「什麼權限都沒有」= 拒絕。
     * 不用 404，因為那會把一次乾淨的權限拒絕變成 500。
     */
    @GetMapping("/users/{userId}/permissions")
    public List<String> getUserPermissions(@PathVariable String userId) {
        return USER_PERMS.getOrDefault(userId, List.of());
    }

    /** 判定型：不認識的使用者回 {@code false}（拒絕）。理由同上。 */
    @GetMapping("/users/{userId}/has-permission")
    public Map<String, Object> hasPermission(@PathVariable String userId, @RequestParam String code) {
        List<String> perms = USER_PERMS.getOrDefault(userId, List.of());
        boolean has = perms.contains("*") || perms.contains(code);
        return Map.of("hasPermission", has);
    }
}
