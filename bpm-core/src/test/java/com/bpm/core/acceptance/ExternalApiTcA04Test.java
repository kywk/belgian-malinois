package com.bpm.core.acceptance;

import com.bpm.core.external.ApiKeyUtil;
import com.bpm.core.model.ExternalSystem;
import com.bpm.core.repository.ExternalSystemRepository;
import com.bpm.core.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TC-A04 外部系統 API 發起流程的驗收迴歸測試。
 *
 * <p>TC-A04 是未通過的 4 項驗收案例之一。它驗不過的直接原因很單純：
 * {@code bpm_external_system} <b>沒有任何 seed SQL</b>，資料只能來自 admin UI，
 * 因此乾淨環境上根本沒有可用的外部系統可以測。本測試自行建立測試資料，
 * 讓這條路徑第一次有自動化覆蓋。
 *
 * <p>更重要的是，commit {@code f12a8c2}（R-09 授權改精確比對）被交接文件
 * 列為「高風險、未經編譯驗證」。它收緊了三處靜默放寬權限的子串比對，
 * 而那些判定全部集中在 {@code ExternalSystemPolicy}。本測試是它第一次
 * 被實際執行 —— 涵蓋改動前會誤放行、改動後必須拒絕的那些情境。
 */
class ExternalApiTcA04Test extends IntegrationTestBase {

    @Autowired
    private org.flowable.engine.TaskService taskService;

    @Autowired
    private ExternalSystemRepository repo;

    @Autowired
    private org.flowable.engine.RuntimeService runtimeService;

    private static final String PLAIN_KEY = "sk-tca04-testkey";

    @BeforeEach
    void seedExternalSystem() {
        repo.deleteAll();
    }

    /** 建立一個外部系統。apiKey 以 SHA-256 雜湊存放（明文只給呼叫端）。 */
    private ExternalSystem given(String systemId, String allowedActions, String allowedProcessKeys) {
        ExternalSystem sys = new ExternalSystem();
        // 刻意不設 id：@GeneratedValue(strategy = UUID) 會產生它。
        // 自行指定 id 會讓 save() 誤判為 detached 實體而走 merge()，
        // 在資料列不存在時拋 StaleObjectStateException。
        sys.setSystemId(systemId);
        sys.setSystemName("測試系統");
        sys.setApiKey(ApiKeyUtil.hash(PLAIN_KEY));
        sys.setAllowedActions(allowedActions);
        sys.setAllowedProcessKeys(allowedProcessKeys);
        sys.setEnabled(true);
        sys.setCreatedAt(Instant.now());
        return repo.save(sys);
    }

    /**
     * 外部系統發起流程的 body。
     *
     * <p>必須帶 {@code firstTaskAssignee} —— 當 initiator 為 {@code system:*}
     * （外部系統發起的預設值）時，後端要求明確指定第一個任務的受理人，
     * 否則回 400。這是合理的契約：外部系統發起的案件沒有人類發起者，
     * 若不指定受理人，任務會落在沒有人看得到的地方。
     */
    private static String startBody(String processKey) {
        return "{\"processDefinitionKey\":\"" + processKey + "\","
                + "\"businessKey\":\"TCA04-" + UUID.randomUUID() + "\","
                + "\"firstTaskAssignee\":\"mgr001\","
                + "\"variables\":{\"leaveType\":\"annual\",\"days\":1}}";
    }

    @Test
    @DisplayName("TC-A04：授權正確時外部系統可發起流程")
    void externalSystemCanStartProcess() throws Exception {
        given("erp", "[\"start_process\"]", "[\"leave-approval\"]");

        mockMvc.perform(post("/api/external/process-instances")
                        .header("X-API-Key", PLAIN_KEY)
                        .header("X-System-Id", "erp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(startBody("leave-approval")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.processInstanceId").exists());
    }

