package com.bpm.core.external;

import com.bpm.core.audit.AuditEventPublisher;
import com.bpm.core.dto.AuditEvent;
import com.bpm.core.model.ExternalSystem;
import com.bpm.core.model.ProcessVariableSpec;
import com.bpm.core.repository.ProcessVariableSpecRepository;
import com.bpm.core.service.FormVersionLocker;
import org.flowable.engine.HistoryService;
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
    private final ProcessVariableSpecRepository specRepo;
    private final AuditEventPublisher auditPublisher;
    private final FormVersionLocker formVersionLocker;
    private final ExternalSystemPolicy policy;
    private final com.bpm.core.service.OrgService orgService;

    /**
     * 流程實例的擁有者。啟動時由 server 寫入，外部系統無法透過 request body 影響。
     *
     * <p>改動前擁有權是比對 {@code initiator}，但 {@code initiator} 可由呼叫端在
     * body 中任意指定（見 startProcess），因此不是可信的擁有權來源。
     */
    private static final String OWNER_VAR = "_externalSystemId";

    public ExternalApiController(RuntimeService runtimeService, TaskService taskService,
                                  HistoryService historyService, ProcessVariableSpecRepository specRepo,
                                  AuditEventPublisher auditPublisher, FormVersionLocker formVersionLocker,
                                  ExternalSystemPolicy policy,
                                  com.bpm.core.service.OrgService orgService) {
        this.runtimeService = runtimeService;
        this.taskService = taskService;
        this.historyService = historyService;
        this.specRepo = specRepo;
        this.auditPublisher = auditPublisher;
        this.formVersionLocker = formVersionLocker;
        this.policy = policy;
        this.orgService = orgService;
    }

    // ── 1. Start Process ──

    @PostMapping("/process-instances")
    public Map<String, Object> startProcess(@RequestBody Map<String, Object> body,
                                             @RequestAttribute("externalSystemId") String systemId,
                                             @RequestAttribute("externalSystem") ExternalSystem sys) {
        String processDefKey = (String) body.get("processDefinitionKey");
        String businessKey = (String) body.get("businessKey");
        String initiator = (String) body.getOrDefault("initiator", "system:" + systemId);
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

        // 沒有指定受理人也沒有候選群組 → 流程會走組織查詢（initiator 的直屬主管）。
        //
        // ⚠️ 原本的條件是 initiator.startsWith("system:")，而 initiator 完全由
        // 呼叫端指定（R-20）—— 送一個不以 system: 開頭的值就能整個跳過這個檢查。
        // 改動前的後果不是「檢查被跳過」而已：組織 mock 對未知 userId 一律回
        // mgr001，所以案件會<b>啟動成功並派給 mgr001</b>，看起來毫無異常。
        //
        // 現在判斷的依據換成「流程接下來需不需要查組織」這個客觀事實，
        // 不再依賴呼叫端可任意指定的字串。system:<id> 一定查不到（不是人），
        // 偽造的員工編號也一樣 —— 兩者都會在這裡拿到明確的 400，
        // 而不是流程啟動時 JUEL 求值失敗的 500。
        if (firstAssignee == null && firstGroups == null) {
            if (initiator.startsWith("system:")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "外部系統發起流程必須指定 firstTaskAssignee 或 firstTaskCandidateGroups"
                                + "（initiator=" + initiator + " 不是組織系統中的人員，無法推導簽核主管）");
            }
            try {
                orgService.getDirectManager(initiator);
            } catch (Exception e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "initiator=" + initiator + " 不是組織系統認識的人員，"
                                + "無法推導簽核主管。請指定 firstTaskAssignee 或 firstTaskCandidateGroups。", e);
            }
        }

        // Validate variables against ProcessVariableSpec
        validateVariables(processDefKey, variables);

        // Prepare variables
        variables.put("initiator", initiator);
        // 擁有者由 server 決定，覆寫呼叫端可能夾帶的同名變數（body 的 variables
        // 是自由 map，必須在這裡最後寫入才不會被蓋掉）。
        variables.put(OWNER_VAR, systemId);
        if (initiator.startsWith("system:") && firstAssignee != null) {
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

        // Start process
        ProcessInstance pi = runtimeService.startProcessInstanceByKey(processDefKey, businessKey, variables);

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
            if (firstGroups != null) {
                for (String g : firstGroups.split(",")) {
                    taskService.addCandidateGroup(firstTask.getId(), g.trim());
                }
            }
        }

        auditPublisher.publish(new AuditEvent("EXTERNAL_API_CALL", "system:" + systemId,
                "external_api", processDefKey, pi.getProcessInstanceId(), null, businessKey,
                Map.of("action", "start_process", "processDefinitionKey", processDefKey),
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
                .variableValueEquals("initiator", "system:" + systemId)
                .orderByProcessInstanceStartTime().desc().list().stream()
                .map(hp -> buildStatusFromHistory(hp))
                .toList();
    }

    // ── 3. Complete Task ──

    @PutMapping("/tasks/{taskId}")
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

        auditPublisher.publish(new AuditEvent("EXTERNAL_API_CALL", "system:" + systemId,
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
            if (initiator != null && initiator.toString().equals("system:" + systemId)) return;

            pid = superProcessInstanceIdOf(pid);
        }
        throw forbidden();
    }

    /** 取變數值，先查執行中再查歷史；實例不存在時回 null 而非拋例外。 */
    private Object variableOf(String processInstanceId, String name) {
        try {
            Object v = runtimeService.getVariable(processInstanceId, name);
            if (v != null) return v;
        } catch (RuntimeException ignored) {
            // 實例已結束時 runtimeService 會拋例外，改查歷史
        }
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
