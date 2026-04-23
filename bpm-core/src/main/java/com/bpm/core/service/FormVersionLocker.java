package com.bpm.core.service;

import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.StartEvent;
import org.flowable.bpmn.model.UserTask;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

/**
 * Scans BPMN for all formKeys and locks their published versions
 * into a process variable (_formVersions) at process start time.
 */
@Service
public class FormVersionLocker {

    private final RepositoryService repositoryService;
    private final RuntimeService runtimeService;
    private final RestClient formClient;

    public FormVersionLocker(RepositoryService repositoryService, RuntimeService runtimeService,
                             @Value("${bpm.form-service-url:http://localhost:8081}") String formServiceUrl) {
        this.repositoryService = repositoryService;
        this.runtimeService = runtimeService;
        this.formClient = RestClient.builder().baseUrl(formServiceUrl).build();
    }

    /**
     * After process start, resolve all formKey versions and store as process variable.
     */
    public void lockVersions(String processInstanceId, String processDefinitionId) {
        BpmnModel model = repositoryService.getBpmnModel(processDefinitionId);
        Map<String, Integer> versions = new HashMap<>();

        for (FlowElement el : model.getMainProcess().getFlowElements()) {
            String formKey = null;
            if (el instanceof UserTask ut) formKey = ut.getFormKey();
            else if (el instanceof StartEvent se) formKey = se.getFormKey();

            if (formKey != null && !formKey.isBlank() && !formKey.startsWith("external:")
                    && !formKey.contains("${") && !versions.containsKey(formKey)) {
                Integer version = resolveVersion(formKey);
                if (version != null) versions.put(formKey, version);
            }
        }

        if (!versions.isEmpty()) {
            runtimeService.setVariable(processInstanceId, "_formVersions", (Serializable) versions);
        }
    }

    private Integer resolveVersion(String formKey) {
        try {
            var resp = formClient.get().uri("/api/forms/{formKey}", formKey)
                    .retrieve().body(Map.class);
            return resp != null ? (Integer) resp.get("version") : null;
        } catch (Exception e) {
            return null;
        }
    }
}
