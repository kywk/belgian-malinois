package com.bpm.core.audit;

import com.bpm.core.support.IntegrationTestBase;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 稽核 fail-closed（security-audit P1-14，2026-09-29 決策）：
 * 稽核寫不進去，業務操作就不成立。
 *
 * <p>每個測試都斷言兩件事：回應是 503，<b>而且業務資料沒有落地</b>。
 * 只斷言 503 是空的 —— controller 沒有 {@code @Transactional} 時，
 * Flowable 呼叫早已各自 commit，回 503 但資料已改，比 fail-open 更糟。
 *
 * <p><b>如何製造稽核失敗：</b>在 {@code bpm_audit_log} 上掛一個測試用的
 * {@code AFTER INSERT} 觸發器直接 {@code THROW}。這是真的 DB 端失敗，
 * 走的是與正式環境相同的例外路徑。
 * 不用 {@code @MockitoSpyBean}：它會另起一個 ApplicationContext，而
 * {@code IntegrationTestBase} 用 {@code DEFINED_PORT}，第二個 context 會撞 port。
 */
class AuditFailClosedTest extends IntegrationTestBase {

    @Autowired
    private RepositoryService repositoryService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private TaskService taskService;

    private static final String KEY = "audit-fail-closed-single";

    private static final String FAIL_TRIGGER = "trg_test_audit_fail_closed";

    /** 讓符合 LIKE 樣式的 operation_type 寫入時直接失敗。 */
    private static void failAuditInsertsWhere(String operationTypeLike) {
        dropFailTrigger();
        withAuditConnection(c -> {
            try (Statement st = c.createStatement()) {
                st.execute("CREATE TRIGGER " + FAIL_TRIGGER + " ON bpm_audit_log AFTER INSERT AS "
                        + "IF EXISTS (SELECT 1 FROM inserted WHERE operation_type LIKE '"
                        + operationTypeLike + "') THROW 50000, N'模擬稽核 DB 寫入失敗', 1;");
            }
        });
    }

    @AfterEach
    void removeFailTrigger() {
        dropFailTrigger();
    }

    private static void dropFailTrigger() {
        withAuditConnection(c -> {
            try (Statement st = c.createStatement()) {
                st.execute("IF OBJECT_ID('" + FAIL_TRIGGER + "', 'TR') IS NOT NULL DROP TRIGGER " + FAIL_TRIGGER);
            }
        });
    }

