package com.bpm.core.external;

import org.springframework.transaction.annotation.Transactional;
import com.bpm.core.audit.AuditEventPublisher;
import com.bpm.core.dto.AuditEvent;
import com.bpm.core.model.ExternalSystem;
import com.bpm.core.model.ProcessVariableSpec;
import com.bpm.core.repository.ProcessVariableSpecRepository;
import com.bpm.core.service.FormVersionLocker;
import org.flowable.common.engine.api.FlowableObjectNotFoundException;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.identitylink.api.IdentityLink;
import org.flowable.identitylink.api.IdentityLinkType;
import org.flowable.task.api.Task;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

@RestController
@RequestMapping("/api/external")
public class ExternalApiController {

    private final RuntimeService runtimeService;
    private final TaskService taskService;
    private final HistoryService historyService;
    private final RepositoryService repositoryService;
    private final ProcessVariableSpecRepository specRepo;
    private final AuditEventPublisher auditPublisher;
    private final FormVersionLocker formVersionLocker;
    private final ExternalSystemPolicy policy;
    /**
     * 外部系統指名的身分必須是人（#88）。{@code onBehalfOf} 與
     * {@code firstTaskAssignee} 共用同一條規則 —— 見該類別註解
     * 「為什麼把 onBehalfOf 的既有檢查也收進來」。
     */
    private final ExternalActorGuard actorGuard;
    /**
     * 第一關受理人的代理人代換（#5）。BPMN 的 {@code managerReview} 由
     * {@link com.bpm.core.service.InitialAssigneeResolver#resolve} 決定，
     * 而本類別在流程啟動後會<b>再設定一次</b> {@code firstTaskAssignee} ——
     * 那一行必須走同一個代換，否則會把 BPMN 已代換的代理人蓋回休假者本人。
     */
    private final com.bpm.core.service.InitialAssigneeResolver assigneeResolver;

    /**
     * 流程實例的擁有者。啟動時由 server 寫入，外部系統無法透過 request body 影響。
     *
     * <p>改動前擁有權是比對 {@code initiator}，但 {@code initiator} 可由呼叫端在
     * body 中任意指定（見 startProcess），因此不是可信的擁有權來源。
     */
    private static final String OWNER_VAR = "_externalSystemId";

    public ExternalApiController(RuntimeService runtimeService, TaskService taskService,
                                  HistoryService historyService, RepositoryService repositoryService,
                                  ProcessVariableSpecRepository specRepo,
                                  AuditEventPublisher auditPublisher, FormVersionLocker formVersionLocker,
                                  ExternalSystemPolicy policy,
                                  ExternalActorGuard actorGuard,
                                  com.bpm.core.service.InitialAssigneeResolver assigneeResolver) {
        this.runtimeService = runtimeService;
        this.taskService = taskService;
        this.historyService = historyService;
        // #80：注入只為了啟動路徑的存在性預先檢查（見 startProcess 的註解）。
        // 在此之前，這個類別從未查過 RepositoryService —— 那是缺陷的成因。
        this.repositoryService = repositoryService;
        this.specRepo = specRepo;
        this.auditPublisher = auditPublisher;
        this.formVersionLocker = formVersionLocker;
        this.policy = policy;
        // #88：原本這裡注入 OrgService 供 onBehalfOf 的 inline try/catch 使用。
        // 規則移到 ExternalActorGuard 後本類別不再直接碰組織系統。
        this.actorGuard = actorGuard;
        // #5：見欄位註解 —— 啟動後補設定第一關受理人時要與 BPMN 走同一份代換。
        this.assigneeResolver = assigneeResolver;
    }

    // ── 1. Start Process ──

    @PostMapping("/process-instances")
    @Transactional("primaryTransactionManager")
    public Map<String, Object> startProcess(@RequestBody Map<String, Object> body,
                                             @RequestAttribute("externalSystemId") String systemId,
                                             @RequestAttribute("externalSystem") ExternalSystem sys) {
        String processDefKey = (String) body.get("processDefinitionKey");
        String businessKey = (String) body.get("businessKey");
        // ⚠️ initiator 一律由 server 決定（R-20）。
        //
        // 改動前 body 可指定任意 initiator，而下游廣泛信任它：主管路由、
        // 「我的申請」、通知信的申請人。任何持有 API key 的系統都能偽造一張
        // 「看似由某位員工提出」的單，並送到那位員工的主管。
        //
        // 帶了 initiator 就明確拒絕，而不是靜默忽略：靜默忽略會讓呼叫端以為
        // 案件是以那位員工的名義發起的。
        if (body.containsKey("initiator")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "initiator 由伺服器決定（" + ExternalActorIdentity.of(systemId) + "），不可由呼叫端指定。"
                            + "代員工發起請改用 onBehalfOf（需管理員為此系統開啟授權）。");
        }
        String initiator = ExternalActorIdentity.of(systemId);
        String onBehalfOf = (String) body.get("onBehalfOf");
        if (onBehalfOf != null && onBehalfOf.isBlank()) onBehalfOf = null;
        String firstAssignee = (String) body.get("firstTaskAssignee");
        // ⚠️ #93：這裡<b>不能</b>寫成 (String) body.get("firstTaskCandidateGroups")。
        //
        // 改動前就是那樣，而 docs/bpm-platform-spec.md §9.2 示範的形狀是
        // JSON 陣列 ["hr_dept"] —— 送陣列就在 cast 那一行拋 ClassCastException
        // → 500，而 #73 刻意只回傳「刻意丟出的」訊息，所以呼叫端連
        // 「你送錯形狀了」都拿不到。
        //
        // 危害不是「壞掉」而是「永遠不會成功還一直重試」：500 的語意是
        // 「稍後重試」，而 payload 不變就永遠不會成功，批次會無限重試。
        //
        // ⚠️ 而那個 cast 還排在<b>所有檢查之前</b>（連 allowedProcessKeys
        // 的 403 都還沒查），所以未授權的系統送陣列會拿到 500 而不是 403。
        // 現在只把值原封不動取出，形狀的判定交給 parseCandidateGroups，
        // 呼叫位置則在兩個 403 之後（見下方 requireAllowedCandidateGroups 處）。
        Object firstGroups = body.get("firstTaskCandidateGroups");
        String callbackUrl = (String) body.get("callbackUrl");

        @SuppressWarnings("unchecked")
        Map<String, Object> variables = body.get("variables") instanceof Map
                ? new HashMap<>((Map<String, Object>) body.get("variables")) : new HashMap<>();

