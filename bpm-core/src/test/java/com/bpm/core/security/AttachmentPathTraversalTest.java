package com.bpm.core.security;

import com.bpm.core.support.IntegrationTestBase;
import org.flowable.engine.RuntimeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 附件上傳的路徑穿越防護測試（security-audit P0-1）。
 *
 * <p><b>為什麼這是「先修這 5 個」的第一項。</b>它是整份審查中唯一有明確
 * RCE 路徑的項目：
 * <ol>
 *   <li>{@code processInstanceId} 是 {@code @RequestParam}、零驗證，
 *       直接 {@code uploadDir.resolve(...)} → 可建立任意目錄並寫檔。</li>
 *   <li>{@code originalFilename} 由 client 完全控制（Servlet multipart 不剝除
 *       路徑），{@code "uuid_" + "/../../../x"} 正規化後仍可逐層上跳。</li>
 *   <li>{@code transferTo(Path)} 預設是 CREATE + TRUNCATE_EXISTING → 可覆寫既有檔案。</li>
 *   <li>Dockerfile 無 {@code USER} 指令 → 以 root 執行，{@code WORKDIR /app}
 *       → 可覆寫 {@code /app/app.jar}，下次重啟即 RCE。</li>
 * </ol>
 *
 * <p>本測試針對 (1)(2)(3)。(4) 屬 Dockerfile，由同一個 commit 修復但無法在
 * 單元／整合測試層驗證。
 */
class AttachmentPathTraversalTest extends IntegrationTestBase {

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private com.bpm.core.repository.FileAttachmentRepository attachmentRepo;

    @Value("${bpm.upload.dir:./uploads}")
    private String uploadDirProp;

    private String startInstance() {
        return runtimeService.startProcessInstanceByKey("leave-approval",
                Map.of("initiator", "user001", "leaveType", "annual", "days", 1)).getId();
    }

    /** uploadDir 之外是否出現了不該出現的檔案。 */
    private static boolean escaped(Path uploadDir, String marker) throws Exception {
        Path parent = uploadDir.toAbsolutePath().normalize().getParent();
        if (parent == null || !Files.exists(parent)) return false;
        try (Stream<Path> s = Files.walk(parent, 3)) {
            return s.anyMatch(p -> p.getFileName() != null
                    && p.getFileName().toString().contains(marker)
                    && !p.toAbsolutePath().normalize()
                            .startsWith(uploadDir.toAbsolutePath().normalize()));
        }
    }

    @Test
    @DisplayName("processInstanceId 帶路徑穿越必須被拒絕，且不得在 uploadDir 之外建立任何檔案")
    void traversalInProcessInstanceIdIsRejected() throws Exception {
        Path uploadDir = Path.of(uploadDirProp);
        MockMultipartFile file = new MockMultipartFile(
                "file", "payload.txt", "text/plain", "PWNED".getBytes());

        mockMvc.perform(multipart("/api/attachments")
                        .file(file)
                        .param("processInstanceId", "../../../../etc/cron.d")
                        .param("uploadedBy", "attacker"))
                .andExpect(result -> assertThat(result.getResponse().getStatus())
                        .as("路徑穿越必須被拒絕（4xx），不得靜默接受")
                        .isBetween(400, 499));

        assertThat(escaped(uploadDir, "payload"))
                .as("uploadDir 之外不得出現任何檔案").isFalse();
    }

    @Test
    @DisplayName("不存在的 processInstanceId 必須被拒絕（避免任意建目錄與列舉）")
    void unknownProcessInstanceIsRejected() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "a.txt", "text/plain", "x".getBytes());

        mockMvc.perform(multipart("/api/attachments")
                        .file(file)
                        .param("processInstanceId", "00000000-0000-0000-0000-000000000000")
                        .param("uploadedBy", "user001"))
                .andExpect(result -> assertThat(result.getResponse().getStatus())
                        .as("不存在的流程實例不得接受附件").isBetween(400, 499));
    }

    @Test
    @DisplayName("檔名帶路徑穿越時，實際落地路徑必須仍在 uploadDir 內")
    void traversalInFilenameCannotEscape() throws Exception {
        String pid = startInstance();
        Path uploadDir = Path.of(uploadDirProp);

        // Servlet multipart 不會剝除路徑，因此 client 可以送這種檔名。
        MockMultipartFile file = new MockMultipartFile(
                "file", "../../../../tmp/escape-marker.txt", "text/plain", "PWNED".getBytes());

        mockMvc.perform(multipart("/api/attachments")
                        .file(file)
                        .param("processInstanceId", pid)
                        .param("uploadedBy", "user001")
                        // 附件現在有物件層授權（P1-6）：必須是案件關係人。
                        // user001 是 startInstance() 的發起人。
                        .header("X-User-Id", "user001"))
                .andExpect(status().isOk());

        assertThat(escaped(uploadDir, "escape-marker"))
                .as("即使檔名帶 ../，檔案也必須留在 uploadDir 內").isFalse();
    }

    @Test
    @DisplayName("儲存檔名不得採用 client 提供的值；原始檔名只存在 DB")
    void storedNameMustNotUseClientFilename() throws Exception {
        String pid = startInstance();

        MockMultipartFile file = new MockMultipartFile(
                "file", "機密報告.txt", "text/plain", "data".getBytes());

        var res = mockMvc.perform(multipart("/api/attachments")
                        .file(file)
                        .param("processInstanceId", pid)
                        .param("uploadedBy", "user001")
                        // 附件現在有物件層授權（P1-6）：必須是案件關係人。
                        // user001 是 startInstance() 的發起人。
                        .header("X-User-Id", "user001"))
                .andExpect(status().isOk())
                .andReturn();

        String body = res.getResponse().getContentAsString();
        // fileName（顯示用）必須保留原始檔名
        assertThat(body).as("原始檔名必須保留在 DB 供顯示").contains("機密報告.txt");
        // 回應刻意不再包含 filePath（P1-6：不外洩容器內實體路徑）
        assertThat(body).as("回應不得外洩實體路徑").doesNotContain("filePath");

        // 實際落地路徑改由 DB 驗證：檔名是 client 完全控制的輸入，
        // 不應出現在檔案系統路徑上。
        assertThat(attachmentRepo.findByProcessInstanceIdOrderByUploadedAtDesc(pid))
                .isNotEmpty()
                .allSatisfy(att -> assertThat(att.getFilePath())
                        .as("實際儲存路徑不得使用 client 提供的檔名")
                        .doesNotContain("機密報告"));
    }

    @Test
    @DisplayName("正常上傳仍須可用（不得因為加了驗證而破壞功能）")
    void normalUploadStillWorks() throws Exception {
        String pid = startInstance();

        mockMvc.perform(multipart("/api/attachments")
                        .file(new MockMultipartFile("file", "spec.pdf",
                                "application/pdf", "PDF-CONTENT".getBytes()))
                        .param("processInstanceId", pid)
                        .param("uploadedBy", "user001")
                        // 附件現在有物件層授權（P1-6）：必須是案件關係人。
                        // user001 是 startInstance() 的發起人。
                        .header("X-User-Id", "user001"))
                .andExpect(status().isOk());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/attachments").param("processInstanceId", pid)
                        .header("X-User-Id", "user001"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$[0].fileName").value("spec.pdf"));
    }
}
