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
        // ⚠️ fixture 刻意全用 ASCII。
        // name 與 subject_template 是 VARCHAR，而 DB 定序為
        // SQL_Latin1_General_CP1_CI_AS → 中文會被靜默換成問號。
        // 若受害者與攻擊者的值都是中文，兩者都會變成 "????" 而無法區分，
        // 這個測試就驗不到任何東西（見 chineseSubjectIsCurrentlyCorrupted）。
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
    @DisplayName("⚠️ 已知缺陷：通知模板的中文主旨被靜默損壞（NVARCHAR 全面修復的變更偵測點）")
    void chineseSubjectIsCurrentlyCorrupted() {
        // subject_template／name 是 VARCHAR，DB 定序為 Latin1
        // → 中文寫入時被換成問號，且不報任何錯誤。
        // 對一個繁中系統而言，這代表「所有通知信的主旨都是亂碼」。
        // 屬全 schema 系統性 NVARCHAR 問題的一部分，範圍待決策 ——
        // 此處斷言現況，修復後應改為 isEqualTo 原文。
        NotifyTemplate t = new NotifyTemplate();
        t.setName("中文模板名稱");
        t.setChannel("EMAIL");
        t.setSubjectTemplate("【BPM】您有新的待辦事項");
        t.setBodyTemplate("內容放在 NVARCHAR(MAX)，這裡是正常的");
        t = templateRepo.save(t);

        NotifyTemplate reloaded = templateRepo.findById(t.getId()).orElseThrow();
        assertThat(reloaded.getSubjectTemplate())
                .as("現況：VARCHAR 欄位的中文已損壞。NVARCHAR 修復後改為 "
                        + "isEqualTo(\"【BPM】您有新的待辦事項\")")
                .contains("?");
        assertThat(reloaded.getBodyTemplate())
                .as("對照組：bodyTemplate 是 NVARCHAR(MAX)，中文正常")
                .isEqualTo("內容放在 NVARCHAR(MAX)，這裡是正常的");
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
