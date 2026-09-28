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
    @DisplayName("擁有權由 server 寫入，不依賴可偽造的 initiator")
    void ownershipIsRecordedByServer() throws Exception {
        given("erp", "[\"start_process\"]", "[\"leave-approval\"]");

        String body = "{\"processDefinitionKey\":\"leave-approval\","
                + "\"businessKey\":\"TCA04-own\","
                + "\"firstTaskAssignee\":\"mgr001\","
                // 刻意偽造 initiator：擁有權判定不得採信它（R-09 已改為
                // 依 server 寫入的 _externalSystemId 判定）
                + "\"initiator\":\"erp-legacy\","
                + "\"variables\":{\"leaveType\":\"annual\",\"days\":1}}";

        var res = mockMvc.perform(post("/api/external/process-instances")
                        .header("X-API-Key", PLAIN_KEY)
                        .header("X-System-Id", "erp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(res.getResponse().getContentAsString())
                .as("啟動應成功並回傳實例 id")
                .contains("processInstanceId");
    }

    /**
     * 偽造 initiator 不再能繞過受理人的必填要求（P2-7 修復）。
     *
     * <h2>改動前為什麼會通過</h2>
     *
     * <p>必填檢查原本寫成 {@code initiator.startsWith("system:")} 才要求
     * {@code firstTaskAssignee}。而 initiator 完全由呼叫端指定（R-20），
     * 送一個不以 {@code system:} 開頭的值就整個跳過檢查。
     *
     * <p>後果不只是「檢查被跳過」：BPMN 接著算
     * {@code orgService.getDirectManager("not-a-system-prefix")}，
     * 而組織 mock 對未知 userId 一律回 {@code mgr001} ——
     * 案件<b>啟動成功並派給 mgr001</b>，外部系統收到 200，看起來毫無異常。
     *
     * <h2>現在的行為</h2>
     *
     * <p>檢查的依據換成「流程接下來需不需要查組織」這個客觀事實，
     * 不再依賴呼叫端可任意指定的字串。所以 system 帳號與偽造的員工編號
     * 都會拿到 400。
     *
     * <h2>R-20 仍未完全修復</h2>
     *
     * <p>呼叫端仍可冒用<b>真實存在</b>的員工編號當 initiator ——
     * 那會通過這裡的檢查，案件派給那個人的主管。要徹底修掉必須讓 initiator
     * 由 server 依 API key 決定，那是 R-20 的本體，尚未施作。
     * 這次只拿掉了「用一個不存在的身分繞過必填檢查」這條路。
     */
    @Test
    @DisplayName("R-20（部分修復）：偽造 initiator 不得繞過受理人的必填要求")
    void forgedInitiatorCannotBypassAssigneeRequirement() throws Exception {
        given("erp", "[\"start_process\"]", "[\"leave-approval\"]");

        String body = "{\"processDefinitionKey\":\"leave-approval\","
                + "\"businessKey\":\"TCA04-bypass\","
                + "\"initiator\":\"not-a-system-prefix\","
                + "\"variables\":{\"leaveType\":\"annual\",\"days\":1}}";

        mockMvc.perform(post("/api/external/process-instances")
                        .header("X-API-Key", PLAIN_KEY)
                        .header("X-System-Id", "erp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("代真實員工發起時不需指定受理人 —— 主管路由仍須可用")
    void onBehalfOfRealEmployeeStillRoutesToManager() throws Exception {
        // 上一個測試不能是靠「一律要求 firstTaskAssignee」達成的 ——
        // 那會擋掉「外部系統代員工送件」這個正當用途。
        // 這裡確認 initiator 是組織系統認識的人時，仍可省略受理人並走主管路由。
        given("erp", "[\"start_process\"]", "[\"leave-approval\"]");

        String body = "{\"processDefinitionKey\":\"leave-approval\","
                + "\"businessKey\":\"TCA04-onbehalf-" + UUID.randomUUID() + "\","
                + "\"initiator\":\"user001\","
                + "\"variables\":{\"leaveType\":\"annual\",\"days\":1}}";

        mockMvc.perform(post("/api/external/process-instances")
                        .header("X-API-Key", PLAIN_KEY)
                        .header("X-System-Id", "erp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        // 第一個任務應落在 user001 的主管身上，而不是任何捏造的預設值。
        var task = taskService.createTaskQuery()
                .processVariableValueEquals("initiator", "user001")
                .orderByTaskCreateTime().desc().list().get(0);
        org.assertj.core.api.Assertions.assertThat(task.getAssignee()).isEqualTo("mgr001");
    }
}
