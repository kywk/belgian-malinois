package com.bpm.core.controller;

import com.bpm.core.service.InitialAssigneeResolver;
import org.springframework.transaction.annotation.Transactional;
import com.bpm.core.audit.AuditEventPublisher;
import com.bpm.core.dto.AuditEvent;
import com.bpm.core.dto.StartProcessRequest;
import com.bpm.core.service.FormVersionLocker;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/process-instances")
public class ProcessController {

    private final RuntimeService runtimeService;
    private final RepositoryService repositoryService;
    private final TaskService taskService;
    private final AuditEventPublisher auditPublisher;
    private final FormVersionLocker formVersionLocker;

    public ProcessController(RuntimeService runtimeService, RepositoryService repositoryService,
                             TaskService taskService, AuditEventPublisher auditPublisher,
                             FormVersionLocker formVersionLocker) {
        this.runtimeService = runtimeService;
        this.repositoryService = repositoryService;
        this.taskService = taskService;
        this.auditPublisher = auditPublisher;
        this.formVersionLocker = formVersionLocker;
    }

    @PostMapping
    @Transactional("primaryTransactionManager")
    public Map<String, Object> startProcess(@RequestBody StartProcessRequest req) {
        Map<String, Object> vars = req.variables() != null ? new HashMap<>(req.variables()) : new HashMap<>();
        if (req.initiator() != null) vars.put("initiator", req.initiator());

        ProcessInstance pi = runtimeService.startProcessInstanceByKey(
                req.processDefinitionKey(), req.businessKey(), vars);

        formVersionLocker.lockVersions(pi.getProcessInstanceId(), pi.getProcessDefinitionId());

        auditPublisher.publish(new AuditEvent("PROCESS_START", req.initiator(),
                pi.getProcessInstanceId(), null,
                Map.of("processDefinitionKey", req.processDefinitionKey(),
                        "businessKey", req.businessKey() != null ? req.businessKey() : "")));

        Map<String, Object> result = new HashMap<>();
        result.put("processInstanceId", pi.getProcessInstanceId());
        result.put("businessKey", req.businessKey() != null ? req.businessKey() : "");
        result.put("status", "running");

        // Include currentTask info
        //
        // ⚠️ 不可用 singleResult()：它在結果超過一筆時拋 FlowableException。
        // 平行閘道或 multi-instance 會簽會產生併發任務 —— 而這一行在流程
        // 已經成功啟動「之後」才執行，因此例外會讓使用者收到 500 並重送，
        // 造成重複案件（security-audit P1-2）。
        List<Task> currentTasks = taskService.createTaskQuery()
                .processInstanceId(pi.getProcessInstanceId())
                .orderByTaskCreateTime().asc().list();
        if (!currentTasks.isEmpty()) {
            Task currentTask = currentTasks.get(0);
            result.put("currentTask", Map.of(
                    "taskId", currentTask.getId(),
                    "taskName", currentTask.getName() != null ? currentTask.getName() : "",
                    "assignee", currentTask.getAssignee() != null ? currentTask.getAssignee() : ""));
            result.put("currentTaskCount", currentTasks.size());
        }
        return result;
    }

    @GetMapping
    public List<Map<String, Object>> getProcessInstances(@RequestParam(required = false) String initiator) {
        var query = runtimeService.createProcessInstanceQuery();
        // 代員工發起的案件（R-20）：initiator 是 system:<id>，員工記在 onBehalfOf。
        // 兩者都要比對，否則代發的單不會出現在那位員工的「我的申請」。
        // 回應以 onBehalf=true 標示，讓前端能顯示「由外部系統代為提出」——
        // 使用者看到一張自己沒送過的單，必須知道它是怎麼來的。
        java.util.Set<String> onBehalf = java.util.Set.of();
        if (initiator != null) {
            query.or().variableValueEquals("initiator", initiator)
                    .variableValueEquals(InitialAssigneeResolver.ON_BEHALF_OF_VAR, initiator).endOr();
            onBehalf = runtimeService.createProcessInstanceQuery()
                    .variableValueEquals(InitialAssigneeResolver.ON_BEHALF_OF_VAR, initiator).list()
                    .stream().map(ProcessInstance::getProcessInstanceId).collect(java.util.stream.Collectors.toSet());
        }
        final java.util.Set<String> delegated = onBehalf;
        return query.orderByProcessInstanceId().desc().list().stream()
                .map(pi -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("onBehalf", delegated.contains(pi.getProcessInstanceId()));
                    m.put("processInstanceId", pi.getProcessInstanceId());
                    m.put("processDefinitionKey", pi.getProcessDefinitionKey());
                    m.put("businessKey", pi.getBusinessKey() != null ? pi.getBusinessKey() : "");
                    m.put("startTime", pi.getStartTime());
                    m.put("status", "running");
                    // currentTask
                    //
                    // ⚠️ 這一行在 GET /api/process-instances 的 stream 之中。
                    // 用 singleResult() 的話，只要系統中「任何一個」案件有
                    // 併發任務，這個端點就對「所有使用者」整體 500 ——
                    // 而業務人員在設計器畫一個平行閘道就能觸發。
                    List<Task> tasks = taskService.createTaskQuery()
                            .processInstanceId(pi.getProcessInstanceId())
                            .orderByTaskCreateTime().asc().list();
                    if (!tasks.isEmpty()) {
                        Task task = tasks.get(0);
                        m.put("currentTask", Map.of(
                                "taskName", task.getName() != null ? task.getName() : "",
                                "assignee", task.getAssignee() != null ? task.getAssignee() : ""));
                        // 併發時只顯示其中一個會讓使用者以為案件只等一個人；
                        // 把數量一併帶出來，讓前端至少有能力呈現「還有其他關卡」。
                        m.put("currentTaskCount", tasks.size());
                    }
                    return m;
                }).toList();
    }

    @GetMapping("/{id}/variables")
    public Map<String, Object> getVariables(@PathVariable String id) {
        try { return runtimeService.getVariables(id); }
        catch (Exception e) { return Map.of(); }
    }

    @GetMapping("/{id}/bpmn-xml")
    public Map<String, Object> getBpmnXml(@PathVariable String id) throws Exception {
        ProcessInstance pi = runtimeService.createProcessInstanceQuery()
                .processInstanceId(id).singleResult();
        if (pi == null) return Map.of("xml", "", "activeIds", List.of());

        var pd = repositoryService.getProcessDefinition(pi.getProcessDefinitionId());
        org.flowable.bpmn.model.BpmnModel model = repositoryService.getBpmnModel(pi.getProcessDefinitionId());

        // Auto-layout if no DI (hand-written BPMN without diagram section)
        if (model.getLocationMap().isEmpty()) {
            new org.flowable.bpmn.BpmnAutoLayout(model).execute();
        }

        byte[] xmlBytes = new org.flowable.bpmn.converter.BpmnXMLConverter().convertToXML(model);
        List<String> activeIds = runtimeService.getActiveActivityIds(id);
        return Map.of("xml", new String(xmlBytes), "activeIds", activeIds);
    }
}
