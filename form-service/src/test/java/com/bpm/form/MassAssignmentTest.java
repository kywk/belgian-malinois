package com.bpm.form;

import com.bpm.form.model.FormData;
import com.bpm.form.model.FormDefinition;
import com.bpm.form.repository.FormDataRepository;
import com.bpm.form.repository.FormDefinitionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * mass-assignment：用 JPA entity 當 {@code @RequestBody}（security-audit P0-4）。
 *
 * <p>Spring Data 的 {@code save()} 以 {@code id == null} 判斷 isNew，
 * {@code id} 非 null 時走 {@code em.merge()} → 變成 <b>UPDATE</b>。
 * 因此只要在建立請求的 body 裡夾帶別人的 {@code id}，
 * 「新增」就會變成「覆寫任意資料列」。
 *
 * <p>form-service 的兩個受害端點：
 * <ul>
 *   <li>{@code POST /api/forms}：改寫已發布表單的 schemaJson，且 create()
 *       會強制把 status 設回 draft → 繞過「只有 draft 能改」的守衛，
 *       之後連 delete 守衛也一併失效。</li>
 *   <li>{@code POST /api/form-data}：覆寫他人案件已送出的表單資料，
 *       而 submittedAt 是 {@code updatable = false} → <b>篡改無跡</b>。</li>
 * </ul>
 */
@AutoConfigureMockMvc
class MassAssignmentTest extends FormServiceIntegrationTestBase {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FormDefinitionRepository defRepo;

    @Autowired
    private FormDataRepository dataRepo;

    @Test
    @DisplayName("POST /api/forms 不得以 body 的 id 覆寫既有（尤其是已發布的）表單")
    void createFormCannotOverwriteExisting() throws Exception {
        FormDefinition victim = new FormDefinition();
        victim.setFormKey("victim-form");
        victim.setName("原始表單");
        victim.setVersion(7);
        victim.setStatus("published");
        victim.setSchemaJson("{\"fields\":[{\"id\":\"original\"}]}");
        victim = defRepo.save(victim);
        String victimId = victim.getId();

        mockMvc.perform(post("/api/forms")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":\"" + victimId + "\","
                        + "\"formKey\":\"attacker-form\","
                        + "\"name\":\"被改掉的表單\","
                        + "\"schemaJson\":\"{\\\"fields\\\":[{\\\"id\\\":\\\"injected\\\"}]}\"}"));

        FormDefinition after = defRepo.findById(victimId).orElseThrow();
        assertThat(after.getSchemaJson())
                .as("既有表單的 schemaJson 不得被建立請求改寫")
                .contains("original").doesNotContain("injected");
        assertThat(after.getStatus())
                .as("已發布的狀態不得被打回 draft —— 那會繞過『只有 draft 能改』的守衛")
                .isEqualTo("published");
        assertThat(after.getVersion()).as("版本不得被重設").isEqualTo(7);
        assertThat(after.getName()).as("名稱不得被改寫").isEqualTo("原始表單");
    }

    @Test
    @DisplayName("POST /api/form-data 不得以 body 的 id 覆寫他人已送出的表單資料")
    void submitFormDataCannotOverwriteExisting() throws Exception {
        FormData victim = new FormData();
        victim.setFormDefinitionId("def-1");
        victim.setProcessInstanceId("proc-victim");
        victim.setSubmittedBy("user001");
        victim.setDataJson("{\"amount\":100}");
        victim = dataRepo.save(victim);
        String victimId = victim.getId();

        mockMvc.perform(post("/api/form-data")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":\"" + victimId + "\","
                        + "\"formDefinitionId\":\"def-1\","
                        + "\"processInstanceId\":\"proc-attacker\","
                        + "\"submittedBy\":\"attacker\","
                        + "\"dataJson\":\"{\\\"amount\\\":999999}\"}"));

        FormData after = dataRepo.findById(victimId).orElseThrow();
        assertThat(after.getDataJson())
                .as("他人已送出的表單資料不得被建立請求覆寫（submittedAt 不可更新 → 篡改無跡）")
                .contains("100").doesNotContain("999999");
        assertThat(after.getSubmittedBy()).isEqualTo("user001");
        assertThat(after.getProcessInstanceId()).isEqualTo("proc-victim");
    }

    @Test
    @DisplayName("正常建立仍須可用，且伺服器自行產生 id")
    void normalCreateStillWorks() throws Exception {
        long before = defRepo.count();
        mockMvc.perform(post("/api/forms")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"formKey\":\"brand-new\",\"name\":\"新表單\","
                        + "\"schemaJson\":\"{\\\"fields\\\":[]}\"}"));
        assertThat(defRepo.count()).as("應新增一筆").isEqualTo(before + 1);
        assertThat(defRepo.findAll())
                .anySatisfy(d -> assertThat(d.getFormKey()).isEqualTo("brand-new"));
    }
}
