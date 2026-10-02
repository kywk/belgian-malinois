package com.bpm.core.notify;

import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.BoundaryEvent;
import org.flowable.bpmn.model.Process;
import org.flowable.bpmn.model.SequenceFlow;
import org.flowable.bpmn.model.ServiceTask;
import org.flowable.bpmn.model.SubProcess;
import org.flowable.bpmn.model.UserTask;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.TaskService;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.identitylink.api.IdentityLink;
import org.flowable.identitylink.api.IdentityLinkType;
import org.flowable.task.api.Task;
import org.flowable.task.api.TaskQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * #23 {@link TimeoutNotifyDelegate} 的任務查找規則。
 *
 * <h2>為什麼要用真實的 BpmnModel 而不是 mock</h2>
 *
 * <p>本類別最容易錯的一段是「boundary 的 execution 不是任務本身」的推導：
 * 服務任務的 current activity 是服務任務、boundary 要從 incoming flow 反推、
 * attachedToRef 才是任務 key。這條路徑的形狀由 {@code org.flowable.bpmn.model}
 * 的物件圖決定（{@code getIncomingFlows}/{@code getSourceFlowElement}/
 * {@code getAttachedToRef}），把它 mock 掉等於跳過真正要釘住的部分。
 * 這裡用真的 model 物件手動接線，讓規則本身被測到。
 *
 * <h2>失敗型態是「什麼都不發生」</h2>
 *
 * <p>通知失敗不會有例外、流程照跑 —— 所以「有送」與「沒送」兩邊都要釘。
 * 沒送的那幾條（查不到節點、attachedToRef 不是 UserTask、任務已完成、
 * 沒有收件人、多個 boundary 無法分辨）各自是一條測試，避免一個
 * 「反正就是 no-op」的鬆散斷言蓋住錯誤的 no-op。
 */
class TimeoutNotifyDelegateTest {

    private RepositoryService repositoryService;
    private TaskService taskService;
    private NotifyPublisher publisher;
    private DelegateExecution execution;
    private TimeoutNotifyDelegate delegate;

    @BeforeEach
    void setUp() {
        repositoryService = Mockito.mock(RepositoryService.class);
        taskService = Mockito.mock(TaskService.class);
        publisher = Mockito.mock(NotifyPublisher.class);
        execution = Mockito.mock(DelegateExecution.class);
        delegate = new TimeoutNotifyDelegate(repositoryService, taskService, publisher);

        when(execution.getCurrentActivityId()).thenReturn("notifyTimeout");
        when(execution.getProcessInstanceId()).thenReturn("pid-1");
        when(execution.getProcessDefinitionId()).thenReturn("tnt:1:42");
    }

    // ── 測試模型：boundary → serviceTask（本工項的主要接法）──────────

    /**
     * 手動組出 {@code approve --boundary(timeoutBoundary)--> notifyTimeout}。
     *
     * <p>{@code notifyTimeout.getIncomingFlows()} 必須手動接上 ——
     * XML 部署時由 parser 建立，這裡手動建立等價的物件圖。
     */
    private static BpmnModel serviceTaskAfterBoundary() {
        BpmnModel model = new BpmnModel();
        Process process = new Process();
        process.setId("tnt");

        UserTask approve = new UserTask();
        approve.setId("approve");
        approve.setName("審核關卡");

        BoundaryEvent boundary = new BoundaryEvent();
        boundary.setId("timeoutBoundary");
        boundary.setAttachedToRef(approve);
        boundary.setCancelActivity(false);

        ServiceTask notify = new ServiceTask();
        notify.setId("notifyTimeout");

        SequenceFlow flow = new SequenceFlow("timeoutBoundary", "notifyTimeout");
        flow.setId("f2");
        flow.setSourceFlowElement(boundary);
        flow.setTargetFlowElement(notify);
        notify.getIncomingFlows().add(flow);

        process.addFlowElement(approve);
        process.addFlowElement(boundary);
        process.addFlowElement(notify);
        process.addFlowElement(flow);
        model.addProcess(process);
        return model;
    }

    private Task task(String id, String name, String assignee) {
        Task task = Mockito.mock(Task.class);
        when(task.getId()).thenReturn(id);
        when(task.getName()).thenReturn(name);
        when(task.getAssignee()).thenReturn(assignee);
        return task;
    }

    private void taskQueryReturns(List<Task> tasks) {
        TaskQuery query = Mockito.mock(TaskQuery.class);
        when(taskService.createTaskQuery()).thenReturn(query);
        when(query.processInstanceId(anyString())).thenReturn(query);
        when(query.taskDefinitionKey(anyString())).thenReturn(query);
        when(query.list()).thenReturn(tasks);
    }

