package com.bpm.core.security;

import com.bpm.core.support.IntegrationTestBase;
import com.bpm.core.support.TestGatewayMockMvcCustomizer;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #71：讀端授權 —— 四個 🔴 端點。
 *
 * <h2>缺陷</h2>
 *
 * <p>四個端點的篩選參數全是 {@code required=false} 且<b>完全不檢查是否等於
 * 呼叫者</b>，其中三個在參數缺席時等同於「回傳全部」：
 *
 * <ul>
 *   <li>{@code GET /api/process-instances} —— 實測回 107 件全公司執行中案件，
 *       含 businessKey 與當前審核人。</li>
 *   <li>{@code GET /api/tasks} —— 回傳全公司待辦，洩漏「誰在審什麼」。</li>
 *   <li>{@code GET /api/history/process-instances} —— 回傳全公司歷史實例。</li>
 *   <li>{@code GET /api/process-instances/{id}/variables} —— 零授權檢查，
 *       而欄位 id == 流程變數名（spec §8.5），於是薪資等敏感表單資料任何登入者
 *       都能讀；且 {@code catch → Map.of()} 把「沒權／不存在／引擎錯誤」
 *       三種語意塌成 {@code 200 + {}}。</li>
 * </ul>
 *
 * <h2>⚠️ 為什麼狀態碼走真實 HTTP</h2>
 *
 * <p>MockMvc <b>不做 error dispatch</b>：{@code ResponseStatusException} 會被
 * 容器轉成 ERROR dispatch 打到 {@code /error}，而狀態碼正是那條路徑決定的
 * （見 {@link ErrorDispatchTest}）。用 MockMvc 寫，這些測試在缺陷存在時
 * 會照樣全綠。
 */
class ReadEndpointAuthorizationTest extends IntegrationTestBase {

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private TaskService taskService;

    private final HttpClient http = HttpClient.newHttpClient();

    /**
     * 一個確定沒有出現在任何既有測試資料裡的身分。
     *
     * <p>測試共用同一個 MSSQL 容器（見 IntegrationTestBase 類別註解），
     * 所以「user003 看不到這張單」這種斷言可能因為<b>別的測試</b>讓他變成
     * 參與者而失效。用一個沒有任何流程、沒有權限、也不在組織 fixture 裡的 id，
     * 才是真正的 outsider。
     */
    private static final String OUTSIDER = "outsider001";

    // ── HTTP 與資料小工具 ──────────────────────────────────────────

