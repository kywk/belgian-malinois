package com.bpm.core.form;

import com.bpm.core.form.model.FormDefinition;
import com.bpm.core.form.repository.FormDefinitionRepository;
import org.junit.jupiter.api.DisplayName;
import com.bpm.core.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 已發布表單的改版路徑（security-audit P1-12）。
 *
 * <p><b>問題。</b>這是低程式碼平台的核心功能缺口：
 * <ul>
 *   <li>{@code create()} 無條件 {@code setVersion(1)}，而 {@code (formKey, version)}
 *       有唯一約束 → 對既有 formKey 重新 POST 直接撞約束。</li>
 *   <li>published 列不能 update（守衛擋掉）、不能 delete。</li>
 *   <li>因此 <b>data.sql 種下的四張表單全部是 version=1／published →
 *       透過 API 完全不可修改</b>。想改只能呼叫 publish() 產生一份內容完全
 *       相同的 v2，再也沒有辦法把新的 schemaJson 放進去。</li>
 * </ul>
 * 這直接堵死「讓業務人員自行設計、維運流程與表單」的產品目標。
 *
 * <p><b>publish() 另有三個問題：</b>無狀態守衛（對 archived 呼叫會把它
 * <b>復活</b>；對 published 重複呼叫版本號無限膨脹）；<b>一次 publish 產生
 * 兩筆 published</b>（clone 到 v2，又把來源 draft 也標 published）→
 * UI 出現重複表單；{@code findMaxVersion()} 讀寫非原子。
 */
class FormRevisionTest extends IntegrationTestBase {

    @Autowired
    private FormDefinitionRepository defRepo;

    private String uniqueKey() {
        return "rev-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private FormDefinition publishedForm(String formKey, int version) {
        FormDefinition d = new FormDefinition();
        d.setFormKey(formKey);
        d.setName("原始表單");
        d.setVersion(version);
        d.setStatus("published");
        d.setSchemaJson("{\"fields\":[{\"id\":\"v" + version + "\"}]}");
        return defRepo.save(d);
    }

    private List<FormDefinition> versionsOf(String formKey) {
        return defRepo.findAll().stream()
                .filter(d -> formKey.equals(d.getFormKey()))
                .sorted(java.util.Comparator.comparing(FormDefinition::getVersion))
                .toList();
    }

    @Test
    @DisplayName("已發布的表單必須有改版路徑（改動前完全不可修改）")
    void publishedFormCanBeRevised() throws Exception {
        String key = uniqueKey();
        publishedForm(key, 1);

        // 建立下一版 draft
        var res = mockMvc.perform(post("/api/forms/{formKey}/revisions", key)
                        .header("X-User-Id", "admin001"))
                .andExpect(status().isOk())
                .andReturn();
        String body = res.getResponse().getContentAsString();
        assertThat(body).contains("\"version\":2").contains("\"status\":\"draft\"");

        String draftId = body.replaceAll(".*\"id\":\"([^\"]*)\".*", "$1");

        // 新的 schemaJson 必須放得進去 —— 這正是改動前做不到的事
        mockMvc.perform(put("/api/forms/{id}", draftId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"改版後的表單\","
                                + "\"schemaJson\":\"{\\\"fields\\\":[{\\\"id\\\":\\\"newField\\\"}]}\"}"))
                .andExpect(status().isOk());

        // 發布
        mockMvc.perform(post("/api/forms/{id}/publish", draftId))
                .andExpect(status().isOk());

        var all = versionsOf(key);
        assertThat(all).hasSize(2);
        assertThat(all.get(1).getVersion()).isEqualTo(2);
        assertThat(all.get(1).getStatus()).isEqualTo("published");
        assertThat(all.get(1).getSchemaJson()).contains("newField");
        assertThat(all.get(1).getName()).isEqualTo("改版後的表單");
        // v1 必須保持原樣（進行中案件靠版本鎖定指向它）
        assertThat(all.get(0).getSchemaJson()).contains("\"v1\"");
    }

    @Test
    @DisplayName("publish 不得產生兩筆 published（改動前 UI 會出現重複表單）")
    void publishDoesNotDuplicate() throws Exception {
        String key = uniqueKey();
        var res = mockMvc.perform(post("/api/forms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"formKey\":\"" + key + "\",\"name\":\"新表單\","
                                + "\"schemaJson\":\"{\\\"fields\\\":[]}\"}"))
                .andExpect(status().isOk()).andReturn();
        String id = res.getResponse().getContentAsString()
                .replaceAll(".*\"id\":\"([^\"]*)\".*", "$1");

        mockMvc.perform(post("/api/forms/{id}/publish", id)).andExpect(status().isOk());

        assertThat(versionsOf(key))
                .as("一次 publish 必須只留下一筆 published，"
                        + "改動前會 clone 出 v2 又把來源 draft 也標 published")
                .hasSize(1);
        assertThat(versionsOf(key).get(0).getStatus()).isEqualTo("published");
        assertThat(versionsOf(key).get(0).getVersion()).isEqualTo(1);
    }

    @Test
    @DisplayName("publish 只能對 draft 呼叫：archived 不得被復活、published 不得重複發布")
    void publishHasStateGuard() throws Exception {
        String key = uniqueKey();
        FormDefinition published = publishedForm(key, 1);

        // 對 published 重複呼叫 → 改動前版本號會無限膨脹
        mockMvc.perform(post("/api/forms/{id}/publish", published.getId()))
                .andExpect(status().isBadRequest());

        // archived 不得被 publish 復活
        published.setStatus("archived");
        defRepo.save(published);
        mockMvc.perform(post("/api/forms/{id}/publish", published.getId()))
                .andExpect(status().isBadRequest());
        assertThat(defRepo.findById(published.getId()).orElseThrow().getStatus())
                .as("archived 不得被 publish 復活").isEqualTo("archived");

        assertThat(versionsOf(key)).as("版本數不得因失敗的 publish 增加").hasSize(1);
    }

    @Test
    @DisplayName("對既有 formKey 重新 POST 必須給出明確錯誤並指向改版端點")
    void createOnExistingKeyIsRejectedClearly() throws Exception {
        String key = uniqueKey();
        publishedForm(key, 1);

        mockMvc.perform(post("/api/forms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"formKey\":\"" + key + "\",\"name\":\"重複\","
                                + "\"schemaJson\":\"{}\"}"))
                .andExpect(status().isBadRequest());

        assertThat(versionsOf(key)).hasSize(1);
    }

    @Test
    @DisplayName("同一個 formKey 同時只能有一份未發布的 draft")
    void onlyOneOpenDraftPerFormKey() throws Exception {
        String key = uniqueKey();
        publishedForm(key, 1);

        mockMvc.perform(post("/api/forms/{formKey}/revisions", key)
                        .header("X-User-Id", "admin001"))
                .andExpect(status().isOk());
        // 第二次應被擋下，否則會出現兩份互相覆蓋的 draft
        mockMvc.perform(post("/api/forms/{formKey}/revisions", key)
                        .header("X-User-Id", "admin001"))
                .andExpect(status().isConflict());

        assertThat(versionsOf(key)).hasSize(2);
    }

    @Test
    @DisplayName("對不存在的 formKey 建立改版必須回 404")
    void revisionOfUnknownKeyIs404() throws Exception {
        mockMvc.perform(post("/api/forms/{formKey}/revisions", "no-such-form")
                        .header("X-User-Id", "admin001"))
                .andExpect(status().isNotFound());
    }
}
