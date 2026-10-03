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
 *     <flowable:field name="to" stringValue="mgr001,${applicantEmail}"/>
 *     <flowable:field name="subject" stringValue="案件 ${processInstanceId} 已受理"/>
 *     <flowable:field name="body" stringValue="您好，案件已進入審核。"/>
 *   </extensionElements>
 * </serviceTask>
 * }</pre>
 *
 * <h2>欄位</h2>
 * <ul>
 *   <li>{@code to}（必填）：逗號分隔的收件人，每項可以是完整 email 或
 *       userId（不含 {@code @} 時自動補 {@code @company.com}，見下方
 *       「收件人解析」）。支援 {@code ${var}} 替換（規則見
 *       {@link BpmnFieldSupport}）。<b>空白／全部替換後為空 → log warn
 *       + 不寄</b>：沒有收件人的信不是信，而且空 {@code To} 會讓 SMTP
 *       端拒絕或寄給退信位址。</li>
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
 * <h2>收件人解析：DLQ 告警的形狀 + userId 自動補網域</h2>
 *
 * <p>逗號分隔 → trim → 去空白項 → 補網域 → 去重。逗號／trim／去空／
 * 去重的形狀與 {@code DeadLetterConsumer.parseRecipients} 一致，補網域
 * 是本 delegate 額外的一步：
 *
 * <ul>
 *   <li><b>值不含 {@code @} → 視為 userId，補 {@code @company.com}</b>，
 *       與 {@code EmailConsumer} 對 assignee／candidateUsers 的慣例相同
 *       （{@code to + "@company.com"}）。</li>
 *   <li><b>值含 {@code @} → 原樣使用</b>，不補、不改寫。</li>
 * </ul>
 *
 * <p><b>歷史：為什麼先前刻意不補，為什麼現在改。</b>本 delegate 初版把
 * {@code to} 當完整 email 原樣送出，理由是「不替設計師猜」——怕把打錯的
 * 字串變成另一個錯誤位址。但平台其他地方的收件人慣例是 userId
 * （assignee／candidateUsers 都只存 {@code user001}），設計師照慣例在
 * {@code to} 填 {@code user001} 時，舊版會把 {@code user001} 原樣交給
 * SMTP：被拒收還算好的，寬鬆的 SMTP／MailHog 會收下 {@code To: user001}
 * 而無人察覺——沒有例外、log 還記「已寄出」，正是最貴的靜默錯誤
 * （2026-10-03 實測）。現在不含 {@code @} 一律補網域：就算設計師填錯，
 * 補出來的位址也是可預期、可從 log 追的形狀。
 *
 * <p>「含 {@code @}」採最寬鬆的判斷：{@code a@} 這類位置怪異的值也原樣
 * 放行——補成 {@code a@@company.com} 更不可能是設計師要的，丟掉又是另一
 * 種靜默錯誤。壞位址交給 SMTP 拒收，失敗照 {@link #execute} 的 fail-open
 * 語意只記 log。
 *
 * <p>去重發生在補網域<b>之後</b>：{@code user001,user001@company.com}
 * 會塌成同一個位址，同一封信不寄兩次。
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

    /**
     * userId 收件人自動補的網域：與 {@code EmailConsumer.sendEmail}
     * （{@code mail.setTo(to + "@company.com")}）同一慣例。
     *
     * <p>刻意不抽共用常數：{@code EmailConsumer} 是 #43 範圍外的既有程式
     * （本工項不動它），而 {@link #FROM} 目前也和兩個 consumer 各寫一份；
     * 改網域時要連同 {@code EmailConsumer} 一起改。
     */
    private static final String COMPANY_MAIL_DOMAIN = "@company.com";

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
     * 逗號分隔的收件人解析：trim、去空白項、補網域、去重。
     *
     * <p>逗號／trim／去空／去重的形狀與
     * {@code DeadLetterConsumer.parseRecipients} 一致；差別是多一步
     * {@link #normalizeRecipient}。順序是<b>先補網域再 distinct</b>：
     * {@code user001,user001@company.com} 會塌成同一個位址，不寄兩次。
     */
    static List<String> parseRecipients(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .map(EmailNotifyDelegate::normalizeRecipient)
                .distinct()
                .toList();
    }

    /**
     * 收件人正規化：不含 {@code @} 視為 userId → 補
     * {@code @company.com}；含 {@code @} → 原樣。
     *
     * <p>「含 {@code @}」是最寬鬆的判斷：{@code a@} 這種位置怪異的值也
     * 原樣放行（補成 {@code a@@company.com} 更不可能是設計師要的；丟掉
     * 又是另一種靜默錯誤）。壞位址由 SMTP 拒收，失敗照 {@link #execute}
     * 的 fail-open 語意只記 log。完整契約見類別註解。
     */
    private static String normalizeRecipient(String value) {
        return value.contains("@") ? value : value + COMPANY_MAIL_DOMAIN;
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
