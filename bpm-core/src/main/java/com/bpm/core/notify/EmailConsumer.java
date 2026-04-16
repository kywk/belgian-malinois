package com.bpm.core.notify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class EmailConsumer {

    private static final Logger log = LoggerFactory.getLogger(EmailConsumer.class);
    private final JavaMailSender mailSender;

    public EmailConsumer(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    @RabbitListener(queues = "bpm.notify.queue")
    public void handle(Map<String, Object> msg) {
        String event = (String) msg.get("event");
        String assignee = (String) msg.get("assignee");
        String taskName = (String) msg.getOrDefault("taskName", "");
        String initiator = (String) msg.getOrDefault("initiator", "");

        if (assignee == null || assignee.isBlank()) {
            log.debug("No assignee for notification, skipping email");
            return;
        }

        String subject;
        String body;

        switch (event != null ? event : "") {
            case "task_assigned" -> {
                subject = "【BPM】您有新的待辦事項：" + taskName;
                body = "您好，\n\n您有一筆新的待辦事項需要處理。\n\n"
                        + "任務名稱：" + taskName + "\n"
                        + "申請人：" + initiator + "\n\n"
                        + "請登入 BPM 平台處理。";
            }
            case "process_returned" -> {
                subject = "【BPM】您的申請已被退回";
                body = "您好，\n\n您的申請「" + taskName + "」已被退回，請修改後重新提交。";
            }
            case "process_rejected" -> {
                subject = "【BPM】您的申請已被拒絕";
                body = "您好，\n\n您的申請「" + taskName + "」已被拒絕。\n\n"
                        + "原因：" + msg.getOrDefault("reason", "未提供");
            }
            case "process_completed" -> {
                subject = "【BPM】您的申請已核准";
                body = "您好，\n\n您的申請「" + taskName + "」已核准完成。";
            }
            default -> {
                log.debug("Unknown notification event: {}", event);
                return;
            }
        }

        try {
            SimpleMailMessage mail = new SimpleMailMessage();
            mail.setTo(assignee + "@company.com"); // Convention: userId@company.com
            mail.setSubject(subject);
            mail.setText(body);
            mail.setFrom("bpm-noreply@company.com");
            mailSender.send(mail);
            log.info("Email sent to {} for event {}", assignee, event);
        } catch (Exception e) {
            log.error("Failed to send email to {}: {}", assignee, e.getMessage());
            throw new RuntimeException(e); // triggers retry → DLQ
        }
    }
}
