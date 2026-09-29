package com.bpm.core.audit;

import com.bpm.core.audit.model.OperationType;
import com.bpm.core.model.ExternalSystem;
import com.bpm.core.repository.ExternalSystemRepository;
import com.bpm.core.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 組態變更與稽核查詢必須真的寫進 bpm_audit_db（security-audit P2-4）。
 *
 * <h2>為什麼要另開連線直接查資料庫</h2>
 *
 * <p>斷言「controller 呼叫了 publisher」抓不到這一類缺陷 —— 本專案最嚴重的
 * 一次（commit cecdbe4）就是呼叫全都正常、交易也 commit 成功，
 * 但稽核一筆都沒寫進去。唯一可靠的驗證是另開 JDBC 連線到 bpm_audit_db
 * 確認資料列真的在那裡。
 *
 * <p>{@code AuditEventPublisher} 是 @Async，所以查詢前要等它落地。
 */
class AuditCoverageTest extends IntegrationTestBase {

    @Autowired private MockMvc mockMvc;
    @Autowired private ExternalSystemRepository externalSystemRepo;

    private final List<String> createdSystems = new ArrayList<>();

    @BeforeEach
    void clean() {
        truncateAuditLog();
    }

    @org.junit.jupiter.api.AfterEach
    void cleanupSystems() {
        createdSystems.forEach(sid -> externalSystemRepo.findBySystemId(sid)
                .ifPresent(externalSystemRepo::delete));
        createdSystems.clear();
    }

