package com.bpm.core.uniform;

import com.bpm.core.form.model.FormData;
import com.bpm.core.form.model.FormDefinition;
import com.bpm.core.dto.FormPreviewRequest;
import com.bpm.core.security.CallerId;
import com.bpm.core.security.ProcessAccessGuard;
import com.bpm.core.security.TaskHolderGuard;
import org.flowable.bpmn.model.StartEvent;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.TaskService;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.task.api.Task;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.Map;

/**
 * uni-form Envelope 端點（docs/plan/2026-10-12-uniform-form-refactor.md §五 A1／A3／A4）。
 *
 * <h2>三個端點、同一種回應</h2>
 *
 * <ul>
 *   <li>{@code GET /api/tasks/{taskId}/form}：任務表單。Access Profile 來自
 *       BPMN 節點（{@link FormAccessResolver}）；{@code data} 是該案件最新
 *       FormData 列（canonical JSON）或 {@code {}}。</li>
 *   <li>{@code GET /api/process-definitions/{key}/start-form}：起始表單。
 *       profile 來自 startEvent（無 access → fill／editable）；{@code data} 固定
 *       {@code {}}（新案件還沒有資料）。</li>
 *   <li>{@code POST /api/forms/preview}：設計器預覽。DSL 先過
 *       {@link FormDefinitionValidator}，profile 固定 fill／editable，
 *       {@code key} 取自 DSL、{@code version} 固定 1。</li>
 * </ul>
 *
 * <h2>授權（逐條沿用既有政策，不新造）</h2>
 *
 * <ul>
 *   <li><b>任務表單＝讀個案內容</b>：與 variables／form-data／附件／簽核
 *       軌跡同一條 {@link ProcessAccessGuard#requireReadAccess}（關係人 ∪
 *       {@code audit:log:read} 旁路且每次留痕），非關係人 404。
 *       未登入由 {@code SecurityConfig} 的 {@code /api/** → authenticated()}
 *       擋成 401；本 controller 不做身分 fallback（見 CallerIdArgumentResolver）。</li>
 *   <li><b>start-form 是流程定義層級</b>：它描述「按下啟動時要填什麼」，
 *       與清單（{@code GET /api/process-definitions}，登入即可）同一層級，
 *       沒有個案資料 —— 維持登入即可。</li>
 *   <li><b>preview 落在 {@code POST /api/forms/**}</b>：沿用既有的
 *       {@code bpm:form:design}（或 ROLE_ADMIN）規則 —— 設計器改的是
 *       流程語意（欄位 id == 流程變數名），不是個人資料。這條規則在
 *       {@code SecurityConfig}，本 controller 不重寫。</li>
 * </ul>
 */
