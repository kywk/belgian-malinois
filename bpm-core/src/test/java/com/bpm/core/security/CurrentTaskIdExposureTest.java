package com.bpm.core.security;

import com.bpm.core.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * currentTask 必須帶 taskId：列表端點與啟動回應形狀一致。
 *
 * <h2>缺口</h2>
 *
 * <p>{@code POST /api/process-instances}（啟動後組回應）的 currentTask 早就有
 * {@code Task#getId()} 放進 {@code taskId}，但三個列表路徑只有
 * {@code taskName}／{@code assignee}：
 * {@code GET /api/process-instances}（我的申請）、
 * {@code GET /api/process-instances/involved} 與
 * {@code GET /api/history/process-instances/involved}（我參與的）。
 * 同一支前端元件讀這三種回應，卻只有一邊拿得到任務 id。
 *
 * <p>影響：前端「我的申請」的催辦按鈕手上只有案件 id；
 * 以 taskId 為路徑參數的端點因此接不上。本工項只補欄位，不動端點。
 *
 * <h2>驗證方式</h2>
 *
 * <p>不由回應推測：任務 id 直接向 {@code TaskService}（ACT_RU_TASK）取得，
 * 再斷言回應中的 taskId 等於它 —— 否則「補上一個錯誤的 id」也會過。
 * 平行關卡另外驗 currentTaskCount 不因本次改動而改變。
 *
 * <h2>負向控制組實測（2026-10-03，stash 掉三處新增的 taskId、其餘不動）</h2>
 *
 * <p><b>5 條中 4 紅 1 綠。</b>紅的是三條形狀斷言（我的申請、兩個「我參與的」）
 * 與平行關卡 —— 缺欄位時 {@code taskId} 取到空字串，與 ACT_RU_TASK 的
 * 真實 id 不符。綠的是 {@link #finishedCaseHasNoCurrentTask}：它斷言的是
 * 「已結束案件不得出現 currentTask」這個既有行為，缺陷版與修好版都必須成立，
 * 因此本來就該是同一個顏色 —— 準確說它是防護網，不是偵測器。
 */
class CurrentTaskIdExposureTest extends IntegrationTestBase {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private RepositoryService repositoryService;

    // ── 工具 ────────────────────────────────────────────────────────

    /** leave-approval 的一張單：停在主管審核（assignee = mgr001）。 */
    private record LeaveCase(String pid, String managerTaskId) {}

    private LeaveCase startLeaveCase() {
        var pi = runtimeService.startProcessInstanceByKey("leave-approval",
                Map.of("initiator", "user001", "leaveType", "annual", "days", 1));
        Task manager = taskService.createTaskQuery().processInstanceId(pi.getId()).singleResult();
        assertThat(manager.getAssignee())
                .as("前置條件：主管審核的持有者必須是 mgr001")
                .isEqualTo("mgr001");
        return new LeaveCase(pi.getId(), manager.getId());
    }

    /** 以指定身分 GET，回傳回應中 {@code processInstanceId == pid} 的那一列。 */
    private JsonNode rowFor(String path, String user, String pid) throws Exception {
        String body = mockMvc.perform(get(path).header("X-User-Id", user))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        List<JsonNode> matched = new ArrayList<>();
        for (JsonNode row : MAPPER.readTree(body)) {
            if (pid.equals(row.path("processInstanceId").asText())) matched.add(row);
        }
        assertThat(matched).as("%s 應包含案件 %s", path, pid).hasSize(1);
        return matched.get(0);
    }

    // ── GET /api/process-instances（我的申請）───────────────────────

    @Test
    @DisplayName("我的申請：currentTask.taskId 等於 ACT_RU_TASK 的實際任務 id")
    void myApplicationsExposeRealTaskId() throws Exception {
        LeaveCase c = startLeaveCase();

        JsonNode currentTask = rowFor("/api/process-instances", "user001", c.pid()).path("currentTask");

        assertThat(currentTask.path("taskId").asText())
                .as("沒有 taskId，前端只能催「案件」；未來以 taskId 為路徑的端點也接不上")
                .isEqualTo(c.managerTaskId());
    }

    // ── 兩個「我參與的」端點：形狀必須與我的申請一致 ───────────────

    @Test
    @DisplayName("我參與的（執行中）：currentTask.taskId 與我的申請同一形狀")
    void involvedExposesRealTaskId() throws Exception {
        LeaveCase c = startLeaveCase();

        JsonNode currentTask =
                rowFor("/api/process-instances/involved", "mgr001", c.pid()).path("currentTask");

        assertThat(currentTask.path("taskId").asText())
                .as("三處 currentTask 由同一支前端元件讀取，形狀不得各自為政")
                .isEqualTo(c.managerTaskId());
    }

    @Test
    @DisplayName("我參與的（歷史）：執行中案件的 currentTask.taskId 與其他兩處一致")
    void historicInvolvedExposesRealTaskId() throws Exception {
        LeaveCase c = startLeaveCase();

        JsonNode row = rowFor("/api/history/process-instances/involved", "mgr001", c.pid());

        assertThat(row.path("status").asText()).isEqualTo("running");
        assertThat(row.path("currentTask").path("taskId").asText())
                .as("歷史端點也涵蓋執行中案件，形狀必須相同")
                .isEqualTo(c.managerTaskId());
    }

    // ── 沒有待處理任務：不得出現 currentTask ───────────────────────

    @Test
    @DisplayName("已結束的案件：runtime 查不到任務，不得出現 currentTask")
    void finishedCaseHasNoCurrentTask() throws Exception {
        LeaveCase c = startLeaveCase();
        taskService.complete(c.managerTaskId(), Map.of("approved", true, "rejected", false));

        JsonNode row = rowFor("/api/history/process-instances/involved", "mgr001", c.pid());

        assertThat(row.path("status").asText()).isEqualTo("completed");
        assertThat(row.has("currentTask"))
                .as("空的 currentTask 會讓前端顯示一個不存在的關卡")
                .isFalse();
        assertThat(row.has("currentTaskCount")).isFalse();
    }

    // ── 平行關卡：補欄位不得改變既有計數行為 ───────────────────────

    private static final String PARALLEL_KEY = "currenttask-parallel-two-tasks";

    /** 一支帶平行閘道的流程：start → fork → 兩個 UserTask。 */
    private void deployParallel() {
        if (repositoryService.createProcessDefinitionQuery()
                .processDefinitionKey(PARALLEL_KEY).count() > 0) {
            return;
        }
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:flowable="http://flowable.org/bpmn"
                             targetNamespace="test">
                  <process id="%s" name="平行兩任務" isExecutable="true">
                    <startEvent id="start"/>
                    <sequenceFlow id="f0" sourceRef="start" targetRef="fork"/>
                    <parallelGateway id="fork"/>
                    <sequenceFlow id="f1" sourceRef="fork" targetRef="taskA"/>
                    <sequenceFlow id="f2" sourceRef="fork" targetRef="taskB"/>
                    <userTask id="taskA" name="平行任務 A" flowable:assignee="mgr001"/>
                    <userTask id="taskB" name="平行任務 B" flowable:assignee="mgr002"/>
                    <sequenceFlow id="f3" sourceRef="taskA" targetRef="join"/>
                    <sequenceFlow id="f4" sourceRef="taskB" targetRef="join"/>
                    <parallelGateway id="join"/>
                    <sequenceFlow id="f5" sourceRef="join" targetRef="end"/>
                    <endEvent id="end"/>
                  </process>
                </definitions>
                """.formatted(PARALLEL_KEY);
        repositoryService.createDeployment()
                .addString(PARALLEL_KEY + ".bpmn20.xml", xml).name(PARALLEL_KEY).deploy();
    }

    @Test
    @DisplayName("平行關卡：currentTaskCount 行為不變，taskId 指向其中一個真實任務")
    void parallelCaseKeepsCountAndExposesOneRealTaskId() throws Exception {
        deployParallel();
        var pi = runtimeService.startProcessInstanceByKey(PARALLEL_KEY, Map.of("initiator", "user001"));
        Set<String> actualIds = taskService.createTaskQuery().processInstanceId(pi.getId()).list()
                .stream().map(Task::getId).collect(Collectors.toSet());
        assertThat(actualIds).as("前置條件：平行閘道應產生兩個任務").hasSize(2);

        JsonNode row = rowFor("/api/process-instances", "user001", pi.getId());

        assertThat(row.path("currentTaskCount").asInt())
                .as("補 taskId 不得改變既有的 currentTaskCount 行為")
                .isEqualTo(2);
        assertThat(actualIds)
                .as("currentTask 只取其中一個，但必須是 ACT_RU_TASK 裡真實存在的任務")
                .contains(row.path("currentTask").path("taskId").asText());
    }
}
