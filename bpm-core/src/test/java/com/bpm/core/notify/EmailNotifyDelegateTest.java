package com.bpm.core.notify;

import org.flowable.bpmn.model.FieldExtension;
import org.flowable.bpmn.model.ServiceTask;
import org.flowable.bpmn.model.UserTask;
import org.flowable.engine.delegate.DelegateExecution;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * #43 {@link EmailNotifyDelegate} 的寄送規則。
 *
 * <h2>為什麼一定要 capture 出 {@link SimpleMailMessage}</h2>
 *
 * <p>這個 delegate 的失敗型態與通知一樣是「什麼都不發生」：不寄信不會有
 * 例外、流程照跑。只驗「execute 沒拋」等於什麼都沒驗。所以正向測試全部
 * 用 {@link ArgumentCaptor} 釘住實際送進 {@code JavaMailSender} 的
 * 收件人、主旨、內文 —— 這是唯一能區分「寄對了」與「根本沒寄」的證據。
 *
 * <h2>單元測試不碰真實 SMTP</h2>
 *
 * <p>{@code JavaMailSender} 是 mock；整合測試（
 * {@code EmailNotifyDelegateIntegrationTest}）才在真實引擎裡跑，
 * 那裡的 SMTP 刻意是壞的（{@code application-test.yml} 指到不存在的
 * port），驗的是 fail-open：寄不出去，流程仍然走完。
 *
 * <h2>收件人正規化（#43 收尾）</h2>
 *
 * <p>{@code to} 現在接受 userId：不含 {@code @} 補 {@code @company.com}。
 * 這是平台慣例（assignee／candidateUsers 都是 {@code user001}）與 delegate
 * 初版「原樣送出」之間的落差收尾；邊界（含 {@code @} 一律原樣、補網域後
 * 才去重）都在下面釘住。
 */
class EmailNotifyDelegateTest {

    private JavaMailSender mailSender;
    private DelegateExecution execution;
    private EmailNotifyDelegate delegate;

    @BeforeEach
    void setUp() {
        mailSender = Mockito.mock(JavaMailSender.class);
        execution = Mockito.mock(DelegateExecution.class);
        delegate = new EmailNotifyDelegate(mailSender);
        when(execution.getProcessInstanceId()).thenReturn("pid-1");
        when(execution.getCurrentActivityId()).thenReturn("notifyEmail");
    }

    // ── 工具 ────────────────────────────────────────────────────────

    private static ServiceTask serviceTask(FieldExtension... fields) {
        ServiceTask task = new ServiceTask();
        task.setId("notifyEmail");
        for (FieldExtension field : fields) {
            task.getFieldExtensions().add(field);
        }
        return task;
    }

    private static FieldExtension field(String name, String stringValue) {
        FieldExtension field = new FieldExtension();
        field.setFieldName(name);
        field.setStringValue(stringValue);
        return field;
    }

    private SimpleMailMessage captureMail() {
        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        return captor.getValue();
    }

    // ── 正向 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("to／subject／body 原樣送出：收件人、寄件者、主旨、內文都對")
    void sendsLiteralFields() {
        when(execution.getCurrentFlowElement()).thenReturn(serviceTask(
                field("to", "mgr001@company.com"),
                field("subject", "案件已受理"),
                field("body", "您好，案件已進入審核。")));

        delegate.send(execution);

        SimpleMailMessage mail = captureMail();
        assertThat(mail.getTo()).containsExactly("mgr001@company.com");
        assertThat(mail.getFrom()).isEqualTo("bpm-noreply@company.com");
        assertThat(mail.getSubject()).isEqualTo("案件已受理");
        assertThat(mail.getText()).isEqualTo("您好，案件已進入審核。");
    }

    @Test
    @DisplayName("${var} 在 to／subject／body 都替換（三個欄位共用同一份 helper）")
    void substitutesVariablesInAllFields() {
        when(execution.getVariable("approverEmail")).thenReturn("mgr001@company.com");
        when(execution.getVariable("caseNo")).thenReturn("C-2026-001");
        when(execution.getCurrentFlowElement()).thenReturn(serviceTask(
                field("to", "audit@company.com,${approverEmail}"),
                field("subject", "案件 ${caseNo} 已受理"),
                field("body", "承辦人：${approverEmail}")));

        delegate.send(execution);

        SimpleMailMessage mail = captureMail();
        assertThat(mail.getTo()).containsExactly("audit@company.com", "mgr001@company.com");
        assertThat(mail.getSubject()).isEqualTo("案件 C-2026-001 已受理");
        assertThat(mail.getText()).isEqualTo("承辦人：mgr001@company.com");
    }

    @Test
    @DisplayName("收件人 trim、去空項、去重；完整 email 原樣（形狀與 DLQ 告警一致）")
    void parsesRecipientsLikeDeadLetterConsumer() {
        when(execution.getCurrentFlowElement()).thenReturn(serviceTask(
                field("to", " a@x.com ,, b@x.com ,a@x.com, ")));

        delegate.send(execution);

        assertThat(captureMail().getTo()).containsExactly("a@x.com", "b@x.com");
    }

    // ── 正向：收件人正規化（#43 收尾）─────────────────────────────────