@RestController
    public class FormEnvelopeController {

    private final TaskService taskService;
    private final RepositoryService repositoryService;
    private final ProcessAccessGuard accessGuard;
    private final TaskHolderGuard holderGuard;
    private final FormAccessResolver accessResolver;
    private final FormContextResolver contextResolver;
    private final FormEnvelopeService envelopeService;
    private final FormDefinitionValidator definitionValidator;
    private final OptionLabelSnapshots labelSnapshots;

    public FormEnvelopeController(TaskService taskService, RepositoryService repositoryService,
                                  ProcessAccessGuard accessGuard, TaskHolderGuard holderGuard, FormAccessResolver accessResolver,
                                  FormContextResolver contextResolver, FormEnvelopeService envelopeService,
                                  FormDefinitionValidator definitionValidator, OptionLabelSnapshots labelSnapshots) {
        this.taskService = taskService;
        this.repositoryService = repositoryService;
        this.accessGuard = accessGuard;
        this.holderGuard = holderGuard;
        this.accessResolver = accessResolver;
        this.contextResolver = contextResolver;
        this.envelopeService = envelopeService;
        this.definitionValidator = definitionValidator;
        this.labelSnapshots = labelSnapshots;
    }

    /**
     * 任務的動態表單（Envelope 本體）。
     *
     * <p>狀態碼：
     * <ul>
     *   <li>任務不存在 → 404。</li>
     *   <li>非關係人 → 404（{@code requireReadAccess}；404 而非 403，
     *       避免把「這個 taskId 存在」變成可枚舉的訊號）。</li>
     *   <li>{@code formKey} 空白或 {@code external:} 前綴 → 404，訊息指名
     *       「此任務不是動態表單任務」—— 外部表單由外部系統渲染，
     *       不是「找不到」，而是「這裡沒有」。這兩者對呼叫端是同一個動作
     *       （不要重試），所以共用 404、以訊息區分。</li>
     *   <li>找不到（鎖定版本的）表單定義 → 404。</li>
     * </ul>
     */
    @GetMapping("/api/tasks/{taskId}/form")
    @io.swagger.v3.oas.annotations.Operation(summary="讀取任務表單 Envelope", description="先驗任務關係／持有者；加簽沿伺服器父鏈尋找表單且唯讀。返回UniForm投影與選項標籤快照，不返回未授權原文。 身分由JWT或已啟用且受信任的閘道建立；CallerId由SecurityContext取得，不是query參數。", operationId="FormEnvelopeController_taskForm")
    @io.swagger.v3.oas.annotations.security.SecurityRequirements({@io.swagger.v3.oas.annotations.security.SecurityRequirement(name="bpmBearer"), @io.swagger.v3.oas.annotations.security.SecurityRequirement(name="gatewaySecret")})
    @io.swagger.v3.oas.annotations.Parameter(name="X-User-Id", in=io.swagger.v3.oas.annotations.enums.ParameterIn.HEADER, description="僅信任閘道模式使用；與X-System-Id至少一項，須同時帶X-Gateway-Secret，JWT身分優先")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="200", description="讀取任務表單 Envelope成功；直接回傳 Map<String, Object>，動態Map欄位依本operation描述及領域內容。", useReturnTypeSchema=true)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="401", description="未通過身分驗證；框架錯誤body；message僅刻意提供理由時存在", content=@io.swagger.v3.oas.annotations.media.Content(mediaType="application/json", schema=@io.swagger.v3.oas.annotations.media.Schema(implementation=com.bpm.core.config.BpmDocumentationModels.FrameworkError.class)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="403", description="身分有效但不具路由或資源權限；框架錯誤body；message僅刻意提供理由時存在", content=@io.swagger.v3.oas.annotations.media.Content(mediaType="application/json", schema=@io.swagger.v3.oas.annotations.media.Schema(implementation=com.bpm.core.config.BpmDocumentationModels.FrameworkError.class)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="404", description="資源或等待訂閱不存在；框架錯誤body；message僅刻意提供理由時存在", content=@io.swagger.v3.oas.annotations.media.Content(mediaType="application/json", schema=@io.swagger.v3.oas.annotations.media.Schema(implementation=com.bpm.core.config.BpmDocumentationModels.FrameworkError.class)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="409", description="目前狀態、版本或引用衝突；框架錯誤body；message僅刻意提供理由時存在", content=@io.swagger.v3.oas.annotations.media.Content(mediaType="application/json", schema=@io.swagger.v3.oas.annotations.media.Schema(implementation=com.bpm.core.config.BpmDocumentationModels.FrameworkError.class)))
    public Map<String, Object> taskForm(@PathVariable String taskId, @io.swagger.v3.oas.annotations.Parameter(hidden=true) @CallerId String callerId) {
        Task task = taskService.createTaskQuery().taskId(taskId).singleResult();
        if (task == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "任務不存在: " + taskId);
        }
        // 授權先於「這個任務是什麼」的判斷：非關係人拿不到任何關於任務的資訊。
        boolean countersign = task.getParentTaskId() != null;
        if (countersign) {
            // 加簽是 standalone task。先驗持有者，再沿伺服器保存的父鏈找表單；
            // 不授予父任務的一般讀寫權限，亦不接受呼叫者指定父任務。
            holderGuard.requireHolder(task, callerId);
            Set<String> visited = new HashSet<>();
            while (task.getParentTaskId() != null) {
                if (!visited.add(task.getId())) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "加簽父任務關係不正確");
                }
                task = taskService.createTaskQuery().taskId(task.getParentTaskId()).singleResult();
                if (task == null) {
                    throw new ResponseStatusException(HttpStatus.NOT_FOUND, "加簽父任務不存在");
                }
            }
        } else {
            accessGuard.requireReadAccess(task.getProcessInstanceId(), callerId);
            holderGuard.requireActiveDelegation(task, callerId);
        }

        String formKey = task.getFormKey();
        if (formKey == null || formKey.isBlank() || formKey.startsWith("external:")) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "此任務不是動態表單任務: " + taskId);
        }

        FormDefinition def = contextResolver.definitionFor(formKey, task.getProcessInstanceId());
        Map<String, Object> profile = accessResolver.profileForTask(
                task.getProcessDefinitionId(), task.getTaskDefinitionKey());
        if (countersign) profile = readonlyProfile(profile);
        FormData latest = contextResolver.latestRow(task.getProcessInstanceId());
        Map<String, Object> envelope = envelopeService.envelope(def, profile, contextResolver.storedDataOf(latest));
        return labelSnapshots.attach(envelope, latest == null ? null : latest.getOptionLabelsJson());
    }

    private static Map<String, Object> readonlyProfile(Map<String, Object> source) {
        Map<String, Object> profile = new LinkedHashMap<>(source);
        profile.put("mode", "view");
        profile.put("default", "hidden".equals(source.get("default")) ? "hidden" : "readonly");
        Map<String, Object> fields = new LinkedHashMap<>();
        if (source.get("fields") instanceof Map<?, ?> entries) {
            entries.forEach((key, value) -> {
                if (value instanceof Map<?, ?> entry) {
                    Map<String, Object> field = new LinkedHashMap<>();
                    entry.forEach((name, setting) -> field.put(name.toString(), setting));
                    // required-only 覆寫繼承 default；hidden 不可因加簽而變為可見。
                    if (field.containsKey("state") && !"hidden".equals(field.get("state"))) {
                        field.put("state", "readonly");
                    }
                    fields.put(key.toString(), field);
                }
            });
        }
        profile.put("fields", fields);
        return profile;
    }

    /**
     * 流程的起始表單（Envelope 本體）。
     *
     * <ul>
     *   <li>流程 key 不存在 → 404（取 latestVersion，與清單端點同一語意）。</li>
     *   <li>startEvent 沒有 formKey → 404（「此流程沒有起始表單」）。</li>
     *   <li>找不到 published 定義 → 404。</li>
     * </ul>
     */
    @GetMapping("/api/process-definitions/{key}/start-form")
    @io.swagger.v3.oas.annotations.Operation(summary="讀取起始表單與版本脈絡", description="取得最新流程與published表單；suspended回409。applicationContext含流程及表單版本，送件必須回傳以查核過期。 身分由JWT或已啟用且受信任的閘道建立；CallerId由SecurityContext取得，不是query參數。", operationId="FormEnvelopeController_startForm")
    @io.swagger.v3.oas.annotations.security.SecurityRequirements({@io.swagger.v3.oas.annotations.security.SecurityRequirement(name="bpmBearer"), @io.swagger.v3.oas.annotations.security.SecurityRequirement(name="gatewaySecret")})
    @io.swagger.v3.oas.annotations.Parameter(name="X-User-Id", in=io.swagger.v3.oas.annotations.enums.ParameterIn.HEADER, description="僅信任閘道模式使用；與X-System-Id至少一項，須同時帶X-Gateway-Secret，JWT身分優先")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="200", description="讀取起始表單與版本脈絡成功；直接回傳 Map<String, Object>，動態Map欄位依本operation描述及領域內容。", useReturnTypeSchema=true)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="401", description="未通過身分驗證；框架錯誤body；message僅刻意提供理由時存在", content=@io.swagger.v3.oas.annotations.media.Content(mediaType="application/json", schema=@io.swagger.v3.oas.annotations.media.Schema(implementation=com.bpm.core.config.BpmDocumentationModels.FrameworkError.class)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="403", description="身分有效但不具路由或資源權限；框架錯誤body；message僅刻意提供理由時存在", content=@io.swagger.v3.oas.annotations.media.Content(mediaType="application/json", schema=@io.swagger.v3.oas.annotations.media.Schema(implementation=com.bpm.core.config.BpmDocumentationModels.FrameworkError.class)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="404", description="資源或等待訂閱不存在；框架錯誤body；message僅刻意提供理由時存在", content=@io.swagger.v3.oas.annotations.media.Content(mediaType="application/json", schema=@io.swagger.v3.oas.annotations.media.Schema(implementation=com.bpm.core.config.BpmDocumentationModels.FrameworkError.class)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="409", description="目前狀態、版本或引用衝突；框架錯誤body；message僅刻意提供理由時存在", content=@io.swagger.v3.oas.annotations.media.Content(mediaType="application/json", schema=@io.swagger.v3.oas.annotations.media.Schema(implementation=com.bpm.core.config.BpmDocumentationModels.FrameworkError.class)))
    public Map<String, Object> startForm(@PathVariable String key) {
        ProcessDefinition pd = repositoryService.createProcessDefinitionQuery()
                .processDefinitionKey(key).latestVersion().singleResult();
        if (pd == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "流程定義不存在: " + key);
        }
        StartEvent startEvent = accessResolver.startEventOf(pd.getId());
        if (pd.isSuspended()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "此流程已暫停，無法受理新申請");
        }
        String formKey = ApplicationFormBinding.formKey(startEvent);
        if (formKey == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "此流程沒有起始表單: " + key);
        }

        // 開啟時取得最新 published；送件必須回傳此脈絡，由伺服器重新核對是否過期。
        FormDefinition def = contextResolver.definitionFor(formKey, null);
        ApplicationFormBinding.requireCompatible(repositoryService.getBpmnModel(pd.getId()), def);
        Map<String, Object> profile = accessResolver.profileForStartEvent(pd.getId());
        Map<String, Object> envelope = new LinkedHashMap<>(
                envelopeService.envelope(def, profile, new LinkedHashMap<>()));
        envelope.put("applicationContext", Map.of(
                "processDefinitionId", pd.getId(), "formDefinitionId", def.getId(),
                "formKey", def.getFormKey(), "formVersion", def.getVersion()));
        return envelope;
    }

    /**
     * 設計器預覽：以 body 的 DSL 投影 Envelope。
     *
     * <p>{@code data} 固定 {@code {}} —— 預覽沒有案件，也沒有可信的既有資料。
     * 前端要預覽「填入後的樣子」時，那是前端的本地狀態，不是伺服器的 Envelope。
     */
    @PostMapping("/api/forms/preview")
    @io.swagger.v3.oas.annotations.Operation(summary="預覽未保存的 UniForm DSL", description="schemaJson經DSL驗證；以fill/editable profile產生Envelope，不新增表單版本。 需要bpm:form:design或ROLE_ADMIN。 身分由JWT或已啟用且受信任的閘道建立；CallerId由SecurityContext取得，不是query參數。", operationId="FormEnvelopeController_preview")
    @io.swagger.v3.oas.annotations.security.SecurityRequirements({@io.swagger.v3.oas.annotations.security.SecurityRequirement(name="bpmBearer"), @io.swagger.v3.oas.annotations.security.SecurityRequirement(name="gatewaySecret")})
    @io.swagger.v3.oas.annotations.Parameter(name="X-User-Id", in=io.swagger.v3.oas.annotations.enums.ParameterIn.HEADER, description="僅信任閘道模式使用；與X-System-Id至少一項，須同時帶X-Gateway-Secret，JWT身分優先")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="200", description="預覽未保存的 UniForm DSL成功；直接回傳 Map<String, Object>，動態Map欄位依本operation描述及領域內容。", useReturnTypeSchema=true)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="400", description="輸入形狀或業務前置條件不合法；框架錯誤body；message僅刻意提供理由時存在", content=@io.swagger.v3.oas.annotations.media.Content(mediaType="application/json", schema=@io.swagger.v3.oas.annotations.media.Schema(implementation=com.bpm.core.config.BpmDocumentationModels.FrameworkError.class)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="401", description="未通過身分驗證；框架錯誤body；message僅刻意提供理由時存在", content=@io.swagger.v3.oas.annotations.media.Content(mediaType="application/json", schema=@io.swagger.v3.oas.annotations.media.Schema(implementation=com.bpm.core.config.BpmDocumentationModels.FrameworkError.class)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="403", description="身分有效但不具路由或資源權限；框架錯誤body；message僅刻意提供理由時存在", content=@io.swagger.v3.oas.annotations.media.Content(mediaType="application/json", schema=@io.swagger.v3.oas.annotations.media.Schema(implementation=com.bpm.core.config.BpmDocumentationModels.FrameworkError.class)))
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required=true, content=@io.swagger.v3.oas.annotations.media.Content(mediaType="application/json", schema=@io.swagger.v3.oas.annotations.media.Schema(implementation=FormPreviewRequest.class)))
    public Map<String, Object> preview(@RequestBody FormPreviewRequest req) {
        String schemaJson = req != null ? req.schemaJson() : null;
        Map<String, Object> dsl = definitionValidator.parse(schemaJson);
        definitionValidator.validate(dsl);

        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("mode", "fill");
        profile.put("default", "editable");
        profile.put("fields", new LinkedHashMap<>());
        return envelopeService.preview(dsl, profile, new LinkedHashMap<>());
    }
}
