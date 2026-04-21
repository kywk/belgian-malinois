package com.bpm.core.controller;

import org.flowable.bpmn.BpmnAutoLayout;
import org.flowable.bpmn.converter.BpmnXMLConverter;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.repository.ProcessDefinition;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/process-definitions")
public class ProcessDefinitionController {

    private final RepositoryService repositoryService;

    public ProcessDefinitionController(RepositoryService repositoryService) {
        this.repositoryService = repositoryService;
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(defaultValue = "false") boolean latestVersion) {
        var query = repositoryService.createProcessDefinitionQuery().orderByProcessDefinitionVersion().desc();
        if (latestVersion) query.latestVersion();
        return query.list().stream().map(this::toMap).toList();
    }

    @GetMapping(value = "/{id}/resourcedata", produces = MediaType.TEXT_XML_VALUE)
    public String getXml(@PathVariable String id) throws Exception {
        ProcessDefinition pd = repositoryService.createProcessDefinitionQuery()
                .processDefinitionId(id).singleResult();
        if (pd == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        BpmnModel model = repositoryService.getBpmnModel(id);
        if (model.getLocationMap().isEmpty()) {
            new BpmnAutoLayout(model).execute();
        }
        return new String(new BpmnXMLConverter().convertToXML(model));
    }

    private Map<String, Object> toMap(ProcessDefinition pd) {
        return Map.of(
            "id", pd.getId(),
            "key", pd.getKey(),
            "name", pd.getName() != null ? pd.getName() : "",
            "version", pd.getVersion(),
            "deploymentId", pd.getDeploymentId(),
            "suspended", pd.isSuspended()
        );
    }
}
