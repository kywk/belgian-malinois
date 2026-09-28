package com.bpm.core.controller;

import com.bpm.core.audit.AuditEventPublisher;
import com.bpm.core.dto.AuditEvent;
import com.bpm.core.dto.CommentRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.flowable.common.engine.impl.identity.Authentication;
import com.bpm.core.dto.TaskActionRequest;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Stream;

@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    /**
     * 不可由呼叫端以任務變數改寫的變數名。
     *
     * <p>{@code initiator} 是關鍵：兩支已部署的 BPMN 都用
     * {@code ${orgService.getDirectManager(initiator)}} 解析主管、
     * 用 {@code ${initiator}} 指派補件任務。申請人在完成自己的補件任務時
     * 附帶一個偽造的 initiator，下一輪主管審核就會派給他指定的人的主管
     * —— 等於簽核人自選審核者（security-audit P0-5）。
     *
     * <p>{@code effectiveInitiator} 是伺服器由外部系統請求推導出來的身分，
     * 同理不可由呼叫端指定。
     *
     * <p>{@code _} 前綴的一律拒絕（R-23）：那是伺服器的內部狀態，
     * 包含 {@code _formVersions}（表單版本鎖定）與
     * {@code _externalSystemId}（外部系統擁有權判定的依據）。
     *
     * <p>⚠️ 這是保護名單（deny-list）而非完整的白名單。真正的白名單應該來自
     * formKey 的 schema（spec §8.5：欄位 id == 變數名），但那需要在此查詢
     * form-service 並處理它不可用時的行為，範圍更大。目前的做法擋住了所有
     * 「改寫引擎與身分語意」的變數，而一般業務欄位照常放行。
     */
    private static final java.util.Set<String> PROTECTED_VARIABLES =
            java.util.Set.of("initiator", "effectiveInitiator");

    private static boolean isProtectedVariable(String name) {
        if (name == null || name.isBlank()) return true;
        return name.startsWith("_") || PROTECTED_VARIABLES.contains(name);
    }

    private final TaskService taskService;
    private final RuntimeService runtimeService;
    private final RepositoryService repositoryService;
    private final HistoryService historyService;
    private final AuditEventPublisher auditPublisher;

    public TaskController(TaskService taskService, RuntimeService runtimeService,
                          RepositoryService repositoryService, HistoryService historyService,
                          AuditEventPublisher auditPublisher) {
        this.taskService = taskService;
        this.runtimeService = runtimeService;
        this.repositoryService = repositoryService;
        this.historyService = historyService;
        this.auditPublisher = auditPublisher;
    }

    /**
     * Merged pending tasks: assignee + candidateUser + candidateGroups, deduplicated.
     */
    @GetMapping
    public List<Map<String, Object>> getTasks(
            @RequestParam(required = false) String assignee,
            @RequestParam(required = false) String candidateUser,
            @RequestParam(required = false) String candidateGroups) {

        Map<String, Task> taskMap = new LinkedHashMap<>();

        if (assignee != null) {
            taskService.createTaskQuery().taskAssignee(assignee).list()
                    .forEach(t -> taskMap.put(t.getId(), t));
        }
        if (candidateUser != null) {
            taskService.createTaskQuery().taskCandidateUser(candidateUser).list()
                    .forEach(t -> taskMap.putIfAbsent(t.getId(), t));
        }
        if (candidateGroups != null) {
            taskService.createTaskQuery()
                    .taskCandidateGroupIn(List.of(candidateGroups.split(",")))
                    .list().forEach(t -> taskMap.putIfAbsent(t.getId(), t));
        }

        // If no filter params, return all
        if (assignee == null && candidateUser == null && candidateGroups == null) {
            taskService.createTaskQuery().orderByTaskCreateTime().desc().list()
                    .forEach(t -> taskMap.put(t.getId(), t));
        }

        // Filter out candidate tasks where user already reviewed in the same process
        String filterUser = assignee != null ? assignee : candidateUser;
        if (filterUser != null) {
            Set<String> reviewedProcessIds = historyService.createHistoricTaskInstanceQuery()
                    .taskAssignee(filterUser).finished().list().stream()
                    .map(ht -> ht.getProcessInstanceId())
                    .collect(java.util.stream.Collectors.toSet());
            taskMap.entrySet().removeIf(e -> {
                Task t = e.getValue();
                // Keep if directly assigned; remove only unassigned candidate tasks
                return t.getAssignee() == null && reviewedProcessIds.contains(t.getProcessInstanceId());
            });
        }

        return taskMap.values().stream()
                .sorted(Comparator.comparing(Task::getCreateTime).reversed())
                .map(this::toMap).toList();
    }

    @PutMapping("/{id}")
    public Map<String, Object> updateTask(@PathVariable String id, @RequestBody TaskActionRequest req) {
        Task task = taskService.createTaskQuery().taskId(id).singleResult();
        String processInstanceId = task != null ? task.getProcessInstanceId() : null;
        String action = req.action();
        String auditType;

        if ("claim".equals(action)) {
            taskService.claim(id, req.assignee());
            auditType = "TASK_CLAIM";
        } else if ("complete".equals(action)) {
            // Block complete if there are pending subtasks (countersign)
            if (!taskService.getSubTasks(id).isEmpty()) {
                return Map.of("taskId", id, "status", "error", "message", "有未完成的加簽子任務");
            }
            Map<String, Object> vars = new HashMap<>();
            if (req.variables() != null) {
                // 拒絕而非靜默丟棄：靜默丟棄會讓攻擊嘗試無跡可循，
                // 也會讓正常使用者以為自己送出的值生效了。
                req.variables().stream()
                        .filter(v -> isProtectedVariable(v.name()))
                        .findFirst()
                        .ifPresent(v -> {
                            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                    "不允許以任務變數改寫受保護的變數: " + v.name());
                        });
                req.variables().forEach(v -> vars.put(v.name(), v.value()));
            }
            // Ensure gateway variables are always set to avoid EL PropertyNotFoundException
            vars.putIfAbsent("rejected", false);
            vars.putIfAbsent("approved", false);
            taskService.complete(id, vars);
            auditType = task != null && task.getName() != null && task.getName().contains("補件")
                    ? "TASK_RESUBMIT" : resolveCompleteAuditType(vars);
        } else if ("delegate".equals(action)) {
            taskService.delegateTask(id, req.delegateUser());
            auditType = "TASK_DELEGATE";
        } else if ("resolve".equals(action)) {
            taskService.resolveTask(id);
            auditType = "TASK_RESOLVE";
        } else if (req.assignee() != null) {
            taskService.setAssignee(id, req.assignee());
            auditType = "TASK_REASSIGN";
        } else {
            auditType = "TASK_UPDATE";
        }

        Map<String, Object> detail = new HashMap<>();
        detail.put("action", action != null ? action : "reassign");
        if (req.variables() != null) {
            req.variables().forEach(v -> detail.put(v.name(), v.value()));
        }

        auditPublisher.publish(new AuditEvent(auditType, req.assignee(), processInstanceId, id, detail));
        return Map.of("taskId", id, "status", "ok");
    }

    /**
     * 新增批註。
     *
     * <p>⚠️ 必須設定 Flowable 的 {@code Authentication} —— 這是 TC-A02
     * （多人批註）驗收不過的根因。{@code taskService.addComment} 的作者
     * 取自 {@code Authentication.getAuthenticatedUserId()}，本專案先前
     * 從未設定過，因此每一筆批註的 userId 都是 null，API 一律回空字串。
     * 單人批註看起來正常（訊息有寫入），所以缺陷只在多人情境顯現 ——
     * 而「分辨誰說了什麼」正是多方意見的全部意義。
     *
     * <p>身分優先取 {@code X-User-Id} 標頭（前端共用 axios instance 一律
     *附上，acceptance 腳本也用它），退回 body 的 {@code userId}。
     * 兩者皆無時<b>仍然接受</b>批註：acceptance-test.sh 的 TC-L04 就是
     * 只帶標頭、body 無 userId 的形狀，若改成必填會讓原本通過的案例退步。
     */
    @PostMapping("/{id}/comments")
    public Map<String, String> addComment(@PathVariable String id,
                                          @RequestBody CommentRequest req,
                                          @RequestHeader(value = "X-User-Id", required = false)
                                          String headerUserId) {
        Task task = taskService.createTaskQuery().taskId(id).singleResult();
        String processInstanceId = task != null ? task.getProcessInstanceId() : null;

        String author = firstNonBlank(headerUserId, req.userId());

        // 用 try/finally 還原原值：Authentication 存放在 ThreadLocal，
        // 而 servlet 容器的執行緒是重複使用的 —— 不還原會讓下一個請求
        // 沿用上一個使用者的身分。
        String previous = Authentication.getAuthenticatedUserId();
        try {
            Authentication.setAuthenticatedUserId(author);
            taskService.addComment(id, processInstanceId, req.message());
        } finally {
            Authentication.setAuthenticatedUserId(previous);
        }

        auditPublisher.publish(new AuditEvent("TASK_COMMENT", author,
                processInstanceId, id, Map.of("message", req.message())));
        return Map.of("status", "ok");
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        if (b != null && !b.isBlank()) return b;
        return null;
    }

    @GetMapping("/{id}/comments")
    public List<Map<String, Object>> getComments(@PathVariable String id) {
        return mapComments(taskService.getTaskComments(id));
    }

    private String resolveCompleteAuditType(Map<String, Object> vars) {
        if (Boolean.TRUE.equals(vars.get("rejected"))) return "TASK_REJECT";
        if (Boolean.FALSE.equals(vars.get("approved"))) return "TASK_RETURN";
        if (Boolean.TRUE.equals(vars.get("approved"))) return "TASK_APPROVE";
        return "TASK_APPROVE";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(Task t) {
        Map<String, Object> m = new HashMap<>();
        m.put("taskId", t.getId());
        m.put("taskName", t.getName());
        m.put("assignee", t.getAssignee());
        m.put("processInstanceId", t.getProcessInstanceId());
        m.put("createTime", t.getCreateTime());
        m.put("dueDate", t.getDueDate());
        m.put("formKey", t.getFormKey());
        // Resolve locked formVersion from process variable
        try {
            Map<String, Integer> versions = (Map<String, Integer>)
                    runtimeService.getVariable(t.getProcessInstanceId(), "_formVersions");
            if (versions != null && t.getFormKey() != null) {
                m.put("formVersion", versions.get(t.getFormKey()));
            }
        } catch (Exception ignored) {}
        // Enrich with processDefinitionKey and businessKey
        try {
            ProcessInstance pi = runtimeService.createProcessInstanceQuery()
                    .processInstanceId(t.getProcessInstanceId()).singleResult();
            if (pi != null) {
                m.put("processDefinitionKey", pi.getProcessDefinitionKey());
                m.put("businessKey", pi.getBusinessKey());
            }
        } catch (Exception ignored) {}
        return m;
    }

    static List<Map<String, Object>> mapComments(List<org.flowable.engine.task.Comment> comments) {
        return comments.stream()
                .map(c -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("id", c.getId());
                    m.put("message", c.getFullMessage());
                    m.put("userId", c.getUserId() != null ? c.getUserId() : "");
                    m.put("time", c.getTime().toString());
                    return m;
                }).toList();
    }
}
