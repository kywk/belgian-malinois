package com.bpm.core.controller;

import com.bpm.core.support.IntegrationTestBase;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class HistoryProcessOutcomeTest extends IntegrationTestBase {
    @Autowired RuntimeService runtimeService;
    @Autowired TaskService taskService;
    @Autowired ObjectMapper mapper;

    private String start() {
        return runtimeService.startProcessInstanceByKey("leave-approval", Map.of("initiator", "user001")).getId();
    }

    @Test
    void historicListsDistinguishRejectedApprovedCancelledAndRunning() throws Exception {
        String rejected = start();
        taskService.complete(taskService.createTaskQuery().processInstanceId(rejected).singleResult().getId(),
                Map.of("approved", false, "rejected", true));
        String approved = start();
        var approvalTask = taskService.createTaskQuery().processInstanceId(approved).singleResult();
        // 同名 task-local 變數不可把核准案件誤分類成拒絕。
        taskService.setVariableLocal(approvalTask.getId(), "rejected", true);
        taskService.complete(approvalTask.getId(), Map.of("approved", true, "rejected", false));
        String cancelled = start();
        runtimeService.setVariable(cancelled, "rejected", true);
        runtimeService.deleteProcessInstance(cancelled, "驗證取消分類");
        String running = start();
        runtimeService.setVariable(running, "rejected", true);

        for (String path : new String[] {"/api/history/process-instances", "/api/history/process-instances/involved"}) {
            var result = mockMvc.perform(get(path).header("X-User-Id", "user001"))
                    .andExpect(status().isOk()).andReturn();
            Map<String, String> states = new HashMap<>();
            mapper.readTree(result.getResponse().getContentAsString()).forEach(row ->
                    states.put(row.path("processInstanceId").asText(), row.path("status").asText()));
            assertThat(states).containsEntry(rejected, "rejected").containsEntry(approved, "completed")
                    .containsEntry(cancelled, "cancelled").containsEntry(running, "running");
        }
    }
}