    @Test
    @DisplayName("R-09：allowedActions 必須精確比對，不可子串誤放行")
    void actionMatchingMustBeExact() throws Exception {
        // 這是 R-09 修復前會誤放行的經典情境：
        //   "[\"query_status_extended\"]".contains("query_status") == true
        // 改動後必須以集合成員精確比對 → start_process 不在清單內，應 403。
        given("erp", "[\"start_process_extended\"]", "[\"leave-approval\"]");

        mockMvc.perform(post("/api/external/process-instances")
                        .header("X-API-Key", PLAIN_KEY)
                        .header("X-System-Id", "erp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(startBody("leave-approval")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("R-09：allowedProcessKeys 必須生效（改動前完全沒有程式碼在檢查）")
    void processKeyRestrictionIsEnforced() throws Exception {
        // 改動前這個欄位沒有任何一行程式碼在讀 —— 只被授權 leave-approval
        // 的系統也能啟動 purchase-approval。
        given("erp", "[\"start_process\"]", "[\"leave-approval\"]");

        mockMvc.perform(post("/api/external/process-instances")
                        .header("X-API-Key", PLAIN_KEY)
                        .header("X-System-Id", "erp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(startBody("purchase-approval")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("空的 allowedActions（[]）代表拒絕全部，不是不限制")
    void emptyListMeansDenyAll() throws Exception {
        given("erp", "[]", "[\"leave-approval\"]");

        mockMvc.perform(post("/api/external/process-instances")
                        .header("X-API-Key", PLAIN_KEY)
                        .header("X-System-Id", "erp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(startBody("leave-approval")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("無法解析的 allowedActions 必須拒絕（fail-closed）")
    void unparseableConfigFailsClosed() throws Exception {
        given("erp", "[not valid json", "[\"leave-approval\"]");

        mockMvc.perform(post("/api/external/process-instances")
                        .header("X-API-Key", PLAIN_KEY)
                        .header("X-System-Id", "erp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(startBody("leave-approval")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("缺少或錯誤的憑證必須 401；停用的系統必須 403")
    void credentialAndEnabledChecks() throws Exception {
        given("erp", "[\"start_process\"]", "[\"leave-approval\"]");

        // 完全沒帶標頭
        mockMvc.perform(post("/api/external/process-instances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(startBody("leave-approval")))
                .andExpect(status().isUnauthorized());

        // API Key 錯誤
        mockMvc.perform(post("/api/external/process-instances")
                        .header("X-API-Key", "sk-wrong")
                        .header("X-System-Id", "erp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(startBody("leave-approval")))
                .andExpect(status().isUnauthorized());

        // 停用
        ExternalSystem sys = repo.findAll().get(0);
        sys.setEnabled(false);
        repo.save(sys);
        mockMvc.perform(post("/api/external/process-instances")
                        .header("X-API-Key", PLAIN_KEY)
                        .header("X-System-Id", "erp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(startBody("leave-approval")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("未知的外部 API 端點必須拒絕（fail-closed，不得預設取得查詢權限）")
    void unknownEndpointIsRejected() throws Exception {
        given("erp", "[\"start_process\",\"query_status\"]", "[\"leave-approval\"]");

        // 改動前未匹配的路徑會 fall through 成 "query_status"，
        // 等於任何未知端點自動取得查詢權限。
        mockMvc.perform(post("/api/external/unknown-endpoint")
                        .header("X-API-Key", PLAIN_KEY)
                        .header("X-System-Id", "erp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("擁有權由 server 寫入，不依賴呼叫端提供的任何身分")
    void ownershipIsRecordedByServer() throws Exception {
        given("erp", "[\"start_process\"]", "[\"leave-approval\"]");

        var res = start("{\"processDefinitionKey\":\"leave-approval\","
                + "\"businessKey\":\"TCA04-own\","
                + "\"firstTaskAssignee\":\"mgr001\","
                + "\"variables\":{\"leaveType\":\"annual\",\"days\":1}}")
                .andExpect(status().isOk())
                .andReturn();

        assertThat(res.getResponse().getContentAsString())
                .as("啟動應成功並回傳實例 id")
                .contains("processInstanceId");
    }

    // ── R-20：initiator 由 server 決定；代員工發起需系統授權 ──────────────
    //
    // 改動前 body 可指定任意 initiator，而下游廣泛信任它（主管路由、我的申請、
    // 通知信）→ 任何持有 API key 的系統都能偽造一張「看似由某位員工提出」的單。
    // 2026-09-29 決策：initiator 一律為 system:<id>；代發改用 onBehalfOf，
    // 且只有 allowOnBehalfOf=true 的系統能用。

    private org.springframework.test.web.servlet.ResultActions start(String body) throws Exception {
        return mockMvc.perform(post("/api/external/process-instances")
                .header("X-API-Key", PLAIN_KEY)
                .header("X-System-Id", "erp")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private void allowOnBehalfOf(ExternalSystem sys) {
        sys.setAllowOnBehalfOf(true);
        repo.save(sys);
    }

    private String pidOf(org.springframework.test.web.servlet.MvcResult res) throws Exception {
        return res.getResponse().getContentAsString()
                .replaceAll(".*\"processInstanceId\":\"([^\"]*)\".*", "$1");
    }

    @Test
    @DisplayName("R-20：body 帶 initiator 一律 400（不靜默忽略）")
    void initiatorInBodyIsRejected() throws Exception {
        given("erp", "[\"start_process\"]", "[\"leave-approval\"]");
        start("{\"processDefinitionKey\":\"leave-approval\","
                + "\"firstTaskAssignee\":\"mgr001\","
                + "\"initiator\":\"user001\","
                + "\"variables\":{\"leaveType\":\"annual\",\"days\":1}}")
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("R-20：沒有受理人、候選群組、也沒有代發 → 400")
    void assigneeRequiredWithoutOnBehalfOf() throws Exception {
        given("erp", "[\"start_process\"]", "[\"leave-approval\"]");
        start("{\"processDefinitionKey\":\"leave-approval\","
                + "\"variables\":{\"leaveType\":\"annual\",\"days\":1}}")
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("R-20：未授權代發的系統帶 onBehalfOf → 403")
    void onBehalfOfRequiresSystemAuthorization() throws Exception {
        given("erp", "[\"start_process\"]", "[\"leave-approval\"]");  // 預設 allowOnBehalfOf=false
        start("{\"processDefinitionKey\":\"leave-approval\","
                + "\"onBehalfOf\":\"user001\","
                + "\"variables\":{\"leaveType\":\"annual\",\"days\":1}}")
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("R-20：已授權代發但員工不存在 → 400")
    void onBehalfOfUnknownEmployeeIsRejected() throws Exception {
        allowOnBehalfOf(given("erp", "[\"start_process\"]", "[\"leave-approval\"]"));
        start("{\"processDefinitionKey\":\"leave-approval\","
                + "\"onBehalfOf\":\"nobody-" + UUID.randomUUID() + "\","
                + "\"variables\":{\"leaveType\":\"annual\",\"days\":1}}")
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("R-20：已授權代發 → 走該員工的主管、initiator 仍是 system、出現在員工的「我的申請」並標示代發")
    void authorizedOnBehalfOfRoutesToEmployeesManager() throws Exception {
        allowOnBehalfOf(given("erp", "[\"start_process\"]", "[\"leave-approval\"]"));

        var res = start("{\"processDefinitionKey\":\"leave-approval\","
                + "\"businessKey\":\"TCA04-onbehalf-" + UUID.randomUUID() + "\","
                + "\"onBehalfOf\":\"user001\","
                + "\"variables\":{\"leaveType\":\"annual\",\"days\":1}}")
                .andExpect(status().isOk()).andReturn();
        String pid = pidOf(res);

        var task = taskService.createTaskQuery().processInstanceId(pid).singleResult();
        assertThat(task.getAssignee()).as("應落在 user001 的主管").isEqualTo("mgr001");
        assertThat(runtimeService.getVariable(pid, "initiator"))
                .as("initiator 是 server 決定的系統身分，不是員工").isEqualTo("system:erp");

        String mine = mockMvc.perform(get("/api/process-instances").param("initiator", "user001"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(mine).as("代發的單必須出現在員工的「我的申請」").contains(pid);
        var row = new tools.jackson.databind.ObjectMapper().readTree(mine).findParents("processInstanceId")
                .stream().filter(n -> pid.equals(n.get("processInstanceId").asText())).findFirst().orElseThrow();
        assertThat(row.get("onBehalf").asBoolean())
                .as("而且必須標示為代發，否則員工會看到一張自己沒送過的單")
                .isTrue();
    }

    @Test
    @DisplayName("R-20：不能藉 variables 夾帶 onBehalfOf 繞過系統授權")
    void onBehalfOfCannotBeSmuggledThroughVariables() throws Exception {
        given("erp", "[\"start_process\"]", "[\"leave-approval\"]");  // 未授權代發
        var res = start("{\"processDefinitionKey\":\"leave-approval\","
                + "\"firstTaskAssignee\":\"mgr001\","
                + "\"variables\":{\"leaveType\":\"annual\",\"days\":1,\"onBehalfOf\":\"user001\"}}")
                .andExpect(status().isOk()).andReturn();
        assertThat(runtimeService.getVariable(pidOf(res), "onBehalfOf"))
                .as("variables 是自由 map；server 必須移除它，否則等於繞過授權")
                .isNull();
    }
}
