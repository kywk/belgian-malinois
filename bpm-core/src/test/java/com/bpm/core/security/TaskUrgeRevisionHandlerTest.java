package com.bpm.core.security;

import com.bpm.core.service.ApplicantResolver;
import com.bpm.core.service.InitialAssigneeResolver;
import com.bpm.core.support.IntegrationTestBase;
import com.bpm.core.support.NotifyTestSink;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * #3（2026-10-02 裁決）：系統案件的催辦權開放給 {@code bpm:external:revision}
 * 的受理人（補件關卡的同一批人）。
 *
 * <h2>缺陷（修補前）</h2>
 *
 * <pre>
 *   外部系統 erp 發起（initiator = system:erp、無 onBehalfOf）
 *   → 案件停在主管審核
 *   → 催辦的判定是 applicantOf：onBehalfOf 沒有、initiator 不是人 → null
 *   → 沒有任何呼叫者會等於 null
 *   → 申請人不存在，所以「只有申請人可以催辦」這條規則把所有人擋在門外
 * </pre>
 *
 * <p>這是 #83（補件關卡派給 {@code system:erp}、沒有人能簽）的另一面：
 * 同一個「沒有自然人申請人」的事實，一邊讓案件卡住，一邊讓催辦永遠被拒。
 *
 * <h2>⚠️ 每一條拒絕都要同時證明兩件事</h2>
 *
 * <p>授權缺陷有兩種失敗型態，只看狀態碼分辨不出來：
 * <ol>
 *   <li><b>拒絕了不該拒的人</b>（缺陷本身）—— 例如受理人催辦拿到 404；</li>
 *   <li><b>拒絕時仍留下副作用</b> —— 通知被送出、TASK_URGE 被寫入、
 *       或 30 分鐘冷卻被消耗（下一位合法的催辦反而拿到 429）。</li>
 * </ol>
 * 因此每條負向案例都配「零 task_urged、零 TASK_URGE、案件未變」；
 * 查無受理人那條還加驗「拿掉空快取後受理人立刻催得動」，
 * 那是冷卻未被消耗的最強證明（沿用 {@code NotifyTriggerIntegrationTest} 的手法）。
 *
 * <h2>⚠️ 自然人案件是負向對照，不是遺漏</h2>
 *
 * <p>裁決只把<b>系統案件</b>的催辦權開放給受理人。自然人案件
 * （{@code initiator=user001}）即使受理人 {@code dir001} 是目前的任務
 * 持有者，也只拿到與其他參與者相同的 403 —— 若哪天有人把第三段
 * 「順手」套到所有案件上，這一條會紅。
 *
 * <h2>負向控制組實測（2026-10-03：把 {@code urgeTask} 的申請人判定還原成缺陷期的
 * {@code applicantOf}，只改這一行）</h2>
 *
 * <p><b>4 條中 2 紅 2 綠。</b>
 *
 * <table border="1">
 *   <caption>負向控制組結果</caption>
 *   <tr><th>結果</th><th>測試</th><th>意義</th></tr>
 *   <tr><td>🔴 紅</td>
 *       <td>{@code revisionHandlerCanUrgeSystemCase}<br>
 *           {@code noHandlerMeansNoOneCanUrge}</td>
 *       <td>缺陷期間系統案件沒有任何人是催辦人：受理人 dir001 催辦
 *           拿到 <b>404</b>（預期 200）。第二條紅在最後一段「拿掉空快取後
 *           受理人立刻催得動」—— 缺陷期間他永遠催不動；該條前面的
 *           「查無受理人 → 403／404、零副作用」在缺陷期也<b>是綠的</b>
 *           （{@code applicant} 恆為 null），所以只證明得了拒絕形狀，
 *           證明不了新分支。</td></tr>
 *   <tr><td>🟢 綠</td>
 *       <td>{@code nonHandlerCannotUrgeSystemCase}<br>
 *           {@code revisionHandlerCannotUrgeHumanCase}</td>
 *       <td>它們在缺陷期間就該綠（拒絕分流與自然人案件本來就不受影響），
 *           證明不了新功能。價值在於防止錯誤的修法：把 403／404 放寬成 200
 *           會讓前者紅，把第三段套用到自然人案件會讓後者紅。</td></tr>
 * </table>
 *
 * <p>控制組還原後 4 條全綠；正常版本（新分支存在）由本檔的其他測試
 * 與 {@code ApplicantResolverSharedRuleTest}／
 * {@code TaskUrgeApplicantResolutionTest} 共同釘住。
 */
class TaskUrgeRevisionHandlerTest extends IntegrationTestBase {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** dev fixture 裡 {@code bpm:external:revision} 的持有人（見 MockPermController）。 */
    private static final String HANDLER = "dir001";

