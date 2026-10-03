package com.bpm.core.notify;

import com.bpm.core.repository.NotifyConfigRepository;
import com.bpm.core.repository.NotifyTemplateRepository;
import com.bpm.core.webhook.WebhookUrlPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #7 殘餘收尾：{@code process_cancelled} 的預設信件模板。
 *
 * <p>整合測試只看得到 RabbitMQ 上的 payload；如果 {@code EmailConsumer} 的
 * switch 少了 {@code process_cancelled}，訊息會被 {@code default -> return}
 * 靜默丟棄 —— 沒有例外、沒有 DLQ、payload 斷言照樣全綠，只有受理人永遠
 * 收不到信。這一條把「新的 case 真的存在」與收件人規則釘住。
 *
 * <p>刻意不設 {@code processDefinitionKey}：走的是硬編模板的 fallback 路徑
 * （本工項新增的 case），與 {@code NotificationTemplateTest} 的模板機制
 * 測試互補而不重疊。
 */
class EmailConsumerCancelTest {

    private JavaMailSender mailSender;
    private EmailConsumer consumer;

    @BeforeEach
    void setUp() {
        mailSender = Mockito.mock(JavaMailSender.class);
        consumer = new EmailConsumer(mailSender, Mockito.mock(NotifyConfigRepository.class),
                Mockito.mock(NotifyTemplateRepository.class),
                new WebhookUrlPolicy(""), new ObjectMapper(), 2000, 2000);
    }

    @Test
    @DisplayName("process_cancelled 給受理人：主旨與內文都不得落到 default 的靜默丟棄")
    void cancelledToAssigneeUsesDefaultTemplate() {
        consumer.handle(Map.of(
                "event", "process_cancelled",
                "taskName", "主管審核",
                "initiator", "user001",
                "assignee", "mgr001"));

        ArgumentCaptor<SimpleMailMessage> cap = ArgumentCaptor.forClass(SimpleMailMessage.class);
        Mockito.verify(mailSender).send(cap.capture());
        assertThat(cap.getValue().getTo()).containsExactly("mgr001@company.com");
        assertThat(cap.getValue().getSubject()).contains("撤回").contains("主管審核");
        // 收件人是受理人，信裡要說清楚「誰撤回的、哪個任務不用處理了」。
        assertThat(cap.getValue().getText())
                .contains("user001").contains("主管審核").contains("撤回");
    }

    @Test
    @DisplayName("process_cancelled 候選任務：assignee 為空時送每一位候選人")
    void cancelledToCandidatesSendsToEachCandidate() {
        consumer.handle(Map.of(
                "event", "process_cancelled",
                "taskName", "財務審核",
                "initiator", "user001",
                "candidateUsers", List.of("mgr001", "dir001")));

        ArgumentCaptor<SimpleMailMessage> cap = ArgumentCaptor.forClass(SimpleMailMessage.class);
        Mockito.verify(mailSender, Mockito.times(2)).send(cap.capture());
        assertThat(cap.getAllValues())
                .extracting(mm -> mm.getTo()[0])
                .containsExactlyInAnyOrder("mgr001@company.com", "dir001@company.com");
    }
}
