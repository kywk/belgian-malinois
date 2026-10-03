package com.bpm.core.form;

import com.bpm.core.form.model.FormData;
import com.bpm.core.form.model.FormDefinition;
import com.bpm.core.form.repository.FormDataRepository;
import com.bpm.core.form.repository.FormDefinitionRepository;
import com.bpm.core.support.IntegrationTestBase;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * #59：封存／刪除的使用中保護。
 *
 * <p>改動前 {@code FormService.archive} 只驗狀態是 published、
 * {@code delete} 只擋 published／archived，兩者都不看表單是否正在被流程使用。
 * 後果有兩條：
 *
 * <ul>
 *   <li>封存一份執行中案件正在用的表單：新流程不再取得它，而版本鎖定失敗
 *       （或尚未鎖定）的舊案件會退回查「最新 published」→ 表單直接消失。</li>
 *   <li>刪除一份已被表單資料指向的 draft：定義與資料之間沒有 FK，
 *       {@code bpm_form_data.form_definition_id} 留下一筆指向不存在表單的孤兒資料。</li>
 * </ul>
 *
 * <p>本組測試是<b>服務層語意</b>的驗證（HTTP 走完整守衛，資料用 repository
 * 直接種入）。測試 e（負控組）不在這裡 —— 它是施工時手動把
 * {@code requireNotUsedByRunningProcess} 拿掉跑一次，確認
 * {@link #archiveIs409WhenFormUsedByRunningCase} 真的會紅，
 * 否則那個測試可能只是「剛好什麼都沒發生」。
 */
class FormUsageGuardTest extends IntegrationTestBase {

    /** 持有 {@code bpm:form:design} 的身分（MockPermController 的 dev fixture）。 */
    private static final String DESIGNER = "mgr001";

    @Autowired
    private FormDefinitionRepository defRepo;

    @Autowired
    private FormDataRepository dataRepo;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private TaskService taskService;

    private FormDefinition saveForm(String status) {
        FormDefinition d = new FormDefinition();
        d.setFormKey("guard-" + UUID.randomUUID().toString().substring(0, 8));
        d.setName("使用中守衛測試");
        d.setVersion(1);
        d.setStatus(status);
        d.setSchemaJson("{\"fields\":[]}");
        return defRepo.save(d);
    }

    /** 啟動一個真實的 leave-approval 案件。 */
    private String startCase() {
        return runtimeService.startProcessInstanceByKey("leave-approval",
                Map.of("initiator", "user001", "leaveType", "annual", "days", 1)).getId();
    }

    /** 完成主管關卡並核准 → 案件結束（runtime 查不到、只剩歷史）。 */
    private void finishCase(String processInstanceId) {
        var task = taskService.createTaskQuery().processInstanceId(processInstanceId).singleResult();
        taskService.complete(task.getId(), Map.of("approved", true, "rejected", false));
    }

    /** 種一筆指向該表單版本的表單資料（避開 controller 的授權，聚焦 service 語意）。 */
    private void saveFormData(String formDefinitionId, String processInstanceId) {
        FormData data = new FormData();
        data.setFormDefinitionId(formDefinitionId);
        data.setProcessInstanceId(processInstanceId);
        data.setSubmittedBy("user001");
        data.setDataJson("{\"days\":1}");
        dataRepo.save(data);
    }

    private boolean isRunning(String processInstanceId) {
        return runtimeService.createProcessInstanceQuery()
                .processInstanceId(processInstanceId).count() > 0;
    }

    // ── archive ───────────────────────────────────────────────────

    @Test
    @DisplayName("表單正被執行中的案件使用 → archive 409，狀態不變")
    void archiveIs409WhenFormUsedByRunningCase() throws Exception {
        FormDefinition def = saveForm("published");
        String pid = startCase();
        saveFormData(def.getId(), pid);

        // 非空驗證：案件必須真的在跑，否則下面的 409 可能來自別的原因。
        assertThat(isRunning(pid)).as("前置條件：案件必須在執行中").isTrue();

        mockMvc.perform(post("/api/forms/{id}/archive", def.getId())
                        .header("X-User-Id", DESIGNER))
                .andExpect(status().isConflict());

        assertThat(defRepo.findById(def.getId()).orElseThrow().getStatus())
                .as("409 之後不得封存 —— 否則擋了等於沒擋")
                .isEqualTo("published");
    }

    @Test
    @DisplayName("表單只被已結束的案件使用 → archive 200（歷史資料要能封存）")
    void archiveSucceedsWhenFormOnlyUsedByFinishedCase() throws Exception {
        FormDefinition def = saveForm("published");
        String pid = startCase();
        finishCase(pid);
        saveFormData(def.getId(), pid);

        // 非空驗證：案件必須真的結束，否則這個測試與「執行中」那條重疊。
        assertThat(isRunning(pid)).as("前置條件：案件必須已結束").isFalse();

        mockMvc.perform(post("/api/forms/{id}/archive", def.getId())
                        .header("X-User-Id", DESIGNER))
                .andExpect(status().isOk());

        assertThat(defRepo.findById(def.getId()).orElseThrow().getStatus())
                .as("用過但已結束的表單必須封存得掉，否則生命週期永遠不會結束")
                .isEqualTo("archived");
    }

    @Test
    @DisplayName("未被任何案件使用的 published 表單 → archive 200（既有行為）")
    void archiveSucceedsForUnusedPublishedForm() throws Exception {
        FormDefinition def = saveForm("published");
        assertThat(dataRepo.existsByFormDefinitionId(def.getId()))
                .as("前置條件：這份表單必須沒有任何表單資料").isFalse();

        mockMvc.perform(post("/api/forms/{id}/archive", def.getId())
                        .header("X-User-Id", DESIGNER))
                .andExpect(status().isOk());

        assertThat(defRepo.findById(def.getId()).orElseThrow().getStatus())
                .isEqualTo("archived");
    }

    // ── delete ────────────────────────────────────────────────────

    @Test
    @DisplayName("draft 有表單資料指向它 → delete 409（避免孤兒資料）")
    void deleteDraftIs409WhenFormDataPointsToIt() throws Exception {
        FormDefinition def = saveForm("draft");
        String pid = startCase();
        finishCase(pid);
        saveFormData(def.getId(), pid);

        assertThat(dataRepo.existsByFormDefinitionId(def.getId()))
                .as("前置條件：必須真的有一筆表單資料指向這份 draft").isTrue();

        mockMvc.perform(delete("/api/forms/{id}", def.getId())
                        .header("X-User-Id", DESIGNER))
                .andExpect(status().isConflict());

        assertThat(defRepo.findById(def.getId()))
                .as("409 之後不得刪除 —— 否則擋了等於沒擋")
                .isPresent();
    }

    @Test
    @DisplayName("draft 無表單資料 → delete 200（既有合法路徑不得被擋）")
    void deleteDraftSucceedsWhenNoFormData() throws Exception {
        FormDefinition def = saveForm("draft");

        mockMvc.perform(delete("/api/forms/{id}", def.getId())
                        .header("X-User-Id", DESIGNER))
                .andExpect(status().isOk());

        assertThat(defRepo.findById(def.getId()))
                .as("沒有任何資料指向的 draft 必須刪得掉")
                .isEmpty();
    }
}
