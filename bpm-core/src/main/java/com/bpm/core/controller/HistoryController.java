package com.bpm.core.controller;

import com.bpm.core.security.CallerId;
import com.bpm.core.security.ProcessAccessGuard;
import com.bpm.core.service.InitialAssigneeResolver;
import com.bpm.core.service.ProcessInvolvementService;
import org.flowable.engine.HistoryService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.task.api.Task;
import org.flowable.task.api.history.HistoricTaskInstance;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/history")
public class HistoryController {

    private final HistoryService historyService;
    private final TaskService taskService;
    private final ProcessAccessGuard accessGuard;
    private final ProcessInvolvementService involvementService;

    public HistoryController(HistoryService historyService, TaskService taskService,
                             ProcessAccessGuard accessGuard,
                             ProcessInvolvementService involvementService) {
        this.historyService = historyService;
        this.taskService = taskService;
        this.accessGuard = accessGuard;
        this.involvementService = involvementService;
    }

    @GetMapping("/tasks")
    public List<Map<String, Object>> getHistoricTasks(
            @RequestParam(required = false) String assignee,
            @RequestParam(required = false) String processInstanceId) {
        var query = historyService.createHistoricTaskInstanceQuery().finished();
        if (assignee != null) query.taskAssignee(assignee);
        if (processInstanceId != null) query.processInstanceId(processInstanceId);
        return query.orderByHistoricTaskInstanceEndTime().desc().list().stream()
                .map(this::taskToMap).toList();
    }

    @GetMapping("/tasks/{taskId}/comments")
    public List<Map<String, Object>> getHistoricTaskComments(@PathVariable String taskId) {
        return TaskController.mapComments(taskService.getTaskComments(taskId));
    }

    /**
     * 我的申請（歷史）（#71：讀端授權）。
     *
     * <p>規則與 {@code GET /api/process-instances} 完全相同：省略參數 = 呼叫者自己、
     * 帶了別人的 id = 明確 400。改動前不帶參數即回傳<b>全公司</b>的歷史實例。
     *
     * <p>⚠️ <b>不在本次範圍</b>：{@code GET /api/history/tasks}（簽核時間軸）
     * 與 {@code .../comments}（簽核意見）同樣零物件層授權 ——
     * 前端的 {@code ApprovalTimeline} 刻意不傳 {@code assignee}，
     * 因此任何登入者可看任何案件的完整簽核時間軸。
     * 但收件匣頁面就是靠它渲染的，且「點開一個自己參與過的案件看簽核軌跡」
     * 是正常需求，因此它需要的是「以 processInstanceId 為條件時驗參與者」
     * 而不是照搬本方法。見 backlog #71 剩餘項目。
     */
    @GetMapping("/process-instances")
    public List<Map<String, Object>> getHistoricProcessInstances(
            @RequestParam(required = false) String initiator,
            @RequestParam(required = false, defaultValue = "false") boolean finished,
            @CallerId String callerId) {
        String self = accessGuard.requireSelf(initiator, callerId, "initiator");
        var query = historyService.createHistoricProcessInstanceQuery();
        // 代員工發起的案件（R-20）：initiator 是 system:<id>，員工記在 onBehalfOf。
        // 兩者都要比對，否則代發的單不會出現在那位員工的「我的申請」。
        // 回應以 onBehalf=true 標示，讓前端能顯示「由外部系統代為提出」——
        // 使用者看到一張自己沒送過的單，必須知道它是怎麼來的。
        query.or().variableValueEquals("initiator", self)
                .variableValueEquals(InitialAssigneeResolver.ON_BEHALF_OF_VAR, self).endOr();
        java.util.Set<String> onBehalf = historyService.createHistoricProcessInstanceQuery()
                .variableValueEquals(InitialAssigneeResolver.ON_BEHALF_OF_VAR, self).list()
                .stream().map(HistoricProcessInstance::getId).collect(java.util.stream.Collectors.toSet());
        if (finished) query.finished();
        final java.util.Set<String> delegated = onBehalf;
        return query.orderByProcessInstanceStartTime().desc().list().stream()
                .map(p -> {
                    Map<String, Object> m = processToMap(p);
                    m.put("onBehalf", delegated.contains(p.getId()));
                    return m;
                }).toList();
    }

