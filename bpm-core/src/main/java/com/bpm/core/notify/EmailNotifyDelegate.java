package com.bpm.core.notify;

import com.bpm.core.engine.BpmnFieldSupport;
import org.flowable.bpmn.model.ServiceTask;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * 通用寄信 delegate（#43）：BPMN 節點上直接指定收件人與內容寄一封信。
 *
 * <p>用途是「流程走到某一步時通知某些人」，收件人與內容由設計器在節點上
 * 填寫，不需要事先建 NotifyConfig／模板：
 *
 * <pre>{@code
 * <serviceTask id="notifyEmail" flowable:delegateExpression="${emailNotifyDelegate}">
 *   <extensionElements>
 *     <flowable:field name="to" stringValue="mgr001@company.com,${applicantEmail}"/>
 *     <flowable:field name="subject" stringValue="案件 ${processInstanceId} 已受理"/>
 *     <flowable:field name="body" stringValue="您好，案件已進入審核。"/>
 *   </extensionElements>
 * </serviceTask>
 * }</pre>
 *
 * <h2>欄位</h2>
 * <ul>
 *   <li>{@code to}（必填）：逗號分隔的收件人。支援 {@code ${var}} 替換
 *       （規則見 {@link BpmnFieldSupport}）。<b>空白／全部替換後為空
 *       → log warn + 不寄</b>：沒有收件人的信不是信，而且空 {@code To}
 *       會讓 SMTP 端拒絕或寄給退信位址。</li>
 *   <li>{@code subject}：選填；空白時用預設主旨。</li>
 *   <li>{@code body}：選填；空白時寄空內文（純提醒信仍成立）。</li>
 * </ul>
 *
 * <h2>⚠️ fail-open：寄信失敗絕不影響流程</h2>
 *
 * <p>{@link #execute} 吞掉<b>所有</b>例外，只記 log。與
 * {@link NotifyPublisher#publish}／{@link TimeoutNotifyDelegate} 同一語意：
 * delegate 在 Flowable 的 job 裡執行，例外往外丟會讓 job 失敗、重試，
 * 甚至讓案件卡在寄不出去的那一步 —— 而信寄不出去不是業務錯誤。
 *
 * <h2>收件人解析：與 DLQ 告警同一條規則</h2>
 *
 * <p>逗號分隔 → trim → 去空白項 → 去重，與
 * {@code DeadLetterConsumer.parseRecipients} 的形狀一致。收件人<b>原樣</b>
 * 使用，不補 {@code @company.com}（那是 {@code EmailConsumer} 給
 * assignee id 的舊慣例；本 delegate 的 {@code to} 是完整 email）。
 *
 * <h2>⚠️ 不依賴 {@code flowable:field} 的 setter 注入</h2>
 *
 * <p>欄位一律在 {@link #send} 內用 {@link BpmnFieldSupport} 從 model 讀取。
 * 理由（單例 bean 的注入競態、7.2.0 預設 MIXED 模式的位元碼證據）寫在
 * {@link BpmnFieldSupport} 的類別註解 —— 三個通用 delegate 共用同一份說明，
 * 不在此複製。
 */
@Component("emailNotifyDelegate")
public class EmailNotifyDelegate implements JavaDelegate {

    private static final Logger log = LoggerFactory.getLogger(EmailNotifyDelegate.class);

    /** 與 {@code DeadLetterConsumer}／{@code EmailConsumer} 同一個寄件者。 */
    private static final String FROM = "bpm-noreply@company.com";

    /** {@code subject} 未設定或空白時的主旨。 */
    static final String DEFAULT_SUBJECT = "【BPM】流程通知";

    private final JavaMailSender mailSender;

    /**
     * 沒有引擎依賴，因此不需要 {@link TimeoutNotifyDelegate} 那種
     * {@code @Lazy}：本 bean 被 {@code FlowableConfig} 收進引擎的 beans map，
     * 但建構它只用到 Spring 的 {@code JavaMailSender}，不會回頭依賴
     * processEngine，沒有循環。
     */
    public EmailNotifyDelegate(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    /** Flowable 進入點：所有失敗都吞在這裡，理由見類別註解。 */
    @Override
    public void execute(DelegateExecution execution) {
        try {
            send(execution);
        } catch (Exception e) {
            log.warn("EmailNotifyDelegate 寄信失敗（不影響流程）：processInstanceId={} activityId={}",
                    execution.getProcessInstanceId(), execution.getCurrentActivityId(), e);
        }
    }

    /**
     * 可單獨測試的本文（package-private）：不吞例外，讓單元測試看得到行為。
     */
    void send(DelegateExecution execution) {
        ServiceTask task = serviceTask(execution);
        if (task == null) {
            log.warn("EmailNotifyDelegate 不在 ServiceTask 上（currentFlowElement 不是 ServiceTask），"
                    + "讀不到欄位，略過：processInstanceId={}", execution.getProcessInstanceId());
            return;
        }

        List<String> recipients = parseRecipients(BpmnFieldSupport.field(task, "to", execution));
        if (recipients.isEmpty()) {
            log.warn("EmailNotifyDelegate 的 to 為空或替換後沒有有效收件人，不寄空信，略過："
                    + "processInstanceId={} activityId={}",
                    execution.getProcessInstanceId(), execution.getCurrentActivityId());
            return;
        }

        String subject = BpmnFieldSupport.field(task, "subject", execution);
        String body = BpmnFieldSupport.field(task, "body", execution);

        SimpleMailMessage mail = new SimpleMailMessage();
        mail.setFrom(FROM);
        mail.setTo(recipients.toArray(String[]::new));
        mail.setSubject(subject == null || subject.isBlank() ? DEFAULT_SUBJECT : subject);
        mail.setText(body == null ? "" : body);
        mailSender.send(mail);

        log.info("EmailNotifyDelegate 已寄出：processInstanceId={} activityId={} recipients={}",
                execution.getProcessInstanceId(), execution.getCurrentActivityId(), recipients);
    }

    /**
     * 逗號分隔的收件人解析：trim、去空白項、去重。
     * 與 {@code DeadLetterConsumer.parseRecipients} 同一條規則。
     */
    static List<String> parseRecipients(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .distinct()
                .toList();
    }

    /**
     * 取當前 serviceTask。delegateExpression 掛在 serviceTask 上時
     * {@code getCurrentFlowElement()} 就是它；掛錯位置（例如 execution
     * listener 掛在別的元素上）回 {@code null}，由呼叫端 no-op。
     */
    private static ServiceTask serviceTask(DelegateExecution execution) {
        return execution.getCurrentFlowElement() instanceof ServiceTask task ? task : null;
    }
}
