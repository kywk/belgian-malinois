package com.bpm.core.engine;

import com.bpm.core.support.IntegrationTestBase;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #89：「有候選人」不等於「候選人看得到」。
 *
 * <h2>缺陷：判準與 Flowable 的實際查詢條件不一致</h2>
 *
 * <p>{@code UnreachableTaskListener} 對「有候選人」一律當成有人能處理
 * （#83 從「assignee 是不是空」改成「有沒有人能動它」時留下的判準），
 * 只把 {@code system:} 前綴挖掉。
 *
 * <p>但 Flowable 的候選人查詢帶著 {@code RES.ASSIGNEE_ IS NULL}
 * （{@code Task.xml} 的 {@code selectTaskByCandidateGroup*}），所以
 * <b>只要 ASSIGNEE_ 不是 null，候選人就看不到那個任務</b>。
 *
 * <p>而 {@code setAssignee(taskId, "")} 產生的是<b>空字串不是 null</b>。
 * 於是出現這個形狀：<b>有候選人、assignee 是空字串</b> ——
 * 群組成員在待辦清單裡看不到它，listener 也不告警，案件從第一關就卡住。
 *
 * <h2>⚠️ 實測修正了工項描述中的一個前提</h2>
 *
 * <p>工項描述說「BPMN 的 {@code flowable:assignee=""} 字面值仍能產生它」。
 * <b>實測不成立</b>：{@code UserTaskActivityBehavior.handleAssignments} 對
 * assignee 有 {@code StringUtils.isNotEmpty} 前置判斷，空字串<b>不會被寫入</b>。
 * 實測 {@code flowable:assignee=""} 與 {@code flowable:assignee=" "}
 * 產生的 assignee 都是 <b>null</b>，候選群組查得到、listener 也不告警 ——
 * <b>那條路徑的行為是正確的</b>。{@code bpmnLiteralIsNotTheDefect} 把這個事實釘住。
 *
 * <p><b>但缺陷是真的</b>，有兩條真的能產生的路徑，皆為實測：
 * <ol>
 *   <li>{@code setAssignee(taskId, "")} —— {@code TaskService.setAssignee}
 *       沒有 isNotEmpty 判斷，空字串<b>原樣寫入</b>。
 *       這是 {@code ExternalApiController} 在帶候選群組時的形狀（#88 已擋）。</li>
 *   <li>{@code flowable:assignee="${var}"} 而 {@code var} 為空白字串 ——
 *       運算式求值<b>繞過</b>那道 isNotEmpty（判斷的是運算式<b>字串</b>非空，
 *       不是求值<b>結果</b>非空），所以 {@code "  "} 照樣寫入。
 *       <b>這一條管理員部署的流程就能觸發，不經任何 API。
 *       ⚠️ 已由 #91 方向 B 根治，見下一節。</b></li>
 * </ol>
 *
 * <h2>⚠️ 2026-10-01（#91 方向 B）：路徑 2 已在寫入端根治</h2>
 *
 * <p>路徑 2（{@code flowable:assignee="${var}"} 求值為空白）<b>已由
 * {@link BlankAssigneeNormalizingInterceptor}（全域 {@code CreateUserTaskInterceptor}）
 * 根治</b>：它在 {@code handleAssignments} 之後把空白 assignee 收斂成 null。
 * 因此這條路徑<b>不再產生空白 assignee</b> → 候選群組查得到 → 不告警。
 * 這一條的性質從「重現 #89 缺陷」變成「#91 方向 B 的迴歸釘」，
 * 測試名也改為 {@code blankAssigneeFromExpressionIsNormalizedByDirectionB}。
 *
 * <p>⚠️ <b>這不代表 {@code assignee-blocks-candidates} 這個 reason 死了。</b>
 * 剩下的可達路徑<b>只有 API 寫入端</b>（路徑 1）：{@code TaskService.setAssignee}
 * 在任務<b>建立之後</b>才寫值，不經過只管建立任務的 interceptor，仍會把空字串
 * 原樣寫入。所以 {@code blankAssigneeSetByApiIsAlerted} 與
 * {@code whitespaceAssigneeIsAlerted} 仍走舊路徑、仍告警
 * {@code assignee-blocks-candidates} —— <b>那兩條就是這個 reason 還活著的證據。</b>
 *
 * <h2>非回歸對照組是必要的，不是多餘的</h2>
 *
 * <p>{@code nullAssigneeWithCandidateIsStillNotAlerted} 與
 * {@code candidateAddedInSameTransactionIsStillNotAlerted} 不是「多測一點」：
 * 一個「全部都告警」的實作能讓下面<b>兩條</b>紅的測試（API 寫入端）全部通過。
 * <b>兩組必須成組存在</b>，否則這個判準可以被「一律告警」 trivially 滿足。
 *
 * <h2>為什麼每條都附帶「候選人查得到嗎」的斷言</h2>
 *
 * <p>只斷言「有告警」不足以證明修的是對的東西 —— 它可能只是<b>告警得太寬</b>。
 * 所以<b>兩條 API 寫入端的缺陷測試</b>都先斷言
 * {@code taskCandidateGroup(...).count() == 0}：<b>證實候選人確實看不到</b>，
 * 告警才是對應到真實的卡死，而不是憑空亂報。
 * 這是「判準與 assignee 的實際形狀一致」的實證，而不是只看 listener 的輸出。
 *
 * <p>⚠️ #91 方向 B 之後，{@code blankAssigneeFromExpressionIsNormalizedByDirectionB}
 * 反過來斷言 {@code count() == 1}：它要證明的正是「修好之後候選人<b>真的看得到</b>」，
 * 與上面兩條互為對照。
 *
 * <h2>⚠️ 負向控制組實測（2026-09-30，整份還原缺陷版本後重跑）</h2>
 *
 * <p>還原方式：<b>整份還原</b> {@code UnreachableTaskListener.java} 到 HEAD
 * （{@code git show HEAD:...} 寫出，不是只改回那一行）。
 * 理由見 round-3 handoff 第 4.4 節 —— 把缺陷放回錯誤的位置會讓它反過來
 * 擋掉一切、測試全綠，等於沒驗到。
 *
 * <p><b>8 條中 3 紅 5 綠</b>。綠的那五條<b>不是漏抓</b>，而是他們存在的理由。
 *
 * <p>⚠️ <b>2026-10-01（#91 方向 B）後，這張表有一處變動</b>：原列為紅的
 * {@code blankAssigneeFromExpressionIsAlerted}（已改名為
 * {@code blankAssigneeFromExpressionIsNormalizedByDirectionB}）<b>不再是缺陷
 * 重現，也不在負向控制組裡了</b> —— 原因見下一段。其餘敘述保留原意。
 *
 * <table border="1">
 *   <caption>負向控制組結果（2026-09-30 實測；2026-10-01 移除一條，見下方說明）</caption>
 *   <tr><th>結果</th><th>測試</th><th>意義</th></tr>
 *   <tr><td>🔴 紅</td>
 *       <td>{@code blankAssigneeSetByApiIsAlerted}<br>
 *           {@code whitespaceAssigneeIsAlerted}</td>
 *       <td>缺陷期間 {@code hasCandidate && !assigneeIsSystemActor} 讓候選人
 *           「救」成功 → 直接 return → 一筆告警都沒有。實際是
 *           {@code expected: assignee-blocks-candidates but was: []}。</td></tr>
 *   <tr><td>🟢 綠</td>
 *       <td>{@code bpmnLiteralIsNotTheDefect}<br>
 *           {@code nullAssigneeWithCandidateIsStillNotAlerted}<br>
 *           {@code humanAssigneeIsStillNotAlerted}<br>
 *           {@code missingAssigneeKeepsOriginalReason}<br>
 *           {@code candidateAddedInSameTransactionIsStillNotAlerted}</td>
 *       <td>它們與本缺陷無關（缺陷期間就該綠）。<b>重點是修好之後仍必須綠</b> ——
 *           一個「有候選人就全部告警」的實作能讓兩條紅的通過，卻會讓這五條紅。
 *           兩組必須成組存在。</td></tr>
 * </table>
 *
 * <h3>⚠️ 為什麼把 {@code blankAssigneeFromExpressionIsAlerted} 移出負向控制組</h3>
 *
 * <p>它在 2026-09-30 的負向控制組裡<b>確實是紅的</b>，但那是在 #91 方向 B
 * 之前。方向 B 把 BPMN 運算式的空白 assignee 在<b>寫入端</b>收斂成 null，
 * 於是這條測試驗的對象整個換了：從「重現缺陷（空白 assignee + 候選人看不到
 * + 告警）」變成「<b>方向 B 的迴歸釘</b>（assignee 為 null + 候選人看得到 +
 * 不告警）」。<b>性質變了，所以不能繼續放在「缺陷期間該紅」的表裡。</b>
 * 這不是把歷史刪掉 —— 上述 2026-09-30 的紀錄與「3 紅」的數字原樣保留，
 * 只是標明它現在的角色。
 *
 * <p>⚠️ 另外兩組測試（{@code UnreachableTaskAlertTest} 3 條、
 * {@code UnreachableSystemAssigneeAlertTest} 6 條）在本輪缺陷期間<b>全綠</b>，
 * 證明本工項沒有動到 #83 的判準與既有告警行為。
 */
