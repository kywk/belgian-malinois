package com.bpm.core.controller;

import com.bpm.core.audit.AuditEventPublisher;
import com.bpm.core.form.service.FormService;
import com.bpm.core.form.validation.FormSchemaValidator;
import com.bpm.core.notify.NotifyPublisher;
import com.bpm.core.security.ProcessAccessGuard;
import com.bpm.core.service.ApplicantIdentityLookup;
import com.bpm.core.service.FormVersionLocker;
import com.bpm.core.service.ProcessInvolvementService;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.task.api.history.HistoricTaskInstanceQuery;
import org.flowable.task.api.Task;
import org.flowable.task.api.TaskQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * #7 殘餘收尾：通知發送端（RabbitMQ）不通時，撤回仍必須成功。
 *
 * <h2>為什麼要一條 controller 層的單元測試</h2>
 *
 * <p>整合測試（{@code ProcessCancelNotifyTest}）證明「撤回成功時通知真的
 * 送出」，但送不出去的情境無法在整合測試裡安全製造（停 RabbitMQ 容器會
 * 連累整個 JVM 的共用容器）。{@code NotifyPublisherTest} 只證明
 * {@code publish} 吞例外，沒有經過 {@code ProcessController}。
 * 這一條把兩端接起來：mock 的 {@code RabbitTemplate.convertAndSend} 拋例外
 * （等同 broker 不通），直接呼叫 {@code cancelProcess}，驗證它仍回
 * {@code cancelled} 且刪除真的執行。
 *
 * <h2>為什麼不用 {@code @MockitoSpyBean}</h2>
 *
 * <p>它會另起一個 ApplicationContext（{@code AuditFailClosedTest} 已記載
 * 這個代價）；而直接建構 controller 不需要任何 Spring context，
 * 證明的命題完全相同：通知路徑的例外不得往外丟。
 */
class ProcessCancelNotifyFailureTest {

    private RuntimeService runtimeService;
    private TaskService taskService;
    private HistoryService historyService;
    private ProcessAccessGuard accessGuard;
    private ApplicantIdentityLookup applicantLookup;
    private RabbitTemplate rabbitTemplate;
    private ProcessController controller;

    @BeforeEach
    void setUp() {
        runtimeService = mock(RuntimeService.class);
        taskService = mock(TaskService.class);
        historyService = mock(HistoryService.class);
        accessGuard = mock(ProcessAccessGuard.class);
        applicantLookup = mock(ApplicantIdentityLookup.class);
        rabbitTemplate = mock(RabbitTemplate.class);
        NotifyPublisher publisher = new NotifyPublisher(rabbitTemplate);
        controller = new ProcessController(runtimeService, mock(RepositoryService.class),
                taskService, historyService, mock(AuditEventPublisher.class),
                mock(FormVersionLocker.class), accessGuard,
                mock(ProcessInvolvementService.class), applicantLookup, publisher,
                // #60 新增的三個相依：撤回路徑完全用不到，mock 即可
                // （見該測試「為什麼不用 @MockitoSpyBean」的說明）。
                mock(FormService.class),
                mock(FormSchemaValidator.class),
                new ObjectMapper());
    }

    @Test
    @DisplayName("#7 e) RabbitMQ 不通（convertAndSend 拋例外）→ 撤回仍回 cancelled、刪除照做")
    void cancelSucceedsWhenBrokerIsDown() {
        String pid = "pid-1";
        when(accessGuard.stateOf(pid)).thenReturn(ProcessAccessGuard.InstanceState.RUNNING);
        when(applicantLookup.applicantOf(pid)).thenReturn("user001");

        // 前置條件：第一關尚未處理。
        HistoricTaskInstanceQuery historic = mock(HistoricTaskInstanceQuery.class);
        when(historyService.createHistoricTaskInstanceQuery()).thenReturn(historic);
        when(historic.processInstanceId(pid)).thenReturn(historic);
        when(historic.finished()).thenReturn(historic);
        when(historic.count()).thenReturn(0L);

        // 刪除前收集收件人：一張有 assignee 的任務。
        TaskQuery taskQuery = mock(TaskQuery.class);
        when(taskService.createTaskQuery()).thenReturn(taskQuery);
        when(taskQuery.processInstanceId(pid)).thenReturn(taskQuery);
        Task task = mock(Task.class);
        when(task.getId()).thenReturn("task-1");
        when(task.getName()).thenReturn("主管審核");
        when(task.getAssignee()).thenReturn("mgr001");
        when(task.getProcessDefinitionId()).thenReturn("leave-approval:1:1");
        when(taskQuery.list()).thenReturn(List.of(task));

        // broker 不通：publish 內部的 convertAndSend 拋例外。
        doThrow(new RuntimeException("broker down"))
                .when(rabbitTemplate).convertAndSend(anyString(), anyString(), any(Object.class));

        Map<String, Object> result = controller.cancelProcess(pid, Map.of(), "user001");

        assertThat(result)
                .containsEntry("processInstanceId", pid)
                .containsEntry("status", "cancelled");
        verify(runtimeService).deleteProcessInstance(pid, "applicant-cancel");
        // 通知路徑真的被走過（例外是在 publish 裡被吞掉的），
        // 不是「根本沒呼叫通知」才沒失敗 —— 否則這條測試證明不了 fail-open。
        verify(rabbitTemplate).convertAndSend(anyString(), anyString(), any(Object.class));
    }
}
