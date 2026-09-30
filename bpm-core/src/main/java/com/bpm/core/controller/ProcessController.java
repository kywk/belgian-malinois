package com.bpm.core.controller;

import com.bpm.core.service.InitialAssigneeResolver;
import org.springframework.transaction.annotation.Transactional;
import com.bpm.core.audit.AuditEventPublisher;
import com.bpm.core.audit.model.OperationType;
import com.bpm.core.dto.AuditEvent;
import com.bpm.core.dto.StartProcessRequest;
import com.bpm.core.security.CallerId;
import com.bpm.core.security.ProcessAccessGuard;
import com.bpm.core.service.FormVersionLocker;
import com.bpm.core.service.ProcessInvolvementService;
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
     *       {@code ${assigneeResolver.resolve(execution)}} 決定第一關受理人
     *       （該 resolver 內部讀 {@code initiator}），於是冒用 initiator
     *       等於把單子送到共犯的主管手上。</li>
     *   <li><b>補件任務的 assignee</b>：{@code applicantResolver}（#83 之前
     *       是直接 {@code ${initiator}}）。</li>
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
    private final ProcessAccessGuard accessGuard;
    private final ProcessInvolvementService involvementService;

    public ProcessController(RuntimeService runtimeService, RepositoryService repositoryService,
                             TaskService taskService, AuditEventPublisher auditPublisher,
                             FormVersionLocker formVersionLocker,
                             ProcessAccessGuard accessGuard,
                             ProcessInvolvementService involvementService) {
        this.runtimeService = runtimeService;
        this.repositoryService = repositoryService;
        this.taskService = taskService;
        this.auditPublisher = auditPublisher;
        this.formVersionLocker = formVersionLocker;
        this.accessGuard = accessGuard;
        this.involvementService = involvementService;
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

    /**
     * 我的申請（執行中）（#71：讀端授權）。
     *
     * <h2>改動前是什麼</h2>
     *
     * <p>{@code ?initiator=} 是 {@code required=false} 且<b>完全不檢查</b>是否
     * 等於呼叫者 —— 不帶參數時 {@code query} 上沒有任何條件，於是回傳全公司
     * <b>執行中</b>的案件（實測 107 件），包含 businessKey 與當前審核人。
     * 那等於任何登入者都能列出全公司此刻在審什麼、卡在誰手上。
     *
     * <h2>三條規則（與 #66 同一組政策，不可改）</h2>
     *
     * <ol>
     *   <li><b>帶了與自己不符的身分 → 明確 400。</b>不靜默忽略：靜默丟棄會讓
     *       呼叫端以為它查得到對方的申請，而實際拿到的是自己的。</li>
     *   <li><b>省略參數 → 預設為呼叫者</b>（不是「全部」）。</li>
     *   <li><b>空白視同省略。</b>與 {@code DocumentController.createdBy} 的
     *       判斷一致（那是 JPA entity 欄位，同樣無法分辨「沒送」與送了 null）。
     *       空白不可能指向別人，因此不需要 400。</li>
     * </ol>
     *
     * <p>⚠️ 這裡<b>不</b>套用稽核旁路：本端點回傳的永遠是「呼叫者自己的申請」，
     * 稽核人員要查<b>別人</b>的案件請走 {@code /involved}（仍限於他自己參與的）
     * 或稽核 API。附件那條旁路是因為「調查者不會是該案的參與者」，
     * 這個端點沒有同樣的需求。
     */
    @GetMapping
    public List<Map<String, Object>> getProcessInstances(
            @RequestParam(required = false) String initiator,
            @CallerId String callerId) {
        String self = accessGuard.requireSelf(initiator, callerId, "initiator");
        var query = runtimeService.createProcessInstanceQuery();
        // 代員工發起的案件（R-20）：initiator 是 system:<id>，員工記在 onBehalfOf。
        // 兩者都要比對，否則代發的單不會出現在那位員工的「我的申請」。
        // 回應以 onBehalf=true 標示，讓前端能顯示「由外部系統代為提出」——
        // 使用者看到一張自己沒送過的單，必須知道它是怎麼來的。
        query.or().variableValueEquals("initiator", self)
                .variableValueEquals(InitialAssigneeResolver.ON_BEHALF_OF_VAR, self).endOr();
        java.util.Set<String> onBehalf = runtimeService.createProcessInstanceQuery()
                .variableValueEquals(InitialAssigneeResolver.ON_BEHALF_OF_VAR, self).list()
                .stream().map(ProcessInstance::getProcessInstanceId).collect(java.util.stream.Collectors.toSet());
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

    /**
     * 我參與的案件（執行中）—— 申請人的單不算，審過／正在審的才算。
     *
     * <h2>為什麼需要這個端點</h2>
     *
     * <p>{@code GET /api/process-instances} 只回「我送出的單」，但實務上最常見的
     * 情境是「我三個月前審過這張單，現在卡在別人的關卡」—— 那張單不是我的申請，
     * 卻明確是我參與的。要求查詢端點同時回兩種視角會讓「我的申請」這個頁面
     * 混入別人送來、也已經審完的案件，於是拆成不同 API。
     *
     * <h2>授權規則</h2>
     *
     * <p>不帶任何參數，範圍就是「{@code isParticipant} 為真的案件」——
     * 與 {@link ProcessAccessGuard#isParticipant} 的三個條件刻意一致
     * （見 {@link ProcessInvolvementService}），否則會出現
     * 「列表看得到、點進去 404」或反過來。
     *
     * <h2>⚠️ 效能</h2>
     *
     * <p>查詢次數固定為 4，與公司規模無關；<b>不可</b>改成
     * 「列出所有案件再逐案跑 isParticipant」（那是 N+1 × 4，見該類別）。
     * 其中 {@code taskInvolvedUser} 本身是全表掃描，那是這個端點的主要成本來源。
     *
     * <p>currentTask 一次撈回（而非逐案查詢）：我們已經知道所有 id 了，
     * 而 {@code getProcessInstances} 的逐案查詢是既有端點的 N+1，不在本次範圍。
     */
    @GetMapping("/involved")
    public List<Map<String, Object>> getInvolvedProcessInstances(@CallerId String callerId) {
        if (callerId == null || callerId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "無法確認身分，請先登入");
        }
        java.util.Set<String> ids = involvementService.involvedRunningInstanceIds(callerId);
        if (ids.isEmpty()) return List.of();

        // ⚠️ 絕對不可用 singleResult()：見上面 getProcessInstances 內的註解。
        // 平行閘道或 multi-instance 會簽會產生併發任務，那會讓整個端點 500。
        java.util.Map<String, List<Task>> tasksByInstance = new java.util.HashMap<>();
        for (Task t : involvementService.findOpenTasks(ids)) {
            tasksByInstance.computeIfAbsent(t.getProcessInstanceId(), k -> new java.util.ArrayList<>()).add(t);
        }
        return involvementService.findRunning(ids).stream()
                .map(pi -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("involved", true);
                    m.put("processInstanceId", pi.getProcessInstanceId());
                    m.put("processDefinitionKey", pi.getProcessDefinitionKey());
                    m.put("businessKey", pi.getBusinessKey() != null ? pi.getBusinessKey() : "");
                    m.put("startTime", pi.getStartTime());
                    m.put("status", "running");
                    List<Task> tasks = tasksByInstance.getOrDefault(pi.getProcessInstanceId(), List.of());
                    if (!tasks.isEmpty()) {
                        Task task = tasks.get(0);
                        m.put("currentTask", Map.of(
                                "taskName", task.getName() != null ? task.getName() : "",
                                "assignee", task.getAssignee() != null ? task.getAssignee() : ""));
                        // 併發時只顯示其中一個會讓使用者以為案件只等一個人，
                        // 把數量一併帶出來（與 GET /api/process-instances 同一理由）。
                        m.put("currentTaskCount", tasks.size());
                    }
                    return m;
                }).toList();
    }

    /**
     * 案件變數 —— 表單資料（欄位 id == 變數名，spec §8.5）。
     *
     * <h2>改動前是什麼</h2>
     *
     * <p>{@code try { return runtimeService.getVariables(id); }
     * catch (Exception e) { return Map.of(); }} ——
     * <b>零授權檢查</b>，而變數裡放的是薪資等敏感表單資料，任何登入者都能讀
     * 任何案件的 id。而且那個 catch 把「你沒權」「案件不存在」「引擎出錯」
     * 三種語意全部塌成 {@code 200 + {}}，呼叫端無從分辨該怎麼處理。
     *
     * <h2>三種結果</h2>
     *
     * <ol>
     *   <li><b>非參與者 → 404</b>（不是 403）。403 會確認「這個案件存在」，
     *       對可枚舉的 id 等於把枚舉管道留著。理由與
     *       {@link ProcessAccessGuard#requireParticipant} 相同。</li>
     *   <li><b>不存在的實例 → 404</b>。</li>
     *   <li><b>已結束、而呼叫者是參與者 → 200 + {}</b>。
     *       這是<b>刻意維持</b>的既有行為：Flowable 結束流程時會清除 runtime
     *       變數，而前端的 {@code DocumentDetail.vue} 依賴空物件
     *       （它 catch 空、variables 保持 {}、{@code DynamicForm} 仍用 schema 渲染）。
     *       改成 404 會讓「審結的單打不開」。</li>
     * </ol>
     *
     * <h2>稽核旁路（policy：只認 audit:log:read、唯讀、每次留痕）</h2>
     *
     * <p>與附件完全相同的政策與相同的實作方式（{@link ProcessAccessGuard#requireReadAccess}），
     * 因為這裡的資料與附件是同一批（一份單的薪資欄位既在表單資料也在附件裡）。
     * 經由旁路的讀取會寫一筆 {@code DATA_ACCESS {auditBypass:true}}。
     *
     * <p>⚠️ 與 {@code AttachmentController.download} 的一處刻意差異：
     * 那裡<b>每一次</b>下載（含參與者）都留痕，這裡只記旁路。
     * 因為 variables 在每次開啟單據時都會被讀一次（熱路徑），
     * 而參與者讀到的正是他自己填的表單值。把「稽核旁路」這件事本身
     * 完整記錄下來就足以回答「誰以稽核身分調閱了哪些案件」；
     * 若連參與者讀表單都要留痕，等於要為日常操作建立另一套行為稽核。
     * 這是政策選擇，若要與附件完全對齊，把 {@code publish} 移出 {@code if} 即可。
     *
     * <p>刻意<b>不</b>加 {@code @Transactional}：這是唯讀查詢 + 一次稽核寫入，
     * 與 {@code AttachmentController.list} 同一型（稽核失敗 → 503，
     * 見 {@code AuditFailClosedTest}）。加交易反而會讓稽核掛在 beforeCommit，
     * 而回應組裝階段的例外會讓它永遠寫不進去。
     */
    @GetMapping("/{id}/variables")
    public Map<String, Object> getVariables(@PathVariable String id, @CallerId String callerId) {
        boolean auditBypass = accessGuard.requireReadAccess(id, callerId);
        if (auditBypass) {
            // 參與者讀自己的表單值不留痕（那是日常操作）；稽核旁路必須留痕。
            auditPublisher.publish(new AuditEvent(OperationType.DATA_ACCESS.name(), callerId,
                    id, null,
                    Map.of("action", "get_variables", "auditBypass", true)));
        }

        // 區分「已結束」與「不存在」：兩者 runtime 都拿不到變數，
        // 但前者是參與者有權看的合法狀態（回 {}），後者是 404。
        // 順序上授權先於存在性檢查：沒有權限的人不該靠狀態碼分辨
        // 「這個 id 不存在」與「我不該看這個 id」。
        if (accessGuard.stateOf(id) != ProcessAccessGuard.InstanceState.RUNNING) {
            return Map.of();
        }
        return runtimeService.getVariables(id);
    }

    /**
     * 案件流程圖：流程定義的 BPMN XML ＋ <b>目前點亮的活動 id</b>（#80：補上物件層授權）。
     *
     * <h2>改動前是什麼</h2>
     *
     * <p>整個方法<b>沒有 {@code @CallerId}、沒有任何檢查</b>：
     * <pre>
     *   public Map&lt;String, Object&gt; getBpmnXml(@PathVariable String id)
     * </pre>
     * 任何登入者拿任一 pid 就拿得到流程圖，而<b>回應裡的 {@code activeIds}
     * 洩漏的是「這張單現在卡在哪一關」</b>。
     *
     * <h2>為什麼這個情報比 id 枚舉更值錢</h2>
     *
     * <p>純粹的 id 枚舉只能證明「某張單存在」，拿到之後還得逐個端點試。
     * 而 {@code activeIds} 直接回答了攻擊者最需要的那一個問題：
     * <b>「現在輪到誰審」。</b> 拿到之後，後續動作就可以針對那個人
     * （社交工程、釣魚信、猜他的待辦網址），而不是對整間公司廣撒網。
     * 換句話說它把「我該攻擊誰」從搜尋問題變成查表問題。
     *
     * <p>而且這條路徑是<b>刻意留下來的</b>：#76 關掉了
     * {@code GET /api/history/tasks} 這個 taskId 發射台，#71 關掉了
     * {@code GET /api/process-instances}，但 pid 仍然可從
     * {@code GET /api/documents}（#80 一併修）與稽核紀錄取得 ——
     * 而本端點是 pid 進去、<b>情報出來</b>的轉換器。
     *
     * <h2>守衛：{@code requireReadAccess}，與 variables／附件／表單資料同一條</h2>
     *
     * <p>資料是「一張單的內容」（流程路徑與目前位置），
     * 因此走既定的讀端政策：<b>關係人，<b>或</b>持有 {@code audit:log:read}
     * 的稽核人員，且旁路每次留痕</b>，非關係人 404。
     *
     * <p>不新造一條規則的理由不是 DRY，而是<b>規則只能有一份</b>：
     * 這份資料與 {@code .../{id}/variables}（薪資欄位）、附件、
     * {@code /api/form-data/{pid}}、簽核軌跡是同一批的同一個層級。
     * 讓本端點自己寫一份 {@code isParticipant}，就會出現
     * 「variables 拒絕、bpmn-xml 放行」那種組合型式的差異 ——
     * 而那種差異比沒有檢查更難察覺，因為兩邊單獨看起來都合理。
     *
     * <h2>⚠️ 為什麼「實例不存在」仍然是 200 + 空圖（未改）</h2>
     *
     * <p>{@code pi == null} 有兩種成因，而<b>本方法刻意不分辨它們</b>：
     * <ul>
     *   <li><b>已結案</b>：runtime 查不到，但案件確實存在。關係人有權看，
     *       而 {@code ProcessDiagram.vue:23} 依賴 {@code 200 + xml:""}
     *       來顯示「無流程圖資料」。改成 404 會讓審結的單在畫面上變成
     *       {@code el-empty} 的錯誤訊息。</li>
     *   <li><b>從未存在</b>：此時呼叫者必然不是關係人
     *       （{@code isParticipant} 三個條件都查不到任何东西），
     *       <b>已經在守衛那裡被擋成 404</b>。</li>
     * </ul>
     *
     * <p>所以剩下的 200 + 空圖只有「稽核旁路」與「已結案的關係人」兩種，
     * 兩者都沒有再洩漏任何東西。用歷史查詢把這兩種情形分開需要改變
     * 回應契約（已結案的單突然有流程圖），那是產品決定，不屬於本工項 ——
     * 已回報 PM。
     *
     * <h2>稽核旁路留痕</h2>
     *
     * <p>與 {@link #getVariables} 同一政策：<b>只記旁路</b>，不記關係人讀取。
     * 因為開一張單就會讀一次流程圖，把日常操作全部留痕等於要為它建立
     * 另一套行為稽核；而「誰以稽核身分調閱了哪些案件的流程圖」這件事本身
     * 被記錄下來就足以追查。用 {@code publish}（fail-closed）而非
     * {@code publishDetached}：稽核寫不進去的話，這次調閱就不該成功。
     *
     * <h2>刻意不加 {@code @Transactional}</h2>
     *
     * <p>唯讀查詢 ＋ 一次稽核寫入，與 {@link #getVariables}、
     * {@code AttachmentController.list}、{@code HistoryController.getHistoricTasks}
     * 同一型（稽核失敗 → 503，見 {@code AuditFailClosedTest}）。
     * 加交易反而會讓稽核掛在 {@code beforeCommit}，
     * 而回應組裝階段的例外會讓它永遠寫不進去。
     */
    @GetMapping("/{id}/bpmn-xml")
    public Map<String, Object> getBpmnXml(@PathVariable String id,
                                          @CallerId String callerId) throws Exception {
        // 授權先於存在性檢查：沒有權限的人不該靠狀態碼分辨
        // 「這個 id 不存在」與「我不該看這個 id」（兩者都必須是 404）。
        if (accessGuard.requireReadAccess(id, callerId)) {
            // 關係人讀自己案件的流程圖不留痕（那是日常操作）；稽核旁路必須留痕。
            auditPublisher.publish(new AuditEvent(OperationType.DATA_ACCESS.name(), callerId,
                    id, null,
                    Map.of("action", "get_bpmn_xml", "auditBypass", true)));
        }

        ProcessInstance pi = runtimeService.createProcessInstanceQuery()
                .processInstanceId(id).singleResult();
        // 見上方「為什麼實例不存在仍然是 200 + 空圖」：已結案的單前端要能開。
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
