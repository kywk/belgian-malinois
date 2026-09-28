package com.bpm.core.security;

import com.bpm.core.support.IntegrationTestBase;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 附件的物件層授權（security-audit P1-6）。
 *
 * <p><b>問題。</b>{@code download(@PathVariable String id)} 只做 {@code findById}，
 * <b>沒有「呼叫者是否為該案件關係人」的檢查</b>。而 Flowable 的
 * processInstanceId 可被枚舉 → 可列舉全公司案件的附件並下載。
 * {@code list()} 還把 {@code filePath} 實體路徑一起回傳。
 * 三個端點（上傳／下載／列表）都零稽核 —— 誰下載了薪資單，沒有紀錄。
 *
 * <p>審查特別指出「即使補上認證，程式碼裡也<b>沒有可掛授權判斷的位置</b>」。
 * 本次的重點就是建立那個位置。
 *
 * <p>⚠️ <b>限制</b>：呼叫者身分仍取自 {@code X-User-Id} 標頭，也就是仍可
 * 自報（R-01 尚未完成）。因此這不是完整的安全邊界 —— 它關掉的是「枚舉」
 * 這條路，並且把授權判斷點建立起來，讓 R-01 完成後只需要換掉身分來源。
 */
class AttachmentAuthorizationTest extends IntegrationTestBase {

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private TaskService taskService;

    /** 由 user001 發起、mgr001 審核的案件。 */
    private String startCase() {
        return runtimeService.startProcessInstanceByKey("leave-approval",
                Map.of("initiator", "user001", "leaveType", "annual", "days", 1)).getId();
    }

    private String upload(String pid, String who) throws Exception {
        var res = mockMvc.perform(multipart("/api/attachments")
                        .file(new MockMultipartFile("file", "薪資單.pdf",
                                "application/pdf", "SALARY".getBytes()))
                        .param("processInstanceId", pid)
                        .param("uploadedBy", who)
                        .header("X-User-Id", who))
                .andExpect(status().isOk())
                .andReturn();
        String body = res.getResponse().getContentAsString();
        return body.replaceAll(".*\"id\":\"([^\"]*)\".*", "$1");
    }

    @Test
    @DisplayName("案件關係人可以上傳、列出與下載附件")
    void participantsCanAccess() throws Exception {
        String pid = startCase();
        String attId = upload(pid, "user001");   // 發起人

        // 發起人
        mockMvc.perform(get("/api/attachments").param("processInstanceId", pid)
                        .header("X-User-Id", "user001"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/attachments/{id}/download", attId)
                        .header("X-User-Id", "user001"))
                .andExpect(status().isOk());

        // 當前簽核人
        mockMvc.perform(get("/api/attachments/{id}/download", attId)
                        .header("X-User-Id", "mgr001"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("非關係人不得下載附件（擋掉枚舉全公司附件）")
    void nonParticipantCannotDownload() throws Exception {
        String pid = startCase();
        String attId = upload(pid, "user001");

        mockMvc.perform(get("/api/attachments/{id}/download", attId)
                        .header("X-User-Id", "user005"))
                .andExpect(result -> assertThat(result.getResponse().getStatus())
                        .as("user005 與此案件無關，不得下載其附件")
                        .isIn(403, 404));
    }

    @Test
    @DisplayName("非關係人不得列出案件附件")
    void nonParticipantCannotList() throws Exception {
        String pid = startCase();
        upload(pid, "user001");

        mockMvc.perform(get("/api/attachments").param("processInstanceId", pid)
                        .header("X-User-Id", "user005"))
                .andExpect(result -> assertThat(result.getResponse().getStatus())
                        .as("非關係人不得列出案件附件").isIn(403, 404));
    }

    @Test
    @DisplayName("非關係人不得上傳附件到他人案件")
    void nonParticipantCannotUpload() throws Exception {
        String pid = startCase();

        mockMvc.perform(multipart("/api/attachments")
                        .file(new MockMultipartFile("file", "inject.pdf",
                                "application/pdf", "X".getBytes()))
                        .param("processInstanceId", pid)
                        .param("uploadedBy", "user005")
                        .header("X-User-Id", "user005"))
                .andExpect(result -> assertThat(result.getResponse().getStatus())
                        .as("非關係人不得把附件塞進他人案件").isIn(403, 404));
    }

    @Test
    @DisplayName("回應不得外洩檔案系統的實體路徑")
    void responsesMustNotLeakFilePath() throws Exception {
        String pid = startCase();
        upload(pid, "user001");

        var res = mockMvc.perform(get("/api/attachments").param("processInstanceId", pid)
                        .header("X-User-Id", "user001"))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(res.getResponse().getContentAsString())
                .as("filePath 是容器內的實體路徑，對呼叫端沒有用途，"
                        + "只會洩漏部署結構")
                .doesNotContain("filePath")
                .doesNotContain("/app/uploads");
    }

    @Test
    @DisplayName("附件的上傳與下載必須留下稽核事件")
    void attachmentAccessIsAudited() throws Exception {
        truncateAuditLog();
        String pid = startCase();
        String attId = upload(pid, "user001");
        mockMvc.perform(get("/api/attachments/{id}/download", attId)
                        .header("X-User-Id", "mgr001"))
                .andExpect(status().isOk());

        java.util.List<String> ops = java.util.List.of();
        for (int i = 0; i < 50; i++) {
            ops = auditOps();
            if (ops.size() >= 2) break;
            Thread.sleep(100);
        }
        assertThat(ops)
                .as("誰上傳、誰下載了附件必須有紀錄 —— 改動前三個端點全無稽核")
                .contains("FORM_SUBMIT", "DATA_ACCESS");
    }

    private static java.util.List<String> auditOps() {
        java.util.List<String> out = new java.util.ArrayList<>();
        withAuditConnection(c -> {
            try (var st = c.createStatement();
                 var rs = st.executeQuery("SELECT operation_type FROM bpm_audit_log ORDER BY id")) {
                while (rs.next()) out.add(rs.getString(1));
            }
        });
        return out;
    }
}
