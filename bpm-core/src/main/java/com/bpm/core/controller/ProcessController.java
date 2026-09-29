package com.bpm.core.controller;

import com.bpm.core.service.InitialAssigneeResolver;
import org.springframework.transaction.annotation.Transactional;
import com.bpm.core.audit.AuditEventPublisher;
import com.bpm.core.dto.AuditEvent;
import com.bpm.core.dto.StartProcessRequest;
import com.bpm.core.security.CallerId;
import com.bpm.core.service.FormVersionLocker;
import org.flowable.common.engine.api.FlowableObjectNotFoundException;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

@RestController
@RequestMapping("/api/process-instances")
public class ProcessController {

    /**
     * 不可由呼叫端在<b>啟動流程</b>時指定的流程變數。
     *
     * <p>與 {@code TaskController.PROTECTED_VARIABLES} 同一份清單、同一個理由 ——
     * 見該處的註解。啟動路徑比完成路徑更危險，因為它一次決定了：
     *
     * <ul>
     *   <li><b>簽核人</b>：兩支已部署的 BPMN 都用
     *       {@code ${assigneeResolver.resolve(execution)}}（內部讀
     *       {@code initiator}）決定第一關受理人，於是冒用 initiator
     *       等於把單子送到共犯的主管手上。</li>
     *   <li><b>補件任務的 assignee</b>：{@code ${initiator}}。</li>
     *   <li><b>通知信的申請人</b>。</li>
     *   <li><b>稽核的 operatorId</b> —— 這一項最惡劣：它會被 hash chain
     *       永久固定成一筆「可信」的紀錄，之後再談竊取者都無法從紀錄裡分辨。</li>
     * </ul>
     *
     * <p>為什麼連 {@code _} 前綴的一律拒絕：那是伺服器的內部狀態
     * （{@code _formVersions}、{@code _externalSystemId}、{@code _callbackUrl}）。
     * 特別是 {@code _externalSystemId} —— 改動前呼叫端可以在 body 的
     * {@code variables} 裡夾帶它，把案件偽裝成某個外部系統擁有，
     * <b>繞過 R-09 的精確比對</b>（該比對本來是可信的，前提是這個值
     * 只由 {@code ExternalApiController} 寫入）。
     *
     * <p>{@code onBehalfOf} 同理：它是 R-20 為外部系統代發而設的
     * <b>系統授權</b>（{@code allowOnBehalfOf}）。允許一般登入者透過
     * {@code variables} 夾帶它，等於把一條需要管理員授權的權限
     * 變成任何登入者都能行使。
     *
     * <p>⚠️ 這仍然是<b>保護名單</b>（deny-list）而非完整白名單。真正的白名單
     * 應該來自 formKey 的 schema（spec §8.5：欄位 id == 變數名），
     * 但那需要在此查詢 form-service 並處理它不可用時的行為，範圍更大。
     * 這裡沿用與 {@code TaskController} 相同的取捨：擋住所有「改寫引擎
     * 與身分語意」的變數，一般業務欄位照常放行。兩處必須維持同一份清單 ——
     * 啟動路徑放行的、完成路徑擋掉的組合，會讓一份表單資料在流程的兩個
     * 時點有不同意義。
     */
    private static final java.util.Set<String> PROTECTED_VARIABLES =
            java.util.Set.of("initiator", "effectiveInitiator",
                    InitialAssigneeResolver.ON_BEHALF_OF_VAR);

    private static boolean isProtectedVariable(String name) {
        if (name == null || name.isBlank()) return true;
        return name.startsWith("_") || PROTECTED_VARIABLES.contains(name);
    }

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

