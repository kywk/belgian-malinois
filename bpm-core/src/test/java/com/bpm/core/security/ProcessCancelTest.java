package com.bpm.core.security;

import com.bpm.core.support.IntegrationTestBase;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * #7：撤回（申請人撤案）—— 授權、可撤回條件與 {@code PROCESS_CANCEL} 稽核。
 *
 * <h2>本工項接上的是既有的預留值</h2>
 *
 * <p>{@code OperationType.PROCESS_CANCEL} 早已存在，且一直列在
 * {@code NOT_YET_IMPLEMENTED}（「撤案：無端點」）—— 本工項是它第一個、
 * 也是目前唯一發出它的地方。端點：
 * {@code POST /api/process-instances/{id}/cancel}。
 *
 * <h2>每一條拒絕都配「案件不變 + 零 PROCESS_CANCEL」斷言</h2>
 *
 * <p>狀態碼對了不代表「什麼都沒發生」被驗過，而撤回最大的風險是
 * 「回應看起來被拒絕，實例其實被刪掉了」。403／404 仍會各留一筆
 * {@code DATA_ACCESS denied}（那是既有授權留痕，不是撤回），
 * 400／409 則不留任何稽核。
 *
 * <h2>授權分流與催辦一致，申請人判定只有一份</h2>
 *
 * <p>參與者非申請人 → 403；非參與者 → 404（{@code denyNonParticipant}，
 * 不留枚舉管道）。申請人判定用 {@code ApplicantIdentityLookup.applicantOf}
 * —— 代發案件取 {@code onBehalfOf}、系統案件回 null（不開放）。
 * 見 {@code ProcessController.cancelProcess} 的 javadoc。
 *
 * <h2>撤回通知（#7 殘餘收尾後）</h2>
 *
 * <p>撤回會通知現任受理人（{@code process_cancelled}）—— 行為、payload
 * 與「被拒零通知」由 {@code ProcessCancelNotifyTest} 驗證。本檔專注在
 * 授權、狀態分流與稽核，不重複通知斷言；改動前此處記載的
 * 「通知不在本端點範圍」已不成立。
 *
 * <h2>負向控制組（2026-10-03 實測）</h2>
 *
 * <p>兩次都把 {@code ProcessController.java} 的目標檢查暫時改成
 * {@code if (false && ...)}（其餘不動），跑完整個類別後還原：
 *
 * <ol>
 *   <li><b>停用「已完成任務」檢查</b>：11 條中 <b>1 紅 10 綠</b>。
 *       紅的是 {@link #caseWithFinishedTaskCannotBeCancelled}
 *       （409 變 200，案件真的被刪掉）；其餘照綠，證明它們測的是
 *       授權、狀態分流、稽核等其他行為，不依賴這個條件。</li>
 *   <li><b>停用申請人／參與者分流</b>：11 條中 <b>3 紅 8 綠</b>。
 *       紅的是 {@link #participantWhoIsNotApplicantIsForbidden}（403 變 200）、
 *       {@link #unrelatedUserGetsNotFound}（404 變 200）與
 *       {@link #onBehalfOfOtherParticipantIsForbidden}（403 變 200）
 *       —— 三條都是「被拒卻成功撤回」。</li>
 * </ol>
 *
 * <p><b>這兩組控制組證明不了什麼</b>：401、稽核 fail-closed（503）、
 * 撤回通知（現由 {@code ProcessCancelNotifyTest} 覆蓋）、TOCTOU 競爭窗口，
 * 以及外部 API {@code /status} 的 {@code cancelled} 對映，都不在本測試的
 * 斷言範圍內 —— 它們是 javadoc 已記載的殘餘與既有行為，不是這裡
 * 「綠燈」所保證的事。
 */
class ProcessCancelTest extends IntegrationTestBase {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private HistoryService historyService;

    @Autowired
    private RepositoryService repositoryService;

    @BeforeEach
    void clean() {
        truncateAuditLog();
    }

    // ── 情境與 HTTP 小工具 ──────────────────────────────────────────

    private record Case(String pid, String managerTaskId) {}

    /**
     * 一張 leave-approval 的單，停在主管審核（assignee = mgr001）。
     *
     * <p>用 {@code runtimeService} 直接啟動而不是走
     * {@code POST /api/process-instances}：fixture 不依賴被測 controller
     * 之外的啟動路徑，起點才單純（與 {@code NotifyTriggerIntegrationTest}
     * 同一取向）。
     */
    private Case startLeave() {
        var pi = runtimeService.startProcessInstanceByKey("leave-approval",
                Map.of("initiator", "user001", "leaveType", "annual", "days", 1));
        Task manager = taskService.createTaskQuery().processInstanceId(pi.getId()).singleResult();
        assertThat(manager.getAssignee())
                .as("前置條件：主管審核的持有者必須是 mgr001")
                .isEqualTo("mgr001");
        return new Case(pi.getId(), manager.getId());
    }

    /** 代發案件：initiator 是系統身分，自然人在 onBehalfOf（R-20）。 */
    private Case startOnBehalfOf() {
        var pi = runtimeService.startProcessInstanceByKey("leave-approval",
                Map.of("initiator", "system:erp", "onBehalfOf", "user001",
                        "leaveType", "annual", "days", 1));
        Task manager = taskService.createTaskQuery().processInstanceId(pi.getId()).singleResult();
        assertThat(manager.getAssignee())
                .as("前置條件：代發的主管關卡依 onBehalfOf 路由")
                .isEqualTo("mgr001");
        return new Case(pi.getId(), manager.getId());
    }

    /** 部署一支候選任務流程，用來驗「已聲明但未完成」。 */
    private String deployClaimableProcess() {
        String key = "pc-claim-" + UUID.randomUUID().toString().substring(0, 8);
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:flowable="http://flowable.org/bpmn"
                             targetNamespace="http://bpm.com/process-cancel-test">
                  <process id="%s" isExecutable="true">
                    <startEvent id="start"/>
                    <sequenceFlow id="f1" sourceRef="start" targetRef="approve"/>
                    <userTask id="approve" name="審核關卡" flowable:candidateUsers="mgr001,mgr002"/>
                    <sequenceFlow id="f2" sourceRef="approve" targetRef="end"/>
                    <endEvent id="end"/>
                  </process>
                </definitions>
                """.formatted(key);
        repositoryService.createDeployment()
                .name(key)
                .addString(key + ".bpmn20.xml", xml)
                .deploy();
        return key;
    }

    /** 不帶 body（body 是選配）。 */
    private ResultActions cancel(String pid, String user) throws Exception {
        return mockMvc.perform(post("/api/process-instances/{id}/cancel", pid)
                .header("X-User-Id", user));
    }

    private ResultActions cancel(String pid, String user, String json) throws Exception {
        return mockMvc.perform(post("/api/process-instances/{id}/cancel", pid)
                .header("X-User-Id", user)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    private boolean runtimeStillExists(String pid) {
        return runtimeService.createProcessInstanceQuery().processInstanceId(pid).count() > 0;
    }

    /** 這個案件的 PROCESS_CANCEL 稽核列：{operator_id, detail}，依 id。 */
    private List<String[]> cancelAuditRows(String pid) {
        List<String[]> out = new ArrayList<>();
        withAuditConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT operator_id, detail FROM bpm_audit_log "
                            + "WHERE operation_type = 'PROCESS_CANCEL' AND process_instance_id = ? "
                            + "ORDER BY id")) {
                ps.setString(1, pid);
                var rs = ps.executeQuery();
                while (rs.next()) out.add(new String[]{rs.getString(1), rs.getString(2)});
            }
        });
        return out;
    }

    /** 某人在某案件留下的 DATA_ACCESS detail 清單（授權拒絕的痕跡）。 */
    private List<String> dataAccessDetails(String pid, String operatorId) {
        List<String> out = new ArrayList<>();
        withAuditConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT detail FROM bpm_audit_log "
                            + "WHERE operation_type = 'DATA_ACCESS' "
                            + "AND process_instance_id = ? AND operator_id = ? ORDER BY id")) {
                ps.setString(1, pid);
                ps.setString(2, operatorId);
                var rs = ps.executeQuery();
                while (rs.next()) out.add(rs.getString(1));
            }
        });
        return out;
    }

    // ── a) 申請人撤回剛啟動的案件 ──────────────────────────────────

    @Test
    @DisplayName("#7 a) 申請人撤回剛啟動的案件 → 200；runtime 消失、歷史保留、"
            + "DELETE_REASON_ 有值、PROCESS_CANCEL operator=呼叫者")
    void applicantCanCancelFreshCase() throws Exception {
        Case c = startLeave();

        cancel(c.pid(), "user001", "{\"reason\":\"臨時取消\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.processInstanceId").value(c.pid()))
                .andExpect(jsonPath("$.status").value("cancelled"));

        assertThat(runtimeStillExists(c.pid()))
                .as("撤回後 runtime 不該再查得到實例")
                .isFalse();

        HistoricProcessInstance hp = historyService.createHistoricProcessInstanceQuery()
                .processInstanceId(c.pid()).singleResult();
        assertThat(hp).as("歷史實例必須保留（軌跡與稽核靠它）").isNotNull();
        assertThat(hp.getEndTime()).isNotNull();
        assertThat(hp.getDeleteReason())
                .as("§9.4 的 cancelled 判定是 deleteReason 非 null；"
                        + "reason 必須寫進 ACT_HI_PROCINST.DELETE_REASON_")
                .isEqualTo("臨時取消");

        List<String[]> rows = cancelAuditRows(c.pid());
        assertThat(rows)
                .as("撤回成功的唯一證據是這筆紀錄；沒有它，「誰撤了這張單」在稽核上不存在")
                .hasSize(1);
        assertThat(rows.get(0)[0])
                .as("operator 必須是真正做事的呼叫者")
                .isEqualTo("user001");

        Map<String, Object> detail = MAPPER.readValue(rows.get(0)[1], new TypeReference<>() {});
        assertThat(detail)
                .as("detail 只放撤回原因與時間 —— 鍵恰好這兩個")
                .containsOnlyKeys("reason", "cancelledAt")
                .containsEntry("reason", "臨時取消");
        assertThat(detail.get("cancelledAt")).isNotNull();
    }

    // ── b) 參與者非申請人 403／無關者 404 ───────────────────────────

    @Test
    @DisplayName("#7 b) 參與者但不是申請人（審核人）→ 403；案件不變、零 PROCESS_CANCEL、"
            + "留 DATA_ACCESS 拒絕痕跡")
    void participantWhoIsNotApplicantIsForbidden() throws Exception {
        Case c = startLeave();

        cancel(c.pid(), "mgr001", "{}").andExpect(status().isForbidden());

        assertThat(runtimeStillExists(c.pid()))
                .as("403 之後案件必須仍在 runtime")
                .isTrue();
        assertThat(taskService.createTaskQuery().processInstanceId(c.pid()).count())
                .as("受理人的任務不得被動到")
                .isEqualTo(1);
        assertThat(cancelAuditRows(c.pid()))
                .as("被拒的請求不得宣告一件沒發生的撤回")
                .isEmpty();
        assertThat(dataAccessDetails(c.pid(), "mgr001"))
                .as("參與者非申請人是被拒的授權嘗試，要留 DATA_ACCESS 痕跡")
                .isNotEmpty()
                .allMatch(d -> d.contains("\"denied\":true"))
                .allMatch(d -> d.contains("not the applicant"));
    }

    @Test
    @DisplayName("#7 b) 與案件無關的人撤回 → 404（不留枚舉管道）；案件不變、零 PROCESS_CANCEL")
    void unrelatedUserGetsNotFound() throws Exception {
        Case c = startLeave();

        cancel(c.pid(), "user002", "{}").andExpect(status().isNotFound());

        assertThat(runtimeStillExists(c.pid()))
                .as("404 之後案件必須仍在 runtime")
                .isTrue();
        assertThat(cancelAuditRows(c.pid())).isEmpty();
        assertThat(dataAccessDetails(c.pid(), "user002"))
                .as("非參與者的存取嘗試必須留下 DATA_ACCESS {denied:true}")
                .isNotEmpty()
                .allMatch(d -> d.contains("\"denied\":true"));
    }

    // ── c) 已有完成任務 → 409 ───────────────────────────────────────

    @Test
    @DisplayName("#7 c) 已有完成任務（主管審過、案件還在跑）→ 409；案件不變、零 PROCESS_CANCEL")
    void caseWithFinishedTaskCannotBeCancelled() throws Exception {
        Case c = startLeave();
        taskService.complete(c.managerTaskId(), Map.of("approved", false, "rejected", false));
        Task revision = taskService.createTaskQuery().processInstanceId(c.pid()).singleResult();
        assertThat(revision)
                .as("前置條件：退回後案件仍在跑（否則測到的是『已結束』的 404，不是 409）")
                .isNotNull();
        assertThat(revision.getAssignee())
                .as("前置條件：補件關卡回到申請人")
                .isEqualTo("user001");

        cancel(c.pid(), "user001", "{\"reason\":\"後悔了\"}")
                .andExpect(status().isConflict());

        assertThat(runtimeStillExists(c.pid()))
                .as("409 之後案件必須仍在 runtime")
                .isTrue();
        assertThat(taskService.createTaskQuery().processInstanceId(c.pid()).count())
                .as("補件任務不得被動到")
                .isEqualTo(1);
        assertThat(cancelAuditRows(c.pid()))
                .as("409 不得宣告撤回發生")
                .isEmpty();
    }

    // ── d) 已結束／不存在 → 404 ─────────────────────────────────────

    @Test
    @DisplayName("#7 d) 已結束的案件 → 404；不得把它改寫成撤回")
    void finishedCaseIsNotFound() throws Exception {
        Case c = startLeave();
        taskService.complete(c.managerTaskId(), Map.of("approved", true, "rejected", false));
        assertThat(runtimeStillExists(c.pid()))
                .as("前置條件：核准後案件應已結束")
                .isFalse();

        cancel(c.pid(), "user001", "{}").andExpect(status().isNotFound());

        assertThat(cancelAuditRows(c.pid())).isEmpty();
        HistoricProcessInstance hp = historyService.createHistoricProcessInstanceQuery()
                .processInstanceId(c.pid()).singleResult();
        assertThat(hp).isNotNull();
        assertThat(hp.getDeleteReason())
                .as("原本是正常結案，不得被 404 路徑改寫成撤回（deleteReason 必須仍是 null）")
                .isNull();
    }

    @Test
    @DisplayName("#7 d) 不存在的案件 → 404，零 PROCESS_CANCEL")
    void unknownCaseIsNotFound() throws Exception {
        String pid = "no-such-process-instance-" + UUID.randomUUID();

        cancel(pid, "user001", "{}").andExpect(status().isNotFound());

        assertThat(cancelAuditRows(pid)).isEmpty();
    }

    // ── e) 代發案件（R-20）───────────────────────────────────────────

    @Test
    @DisplayName("#7 e) 代發案件（initiator=system、onBehalfOf=user001）："
            + "user001 可撤回；沒帶 reason 時用固定字串")
    void onBehalfOfApplicantCanCancel() throws Exception {
        Case c = startOnBehalfOf();

        // 不帶 body：同時驗「body 是選配」與預設 reason 真的寫進 DELETE_REASON_。
        cancel(c.pid(), "user001")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("cancelled"));

        assertThat(runtimeStillExists(c.pid())).isFalse();
        HistoricProcessInstance hp = historyService.createHistoricProcessInstanceQuery()
                .processInstanceId(c.pid()).singleResult();
        assertThat(hp).isNotNull();
        assertThat(hp.getDeleteReason())
                .as("沒帶 reason 時用固定字串，cancelled 判定才不會失去依據")
                .isEqualTo("applicant-cancel");

        assertThat(cancelAuditRows(c.pid()))
                .as("申請人判定取 onBehalfOf 之後，user001 是唯一能撤回的人")
                .hasSize(1);
    }

    @Test
    @DisplayName("#7 e) 代發案件的其他參與者（審核人）→ 403，案件不變、零 PROCESS_CANCEL")
    void onBehalfOfOtherParticipantIsForbidden() throws Exception {
        Case c = startOnBehalfOf();

        cancel(c.pid(), "mgr001", "{}").andExpect(status().isForbidden());

        assertThat(runtimeStillExists(c.pid()))
                .as("403 之後案件必須仍在 runtime")
                .isTrue();
        assertThat(cancelAuditRows(c.pid())).isEmpty();
        assertThat(dataAccessDetails(c.pid(), "mgr001")).isNotEmpty();
    }

    // ── f) 重複撤回 ─────────────────────────────────────────────────

    @Test
    @DisplayName("#7 f) 重複撤回 → 第二次 404，且不得寫出第二筆 PROCESS_CANCEL")
    void secondCancelIsNotFound() throws Exception {
        Case c = startLeave();

        cancel(c.pid(), "user001", "{}").andExpect(status().isOk());
        cancel(c.pid(), "user001", "{}").andExpect(status().isNotFound());

        assertThat(cancelAuditRows(c.pid()))
                .as("第二次 404 不得宣告第二次撤回；成功那次的一筆仍在")
                .hasSize(1);
    }

    // ── 邊界：已聲明未完成、reason 形狀 ─────────────────────────────

    @Test
    @DisplayName("#7 已聲明（claimed）但未完成仍可撤回 —— 『處理過』的判定是完成，不是聲明")
    void claimedButUnfinishedIsStillCancellable() throws Exception {
        String key = deployClaimableProcess();
        String pid = runtimeService.startProcessInstanceByKey(key,
                Map.of("initiator", "user001")).getId();
        Task task = taskService.createTaskQuery().processInstanceId(pid).singleResult();
        assertThat(task.getAssignee()).as("前置條件：候選任務尚未聲明").isNull();

        taskService.claim(task.getId(), "mgr001");
        assertThat(taskService.createTaskQuery().processInstanceId(pid).singleResult().getAssignee())
                .as("前置條件：已聲明但未完成")
                .isEqualTo("mgr001");
        assertThat(historyService.createHistoricTaskInstanceQuery()
                .processInstanceId(pid).finished().count())
                .as("前置條件：沒有任何已完成任務")
                .isZero();

        cancel(pid, "user001", "{}").andExpect(status().isOk());

        assertThat(runtimeStillExists(pid))
                .as("聲明不是處理；撤回後實例與受理人手上的任務一起消失")
                .isFalse();
        assertThat(cancelAuditRows(pid)).hasSize(1);
    }

    @Test
    @DisplayName("#7 body 的 reason 不是字串 → 400（不靜默忽略），案件不變、零稽核")
    void nonStringReasonIsRejected() throws Exception {
        Case c = startLeave();

        cancel(c.pid(), "user001", "{\"reason\":123}")
                .andExpect(status().isBadRequest());

        assertThat(runtimeStillExists(c.pid()))
                .as("400 之後案件必須仍在 runtime")
                .isTrue();
        assertThat(cancelAuditRows(c.pid())).isEmpty();
    }
}