    /**
     * {@code BpmPermissionService} 的「權限持有人」快取 key
     * （{@code globalKey}）。直接寫入等於替權限中心回答，
     * 用來模擬「這個權限碼沒有任何持有人」這個真實可發生的狀態
     * —— 同型操作見 {@code CacheConsistencyTest.emptyPermissionHolderListIsCached}。
     */
    private static final String PERM_CACHE_KEY =
            "perm:users:" + ApplicantResolver.PERM_EXTERNAL_REVISION;

    @Autowired private RuntimeService runtimeService;
    @Autowired private TaskService taskService;
    @Autowired private RabbitTemplate rabbitTemplate;
    @Autowired private AmqpAdmin amqpAdmin;
    @Autowired private StringRedisTemplate redis;

    @BeforeEach
    void setUp() {
        NotifyTestSink.install(rabbitTemplate, amqpAdmin);
        NotifyTestSink.reset();
        truncateAuditLog();
        // 前一輪執行可能把「沒有受理人」的空清單留在快取（TTL 5 分鐘），
        // 對其他測試是跨類別的污染，開頭先清掉。
        redis.delete(PERM_CACHE_KEY);
    }

    @AfterEach
    void clearPermCache() {
        redis.delete(PERM_CACHE_KEY);
    }

    // ── 工具 ────────────────────────────────────────────────────────

    /** 一張單與它目前的審核任務。 */
    private record Case(String pid, String managerTaskId) {}

    /**
     * 系統案件：{@code initiator=system:erp}、無 {@code onBehalfOf}，
     * 停在主管審核（assignee = mgr001）。
     *
     * <p>{@code firstTaskAssignee} 是必須的 —— 不指定時第一關會去查
     * {@code system:erp} 的主管，而組織 mock 對不認識的 userId fail-closed。
     */
    private Case startSystemCase() {
        var pi = runtimeService.startProcessInstanceByKey("leave-approval",
                Map.of("initiator", "system:erp",
                        InitialAssigneeResolver.FIRST_ASSIGNEE_VAR, "mgr001",
                        "leaveType", "annual", "days", 1));
        Task manager = taskService.createTaskQuery().processInstanceId(pi.getId()).singleResult();
        assertThat(manager.getAssignee())
                .as("前置條件：第一關必須有真人受理人，否則催辦會先撞到 409")
                .isEqualTo("mgr001");
        return new Case(pi.getId(), manager.getId());
    }