    /**
     * 啟動流程（#66：initiator 一律由已認證的身分決定）。
     *
     * <h2>改動前</h2>
     *
     * <p>{@code req.initiator()} 直接被寫進流程變數，也直接作為稽核的
     * operatorId。也就是說<b>登入的使用者可以用任何人的名義送單</b>，
     * 而那張單會被派給那位「受害者」的主管、通知信上也會寫著他的名字、
     * 稽核裡的操作人也是他 —— 而且被 hash chain 固定成不可否認的紀錄。
     *
     * <h2>三條不可協商的規則</h2>
     *
     * <ol>
     *   <li><b>伺服器是 initiator 的唯一來源。</b>不接受呼叫端指定，也不
     *       「先接受再覆寫」—— 後者會讓呼叫端以為它指定的值生效了。</li>
     *   <li><b>明確 400，不靜默忽略。</b>靜默丟棄會讓呼叫端誤以為案件
     *       是以某人的名義發起，而實際不是。外部 API 那條路
     *       （{@code ExternalApiController}，R-20）用的是同一個語意。</li>
     *   <li><b>稽核的 operatorId 與流程變數的 initiator 必須同源。</b>
     *       兩者不一致時，稽核會記下真正做事的人，而流程路由記下別人 ——
     *       那正是最難偵測的組合。</li>
     * </ol>
     *
     * <h2>為什麼在交易內先檢查再啟動，而不是讓 Flowable 丟例外</h2>
     *
     * <p>{@code startProcessInstanceByKey} 對不存在的 key 會丟
     * {@code FlowableObjectNotFoundException} → 500。使用者看到 500 的
     * 直覺是「再按一次」，於是<b>重送造成重複案件</b>。這裡先查
     * {@code RepositoryService}（該服務本來就注入了，只是從未用於啟動路徑），
     * 缺 key 立刻 404。
     *
     * <p>仍然保留對 {@code FlowableObjectNotFoundException} 的轉譯：
     * 預先檢查與實際啟動之間定義<b>真的可能</b>被刪（管理員停用流程的
     * 動作與使用者的啟動請求同時發生）。那是預先檢查無法消除的
     * 競爭窗口，沒有這一層它就會變回 500。
     */
    @PostMapping
    @Transactional("primaryTransactionManager")
    public Map<String, Object> startProcess(@RequestBody StartProcessRequest req,
                                            @CallerId String callerId) {
        // ⚠️ 為什麼 callerId 為 null 要回 401，而不是 fallback 到任何標頭：
        // CallerIdArgumentResolver 的類別註解已說明 —— 退回讀標頭會讓整套
        // 認證變成裝飾：任何一條沒被授權規則涵蓋的路徑，身分就退回可偽造的
        // 標頭，而且完全沒有痕跡。而啟動流程是全 repo 唯一一個<b>產生</b>
        // 身分語意卻沒有用 @CallerId 的寫入路徑，所以這裡不能是例外。
        // 正常情況下 SecurityConfig 的 authenticated() 擋在前頭；
        // 這道檢查是為了「授權矩陣日後放寬」時不會靜默退化成可偽造的身分。
        if (callerId == null || callerId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "無法確認發起人身分，請先登入");
        }

        String key = req.processDefinitionKey();
        if (key == null || key.isBlank()) {
            // 缺 key 是請求本身不完整（400），不是「找不到東西」（404）。
            // Flowable 對 null key 丟的例外語意不明，必須自己擋。
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少 processDefinitionKey");
        }

        // body 帶了 initiator → 明確拒絕。訊息要說清楚它是由誰決定的，
        // 否則呼叫端只會看到「400」而不知道要改哪裡。
        if (req.initiator() != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "initiator 由登入身分（" + callerId + "）決定，不可由呼叫端指定。"
                            + "請移除 body 的 initiator 欄位。");
        }

        Map<String, Object> vars = req.variables() != null ? new HashMap<>(req.variables()) : new HashMap<>();
        // variables 是自由 map —— 改動前呼叫端可以在這裡夾帶 onBehalfOf /
        // _externalSystemId / initiator，效果與直接指定完全相同。
        // 逐個 key 檢查，訊息指出是哪個變數名（呼叫端才知道要改什麼）。
        vars.keySet().stream()
                .filter(ProcessController::isProtectedVariable)
                .findFirst()
                .ifPresent(name -> {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "不允許以流程變數指定受保護的變數: " + name
                                    + "（initiator 由登入身分決定；_ 前綴為伺服器內部狀態）");
                });

        // 存在性檢查放在所有 400 之後：請求形狀不對與資源不存在是兩件事，
        // 先把形狀講清楚，呼叫端才知道要改 payload 還是改流程選擇。
        if (repositoryService.createProcessDefinitionQuery().processDefinitionKey(key).count() == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "流程定義不存在: " + key);
        }

        // ⚠️ 必須在 startProcessInstanceByKey 之前寫入：BPMN 在流程啟動的當下
        // 就求值 assignee 運算式（見 ExternalApiController 的同型註解）。
        // 由 server 寫在最後，body 無法蓋掉它。
        vars.put("initiator", callerId);

        ProcessInstance pi;
        try {
            pi = runtimeService.startProcessInstanceByKey(key, req.businessKey(), vars);
        } catch (FlowableObjectNotFoundException e) {
            // race condition 保險：預先檢查之後、真正啟動之前，定義被刪了。
            // 沒有這一層它會變成 500 → 使用者重送 → 重複案件。
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "流程定義不存在: " + key, e);
        }

        formVersionLocker.lockVersions(pi.getProcessInstanceId(), pi.getProcessDefinitionId());

        // operatorId 用 callerId，不用 req.initiator() —— 稽核必須記「誰做的」，
        // 而不只是「這張單掛在誰名下」。改動前這裡記的是冒用者，
        // 會讓真正的發起人在稽核裡完全不存在。
        auditPublisher.publish(new AuditEvent("PROCESS_START", callerId,
                pi.getProcessInstanceId(), null,
                Map.of("processDefinitionKey", key,
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
