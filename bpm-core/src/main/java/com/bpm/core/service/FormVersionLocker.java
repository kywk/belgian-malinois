package com.bpm.core.service;

import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.StartEvent;
import org.flowable.bpmn.model.UserTask;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.io.Serializable;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Scans BPMN for all formKeys and locks their published versions
 * into a process variable (_formVersions) at process start time.
 */
@Service
public class FormVersionLocker {

    private static final Logger log = LoggerFactory.getLogger(FormVersionLocker.class);

    private final RepositoryService repositoryService;
    private final RuntimeService runtimeService;
    private final RestClient formClient;
    /** 保留原始 URL 供錯誤訊息使用（RestClient 建立後無法取回 baseUrl）。 */
    private final String formServiceUrl;

    public FormVersionLocker(RepositoryService repositoryService, RuntimeService runtimeService,
                             @Value("${bpm.form-service-url:http://localhost:8081}") String formServiceUrl) {
        this.repositoryService = repositoryService;
        this.runtimeService = runtimeService;
        this.formServiceUrl = formServiceUrl;
        this.formClient = RestClient.builder().baseUrl(formServiceUrl).build();
    }

    /**
     * After process start, resolve all formKey versions and store as process variable.
     */
    public void lockVersions(String processInstanceId, String processDefinitionId) {
        BpmnModel model = repositoryService.getBpmnModel(processDefinitionId);
        Map<String, Integer> versions = new HashMap<>();

        // 先收集 distinct formKey，再逐一解析。
        // 若在迴圈中直接以 versions.containsKey() 去重會有兩個問題：versions
        // 只存放「解析成功」的 key，因此同一個失敗的 formKey 出現在多個 UserTask 時
        // (1) 計數會重複累加，警告訊息虛報 (2) 會對同一個 key 重複發 HTTP 請求，
        // form-service 掛掉時每次啟流程都要付 N 倍的 timeout。
        Set<String> formKeys = new LinkedHashSet<>();
        for (FlowElement el : model.getMainProcess().getFlowElements()) {
            String formKey = null;
            if (el instanceof UserTask ut) formKey = ut.getFormKey();
            else if (el instanceof StartEvent se) formKey = se.getFormKey();

            if (formKey != null && !formKey.isBlank() && !formKey.startsWith("external:")
                    && !formKey.contains("${")) {
                formKeys.add(formKey);
            }
        }

        for (String formKey : formKeys) {
            Integer version = resolveVersion(formKey);
            if (version != null) versions.put(formKey, version);
        }

        if (!versions.isEmpty()) {
            runtimeService.setVariable(processInstanceId, "_formVersions", (Serializable) versions);
        }

        // 讓「版本鎖定沒生效」變成可觀察的事件。
        // 改動前 resolveVersion 吞掉例外回傳 null，若 form-service 不可用則
        // versions 全空、_formVersions 完全不寫入，但流程照常啟動成功 ——
        // 該案件此後永遠走「最新版表單」，版本鎖定靜默失效且無任何錯誤訊息。
        if (formKeys.size() > versions.size()) {
            log.warn("流程實例 {} 的表單版本鎖定不完整：BPMN 中有 {} 個 formKey，"
                            + "但只解析到 {} 個版本。未鎖定的表單將在案件進行中隨改版而變動。"
                            + "請確認 form-service ({}) 可連線且這些 formKey 都有已發布版本。",
                    processInstanceId, formKeys.size(), versions.size(), formServiceUrl);
        }
    }

    private Integer resolveVersion(String formKey) {
        try {
            var resp = formClient.get().uri("/api/forms/{formKey}", formKey)
                    .retrieve().body(Map.class);
            if (resp == null) {
                log.warn("form-service 對 formKey '{}' 回傳空內容，無法鎖定版本", formKey);
                return null;
            }
            Object version = resp.get("version");
            if (version instanceof Integer i) return i;
            if (version instanceof Number n) return n.intValue();
            log.warn("formKey '{}' 的回應缺少可用的 version 欄位（實際值: {}）", formKey, version);
            return null;
        } catch (Exception e) {
            // 刻意不重新拋出：流程啟動不應因表單服務短暫不可用而失敗。
            // 但必須留下記錄，否則此失敗完全不可觀察。
            log.warn("解析 formKey '{}' 的版本失敗，將不鎖定此表單版本: {}",
                    formKey, e.toString());
            return null;
        }
    }
}