    private void capturePublisherCall() {
        ArgumentCaptor<String> assignee = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> candidates = ArgumentCaptor.forClass((Class<List<String>>) (Class<?>) List.class);
        verify(publisher).taskTimedOut(eq("task-1"), eq("審核關卡"), eq("pid-1"),
                eq("tnt:1:42"), assignee.capture(), candidates.capture());
        assertThat(assignee.getValue()).isEqualTo("mgr001");
        assertThat(candidates.getValue()).isEmpty();
    }

    // ── 正向：任務查找 ─────────────────────────────────────────────

    @Test
    @DisplayName("serviceTask 接在 boundary 之後：由 incoming flow 反推，查 attachedToRef 任務並通知受理人")
    void notifiesAssigneeOfTaskAttachedToBoundary() {
        when(repositoryService.getBpmnModel("tnt:1:42")).thenReturn(serviceTaskAfterBoundary());
        taskQueryReturns(List.of(task("task-1", "審核關卡", "mgr001")));

        delegate.notifyTimedOut(execution);

        capturePublisherCall();
    }

    @Test
    @DisplayName("delegate 直接掛在 boundary 上（executionListener）：current activity 就是 boundary")
    void acceptsBoundaryAsCurrentActivity() {
        when(execution.getCurrentActivityId()).thenReturn("timeoutBoundary");
        when(repositoryService.getBpmnModel("tnt:1:42")).thenReturn(serviceTaskAfterBoundary());
        taskQueryReturns(List.of(task("task-1", "審核關卡", "mgr001")));

        delegate.notifyTimedOut(execution);

        capturePublisherCall();
    }

    @Test
    @DisplayName("候選任務：沒有 assignee 時送候選『人』；群組（groupId）沒有 email，略過")
    void candidateTaskSendsCandidateUsersOnly() {
        when(repositoryService.getBpmnModel("tnt:1:42")).thenReturn(serviceTaskAfterBoundary());
        taskQueryReturns(List.of(task("task-1", "審核關卡", null)));

        IdentityLink user1 = Mockito.mock(IdentityLink.class);
        when(user1.getType()).thenReturn(IdentityLinkType.CANDIDATE);
        when(user1.getUserId()).thenReturn("mgr001");
        IdentityLink user2 = Mockito.mock(IdentityLink.class);
        when(user2.getType()).thenReturn(IdentityLinkType.CANDIDATE);
        when(user2.getUserId()).thenReturn("mgr002");
        IdentityLink group = Mockito.mock(IdentityLink.class);
        when(group.getType()).thenReturn(IdentityLinkType.CANDIDATE);
        when(group.getUserId()).thenReturn(null);
        when(group.getGroupId()).thenReturn("finance");
        when(taskService.getIdentityLinksForTask("task-1"))
                .thenReturn(List.of(user1, group, user2));

        delegate.notifyTimedOut(execution);

        ArgumentCaptor<String> assignee = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> candidates = ArgumentCaptor.forClass((Class<List<String>>) (Class<?>) List.class);
        verify(publisher).taskTimedOut(eq("task-1"), eq("審核關卡"), eq("pid-1"),
                eq("tnt:1:42"), assignee.capture(), candidates.capture());
        assertThat(assignee.getValue()).isNull();
        assertThat(candidates.getValue()).containsExactly("mgr001", "mgr002");
    }

    // ── 反向：每一條 no-op 路徑各自釘住 ─────────────────────────────

    @Test
    @DisplayName("BPMN 裡查不到 current activity → no-op（不拋）")
    void unknownActivityIsNoOp() {
        when(repositoryService.getBpmnModel("tnt:1:42")).thenReturn(serviceTaskAfterBoundary());
        when(execution.getCurrentActivityId()).thenReturn("no-such-node");

        delegate.notifyTimedOut(execution);

        verify(publisher, never()).taskTimedOut(anyString(), anyString(), anyString(),
                anyString(), any(), any());
    }

    @Test
    @DisplayName("serviceTask 上游不是 boundary → no-op（掛錯位置的 delegate 不亂提醒）")
    void serviceTaskWithoutBoundaryUpstreamIsNoOp() {
        BpmnModel model = new BpmnModel();
        Process process = new Process();
        process.setId("tnt");
        UserTask approve = new UserTask();
        approve.setId("approve");
        ServiceTask notify = new ServiceTask();
        notify.setId("notifyTimeout");
        SequenceFlow flow = new SequenceFlow("approve", "notifyTimeout");
        flow.setSourceFlowElement(approve);
        flow.setTargetFlowElement(notify);
        notify.getIncomingFlows().add(flow);
        process.addFlowElement(approve);
        process.addFlowElement(notify);
        process.addFlowElement(flow);
        model.addProcess(process);
        when(repositoryService.getBpmnModel("tnt:1:42")).thenReturn(model);

        delegate.notifyTimedOut(execution);

        verify(publisher, never()).taskTimedOut(anyString(), anyString(), anyString(),
                anyString(), any(), any());
    }

