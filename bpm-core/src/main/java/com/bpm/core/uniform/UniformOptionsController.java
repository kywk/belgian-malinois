package com.bpm.core.uniform;

import com.bpm.core.form.model.FormDefinition;
import com.bpm.core.security.CallerId;
import com.bpm.core.security.ProcessAccessGuard;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.TaskService;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.task.api.Task;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/** 選項讀取與表單共用可信綁定、版本及案件讀取授權。 */
@RestController
public class UniformOptionsController {
    private final TaskService tasks;
    private final RepositoryService repository;
    private final ProcessAccessGuard access;
    private final FormAccessResolver profiles;
    private final FormContextResolver context;
    private final FormEnvelopeService envelopes;
    private final OptionsProviderRegistry providers;

    public UniformOptionsController(TaskService tasks, RepositoryService repository,
            ProcessAccessGuard access, FormAccessResolver profiles, FormContextResolver context,
            FormEnvelopeService envelopes, OptionsProviderRegistry providers) {
        this.tasks = tasks;
        this.repository = repository;
        this.access = access;
        this.profiles = profiles;
        this.context = context;
        this.envelopes = envelopes;
        this.providers = providers;
    }

    public record ApplicationContext(String processDefinitionId, String formDefinitionId,
                                     String formKey, Integer formVersion) {}
    public record StartOptionsRequest(String fieldId, ApplicationContext applicationContext) {}
    public record TaskOptionsRequest(String fieldId) {}

    @GetMapping("/api/forms/option-providers")
    public List<Map<String, Object>> catalog() { return providers.catalog(); }

    @PostMapping("/api/process-definitions/{key}/start-form/options")
    public Map<String, Object> startOptions(@PathVariable String key,
            @RequestBody StartOptionsRequest request, @CallerId String caller) {
        ProcessDefinition process = repository.createProcessDefinitionQuery()
                .processDefinitionKey(key).latestVersion().singleResult();
        if (process == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "流程定義不存在");
        if (process.isSuspended()) throw changed();
        if (request == null || request.applicationContext() == null
                || !process.getId().equals(request.applicationContext().processDefinitionId())) throw changed();
        String formKey = ApplicationFormBinding.formKey(profiles.startEventOf(process.getId()));
        if (formKey == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "此流程沒有起始表單");
        FormDefinition definition;
        try { definition = context.definitionFor(formKey, null); }
        catch (ResponseStatusException failure) {
            if (failure.getStatusCode().value() != 404) throw failure;
            throw changed();
        }
        ApplicationContext expected = new ApplicationContext(process.getId(), definition.getId(),
                definition.getFormKey(), definition.getVersion());
        if (!expected.equals(request.applicationContext())
                || !"published".equals(definition.getStatus())) throw changed();
        ApplicationFormBinding.requireCompatible(repository.getBpmnModel(process.getId()), definition);
        Map<String, Object> envelope = envelopes.envelope(definition,
                profiles.profileForStartEvent(process.getId()), Map.of());
        requireField(envelope, request.fieldId());
        return Map.of("options", providers.list(caller));
    }

    @PostMapping("/api/tasks/{taskId}/form/options")
    public Map<String, Object> taskOptions(@PathVariable String taskId,
            @RequestBody TaskOptionsRequest request, @CallerId String caller) {
        Task task = tasks.createTaskQuery().taskId(taskId).singleResult();
        if (task == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "任務不存在");
        access.requireReadAccess(task.getProcessInstanceId(), caller);
        String key = task.getFormKey();
        if (key == null || key.isBlank() || key.startsWith("external:")) throw fieldMissing();
        FormDefinition definition = context.definitionFor(key, task.getProcessInstanceId());
        Map<String, Object> envelope = envelopes.envelope(definition,
                profiles.profileForTask(task.getProcessDefinitionId(), task.getTaskDefinitionKey()),
                context.storedDataOf(context.latestRow(task.getProcessInstanceId())));
        requireField(envelope, request == null ? null : request.fieldId());
        return Map.of("options", providers.list(caller));
    }

    private static void requireField(Map<String, Object> envelope, String id) {
        if (id == null || id.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "fieldId 為必填");
        // 只檢查 SDK 已裁切的公開 schema，不能用原始 DSL 查出 hidden 欄位。
        if (!"fill".equals(envelope.get("mode")) || !(envelope.get("schema") instanceof Map<?, ?> schema)
                || !(envelope.get("meta") instanceof Map<?, ?> meta)
                || !(meta.get("states") instanceof Map<?, ?> states)
                || !eligible(schema.get("root"), id, states)) throw fieldMissing();
    }

    private static boolean eligible(Object raw, String id, Map<?, ?> states) {
        if (raw instanceof List<?> nodes) return nodes.stream().anyMatch(node -> eligible(node, id, states));
        if (!(raw instanceof Map<?, ?> node)) return false;
        if (id.equals(node.get("id"))) {
            return "editable".equals(states.get(node.get("key")))
                    && ("select".equals(node.get("type")) || "radio".equals(node.get("type")))
                    && node.get("optionsSource") instanceof Map<?, ?> source
                    && OptionsProviderRegistry.PROVIDER.equals(source.get("provider"));
        }
        return eligible(node.get("children"), id, states);
    }

    private static ResponseStatusException changed() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "流程或申請表已改版，請保留填寫內容並重新載入比對");
    }

    private static ResponseStatusException fieldMissing() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "此欄位沒有可載入的動態選項");
    }
}
