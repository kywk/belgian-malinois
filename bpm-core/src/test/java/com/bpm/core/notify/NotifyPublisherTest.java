package com.bpm.core.notify;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;

/**
 * #33 通知觸發事件完整化：{@link NotifyPublisher} 的 payload 與判定規則。
 *
 * <h2>這個測試組在防什麼</h2>
 *
 * <p>通知的失敗型態是「什麼都不發生」：沒有例外、流程照跑、簽核照簽，
 * 只有收件人沒收到信。所以規則本身（事件判定、收件人欄位、
 * processDefinitionKey 裁版號）必須被直接釘住 —— 端到端測試只證明
 * 「某個組合有送」，證明不了「拒絕沒有被誤判成退回」這類反例。
 *
 * <h2>P2-1 紅線</h2>
 *
 * <p>{@link #completedNotificationDoesNotLeakVariables} 把「vars 只用於
 * 判定、值不外送」寫成斷言。沒有這一條，日後有人「順手」把整包 vars
 * 放進 payload（外部系統或信件就看得到薪資、身分證號）不會有任何紅燈。
 */
class NotifyPublisherTest {

    private RabbitTemplate rabbitTemplate;
    private NotifyPublisher publisher;

    @BeforeEach
    void setUp() {
        rabbitTemplate = Mockito.mock(RabbitTemplate.class);
        publisher = new NotifyPublisher(rabbitTemplate);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> capturePayload() {
        ArgumentCaptor<Map<String, Object>> cap =
                ArgumentCaptor.forClass((Class<Map<String, Object>>) (Class<?>) Map.class);
        Mockito.verify(rabbitTemplate).convertAndSend(
                eq(NotifyPublisher.EXCHANGE), eq(NotifyPublisher.ROUTING_KEY), cap.capture());
        return cap.getValue();
    }

    // ── 規則：任務完成後的事件判定（只有這一份）────────────────────

    @Test
    @DisplayName("退回：approved=false 且不是拒絕 → process_returned")
    void returnedClassification() {
        assertThat(NotifyPublisher.applicantEventFor(
                Map.of("approved", false, "rejected", false), false))
                .isEqualTo("process_returned");
        assertThat(NotifyPublisher.applicantEventFor(
                Map.of("approved", false), false))
                .isEqualTo("process_returned");
    }

    @Test
    @DisplayName("拒絕 wins over 退回：approved=false + rejected=true → process_rejected")
    void rejectedBeatsReturned() {
        assertThat(NotifyPublisher.applicantEventFor(
                Map.of("approved", false, "rejected", true), true))
                .isEqualTo("process_rejected");
        // 條件順序若寫反（先判 approved=false）就會得到 process_returned。
        assertThat(NotifyPublisher.applicantEventFor(
                Map.of("approved", false, "rejected", true), false))
                .isEqualTo("process_rejected");
    }

    @Test
    @DisplayName("結案只在「核准且流程結束」時發 —— 核准但還在跑不得發")
    void completedRequiresApprovedAndEnded() {
        assertThat(NotifyPublisher.applicantEventFor(
                Map.of("approved", true, "rejected", false), true))
                .isEqualTo("process_completed");
        assertThat(NotifyPublisher.applicantEventFor(
                Map.of("approved", true, "rejected", false), false))
                .as("purchase-approval 的主管核准後還有財務關卡 —— 不能發『已核准完成』")
                .isNull();
    }

    @Test
    @DisplayName("沒有判定結果時不發（例如補件重送的空 vars）")
    void noResultMeansNoNotification() {
        assertThat(NotifyPublisher.applicantEventFor(Map.of(), false)).isNull();
        assertThat(NotifyPublisher.applicantEventFor(Map.of(), true)).isNull();
        // 拒絕但流程還沒結束仍要發（自訂流程的 rejected 可能繼續走）。
        assertThat(NotifyPublisher.applicantEventFor(Map.of("rejected", true), false))
                .isEqualTo("process_rejected");
    }

    // ── payload 形狀 ─────────────────────────────────────────────

    @Test
    @DisplayName("processDefinitionKey 必須裁成 key（含版號的 id 會讓模板機制靜默失效）")
    void processDefinitionIdIsTrimmedToKey() {
        assertThat(NotifyPublisher.extractProcessKey("leave-approval:1:2504"))
                .isEqualTo("leave-approval");
        assertThat(NotifyPublisher.extractProcessKey("purchase-approval:12:99"))
                .isEqualTo("purchase-approval");
        assertThat(NotifyPublisher.extractProcessKey("leave-approval"))
                .isEqualTo("leave-approval");
        assertThat(NotifyPublisher.extractProcessKey(null)).isEmpty();

        publisher.taskCompleted("pid-1", "leave-approval:3:77", "task-1", "主管審核",
                Map.of("approved", true), true, "user001");
        assertThat(capturePayload())
                .containsEntry("processDefinitionKey", "leave-approval")
                .doesNotContainValue("leave-approval:3:77");
    }

    @Test
    @DisplayName("申請人通知：收件人欄位是 assignee（EmailConsumer 的既有契約）")
    void applicantNotificationUsesAssigneeAsRecipient() {
        publisher.taskCompleted("pid-1", "leave-approval:1:1", "task-1", "主管審核",
                Map.of("approved", false, "rejected", false), false, "user001");

        assertThat(capturePayload())
                .containsEntry("event", "process_returned")
                .containsEntry("taskId", "task-1")
                .containsEntry("taskName", "主管審核")
                .containsEntry("processInstanceId", "pid-1")
                .containsEntry("assignee", "user001")
                .containsEntry("initiator", "user001");
    }

    @Test
    @DisplayName("沒有自然人申請人時不發（寄給 system:erp 沒有意義）")
    void noApplicantMeansNoNotification() {
        publisher.taskCompleted("pid-1", "leave-approval:1:1", "task-1", "主管審核",
                Map.of("approved", false), false, null);
        publisher.taskCompleted("pid-1", "leave-approval:1:1", "task-1", "主管審核",
                Map.of("approved", false), false, "  ");

        Mockito.verify(rabbitTemplate, never()).convertAndSend(
                Mockito.anyString(), Mockito.anyString(), Mockito.any(Object.class));
    }

    @Test
    @DisplayName("P2-1：vars 只用於判定，值絕不外送")
    void completedNotificationDoesNotLeakVariables() {
        Map<String, Object> vars = Map.of(
                "approved", true,
                "rejected", false,
                "salary", 123456,
                "idNumber", "A123456789",
                "rejectReason", "薪資調幅尚未報帳",
                "comment", "簽核意見全文");

        publisher.taskCompleted("pid-1", "leave-approval:1:1", "task-1", "主管審核",
                vars, true, "user001");

        Map<String, Object> payload = capturePayload();
        assertThat(payload)
                .as("流程變數與簽核意見一律不得外送")
                .doesNotContainKeys("variables", "salary", "idNumber", "rejectReason", "comment")
                .doesNotContainValue(123456)
                .doesNotContainValue("A123456789")
                .doesNotContainValue("薪資調幅尚未報帳")
                .doesNotContainValue("簽核意見全文");
    }

    @Test
    @DisplayName("認領：收件人是其他候選人，assignee 刻意留空（放了會寄回認領者）")
    void claimedNotificationGoesToOtherCandidates() {
        publisher.taskClaimed("task-1", "財務審核", "pid-1",
                "purchase-approval:1:1", "mgr001", List.of("dir001", "mgr002"));

        assertThat(capturePayload())
                .containsEntry("event", "task_claimed")
                .containsEntry("claimedBy", "mgr001")
                .containsEntry("candidateUsers", List.of("dir001", "mgr002"))
                .doesNotContainKey("assignee");
    }

    @Test
    @DisplayName("認領：沒有其他候選人時不發空訊息")
    void claimedWithoutOtherCandidatesSendsNothing() {
        publisher.taskClaimed("task-1", "財務審核", "pid-1",
                "purchase-approval:1:1", "mgr001", List.of());
        publisher.taskClaimed("task-1", "財務審核", "pid-1",
                "purchase-approval:1:1", "mgr001", null);

        Mockito.verify(rabbitTemplate, never()).convertAndSend(
                Mockito.anyString(), Mockito.anyString(), Mockito.any(Object.class));
    }

    @Test
    @DisplayName("催辦：受理人放 assignee、催辦人放 initiator（署名用）")
    void urgedNotificationShape() {
        publisher.taskUrged("task-1", "主管審核", "pid-1",
                "leave-approval:1:1", "mgr001", List.of(), "user001");

        assertThat(capturePayload())
                .containsEntry("event", "task_urged")
                .containsEntry("assignee", "mgr001")
                .containsEntry("initiator", "user001");
    }

    @Test
    @DisplayName("撤回：受理人放 assignee、撤回人放 initiator；候選任務放 candidateUsers")
    void cancelledNotificationShape() {
        publisher.processCancelled("task-1", "主管審核", "pid-1",
                "leave-approval", "mgr001", List.of(), "user001");

        assertThat(capturePayload())
                .containsEntry("event", "process_cancelled")
                .containsEntry("taskId", "task-1")
                .containsEntry("taskName", "主管審核")
                .containsEntry("processInstanceId", "pid-1")
                .containsEntry("processDefinitionKey", "leave-approval")
                .containsEntry("assignee", "mgr001")
                .containsEntry("initiator", "user001")
                .doesNotContainKey("candidateUsers")
                // P2-1：鍵恰好是這一組 —— 沒有變數、沒有撤回原因、
                // 沒有表單內容。reason 是自由文字，可能夾帶個資。
                .containsOnlyKeys("event", "timestamp", "taskId", "taskName",
                        "processInstanceId", "processDefinitionKey", "assignee", "initiator");

        Mockito.clearInvocations(rabbitTemplate);
        publisher.processCancelled("task-2", "財務審核", "pid-1",
                "purchase-approval", null, List.of("mgr001", "dir001"), "user001");

        assertThat(capturePayload())
                .containsEntry("candidateUsers", List.of("mgr001", "dir001"))
                .doesNotContainKey("assignee");
    }

    @Test
    @DisplayName("指派：候選任務帶 candidateUsers、有 assignee 時不帶空清單")
    void assignedNotificationShape() {
        publisher.taskAssigned("task-1", "財務審核", null,
                List.of("mgr001", "dir001"), "pid-1", "purchase-approval:1:1", "user001");
        assertThat(capturePayload())
                .containsEntry("event", "task_assigned")
                .containsEntry("candidateUsers", List.of("mgr001", "dir001"))
                .doesNotContainKey("assignee");

        Mockito.clearInvocations(rabbitTemplate);
        publisher.taskAssigned("task-2", "主管審核", "mgr001",
                List.of(), "pid-1", "leave-approval:1:1", "user001");
        assertThat(capturePayload())
                .containsEntry("assignee", "mgr001")
                .doesNotContainKey("candidateUsers");
    }

    // ── 不可影響流程 ────────────────────────────────────────────

    @Test
    @DisplayName("發送失敗不得往外拋 —— RabbitMQ 掛掉時簽核仍然要成功")
    void publishFailureNeverThrows() {
        Mockito.doThrow(new RuntimeException("broker down"))
                .when(rabbitTemplate).convertAndSend(Mockito.anyString(),
                        Mockito.anyString(), Mockito.any(Object.class));

        assertThatCode(() -> publisher.taskAssigned("task-1", "主管審核", "mgr001",
                List.of(), "pid-1", "leave-approval:1:1", "user001"))
                .as("通知只是提醒；讓例外往外丟會回滾呼叫端的簽核交易")
                .doesNotThrowAnyException();

        assertThatCode(() -> publisher.taskCompleted("pid-1", "leave-approval:1:1",
                "task-1", "主管審核", Map.of("approved", true), true, "user001"))
                .doesNotThrowAnyException();

        assertThatCode(() -> publisher.processCancelled("task-1", "主管審核", "pid-1",
                "leave-approval", "mgr001", List.of(), "user001"))
                .as("撤回通知失敗同樣不得影響撤回交易")
                .doesNotThrowAnyException();
    }
}