    @Test
    @DisplayName("兩個 boundary 匯入同一節點 → 無法分辨來源，no-op（寧可不送也不要送錯任務）")
    void ambiguousBoundarySourcesAreNoOp() {
        BpmnModel model = serviceTaskAfterBoundary();
        Process process = model.getMainProcess();
        BoundaryEvent second = new BoundaryEvent();
        second.setId("secondBoundary");
        second.setAttachedToRef((UserTask) process.getFlowElement("approve"));
        SequenceFlow secondFlow = new SequenceFlow("secondBoundary", "notifyTimeout");
        secondFlow.setSourceFlowElement(second);
        secondFlow.setTargetFlowElement(process.getFlowElement("notifyTimeout"));
        ((ServiceTask) process.getFlowElement("notifyTimeout")).getIncomingFlows().add(secondFlow);
        process.addFlowElement(second);
        process.addFlowElement(secondFlow);
        when(repositoryService.getBpmnModel("tnt:1:42")).thenReturn(model);

        delegate.notifyTimedOut(execution);

        verify(publisher, never()).taskTimedOut(anyString(), anyString(), anyString(),
                anyString(), any(), any());
    }

    @Test
    @DisplayName("attachedToRef 不是 UserTask（掛在 subprocess 上）→ no-op")
    void nonUserTaskAttachedRefIsNoOp() {
        BpmnModel model = new BpmnModel();
        Process process = new Process();
        process.setId("tnt");
        SubProcess sub = new SubProcess();
        sub.setId("sub");
        BoundaryEvent boundary = new BoundaryEvent();
        boundary.setId("timeoutBoundary");
        boundary.setAttachedToRef(sub);
        ServiceTask notify = new ServiceTask();
        notify.setId("notifyTimeout");
        SequenceFlow flow = new SequenceFlow("timeoutBoundary", "notifyTimeout");
        flow.setSourceFlowElement(boundary);
        flow.setTargetFlowElement(notify);
        notify.getIncomingFlows().add(flow);
        process.addFlowElement(sub);
        process.addFlowElement(boundary);
        process.addFlowElement(notify);
        process.addFlowElement(flow);
        model.addProcess(process);
        when(repositoryService.getBpmnModel("tnt:1:42")).thenReturn(model);

        delegate.notifyTimedOut(execution);

        verify(publisher, never()).taskTimedOut(anyString(), anyString(), anyString(),
                anyString(), any(), any());
    }

    @Test
    @DisplayName("任務已不存在（已完成）→ no-op")
    void completedTaskIsNoOp() {
        when(repositoryService.getBpmnModel("tnt:1:42")).thenReturn(serviceTaskAfterBoundary());
        taskQueryReturns(List.of());

        delegate.notifyTimedOut(execution);

        verify(publisher, never()).taskTimedOut(anyString(), anyString(), anyString(),
                anyString(), any(), any());
    }

    @Test
    @DisplayName("沒有 assignee 也沒有候選人 → no-op（不發沒有收件人的空訊息）")
    void noRecipientsIsNoOp() {
        when(repositoryService.getBpmnModel("tnt:1:42")).thenReturn(serviceTaskAfterBoundary());
        taskQueryReturns(List.of(task("task-1", "審核關卡", null)));
        when(taskService.getIdentityLinksForTask("task-1")).thenReturn(List.of());

        delegate.notifyTimedOut(execution);

        verify(publisher, never()).taskTimedOut(anyString(), anyString(), anyString(),
                anyString(), any(), any());
    }

    @Test
    @DisplayName("中斷式 boundary（cancelActivity=true）→ 明確 no-op，不對已取消的任務發誤導提醒")
    void interruptingBoundaryIsNoOp() {
        BpmnModel model = serviceTaskAfterBoundary();
        ((BoundaryEvent) model.getMainProcess().getFlowElement("timeoutBoundary"))
                .setCancelActivity(true);
        when(repositoryService.getBpmnModel("tnt:1:42")).thenReturn(model);
        // 即使任務查得到（同一個 command 尚未 flush 的真實情境）也不得通知。
        taskQueryReturns(List.of(task("task-1", "審核關卡", "mgr001")));

        delegate.notifyTimedOut(execution);

        verify(publisher, never()).taskTimedOut(anyString(), anyString(), anyString(),
                anyString(), any(), any());
    }

    @Test
    @DisplayName("查 BPMN model 失敗 → execute() 吞例外，流程不受影響（fail-open）")
    void repositoryFailureIsSwallowed() {
        when(repositoryService.getBpmnModel("tnt:1:42"))
                .thenThrow(new RuntimeException("model not found"));

        assertThatCode(() -> delegate.execute(execution))
                .as("delegate 在 Flowable job 裡執行；往外丟會讓 timer job 失敗、案件卡住")
                .doesNotThrowAnyException();
        verify(publisher, never()).taskTimedOut(anyString(), anyString(), anyString(),
                anyString(), any(), any());
    }
}
