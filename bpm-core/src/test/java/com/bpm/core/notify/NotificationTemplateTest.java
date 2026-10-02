package com.bpm.core.notify;

import com.bpm.core.model.NotifyConfig;
import com.bpm.core.model.NotifyTemplate;
import com.bpm.core.repository.NotifyConfigRepository;
import com.bpm.core.repository.NotifyTemplateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 郵件模板機制（security-audit P1-13）。
 *
 * <p><b>問題。</b>{@code NotifyTaskListener} 送出的是
 * {@code task.getProcessDefinitionId()}（例如 {@code leave-approval:1:2504}，含版號），
 * 而 {@code EmailConsumer} 拿它去查
 * {@code findByProcessDefinitionKeyAndEventType...}，DB 存的是
 * {@code leave-approval} → <b>永遠查不到任何設定</b>。
 * {@code NotifyAdminController} 維護的整套模板機制形同虛設，
 * 一律落到硬編中文模板，而 {@code ${processName}} 會被渲染成那串 id。
 *
 * <p>（{@code WebhookTaskListener} 對同一件事有正確的 {@code extractProcessKey()}
 * —— 兩處不一致。）
 *
 * <p>另外兩個獨立問題：{@code templateId} 為 null 時 {@code findById(null)}
 * 會拋 {@code IllegalArgumentException} → retry 3 次後進 DLQ → 該通知永久遺失，
 * 且同一 config 之後每則通知都重踩；候選群組任務的 assignee 為 null →
 * 直接 return → <b>群組待辦完全不發通知</b>。
 */
class NotificationTemplateTest {

    private JavaMailSender mailSender;
    private NotifyConfigRepository configRepo;
    private NotifyTemplateRepository templateRepo;
    private EmailConsumer consumer;

    @BeforeEach
    void setUp() {
        mailSender = Mockito.mock(JavaMailSender.class);
        configRepo = Mockito.mock(NotifyConfigRepository.class);
        templateRepo = Mockito.mock(NotifyTemplateRepository.class);
        consumer = new EmailConsumer(mailSender, configRepo, templateRepo);
    }

    private static Map<String, Object> msg(String key, String assignee) {
        Map<String, Object> m = new HashMap<>();
        m.put("event", "task_assigned");
        m.put("taskName", "主管審核");
        m.put("assignee", assignee);
        m.put("processDefinitionKey", key);
        m.put("initiator", "user001");
        return m;
    }

    private static NotifyConfig cfg(String templateId) {
        NotifyConfig c = new NotifyConfig();
        c.setProcessDefinitionKey("leave-approval");
        c.setEventType("task_assigned");
        c.setChannel("email");
        c.setEnabled(true);
        c.setTemplateId(templateId);
        return c;
    }

    @Test
    @DisplayName("流程定義 id 必須被裁成 key，否則模板永遠查不到")
    void processDefinitionIdIsTrimmedToKey() {
        // #33：裁切規則從 NotifyTaskListener 搬到 NotifyPublisher（所有事件共用）。
        assertThat(NotifyPublisher.extractProcessKey("leave-approval:1:2504"))
                .isEqualTo("leave-approval");
        assertThat(NotifyPublisher.extractProcessKey("purchase-approval:12:99"))
                .isEqualTo("purchase-approval");
        // 已經是 key 的情況不得被破壞
        assertThat(NotifyPublisher.extractProcessKey("leave-approval"))
                .isEqualTo("leave-approval");
        assertThat(NotifyPublisher.extractProcessKey(null)).isEmpty();
    }

    @Test
    @DisplayName("設定好的模板必須真的被採用（改動前一律落到硬編模板）")
    void configuredTemplateIsUsed() {
        NotifyTemplate tmpl = new NotifyTemplate();
        tmpl.setName("自訂模板");
        tmpl.setChannel("email");
        tmpl.setSubjectTemplate("[自訂] ${processName} - ${taskName}");
        tmpl.setBodyTemplate("申請人 ${initiatorName} 的 ${taskName} 待處理");

        when(configRepo.findByProcessDefinitionKeyAndEventTypeAndEnabledTrue(
                "leave-approval", "task_assigned")).thenReturn(List.of(cfg("tmpl-1")));
        when(templateRepo.findById("tmpl-1")).thenReturn(Optional.of(tmpl));

        consumer.handle(msg("leave-approval", "mgr001"));

        ArgumentCaptor<SimpleMailMessage> cap = ArgumentCaptor.forClass(SimpleMailMessage.class);
        Mockito.verify(mailSender).send(cap.capture());
        assertThat(cap.getValue().getSubject())
                .as("應採用自訂模板，且 ${processName} 必須是 key 而非含版號的 id")
                .isEqualTo("[自訂] leave-approval - 主管審核");
        assertThat(cap.getValue().getText()).contains("申請人 user001");
    }

    @Test
    @DisplayName("templateId 為 null 不得拋例外（否則通知永久沉進 DLQ）")
    void nullTemplateIdDoesNotThrow() {
        when(configRepo.findByProcessDefinitionKeyAndEventTypeAndEnabledTrue(
                anyString(), anyString())).thenReturn(List.of(cfg(null)));

        assertThatCode(() -> consumer.handle(msg("leave-approval", "mgr001")))
                .as("findById(null) 會拋 IllegalArgumentException → retry 3 次後進 dlq.bpm，"
                        + "該通知永久遺失，且同一 config 之後每則通知都重踩")
                .doesNotThrowAnyException();

        // 必須退回硬編模板而不是靜默不發
        Mockito.verify(mailSender).send(any(SimpleMailMessage.class));
    }

    @Test
    @DisplayName("候選群組任務（assignee 為 null）必須通知候選人")
    void candidateGroupTasksAreNotified() {
        Map<String, Object> m = msg("leave-approval", null);
        m.put("candidateUsers", List.of("mgr001", "dir001"));
        when(configRepo.findByProcessDefinitionKeyAndEventTypeAndEnabledTrue(
                anyString(), anyString())).thenReturn(List.of());

        consumer.handle(m);

        ArgumentCaptor<SimpleMailMessage> cap = ArgumentCaptor.forClass(SimpleMailMessage.class);
        Mockito.verify(mailSender, Mockito.times(2)).send(cap.capture());
        assertThat(cap.getAllValues())
                .as("群組待辦改動前完全不發通知 —— 候選人不知道有事情等他")
                .extracting(mm -> mm.getTo()[0])
                .containsExactlyInAnyOrder("mgr001@company.com", "dir001@company.com");
    }

    @Test
    @DisplayName("既沒有 assignee 也沒有候選人時才可略過")
    void noRecipientIsSkipped() {
        when(configRepo.findByProcessDefinitionKeyAndEventTypeAndEnabledTrue(
                anyString(), anyString())).thenReturn(List.of());
        consumer.handle(msg("leave-approval", null));
        Mockito.verify(mailSender, Mockito.never()).send(any(SimpleMailMessage.class));
    }
}
