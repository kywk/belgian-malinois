package com.bpm.core.notify;

import com.bpm.core.model.NotifyConfig;
import com.bpm.core.model.NotifyTemplate;
import com.bpm.core.repository.NotifyConfigRepository;
import com.bpm.core.repository.NotifyTemplateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
public class EmailConsumer {

    private static final Logger log = LoggerFactory.getLogger(EmailConsumer.class);
    private final JavaMailSender mailSender;
    private final NotifyConfigRepository configRepo;
    private final NotifyTemplateRepository templateRepo;

    public EmailConsumer(JavaMailSender mailSender, NotifyConfigRepository configRepo,
                         NotifyTemplateRepository templateRepo) {
        this.mailSender = mailSender;
        this.configRepo = configRepo;
        this.templateRepo = templateRepo;
    }

    @RabbitListener(queues = "bpm.notify.queue")
    public void handle(Map<String, Object> msg) {
        String event = str(msg, "event");
        String processDefKey = str(msg, "processDefinitionKey");

        // 收件人：優先 assignee；候選人任務沒有 assignee，改送給候選人。
        // 改動前只看 assignee，為 null 就 return → 群組待辦完全不發通知
        // （security-audit P1-13）。
        List<String> recipients = resolveRecipients(msg);
        if (recipients.isEmpty()) {
            log.debug("No assignee or candidate users, skipping notification");
            return;
        }

        // Try template-based notification first
        if (processDefKey != null && !processDefKey.isBlank()) {
            List<NotifyConfig> configs = configRepo
                    .findByProcessDefinitionKeyAndEventTypeAndEnabledTrue(processDefKey, event);
            for (NotifyConfig cfg : configs) {
                if (!"email".equals(cfg.getChannel())) continue;
                // ⚠️ templateId 可能為 null（NotifyConfig 沒有 nullable=false，
                // 而 create 端點先前也不驗證）。findById(null) 會拋
                // IllegalArgumentException → retry 3 次後進 dlq.bpm →
                // 該通知永久遺失，而且同一 config 之後每則通知都重踩。
                if (cfg.getTemplateId() == null || cfg.getTemplateId().isBlank()) {
                    log.warn("通知設定 {} 的 templateId 為空，改用預設模板（流程 {}／事件 {}）",
                            cfg.getId(), cfg.getProcessDefinitionKey(), cfg.getEventType());
                    continue;
                }
                var tmpl = templateRepo.findById(cfg.getTemplateId()).orElse(null);
                if (tmpl == null) {
                    log.warn("通知設定 {} 指向不存在的模板 {}，改用預設模板",
                            cfg.getId(), cfg.getTemplateId());
                    continue;
                }
                recipients.forEach(to -> sendWithTemplate(tmpl, msg, to));
                return;
            }
        }

        // Fallback: hardcoded templates
        String taskName = str(msg, "taskName");
        String initiator = str(msg, "initiator");
        String subject, body;

        switch (event != null ? event : "") {
            case "task_assigned" -> {
                subject = "【BPM】您有新的待辦事項：" + taskName;
                body = "您好，\n\n任務名稱：" + taskName + "\n申請人：" + initiator + "\n\n請登入 BPM 平台處理。";
            }
            case "process_returned" -> {
                subject = "【BPM】您的申請已被退回";
                body = "您好，\n\n您的申請「" + taskName + "」已被退回，請修改後重新提交。";
            }
            case "process_rejected" -> {
                subject = "【BPM】您的申請已被拒絕";
                body = "您好，\n\n您的申請「" + taskName + "」已被拒絕。\n原因：" + str(msg, "reason");
            }
            case "process_completed" -> {
                subject = "【BPM】您的申請已核准";
                body = "您好，\n\n您的申請「" + taskName + "」已核准完成。";
            }
            default -> { return; }
        }
        final String s = subject, b = body;
        recipients.forEach(to -> sendEmail(to, s, b));
    }

    /**
     * 解析收件人。
     *
     * <p>assignee 優先；候選人任務（candidateUsers／candidateGroups）沒有
     * assignee，此時改送給 NotifyTaskListener 帶過來的候選人清單。
     */
    @SuppressWarnings("unchecked")
    private static List<String> resolveRecipients(Map<String, Object> msg) {
        String assignee = str(msg, "assignee");
        if (!assignee.isBlank()) return List.of(assignee);
        Object candidates = msg.get("candidateUsers");
        if (candidates instanceof List<?> list) {
            return list.stream().filter(Objects::nonNull)
                    .map(Object::toString).filter(v -> !v.isBlank()).distinct().toList();
        }
        return List.of();
    }

    private void sendWithTemplate(NotifyTemplate tmpl, Map<String, Object> vars, String to) {
        String subject = replaceVars(tmpl.getSubjectTemplate(), vars);
        String body = replaceVars(tmpl.getBodyTemplate(), vars);
        sendEmail(to, subject, body);
    }

    private String replaceVars(String template, Map<String, Object> vars) {
        if (template == null) return "";
        String result = template;
        for (var entry : vars.entrySet()) {
            result = result.replace("${" + entry.getKey() + "}", entry.getValue() != null ? entry.getValue().toString() : "");
        }
        // Standard aliases
        result = result.replace("${processName}", str(vars, "processDefinitionKey"));
        result = result.replace("${taskName}", str(vars, "taskName"));
        result = result.replace("${assigneeName}", str(vars, "assignee"));
        result = result.replace("${initiatorName}", str(vars, "initiator"));
        return result;
    }

    private void sendEmail(String to, String subject, String body) {
        try {
            SimpleMailMessage mail = new SimpleMailMessage();
            mail.setTo(to + "@company.com");
            mail.setSubject(subject);
            mail.setText(body);
            mail.setFrom("bpm-noreply@company.com");
            mailSender.send(mail);
            log.info("Email sent to {}: {}", to, subject);
        } catch (Exception e) {
            log.error("Failed to send email to {}: {}", to, e.getMessage());
            throw new RuntimeException(e);
        }
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v != null ? v.toString() : "";
    }
}