    private HttpResponse<String> get(String path, String userId) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + SERVLET_PORT + path))
                .header("X-Gateway-Secret", TestGatewayMockMvcCustomizer.GATEWAY_SECRET)
                .header("X-User-Id", userId)
                .GET()
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    /** 由 initiator 發起 leave-approval，回傳 processInstanceId。 */
    private String startCase(String initiator) {
        return runtimeService.startProcessInstanceByKey("leave-approval",
                Map.of("initiator", initiator, "leaveType", "annual", "days", 1)).getId();
    }

    /** 讓一張單走完（核准），使它變成歷史實例。 */
    private void finishCase(String pid) {
        var task = taskService.createTaskQuery().processInstanceId(pid).singleResult();
        taskService.complete(task.getId(), Map.of("approved", true, "rejected", false));
    }

    // ── GET /api/process-instances ─────────────────────────────────

    @Test
    @DisplayName("#71：帶自己的 initiator 參數 → 200")
    void ownInitiatorParameterIsAllowed() throws Exception {
        String pid = startCase("user001");
        var res = get("/api/process-instances?initiator=user001", "user001");
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).contains(pid);
    }

    @Test
    @DisplayName("#71：帶別人的 initiator → 400，不得靜默忽略")
    void foreignInitiatorIsRejected() throws Exception {
        String pid = startCase("user002");
        var res = get("/api/process-instances?initiator=user002", "user001");
        assertThat(res.statusCode())
                .as("靜默忽略會讓呼叫端以為它查得到對方的申請，而實際拿到自己的")
                .isEqualTo(400);
        assertThat(res.body())
                .as("訊息要說清楚要改什麼，否則呼叫端只會看到 400（#73）")
                .contains("initiator");
        assertThat(res.body()).doesNotContain(pid);
    }

    @Test
    @DisplayName("#71：不帶 initiator → 只回自己的，不得回傳全公司案件")
    void omittedInitiatorMeansTheCaller() throws Exception {
        String mine = startCase("user001");
        String theirs = startCase("user002");

        var res = get("/api/process-instances", "user001");

        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).contains(mine);
        assertThat(res.body())
                .as("改動前：不帶參數即 query 上沒有任何條件 → 全公司執行中案件")
                .doesNotContain(theirs);
    }

    // ── GET /api/history/process-instances ─────────────────────────

    @Test
    @DisplayName("#71：歷史清單帶別人的 initiator → 400")
    void foreignInitiatorIsRejectedOnHistory() throws Exception {
        get("/api/history/process-instances?initiator=user002", "user001");
        assertThat(get("/api/history/process-instances?initiator=user002", "user001").statusCode())
                .isEqualTo(400);
    }

    @Test
    @DisplayName("#71：歷史清單不帶 initiator → 只回自己的")
    void omittedInitiatorMeansTheCallerOnHistory() throws Exception {
        String mine = startCase("user001");
        String theirs = startCase("user002");
        finishCase(mine);
        finishCase(theirs);

        var res = get("/api/history/process-instances", "user001");

        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).contains(mine);
        assertThat(res.body())
                .as("改動前：不帶參數即回傳全公司歷史實例")
                .doesNotContain(theirs);
    }

    // ── GET /api/tasks ─────────────────────────────────────────────

    @Test
    @DisplayName("#71：待辦帶別人的 assignee / candidateUser / candidateGroups → 一律 400")
    void foreignTaskFiltersAreRejected() throws Exception {
        for (String query : new String[]{
                "assignee=user002",
                "candidateUser=user002",
                // ⚠️ candidateGroups 連「等於自己群組」的值都拒絕：
                // 呼叫端送出這個參數本身就表示它期待該值被採信。
                "candidateGroups=dept001",
                "candidateGroups=hr:leave:approve"}) {
            var res = get("/api/tasks?" + query, "user001");
            assertThat(res.statusCode())
                    .as("GET /api/tasks?" + query + " 必須被拒 —— 那是身分冒用")
                    .isEqualTo(400);
        }
    }

    @Test
    @DisplayName("#71：待辦帶自己的 assignee → 200")
    void ownAssigneeParameterIsAllowed() throws Exception {
        String pid = startCase("user001");
        String taskId = taskService.createTaskQuery().processInstanceId(pid).singleResult().getId();

        var res = get("/api/tasks?assignee=mgr001", "mgr001");

        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).contains(taskId);
    }

    @Test
    @DisplayName("#71：不帶參數的待辦只回呼叫者自己的，不得回傳全公司待辦")
    void omittedTaskFiltersMeanTheCaller() throws Exception {
        // ⚠️ 刻意挑主管不同的兩位申請人（user001→mgr001、user004→mgr002）：
        // 若兩張單的主管都是 mgr001，「別人的任務」其實也是 mgr001 的任務，
        // 測試就分辨不出修好了沒有。
        String mine = startCase("user001");
        String theirs = startCase("user004");
        String myTaskId = taskService.createTaskQuery()
                .processInstanceId(mine).singleResult().getId();
        String theirTaskId = taskService.createTaskQuery()
                .processInstanceId(theirs).singleResult().getId();
        assertThat(taskService.createTaskQuery().taskId(theirTaskId).singleResult().getAssignee())
                .as("前置條件：user004 的主管關卡不是 mgr001").isNotEqualTo("mgr001");

        var res = get("/api/tasks?assignee=mgr001", "mgr001");

        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).contains(myTaskId);
        assertThat(res.body()).doesNotContain(theirTaskId);
        // 關鍵：改動前這一段是 taskService.createTaskQuery()...list()（return all），
        // 也就是 mgr001 會看到全公司所有待辦，包含 mgr002 的。
        var all = get("/api/tasks", "mgr001");
        assertThat(all.statusCode()).isEqualTo(200);
        assertThat(all.body()).doesNotContain(theirTaskId);
    }

    @Test
    @DisplayName("#71：完全不認識的身分看不到任何案件")
    void outsiderSeesNothing() throws Exception {
        String pid = startCase("user001");
        assertThat(get("/api/process-instances", OUTSIDER).body()).doesNotContain(pid);
        assertThat(get("/api/tasks", OUTSIDER).body()).doesNotContain(pid);
    }

    // ── GET /api/process-instances/{id}/variables ──────────────────

    @Test
    @DisplayName("#71：非參與者讀他人案件變數 → 404（不是 403）")
    void variablesOfAnotherPersonsCaseAreNotFound() throws Exception {
        String pid = startCase("user001");

        var res = get("/api/process-instances/" + pid + "/variables", OUTSIDER);

        assertThat(res.statusCode())
                .as("403 會確認「這個案件存在」，對可枚舉的 id 等於把枚舉管道留著")
                .isEqualTo(404);
        assertThat(res.body())
                .as("薪資等敏感表單資料任何登入者都能讀，是這個端點最嚴重的後果")
                .doesNotContain("annual");
    }

    @Test
    @DisplayName("#71：申請人讀自己案件的變數 → 200 且拿得到變數")
    void initiatorCanReadOwnVariables() throws Exception {
        String pid = startCase("user001");

        var res = get("/api/process-instances/" + pid + "/variables", "user001");

        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).contains("annual");
    }

    @Test
    @DisplayName("#71：已結束的案件（參與者）仍回 200 + {}，前端依賴這個行為")
    void finishedCaseStillReturnsEmptyObject() throws Exception {
        String pid = startCase("user001");
        finishCase(pid);

        var res = get("/api/process-instances/" + pid + "/variables", "user001");

        // DocumentDetail.vue catch 空、variables 保持 {}、DynamicForm 仍用 schema 渲染。
        // 改成 404 會讓「審結的單打不開」。
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).isEqualTo("{}");
    }

    @Test
    @DisplayName("#71：不存在的實例 id → 404，不再是 200 + {}")
    void unknownInstanceIsNotFound() throws Exception {
        var res = get("/api/process-instances/no-such-instance-id/variables", "user001");
        assertThat(res.statusCode())
                .as("catch(Exception) → Map.of() 把「不存在」也變成 200 + {}，"
                        + "呼叫端無從分辨該重試還是該回報")
                .isEqualTo(404);
    }

    @Test
    @DisplayName("#71：稽核旁路（audit:log:read）可讀他人案件，且每次留痕")
    void auditorCanReadVariablesAndItIsRecorded() throws Exception {
        truncateAuditLog();
        String pid = startCase("user001");

        // dir001 持有 audit:log:read（權限中心 fixture），且不是這張單的參與者。
        var res = get("/api/process-instances/" + pid + "/variables", "dir001");

        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).contains("annual");

        var details = new java.util.ArrayList<String>();
        withAuditConnection(c -> {
            try (var ps = c.prepareStatement("SELECT detail FROM bpm_audit_log "
                    + "WHERE operator_id = 'dir001' AND operation_type = 'DATA_ACCESS' ORDER BY id")) {
                var rs = ps.executeQuery();
                while (rs.next()) details.add(rs.getString(1));
            }
        });
        assertThat(details).as("稽核旁路必須每次留痕，否則「誰調閱了哪些案件」無從追查")
                .isNotEmpty()
                .allMatch(d -> d.contains("\"auditBypass\":true"));
    }

    @Test
    @DisplayName("#71：參與者讀自己的變數不產生稽核旁路紀錄（那是日常操作）")
    void participantReadIsNotRecordedAsBypass() throws Exception {
        truncateAuditLog();
        String pid = startCase("user001");
        get("/api/process-instances/" + pid + "/variables", "user001");

        var count = new java.util.concurrent.atomic.AtomicInteger();
        withAuditConnection(c -> {
            try (var ps = c.prepareStatement("SELECT COUNT(*) FROM bpm_audit_log "
                    + "WHERE operator_id = 'user001' AND operation_type = 'DATA_ACCESS'")) {
                var rs = ps.executeQuery();
                if (rs.next()) count.set(rs.getInt(1));
            }
        });
        assertThat(count.get()).isZero();
    }

    @Test
    @DisplayName("#71：沒有 audit:log:read 的人不得讀他人案件的變數")
    void plainUserHasNoVariablesBypass() throws Exception {
        String pid = startCase("user001");
        // mgr001 是這張單的參與者（主管關卡），所以改用沒有權限的 outsider；
        // 這一條與 audit 旁路那條必須成組存在 —— 少了它，上一條可以靠
        // 「整條旁路失效」達成，而那正是繞過旁路限制的樣態。
        assertThat(get("/api/process-instances/" + pid + "/variables", OUTSIDER).statusCode())
                .isEqualTo(404);
    }
}
