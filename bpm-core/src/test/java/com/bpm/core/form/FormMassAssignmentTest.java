package com.bpm.core.form;

import com.bpm.core.form.model.FormData;
import com.bpm.core.form.model.FormDefinition;
import com.bpm.core.form.repository.FormDataRepository;
import com.bpm.core.form.repository.FormDefinitionRepository;
import org.junit.jupiter.api.DisplayName;
import com.bpm.core.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.flowable.engine.RuntimeService;
import org.springframework.http.MediaType;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
 *
 * <p>⚠️ 2026-09-29 起 {@code POST /api/forms} 另外需要權限碼
 * {@code bpm:form:design}（或 {@code ROLE_ADMIN}）。這組測試因此顯式宣告
 * {@link #DESIGNER}，並在每個測試裡斷言請求<b>確實被受理</b> ——
 * 否則守衛一旦擋下請求，「資料沒有被改動」這個斷言會在缺陷完全存在時
 * 照樣成立（vacuous），也就是測試對缺陷失感。
 * 授權矩陣本身由 {@code FormDesignAuthorizationTest} 驗證。
 */
class FormMassAssignmentTest extends IntegrationTestBase {

    @Autowired
    private FormDefinitionRepository defRepo;

    @Autowired
    private FormDataRepository dataRepo;

    @Autowired
    private RuntimeService runtimeService;

    /**
     * 持有 {@code bpm:form:design} 的身分（MockPermController 的 dev fixture）。
     *
     * <p>⚠️ 2026-09-29 起 {@code POST /api/forms} 需要這個權限碼（或
     * {@code ROLE_ADMIN}），而 {@code TestGatewayMockMvcCustomizer} 的預設身分
     * 是 user001 —— 刻意選了一個沒有任何權限的人。
     *
     * <p>這裡若不補上身分，這個測試會變成<b>對缺陷無感的空斷言</b>：
     * 請求被 403 擋下 → 受害表單當然沒有被改動 → 斷言照樣成立。
     * 上一輪 {@code FormMassAssignmentTest.submitFormDataCannotOverwriteExisting}
     * 就踩過同一個坑（用不存在的 processInstanceId，被守衛先擋）。
     * 因此下面每個測試都額外斷言「攻擊者自己的那份<b>有</b>被建立」——
     * 那才是讓「請求真的送到了 controller」的證據。
     */
    private static final String DESIGNER = "mgr001";

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

        var res = mockMvc.perform(post("/api/forms")
                .header("X-User-Id", DESIGNER)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":\"" + victimId + "\","
                        + "\"formKey\":\"attacker-form\","
                        + "\"name\":\"被改掉的表單\","
                        + "\"schemaJson\":\"{\\\"fields\\\":[{\\\"id\\\":\\\"injected\\\"}]}\"}"))
                .andReturn();

        // ⚠️ 非空驗證：先確認請求真的進到了 create()。
        // 少了這一步，下面那些「沒有被改動」的斷言會在守衛把整個請求擋掉時
        // 也一樣成立 —— 測試就再也無法分辨缺陷修好了還是功能被關掉了。
        assertThat(res.getResponse().getStatus())
                .as("前置條件：具備 bpm:form:design 的請求應被受理，"
                        + "否則下面的斷言對 mass-assignment 缺陷無感")
                .isEqualTo(200);

        FormDefinition after = defRepo.findById(victimId).orElseThrow();
        assertThat(after.getSchemaJson())
                .as("既有表單的 schemaJson 不得被建立請求改寫")
                .contains("original").doesNotContain("injected");
        assertThat(after.getStatus())
                .as("已發布的狀態不得被打回 draft —— 那會繞過『只有 draft 能改』的守衛")
                .isEqualTo("published");
        assertThat(after.getVersion()).as("版本不得被重設").isEqualTo(7);
        assertThat(after.getName()).as("名稱不得被改寫").isEqualTo("原始表單");

        // 攻擊者自己的表單必須真的被建立（而不是覆寫）
        assertThat(defRepo.findAll())
                .as("body 的 id 被歸零後應新增一列，attacker-form 必須真的存在")
                .anySatisfy(d -> {
                    assertThat(d.getFormKey()).isEqualTo("attacker-form");
                    assertThat(d.getId()).isNotEqualTo(victimId);
                });
    }

    @Test
    @DisplayName("POST /api/form-data 不得以 body 的 id 覆寫他人已送出的表單資料")
    void submitFormDataCannotOverwriteExisting() throws Exception {
        // ⚠️ victim 與攻擊者必須掛在「攻擊者自己是參與者」的案件上（#72）。
        //
        // 改動前這裡用字面值 "proc-victim"（不存在的流程實例）是可行的 ——
        // 當時 POST 完全沒有授權檢查。#72 之後守衛會先擋下，
        // 資料「沒有被改動」這個斷言就會在守衛生效的情況下也成立，
        // 測試變成對缺陷無感的空斷言（vacuous）。
        //
        // 所以改用真實實例：守住「即使攻擊者對這個案件有合法權限，
        // 也不能靠 body 的 id 覆寫別人的資料列」這個真正的主張。
        String pid = runtimeService.startProcessInstanceByKey("leave-approval",
                Map.of("initiator", "user001", "leaveType", "annual", "days", 1)).getId();

        FormData victim = new FormData();
        victim.setFormDefinitionId("def-1");
        victim.setProcessInstanceId(pid);
        victim.setSubmittedBy("user002");
        victim.setDataJson("{\"amount\":100}");
        victim = dataRepo.save(victim);
        String victimId = victim.getId();

        mockMvc.perform(post("/api/form-data")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":\"" + victimId + "\","
                        + "\"formDefinitionId\":\"def-1\","
                        + "\"processInstanceId\":\"" + pid + "\","
                        + "\"submittedBy\":\"user001\","
                        + "\"dataJson\":\"{\\\"amount\\\":999999}\"}"));

        FormData after = dataRepo.findById(victimId).orElseThrow();
        assertThat(after.getDataJson())
                .as("他人已送出的表單資料不得被建立請求覆寫（submittedAt 不可更新 → 篡改無跡）")
                .contains("100").doesNotContain("999999");
        assertThat(after.getSubmittedBy()).isEqualTo("user002");
        assertThat(after.getProcessInstanceId()).isEqualTo(pid);
    }

    @Test
    @DisplayName("正常建立仍須可用，且伺服器自行產生 id")
    void normalCreateStillWorks() throws Exception {
        long before = defRepo.count();
        mockMvc.perform(post("/api/forms")
                        .header("X-User-Id", DESIGNER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"formKey\":\"brand-new\",\"name\":\"新表單\","
                                + "\"schemaJson\":\"{\\\"fields\\\":[]}\"}"))
                .andExpect(status().isOk());
        assertThat(defRepo.count()).as("應新增一筆").isEqualTo(before + 1);
        assertThat(defRepo.findAll())
                .anySatisfy(d -> assertThat(d.getFormKey()).isEqualTo("brand-new"));
    }
}
