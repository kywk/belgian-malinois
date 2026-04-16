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
        String assignee = str(msg, "assignee");
        String processDefKey = str(msg, "processDefinitionKey");

        if (assignee == null || assignee.isBlank()) {
            log.debug("No assignee, skipping");
            return;
        }

        // Try template-based notification first
        if (processDefKey != null && !processDefKey.isBlank()) {
            List<NotifyConfig> configs = configRepo
                    .findByProcessDefinitionKeyAndEventTypeAndEnabledTrue(processDefKey, event);
            for (NotifyConfig cfg : configs) {
                if (!"email".equals(cfg.getChannel())) continue;
                var tmpl = templateRepo.findById(cfg.getTemplateId()).orElse(null);
                if (tmpl != null) {
                    sendWithTemplate(tmpl, msg, assignee);
                    return;
                }
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
        sendEmail(assignee, subject, body);
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
