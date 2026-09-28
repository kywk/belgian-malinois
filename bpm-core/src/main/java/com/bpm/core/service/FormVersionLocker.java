package com.bpm.core.service;

import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.StartEvent;
import org.flowable.bpmn.model.UserTask;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.bpm.core.form.service.FormService;
import org.springframework.stereotype.Service;

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
    private final FormService formService;

    public FormVersionLocker(RepositoryService repositoryService, RuntimeService runtimeService,
                             FormService formService) {
        this.repositoryService = repositoryService;
        this.runtimeService = runtimeService;
        this.formService = formService;
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
                            + "請確認這些 formKey 都有已發布版本。",
                    processInstanceId, formKeys.size(), versions.size());
        }
    }

    /**
     * 解析 formKey 的已發布版本號。
     *
     * <p><b>Stage 3（ADR-001 §2）消除了一個靜默失效。</b>改動前這裡是同步 HTTP：
     *
     * <pre>
     *   formClient.get().uri("/api/forms/{formKey}", formKey)...   // 跨服務呼叫
     *   catch (Exception e) { return null; }                       // 例外被吞掉
     * </pre>
     *
     * <p>form-service 不可用時例外被吞掉、回傳 null，{@code versions} 保持為空，
     * {@code lockVersions()} 的 {@code if (!versions.isEmpty())} 不成立
     * → <b>{@code _formVersions} 完全不寫入，但流程照常啟動成功</b>。
     * 該案件此後永遠走「最新版表單」，版本鎖定靜默失效 ——
     * 而這正是版本鎖定要防的事。
     *
     * <p>而且這個呼叫發生在流程啟動的交易之內，且 URL 預設指向自己
     * （見 P1-10 的自我死鎖）。改為 in-process 之後，這整類失敗模式消失：
     * 沒有網路、沒有 timeout、沒有被吞掉的連線例外。
     *
     * <p>剩下唯一的「解析不到」情形是<b>真的沒有已發布版本</b>，
     * 那是資料問題而非基礎設施問題，由 lockVersions() 的警告負責可觀察性。
     */
    private Integer resolveVersion(String formKey) {
        try {
            var def = formService.getSchema(formKey, null);
            return def == null ? null : def.getVersion();
        } catch (Exception e) {
            // 這裡只會是「找不到已發布版本」（ResponseStatusException 404）。
            // 仍不重新拋出：一個 formKey 沒有發布版本不該讓整個流程無法啟動，
            // 但 lockVersions() 會統計並警告。
            log.warn("formKey '{}' 沒有可用的已發布版本，將不鎖定此表單版本: {}",
                    formKey, e.toString());
            return null;
        }
    }
}