    /** 等到指定型別至少出現 n 筆，或逾時。@Async 落地需要一點時間。 */
    private List<String> awaitAuditDetails(OperationType type, int atLeast) {
        long deadline = System.currentTimeMillis() + 10_000;
        List<String> details = List.of();
        while (System.currentTimeMillis() < deadline) {
            details = auditDetails(type);
            if (details.size() >= atLeast) return details;
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return details;
    }

    private static List<String> auditDetails(OperationType type) {
        List<String> out = new ArrayList<>();
        withAuditConnection(c -> {
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT operator_id, detail FROM bpm_audit_log WHERE operation_type = '"
                                 + type.name() + "' ORDER BY id")) {
                while (rs.next()) {
                    out.add(rs.getString("operator_id") + "|" + rs.getString("detail"));
                }
            }
        });
        return out;
    }

    // ── CONFIG_CHANGE：外部系統（授權設定）────────────────────────

    @Test
    @DisplayName("外部系統的建立與授權變更必須留下軌跡與前後值")
    void externalSystemChangesAreAudited() throws Exception {
        // 這個 controller 原本有四個變更端點、零個稽核呼叫。它管的是
        // allowedProcessKeys —— 誰能從外部發起哪些流程。
        String sid = "audit-test-" + UUID.randomUUID().toString().substring(0, 8);
        createdSystems.add(sid);

        mockMvc.perform(post("/api/admin/external-systems")
                        .header("X-User-Id", "admin001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"systemId\":\"" + sid + "\",\"systemName\":\"測試\","
                                + "\"allowedProcessKeys\":\"[\\\"leave-approval\\\"]\","
                                + "\"allowedActions\":\"[\\\"start_process\\\"]\"}"))
                .andExpect(status().isOk());

        // 擴大授權範圍 —— 這是最需要留下前後值的一種變更
        mockMvc.perform(put("/api/admin/external-systems/" + sid)
                        .header("X-User-Id", "attacker001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"systemName\":\"測試\","
                                + "\"allowedProcessKeys\":\"[\\\"leave-approval\\\",\\\"purchase-approval\\\"]\","
                                + "\"allowedActions\":\"[\\\"start_process\\\"]\",\"enabled\":true}"))
                .andExpect(status().isOk());

        var details = awaitAuditDetails(OperationType.CONFIG_CHANGE, 2);
        assertThat(details).hasSizeGreaterThanOrEqualTo(2);

        assertThat(details.get(0)).contains("admin001").contains("create").contains(sid);
        assertThat(details.get(1))
                .as("update 必須記錄操作者與授權欄位的前後值，"
                        + "否則事故調查時只知道「有人改了」")
                .contains("attacker001")
                .contains("allowedProcessKeys")
                .contains("purchase-approval");
    }

    @Test
    @DisplayName("金鑰輪換必須留下軌跡，但明文金鑰絕不可進稽核庫")
    void keyRotationIsAuditedWithoutLeakingThePlaintextKey() throws Exception {
        String sid = "audit-rot-" + UUID.randomUUID().toString().substring(0, 8);
        createdSystems.add(sid);

        String createBody = mockMvc.perform(post("/api/admin/external-systems")
                        .header("X-User-Id", "admin001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"systemId\":\"" + sid + "\",\"systemName\":\"測試\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String firstKey = createBody.replaceAll(".*\"apiKey\":\"([^\"]+)\".*", "$1");

        String rotateBody = mockMvc.perform(post("/api/admin/external-systems/" + sid + "/rotate-key")
                        .header("X-User-Id", "admin001"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String newKey = rotateBody.replaceAll(".*\"apiKey\":\"([^\"]+)\".*", "$1");

        assertThat(firstKey).isNotBlank().isNotEqualTo(newKey);

        var details = awaitAuditDetails(OperationType.CONFIG_CHANGE, 2);
        String all = String.join("\n", details);

        assertThat(all).contains("rotate-key");
        assertThat(all)
                .as("明文金鑰出現在稽核紀錄裡 —— 雜湊就白做了，"
                        + "而稽核庫可查詢且保留期比金鑰生命週期長")
                .doesNotContain(firstKey)
                .doesNotContain(newKey);
    }

    @Test
    @DisplayName("重複的 systemId 必須回 409，不得變成覆寫")
    void duplicateSystemIdIsRejected() throws Exception {
        String sid = "audit-dup-" + UUID.randomUUID().toString().substring(0, 8);
        createdSystems.add(sid);
        String body = "{\"systemId\":\"" + sid + "\",\"systemName\":\"第一次\"}";

        mockMvc.perform(post("/api/admin/external-systems")
                        .header("X-User-Id", "admin001")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/admin/external-systems")
                        .header("X-User-Id", "admin001")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("body 夾帶 id 不得讓「建立」變成覆寫既有系統")
    void bodySuppliedIdCannotOverwriteAnExistingSystem() throws Exception {
        // 實測過的缺陷（P2-4 施作時發現，與 P0-4 同一個機制）：
        // ExternalSystem.id 沒有 READ_ONLY，body 帶既有 id 會讓 save() 走
        // merge → 覆寫那一列 → allowedProcessKeys 被擴大、apiKey 被輪換，
        // 也就是一次請求接管既有的外部系統。
        String sid = "audit-mass-" + UUID.randomUUID().toString().substring(0, 8);
        createdSystems.add(sid);

        String created = mockMvc.perform(post("/api/admin/external-systems")
                        .header("X-User-Id", "admin001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"systemId\":\"" + sid + "\",\"systemName\":\"受害\","
                                + "\"allowedProcessKeys\":\"[\\\"leave-approval\\\"]\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String victimId = created.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
        assertThat(victimId).isNotBlank();

        String other = "audit-mass2-" + UUID.randomUUID().toString().substring(0, 8);
        createdSystems.add(other);
        mockMvc.perform(post("/api/admin/external-systems")
                        .header("X-User-Id", "attacker")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"" + victimId + "\",\"systemId\":\"" + other + "\","
                                + "\"systemName\":\"已被覆寫\","
                                + "\"allowedProcessKeys\":\"[\\\"leave-approval\\\",\\\"purchase-approval\\\"]\"}"))
                .andExpect(status().isOk());

        var victim = externalSystemRepo.findBySystemId(sid).orElseThrow();
        assertThat(victim.getSystemName())
                .as("body 夾帶的 id 覆寫了既有系統 —— 授權被接管")
                .isEqualTo("受害");
        assertThat(victim.getAllowedProcessKeys()).doesNotContain("purchase-approval");
    }

    // ── DATA_ACCESS：查稽核本身要被稽核 ──────────────────────────

    @Test
    @DisplayName("查詢稽核紀錄本身必須留下紀錄（稽核稽核者）")
    void auditLogQueriesAreThemselvesAudited() throws Exception {
        // 稽核庫裡有全公司的請假、採購、核決金額與簽核意見。
        // 可以無痕跡地翻閱它，等於這份資料沒有存取控制的事實層面。
        mockMvc.perform(get("/api/audit-logs")
                        .header("X-User-Id", "nosy001")
                        .param("operatorId", "ceo001")
                        .param("size", "50"))
                .andExpect(status().isOk());

        var details = awaitAuditDetails(OperationType.DATA_ACCESS, 1);
        assertThat(details).isNotEmpty();
        assertThat(details.get(0))
                .contains("nosy001")
                .contains("search")
                .as("查詢條件必須留下 —— 「查了誰的紀錄」才是關鍵資訊")
                .contains("ceo001");
    }

    @Test
    @DisplayName("完整性檢查必須留下紀錄與結論")
    void integrityCheckIsAudited() throws Exception {
        // integrityCheck 是唯一能看出 hash chain 被動過的工具。
        // 若調查者就是竄改者，這筆紀錄是唯一的痕跡 —— 而且它寫在鏈上。
        mockMvc.perform(get("/api/audit-logs/integrity-check")
                        .header("X-User-Id", "auditor001")
                        .param("startDate", "2026-01-01T00:00:00Z")
                        .param("endDate", "2027-01-01T00:00:00Z"))
                .andExpect(status().isOk());

        var details = awaitAuditDetails(OperationType.DATA_ACCESS, 1);
        assertThat(details).isNotEmpty();
        assertThat(String.join("\n", details))
                .contains("auditor001")
                .contains("integrity-check")
                .as("檢查結論本身就是要留存的事實")
                .contains("intact");
    }

    // ── 通知設定：一條隱藏簽核活動的路徑 ─────────────────────────

    @Test
    @DisplayName("關閉通知必須留下軌跡（那是讓活動不被察覺的路徑）")
    void disablingNotificationIsAudited() throws Exception {
        String tplBody = mockMvc.perform(post("/api/admin/notify-templates")
                        .header("X-User-Id", "admin001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"t\",\"channel\":\"email\","
                                + "\"subjectTemplate\":\"主旨\",\"bodyTemplate\":\"內容\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String tplId = tplBody.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");

        String cfgBody = mockMvc.perform(post("/api/admin/notify-configs")
                        .header("X-User-Id", "admin001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"processDefinitionKey\":\"leave-approval\",\"eventType\":\"task.created\","
                                + "\"channel\":\"email\",\"templateId\":\"" + tplId + "\",\"enabled\":true}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String cfgId = cfgBody.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");

        // 關掉通知 —— 相關人員就不會知道有案件在跑
        mockMvc.perform(put("/api/admin/notify-configs/" + cfgId)
                        .header("X-User-Id", "quiet001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"processDefinitionKey\":\"leave-approval\",\"eventType\":\"task.created\","
                                + "\"channel\":\"email\",\"templateId\":\"" + tplId + "\",\"enabled\":false}"))
                .andExpect(status().isOk());

        var all = String.join("\n", awaitAuditDetails(OperationType.CONFIG_CHANGE, 3));
        assertThat(all).contains("quiet001");
        assertThat(all)
                .as("enabled 從 true 變 false 必須看得出來")
                .contains("before.enabled").contains("after.enabled");

        // 清理
        mockMvc.perform(delete("/api/admin/notify-configs/" + cfgId).header("X-User-Id", "admin001"));
        mockMvc.perform(delete("/api/admin/notify-templates/" + tplId).header("X-User-Id", "admin001"));
    }

    @Test
    @DisplayName("刪除被引用的模板必須回 409（否則重開 P1-13 的洞）")
    void deletingAReferencedTemplateIsRejected() throws Exception {
        String tplBody = mockMvc.perform(post("/api/admin/notify-templates")
                        .header("X-User-Id", "admin001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"t2\",\"channel\":\"email\","
                                + "\"subjectTemplate\":\"s\",\"bodyTemplate\":\"b\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String tplId = tplBody.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");

        String cfgBody = mockMvc.perform(post("/api/admin/notify-configs")
                        .header("X-User-Id", "admin001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"processDefinitionKey\":\"leave-approval\",\"eventType\":\"task.created\","
                                + "\"channel\":\"email\",\"templateId\":\"" + tplId + "\",\"enabled\":true}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String cfgId = cfgBody.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");

        // 留下指向不存在模板的設定 → EmailConsumer 取不到模板 → 進 DLQ
        // → 通知永久遺失。P1-13 只在 create/update 擋住錯誤的 templateId，
        // 刪除這條路徑會把同一個洞重新打開。
        mockMvc.perform(delete("/api/admin/notify-templates/" + tplId)
                        .header("X-User-Id", "admin001"))
                .andExpect(status().isConflict());

        // 移除引用之後就能刪
        mockMvc.perform(delete("/api/admin/notify-configs/" + cfgId).header("X-User-Id", "admin001"))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/admin/notify-templates/" + tplId).header("X-User-Id", "admin001"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("刪除不存在的模板必須回 404，不得靜默 no-op")
    void deletingAMissingTemplateReturns404() throws Exception {
        mockMvc.perform(delete("/api/admin/notify-templates/does-not-exist")
                        .header("X-User-Id", "admin001"))
                .andExpect(status().isNotFound());
    }
}