    @BeforeEach
    void deploy() {
        if (repositoryService.createProcessDefinitionQuery().processDefinitionKey(KEY).count() > 0) return;
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:flowable="http://flowable.org/bpmn"
                             targetNamespace="test">
                  <process id="%s" name="單關卡" isExecutable="true">
                    <startEvent id="start"/>
                    <sequenceFlow id="f0" sourceRef="start" targetRef="task"/>
                    <userTask id="task" name="簽核" flowable:assignee="user001"/>
                    <sequenceFlow id="f1" sourceRef="task" targetRef="end"/>
                    <endEvent id="end"/>
                  </process>
                </definitions>
                """.formatted(KEY);
        repositoryService.createDeployment().addString(KEY + ".bpmn20.xml", xml).name(KEY).deploy();
    }

    private void auditAlwaysFails() {
        failAuditInsertsWhere("%");
    }

    private String startDirectly() {
        return runtimeService.startProcessInstanceByKey(KEY, UUID.randomUUID().toString(),
                Map.of("initiator", "user001")).getId();
    }

    private String completeBody() {
        return "{\"action\":\"complete\",\"variables\":[{\"name\":\"approved\",\"value\":true}]}";
    }

    @Test
    @DisplayName("簽核時稽核失敗 → 503，且任務仍在（簽核未生效）")
    void taskCompletionRollsBackWhenAuditFails() throws Exception {
        String pid = startDirectly();
        String taskId = taskService.createTaskQuery().processInstanceId(pid).singleResult().getId();
        auditAlwaysFails();

        mockMvc.perform(put("/api/tasks/" + taskId)
                        .contentType(MediaType.APPLICATION_JSON).content(completeBody()))
                .andExpect(status().isServiceUnavailable());

        assertThat(taskService.createTaskQuery().taskId(taskId).count())
                .as("稽核寫不進去，簽核就不能生效 —— 任務必須還在")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("結案稽核（引擎 listener）失敗 → 503，且流程未結案")
    void processCompletionRollsBackWhenCompletionAuditFails() throws Exception {
        // 這條路徑特別：ProcessCompletedListener 的 isFailOnException() 是 false，
        // 在 listener 裡拋例外會被 Flowable 吞掉。只有掛在交易 beforeCommit 上，
        // 失敗才會真的讓結案回滾。
        String pid = startDirectly();
        String taskId = taskService.createTaskQuery().processInstanceId(pid).singleResult().getId();
        failAuditInsertsWhere("PROCESS_COMPLETE");

        mockMvc.perform(put("/api/tasks/" + taskId)
                        .contentType(MediaType.APPLICATION_JSON).content(completeBody()))
                .andExpect(status().isServiceUnavailable());

        assertThat(runtimeService.createProcessInstanceQuery().processInstanceId(pid).count())
                .as("結案的稽核沒寫進去，流程就不能算結案")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("啟動流程時稽核失敗 → 503，且沒有留下流程實例")
    void processStartRollsBackWhenAuditFails() throws Exception {
        long before = runtimeService.createProcessInstanceQuery().processDefinitionKey(KEY).count();
        auditAlwaysFails();

        mockMvc.perform(post("/api/process-instances")
                        .contentType(MediaType.APPLICATION_JSON)
                        // ⚠️ 不可帶 initiator（#66）：body 帶 initiator 會在進入
                        // 稽核之前就被明確拒絕成 400。斷言 503 就會失敗，
                        // 而失敗訊息會指向「狀態碼不符」——把排查方向帶到
                        // 完全無關的地方。
                        //
                        // 所以移除該欄位而不是改預期值：預設身分是 user001，
                        // server 寫入的 initiator 與原本送的值相同，
                        // 測的仍然是同一件事（稽核寫不進去 → 不得留下流程實例）。
                        .content("{\"processDefinitionKey\":\"" + KEY + "\"}"))
                .andExpect(status().isServiceUnavailable());

        assertThat(runtimeService.createProcessInstanceQuery().processDefinitionKey(KEY).count())
                .as("稽核失敗時不得留下已啟動的流程").isEqualTo(before);
    }

    @Test
    @DisplayName("表單送出（bpm_form_db）時稽核失敗 → 503，且表單資料沒有落地")
    void formSubmitRollsBackWhenAuditFails() throws Exception {
        String pid = "fail-closed-" + UUID.randomUUID();
        auditAlwaysFails();

        mockMvc.perform(post("/api/form-data")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"formDefinitionId\":\"x\",\"processInstanceId\":\"" + pid
                                + "\",\"submittedBy\":\"user001\",\"dataJson\":\"{}\"}"))
                .andExpect(status().isServiceUnavailable());

        int[] rows = {-1};
        withFormConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COUNT(*) FROM bpm_form_data WHERE process_instance_id = ?")) {
                ps.setString(1, pid);
                var rs = ps.executeQuery();
                rs.next();
                rows[0] = rs.getInt(1);
            }
        });
        assertThat(rows[0]).as("表單 DB 與稽核 DB 不同，交易必須開在 formTransactionManager 上才回滾得掉")
                .isZero();
    }

    @Test
    @DisplayName("查詢稽核（唯讀 DATA_ACCESS）時稽核失敗 → 503，不回傳資料")
    void readAuditFailureWithholdsData() throws Exception {
        auditAlwaysFails();
        mockMvc.perform(get("/api/audit-logs/integrity-check").header("X-User-Id", "admin001")
                        .param("startDate", "2026-01-01T00:00:00Z").param("endDate", "2027-01-01T00:00:00Z"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    @DisplayName("拒絕存取的稽核失敗時，仍維持原本的拒絕回應（不變成 503）")
    void denialStaysDeniedWhenAuditFails() throws Exception {
        auditAlwaysFails();
        mockMvc.perform(get("/api/attachments")
                        .param("processInstanceId", "not-mine-" + UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }
}
