package com.bpm.core.security;

import com.bpm.core.support.IntegrationTestBase;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 併發任務不得讓案件列表對所有人 500（security-audit P1-2）。
 *
 * <p><b>問題。</b>{@code taskService.createTaskQuery().processInstanceId(x).singleResult()}
 * 在結果超過一筆時拋 {@code FlowableException}。而
 * {@code ProcessController:81-82} 這一行位於 {@code GET /api/process-instances}
 * 的 stream 之中 —— 只要系統中<b>任何一個</b>案件有兩個併發任務
 * （平行閘道、multi-instance 會簽），這個端點就對<b>所有使用者</b>整體 500。
 *
 * <p>這是低程式碼平台：業務人員在設計器畫一個平行閘道就能觸發，
 * 屬必然而非假設。{@code :57-58} 則讓 {@code POST /api/process-instances}
 * 在流程已成功啟動<b>之後</b>回 500 → 使用者重送造成重複案件。
 */
class ConcurrentTaskListingTest extends IntegrationTestBase {

    @Autowired
    private RepositoryService repositoryService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private TaskService taskService;

    private static final String KEY = "parallel-two-tasks";

    /** 一支帶平行閘道的流程：start → fork → 兩個 UserTask。 */
    private void deployParallel() {
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
                """.formatted(KEY);
        repositoryService.createDeployment()
                .addString(KEY + ".bpmn20.xml", xml).name(KEY).deploy();
    }

    @Test
    @DisplayName("有併發任務的案件不得讓 GET /api/process-instances 整體 500")
    void listingSurvivesConcurrentTasks() throws Exception {
        deployParallel();
        var pi = runtimeService.startProcessInstanceByKey(KEY, Map.of("initiator", "user001"));

        assertThat(taskService.createTaskQuery().processInstanceId(pi.getId()).count())
                .as("平行閘道應產生兩個併發任務").isEqualTo(2);

        // 這是關鍵：一個案件有併發任務，就會讓整個列表端點對所有人 500。
        mockMvc.perform(get("/api/process-instances"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("併發任務的案件在列表中應顯示任務數量，而非靜默只顯示一個")
    void listingExposesConcurrentTaskCount() throws Exception {
        deployParallel();
        runtimeService.startProcessInstanceByKey(KEY, Map.of("initiator", "user001"));

        var res = mockMvc.perform(get("/api/process-instances"))
                .andExpect(status().isOk())
                .andReturn();
        String body = res.getResponse().getContentAsString();
        assertThat(body)
                .as("併發時只顯示其中一個任務會讓使用者以為案件只等一個人；"
                        + "currentTaskCount 讓這件事可見")
                .contains("currentTaskCount");
    }

    @Test
    @DisplayName("啟動帶平行閘道的流程不得在啟動成功後回 500")
    void startingParallelProcessDoesNotFailAfterStart() throws Exception {
        deployParallel();
        long before = runtimeService.createProcessInstanceQuery().processDefinitionKey(KEY).count();

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/process-instances")
                        .header("X-User-Id", "user001")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        // ⚠️ 不可帶 initiator（#66）：initiator 一律由登入身分決定，
                        // body 帶了會被明確拒絕成 400。
                        //
                        // 這個測試要驗的是「流程已啟動之後，組回應不得拋例外」，
                        // 不是冒用防線，所以不能為了配合新政策而弱化斷言 ——
                        // 正確做法是移除該欄位：送 user001 與不送的結果完全相同
                        // （server 寫入的就是 user001，見 TestGatewayMockMvcCustomizer）。
                        .content("{\"processDefinitionKey\":\"" + KEY + "\"}"))
                .andExpect(status().isOk());

        // 改動前：流程已啟動，但組回應時 singleResult 拋例外 → 500
        // → 使用者重送 → 重複案件。
        assertThat(runtimeService.createProcessInstanceQuery().processDefinitionKey(KEY).count())
                .as("應只啟動一個實例").isEqualTo(before + 1);
    }
}