class UnreachableBlankAssigneeAlertTest extends IntegrationTestBase {

    @Autowired private RepositoryService repositoryService;
    @Autowired private RuntimeService runtimeService;
    @Autowired private TaskService taskService;
    @Autowired @Qualifier("primaryTransactionManager") private PlatformTransactionManager primaryTx;

    private static final String BLANK_VIA_API = "b89-blank-via-api";
    private static final String BLANK_VIA_EXPRESSION = "b89-blank-via-expression";
    private static final String BLANK_LITERAL = "b89-blank-literal";
    private static final String CANDIDATE_ONLY = "b89-candidate-only";
    private static final String HUMAN = "b89-human";

    /** 候選群組名稱。與其他測試共用同一個群組不影響 —— 查詢都帶 taskId。 */
    private static final String GROUP = "dept-hr";

    private static String bpmn(String key, String taskAttrs) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:flowable="http://flowable.org/bpmn" targetNamespace="test">
                  <process id="%s" isExecutable="true">
                    <startEvent id="start"/>
                    <sequenceFlow id="f0" sourceRef="start" targetRef="task"/>
                    <userTask id="task" name="測試關卡" %s/>
                    <sequenceFlow id="f1" sourceRef="task" targetRef="end"/>
                    <endEvent id="end"/>
                  </process>
                </definitions>
                """.formatted(key, taskAttrs);
    }

    @BeforeEach
    void deploy() {
        // 候選群組一律寫在 BPMN 上（不是交易內補的），這樣「有候選人」是
        // 建立當下就成立的事實 —— 排除掉 UnreachableTaskAlertTest 那個
        // 「同一交易內才補上候選人」的合法模式。
        if (repositoryService.createProcessDefinitionQuery().processDefinitionKey(BLANK_VIA_API).count() == 0) {
            repositoryService.createDeployment().addString(BLANK_VIA_API + ".bpmn20.xml",
                    bpmn(BLANK_VIA_API, "flowable:candidateGroups=\"" + GROUP + "\"")).deploy();
            // 路徑 2：運算式求值為空白 → 原本繞過 isNotEmpty。
            // ⚠️ #91 方向 B 之後，這一條已是「方向 B 的迴歸釘」：空白 assignee
            // 會被正規化成 null，不再產生缺陷形狀（見類別 javadoc）。
            repositoryService.createDeployment().addString(BLANK_VIA_EXPRESSION + ".bpmn20.xml",
                    bpmn(BLANK_VIA_EXPRESSION,
                            "flowable:assignee=\"${blankVar}\" flowable:candidateGroups=\"" + GROUP + "\"")).deploy();
            // 非缺陷路徑：BPMN 字面值空字串。釘住「它不會產生空字串 assignee」。
            repositoryService.createDeployment().addString(BLANK_LITERAL + ".bpmn20.xml",
                    bpmn(BLANK_LITERAL,
                            "flowable:assignee=\"\" flowable:candidateGroups=\"" + GROUP + "\"")).deploy();
            // 對照組：assignee=null + 有候選群組 → 必須不告警。
            repositoryService.createDeployment().addString(CANDIDATE_ONLY + ".bpmn20.xml",
                    bpmn(CANDIDATE_ONLY, "flowable:candidateGroups=\"" + GROUP + "\"")).deploy();
            // 對照組：真人 assignee → 必須不告警。
            repositoryService.createDeployment().addString(HUMAN + ".bpmn20.xml",
                    bpmn(HUMAN, "flowable:assignee=\"mgr001\"")).deploy();
        }
    }

    /** TASK_UNREACHABLE 告警的 reason 欄位（分辨三種成因）。 */
    private List<String> alertReasons(String pid) {
        var out = new ArrayList<String>();
        withAuditConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT detail FROM bpm_audit_log "
                    + "WHERE operation_type = 'TASK_UNREACHABLE' AND process_instance_id = ?")) {
                ps.setString(1, pid);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String detail = rs.getString(1);
                        if (detail.contains("assignee-is-system-identity")) out.add("assignee-is-system-identity");
                        else if (detail.contains("assignee-blocks-candidates")) out.add("assignee-blocks-candidates");
                        else out.add("no-assignee-no-candidate");
                    }
                }
            }
        });
        return out;
    }

    /** 候選群組查得到這個任務嗎？0 = 群組成員在待辦清單裡看不到它。 */
    private long candidatesCanSee(String pid) {
        var t = taskService.createTaskQuery().processInstanceId(pid).singleResult();
        return t == null ? -1 : taskService.createTaskQuery()
                .taskCandidateGroup(GROUP).taskId(t.getId()).count();
    }

    // ── 缺陷：空字串 assignee + 有候選人 ──────────────────────────────

    @Test
    @DisplayName("#89：assignee 是空字串 + 有候選群組 → 必須告警（缺陷期間靜默卡死且不告警）")
    void blankAssigneeSetByApiIsAlerted() {
        String pid = new TransactionTemplate(primaryTx).execute(status -> {
            String id = runtimeService.startProcessInstanceByKey(BLANK_VIA_API, Map.of()).getId();
            String taskId = taskService.createTaskQuery().processInstanceId(id).singleResult().getId();
            // 這是 ExternalApiController 帶候選群組時的形狀（#88 已在輸入端擋掉，
            // 但 TaskService.setAssignee 本身沒有防護，其他寫入端仍送得出來）。
            taskService.setAssignee(taskId, "");
            return id;
        });

        String assignee = taskService.createTaskQuery().processInstanceId(pid).singleResult().getAssignee();
        assertThat(assignee)
                .as("前置條件：assignee 確實是空字串而**不是 null** —— "
                        + "這是整個缺陷的根，null 的話候選人查得到，本來就沒問題")
                .isNotNull().isEmpty();

        assertThat(candidatesCanSee(pid))
                .as("前置條件：候選群組成員確實看不到這個任務（ASSIGNEE_ IS NULL 不命中）。"
                        + "少了這條斷言就無法分辨「修對了」與「只是告警得太寬」")
                .isZero();

        assertThat(alertReasons(pid))
                .as("有候選人但沒有人看得到 —— 必須告警。缺陷期間這一格是空的")
                .containsExactly("assignee-blocks-candidates");
    }

    @Test
    @DisplayName("#89：assignee 為純空白 + 有候選群組 → 必須告警（空白與空字串對 ASSIGNEE_ IS NULL 等價）")
    void whitespaceAssigneeIsAlerted() {
        String pid = new TransactionTemplate(primaryTx).execute(status -> {
            String id = runtimeService.startProcessInstanceByKey(BLANK_VIA_API, Map.of()).getId();
            String taskId = taskService.createTaskQuery().processInstanceId(id).singleResult().getId();
            taskService.setAssignee(taskId, "  ");
            return id;
        });

        assertThat(candidatesCanSee(pid)).as("純空白同樣讓候選人查不到").isZero();
        assertThat(alertReasons(pid))
                .as("只擋 isEmpty() 而漏掉 isBlank() 會讓這一格還是空的")
                .containsExactly("assignee-blocks-candidates");
    }

    @Test
    @DisplayName("#91 方向 B：BPMN 運算式求值為空白 + 有候選群組 → 正規化成 null、候選人看得到、不告警"
            + "（#89 的這條路徑已由方向 B 根治）")
    void blankAssigneeFromExpressionIsNormalizedByDirectionB() {
        // ⚠️ 這一條原本是 #89「不需要任何寫入端配合」的缺陷路徑：管理員部署一個
        // flowable:assignee="${var}" 的流程，有人把 var 設成空白就會命中。
        // #91 方向 B 的 BlankAssigneeNormalizingInterceptor 在 handleAssignments
        // 之後把空白 assignee 收斂成 null，於是這條路徑不再產生空白 assignee。
        //
        // 因此本測試的斷言整個反轉：不再是「非 null 空白 + 看不到 + 告警」，
        // 而是「null + 看得到 + 不告警」。舊斷言釘的是缺陷期間的舊行為。
        String pid = runtimeService.startProcessInstanceByKey(BLANK_VIA_EXPRESSION,
                Map.of("blankVar", "   ")).getId();

        assertThat(taskService.createTaskQuery().processInstanceId(pid).singleResult().getAssignee())
                .as("方向 B 之後：運算式求值為空白會被正規化成 null，"
                        + "不再是「非 null 的空白字串」")
                .isNull();

        assertThat(candidatesCanSee(pid))
                .as("正規化成 null 之後候選群組查得到（count=1）—— 這正是方向 B 的目的："
                        + "任務不再靜默卡死。舊斷言是 count=0，那是缺陷期間的形狀")
                .isEqualTo(1);

        assertThat(alertReasons(pid))
                .as("候選人看得到了，因此不告警。⚠️ assignee-blocks-candidates 這個 "
                        + "reason 仍活著，只是可達路徑剩下 API 寫入端"
                        + "（blankAssigneeSetByApiIsAlerted／whitespaceAssigneeIsAlerted）")
                .isEmpty();
    }

    // ── 非缺陷路徑：釘住「BPMN 字面值空字串」不是這個缺陷 ─────────────

    @Test
    @DisplayName("釘住：BPMN 的 flowable:assignee=\"\" 字面值**不會**產生空字串 assignee（因此不是缺陷路徑）")
    void bpmnLiteralIsNotTheDefect() {
        String pid = runtimeService.startProcessInstanceByKey(BLANK_LITERAL, Map.of()).getId();

        // 這一條推翻工項描述的前提。Flowable 在 handleAssignments 裡對
        // assignee 有 StringUtils.isNotEmpty 前置判斷 → 空字串不寫入 →
        // assignee 維持 null → 候選群組查得到 → 沒有卡死。
        assertThat(taskService.createTaskQuery().processInstanceId(pid).singleResult().getAssignee())
                .as("Flowable 對 BPMN 字面值 assignee 有 isNotEmpty 判斷，空字串不會被寫入。"
                        + "若這一條哪天轉紅，代表有人動了 listener 之外的地方")
                .isNull();

        assertThat(candidatesCanSee(pid))
                .as("assignee 為 null 時候選群組看得到 —— 這是正確行為，不該告警")
                .isEqualTo(1);
        assertThat(alertReasons(pid)).isEmpty();
    }

    // ── 非回歸對照組：必要的，因為「一律告警」也能讓上面三條綠 ──────────

    @Test
    @DisplayName("對照：assignee=null + 有候選群組 → 不得告警（否則上面三條就是「全部都報」）")
    void nullAssigneeWithCandidateIsStillNotAlerted() {
        String pid = runtimeService.startProcessInstanceByKey(CANDIDATE_ONLY, Map.of()).getId();

        assertThat(taskService.createTaskQuery().processInstanceId(pid).singleResult().getAssignee())
                .as("前置條件：assignee 真的是 null")
                .isNull();
        assertThat(candidatesCanSee(pid))
                .as("前置條件：候選群組看得到它 —— 有人能處理，不該告警")
                .isEqualTo(1);
        assertThat(alertReasons(pid))
                .as("這是 UnreachableTaskAlertTest.taskWithCandidateGroupIsNotAlerted 的同款形狀。"
                        + "一個把 hasCandidate 整個忽略掉的實作會讓它變紅 —— 必須是綠的")
                .isEmpty();
    }

    @Test
    @DisplayName("對照：真人 assignee → 不得告警（#83 的既有行為不得回歸）")
    void humanAssigneeIsStillNotAlerted() {
        String pid = runtimeService.startProcessInstanceByKey(HUMAN, Map.of()).getId();

        assertThat(taskService.createTaskQuery().processInstanceId(pid).singleResult().getAssignee())
                .isEqualTo("mgr001");
        assertThat(alertReasons(pid))
                .as("mgr001 登入進來就看得到、也簽得掉")
                .isEmpty();
    }

    @Test
    @DisplayName("對照：assignee=null + 無候選人 → 仍走 no-assignee 分支（既有成因標記不得改）")
    void missingAssigneeKeepsOriginalReason() {
        String key = "b89-nobody";
        if (repositoryService.createProcessDefinitionQuery().processDefinitionKey(key).count() == 0) {
            repositoryService.createDeployment().addString(key + ".bpmn20.xml", bpmn(key, "")).deploy();
        }
        String pid = runtimeService.startProcessInstanceByKey(key, Map.of()).getId();

        assertThat(alertReasons(pid))
                .as("新增的判準不得改掉既有告警的成因標記 —— 監控靠它分流")
                .containsExactly("no-assignee-no-candidate");
    }

    @Test
    @DisplayName("對照：同一交易內補上候選人 → 不得告警（外部 API 的合法模式，assignee 仍為 null）")
    void candidateAddedInSameTransactionIsStillNotAlerted() {
        String key = "b89-null-then-candidate";
        if (repositoryService.createProcessDefinitionQuery().processDefinitionKey(key).count() == 0) {
            repositoryService.createDeployment().addString(key + ".bpmn20.xml", bpmn(key, "")).deploy();
        }
        String pid = new TransactionTemplate(primaryTx).execute(status -> {
            String id = runtimeService.startProcessInstanceByKey(key, Map.of()).getId();
            String taskId = taskService.createTaskQuery().processInstanceId(id).singleResult().getId();
            taskService.addCandidateGroup(taskId, GROUP);
            return id;
        });

        assertThat(alertReasons(pid))
                .as("assignee 為 null，候選人查得到 —— 這是合法模式，告警就是誤報。"
                        + "與 UnreachableTaskAlertTest 的同款案例重疊，是刻意的："
                        + "新判準不得為了抓空字串而把這個誤報加回來")
                .isEmpty();
    }
}