        // 授權：processDefinitionKey 是否在該系統的 allowedProcessKeys 內。
        // ⚠️ 改動前 allowedProcessKeys 欄位完全沒有任何程式碼在檢查 ——
        // 外部系統即使只被授權 leave-approval，也能啟動任何流程。
        if (processDefKey == null || processDefKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少 processDefinitionKey");
        }
        if (!policy.isProcessKeyAllowed(sys, processDefKey)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "此系統未被授權啟動流程: " + processDefKey);
        }

        // ── #88 政策 B：候選群組必須在該系統的白名單內 ────────────────
        //
        // 為什麼排在兩個 403 之後、requireKnownPerson 之前：
        //  * 它<b>是授權檢查</b>，與 allowedProcessKeys 同類，必須與它們
        //    一起排在身分檢查之前。否則一個未授權的群組會先撞上
        //    「你的員工編號有問題」的 400 —— 呼叫端會去改一個
        //    根本不是問題來源的欄位，而真正的問題（它沒有這個群組的權限）
        //    要等到它換完 id 再送一次才會浮現。
        //  * 必須在 startProcessInstanceByKey 之前：擋在啟動之後就會留下
        //    一個已經存在、卻沒有人能簽的案件。
        //
        // 這一段原本是一整段「刻意不驗證」的註解（#88 的未完成項）。
        // 為什麼那時不能驗、為什麼現在驗的是「授權」而不是「存在」，
        // 見 ExternalSystemPolicy.isCandidateGroupAllowed 的 javadoc。
        //
        // ⚠️ 用回傳值而不是自己再 split 一次 —— 驗證過的清單必須就是
        // 實際寫進 identity link 的那一份，否則「規則只有一份」不成立。
        //
        // #93：形狀解析（陣列／逗號分隔字串）也排在這裡，理由同上 ——
        // 它必須排在 allowedProcessKeys 的 403 之後。否則一個只被授權
        // leave-approval 的系統就能用「403 變 400」探測伺服器上部署了哪些
        // 流程定義（#80 建立這個順序的理由）。
        //
        // ⚠️ 形狀錯誤是 400 而白名單是 403，兩者在<b>同一個欄位</b>上：
        // 一個未授權的群組 + 一個非字串元素會拿到 400（形狀先判，因為
        // 沒有形狀就沒有群組名可以拿去比對白名單）。這不構成枚舉風險 ——
        // 形狀錯誤的回應<b>不隨白名單內容改變</b>，所以無法用 400/403 的
        // 差異反推哪些群組被授權。與 initiator 冒用的 400（更早那一格）
        // 不同形狀的錯誤本來就無法互相取代。
        List<String> firstCandidateGroups = actorGuard.requireAllowedCandidateGroups(
                sys, parseCandidateGroups(firstGroups));

        // 代員工發起（2026-09-29 決策：依系統授權，預設不允許）。
        if (onBehalfOf != null) {
            if (!Boolean.TRUE.equals(sys.getAllowOnBehalfOf())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "此系統未被授權代員工發起（onBehalfOf）");
            }
            // 必須是組織系統認得的人。查不到主管就無法路由 —— 而且一個不存在的
            // 員工編號出現在「我的申請」或通知信上，只會製造混亂。
            // ⚠️ #88 起這條規則的實作在 ExternalActorGuard，與下面
            // firstTaskAssignee 共用同一份（規則只能有一份）。
            actorGuard.requireKnownPerson("onBehalfOf", onBehalfOf);
        }

        // ── #88：firstTaskAssignee 必須是組織系統認識的人 ──────────────
        //
        // ⚠️ 改動前這個欄位<b>完全沒有任何驗證</b>：後端只檢查
        // 「firstTaskAssignee／firstTaskCandidateGroups 至少有一個」，
        // 不檢查那個值是不是人。於是外部系統可以送
        // {"firstTaskAssignee": "system:evil"}，讓第一個人工任務的
        // assignee 變成沒有人能持有的身分 —— 案件從第一關就卡死，
        // 而且沒有任何錯誤訊息。與 #83 是同一個缺陷的另一個入口，
        // 而第一關更早、使用者更可能以為是系統故障。
        //
        // 為什麼排在兩個 403 之後、validateVariables 之前：
        //  * 授權先決（與 #80 的 404 預檢同一個理由）。這條檢查不會洩漏
        //    伺服器狀態（它問的是組織系統，不是流程定義部署），
        //    但順序一致本身就是可讀性。
        //  * 前面每一個 400／403 都在講「請求不完整或不被允許」，
        //    呼叫端該改的是 payload —— 而「你給的員工編號查無此人」
        //    正是同一類錯誤，必須排在同一區。
        //  * 必須在 startProcessInstanceByKey 之前：擋在啟動之後就會留下
        //    一個已經存在、卻沒有人能簽的案件（正是要修的那個缺陷）。
        //
        // 只在有值時檢查 —— 沒指名是合法的（改用候選群組），
        // 由下面的「至少有一個」規則處理。
        actorGuard.requireKnownPerson("firstTaskAssignee", firstAssignee);

        // ── ⚠️ firstTaskCandidateGroups 刻意<b>不</b>驗證（見 #88 報告）──
        //
        // 對照：受理人是「一個 id」，所以「組織系統認不認識他」是個有答案的
        // 問題。候選群組是「一個群組名稱」，而本 repo 的候選群組名稱有
        // <b>三個互質的來源</b>（見 CandidateGroupMembership 類別註解）：
        //   ① 部門代碼（${orgService.getDeptId(initiator)} 或手填）
        //   ② 權限碼（例：hr:leave:approve）
        //   ③ JWT roles claim 帶進來的 authority
        // ① 可以用 getDeptMembers 驗，但②③<b>沒有任何「這群組存在嗎」的
        // API</b> —— 權限中心只能由人反查權限清單，列不出權限碼全集；
        // JWT authority 更不在我們的管轄範圍。
        //
        // 也就是說：要驗就必須<b>假設每個群組都是部門</b>，那會擋掉
        // ②③ 這兩種合法用法（本專案自己產生的 BPMN 就用 ②）。
        // 一個會擋掉合法用法的驗證比沒有驗證更糟。
        //
        // 而且它的危害型態不同、也還沒被裁決：
        //  * 卡死：群組不存在 → 群組成員看不到任務 → 靜默卡死。
        //    ⚠️ UnreachableTaskListener 對這種情況<b>不告警</b>
        //    （UnreachableTaskAlertTest.taskWithCandidateGroupIsNotAlerted）。
        //  * 越權：把案件丟進任意特權群組的待辦池
        //    （docs/plan/2026-09-28-remediation-backlog.md:260），
        //    那是<b>授權範圍</b>問題，必須由 PM 決定「哪些群組可被指定」。
        //
        // 所以這一格留白是<b>有意識的未完成</b>，不是漏掉。合理的下一步是
        // 在外部系統設定檔加一個 allowedCandidateGroups 白名單
        // （授權維度、零外部系統依賴），或由權限中心提供群組存在性 API。
        // 在那之前，維持現狀是唯一不會擋掉合法用法的選擇。
        //
        // ✅ **2026-09-30 已實作（#88 政策 B）**：授權面（越權）改由
        // actorGuard.requireAllowedCandidateGroups 的白名單根治，
        // 見上方呼叫處。存在性面（卡死）仍未根治 —— 沒有那個 API，
        // 理由不變（會擋掉權限碼與 JWT authority 兩種合法用法）。
        //
        // ⚠️ 未根治的那一半已回報 PM：`firstTaskCandidateGroups: " , "`
        // 這種只送分隔符的 payload，改動前會產生空字串的候選群組而
        // 讓 UnreachableTaskListener 不告警；現在空項目被丟棄，
        // 「至少有一個」規則因此會看到「沒有群組」而回 400（見下）。

        // 沒有受理人、沒有候選群組、也不是代員工發起 → 無從推導簽核人。
        // initiator 是 system:<id>，不是人，組織系統查不到它的主管。
        //
        // ⚠️ 比對的是**解析後**的清單而不是原始字串。改動前比對 raw：
        // `firstTaskCandidateGroups: " , "` 非 null 而通過，但實際上
        // 沒有任何群組會被掛到任務上 → 第一關沒有 assignee 也沒有候選人
        // → 靜默卡死，且 listener 因為看到空字串 identity link 而不告警。
        // 用解析後的清單是讓這條**既有規則**看到事實，不是新增一條規則。
        if (firstAssignee == null && firstCandidateGroups.isEmpty() && onBehalfOf == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "外部系統發起流程必須指定 firstTaskAssignee、firstTaskCandidateGroups，"
                            + "或（已授權時）onBehalfOf");
        }

        // ── R-23 殘留：保留命名空間，與 completeTask 同一份 helper ──────
        //
        // ⚠️ 改動前 startProcess 完全不擋 `_` 前綴：body 的 variables 是自由
        // map，呼叫端可以夾帶 _externalSystemId（R-20 之後會被下面 server
        // 的 put 覆寫，但「靜默忽略」讓呼叫端以為設定生效了）、
        // _formVersions（表單版本鎖的內容，見 FormVersionLocker）、
        // _callbackUrl（server 只會依 body.callbackUrl 覆寫，variables 裡
        // 夾帶的值會留著）。這是 R-19 在 completeTask 修掉的同一個缺陷，
        // 差別只在入口 —— R-23 的修法要求所有 variable 寫入路徑一致拒絕。
        //
        // 順序沿用 completeTask：rejectReservedVariableNames → validateVariables
        // （見該方法的 javadoc）。兩者都是「payload 用了不該用的名字／形狀」
        // → 400；且都排在啟動之前，被拒的請求不啟動流程、不寫變數、不寫稽核。
        //
        // ⚠️ 檢查的是「呼叫端傳進來的」variables：server 稍後才寫入的
        // _externalSystemId／_formVersions／_callbackUrl 不受影響
        // （它們在下面才 put，不經過這一行）。
        //
        // 為什麼可以排在下面的 404 預檢之前：保留變數的 400 不隨部署清單
        // 改變，不像 #80 的 404 需要排在 403 之後才不會變成枚舉工具。
        rejectReservedVariableNames(variables);
        // Validate variables against ProcessVariableSpec
        validateVariables(processDefKey, variables);

        // Prepare variables
        variables.put("initiator", initiator);
        // 擁有者由 server 決定，覆寫呼叫端可能夾帶的同名變數（body 的 variables
        // 是自由 map，必須在這裡最後寫入才不會被蓋掉）。
        variables.put(OWNER_VAR, systemId);
        // 與 OWNER_VAR 相同：最後寫入，呼叫端的 variables 蓋不掉它。
        // 沒有代發時明確移除，避免呼叫端藉 variables 夾帶。
        if (onBehalfOf != null) {
            variables.put(com.bpm.core.service.InitialAssigneeResolver.ON_BEHALF_OF_VAR, onBehalfOf);
        } else {
            variables.remove(com.bpm.core.service.InitialAssigneeResolver.ON_BEHALF_OF_VAR);
        }
        if (firstAssignee != null) {
            variables.put("effectiveInitiator", firstAssignee);
        }
        // ⚠️ 必須在 startProcessInstanceByKey 之前放進變數（P2-7）。
        //
        // BPMN 的 managerReview 在流程<b>啟動當下</b>就求值 assignee 運算式，
        // 也就是在下面那幾行 taskService.setAssignee 之前。原本的運算式是
        // ${orgService.getDirectManager(initiator)}，而外部系統發起時
        // initiator 是 system:<id> —— 不是人，組織系統查不到它的主管。
        //
        // 先前沒被發現是因為組織 mock 對未知 userId 一律回 mgr001，
        // 而測試剛好都用 firstTaskAssignee=mgr001，捏造值與預期值恰好相同。
        //
        // ⚠️ #93：寫進變數的仍然是<b>逗號分隔字串</b>，而且是由<b>解析後的
        // 清單</b> join 出來的，不是把 body 的原始值原樣塞回去。理由：
        //  1. 這個變數的形狀是 InitialAssigneeResolver.FIRST_GROUPS_VAR 的
        //     既定契約（str() → toString() → 非空白 → 留空等人認領）。
        //     改成塞 List 會讓變數在 Flowable 的序列化與該類別的行為都變形，
        //     那是本工項不需要的改動範圍。
        //  2. 用解析後的清單才能讓「變數裡的」與「identity link 裡的」一致 ——
        //     例如 "dept001,  " 不會讓變數看起來像有兩個群組。
        // 空清單寫 null（= 未指定），與「欄位不存在」一致。
        String firstGroupsVar = firstCandidateGroups.isEmpty()
                ? null : String.join(",", firstCandidateGroups);
        com.bpm.core.service.InitialAssigneeResolver.putIfPresent(
                variables, firstAssignee, firstGroupsVar);
        if (callbackUrl != null) variables.put("_callbackUrl", callbackUrl);

        // ── 存在性預先檢查（#80：#69 的同一個洞在此仍未修）────────────────
        //
        // 改動前這裡直接呼叫 startProcessInstanceByKey，而它對查不到的 key 會丟
        // FlowableObjectNotFoundException → 裸 500。危害與 ProcessController 那條
        // 完全相同（#69 的 commit 訊息）：呼叫端看到 500 的直覺是「再送一次」，
        // 而外部系統的發起通常是計時批次，**重送會造成重複案件**。
        //
        // ⚠️ 這是本 repo 第三次為同一條規則補上檢查（前兩次是 ProcessController
        // 與 R-20 的 key 缺席 400），而「規則只能有一份」正是 #69 記錄的教訓。
        // 本方法的 @Transactional 讓它無法只靠 catch 收尾：見下方說明。
        //
        // 為什麼放在 403 之後：allowedProcessKeys 先擋。若順序顛倒，
        // 一個只被授權 leave-approval 的系統就能用「403 變 404」的回答
        // 探測出伺服器上到底部署了哪些流程定義 —— 那是把授權檢查變成枚舉工具。
        //
        // 為什麼放在 validateVariables 之後、startProcess 之前：
        // 前面每一個 400／403 都在講「請求本身不完整或不被允許」，
        // 呼叫端該改的是 payload；只有走到這裡才知道它<b>指向的資源不存在</b>。
        // validateVariables 對不存在的 key 查不到規格會直接放行，所以放它之後
        // 不會有人被「缺少必填變數」擋下，卻其實是 key 打錯了。
        if (repositoryService.createProcessDefinitionQuery()
                .processDefinitionKey(processDefKey).count() == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "流程定義不存在: " + processDefKey);
        }

        // Start process
        ProcessInstance pi;
        try {
            pi = runtimeService.startProcessInstanceByKey(processDefKey, businessKey, variables);
        } catch (FlowableObjectNotFoundException e) {
            // race window：預檢之後、真正啟動之前，管理員把定義刪了。
            // 預先檢查消除不了這個窗口，沒有這一層它就會變回 500。
            //
            // ⚠️ 注意 Flowable 命令在外層交易中拋例外會把交易標成 rollback-only，
            // 而本方法是 @Transactional —— 所以這裡只能「翻譯」例外，
            // 不能試圖在同一個交易裡繼續做别的事（見 ProcessAccessGuard
            // #initiatorOf 的同型註解）。
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "流程定義不存在: " + processDefKey, e);
        }

        formVersionLocker.lockVersions(pi.getProcessInstanceId(), pi.getProcessDefinitionId());

        // Set first task assignee/candidates
        //
        // ⚠️ 同 P1-2：singleResult() 在併發任務時拋例外，而此處在流程已啟動
        // 之後執行 → 外部系統收到 500 並重送 → 重複案件。
        // 取最早建立的那一個，行為與單任務時完全相同。
        var firstTasks = taskService.createTaskQuery()
                .processInstanceId(pi.getProcessInstanceId())
                .orderByTaskCreateTime().asc().list();
        Task firstTask = firstTasks.isEmpty() ? null : firstTasks.get(0);
        if (firstTask != null) {
            if (firstAssignee != null) {
                // ── #5：必須與 BPMN 走同一份代理人代換 ────────────────
                //
                // BPMN 的 managerReview 在啟動當下已用 assigneeResolver.resolve
                // 把受理人代換成代理人（若 firstTaskAssignee 設了代理人）。
                // 這一行原本傳原始值，等於<b>把代換結果蓋回去</b> ——
                // 外部系統看到的 200 與 BPMN 實際指派的人不一致，
                // 而且沒有任何錯誤訊息。走 effectiveAssignee 之後，
                // 「誰來簽」的規則只有一份（與 resolve 共用）。
                //
                // ⚠️ 存在性驗證不受影響：actorGuard.requireKnownPerson 已在
                // 流程啟動<b>之前</b>驗過原始的 firstTaskAssignee（見上方）。
                // 這裡只做代換，不再查一次網路。
                taskService.setAssignee(firstTask.getId(),
                        assigneeResolver.effectiveAssignee(firstAssignee));
            }
            // ⚠️ 用守衛回傳的那一份清單（已驗過授權、已 trim、已丟棄空白），
            // 不可在這裡再 split 一次 —— 驗證過的與實際寫入的必須是同一份。
            for (String g : firstCandidateGroups) {
                taskService.addCandidateGroup(firstTask.getId(), g);
            }
        }

        auditPublisher.publish(new AuditEvent("EXTERNAL_API_CALL", ExternalActorIdentity.of(systemId),
                "external_api", processDefKey, pi.getProcessInstanceId(), null, businessKey,
                Map.of("action", "start_process", "processDefinitionKey", processDefKey,
                        "onBehalfOf", onBehalfOf != null ? onBehalfOf : ""),
                java.time.Instant.now()));

        Map<String, Object> result = new HashMap<>();
        result.put("processInstanceId", pi.getProcessInstanceId());
        result.put("businessKey", businessKey != null ? businessKey : "");
        result.put("status", "running");
        if (firstTask != null) {
            result.put("currentTask", Map.of(
                    "taskId", firstTask.getId(),
                    "taskName", firstTask.getName() != null ? firstTask.getName() : "",
                    "assignee", firstAssignee != null ? firstAssignee : ""));
        }
        return result;
    }

    // ── 2. Query Status ──

    @GetMapping("/process-instances/{processInstanceId}/status")
    public Map<String, Object> getStatus(@PathVariable String processInstanceId,
                                          @RequestAttribute("externalSystemId") String systemId) {
        return buildStatusResponse(processInstanceId, systemId);
    }

    @GetMapping("/process-instances")
    public List<Map<String, Object>> queryByBusinessKey(@RequestParam String businessKey,
                                                         @RequestAttribute("externalSystemId") String systemId) {
        // Search in history (covers both running and completed)
        return historyService.createHistoricProcessInstanceQuery()
                .processInstanceBusinessKey(businessKey)
                .variableValueEquals("initiator", ExternalActorIdentity.of(systemId))
                .orderByProcessInstanceStartTime().desc().list().stream()
                .map(hp -> buildStatusFromHistory(hp))
                .toList();
    }

    // ── 3. Complete Task ──

    /**
     * 完成一個任務（R-19）。
     *
     * <h2>改動前：擁有權檢查只回答了一半的問題</h2>
     *
     * <p>原本這裡只做 {@code verifyRunningOwnership} ——「這個流程實例是不是
     * 你的」。但「實例是你的」不等於「這個任務該由你做」：外部系統 X 啟動
     * {@code leave-approval} 之後，該實例的擁有者就是 X，於是 X 可以直接完成
     * 上面由真人主管持有的 managerReview —— <b>自己送出的案件由自己核准</b>，
     * 人工審批等於不存在。
     *
     * <p>同一處還有變數注入：{@code body.variables} 直接進
     * {@code taskService.complete}，可以寫入 {@code _} 前綴的保留變數
     * （含擁有權標記 {@code _externalSystemId} 與表單版本鎖
     * {@code _formVersions}），也可以覆寫 {@code approved}／{@code rejected}
     * —— 那是本流程閘道判斷核准／退回／駁回的唯一依據。
     *
     * <h2>三層檢查與順序</h2>
     *
     * <ol>
     *   <li><b>擁有權</b>（既有，403）：實例屬於本系統，或沿 Call Activity
     *       往上追溯到的父實例屬於本系統。</li>
     *   <li><b>allowedProcessKeys</b>（403）：任務所屬流程 key 必須在該系統的
     *       授權清單內。與 startProcess 同一條規則、同一個
     *       {@link ExternalSystemPolicy#isProcessKeyAllowed} —— 否則一個只被
     *       授權 leave-approval 的系統仍可完成其他流程的任務。</li>
     *   <li><b>任務層級</b>（403）：只有 assignee 或 candidateUsers
     *       <b>明確</b>包含 {@code system:<systemId>} 時才能完成。這是 R-19 的
     *       核心：擋掉「系統完成自己案件上的人工簽核」。候選<b>群組</b>刻意
     *       不納入，理由見 {@link #isTaskHeldBySystem}。</li>
     *   <li><b>變數</b>（400）：{@code _} 前綴是伺服器保留命名空間 →
     *       指名拒絕；其餘走與 startProcess 完全相同的
     *       {@link #validateVariables}（規則只能有一份）。</li>
     * </ol>
     *
     * <p>為什麼授權檢查（403）排在變數檢查（400）之前：與 startProcess 的
     * 順序一致（allowedProcessKeys → validateVariables），也與
     * {@code ExternalRequiredVariableTest.unauthorizedKeyWinsOverBlankRequired}
     * 記載的原則一致 —— 呼叫端該先知道「這件事你根本不能做」，再知道
     * 「你的 payload 哪裡不對」。這裡不像 startProcess 存在部署清單的枚舉
     * 風險（任務在呼叫端自己的實例上，{@code /status} 本來就看得到），
     * 但順序一致本身就是可讀性。
     *
     * <p>所有拒絕都發生在 {@code taskService.complete} 之前：任務仍在、
     * 變數未寫入、不寫稽核。測試對每一條拒絕都驗這件事。
     */
    @PutMapping("/tasks/{taskId}")
    @Transactional("primaryTransactionManager")
    public Map<String, Object> completeTask(@PathVariable String taskId,
                                             @RequestBody Map<String, Object> body,
                                             @RequestAttribute("externalSystemId") String systemId,
                                             @RequestAttribute("externalSystem") ExternalSystem sys) {
        Task task = taskService.createTaskQuery().taskId(taskId).singleResult();
        if (task == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found");

        // ⚠️ 改動前這個端點完全沒有擁有權檢查 —— 任何通過 API Key 驗證的外部系統
        // 都能以 taskId 完成「任何」任務，包含其他系統的案件與人工簽核任務。
        verifyRunningOwnership(task.getProcessInstanceId(), systemId);

        // ── R-19 (iii)：任務所屬流程必須在該系統的 allowedProcessKeys 內 ──
        //
        // 任務是執行中的（上面剛查到），所以實例一定存在於 ACT_RU_EXECUTION；
        // processDefinitionKey 直接取自實例，不必再用 RepositoryService 把
        // processDefinitionId 換成 key（少一次查詢，也沒有「定義被刪」的 null
        // 分支 —— 有執行中實例的定義刪不掉）。這與 TaskController.toMap 的
        // 取法相同。
        //
        // ⚠️ 取的是「任務自己的」流程 key，不是父流程的。Call Activity 子流程
        // 的任務因此要求子流程 key 也在授權清單內 —— 這是刻意的：子流程可能
        // 有自己的人工關卡與變數規格，用父流程的授權放行等於繞過它們。
        ProcessInstance instance = runtimeService.createProcessInstanceQuery()
                .processInstanceId(task.getProcessInstanceId()).singleResult();
        String processDefKey = instance.getProcessDefinitionKey();
        if (!policy.isProcessKeyAllowed(sys, processDefKey)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "此系統未被授權完成流程: " + processDefKey);
        }

        // ── R-19 (i)：任務必須明確指派給本系統 ──
        if (!isTaskHeldBySystem(task, systemId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "此任務未指派給本系統（" + ExternalActorIdentity.of(systemId) + "）。"
                            + "外部系統只能完成 assignee 或 candidateUsers 明確包含該身分的任務；"
                            + "候選群組不在此列。");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> vars = body.get("variables") instanceof Map
                ? new HashMap<>((Map<String, Object>) body.get("variables")) : new HashMap<>();

        // ── R-19 (ii)：保留命名空間與變數規格 ──
        rejectReservedVariableNames(vars);
        validateVariables(processDefKey, vars);

        taskService.complete(taskId, vars);

        auditPublisher.publish(new AuditEvent("EXTERNAL_API_CALL", ExternalActorIdentity.of(systemId),
                "external_api", null, task.getProcessInstanceId(), taskId, null,
                Map.of("action", "complete_task", "variables", vars),
                java.time.Instant.now()));

        return Map.of("taskId", taskId, "status", "completed");
    }

    // ── Helpers ──

    /**
     * 這個任務是否由該外部系統持有（R-19 (i)）—— assignee 或 candidateUsers
     * <b>明確</b>包含 {@code system:<systemId>}。
     *
     * <h2>為什麼需要這條規則</h2>
     *
     * <p>擁有權（{@code _externalSystemId}）描述的是<b>流程實例</b>，不是
     * <b>任務</b>。外部系統啟動 leave-approval 之後就擁有該實例，而上面掛著
     * 由真人主管持有的 managerReview —— 少了這一層，系統就能以 taskId 完成
     * 自己案件上的人工簽核（自我核准）。
     *
     * <h2>為什麼候選群組不納入（2026-10-02 裁決）</h2>
     *
     * <p>本 repo 的候選群組名稱有三個互質來源（部門代碼／權限碼／JWT
     * authority，見 {@code CandidateGroupMembership}），沒有任何 API 能回答
     * 「這個群組屬於哪個外部系統」。要納入就只能假設某種命名慣例（例如群組名
     * 也是 {@code system:<id>}），那既會擋掉合法用法，也保護不了真正的情況。
     * 裁決因此只認 assignee 與 candidateUsers。
     *
     * <h2>為什麼用 {@link ExternalActorIdentity} 而不是自己寫前綴比對</h2>
     *
     * <p>{@code system:} 命名空間的鑄造與判定在本 repo 只有一份規則（見該類別
     * 註解「這條規則必須只有一份」）。這裡需要的是「是不是<b>這一個</b>系統」，
     * 所以用 {@code ExternalActorIdentity.of} 產生預期的完整身分再比對 ——
     * 而不是自己 {@code startsWith("system:")} 之後再切字串。
     *
     * <h2>大小寫為什麼不敏感</h2>
     *
     * <p>與 {@link ExternalActorIdentity#isSystemActor} 的慣例一致（見該類別
     * 「為什麼大小寫不敏感」）：伺服器鑄造的一律小寫，但任務的 assignee 可能
     * 來自 BPMN 字面值等其他寫入端。把 {@code SYSTEM:ERP} 視為
     * {@code system:erp} 只會讓一個「本來沒有人能完成」的任務多一個正當完成者；
     * 它不可能誤中一個真人 id（真人 id 不會是 {@code system:} 開頭），
     * 所以這個方向是安全的。
     *
     * <p>⚠️ 不比對 {@code owner}：裁決只認 assignee 與 candidateUsers。
     * 也比對 candidate <b>user</b> 連結而不是所有 identity link —— Flowable 的
     * identity link 還有 participant 等型別，那些不是「候選人」。
     * 不 trim：{@code " system:erp"} 不是系統身分（與
     * {@code ExternalActorIdentity.isSystemActor} 的判準一致，空白不靜默寬容）。
     *
     * @return true = 該系統可完成此任務；false = 必須拒絕
     */
    private boolean isTaskHeldBySystem(Task task, String systemId) {
        String identity = ExternalActorIdentity.of(systemId);
        if (identity.equalsIgnoreCase(task.getAssignee())) return true;
        // ⚠️ getIdentityLinksForTask 的回傳順序沒有保證（見
        // ExternalCandidateGroupShapeTest 的說明）；這裡只做成員比對，不依賴順序。
        for (IdentityLink link : taskService.getIdentityLinksForTask(task.getId())) {
            if (!IdentityLinkType.CANDIDATE.equals(link.getType())) continue;
            if (identity.equalsIgnoreCase(link.getUserId())) return true;
        }
        return false;
    }

    /**
     * 拒絕 {@code _} 前綴的變數名（R-19 (ii)）。
     *
     * <h2>為什麼 {@code _} 是保留命名空間</h2>
     *
     * <p>伺服器用這個前綴存放不允許呼叫端觸及的狀態：
     * {@code _externalSystemId}（擁有權標記，見 {@link #OWNER_VAR}）、
     * {@code _formVersions}（表單版本鎖，見 {@code FormVersionLocker}）、
     * {@code _callbackUrl}。這些值一旦可被 body 覆寫：
     * <ul>
     *   <li>{@code _externalSystemId} → 把實例「過戶」給別的系統
     *       （R-23 是同一個缺陷的另一個入口）。</li>
     *   <li>{@code _formVersions} → 解鎖表單版本，讓案件以舊版表單繼續走。</li>
     * </ul>
     *
     * <h2>為什麼是 400 而不是 403，為什麼要指名</h2>
     *
     * <p>這是「payload 用了保留的欄位名」——呼叫端該改的是 payload，與
     * {@link #validateVariables} 的 400 同一類（不是授權問題、也不是資源不
     * 存在）。只說「變數不合法」會讓呼叫端一個欄位一個欄位試錯；指名變數
     * （全部列出、排序以求訊息穩定）才能一次定位。
     *
     * <p>⚠️ 檢查的是<b>名稱</b>而不是值，也不 trim：變數名是識別字，Flowable
     * 原樣存放，寬容地 trim 等於同時接受兩個名字。
     */
    private static void rejectReservedVariableNames(Map<String, Object> variables) {
        List<String> reserved = variables.keySet().stream()
                .filter(name -> name != null && name.startsWith("_"))
                .sorted()
                .toList();
        if (!reserved.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "變數名稱不可使用 '_' 前綴（伺服器保留的命名空間）: "
                            + String.join(", ", reserved));
        }
    }

    /**
     * 把 body 的 {@code firstTaskCandidateGroups} 轉成「一串群組名」（#93）。
     *
     * <h2>它修的缺陷</h2>
     *
     * <p>{@code docs/bpm-platform-spec.md} §9.2 示範的形狀是 JSON <b>陣列</b>
     * {@code "firstTaskCandidateGroups": ["hr_dept"]}，而改動前這裡是
     * {@code (String) body.get(...)} —— 送陣列就在那一行拋
     * {@code ClassCastException} → 500。已上線的整合方照 spec 抄就會踩到，
     * 而且 500 的語意是「稍後重試」，payload 不變就永遠不會成功 → 批次無限重試。
     *
     * <h2>⚠️ 為什麼 400 而不是 500</h2>
     *
     * <p>沿用<b>同一個方法裡既有的規則</b>，不是新政策：
     * {@code initiator} 冒用 → 400、{@code firstTaskAssignee} 查無此人 → 400、
     * 空白 → 400。也就是「呼叫端該改 payload 就回 400」。
     * 500 的語意是「稍後重試」，而形狀錯了重試一萬次也不會成功 ——
     * 讓呼叫端在「改 payload」與「稍後重試」之間選錯，是可用性問題。
     *
     * <h2>⚠️ 為什麼陣列是 canonical、逗號分隔字串保留為相容形狀</h2>
     *
     * <p>spec 示範的一直是陣列，而<b>已上線的整合方送的是字串</b>。
     * 只留陣列會讓那些整合在部署新版本的那一天全部壞掉；只留字串則讓
     * spec 從第一天起就是錯的。所以兩個都收，spec 也照實標明。
     *
     * <p>⚠️ 兩個形狀的<b>解析結果必須相同</b> —— 那是「規則只有一份」在
     * 跨形狀時的樣子（trim／丟棄空白／去重都在守衛裡，不在這裡各做一次）。
     *
     * <h2>⚠️ 邊界處置為什麼是這樣</h2>
     *
     * <table border="1">
     *   <caption>firstTaskCandidateGroups 的形狀處置</caption>
     *   <tr><th>body</th><th>結果</th><th>理由</th></tr>
     *   <tr><td>{@code ["hr_dept"]}</td><td>接受</td>
     *       <td>spec §9.2 的 canonical 形狀</td></tr>
     *   <tr><td>{@code "hr_dept,finance"}</td><td>接受</td>
     *       <td>相容形狀；已上線的整合方送這個</td></tr>
     *   <tr><td>{@code ["hr_dept",123]}</td><td><b>400</b></td>
     *       <td>元素非字串，指名索引與型別。<b>不在此默默轉成 "123"}</b> ——
     *           那會建立一個叫「123」的候選群組，而沒有任何人會是它的成員
     *           → 靜默卡死，正是本 repo 反覆修的那種缺陷</td></tr>
     *   <tr><td>{@code 123} / {@code true} / {@code {}}</td><td><b>400</b></td>
     *       <td>整個欄位型別錯</td></tr>
     *   <tr><td>{@code null}</td><td>未指定</td>
     *       <td>沿用現況；與「欄位不存在」不可區分</td></tr>
     *   <tr><td>{@code []}</td><td>空清單</td>
     *       <td>沿用現況：被「至少有一個」規則回 400（不新增規則）</td></tr>
     *   <tr><td>{@code ["  "]}</td><td>空清單</td>
     *       <td>同上；空白元素由守衛丟棄</td></tr>
     * </table>
     *
     * <h2>⚠️ 為什麼不在這裡把空清單直接拒絕</h2>
     *
     * <p>{@code []} 與 {@code ["  "]} 刻意<b>不在這裡</b>回 400，而是交給
     * {@code startProcess} 既有的「至少有一個」規則。那條規則比對的是
     * <b>解析後</b>的清單（#88 刻意如此），所以它本來就看得到「沒有群組」。
     * 在這裡再加一條「空陣列要拒絕」就是同一條規則兩套形狀 —— 這個 repo
     * 反覆記載的缺陷成因。留著既有規則，錯誤訊息也就維持同一句。
     *
     * <h2>為什麼是 400 的訊息要指名索引與型別</h2>
     *
     * <p>只說「格式錯誤」會讓呼叫端一個欄位一個欄位試錯；說「第 2 個元素不是
     * 字串」則一次就定位得到。這與 {@code ExternalActorGuard} 指名員工編號、
     * 白名單指名群組名是同一個標準：<b>診斷要能指出該改哪裡</b>。
     *
     * @param raw body 的原始值（{@code Object}，未轉型）
     * @return 已切開的群組名清單（元素<b>未</b> trim；空清單就是空清單）
     * @throws ResponseStatusException 400（形狀錯；呼叫端該改 payload）
     */
    private static List<String> parseCandidateGroups(Object raw) {
        if (raw == null) return List.of();               // 未指定
        // 相容形狀：已上線的整合方送逗號分隔字串。
        // ⚠️ 這裡切完就交給守衛做 trim／去空白／去重，不在這裡做 ——
        // 那樣會是第二份內容規則。
        //
        // ⚠️ split 的 limit 用 -1（保留結尾空字串）而<b>不是</b>預設值：
        // 兩者在這裡結果相同，因為守衛會丟棄所有空白項目
        // （"dept001," → ["dept001"] 或 ["dept001",""] → 都是 ["dept001"]）。
        // 寫 -1 只是讓「切了幾段」忠實反映呼叫端寫了幾段，不做第二層推論。
        if (raw instanceof String s) return List.of(s.split(",", -1));
        if (raw instanceof List<?> list) {
            List<String> out = new ArrayList<>(list.size());
            for (int i = 0; i < list.size(); i++) {
                Object element = list.get(i);
                // ⚠️ 包含 null 在內都要拒絕：null 元素會讓守衛的 g.trim() NPE
                // → 500，那正是本工項要修的失敗型態。
                if (!(element instanceof String s)) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "firstTaskCandidateGroups 的第 " + (i + 1) + " 個元素必須是字串，"
                                    + "收到的是 " + describeJsonType(element) + "。"
                                    + "陣列的每個元素都必須是群組名稱的字串，例如"
                                    + " [\"hr_dept\"]；若要一次指定多個群組請寫成"
                                    + " [\"hr_dept\",\"finance\"]。");
                }
                out.add(s);
            }
            return out;
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "firstTaskCandidateGroups 必須是字串陣列或逗號分隔字串，收到的是 "
                        + describeJsonType(raw) + "。標準形狀是陣列 [\"hr_dept\"]；"
                        + "逗號分隔字串（\"hr_dept,finance\"）為相容形狀。");
    }

    /**
     * JSON 值的可讀型別名稱，只用在錯誤訊息裡。
     *
     * <p>為什麼不用 {@code getClass().getSimpleName()}：那是 Java 型別
     * （{@code LinkedHashMap}、{@code Integer}），對一個照 spec 串接 payload
     * 的呼叫端毫無意義，而 #66 選定 400 的整個目的就是「讓呼叫端知道要改什麼」。
     */
    private static String describeJsonType(Object v) {
        if (v == null) return "null";
        if (v instanceof String) return "字串";
        if (v instanceof Boolean) return "布林值";
        if (v instanceof Number) return "數字";
        if (v instanceof Map) return "物件";
        if (v instanceof Collection) return "陣列";
        return v.getClass().getSimpleName();
    }

    private Map<String, Object> buildStatusResponse(String processInstanceId, String systemId) {
        // Try running first
        ProcessInstance pi = runtimeService.createProcessInstanceQuery()
                .processInstanceId(processInstanceId).singleResult();
        if (pi != null) {
            verifyRunningOwnership(processInstanceId, systemId);

            Map<String, Object> result = new HashMap<>();
            result.put("processInstanceId", processInstanceId);
            result.put("businessKey", pi.getBusinessKey());
            result.put("status", "running");
            result.put("startedAt", pi.getStartTime());
            result.put("result", null);
            result.put("completedAt", null);
            result.put("currentTasks", taskService.createTaskQuery()
                    .processInstanceId(processInstanceId).list().stream()
                    .map(t -> Map.of(
                            "taskId", t.getId(),
                            "taskName", t.getName() != null ? t.getName() : "",
                            "assignee", t.getAssignee() != null ? t.getAssignee() : "",
                            "createdAt", t.getCreateTime()))
                    .toList());
            return result;
        }

        // Check history
        HistoricProcessInstance hp = historyService.createHistoricProcessInstanceQuery()
                .processInstanceId(processInstanceId).singleResult();
        if (hp == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);

        // ⚠️ 改動前只有「執行中」的實例做擁有權檢查，已完成的實例直接回傳 ——
        // 任何外部系統都能讀取其他系統已結案的案件。
        verifyHistoricOwnership(processInstanceId, systemId);

        return buildStatusFromHistory(hp);
    }

    private Map<String, Object> buildStatusFromHistory(HistoricProcessInstance hp) {
        Map<String, Object> result = new HashMap<>();
        result.put("processInstanceId", hp.getId());
        result.put("businessKey", hp.getBusinessKey());
        result.put("startedAt", hp.getStartTime());
        result.put("completedAt", hp.getEndTime());
        result.put("currentTasks", List.of());

        if (hp.getEndTime() == null) {
            result.put("status", "running");
            result.put("result", null);
        } else if (hp.getDeleteReason() != null) {
            result.put("status", "cancelled");
            result.put("result", null);
        } else {
            result.put("status", "completed");
            // Try to determine result from historic variables
            // 同樣避開 singleResult：子流程／multi-instance 的區域變數
            // 可能出現同名多筆，屆時這裡會拋例外而非回傳狀態。
            var varList = historyService.createHistoricVariableInstanceQuery()
                    .processInstanceId(hp.getId()).variableName("rejected").list();
            var vars = varList.isEmpty() ? null : varList.get(0);
            boolean rejected = vars != null && Boolean.TRUE.equals(vars.getValue());
            result.put("result", rejected ? "rejected" : "approved");
        }
        return result;
    }

    // ── 擁有權檢查 ──

    /** 往上追溯父流程的層數上限，防止資料異常造成無限迴圈。 */
    private static final int MAX_PARENT_DEPTH = 10;

    /** 執行中實例的擁有權檢查。 */
    private void verifyRunningOwnership(String processInstanceId, String systemId) {
        verifyOwnership(processInstanceId, systemId);
    }

    /** 已結案（歷史）實例的擁有權檢查。 */
    private void verifyHistoricOwnership(String processInstanceId, String systemId) {
        verifyOwnership(processInstanceId, systemId);
    }

    /**
     * 擁有權檢查。
     *
     * <p>同時處理執行中與已結案的實例，並沿 Call Activity 的父子關係往上追溯：
     *
     * <ul>
     *   <li><b>processInstanceId 為 null</b> —— 附屬簽的 subtask 是以
     *       {@code taskService.newTask()} 建立的 standalone task，沒有
     *       processInstanceId。直接拒絕，而不是讓 Flowable 拋例外變成 500。</li>
     *   <li><b>Call Activity 子流程</b> —— 子流程是獨立的 process instance，
     *       變數不會自動繼承，因此子流程內的 task 取不到 {@code _externalSystemId}。
     *       若不往上追溯，附屬簽（spec §4.4.2 以 Call Activity 實作）一上線
     *       這個檢查就會擋掉自己的子流程。</li>
     * </ul>
     */
    private void verifyOwnership(String processInstanceId, String systemId) {
        if (processInstanceId == null || processInstanceId.isBlank()) {
            throw forbidden();
        }

        String pid = processInstanceId;
        for (int depth = 0; depth < MAX_PARENT_DEPTH && pid != null; depth++) {
            Object owner = variableOf(pid, OWNER_VAR);
            if (owner != null) {
                if (systemId.equals(owner.toString())) return;
                throw forbidden();
            }
            // 向後相容：本次改動前啟動的實例沒有 _externalSystemId
            Object initiator = variableOf(pid, "initiator");
            if (initiator != null && initiator.toString().equals(ExternalActorIdentity.of(systemId))) return;

            pid = superProcessInstanceIdOf(pid);
        }
        throw forbidden();
    }

    /** 取變數值，先查執行中再查歷史；實例不存在時回 null 而非拋例外。 */
    private Object variableOf(String processInstanceId, String name) {
        // 先確認實例仍在執行，而不是呼叫 getVariable 再 catch 例外：
        // Flowable 命令在外層交易（completeTask 的 @Transactional）中拋例外，
        // 會把外層交易標成 rollback-only，catch 住也救不回來。
        if (runtimeService.createProcessInstanceQuery()
                .processInstanceId(processInstanceId).singleResult() != null) {
            Object v = runtimeService.getVariable(processInstanceId, name);
            if (v != null) return v;
        }
        // 實例已結束，改查歷史
        return historicVariable(processInstanceId, name);
    }

    private Object historicVariable(String processInstanceId, String name) {
        var v = historyService.createHistoricVariableInstanceQuery()
                .processInstanceId(processInstanceId).variableName(name).singleResult();
        return v == null ? null : v.getValue();
    }

    /** 父流程實例 id（非 Call Activity 子流程時為 null）。 */
    private String superProcessInstanceIdOf(String processInstanceId) {
        HistoricProcessInstance hp = historyService.createHistoricProcessInstanceQuery()
                .processInstanceId(processInstanceId).singleResult();
        return hp == null ? null : hp.getSuperProcessInstanceId();
    }

    private ResponseStatusException forbidden() {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, "無權存取此流程");
    }

    /**
     * 檢查必填變數（{@code spec.required == true}）。
     *
     * <p>⚠️ 本方法現在有兩個呼叫點：{@code startProcess}（#91）與
     * {@code completeTask}（R-19 (ii)）。「什麼時候驗」由呼叫端決定，
     * 「怎麼驗」只有這一份 —— 兩個入口的必填語意必須一致，這正是它是
     * 一份實作而不是各寫一份的理由。未宣告的變數兩邊都放行（本方法只看
     * spec 裡 {@code required=true} 的項目），這是既有語意，R-19 沿用。
     *
     * <h2>#91 缺陷：{@code containsKey} 把「有值卻等於沒有值」放行了</h2>
     *
     * <p>改動前這裡只判 {@code !variables.containsKey(name)}。於是
     * {@code {"dept": "  "}} 與 {@code {"dept": null}} 都因為<b>鍵存在</b>而通過，
     * 但 BPMN 的 {@code flowable:assignee="${dept}"} 求值成空白／null：
     * assignee 非 null（空字串）或為 null 卻沒有候選人，
     * {@code ASSIGNEE_ IS NULL} 的候選群組查詢不命中 → 案件靜默卡死。
     * 呼叫端拿到 200 與一個 processInstanceId，完全看不出問題。
     *
     * <h2>裁決後的語意：符合任一 → 400</h2>
     *
     * <ol>
     *   <li>缺值：{@code !containsKey}（既有行為，訊息不變）。</li>
     *   <li>值為 {@code null}。</li>
     *   <li>值為 {@code String} 且 {@code isBlank()}。</li>
     * </ol>
     *
     * <p>兩種 400 的訊息刻意不同：一種是「你根本沒送這個欄位」，
     * 另一種是「你送了但它是空白」——呼叫端要改的是 payload 的不同地方。
     * 但都維持 <b>400</b>：這是「請求形狀不對、改了才會成功」，不是可重試的 5xx。
     *
     * <h2>⚠️ 刻意<b>不</b>擋：{@code 0}、{@code false}、空集合／空 Map</h2>
     *
     * <p>「空白」的判準是<b>字串語意</b>，不是一般程式語言裡的
     * <b>falsy</b>。因此這裡用 {@code value == null || (value instanceof String s && s.isBlank())}，
     * 而<b>不是</b> {@code value == null || "".equals(value.toString().trim())} 之類
     * 會把任何型別都先轉成字串再判的寫法。
     *
     * <p>為什麼這個取捨重要：{@code 0}（例如 {@code days}＝0）、{@code false}
     * （例如布林旗標）、空集合／空 Map 都是<b>合法且語意明確的必填值</b>。
     * 把它們當成「空白」擋下，等於把一個「漏填」缺陷換成一個「合法的 0 填不進來」
     * 缺陷，而且症狀一樣是靜默卡死 —— 只是換了一批受害者。
     * {@code required=false} 的變數完全不受本方法影響。
     */
    private void validateVariables(String processDefKey, Map<String, Object> variables) {
        List<ProcessVariableSpec> specs = specRepo.findByProcessDefinitionKeyOrderByVariableName(processDefKey);
        for (ProcessVariableSpec spec : specs) {
            if (!Boolean.TRUE.equals(spec.getRequired())) continue;
            String name = spec.getVariableName();
            // ① 缺值：鍵不存在。既有行為，訊息不變（呼叫端既有測試依賴它）。
            if (!variables.containsKey(name)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "缺少必填變數: " + name);
            }
            // ②／③ 有鍵但值等於沒有值：null 或純空白字串。
            Object value = variables.get(name);
            if (value == null || (value instanceof String s && s.isBlank())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "必填變數不可為空白: " + name);
            }
        }
    }
}