    /** 這個案件的 TASK_URGE 稽核列：{operator_id, detail}，依 id。 */
    private List<String[]> urgeAuditRows(String pid) {
        List<String[]> out = new ArrayList<>();
        withAuditConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT operator_id, detail FROM bpm_audit_log "
                            + "WHERE operation_type = 'TASK_URGE' AND process_instance_id = ? "
                            + "ORDER BY id")) {
                ps.setString(1, pid);
                var rs = ps.executeQuery();
                while (rs.next()) out.add(new String[]{rs.getString(1), rs.getString(2)});
            }
        });
        return out;
    }

    /** 恰好一則 task_urged 通知；更多或更少都是失敗。 */
    private Map<String, Object> onlyTaskUrged() {
        List<Map<String, Object>> urged = NotifyTestSink.events(NotifyTestSink.drain(), "task_urged");
        assertThat(urged).hasSize(1);
        return urged.get(0);
    }

    // ── 正向：受理人可以催辦系統案件 ────────────────────────────────

    @Test
    @DisplayName("#3：系統案件 → 受理人 dir001 催辦成功（通知＋TASK_URGE，operator=呼叫者）")
    void revisionHandlerCanUrgeSystemCase() throws Exception {
        Case c = startSystemCase();

        mockMvc.perform(post("/api/tasks/urge")
                        .header("X-User-Id", HANDLER)
                        .param("processInstanceId", c.pid()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recipients[0]").value("mgr001"))
                .andExpect(jsonPath("$.cooldownMinutes").value(30));

        // 非空斷言：通知真的送出了，而且是送給案件目前的受理人。
        Map<String, Object> urged = onlyTaskUrged();
        assertThat(urged)
                .containsEntry("assignee", "mgr001")
                .containsEntry("processInstanceId", c.pid())
                .as("payload 的 initiator 是發動催辦的人（受理人），不是 engine 的 system:erp")
                .containsEntry("initiator", HANDLER);

        // 非空斷言：稽核真的在資料庫裡，operator 是呼叫者。
        List<String[]> rows = urgeAuditRows(c.pid());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)[0]).isEqualTo(HANDLER);
        Map<String, Object> detail = MAPPER.readValue(rows.get(0)[1], new TypeReference<>() {});
        assertThat(detail)
                .containsOnlyKeys("cooldownMinutes", "taskCount", "recipientCount")
                .containsEntry("cooldownMinutes", 30)
                .containsEntry("taskCount", 1)
                .containsEntry("recipientCount", 1);
    }

    // ── 拒絕：不是受理人 ← 沿用既有 403／404 分流 ───────────────────

    @Test
    @DisplayName("#3：系統案件＋非受理人 → 參與者 403、無關者 404；零通知、零 TASK_URGE")
    void nonHandlerCannotUrgeSystemCase() throws Exception {
        Case c = startSystemCase();

        // mgr001 是這張單目前的任務持有者（參與者），但不是催辦受理人。
        mockMvc.perform(post("/api/tasks/urge")
                        .header("X-User-Id", "mgr001")
                        .param("processInstanceId", c.pid()))
                .andExpect(status().isForbidden());

        // user002 與這張單毫無關係 → 404（不新增枚舉管道）。
        mockMvc.perform(post("/api/tasks/urge")
                        .header("X-User-Id", "user002")
                        .param("processInstanceId", c.pid()))
                .andExpect(status().isNotFound());

        assertThat(NotifyTestSink.events(NotifyTestSink.drain(), "task_urged"))
                .as("被拒的催辦不得送通知")
                .isEmpty();
        assertThat(urgeAuditRows(c.pid()))
                .as("被拒的催辦不得宣告一件沒發生的事")
                .isEmpty();

        Task manager = taskService.createTaskQuery().taskId(c.managerTaskId()).singleResult();
        assertThat(manager).as("被拒的請求不得改動案件").isNotNull();
        assertThat(manager.getAssignee()).isEqualTo("mgr001");
    }

    // ── 負向對照：自然人案件不受影響 ────────────────────────────────

    @Test
    @DisplayName("#3 負向對照：自然人案件的受理人 dir001 仍然不能催辦 → 403（不得放寬）")
    void revisionHandlerCannotUrgeHumanCase() throws Exception {
        // initiator 是人（user001），dir001 只是這張單目前的任務受理人。
        // 第三段（權限碼指定的受理人）只適用於「沒有自然人申請人」的案件；
        // 若哪天有人讓它對所有案件生效，這一條會紅。
        var pi = runtimeService.startProcessInstanceByKey("leave-approval",
                Map.of("initiator", "user001",
                        InitialAssigneeResolver.FIRST_ASSIGNEE_VAR, "dir001",
                        "leaveType", "annual", "days", 1));
        Task manager = taskService.createTaskQuery().processInstanceId(pi.getId()).singleResult();
        assertThat(manager.getAssignee())
                .as("前置條件：dir001 是這張單的任務持有者（參與者），測的才是『參與者非申請人』")
                .isEqualTo("dir001");

        mockMvc.perform(post("/api/tasks/urge")
                        .header("X-User-Id", HANDLER)
                        .param("processInstanceId", pi.getId()))
                .andExpect(status().isForbidden());

        assertThat(NotifyTestSink.events(NotifyTestSink.drain(), "task_urged")).isEmpty();
        assertThat(urgeAuditRows(pi.getId())).isEmpty();
        assertThat(taskService.createTaskQuery().taskId(manager.getId()).singleResult()).isNotNull();
    }

    // ── 邊界：權限中心查無受理人 → 不得放行，也不得留副作用 ──────────

    @Test
    @DisplayName("#3：權限中心查無受理人 → 非 200、零副作用（不得 fail-open）")
    void noHandlerMeansNoOneCanUrge() throws Exception {
        Case c = startSystemCase();

        // 模擬權限中心回「沒有任何持有人」。BpmPermissionService 會把空清單
        // 也快取起來，所以直接寫快取等於替它回答（空字串 → splitCsv → 空清單）。
        redis.opsForValue().set(PERM_CACHE_KEY, "");

        // 案件目前的持有者是參與者，但此刻不存在任何受理人 → 403。
        mockMvc.perform(post("/api/tasks/urge")
                        .header("X-User-Id", "mgr001")
                        .param("processInstanceId", c.pid()))
                .andExpect(status().isForbidden());

        // 權限碼的持有人本人也不行 ——「受理人」這個答案此刻不存在，
        // 不能因為他是 fixture 裡的 dir001 就放行。
        mockMvc.perform(post("/api/tasks/urge")
                        .header("X-User-Id", HANDLER)
                        .param("processInstanceId", c.pid()))
                .andExpect(status().isNotFound());

        assertThat(NotifyTestSink.events(NotifyTestSink.drain(), "task_urged")).isEmpty();
        assertThat(urgeAuditRows(c.pid())).isEmpty();

        // 零副作用的最強證明：拿掉「沒有受理人」的快取後，受理人立刻
        // 催得動 —— 前面兩次被拒都沒有消耗 30 分鐘冷卻。
        redis.delete(PERM_CACHE_KEY);
        mockMvc.perform(post("/api/tasks/urge")
                        .header("X-User-Id", HANDLER)
                        .param("processInstanceId", c.pid()))
                .andExpect(status().isOk());
        assertThat(NotifyTestSink.events(NotifyTestSink.drain(), "task_urged")).hasSize(1);
    }
}
