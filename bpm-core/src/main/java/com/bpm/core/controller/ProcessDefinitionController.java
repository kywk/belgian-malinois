package com.bpm.core.controller;

import com.bpm.core.uniform.FormAccessResolver;
import org.flowable.bpmn.BpmnAutoLayout;
import org.flowable.bpmn.converter.BpmnXMLConverter;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.StartEvent;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.repository.ProcessDefinition;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/process-definitions")
    public class ProcessDefinitionController {

    private final RepositoryService repositoryService;
    // startEvent 的走訪規則只有一份（FormAccessResolver）；這裡只是借用
    // 「主流程的第一個 StartEvent」來取 formKey。
    private final FormAccessResolver accessResolver;

    public ProcessDefinitionController(RepositoryService repositoryService,
                                       FormAccessResolver accessResolver) {
        this.repositoryService = repositoryService;
        this.accessResolver = accessResolver;
    }

    /**
     * 流程定義清單。
     *
     * <p>uni-form 重構（計畫 §五 A2）：每一項新增 {@code startFormKey}
     * （String｜null）—— 前端要能用同一份清單決定「啟動時要不要走
     * {@code GET /api/process-definitions/{key}/start-form}」。
     * 沒有起始表單（startEvent 無 formKey）時是 {@code null}，不是空字串：
     * 「沒有」與「空字串 formKey」在 BPMN 上是同一件事，但在 JSON 上
     * null 讓前端可以用「有沒有值」判斷，不必再比對空字串。
     */
    @GetMapping
    @io.swagger.v3.oas.annotations.Operation(summary="列出流程定義", description="latestVersion預設false；回id/key/name/version/deploymentId/suspended/startFormKey陣列。 身分由JWT或已啟用且受信任的閘道建立；CallerId由SecurityContext取得，不是query參數。", operationId="ProcessDefinitionController_list")
    @io.swagger.v3.oas.annotations.security.SecurityRequirements({@io.swagger.v3.oas.annotations.security.SecurityRequirement(name="bpmBearer"), @io.swagger.v3.oas.annotations.security.SecurityRequirement(name="gatewaySecret")})
    @io.swagger.v3.oas.annotations.Parameter(name="X-User-Id", in=io.swagger.v3.oas.annotations.enums.ParameterIn.HEADER, description="僅信任閘道模式使用；與X-System-Id至少一項，須同時帶X-Gateway-Secret，JWT身分優先")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="200", description="列出流程定義成功；直接回傳所列資料，不另加code/data envelope", content=@io.swagger.v3.oas.annotations.media.Content(mediaType="application/json", array=@io.swagger.v3.oas.annotations.media.ArraySchema(schema=@io.swagger.v3.oas.annotations.media.Schema(implementation=com.bpm.core.config.BpmDocumentationModels.ProcessDefinitionView.class))))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="401", description="未通過身分驗證；框架依錯誤處理設定輸出，非成功資料schema", content=@io.swagger.v3.oas.annotations.media.Content)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="403", description="身分有效但不具路由或資源權限；框架依錯誤處理設定輸出，非成功資料schema", content=@io.swagger.v3.oas.annotations.media.Content)
    public List<Map<String, Object>> list(@RequestParam(defaultValue = "false") boolean latestVersion) {
        var query = repositoryService.createProcessDefinitionQuery().orderByProcessDefinitionVersion().desc();
        if (latestVersion) query.latestVersion();
        return query.list().stream().map(this::toMap).toList();
    }

    @GetMapping(value = "/{id}/resourcedata", produces = MediaType.TEXT_XML_VALUE)
    @io.swagger.v3.oas.annotations.Operation(summary="讀取流程 BPMN XML", description="回原始XML文字；若缺圖形座標先在模型執行自動排版，不是JSON envelope。 身分由JWT或已啟用且受信任的閘道建立；CallerId由SecurityContext取得，不是query參數。", operationId="ProcessDefinitionController_getXml")
    @io.swagger.v3.oas.annotations.security.SecurityRequirements({@io.swagger.v3.oas.annotations.security.SecurityRequirement(name="bpmBearer"), @io.swagger.v3.oas.annotations.security.SecurityRequirement(name="gatewaySecret")})
    @io.swagger.v3.oas.annotations.Parameter(name="X-User-Id", in=io.swagger.v3.oas.annotations.enums.ParameterIn.HEADER, description="僅信任閘道模式使用；與X-System-Id至少一項，須同時帶X-Gateway-Secret，JWT身分優先")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="200", description="BPMN XML文字（String converter）", content=@io.swagger.v3.oas.annotations.media.Content(mediaType="text/xml", schema=@io.swagger.v3.oas.annotations.media.Schema(implementation=String.class)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="401", description="未通過身分驗證；框架依錯誤處理設定輸出，非成功資料schema", content=@io.swagger.v3.oas.annotations.media.Content)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="403", description="身分有效但不具路由或資源權限；框架依錯誤處理設定輸出，非成功資料schema", content=@io.swagger.v3.oas.annotations.media.Content)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="404", description="資源或等待訂閱不存在；框架依錯誤處理設定輸出，非成功資料schema", content=@io.swagger.v3.oas.annotations.media.Content)
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
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", pd.getId());
        map.put("key", pd.getKey());
        map.put("name", pd.getName() != null ? pd.getName() : "");
        map.put("version", pd.getVersion());
        map.put("deploymentId", pd.getDeploymentId());
        map.put("suspended", pd.isSuspended());
        map.put("startFormKey", startFormKeyOf(pd.getId()));
        return map;
    }

    /** startEvent 的 {@code flowable:formKey}；沒有 startEvent 或沒有 formKey 回 {@code null}。 */
    private String startFormKeyOf(String processDefinitionId) {
        StartEvent startEvent = accessResolver.startEventOf(processDefinitionId);
        String formKey = startEvent != null ? startEvent.getFormKey() : null;
        return formKey != null && !formKey.isBlank() ? formKey : null;
    }
}
