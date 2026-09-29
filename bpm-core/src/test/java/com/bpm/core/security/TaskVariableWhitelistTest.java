package com.bpm.core.security;

import com.bpm.core.support.IntegrationTestBase;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 任務變數的保護名單（security-audit P0-5）。
 *
 * <p><b>問題。</b>{@code TaskController} 的
 * {@code req.variables().forEach(v -> vars.put(v.name(), v.value()))}
 * 完全信任呼叫端給的變數名，沒有任何白名單。
 *
 * <p>兩支已部署的 BPMN 都用 {@code ${orgService.getDirectManager(initiator)}}
 * 解析主管、用 {@code ${initiator}} 指派補件任務。因此申請人在完成自己的
 * 補件任務時只要附帶 {@code {"name":"initiator","value":"某共犯"}}，
 * <b>下一輪主管審核就會派給該共犯的主管</b> —— 簽核人自選審核者。
 *
 * <p>而前端 {@code DocumentDetail.vue} 本來就把整份表單欄位當變數送出
 * （符合 spec §8.5 的「欄位 id == 變數名」約定），所以只要表單有同名欄位，
 * 連改 payload 都不需要。
 *
 * <p>與已登錄的 R-23 不同：R-23 只涵蓋 {@code _} 前綴的內部變數，
 * 而 {@code initiator} 沒有底線。本測試同時涵蓋兩者。
 */
class TaskVariableWhitelistTest extends IntegrationTestBase {

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private TaskService taskService;

    private Task startAndGetManagerTask() {
        var pi = runtimeService.startProcessInstanceByKey("leave-approval",
                Map.of("initiator", "user001", "leaveType", "annual", "days", 1));
        List<Task> tasks = taskService.createTaskQuery().processInstanceId(pi.getId()).list();
        assertThat(tasks).hasSize(1);
        return tasks.get(0);
    }

    private static String completeBody(String varName, String varValue) {
        return "{\"action\":\"complete\",\"variables\":["
                + "{\"name\":\"approved\",\"value\":true},"
                + "{\"name\":\"" + varName + "\",\"value\":\"" + varValue + "\"}]}";
    }

    @Test
    @DisplayName("不得以任務變數改寫 initiator —— 那等於自選下一關簽核人")
    void cannotOverwriteInitiator() throws Exception {
        Task task = startAndGetManagerTask();
        String pid = task.getProcessInstanceId();

        mockMvc.perform(put("/api/tasks/{id}", task.getId())
                        .header("X-User-Id", "mgr001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(completeBody("initiator", "accomplice")))
                .andExpect(status().isBadRequest());

        // 任務不得被完成，且 initiator 必須保持原值
        assertThat(taskService.createTaskQuery().taskId(task.getId()).singleResult())
                .as("請求被拒時任務不得被完成").isNotNull();
        assertThat(runtimeService.getVariable(pid, "initiator"))
                .as("initiator 不得被改寫").isEqualTo("user001");
    }

    @Test
    @DisplayName("不得以任務變數改寫 _ 前綴的內部變數（R-23）")
    void cannotOverwriteInternalVariables() throws Exception {
        for (String name : List.of("_formVersions", "_externalSystemId", "_callbackUrl")) {
            Task task = startAndGetManagerTask();
            mockMvc.perform(put("/api/tasks/{id}", task.getId())
                            .header("X-User-Id", "mgr001")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(completeBody(name, "injected")))
                    .andExpect(status().isBadRequest());
            assertThat(taskService.createTaskQuery().taskId(task.getId()).singleResult())
                    .as(name + " 被拒時任務不得被完成").isNotNull();
        }
    }

    @Test
    @DisplayName("不得改寫 effectiveInitiator（伺服器推導的身分）")
    void cannotOverwriteEffectiveInitiator() throws Exception {
        Task task = startAndGetManagerTask();
        mockMvc.perform(put("/api/tasks/{id}", task.getId())
                        .header("X-User-Id", "mgr001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(completeBody("effectiveInitiator", "accomplice")))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("正常的表單變數必須照常可用（不得因為加了保護而破壞簽核）")
    void normalFormVariablesStillWork() throws Exception {
        Task task = startAndGetManagerTask();

        // leaveType / reason 是 leave-request 表單的欄位 id，
        // 依 spec §8.5 就是流程變數名 —— 這些必須照常寫入。
        mockMvc.perform(put("/api/tasks/{id}", task.getId())
                        .header("X-User-Id", "mgr001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"complete\",\"variables\":["
                                + "{\"name\":\"approved\",\"value\":true},"
                                + "{\"name\":\"approverComment\",\"value\":\"同意\"},"
                                + "{\"name\":\"reason\",\"value\":\"家庭因素\"}]}"))
                .andExpect(status().isOk());

        assertThat(taskService.createTaskQuery().taskId(task.getId()).singleResult())
                .as("正常簽核必須成功完成").isNull();
    }

    @Test
    @DisplayName("完整攻擊情境：申請人在補件任務偽造 initiator，主管仍須是真正的主管")
    void forgedInitiatorOnResubmitCannotRedirectApproval() throws Exception {
        // 1. user001 發起請假 → mgr001 審核
        Task mgrTask = startAndGetManagerTask();
        String pid = mgrTask.getProcessInstanceId();

        // 2. mgr001 退回（approved=false、未設 rejected）→ 產生「申請者補件」任務
        taskService.complete(mgrTask.getId(), Map.of("approved", false, "rejected", false));

        List<Task> resubmit = taskService.createTaskQuery().processInstanceId(pid).list();
        assertThat(resubmit).as("退回後應產生補件任務").hasSize(1);
        assertThat(resubmit.get(0).getAssignee())
                .as("補件任務由 ${initiator} 指派 → 應為 user001").isEqualTo("user001");

        // 3. user001 完成補件時偽造 initiator，企圖讓下一關派給共犯的主管
        mockMvc.perform(put("/api/tasks/{id}", resubmit.get(0).getId())
                        .header("X-User-Id", "user001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(completeBody("initiator", "accomplice")))
                .andExpect(status().isBadRequest());

        // 4. 用合法方式完成補件
        taskService.complete(resubmit.get(0).getId(), Map.of("approved", true));

        // 5. 下一關必須仍是 user001 的真正主管 mgr001
        List<Task> next = taskService.createTaskQuery().processInstanceId(pid).list();
        assertThat(next).isNotEmpty();
        assertThat(next.get(0).getAssignee())
                .as("主管仍須由真正的 initiator(user001) 解析 → mgr001，不得被導向共犯的主管")
                .isEqualTo("mgr001");
    }
}
