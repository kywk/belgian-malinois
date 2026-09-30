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
                                  ExternalActorGuard actorGuard) {
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
        String firstGroups = (String) body.get("firstTaskCandidateGroups");
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
        List<String> firstCandidateGroups =
                actorGuard.requireAllowedCandidateGroups(sys, firstGroups);

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
        com.bpm.core.service.InitialAssigneeResolver.putIfPresent(variables, firstAssignee, firstGroups);
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
            if (firstAssignee != null) taskService.setAssignee(firstTask.getId(), firstAssignee);
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

    @PutMapping("/tasks/{taskId}")
    @Transactional("primaryTransactionManager")
    public Map<String, Object> completeTask(@PathVariable String taskId,
                                             @RequestBody Map<String, Object> body,
                                             @RequestAttribute("externalSystemId") String systemId) {
        Task task = taskService.createTaskQuery().taskId(taskId).singleResult();
        if (task == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found");

        // ⚠️ 改動前這個端點完全沒有擁有權檢查 —— 任何通過 API Key 驗證的外部系統
        // 都能以 taskId 完成「任何」任務，包含其他系統的案件與人工簽核任務。
        verifyRunningOwnership(task.getProcessInstanceId(), systemId);

        @SuppressWarnings("unchecked")
        Map<String, Object> vars = body.get("variables") instanceof Map
                ? new HashMap<>((Map<String, Object>) body.get("variables")) : new HashMap<>();

        taskService.complete(taskId, vars);

        auditPublisher.publish(new AuditEvent("EXTERNAL_API_CALL", ExternalActorIdentity.of(systemId),
                "external_api", null, task.getProcessInstanceId(), taskId, null,
                Map.of("action", "complete_task", "variables", vars),
                java.time.Instant.now()));

        return Map.of("taskId", taskId, "status", "completed");
    }

    // ── Helpers ──

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

    private void validateVariables(String processDefKey, Map<String, Object> variables) {
        List<ProcessVariableSpec> specs = specRepo.findByProcessDefinitionKeyOrderByVariableName(processDefKey);
        for (ProcessVariableSpec spec : specs) {
            if (Boolean.TRUE.equals(spec.getRequired()) && !variables.containsKey(spec.getVariableName())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "缺少必填變數: " + spec.getVariableName());
            }
        }
    }
}
