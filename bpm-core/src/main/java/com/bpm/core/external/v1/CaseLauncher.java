package com.bpm.core.external.v1;

import static com.bpm.core.external.v1.CaseError.*;

import com.bpm.core.audit.AuditEventPublisher;
import com.bpm.core.dto.AuditEvent;
import com.bpm.core.external.ExternalActorIdentity;
import com.bpm.core.form.model.FormData;
import com.bpm.core.form.service.FormService;
import com.bpm.core.repository.ProcessVariableSpecRepository;
import com.bpm.core.service.FormVersionLocker;
import com.bpm.core.service.InitialAssigneeResolver;
import com.bpm.core.uniform.ApplicationFormBinding;
import com.bpm.core.uniform.FormAccessResolver;
import com.bpm.core.uniform.FormSubmissionService;
import java.util.*;
import org.flowable.engine.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

/** Validate without side effects, then launch under the caller's primary transaction and reservation lock. */
@Service
class CaseLauncher {
    private final RepositoryService definitions;
    private final RuntimeService runtime;
    private final TaskService tasks;
    private final FormService forms;
    private final FormVersionLocker versions;
    private final FormSubmissionService submissions;
    private final FormAccessResolver access;
    private final ProcessVariableSpecRepository specs;
    private final AuditEventPublisher audit;

    CaseLauncher(RepositoryService definitions, RuntimeService runtime, TaskService tasks, FormService forms,
            FormVersionLocker versions, FormSubmissionService submissions, FormAccessResolver access,
            ProcessVariableSpecRepository specs, AuditEventPublisher audit) {
        this.definitions = definitions;
        this.runtime = runtime;
        this.tasks = tasks;
        this.forms = forms;
        this.versions = versions;
        this.submissions = submissions;
        this.access = access;
        this.specs = specs;
        this.audit = audit;
    }
    record Prepared(Map<String, Object> variables, FormData form) {}

    Prepared prepare(CaseStore.Binding binding, CaseCommand c) {
        if (!binding.enabled() || !binding.definitionId().equals(c.processDefinitionId()))
            throw DEFINITION_VERSION_MISMATCH.fail();
        var definition =
                definitions.createProcessDefinitionQuery().processDefinitionId(binding.definitionId()).singleResult();
        if (definition == null || definition.isSuspended() || !definition.getKey().equals(binding.definitionKey()))
            throw DEFINITION_VERSION_MISMATCH.fail();

        Map<String, Object> vars = new HashMap<>(c.variables());
        checkVariables(binding.definitionKey(), vars, c.formData());
        try {
            return new Prepared(vars, prepareForm(binding, c.formData(), vars));
        } catch (ResponseStatusException ex) {
            throw switch (ex.getStatusCode().value()) {
                case 400, 422 -> FORM_VALIDATION_FAILED.fail();
                case 409 -> FORM_VERSION_MISMATCH.fail();
                case 503 -> UPSTREAM_UNAVAILABLE.fail();
                default -> INTEGRATION_NOT_CONFIGURED.fail();
            };
        }
    }

