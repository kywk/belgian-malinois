package com.bpm.core.external;

import com.bpm.core.repository.ExternalSystemRepository;
import com.bpm.core.support.IntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 工項 #20：外部系統使用紀錄（{@code GET /api/admin/external-systems/{systemId}/usage-logs}）。
 *
 * <h2>測什麼</h2>
 *
 * <p>用真實的 API Key 走真實 HTTP 發起一次外部呼叫，再從管理端點查回來 ——
 * 驗的是「呼叫真的產生紀錄、端點真的查得到同一筆」，不是把兩邊都 mock 起來
 * 的形狀測試。涵蓋：欄位正確、跨系統隔離、未知系統 404、分頁、日期區間、
 * 停用後仍可查歷史、非管理員 403，以及查詢本身寫一筆 {@code DATA_ACCESS}。
 *
 * <h2>負向控制組實測（拿掉 operatorId 過濾後重跑本類別）</h2>
 *
 * <p>把 {@code usageLogs} 的 {@code ExternalActorIdentity.of(systemId)} 換成
 * {@code null}（只留 {@code operationType} 過濾）後重跑：
 * {@link #otherSystemsCallsAreNotReturned} <b>紅</b>（A 的查詢回傳了 B 的
 * businessKey），其餘 6 條綠 —— 它們各自只建立一個系統，拿掉過濾後仍然
 * 查得到同一批資料，所以那些綠燈證明不了隔離。
 *
 * <p><b>證明不了的事</b>：本組測試跑在單一節點上，不涵蓋多實例部署時
 * 稽核寫入的 hash chain 競爭（與本端點無關，見 {@code AuditLogService}）；
 * 也刻意不測「被拒絕的呼叫」——那條路徑由 #22 修改中的
 * {@code ExternalApiAuthFilter} 負責，時序上不該由本工項綁定它的行為。
 * 端點回傳的是 Spring Data {@code PageImpl} 的原生 JSON，欄位穩定性與
 * {@code /api/audit-logs} 相同（同一個已知限制，不是本工項新增的）。
 */
class ExternalSystemUsageLogsTest extends IntegrationTestBase {

    private static final String PROCESS_KEY = "leave-approval";

    @Autowired private MockMvc mockMvc;
    @Autowired private ExternalSystemRepository repo;

    private final HttpClient http = HttpClient.newHttpClient();
    private final List<String> createdSystems = new ArrayList<>();

    @BeforeEach
    void cleanAudit() {
        // 稽核是同步寫入（AuditEventPublisher fail-closed），清乾淨讓每條測試
        // 可以斷言精確筆數（CallbackReceiverTest 同款）。
        truncateAuditLog();
    }

    @AfterEach
    void cleanupSystems() {
        createdSystems.forEach(sid -> repo.findBySystemId(sid)
                .ifPresent(repo::delete));
        createdSystems.clear();
    }

    // ── fixture／工具 ──────────────────────────────────────────────

    /** 走管理 API 建立系統，回傳只出現一次的明文 API Key。 */
    private String createSystem(String systemId) throws Exception {
        createdSystems.add(systemId);
        String body = mockMvc.perform(post("/api/admin/external-systems")
                        .header("X-User-Id", "admin001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"systemId\":\"" + systemId + "\","
                                + "\"systemName\":\"T20 使用紀錄測試\","
                                + "\"allowedProcessKeys\":\"[\\\"leave-approval\\\"]\","
                                + "\"allowedActions\":\"[\\\"start_process\\\"]\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return body.replaceAll(".*\"apiKey\":\"([^\"]+)\".*", "$1");
    }

    /** 用該系統的 API Key 真實呼叫外部 API；回傳流程實例 id。 */
    private String startCall(String systemId, String apiKey, String businessKey) throws Exception {
        var req = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + SERVLET_PORT + "/api/external/process-instances"))
                .header("X-API-Key", apiKey)
                .header("X-System-Id", systemId)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"processDefinitionKey\":\"" + PROCESS_KEY + "\","
                                + "\"businessKey\":\"" + businessKey + "\","
                                + "\"firstTaskAssignee\":\"mgr001\","
                                + "\"variables\":{\"leaveType\":\"annual\",\"days\":1}}"))
                .build();
        var res = http.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(res.statusCode())
                .as("前置條件：外部呼叫必須成功，usage-logs 才有東西可查：" + res.body())
                .isEqualTo(200);
        return res.body().replaceAll(".*\"processInstanceId\":\"([^\"]*)\".*", "$1");
    }

    /** 以 admin001 查 usage-logs；params 成對帶入（可空）。 */
    private ResultActions getUsageLogs(String systemId, String... params) throws Exception {
        MockHttpServletRequestBuilder req = get(
                "/api/admin/external-systems/" + systemId + "/usage-logs")
                .header("X-User-Id", "admin001");
        for (int i = 0; i < params.length; i += 2) {
            req = req.param(params[i], params[i + 1]);
        }
        return mockMvc.perform(req).andExpect(status().isOk());
    }

    /** 直接查稽核庫：該系統的 EXTERNAL_API_CALL 真的存在（隔離測試的非空洞性前提）。 */
    private long externalApiCallRows(String operatorId) {
        long[] count = {0};
        withAuditConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COUNT(*) FROM bpm_audit_log "
                            + "WHERE operation_type = 'EXTERNAL_API_CALL' AND operator_id = ?")) {
                ps.setString(1, operatorId);
                var rs = ps.executeQuery();
                if (rs.next()) count[0] = rs.getLong(1);
            }
        });
        return count[0];
    }

    private List<String> dataAccessRows() {
        List<String> out = new ArrayList<>();
        withAuditConnection(c -> {
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT operator_id, detail FROM bpm_audit_log "
                                 + "WHERE operation_type = 'DATA_ACCESS' ORDER BY id")) {
                while (rs.next()) {
                    out.add(rs.getString("operator_id") + "|" + rs.getString("detail"));
                }
            }
        });
        return out;
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    // ── 內容與欄位 ────────────────────────────────────────────────

    @Test
    @DisplayName("#20：用 API Key 呼叫一次 → usage-logs 查得到該筆，欄位與稽核一致")
    void usageLogsReturnsTheSystemsCalls() throws Exception {
        String sid = "t20-main-" + shortId();
        String key = createSystem(sid);
        String businessKey = "T20-" + UUID.randomUUID();
        String pid = startCall(sid, key, businessKey);

        getUsageLogs(sid)
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.number").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.content[0].operationType").value("EXTERNAL_API_CALL"))
                .andExpect(jsonPath("$.content[0].operatorId").value("system:" + sid))
                .andExpect(jsonPath("$.content[0].operatorSource").value("external_api"))
                .andExpect(jsonPath("$.content[0].businessKey").value(businessKey))
                .andExpect(jsonPath("$.content[0].processInstanceId").value(pid))
                .andExpect(jsonPath("$.content[0].detail", containsString("start_process")))
                .andExpect(jsonPath("$.content[0].detail", containsString(PROCESS_KEY)));

        // 查稽核本身也要留紀錄（稽核稽核者）：條件與命中筆數都要在。
        assertThat(dataAccessRows())
                .as("翻閱外部系統的呼叫紀錄必須留下誰翻了什麼範圍")
                .anySatisfy(row -> assertThat(row)
                        .contains("admin001").contains("usage-logs").contains(sid));
    }

    // ── 隔離 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("#20：A 的 usage-logs 不得出現 B 的呼叫（跨系統隔離）")
    void otherSystemsCallsAreNotReturned() throws Exception {
        String a = "t20-iso-a-" + shortId();
        String b = "t20-iso-b-" + shortId();
        String keyA = createSystem(a);
        String keyB = createSystem(b);
        String businessKeyA = "T20-A-" + UUID.randomUUID();
        String businessKeyB = "T20-B-" + UUID.randomUUID();

        startCall(a, keyA, businessKeyA);
        startCall(b, keyB, businessKeyB);

        // 非空洞性前提：B 的紀錄真的在稽核庫裡，只是不該被 A 的查詢撈出來。
        // 少了這一行，一個「什麼都查不到」的實作也能讓下面全綠。
        assertThat(externalApiCallRows("system:" + b))
                .as("前置條件：B 的呼叫必須已經寫進稽核庫")
                .isEqualTo(1);

        String bodyA = getUsageLogs(a)
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].operatorId").value("system:" + a))
                .andExpect(jsonPath("$.content[0].businessKey").value(businessKeyA))
                .andReturn().getResponse().getContentAsString();
        assertThat(bodyA)
                .as("A 的回應不得含 B 的身分或案件")
                .doesNotContain("system:" + b)
                .doesNotContain(businessKeyB);

        getUsageLogs(b)
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].operatorId").value("system:" + b))
                .andExpect(jsonPath("$.content[0].businessKey").value(businessKeyB));
    }

    // ── 邊界 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("#20：未知系統 → 404（沿用 find(systemId)，不得回空頁假裝存在）")
    void unknownSystemReturns404() throws Exception {
        mockMvc.perform(get("/api/admin/external-systems/t20-does-not-exist-" + shortId()
                        + "/usage-logs")
                        .header("X-User-Id", "admin001"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("#20：分頁參數生效，且排序與 /api/audit-logs 相同（新到舊、不重不漏）")
    void paginationIsApplied() throws Exception {
        String sid = "t20-page-" + shortId();
        String key = createSystem(sid);
        String first = "T20-P1-" + UUID.randomUUID();
        String second = "T20-P2-" + UUID.randomUUID();
        String third = "T20-P3-" + UUID.randomUUID();
        startCall(sid, key, first);
        startCall(sid, key, second);
        startCall(sid, key, third);

        getUsageLogs(sid, "page", "0", "size", "1")
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.number").value(0))
                .andExpect(jsonPath("$.content[0].businessKey").value(third));
        getUsageLogs(sid, "page", "1", "size", "1")
                .andExpect(jsonPath("$.number").value(1))
                .andExpect(jsonPath("$.content[0].businessKey").value(second));
        getUsageLogs(sid, "page", "2", "size", "1")
                .andExpect(jsonPath("$.number").value(2))
                .andExpect(jsonPath("$.last").value(true))
                .andExpect(jsonPath("$.content[0].businessKey").value(first));
    }

    @Test
    @DisplayName("#20：日期區間沿用既有查詢語意（startDate／endDate 真的接上）")
    void dateRangeFiltersAreApplied() throws Exception {
        String sid = "t20-date-" + shortId();
        String key = createSystem(sid);
        startCall(sid, key, "T20-D-" + UUID.randomUUID());

        getUsageLogs(sid, "startDate", "2100-01-01T00:00:00Z")
                .andExpect(jsonPath("$.totalElements").value(0));
        getUsageLogs(sid, "endDate", "2000-01-01T00:00:00Z")
                .andExpect(jsonPath("$.totalElements").value(0));
        getUsageLogs(sid,
                "startDate", "2000-01-01T00:00:00Z",
                "endDate", "2100-01-01T00:00:00Z")
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    @DisplayName("#20：停用後仍可查歷史（DELETE 是停用，事故調查發生在停用之後）")
    void disabledSystemStillQueryable() throws Exception {
        String sid = "t20-off-" + shortId();
        String key = createSystem(sid);
        startCall(sid, key, "T20-OFF-" + UUID.randomUUID());

        mockMvc.perform(delete("/api/admin/external-systems/" + sid)
                        .header("X-User-Id", "admin001"))
                .andExpect(status().isOk());
        assertThat(repo.findBySystemId(sid).orElseThrow().getEnabled())
                .as("前置條件：系統已停用")
                .isFalse();

        getUsageLogs(sid).andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    @DisplayName("#20：非管理員不得查（/api/admin/** 既有 ROLE_ADMIN，零 SecurityConfig 變更）")
    void nonAdminCannotQueryUsageLogs() throws Exception {
        String sid = "t20-auth-" + shortId();
        createSystem(sid);

        mockMvc.perform(get("/api/admin/external-systems/" + sid + "/usage-logs")
                        .header("X-User-Id", "user001"))
                .andExpect(status().isForbidden());
    }
}
