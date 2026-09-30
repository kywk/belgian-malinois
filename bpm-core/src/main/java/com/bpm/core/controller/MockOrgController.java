package com.bpm.core.controller;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/**
 * 開發用的組織系統替身。
 *
 * <h2>⚠️ 這個 mock 必須 fail-closed（security-audit P2-7）</h2>
 *
 * <p>改動前每個查詢都有 fail-open 預設值：不認識的 userId 會被<b>捏造</b>成
 * {@code dept001} 的員工、主管一律回 {@code mgr001}、不認識的部門回
 * {@code user001~003}。
 *
 * <p>為什麼這比「查不到」危險得多：這些回答直接決定任務要派給誰。
 * 捏造的答案會讓流程<b>順利跑完</b>並把簽核任務派給一個無關的人 ——
 * 系統沒有任何異常訊號，而簽核權責已經錯了。相對地，查不到就拋錯，
 * 錯誤會停在流程啟動的那一刻，指向正確的根因。
 *
 * <p>更關鍵的是它讓測試失去意義：驗收測試會過，因為每個查詢都回得出東西；
 * 上線後對接真實組織系統，同一段程式碼拿到的是 404 或空集合，
 * 流程才靜默卡住 —— 而沒有任何測試能事先抓到。<b>綠燈反而掩蓋了問題。</b>
 *
 * <p>另外修掉一個自我矛盾：{@code getManager("dir001")} 原本回 {@code mgr001}
 * （因為 dir001 不在 MANAGER_MAP 裡而套用預設值），但
 * {@code getManagerChain("dir001")} 回 {@code []}。兩個端點對同一個事實
 * 給出不同答案，而且前者還構成 {@code mgr001 → dir001 → mgr001} 的環。
 * 現在 dir001／admin001 這種鏈頂人員一律回 {@code {}}（無主管），與 chain 一致。
 *
 * <h2>故障注入：id 以 {@code fault-} 開頭時這個 mock 會刻意故障（#88）</h2>
 *
 * <p>存在的理由：<b>「組織系統故障」與「查無此人」必須是兩條分開的路徑</b>，
 * 而「故障」在 happy-path 的 fixture 裡<b>造不出來</b> —— 每個查詢都回得出
 * 答案時，故障分支永遠沒有被測試走過。這正是本類別註解講了兩輪的
 * 「綠燈反而掩蓋了問題」的同一種形狀，只是這次掩蓋的是<b>可用性</b>而非正確性。
 *
 * <p>可用的 id（只有這三個形狀刻意行為異常）：
 * <pre>
 *   fault-503  → HTTP 503   （組織系統自己故障）
 *   fault-500  → HTTP 500   （組織系統自己故障，內部錯誤）
 *   fault-401  → HTTP 401   （我們沒有被允許查 —— 不是「查無此人」）
 * </pre>
 *
 * <p>⚠️ <b>刻意不提供「逾時」形狀</b>：那需要這個 mock 睡到超過
 * {@code bpm.external.read-timeout-ms}（預設 3 秒），等於讓 mock 的行為
 * 依賴呼叫端的逾時設定 —— 設定一改這個測試就會靜默失去意義。
 * 「連線逾時／無法連線」那條路徑由 {@code ExternalActorGuardTest} 以
 * {@code ResourceAccessException} 直接驗證（那是它唯一能精確產生的形狀）。
 *
 * <p>為什麼用「特殊的 id」而不是一個全域開關：開關是<b>有狀態</b>的，
 * 而所有整合測試共用同一個 Spring context 與同一個 Tomcat —— 任何一個
 * 測試在結束前失敗就會把開關留在「故障」狀態，讓後續的測試連帶紅掉，
 * 而錯誤訊息會指向完全不相關的地方。特殊 id 是<b>無狀態且自我限定</b>的：
 * 只有明文送出那個 id 的請求會受影響，而且錯誤訊息會直接說明原因。
 *
 * Test users:
 *   user001~user003  → dept001 員工，主管 mgr001
 *   user004~user005  → dept002 員工，主管 mgr002
 *   mgr001           → dept001 主管，主管 dir001
 *   mgr002           → dept002 主管，主管 dir001
 *   dir001           → 總監
 *   admin001         → 系統管理員
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
@RequestMapping("/mock/org/api")
public class MockOrgController {

    private static final Map<String, Map<String, Object>> USERS = new LinkedHashMap<>();
    private static final Map<String, String> MANAGER_MAP = new HashMap<>();
    private static final Map<String, String> DEPT_MAP = new HashMap<>();

    static {
        USERS.put("user001", Map.of("userId", "user001", "name", "王小明", "deptId", "dept001", "email", "user001@example.com"));
        USERS.put("user002", Map.of("userId", "user002", "name", "李小華", "deptId", "dept001", "email", "user002@example.com"));
        USERS.put("user003", Map.of("userId", "user003", "name", "張小芳", "deptId", "dept001", "email", "user003@example.com"));
        USERS.put("user004", Map.of("userId", "user004", "name", "陳大文", "deptId", "dept002", "email", "user004@example.com"));
        USERS.put("user005", Map.of("userId", "user005", "name", "林小玲", "deptId", "dept002", "email", "user005@example.com"));
        USERS.put("mgr001", Map.of("userId", "mgr001", "name", "李主管",  "deptId", "dept001", "email", "mgr001@example.com"));
        USERS.put("mgr002", Map.of("userId", "mgr002", "name", "陳主管",  "deptId", "dept002", "email", "mgr002@example.com"));
        USERS.put("dir001", Map.of("userId", "dir001", "name", "王總監",  "deptId", "dept001", "email", "dir001@example.com"));
        USERS.put("admin001", Map.of("userId", "admin001", "name", "系統管理員", "deptId", "admin", "email", "admin@example.com"));

        MANAGER_MAP.put("user001", "mgr001"); MANAGER_MAP.put("user002", "mgr001"); MANAGER_MAP.put("user003", "mgr001");
        MANAGER_MAP.put("user004", "mgr002"); MANAGER_MAP.put("user005", "mgr002");
        MANAGER_MAP.put("mgr001", "dir001");  MANAGER_MAP.put("mgr002", "dir001");

        USERS.forEach((uid, u) -> DEPT_MAP.put(uid, (String) u.get("deptId")));
    }

    @GetMapping("/users")
    public Collection<Map<String, Object>> listUsers() { return USERS.values(); }

    @GetMapping("/users/{userId}")
    public Map<String, Object> getUser(@PathVariable String userId) {
        var user = USERS.get(userId);
        if (user == null) throw unknown("使用者", userId);
        return user;
    }

    /**
     * 回傳 {@code {}}（而非 404）代表「此人存在但位於鏈頂，沒有主管」。
     * 這個區分是必要的：dir001 沒有主管是<b>事實</b>，不是錯誤，
     * 而且必須與 {@link #getManagerChain} 回 {@code []} 一致。
     */
    @GetMapping("/users/{userId}/manager")
    public Map<String, String> getManager(@PathVariable String userId) {
        if (!USERS.containsKey(userId)) throw faultOrUnknown("使用者", userId);
        String mgr = MANAGER_MAP.get(userId);
        return mgr == null ? Map.of() : Map.of("managerId", mgr);
    }

    @GetMapping("/users/{userId}/manager-chain")
    public List<String> getManagerChain(@PathVariable String userId, @RequestParam(defaultValue = "3") int levels) {
        if (!USERS.containsKey(userId)) throw unknown("使用者", userId);
        List<String> chain = new ArrayList<>();
        String current = userId;
        for (int i = 0; i < levels; i++) {
            String mgr = MANAGER_MAP.get(current);
            if (mgr == null) break;
            chain.add(mgr);
            current = mgr;
        }
        return chain;
    }

    @GetMapping("/users/{userId}/department")
    public Map<String, String> getDepartment(@PathVariable String userId) {
        String dept = DEPT_MAP.get(userId);
        if (dept == null) throw unknown("使用者", userId);
        return Map.of("deptId", dept);
    }

    /**
     * 固定回 {@code {}} —— 這個 fixture 沒有設定任何代理人。
     * 這不是 fail-open：「沒有代理人」是誠實的答案，不會導致任務被錯派。
     */
    @GetMapping("/users/{userId}/substitute")
    public Map<String, String> getSubstitute(@PathVariable String userId) {
        if (!USERS.containsKey(userId)) throw unknown("使用者", userId);
        return Map.of();
    }

    @GetMapping("/departments/{deptId}/members")
    public List<String> getDeptMembers(@PathVariable String deptId) {
        List<String> members = new ArrayList<>();
        DEPT_MAP.forEach((uid, dept) -> { if (dept.equals(deptId)) members.add(uid); });
        if (members.isEmpty()) throw unknown("部門", deptId);
        return members;
    }

    /**
     * 錯誤訊息要點名這是「開發 fixture 裡沒有」，而不是含糊的 404 ——
     * 排查的人第一眼就該知道問題出在 mock 資料還是真實組織系統。
     *
     * <p>⚠️ <b>404 是「明確的拒絕」，這是 {@code ExternalActorGuard} 判斷
     * 400／503 的依據</b>，所以這個狀態碼不可改成 500 或模糊的 200。
     * 見 {@code ExternalActorGuard} 類別註解「分界線是 HTTP 404」。
     */
    private static ResponseStatusException unknown(String kind, String id) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND,
                "開發用組織 fixture 中沒有這個%s：%s（MockOrgController 刻意不捏造預設值，見類別註解）"
                        .formatted(kind, id));
    }

    /**
     * 故障注入 id（見類別註解「故障注入」）。命中就丟對應的狀態碼。
     *
     * <p>刻意放在 {@link #unknown} <b>之前</b>判斷：故障是「這個 mock 壞了」，
     * 而 unknown 是「這個 id 不存在」—— 兩者的下游處置完全不同（503 vs 400），
     * 而 503 那個形狀必須排在前面才不會被 unknown 的 404 蓋掉。
     */
    private static ResponseStatusException faultOrUnknown(String kind, String id) {
        if (id.startsWith(FAULT_PREFIX)) {
            HttpStatus status = switch (id.substring(FAULT_PREFIX.length())) {
                case "503" -> HttpStatus.SERVICE_UNAVAILABLE;
                case "500" -> HttpStatus.INTERNAL_SERVER_ERROR;
                case "401" -> HttpStatus.UNAUTHORIZED;
                default -> null;
            };
            if (status != null) {
                return new ResponseStatusException(status,
                        "開發 fixture 的故障注入 id：" + id + "（MockOrgController 刻意回 "
                                + status.value() + " 來模擬組織系統故障，見類別註解）");
            }
        }
        return unknown(kind, id);
    }

    /** 故障注入 id 的前綴。見 {@link #faultOrUnknown}。 */
    private static final String FAULT_PREFIX = "fault-";
}