    String launch(CaseStore.Delivery row, CaseStore.Binding binding, CaseCommand c, Prepared prepared,
            String reviewer, String trace) {
        var vars = prepared.variables();
        var data = prepared.form();
        String initiator = ExternalActorIdentity.of(row.systemId());
        vars.put("initiator", initiator);
        vars.put("_externalSystemId", row.systemId());
        vars.put(InitialAssigneeResolver.FIRST_ASSIGNEE_VAR, reviewer);
        String id = runtime.createProcessInstanceBuilder()
                            .predefineProcessInstanceId(row.attemptId())
                            .processDefinitionId(binding.definitionId())
                            .businessKey(row.businessKey())
                            .variables(vars)
                            .start()
                            .getId();
        var first = tasks.createTaskQuery().processInstanceId(id).list();
        if (first.isEmpty() || first.stream().anyMatch(task -> !reviewer.equals(task.getAssignee())))
            throw ROUTING_UNAVAILABLE.fail();
        audit.publish(new AuditEvent("EXTERNAL_API_CALL", initiator, id, null,
                Map.of("action", "start_process", "requestId", c.requestId(), "businessKey", row.businessKey(),
                        "actorType", c.actor().type(), "actorIssuer", c.actor().issuer(),
                        "TALENT".equals(c.actor().type()) ? "subject" : "agentCode",
                        "TALENT".equals(c.actor().type()) ? c.actor().subject() : c.actor().agentCode(), "traceId", trace)));
        if (data != null) {
            data.setProcessInstanceId(id);
            data.setSubmittedBy(initiator);
            var saved = forms.submitData(data);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status == STATUS_ROLLED_BACK)
                        forms.deleteDataById(saved.getId());
                }
            });
        }
        return id;
    }
    private FormData prepareForm(CaseStore.Binding b, CaseCommand.Form input, Map<String, Object> vars) {
        String main = ApplicationFormBinding.formKey(access.startEventOf(b.definitionId()));
        if (input == null) {
            if (main != null || b.formId() != null)
                throw FORM_VERSION_MISMATCH.fail();
            vars.put(FormVersionLocker.VERSIONS_VARIABLE, versions.prepareTaskVersions(b.definitionId()));
            return null;
        }
        if (main == null || !input.formDefinitionId().equals(b.formId()) || b.formVersion() == null
                || b.formVersion().longValue() != input.formVersion())
            throw FORM_VERSION_MISMATCH.fail();
        var form = forms.findDefinitionByIdOrKey(b.formId());
        if (form == null || !b.formId().equals(form.getId()) || !main.equals(form.getFormKey())
                || !b.formVersion().equals(form.getVersion()) || !"published".equals(form.getStatus()))
            throw FORM_VERSION_MISMATCH.fail();
        if (!vars.isEmpty() && !Collections.disjoint(vars.keySet(), submissions.declaredFieldKeys(form))) {
            throw VALIDATION_ERROR.fail();
        }
        var fields = new LinkedHashMap<>(input.dataJson());
        vars.put(FormVersionLocker.VERSIONS_VARIABLE,
                new HashMap<>(versions.prepareVersions(b.definitionId(), form)));
        var result = submissions.validateWithSnapshots(
                form, access.profileForStartEvent(b.definitionId()), fields, Map.of(), null);
        if (!result.errors().isEmpty())
            throw FORM_VALIDATION_FAILED.fail();
        fields.forEach((k, v) -> {
            if (!k.startsWith("$"))
                vars.put(k, v);
        });
        var data = new FormData();
        data.setFormDefinitionId(form.getId());
        data.setDataJson(submissions.dataJson(fields));
        data.setOptionLabelsJson(result.optionLabelsJson());
        return data;
    }
    private void checkVariables(String key, Map<String, Object> vars, CaseCommand.Form form) {
        var unknown = new HashSet<>(vars.keySet());
        for (var spec : specs.findByProcessDefinitionKeyOrderByVariableName(key)) {
            String name = spec.getVariableName();
            unknown.remove(name);
            Object value = vars.get(name);
            if (value == null && form != null)
                value = form.dataJson().get(name);
            if (Boolean.TRUE.equals(spec.getRequired())
                    && (value == null || value instanceof String s && s.isBlank()))
                throw VALIDATION_ERROR.fail();
            if (vars.containsKey(name) && value != null) {
                boolean valid = switch (spec.getVariableType()) {
                    case "string", "date" -> value instanceof String;
                    case "number" -> value instanceof Number;
                    case "boolean" -> value instanceof Boolean;
                    default -> false;
                };
                if (!valid)
                    throw VALIDATION_ERROR.fail();
            }
        }
        if (!unknown.isEmpty())
            throw VALIDATION_ERROR.fail();
    }
}