    /**
     * 我參與的案件（歷史，含仍在執行中的）。
     *
     * <p>回應形狀沿用 {@link #processToMap}（它已涵蓋 running／completed／
     * cancelled 三種狀態），再補上 {@code currentTask} 與
     * {@code currentTaskCount} —— 前者讓使用者知道這張單現在卡在誰手上，
     * 後者是因為併發任務時只顯示一個會讓人以為只等一個人。
     *
     * <p>⚠️ {@code processToMap} 的輸出<b>永遠不可</b>用
     * {@code singleResult()} 取得 currentTask（見 ProcessController 內的註解）：
     * 平行閘道／multi-instance 會簽會產生併發任務，那會讓整個端點對所有人 500。
     * 這裡改成一次撈回所有相關實例的未完成任務（見 {@link ProcessInvolvementService}）。
     *
     * <p>授權：不帶任何參數，範圍就是「{@code isParticipant} 為真的案件」。
     */
    @GetMapping("/process-instances/involved")
    public List<Map<String, Object>> getInvolvedHistoricProcessInstances(@CallerId String callerId) {
        if (callerId == null || callerId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "無法確認身分，請先登入");
        }
        java.util.Set<String> ids = involvementService.involvedHistoricInstanceIds(callerId);
        if (ids.isEmpty()) return List.of();

        // currentTask 只對執行中的實例有意義；已結束的實例在 runtime 查不到任務。
        java.util.Map<String, List<Task>> tasksByInstance = new java.util.HashMap<>();
        for (Task t : involvementService.findOpenTasks(ids)) {
            tasksByInstance.computeIfAbsent(t.getProcessInstanceId(), k -> new java.util.ArrayList<>()).add(t);
        }
        return involvementService.findHistoric(ids).stream()
                .map(p -> {
                    Map<String, Object> m = processToMap(p);
                    // 標示「這不是我的申請，是我參與的」，否則使用者會以為
                    // 這張自己沒送過的單出現在自己的清單裡。
                    m.put("involved", true);
                    List<Task> tasks = tasksByInstance.getOrDefault(p.getId(), List.of());
                    if (!tasks.isEmpty()) {
                        Task task = tasks.get(0);
                        m.put("currentTask", Map.of(
                                "taskName", task.getName() != null ? task.getName() : "",
                                "assignee", task.getAssignee() != null ? task.getAssignee() : ""));
                        m.put("currentTaskCount", tasks.size());
                    }
                    return m;
                }).toList();
    }

    private Map<String, Object> taskToMap(HistoricTaskInstance t) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", t.getId());
        m.put("name", t.getName());
        m.put("assignee", t.getAssignee());
        m.put("processInstanceId", t.getProcessInstanceId());
        m.put("startTime", t.getStartTime());
        m.put("endTime", t.getEndTime());
        return m;
    }

    /**
     * 歷史實例的對外表示法。
     *
     * <p>{@code status} 由 {@code endTime}／{@code deleteReason} 推導，
     * 因此同一份形狀涵蓋執行中與已結束 —— 這是「我參與的」新端點
     * 能只維護一份對映的原因。
     */
    Map<String, Object> processToMap(HistoricProcessInstance p) {
        Map<String, Object> m = new HashMap<>();
        m.put("processInstanceId", p.getId());
        m.put("processDefinitionKey", p.getProcessDefinitionKey());
        m.put("businessKey", p.getBusinessKey());
        m.put("startTime", p.getStartTime());
        m.put("endTime", p.getEndTime());
        // Derive status
        if (p.getEndTime() == null) {
            m.put("status", "running");
        } else if (p.getDeleteReason() != null) {
            m.put("status", "cancelled");
        } else {
            m.put("status", "completed");
        }
        return m;
    }
}
