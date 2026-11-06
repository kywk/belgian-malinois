package com.bpm.core.staff;

import com.bpm.core.dto.TaskActionRequest;
import org.flowable.engine.RepositoryService;
import org.flowable.task.api.Task;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "bpm.staff.enabled", havingValue = "true")
public class StaffTaskAuthorization {
    private final StaffIntegrationProperties properties;
    private final StaffAuthzClient authz;
    private final StaffDirectoryClient directory;
    private final RepositoryService repository;
    private final org.flowable.engine.TaskService tasks;
    public StaffTaskAuthorization(StaffIntegrationProperties properties, StaffAuthzClient authz,
            StaffDirectoryClient directory, RepositoryService repository, org.flowable.engine.TaskService tasks) {
        this.properties = properties; this.authz = authz; this.directory = directory; this.repository = repository; this.tasks = tasks;
    }
    /** Called after holder guard and before any task mutation, including resolve/reassign. */
    public void require(Task task, TaskActionRequest request) {
        var staff = StaffAuthentication.current();
        if (staff == null) throw StaffFailure.denied();
        Task workflowTask = task;
        var visited = new java.util.HashSet<String>();
        while (workflowTask != null && workflowTask.getProcessDefinitionId() == null
                && workflowTask.getParentTaskId() != null && visited.add(workflowTask.getId()) && visited.size() <= 16) {
            workflowTask = tasks.createTaskQuery().taskId(workflowTask.getParentTaskId()).singleResult();
        }
        if (workflowTask == null || workflowTask.getProcessDefinitionId() == null)
            throw new StaffFailure("BPM_INTEGRATION_NOT_CONFIGURED", 503, "無法確認任務所屬流程的核決權限");
        var definition = repository.getProcessDefinition(workflowTask.getProcessDefinitionId());
        String permission = properties.approvalPermissions().get(definition.getKey());
        if (permission == null || permission.isBlank())
            throw new StaffFailure("BPM_INTEGRATION_NOT_CONFIGURED", 503, "此流程尚未設定核決權限對照");
        // Fresh checks at the mutation boundary: do not authorize using a previous page/session response.
        var token = staff.getToken();
        var identity = directory.resolve(token.getIssuer().toString(), token.getSubject());
        // The holder guard used the request snapshot; a changed mapping needs a fresh holder decision.
        if (!identity.userId().equals(staff.getName()) || !identity.deptId().equals(staff.staff().deptId())
                || !identity.subjectIds().equals(staff.staff().subjectIds())) throw StaffFailure.denied();
        if (!authz.allowed(identity, StaffPermissions.CONSOLE) || !authz.allowed(identity, StaffPermissions.ACT) || !authz.allowed(identity, permission))
            throw StaffFailure.denied();
        if (request != null && request.assignee() != null) directory.byUserId(request.assignee());
        if (request != null && request.delegateUser() != null) {
            directory.byUserId(request.delegateUser());
            if ("delegate".equals(request.action())) directory.requireDelegation(
                    task.getOwner() == null ? task.getAssignee() : task.getOwner(), request.delegateUser());
        }
        if (task.getDelegationState() == org.flowable.task.api.DelegationState.PENDING
                && !identity.userId().equals(task.getAssignee())
                && !("resolve".equals(request == null ? null : request.action()) && identity.userId().equals(task.getOwner())))
            directory.requireDelegation(task.getOwner(), task.getAssignee());
        identity.validated(java.time.Instant.now());
    }
}
