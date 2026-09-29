package com.bpm.core.controller;

import org.springframework.transaction.annotation.Transactional;
import com.bpm.core.security.CallerId;
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
            java.util.Set.of("initiator", "effectiveInitiator", "onBehalfOf");

    private static boolean isProtectedVariable(String name) {
        if (name == null || name.isBlank()) return true;
        return name.startsWith("_") || PROTECTED_VARIABLES.contains(name);
    }

    private final TaskService taskService;
    private final RuntimeService runtimeService;
    private final RepositoryService repositoryService;
    private final AuditEventPublisher auditPublisher;
    private final com.bpm.core.security.ProcessAccessGuard accessGuard;
    private final com.bpm.core.service.CandidateGroupMembership groupMembership;

    public TaskController(TaskService taskService, RuntimeService runtimeService,
                          RepositoryService repositoryService,
                          AuditEventPublisher auditPublisher,
                          com.bpm.core.security.ProcessAccessGuard accessGuard,
                          com.bpm.core.service.CandidateGroupMembership groupMembership) {
        this.taskService = taskService;
        this.runtimeService = runtimeService;
        this.repositoryService = repositoryService;
        this.auditPublisher = auditPublisher;
        this.accessGuard = accessGuard;
        this.groupMembership = groupMembership;
    }

    /**
     * 我的待辦（指派給我 ＋ 我是候選人 ＋ 我所屬的候選群組），依 taskId 去重。
     *
     * <h2>改動前是什麼（#71）</h2>
     *
     * <p>{@code assignee}／{@code candidateUser}／{@code candidateGroups}
     * 三個參數全是 {@code required=false} 且<b>完全不檢查是否等於呼叫者</b>；
     * 三個都不帶時還有一條「return all」—— 也就是回傳全公司待辦，
     * 洩漏「誰在審什麼」。那是收件匣資訊，不是公開資訊。
     *
     * <h2>三個參數三種處理</h2>
     *
     * <ol>
     *   <li><b>{@code assignee}／{@code candidateUser}</b>：省略 → 用呼叫者；
     *       帶了別人的 id → 明確 400（與 #66 同一政策）。
     *       這兩個參數保留是為了讓呼叫端能明確表達意圖，不是為了授權 ——
     *       它們的結果永遠與「省略」相同。</li>
     *   <li><b>{@code candidateGroups}：帶了任何值 → 一律 400。</b>
     *       這是本方法最需要解釋的一條。assignee／candidateUser 至少還能
     *       解讀成「查自己」，而群組是一個<b>集合</b>的自稱 —— 等於要求
     *       伺服器相信「我屬於這個組織」。放行它的後果與 #66 的 initiator
     *       冒用完全同型，而且更隱蔽（拿到的結果看起來完全正常）。
     *       即使帶的值剛好等於呼叫端自己的群組也拒絕：呼叫端送出這個參數
     *       本身就表示它期待該值被採信。</li>
     * </ol>
     *
     * <h2>⚠️ 省略 candidateGroups 時<b>不能</b>完全不查群組</h2>
     *
     * <p>設計器的「發起人所屬單位」會產生
     * {@code flowable:candidateGroups="${orgService.getDeptId(initiator)}"}
     * —— 一個<b>沒有受理人</b>的任務（見 {@code InitialAssigneeResolver}
     * 對「只給候選群組」的說明）。若完全不查群組，這類任務會從<b>所有人</b>
     * 的收件匣消失，而且沒有任何錯誤訊息：案件就那樣卡住。
     * 「查不到就不查」正是 {@code DuplicateApprovalFilterTest} 記錄過的
     * 教訓（候選任務被靜默隱藏 → 案件卡死）。
     *
     * <p>所以省略時由 {@link com.bpm.core.service.CandidateGroupMembership}
     * 計算呼叫端實際所屬的群組（部門代碼 ∪ 權限碼 ∪ authorities）。
     *
     * <p>⚠️ 已部署的 {@code purchase-approval} 的 {@code financeReview} 用的是
     * {@code candidateUsers} 不是 candidateGroups，所以這段不影響它 ——
     * 由 {@code InvolvedInstancesTest.candidateUserFlowIsUnaffected} 證明。
     *
     * <h2>⚠️ 不加稽核</h2>
     *
     * <p>本端點只回傳呼叫者自己的待辦，沒有稽核旁路。被拒的參數是 400，
     * 且已由 {@link com.bpm.core.security.ProcessAccessGuard} 留下
     * {@code DATA_ACCESS {denied:true}}。
     */
    @GetMapping
    public List<Map<String, Object>> getTasks(
            @RequestParam(required = false) String assignee,
            @RequestParam(required = false) String candidateUser,
            @RequestParam(required = false) String candidateGroups,
            @CallerId String callerId) {

        String self = accessGuard.requireSelf(assignee, callerId, "assignee");
        String selfAsCandidate = accessGuard.requireSelf(candidateUser, callerId, "candidateUser");
        accessGuard.rejectCallerSuppliedGroups(candidateGroups, callerId);

        Map<String, Task> taskMap = new LinkedHashMap<>();

        taskService.createTaskQuery().taskAssignee(self).list()
                .forEach(t -> taskMap.put(t.getId(), t));
        taskService.createTaskQuery().taskCandidateUser(selfAsCandidate).list()
                .forEach(t -> taskMap.putIfAbsent(t.getId(), t));

        // 候選群組任務沒有 assignee，只能靠群組找到。空集合時<b>不能</b>查 ——
        // taskCandidateGroupIn(空清單) 會產生 IN ()，MSSQL 直接報錯。
        java.util.Set<String> myGroups = groupMembership.groupsOf(self);
        if (!myGroups.isEmpty()) {
            taskService.createTaskQuery()
                    .taskCandidateGroupIn(List.copyOf(myGroups))
                    .list().forEach(t -> taskMap.putIfAbsent(t.getId(), t));
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
     * null。現在改為：已認證的呼叫者（{@code @CallerId}）→ 任務目前的 assignee
     * → body 的 assignee。R-01 完成後第一順位不再是可偽造的標頭。
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
    @Transactional("primaryTransactionManager")
    public Map<String, Object> updateTask(@PathVariable String id,
                                          @RequestBody TaskActionRequest req,
                                          @CallerId
                                          String callerId) {
        Task task = taskService.createTaskQuery().taskId(id).singleResult();
        if (task == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "任務不存在: " + id);
        }
        String processInstanceId = task.getProcessInstanceId();
        String action = req.action();

        // 操作者：標頭優先（前端共用 axios instance 一律附上），
        // 退回任務現有的 assignee，最後才是 body 的 assignee。
        String operatorId = firstNonBlank(callerId,
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
                String claimant = firstNonBlank(callerId, req.assignee());
                if (claimant == null) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "claim 必須指定認領者（已認證的身分或 body 的 assignee）");
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
    @Transactional("primaryTransactionManager")
    public Map<String, String> addComment(@PathVariable String id,
                                          @RequestBody CommentRequest req,
                                          @CallerId
                                          String callerId) {
        Task task = taskService.createTaskQuery().taskId(id).singleResult();
        String processInstanceId = task != null ? task.getProcessInstanceId() : null;

        String author = firstNonBlank(callerId, req.userId());

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
