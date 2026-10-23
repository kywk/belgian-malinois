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
import java.util.LinkedHashMap;
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
    private final FormSubmissionService submissions;

    public UniformOptionsController(TaskService tasks, RepositoryService repository,
            ProcessAccessGuard access, FormAccessResolver profiles, FormContextResolver context,
            FormEnvelopeService envelopes, OptionsProviderRegistry providers, FormSubmissionService submissions) {
        this.tasks = tasks;
        this.repository = repository;
        this.access = access;
        this.profiles = profiles;
        this.context = context;
        this.envelopes = envelopes;
        this.providers = providers;
        this.submissions = submissions;
    }

    public record ApplicationContext(String processDefinitionId, String formDefinitionId,
                                     String formKey, Integer formVersion) {}
    public record StartOptionsRequest(String fieldId, ApplicationContext applicationContext, Map<String, Object> data) {
        public StartOptionsRequest(String fieldId, ApplicationContext applicationContext) { this(fieldId, applicationContext, null); }
    }
    public record TaskOptionsRequest(String fieldId, Map<String, Object> data) {
        public TaskOptionsRequest(String fieldId) { this(fieldId, null); }
    }

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
        Map<String, Object> profile = profiles.profileForStartEvent(process.getId());
        Map<String, Object> candidate = submissions.optionsCandidate(definition, profile, request.data(), Map.of());
        return list(definition, profile, candidate, request.fieldId(), caller);
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
        if (request == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "fieldId 為必填");
        Map<String, Object> profile = profiles.profileForTask(task.getProcessDefinitionId(), task.getTaskDefinitionKey());
        Map<String, Object> candidate = submissions.optionsCandidate(definition, profile, request.data(),
                context.storedDataOf(context.latestRow(task.getProcessInstanceId())));
        return list(definition, profile, candidate, request.fieldId(), caller);
    }

    private Map<String, Object> list(FormDefinition definition, Map<String, Object> profile,
            Map<String, Object> candidate, String id, String caller) {
        if (id == null || id.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "fieldId 為必填");
        // 投影使用同一可信 candidate 與 behaviorRegistry，保留 SDK 的完整 hidden closure。
        Map<String, Object> envelope = envelopes.envelope(definition, profile, candidate);
        if (!"fill".equals(envelope.get("mode")) || !(envelope.get("schema") instanceof Map<?, ?> schema)
                || !(envelope.get("meta") instanceof Map<?, ?> meta)
                || !(meta.get("states") instanceof Map<?, ?> rawStates)) throw fieldMissing();
        Map<String, String> states = new LinkedHashMap<>();
        rawStates.forEach((key, value) -> states.put((String) key, (String) value));
        Map<String, Object> field = eligible(schema.get("root"), id, states);
        if (field == null) throw fieldMissing();
        return Map.of("options", providers.list(caller, OptionsProviderRegistry.params(field, candidate, states)));
    }

    private static Map<String, Object> eligible(Object raw, String id, Map<String, String> states) {
        if (raw instanceof List<?> nodes) {
            for (Object node : nodes) {
                Map<String, Object> found = eligible(node, id, states);
                if (found != null) return found;
            }
            return null;
        }
        if (!(raw instanceof Map<?, ?> node)) return null;
        if (id.equals(node.get("id")) && "editable".equals(states.get(node.get("key")))
                && List.of("select", "radio", "checkbox").contains(node.get("type"))
                && node.get("optionsSource") instanceof Map<?, ?> source
                && OptionsProviderRegistry.PROVIDER.equals(source.get("provider"))) {
            Map<String, Object> field = new LinkedHashMap<>();
            node.forEach((key, value) -> field.put((String) key, value));
            return field;
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