    @Test
    @DisplayName("userId（不含 @）自動補 @company.com：平台慣例 user001 → user001@company.com")
    void userIdGetsCompanyDomain() {
        when(execution.getCurrentFlowElement()).thenReturn(serviceTask(
                field("to", " user001 ")));

        delegate.send(execution);

        assertThat(captureMail().getTo()).containsExactly("user001@company.com");
    }

    @Test
    @DisplayName("完整 email（含 @）原樣使用，不重複補網域")
    void emailIsKeptAsIs() {
        when(execution.getCurrentFlowElement()).thenReturn(serviceTask(
                field("to", "user001@x.com")));

        delegate.send(execution);

        assertThat(captureMail().getTo()).containsExactly("user001@x.com");
    }

    @Test
    @DisplayName("混合清單：userId 補網域、email 原樣，順序不變")
    void mixesUserIdsAndEmails() {
        when(execution.getCurrentFlowElement()).thenReturn(serviceTask(
                field("to", "mgr001, audit@x.com , user002")));

        delegate.send(execution);

        assertThat(captureMail().getTo())
                .containsExactly("mgr001@company.com", "audit@x.com", "user002@company.com");
    }

    @Test
    @DisplayName("${var} 替換出 userId 也補網域（設計師把變數當 userId 用是常態）")
    void substitutedUserIdGetsDomain() {
        when(execution.getVariable("approver")).thenReturn("user007");
        when(execution.getCurrentFlowElement()).thenReturn(serviceTask(
                field("to", "mgr001@company.com,${approver}")));

        delegate.send(execution);

        assertThat(captureMail().getTo())
                .containsExactly("mgr001@company.com", "user007@company.com");
    }

    @Test
    @DisplayName("@ 在怪位置（a@）→ 原樣放行：含 @ 就當設計師自己寫的位址，不補也不丟")
    void atSignAnywhereIsLeftUntouched() {
        when(execution.getCurrentFlowElement()).thenReturn(serviceTask(
                field("to", "a@,b")));

        delegate.send(execution);

        assertThat(captureMail().getTo()).containsExactly("a@", "b@company.com");
    }

    @Test
    @DisplayName("去重在補網域之後：user001 與 user001@company.com 是同一位址，只寄一次")
    void dedupesAfterNormalization() {
        when(execution.getCurrentFlowElement()).thenReturn(serviceTask(
                field("to", "user001, user001@company.com,user001")));

        delegate.send(execution);

        assertThat(captureMail().getTo()).containsExactly("user001@company.com");
    }

    @Test
    @DisplayName("subject 缺欄位 → 預設主旨；body 缺欄位 → 空內文")
    void defaultsWhenOptionalFieldsMissing() {
        when(execution.getCurrentFlowElement()).thenReturn(serviceTask(
                field("to", "a@x.com")));

        delegate.send(execution);

        SimpleMailMessage mail = captureMail();
        assertThat(mail.getSubject()).isEqualTo(EmailNotifyDelegate.DEFAULT_SUBJECT);
        assertThat(mail.getText()).isEmpty();
    }

    // ── 反向：不寄 ──────────────────────────────────────────────────

    @Test
    @DisplayName("to 欄位不存在 → no-op（不寄空信），也不拋例外")
    void missingToFieldIsNoOp() {
        when(execution.getCurrentFlowElement()).thenReturn(serviceTask(
                field("subject", "x")));

        assertThatCode(() -> delegate.execute(execution)).doesNotThrowAnyException();

        verify(mailSender, never()).send(any(SimpleMailMessage.class));
    }

    @Test
    @DisplayName("to 全為空白／變數缺失塌成空白 → no-op（不寄給退信位址）")
    void blankOrCollapsedToIsNoOp() {
        when(execution.getCurrentFlowElement()).thenReturn(serviceTask(
                field("to", " , ${missing} , ")));

        delegate.send(execution);

        verify(mailSender, never()).send(any(SimpleMailMessage.class));
    }

    @Test
    @DisplayName("掛在非 ServiceTask 上（掛錯位置）→ no-op，不拋例外")
    void nonServiceTaskCurrentElementIsNoOp() {
        when(execution.getCurrentFlowElement()).thenReturn(new UserTask());

        assertThatCode(() -> delegate.execute(execution)).doesNotThrowAnyException();

        verify(mailSender, never()).send(any(SimpleMailMessage.class));
    }

    // ── fail-open ───────────────────────────────────────────────────

    @Test
    @DisplayName("mailSender 拋例外 → execute() 吞掉，流程不受影響（fail-open）")
    void mailFailureIsSwallowedByExecute() {
        when(execution.getCurrentFlowElement()).thenReturn(serviceTask(
                field("to", "a@x.com")));
        Mockito.doThrow(new org.springframework.mail.MailSendException("smtp down"))
                .when(mailSender).send(any(SimpleMailMessage.class));

        assertThatCode(() -> delegate.execute(execution))
                .as("delegate 在 Flowable job 裡執行；往外丟會讓 job 失敗、案件卡住")
                .doesNotThrowAnyException();

        verify(mailSender).send(any(SimpleMailMessage.class));
    }
}
