package com.bpm.core.controller;

import com.bpm.core.audit.AuditEventPublisher;
import com.bpm.core.dto.AuditEvent;
import com.bpm.core.audit.model.OperationType;
import com.bpm.core.dto.CommentRequest;
import org.flowable.common.engine.api.FlowableTaskAlreadyClaimedException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.flowable.common.engine.impl.identity.Authentication;
import com.bpm.core.dto.TaskActionRequest;
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
    private final AuditEventPublisher auditPublisher;

    public TaskController(TaskService taskService, RuntimeService runtimeService,
                          RepositoryService repositoryService,
                          AuditEventPublisher auditPublisher) {
        this.taskService = taskService;
        this.runtimeService = runtimeService;
        this.repositoryService = repositoryService;
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

        // ── 「同一人不得重複簽核」的過濾已移除（2026-09-28 決策）───────
        //
        // 移除的原因（security-audit P1-5，該項是審查中唯一沒有給出修法的）：
        //
        //  1. **它從未真正強制任何規則**。過濾只發生在待辦查詢，
        //     PUT /api/tasks/{id} 沒有對應檢查 —— 知道 taskId 就能簽第二次。
        //     一個被當成業務規則展示、實際上只是隱藏的機制，
        //     比沒有這個機制更糟：它讓人以為規則已經生效。
        //
        //  2. **它會讓案件靜默卡死**。過濾範圍是整個流程實例而非節點：
        //     只要該使用者在此 instance 完成過任何任務，所有未指派的候選任務
        //     都被移除。實際情境 —— 財務退回 → 主管再審通過 → 案子回到
        //     financeReview（candidateUsers），但當初退件的財務人員已有
        //     finished 歷史 → 該任務對他永久隱藏。若該權限只有一人，
        //     案件就此卡住且沒有任何錯誤訊息。
        //     （改用 taskDefinitionKey 也解不掉：退回後回到的就是同一個節點。
        //       真正要區分的是「同一輪」，而系統目前沒有輪次的概念。）
        //
        // 若日後確實需要「不得重複簽核」，正確做法是：先定義「一輪」的界線，
        // 然後在 complete() 加上真正的檢查（拒絕而非隱藏），
        // 而不是在查詢端過濾。

        return taskMap.values().stream()
                .sorted(Comparator.comparing(Task::getCreateTime).reversed())
                .map(this::toMap).toList();
    }

    /**
     * 任務動作。
     *
     * <p>改動前這裡有三個問題（security-audit P1-1／P1-3／P1-4），
     * 三者互相牽動因此一併處理：
     *
     * <p><b>P1-1 稽核查不出是誰核准的。</b>operatorId 一律取
     * {@code req.assignee()}，但前端主要簽核入口的 payload 只有 action 與
     * variables → TASK_APPROVE／TASK_RETURN／TASK_REJECT 的 operatorId 全是
     * null。現在改為：X-User-Id 標頭 → 任務目前的 assignee → body 的 assignee。
     *
     * <p><b>P1-3 守門回 HTTP 200。</b>有未完成加簽時回
     * {@code {"status":"error"}} 卻是 200，前端只看 axios 是否 throw →
     * 顯示「操作成功」並導航離開，而 comment 在 complete 之前就已寫入 →
     * DB 留下一筆「核准意見」而核准從未發生。改為 409 CONFLICT，
     * 與本檔其他錯誤路徑一致。
     *
     * <p><b>P1-4 未知 action 靜默改派。</b>改為顯式 switch：未知或缺少
     * action 一律 400。改派必須明確指定 {@code action=reassign}，
     * 不再是「有 assignee 就改派」—— 後者讓 typo 把核准變成改派且回報成功。
     */
    @PutMapping("/{id}")
    public Map<String, Object> updateTask(@PathVariable String id,
                                          @RequestBody TaskActionRequest req,
                                          @RequestHeader(value = "X-User-Id", required = false)
                                          String headerUserId) {
        Task task = taskService.createTaskQuery().taskId(id).singleResult();
        if (task == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "任務不存在: " + id);
        }
        String processInstanceId = task.getProcessInstanceId();
        String action = req.action();

        // 操作者：標頭優先（前端共用 axios instance 一律附上），
        // 退回任務現有的 assignee，最後才是 body 的 assignee。
        String operatorId = firstNonBlank(headerUserId,
                firstNonBlank(task.getAssignee(), req.assignee()));

        OperationType auditType;

        if (action == null || action.isBlank()) {
            // 改動前：沒有 action 但有 assignee 就直接改派 ——
            // 任何人都能用 {"assignee":"自己"} 無條件奪取他人任務。
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "缺少 action（可用：claim, complete, delegate, resolve, reassign）");
        }

        switch (action) {
            case "claim" -> {
                // claim(taskId, null) 的語意是「取消認領」，而且會跳過
                // 已認領檢查 → 空 body 可強制釋放他人任務。因此 assignee
                // 必須有值；acceptance 腳本只帶標頭不帶 body assignee，
                // 所以這裡用解析後的 operatorId。
                String claimant = firstNonBlank(headerUserId, req.assignee());
                if (claimant == null) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "claim 必須指定認領者（X-User-Id 標頭或 body 的 assignee）");
                }
                try {
                    taskService.claim(id, claimant);
                } catch (FlowableTaskAlreadyClaimedException e) {
                    // 改動前這個例外變成裸 500；語意上它是衝突。
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "任務已被他人認領", e);
                }
                operatorId = claimant;
                auditType = OperationType.TASK_CLAIM;
            }
            case "complete" -> {
                if (!taskService.getSubTasks(id).isEmpty()) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "有未完成的加簽子任務");
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
                auditType = task.getName() != null && task.getName().contains("補件")
                        ? OperationType.TASK_RESUBMIT : resolveCompleteAuditType(vars);
            }
            case "delegate" -> {
                if (req.delegateUser() == null || req.delegateUser().isBlank()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "delegate 必須指定 delegateUser");
                }
                taskService.delegateTask(id, req.delegateUser());
                auditType = OperationType.TASK_DELEGATE;
            }
            case "resolve" -> {
                taskService.resolveTask(id);
                auditType = OperationType.TASK_RESOLVE;
            }
            case "reassign" -> {
                // 改派現在必須明確指定 action。
                // ⚠️ 仍未檢查新 assignee 是否為該任務的候選人 —— 那需要
                // 身分與授權模型（R-01），見 security-audit P1-4。
                if (req.assignee() == null || req.assignee().isBlank()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "reassign 必須指定 assignee");
                }
                taskService.setAssignee(id, req.assignee());
                auditType = OperationType.TASK_REASSIGN;
            }
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "未知的 action: " + action
                            + "（可用：claim, complete, delegate, resolve, reassign）");
        }

        Map<String, Object> detail = new HashMap<>();
        detail.put("action", action);
        if (req.variables() != null) {
            req.variables().forEach(v -> detail.put(v.name(), v.value()));
        }

        // operationType 改傳 enum 的 name()：編譯期就綁定，
        // 不會再出現「字串不在 enum 裡 → valueOf 拋例外 → 稽核靜默遺失」。
        auditPublisher.publish(new AuditEvent(auditType.name(), operatorId,
                processInstanceId, id, detail));
        return Map.of("taskId", id, "status", "ok");
    }

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

    private OperationType resolveCompleteAuditType(Map<String, Object> vars) {
        if (Boolean.TRUE.equals(vars.get("rejected"))) return OperationType.TASK_REJECT;
        if (Boolean.FALSE.equals(vars.get("approved"))) return OperationType.TASK_RETURN;
        if (Boolean.TRUE.equals(vars.get("approved"))) return OperationType.TASK_APPROVE;
        return OperationType.TASK_APPROVE;
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
