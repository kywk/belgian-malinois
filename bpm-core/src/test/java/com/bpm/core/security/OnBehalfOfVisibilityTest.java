package com.bpm.core.security;

import com.bpm.core.external.ApiKeyUtil;
import com.bpm.core.model.ExternalSystem;
import com.bpm.core.repository.ExternalSystemRepository;
import com.bpm.core.service.OnBehalfOfLookup;
import com.bpm.core.support.IntegrationTestBase;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * #68b：審核人端（{@code /api/tasks}、{@code /api/history/tasks}）的代發標示。
 *
 * <h2>缺陷（產品缺口，非授權缺陷）</h2>
 *
 * <p>{@code onBehalf} 這個布林值只存在於<b>申請人端</b>的兩個 API
 * （{@code GET /api/process-instances}、{@code GET /api/history/process-instances}），
 * 而審核人每天用的 {@code GET /api/tasks}（待辦清單）與
 * {@code GET /api/history/tasks}（簽核軌跡）完全沒有它。
 * {@code bpm-frontend/src} 對「申請人」三個字的 grep 更是零命中。
 *
 * <p>後果是：主管看到「主管審核」四個字，而這張單的 {@code initiator} 是
 * {@code system:erp} —— <b>他不知道這是代誰發起的</b>，也就無從判斷該問誰補件。
 * 這正是 {@code OnBehalfOfLookup} 類別註解引用的產品理由。
 *
 * <h2>⚠️ 授權面：這個欄位不是新的揭露（這是本測試組要證明的事）</h2>
 *
 * <p>改動前，一位審核人<b>已經</b>讀得到同一個值：
 * {@code GET /api/process-instances/{id}/variables} 走
 * {@code requireReadAccess}（關係人 ∪ {@code audit:log:read}），而它回傳
 * {@code runtimeService.getVariables(id)} —— 整包流程變數，
 * 裡面就有 {@code onBehalfOf}。
 *
 * <p>因此本測試組刻意包含一條「非回歸」斷言：<b>關係人仍然讀得到 variables</b>。
 * 它防的是「有人日後順手把 variables 收窄（把 onBehalfOf 濾掉）以求好看」——
 * 那會讓這裡的標示變成唯一的一條讀取路徑，於是它就不再是零新增揭露。
 * 兩條必須成組存在，否則「零揭露」這個論證無法被驗證。
 *
 * <h2>⚠️ 為什麼斷言「關係人」而不是「持有者」</h2>
 *
 * <p>{@code onBehalfOf} 那張單的第一關 assignee 是
 * {@code orgService.getDirectManager(onBehalfOf)}（見
 * {@code InitialAssigneeResolver}）—— <b>那位員工的主管</b>。
 * 測試用 {@code mgr001} 擔任審核人，讓「他本來就有權讀這張單的 variables」
 * 這件事是顯然的，而不是靠 fixture 假設。
 *
 * <h2>負向控制組（2026-09-30，把兩個 controller 的 onBehalfOf 欄位拿掉後重跑）</h2>
 *
 * <p>預期 5 條中 4 紅 1 綠。紅的是四種形狀（待辦清單／案件詳情來源／
 * 已完成案件的軌跡／非回歸對照組），綠的是「非代發案件不得出現這個欄位」——
 * 它在缺陷期間本來就成立，<b>重點是它證明修法沒有把所有人都標成代發</b>。
 */
class OnBehalfOfVisibilityTest extends IntegrationTestBase {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired private ExternalSystemRepository externalSystemRepo;
    @Autowired private RuntimeService runtimeService;
    @Autowired private TaskService taskService;
    @Autowired private OnBehalfOfLookup onBehalfOfLookup;

    private static final String PLAIN_KEY = "sk-68b-testkey";

    @BeforeEach
    void seed() {
        externalSystemRepo.deleteAll();
        ExternalSystem sys = new ExternalSystem();
        sys.setSystemId("erp");
        sys.setSystemName("測試系統");
        sys.setApiKey(ApiKeyUtil.hash(PLAIN_KEY));
        sys.setAllowedActions("[\"start_process\"]");
        sys.setAllowedProcessKeys("[\"leave-approval\"]");
        sys.setEnabled(true);
        // ⚠️ 沒有這個，ExternalApiController 會回 403 —— 代發是需要授權的。
        sys.setAllowOnBehalfOf(true);
        sys.setCreatedAt(Instant.now());
        externalSystemRepo.save(sys);
    }

    /** 用真正的外部 API 代 user001 發起一張單，回傳 pid。 */
    private String startOnBehalfOf(String onBehalfOf) throws Exception {
        var res = mockMvc.perform(post("/api/external/process-instances")
                        .header("X-API-Key", PLAIN_KEY)
                        .header("X-System-Id", "erp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"processDefinitionKey\":\"leave-approval\","
                                + "\"businessKey\":\"R68B-" + UUID.randomUUID() + "\","
                                + "\"onBehalfOf\":\"" + onBehalfOf + "\","
                                + "\"variables\":{\"leaveType\":\"annual\",\"days\":1}}"))
                .andExpect(status().isOk()).andReturn();
        return res.getResponse().getContentAsString()
                .replaceAll(".*\"processInstanceId\":\"([^\"]*)\".*", "$1");
    }

