package com.bpm.core.security;

import com.bpm.core.model.NotifyTemplate;
import com.bpm.core.repository.NotifyTemplateRepository;
import com.bpm.core.support.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * mass-assignment：用 JPA entity 當 {@code @RequestBody}（security-audit P0-4）。
 *
 * <p>Spring Data 的 {@code save()} 以 {@code id == null} 判斷 isNew，
 * {@code id} 非 null 時走 {@code em.merge()} → 變成 <b>UPDATE</b>。
 *
 * <p>bpm-core 的受害端點中，{@code POST /api/admin/notify-templates} 最嚴重：
 * 覆寫既有郵件模板即可植入釣魚連結，而該 controller <b>零稽核</b>
 * → 沒有任何記錄顯示模板被改過。而且 {@code /api/admin/**} 目前完全無認證
 * （R-18），任何能連到 nginx 的人都能做這件事。
 */
class MassAssignmentTest extends IntegrationTestBase {

    @Autowired
    private NotifyTemplateRepository templateRepo;

    @Test
    @DisplayName("POST /api/admin/notify-templates 不得以 body 的 id 覆寫既有模板")
    void createTemplateCannotOverwriteExisting() throws Exception {
        // fixture 用 ASCII 以便清楚區分受害者與攻擊者的值。
        // （在 NVARCHAR 全面修復之前，這裡是「必須」用 ASCII —— 中文會被
        //   靜默換成問號，兩邊都變 "????" 就驗不到任何東西。現在只是偏好。）
        NotifyTemplate victim = new NotifyTemplate();
        victim.setName("ORIGINAL-TEMPLATE");
        victim.setChannel("EMAIL");
        victim.setSubjectTemplate("BPM-TASK-ASSIGNED");
        victim.setBodyTemplate("Please visit https://bpm.internal/tasks");
        victim = templateRepo.save(victim);
        String victimId = victim.getId();

        mockMvc.perform(post("/api/admin/notify-templates")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":\"" + victimId + "\","
                        + "\"name\":\"PHISHING\",\"channel\":\"EMAIL\","
                        + "\"subjectTemplate\":\"VERIFY-YOUR-ACCOUNT\","
                        + "\"bodyTemplate\":\"Login at https://evil.example/login\"}"));

        NotifyTemplate after = templateRepo.findById(victimId).orElseThrow();
        assertThat(after.getBodyTemplate())
                .as("既有模板不得被建立請求改寫 —— 這條路可植入釣魚連結且無稽核")
                .contains("bpm.internal").doesNotContain("evil.example");
        assertThat(after.getSubjectTemplate())
                .as("主旨不得被改寫").isEqualTo("BPM-TASK-ASSIGNED");
        assertThat(after.getName())
                .as("名稱不得被改寫").isEqualTo("ORIGINAL-TEMPLATE");
    }

    @Test
    @DisplayName("通知模板的中文主旨必須完整保存（NVARCHAR 全面修復後）")
    void chineseSubjectIsPreserved() {
        // 這個測試原本斷言「中文主旨已損壞」，作為 NVARCHAR 全面修復的
        // 變更偵測點。修復完成後依當初註明的方式反轉為斷言正確值。
        //
        // 對繁中系統而言這條路徑的後果最直接：subject_template 損壞
        // 等於所有通知信的主旨都是亂碼。
        NotifyTemplate t = new NotifyTemplate();
        t.setName("中文模板名稱");
        t.setChannel("EMAIL");
        t.setSubjectTemplate("【BPM】您有新的待辦事項");
        t.setBodyTemplate("內容放在 NVARCHAR(MAX)，這裡一直是正常的");
        t = templateRepo.save(t);

        NotifyTemplate reloaded = templateRepo.findById(t.getId()).orElseThrow();
        assertThat(reloaded.getSubjectTemplate())
                .as("中文主旨必須完整保存")
                .isEqualTo("【BPM】您有新的待辦事項");
        assertThat(reloaded.getName()).isEqualTo("中文模板名稱");
        assertThat(reloaded.getBodyTemplate())
                .isEqualTo("內容放在 NVARCHAR(MAX)，這裡一直是正常的");
    }

    @Test
    @DisplayName("正常建立模板仍須可用，且 id 由伺服器產生")
    void normalCreateStillWorks() throws Exception {
        long before = templateRepo.count();
        mockMvc.perform(post("/api/admin/notify-templates")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"新模板\",\"channel\":\"EMAIL\","
                        + "\"subjectTemplate\":\"主旨\",\"bodyTemplate\":\"內容\"}"));
        assertThat(templateRepo.count()).isEqualTo(before + 1);
        assertThat(templateRepo.findAll())
                .anySatisfy(t -> {
                    assertThat(t.getName()).isNotNull();
                    assertThat(t.getId()).as("id 必須由伺服器產生").isNotBlank();
                });
    }

    @Test
    @DisplayName("回應仍必須包含 id（READ_ONLY 只擋反序列化，不擋序列化）")
    void responseStillExposesId() throws Exception {
        var res = mockMvc.perform(post("/api/admin/notify-templates")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"檢查回應\",\"channel\":\"EMAIL\","
                        + "\"subjectTemplate\":\"s\",\"bodyTemplate\":\"b\"}"))
                .andReturn();
        assertThat(res.getResponse().getContentAsString())
                .as("前端需要新建物件的 id，READ_ONLY 不應影響回應")
                .contains("\"id\"");
    }
}
