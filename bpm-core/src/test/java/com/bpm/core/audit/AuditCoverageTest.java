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
    @DisplayName("更新外部系統不得破壞它的 API key；只改代發授權的 PUT 也必須留痕")
    void updateKeepsApiKeyAndAuditsOnBehalfOfToggle() throws Exception {
        // 兩個都是 R-20 線上實測才發現的缺陷：
        // 1. update() 加上 @Transactional（P1-14）後，回應前的 setApiKey("***")
        //    被 flush 進 DB → 該系統的 key 立即失效。MockMvc 測試只看回應，看不到。
        // 2. allowOnBehalfOf 不在 AUDITED_FIELDS → 只改它的 PUT 判定為「沒有變更」、不寫稽核。
        String sid = "audit-key-" + UUID.randomUUID().toString().substring(0, 8);
        createdSystems.add(sid);
        String created = mockMvc.perform(post("/api/admin/external-systems")
                        .header("X-User-Id", "admin001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"systemId\":\"" + sid + "\",\"systemName\":\"測試\","
                                + "\"allowedProcessKeys\":\"[\\\"leave-approval\\\"]\","
                                + "\"allowedActions\":\"[\\\"start_process\\\"]\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String plainKey = created.replaceAll(".*\"apiKey\":\"([^\"]*)\".*", "$1");
        // R-25：create 現在存的是 v2（HMAC）格式。這個測試要驗的是
        // 「update 的回應遮蔽不得寫回 DB」，所以比對「PUT 前後的值相同」，
        // 而不是綁定某一種雜湊演算法（那會讓每次格式演進都誤紅）。
        String storedHashBeforeUpdate = externalSystemRepo.findBySystemId(sid)
                .orElseThrow().getApiKey();
        assertThat(storedHashBeforeUpdate).startsWith("v2:");
        truncateAuditLog();

        mockMvc.perform(put("/api/admin/external-systems/" + sid)
                        .header("X-User-Id", "admin001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"systemName\":\"測試\","
                                + "\"allowedProcessKeys\":\"[\\\"leave-approval\\\"]\","
                                + "\"allowedActions\":\"[\\\"start_process\\\"]\",\"enabled\":true,"
                                + "\"allowOnBehalfOf\":true}"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.apiKey").value("***"));

        assertThat(externalSystemRepo.findBySystemId(sid).orElseThrow().getApiKey())
                .as("回應遮蔽 apiKey 不得寫回 DB —— 否則外部系統立即全部 401")
                .isEqualTo(storedHashBeforeUpdate);

        var details = awaitAuditDetails(OperationType.CONFIG_CHANGE, 1);
        assertThat(String.join("\n", details))
                .as("代發授權是授權變更，必須留下前後值")
                .contains("update").contains("allowOnBehalfOf").contains("false → true");
    }

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

        // 擴大授權範圍 —— 這是最需要留下前後值的一種變更。
        //
        // 身分用 dir001（持有通配以外的權限，但不是 ADMIN）會被 403 擋下，
        // 所以這裡用 admin001。R-01 之後「非管理員擴大外部系統授權」
        // 這條路已經走不通了 —— 見 nonAdminCannotChangeExternalSystems。
        mockMvc.perform(put("/api/admin/external-systems/" + sid)
                        .header("X-User-Id", "admin001")
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
                .contains("admin001")
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
        // ⚠️ 用 admin001 而不是「攻擊者」：非管理員現在會被 403 擋在門外
        // （那是 R-01 的效果，另有測試涵蓋）。但 mass assignment 的保護必須
        // 對「有正當權限的管理員」也成立 —— 一次手誤或被複製的 body
        // 不該靜默覆寫另一個系統的授權。認證不能取代輸入驗證。
        mockMvc.perform(post("/api/admin/external-systems")
                        .header("X-User-Id", "admin001")
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
        //
        // 身分用 dir001 —— 依 2026-09-29 的政策決策，稽核檢視權由權限中心以
        // audit:log:read 指派，dir001 持有它。這個測試的重點正是：
        // 「有正當權限的人」才是最需要被追蹤的對象，因為只有他做得到。
        mockMvc.perform(get("/api/audit-logs")
                        .header("X-User-Id", "dir001")
                        .param("operatorId", "ceo001")
                        .param("size", "50"))
                .andExpect(status().isOk());

        var details = awaitAuditDetails(OperationType.DATA_ACCESS, 1);
        assertThat(details).isNotEmpty();
        assertThat(details.get(0))
                .contains("dir001")
                .contains("search")
                .as("查詢條件必須留下 —— 「查了誰的紀錄」才是關鍵資訊")
                .contains("ceo001");
    }

    @Test
    @DisplayName("沒有 audit:log:read 的人不得查詢稽核紀錄")
    void auditLogQueriesRequireTheAuditPermission() throws Exception {
        // R-01 之前這裡完全沒有認證，任何人帶個 X-User-Id 就能翻閱全公司的
        // 簽核紀錄。現在由權限中心的 audit:log:read 控制。
        //
        // ⚠️ 這個測試與上一個必須成組存在。少了它，上一個可以靠
        // 「整條授權規則失效」達成 —— 而那正是修復前的狀態。
        mockMvc.perform(get("/api/audit-logs")
                        .header("X-User-Id", "user001")
                        .param("size", "50"))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/audit-logs/integrity-check")
                        .header("X-User-Id", "mgr001")
                        .param("startDate", "2026-01-01T00:00:00Z")
                        .param("endDate", "2027-01-01T00:00:00Z"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("完全沒有身分時不得查詢稽核紀錄")
    void anonymousCallersCannotQueryAuditLogs() throws Exception {
        // 沒有閘道密鑰也沒有 Bearer token —— 這是外部攻擊者的實際情境。
        // 401 而非 403：連身分都沒有。
        mockMvc.perform(get("/api/audit-logs")
                        .header("X-Gateway-Secret", "wrong-secret")
                        .param("size", "5"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("完整性檢查必須留下紀錄與結論")
    void integrityCheckIsAudited() throws Exception {
        // integrityCheck 是唯一能看出 hash chain 被動過的工具。
        // 若調查者就是竄改者，這筆紀錄是唯一的痕跡 —— 而且它寫在鏈上。
        mockMvc.perform(get("/api/audit-logs/integrity-check")
                        .header("X-User-Id", "dir001")
                        .param("startDate", "2026-01-01T00:00:00Z")
                        .param("endDate", "2027-01-01T00:00:00Z"))
                .andExpect(status().isOk());

        var details = awaitAuditDetails(OperationType.DATA_ACCESS, 1);
        assertThat(details).isNotEmpty();
        assertThat(String.join("\n", details))
                .contains("dir001")
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
                        .header("X-User-Id", "admin001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"processDefinitionKey\":\"leave-approval\",\"eventType\":\"task.created\","
                                + "\"channel\":\"email\",\"templateId\":\"" + tplId + "\",\"enabled\":false}"))
                .andExpect(status().isOk());

        var all = String.join("\n", awaitAuditDetails(OperationType.CONFIG_CHANGE, 3));
        assertThat(all).contains("admin001");
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
    @DisplayName("非管理員不得變更外部系統的授權設定")
    void nonAdminCannotChangeExternalSystems() throws Exception {
        // 這個端點管的是 allowedProcessKeys —— 誰能從外部發起哪些流程。
        // R-01 之前它完全沒有認證：任何人帶個 X-User-Id 就能擴大授權，
        // 而且（P2-4 之前）連稽核都沒有。
        mockMvc.perform(post("/api/admin/external-systems")
                        .header("X-User-Id", "mgr001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"systemId\":\"should-not-exist\",\"systemName\":\"x\"}"))
                .andExpect(status().isForbidden());

        // dir001 持有多個權限碼但不是通配持有者 —— 一樣不該通過。
        // 這一條在防的是「把 ADMIN 誤寫成任何權限碼都放行」。
        mockMvc.perform(post("/api/admin/external-systems")
                        .header("X-User-Id", "dir001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"systemId\":\"should-not-exist-2\",\"systemName\":\"x\"}"))
                .andExpect(status().isForbidden());

        assertThat(externalSystemRepo.findBySystemId("should-not-exist")).isEmpty();
        assertThat(externalSystemRepo.findBySystemId("should-not-exist-2")).isEmpty();
    }

    @Test
    @DisplayName("非管理員不得變更通知設定（隱藏簽核活動的路徑）")
    void nonAdminCannotChangeNotificationConfig() throws Exception {
        mockMvc.perform(post("/api/admin/notify-templates")
                        .header("X-User-Id", "user001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"channel\":\"email\","
                                + "\"subjectTemplate\":\"s\",\"bodyTemplate\":\"b\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("刪除不存在的模板必須回 404，不得靜默 no-op")
    void deletingAMissingTemplateReturns404() throws Exception {
        mockMvc.perform(delete("/api/admin/notify-templates/does-not-exist")
                        .header("X-User-Id", "admin001"))
                .andExpect(status().isNotFound());
    }
}