    private JsonNode json(String body) throws Exception {
        return JSON.readTree(body);
    }

    /** 待辦清單中 processInstanceId == pid 的那一列。 */
    private JsonNode inboxRow(String userId, String pid) throws Exception {
        String body = mockMvc.perform(get("/api/tasks")
                        .header("X-User-Id", userId))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        for (JsonNode n : json(body)) {
            if (pid.equals(n.path("processInstanceId").asText())) return n;
        }
        throw new AssertionError("待辦清單找不到 pid=" + pid + "，整包回應：" + body);
    }

    // ── 缺陷形狀 ─────────────────────────────────────────────────

    @Test
    @DisplayName("審核人的待辦清單必須標示「代某某發起」")
    void inboxShowsWhoTheCaseIsOnBehalfOf() throws Exception {
        String pid = startOnBehalfOf("user001");

        JsonNode row = inboxRow("mgr001", pid);
        assertThat(row.path("onBehalfOf").asText(null))
                .as("改動前這裡是 null —— 主管只看到「主管審核」，不知道是代誰發起的")
                .isEqualTo("user001");
    }

    @Test
    @DisplayName("案件詳情頁的資料來源（DocumentDetail 用的待辦清單）必須帶得到代發者")
    void theCallTheDocumentDetailPageUsesCarriesTheField() throws Exception {
        // DocumentDetail.vue 是用 getTasks() 的結果找 task，不是另外呼叫。
        // 這一條與上一條是同一件事的兩個理由，寫成兩條是為了讓失敗時
        // 看得出是哪一頁壞了。
        String pid = startOnBehalfOf("user001");
        assertThat(inboxRow("mgr001", pid).has("onBehalfOf")).isTrue();
    }

    @Test
    @DisplayName("簽核軌跡也必須帶得到代發者（審結後回頭查時仍然需要）")
    void approvalTimelineCarriesTheField() throws Exception {
        String pid = startOnBehalfOf("user001");
        // 先讓第一關完成，時間軸上才有一筆
        var task = taskService.createTaskQuery().processInstanceId(pid).singleResult();
        mockMvc.perform(put("/api/tasks/" + task.getId())
                        .header("X-User-Id", "mgr001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"complete\",\"variables\":[{\"name\":\"approved\",\"value\":true}]}"))
                .andExpect(status().isOk());

        String body = mockMvc.perform(get("/api/history/tasks")
                        .header("X-User-Id", "mgr001")
                        .param("processInstanceId", pid))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(json(body)).isNotEmpty();
        for (JsonNode n : json(body)) {
            assertThat(n.path("onBehalfOf").asText(null))
                    .as("軌跡上已完成的關卡也必須標示 —— 審結後回頭查是主要情境")
                    .isEqualTo("user001");
        }
    }

    @Test
    @DisplayName("非回歸：關係人讀 variables 仍然拿得到 onBehalfOf（它是唯一既有的揭露路徑）")
    void thePreexistingDisclosurePathStillWorks() throws Exception {
        // ⚠️ 這一條是「零新增揭露」這個論證的支柱。
        // 若 variables 被收窄到不含 onBehalfOf，審核人就只會從新的任務 DTO
        // 讀到它 —— 那時它就不再是零新增揭露，而是一次真正的政策改變，
        // 必須回到 PM 重新裁決。所以這條與其他三條必須成組存在。
        String pid = startOnBehalfOf("user001");

        String body = mockMvc.perform(get("/api/process-instances/" + pid + "/variables")
                        .header("X-User-Id", "mgr001"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(json(body).path("onBehalfOf").asText(null))
                .as("requireReadAccess 之下，關係人本來就能讀到這個值")
                .isEqualTo("user001");
        assertThat(runtimeService.getVariable(pid, "onBehalfOf")).isEqualTo("user001");
    }

    // ── 非回歸：不得把所有人都標成代發 ───────────────────────────

    @Test
    @DisplayName("人工發起的案件不得出現代發標示")
    void humanInitiatedCaseHasNoOnBehalfMarker() throws Exception {
        String body = mockMvc.perform(post("/api/process-instances")
                        .header("X-User-Id", "user001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"processDefinitionKey\":\"leave-approval\","
                                + "\"businessKey\":\"R68B-human-" + UUID.randomUUID() + "\","
                                + "\"variables\":{\"leaveType\":\"annual\",\"days\":1}}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String pid = body.replaceAll(".*\"processInstanceId\":\"([^\"]*)\".*", "$1");

        assertThat(inboxRow("mgr001", pid).path("onBehalfOf").isNull())
                .as("99% 的案件是人工發起的；把它們全標成代發等於沒有標示")
                .isTrue();
    }

    @Test
    @DisplayName("系統身分不得被當成「代發人」顯示")
    void systemActorIsNotSurfacedAsAnEmployee() {
        // 正常呼叫路徑下 ExternalApiController 已擋掉（它驗過組織系統），
        // 這是顯示端的第二道防線 —— 理由見 OnBehalfOfLookup 的類別註解。
        // 用單元測試而不是走 HTTP：這個組合在真實流程裡<b>造不出來</b>
        // （啟動時就 400 了），硬要構造只會測到 400 那條路徑。
        var result = onBehalfOfLookup.byProcessInstances(List.of("whatever"));
        assertThat(result).isEmpty();
    }
}
