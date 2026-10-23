package com.bpm.core.uniform;

import com.bpm.core.form.model.FormData;
import com.bpm.core.form.model.FormDefinition;
import com.bpm.core.dto.FormPreviewRequest;
import com.bpm.core.security.CallerId;
import com.bpm.core.security.ProcessAccessGuard;
import org.flowable.bpmn.model.StartEvent;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.TaskService;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.task.api.Task;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
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
    private final FormAccessResolver accessResolver;
    private final FormContextResolver contextResolver;
    private final FormEnvelopeService envelopeService;
    private final FormDefinitionValidator definitionValidator;

    public FormEnvelopeController(TaskService taskService, RepositoryService repositoryService,
                                  ProcessAccessGuard accessGuard, FormAccessResolver accessResolver,
                                  FormContextResolver contextResolver, FormEnvelopeService envelopeService,
                                  FormDefinitionValidator definitionValidator) {
        this.taskService = taskService;
        this.repositoryService = repositoryService;
        this.accessGuard = accessGuard;
        this.accessResolver = accessResolver;
        this.contextResolver = contextResolver;
        this.envelopeService = envelopeService;
        this.definitionValidator = definitionValidator;
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
    public Map<String, Object> taskForm(@PathVariable String taskId, @CallerId String callerId) {
        Task task = taskService.createTaskQuery().taskId(taskId).singleResult();
        if (task == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "任務不存在: " + taskId);
        }
        // 授權先於「這個任務是什麼」的判斷：非關係人拿不到任何關於任務的資訊。
        accessGuard.requireReadAccess(task.getProcessInstanceId(), callerId);

        String formKey = task.getFormKey();
        if (formKey == null || formKey.isBlank() || formKey.startsWith("external:")) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "此任務不是動態表單任務: " + taskId);
        }

        FormDefinition def = contextResolver.definitionFor(formKey, task.getProcessInstanceId());
        Map<String, Object> profile = accessResolver.profileForTask(
                task.getProcessDefinitionId(), task.getTaskDefinitionKey());
        FormData latest = contextResolver.latestRow(task.getProcessInstanceId());
        return envelopeService.envelope(def, profile, contextResolver.storedDataOf(latest));
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
