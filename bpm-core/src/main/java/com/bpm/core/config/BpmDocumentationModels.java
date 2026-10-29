package com.bpm.core.config;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;
import java.time.Instant;
import java.util.Date;

/** OpenAPI 專用的 Map 契約；不參與請求綁定或執行期序列化。 */
public final class BpmDocumentationModels {
    private BpmDocumentationModels() {}
    public record VariablesRequest(Map<String, Object> variables) {}
    public record CountersignRequest(@Schema(requiredMode=Schema.RequiredMode.REQUIRED) String countersignUserId, String message) {}
    public record OpinionRequest(String opinion) {}
    public record WorkerAcquireRequest(@Schema(requiredMode=Schema.RequiredMode.REQUIRED) String topic, String workerId,
            @Schema(minimum="1", maximum="86400", defaultValue="300") Long lockDurationSeconds) {}
    public record WorkerCompleteRequest(String workerId, Map<String, Object> variables) {}
    public record WorkerFailRequest(String workerId, String errorCode, String errorMessage) {}
    public record WorkerReleaseRequest(String workerId) {}
    public record CallbackRequest(@Schema(requiredMode=Schema.RequiredMode.REQUIRED) String processInstanceId,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String deliveryId, Map<String, Object> variables) {}
    public record ExternalStartRequest(@Schema(requiredMode=Schema.RequiredMode.REQUIRED) String processDefinitionKey,
            String businessKey, String onBehalfOf, String firstTaskAssignee,
            @Schema(description="字串陣列或逗號分隔字串", oneOf={String.class, String[].class}) Object firstTaskCandidateGroups,
            String callbackUrl, Map<String, Object> variables) {}
    public record ExternalCompleteRequest(Map<String, Object> variables) {}
    public record AttachmentUpload(@Schema(type="string", format="binary", requiredMode=Schema.RequiredMode.REQUIRED) String file,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String processInstanceId, String taskId, String uploadedBy) {}
    public record DeploymentUpload(@Schema(type="string", format="binary", requiredMode=Schema.RequiredMode.REQUIRED) String file, String name) {}
    public record SignalResult(String signalName, long waiting, int variablesApplied) {}
    public record ProcessDefinitionView(String id, String key, String name, int version, String deploymentId, boolean suspended, String startFormKey) {}
    public record HealthView(String service, String status) {}
    public record TaskResult(String taskId, String status, @Schema(description="僅保存表單資料時提供") String formDataId) {}
    public record UrgeResult(String status, List<String> recipients, long cooldownMinutes) {}
    public record StatusView(String status) {}
    public record CommentView(String id, String message, String userId, String time) {}
    public record SubtaskCreated(String taskId, String parentTaskId, String assignee, String name) {}
    public record SubtaskView(String taskId, String name, String assignee, String description, Date createTime) {}
    public record SubtaskCompleted(String subtaskId, boolean allSubtasksDone) {}
    public record AttachmentView(String id, String processInstanceId, String taskId, String fileName, Long fileSize,
            String contentType, String uploadedBy, Instant uploadedAt) {}
    public record ProcessStatus(String processInstanceId, String status) {}
    public record ProcessDiagram(String xml, List<String> activeIds) {}
    public record DeploymentResult(String deploymentId, String name, com.bpm.core.lint.BpmnLintService.LintResult lint) {}
    public record PermissionResult(boolean hasPermission) {}
    public record ManagerView(@Schema(description="無主管時不提供") String managerId) {}
    public record DepartmentView(String deptId) {}
    public record WorkerTasks(List<WorkerTask> tasks) {}
    public record WorkerTask(String jobId, String topic, String processInstanceId, String processDefinitionId,
            String elementId, String elementName, int retries, Date createTime, boolean locked, String lockOwner,
            Date lockExpirationTime, @Schema(description="只有acquire回應提供") Map<String, Object> variables) {}
    public record JobStatus(String jobId, String status) {}
    public record JobFailure(String jobId, String status, int retriesLeft) {}
    public record SystemCreated(String id, String systemId, String systemName, String apiKey, String callbackSecret) {}
    public record RotatedKey(String systemId, String apiKey, String previousKeyValidUntil) {}
    public record RotatedCallback(String systemId, String callbackSecret) {}
    public record CallbackResult(String status, @Schema(description="成功關聯時提供；重複回呼省略") String processInstanceId) {}
    public record ExternalStatus(String processInstanceId, String businessKey, String status, Date startedAt,
            Date completedAt, String result, List<CurrentTask> currentTasks) {}
    public record CurrentTask(String taskId, String taskName, String assignee, Date createdAt) {}
    public record FilterError(String error, int status) {}
}
