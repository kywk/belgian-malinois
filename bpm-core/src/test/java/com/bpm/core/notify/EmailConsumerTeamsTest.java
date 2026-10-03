package com.bpm.core.notify;

import com.bpm.core.model.NotifyConfig;
import com.bpm.core.model.NotifyTemplate;
import com.bpm.core.repository.NotifyConfigRepository;
import com.bpm.core.repository.NotifyTemplateRepository;
import com.bpm.core.webhook.WebhookUrlPolicy;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * #32 {@link EmailConsumer} 的 Teams 路由。
 *
 * <h2>為什麼用真的 HTTP server 而不是 mock {@code RestClient}</h2>
 *
 * <p>要釘住的規則都跟「請求有沒有真的出去」有關：政策拒絕時<b>不得</b>
 * 發請求、非 2xx／逾時要往外丟（走既有 retry→DLQ）、webhookUrl 空要略過
 * 且落 email fallback。用 mock 的 fluent API 只能證明「有呼叫某個方法」，
 * 而 {@link HttpServer} 可以從<b>另一端</b>數請求、檢查實際送出的 body
 * ——這是唯一能區分「送出了」與「看起來送出了」的證據（與
 * {@code TeamsNotifyDelegateTest} 同一條理由）。
 *
 * <h2>policy 的兩種設定</h2>
 *
 * <p>允許路徑用 {@code new WebhookUrlPolicy("localhost")}（把 loopback
 * 明列為例外，與 {@code application-test.yml} 同一條路徑）；拒絕路徑用
 * 空清單，並以 {@code 127.0.0.1} 當目標 —— 它與 {@code localhost} 是不同
 * 的字面 host，而政策比對的就是字面 host。兩者都是真的
 * {@link WebhookUrlPolicy}，不 mock。
 *
 * <h2>email 不得被 Teams 路徑影響</h2>
 *
 * <p>每一條 Teams 正向／反向斷言都同時驗證 {@code JavaMailSender} 沒有
 * 被呼叫（或該被呼叫時真的有呼叫）：這個工項最容易出的錯不是 Teams 送
 * 不出去，而是重構 config 迴圈時把 email 的既有路徑弄壞，或 Teams 失敗時
 * 偷偷 fallback 成 email。
 */
class EmailConsumerTeamsTest {

    private HttpServer server;
    private String baseUrl;

    private final AtomicInteger hits = new AtomicInteger();
    private final AtomicReference<String> lastMethod = new AtomicReference<>();
    private final AtomicReference<String> lastContentType = new AtomicReference<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>();

    private JavaMailSender mailSender;
    private NotifyConfigRepository configRepo;
    private NotifyTemplateRepository templateRepo;

    @BeforeEach
    void setUp() throws IOException {
        hits.set(0);
        lastMethod.set(null);
        lastContentType.set(null);
        lastBody.set(null);

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            hits.incrementAndGet();
            lastMethod.set(exchange.getRequestMethod());
            lastContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));

            String path = exchange.getRequestURI().getPath();
            if (path.contains("/slow")) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            int status;
            if (path.contains("/fail")) {
                status = 503;
            } else if (path.contains("/redirect")) {
                status = 302; // 不跟隨 3xx；3xx 也不算送達
            } else {
                status = 200;
            }
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
        // ⚠️ 必須是 localhost：allowed-hosts 比對的是 URL 裡的字面 host。
        baseUrl = "http://localhost:" + server.getAddress().getPort();

        mailSender = Mockito.mock(JavaMailSender.class);
        configRepo = Mockito.mock(NotifyConfigRepository.class);
        templateRepo = Mockito.mock(NotifyTemplateRepository.class);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    // ── 工具 ────────────────────────────────────────────────────────

    /** 允許 localhost（loopback 的明確例外），逾時寬鬆。 */
    private EmailConsumer consumer(String allowedHosts) {
        return consumer(allowedHosts, 2000);
    }

    private EmailConsumer consumer(String allowedHosts, long readTimeoutMs) {
        return new EmailConsumer(mailSender, configRepo, templateRepo,
                new WebhookUrlPolicy(allowedHosts), new ObjectMapper(), 2000, readTimeoutMs);
    }

    private static NotifyConfig teamsConfig(String webhookUrl, String templateId) {
        NotifyConfig c = new NotifyConfig();
        c.setId("cfg-teams");
        c.setProcessDefinitionKey("leave-approval");
        c.setEventType("task_assigned");
        c.setChannel("teams");
        c.setWebhookUrl(webhookUrl);
        c.setTemplateId(templateId);
        c.setEnabled(true);
        return c;
    }

    private static NotifyConfig emailConfig(String templateId) {
        NotifyConfig c = new NotifyConfig();
        c.setId("cfg-email");
        c.setProcessDefinitionKey("leave-approval");
        c.setEventType("task_assigned");
        c.setChannel("email");
        c.setTemplateId(templateId);
        c.setEnabled(true);
        return c;
    }

    private static NotifyTemplate template(String subject, String body) {
        NotifyTemplate t = new NotifyTemplate();
        t.setName("測試模板");
        t.setChannel("teams");
        t.setSubjectTemplate(subject);
        t.setBodyTemplate(body);
        return t;
    }

    /** 標準事件訊息：task_assigned＋任務名稱／申請人。 */
    private static Map<String, Object> msg(String event, String processDefKey, String assignee) {
        Map<String, Object> m = new HashMap<>();
        m.put("event", event);
        m.put("processDefinitionKey", processDefKey);
        m.put("taskName", "主管審核");
        m.put("initiator", "user001");
        if (assignee != null) m.put("assignee", assignee);
        return m;
    }

    private void givenConfigs(NotifyConfig... configs) {
        when(configRepo.findByProcessDefinitionKeyAndEventTypeAndEnabledTrue(
                anyString(), anyString())).thenReturn(List.of(configs));
    }

    private static String textOf(String payload) {
        try {
            JsonNode node = new ObjectMapper().readTree(payload);
            assertThat(node.size()).as("payload 形狀固定是單一欄位 text").isEqualTo(1);
            return node.get("text").asText();
        } catch (Exception e) {
            throw new AssertionError("payload 不是合法 JSON: " + payload, e);
        }
    }

    // ── 正向：真的有 POST 出去 ──────────────────────────────────────

    @Test
    @DisplayName("teams 設定：模板渲染後的 subject／body 送成 {\"text\": subject\\nbody\"}")
    void teamsConfigSendsRenderedTemplateToWebhook() {
        givenConfigs(teamsConfig(baseUrl + "/hook", "tmpl-1"));
        when(templateRepo.findById("tmpl-1"))
                .thenReturn(Optional.of(template("[案件] ${taskName}", "申請人 ${initiatorName} 請處理")));

        consumer("localhost").handle(msg("task_assigned", "leave-approval", "mgr001"));

        assertThat(hits.get()).isEqualTo(1);
        assertThat(lastMethod.get()).isEqualTo("POST");
        assertThat(lastContentType.get()).contains("application/json");
        assertThat(lastBody.get())
                .isEqualTo("{\"text\":\"[案件] 主管審核\\n申請人 user001 請處理\"}");
        verifyNoInteractions(mailSender);
    }

    @Test
    @DisplayName("🔴 teams 推送不需要收件人：沒有 assignee／候選人時仍送出（email 的 early return 不再擋）")
    void teamsDoesNotRequireRecipients() {
        givenConfigs(teamsConfig(baseUrl + "/hook", null));

        // 群組待辦：沒有 assignee、沒有 candidateUsers。舊結構在這裡就 return 了。
        consumer("localhost").handle(msg("task_assigned", "leave-approval", null));

        assertThat(hits.get())
                .as("收件人只對 email 有意義；Teams 是「一則訊息進一個頻道」")
                .isEqualTo(1);
        assertThat(textOf(lastBody.get()))
                .isEqualTo("【BPM】您有新的待辦事項：主管審核"
                        + "\n您好，\n\n任務名稱：主管審核\n申請人：user001\n\n請登入 BPM 平台處理。");
        verifyNoInteractions(mailSender);
    }

    @Test
    @DisplayName("teams 無模板（或模板不存在）→ 用事件的預設文案，且不落 email fallback")
    void missingTemplateFallsBackToDefaultTextOnTeams() {
        givenConfigs(teamsConfig(baseUrl + "/hook", "ghost-template"));
        when(templateRepo.findById("ghost-template")).thenReturn(Optional.empty());

        consumer("localhost").handle(msg("task_assigned", "leave-approval", "mgr001"));

        assertThat(hits.get()).isEqualTo(1);
        assertThat(textOf(lastBody.get()))
                .as("email 路徑遇到不存在的模板是「略過該設定、落硬編 email」；"
                        + "Teams 路徑改成用同一份預設文案直接送 —— 兩邊文案共用 defaultMessage()")
                .contains("您有新的待辦事項").contains("主管審核");
        verifyNoInteractions(mailSender);
    }

    // ── 反向：政策拒絕／缺 URL ──────────────────────────────────────

    @Test
    @DisplayName("🔴 policy 拒絕（127.0.0.1 不在清單）→ 零請求、不拋例外（不重試）、不 fallback 成 email")
    void policyRejectionSendsNothingAndDoesNotFallBack() {
        givenConfigs(teamsConfig("http://127.0.0.1:" + server.getAddress().getPort() + "/hook", null));

        assertThatCode(() -> consumer("").handle(msg("task_assigned", "leave-approval", "mgr001")))
                .as("政策拒絕是非暫時性的：丟例外只會重試到 DLQ，fallback 則是把 teams 設定偷改成 email")
                .doesNotThrowAnyException();

        assertThat(hits.get()).as("政策拒絕時連嘗試都不能有").isZero();
        verifyNoInteractions(mailSender);
    }

    @Test
    @DisplayName("webhookUrl 空 → 略過該設定；全數略過才落 email fallback（收件人仍有效）")
    void blankWebhookUrlSkipsConfigAndFallsBackToEmail() {
        givenConfigs(teamsConfig(null, null));

        consumer("").handle(msg("task_assigned", "leave-approval", "mgr001"));

        assertThat(hits.get()).isZero();
        ArgumentCaptor<SimpleMailMessage> cap = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(cap.capture());
        assertThat(cap.getValue().getSubject())
                .as("Teams 設定沒有 URL 時，既有 email fallback 行為不變")
                .contains("待辦").contains("主管審核");
    }

    @Test
    @DisplayName("被略過的 teams 設定不阻擋後續 email 設定（config 迴圈走完）")
    void skippedTeamsConfigDoesNotBlockLaterEmailConfig() {
        givenConfigs(teamsConfig("", null), emailConfig("tmpl-e"));
        when(templateRepo.findById("tmpl-e"))
                .thenReturn(Optional.of(template("主旨", "內文")));

        consumer("").handle(msg("task_assigned", "leave-approval", "mgr001"));

        assertThat(hits.get()).isZero();
        verify(mailSender).send(any(SimpleMailMessage.class));
    }

    // ── 兩個 channel 同時設定 ──────────────────────────────────────

    @Test
    @DisplayName("email＋teams 同時設定（email 在前）：兩條都送，email 不得 return 掉 teams")
    void bothChannelsFireWhenEmailFirst() {
        givenConfigs(emailConfig("tmpl-e"), teamsConfig(baseUrl + "/hook", null));
        when(templateRepo.findById("tmpl-e"))
                .thenReturn(Optional.of(template("主旨", "內文")));

        consumer("localhost").handle(msg("task_assigned", "leave-approval", "mgr001"));

        assertThat(hits.get()).as("teams 設定在 email 之後，仍必須被走到").isEqualTo(1);
        verify(mailSender).send(any(SimpleMailMessage.class));
    }

    @Test
    @DisplayName("email＋teams 同時設定（teams 在前）：兩條都送，teams 不得吃掉 email")
    void bothChannelsFireWhenTeamsFirst() {
        givenConfigs(teamsConfig(baseUrl + "/hook", null), emailConfig("tmpl-e"));
        when(templateRepo.findById("tmpl-e"))
                .thenReturn(Optional.of(template("主旨", "內文")));

        consumer("localhost").handle(msg("task_assigned", "leave-approval", "mgr001"));

        assertThat(hits.get()).isEqualTo(1);
        verify(mailSender).send(any(SimpleMailMessage.class));
    }

    // ── 失敗語意：非 2xx／3xx／逾時 → 往外丟（retry→DLQ）────────────

    @Test
    @DisplayName("非 2xx（503）→ 往外丟（走既有 retry→DLQ），不 fallback 成 email")
    void non2xxThrowsForRetry() {
        givenConfigs(teamsConfig(baseUrl + "/fail", null));

        assertThatThrownBy(() -> consumer("localhost").handle(msg("task_assigned", "leave-approval", "mgr001")))
                .as("與 email 的 SMTP 例外同一語意：AMQP listener 的 retry 接手，耗盡後進 dlq.bpm")
                .isInstanceOf(RestClientException.class);

        assertThat(hits.get()).as("非 2xx 是『有送出去但對方失敗』—— 與政策拒絕（零請求）不同")
                .isEqualTo(1);
        verifyNoInteractions(mailSender);
    }

    @Test
    @DisplayName("3xx 不算送達（不跟隨重導）→ 往外丟，請求只有原始那一次")
    void redirect3xxThrowsForRetry() {
        givenConfigs(teamsConfig(baseUrl + "/redirect", null));

        assertThatThrownBy(() -> consumer("localhost").handle(msg("task_assigned", "leave-approval", "mgr001")))
                .isInstanceOf(IllegalStateException.class);

        assertThat(hits.get()).as("跟隨重導＝繞過 SSRF 閘門；SafeRestClients 已關掉").isEqualTo(1);
        verifyNoInteractions(mailSender);
    }

    @Test
    @DisplayName("讀取逾時（100ms vs 睡 1s）→ 往外丟，不是無限等待")
    void readTimeoutThrowsForRetry() {
        givenConfigs(teamsConfig(baseUrl + "/slow", null));

        assertThatThrownBy(() -> consumer("localhost", 100)
                .handle(msg("task_assigned", "leave-approval", "mgr001")))
                .isInstanceOf(RestClientException.class);

        assertThat(hits.get()).isEqualTo(1);
        verifyNoInteractions(mailSender);
    }

    // ── 未知事件 ────────────────────────────────────────────────────

    @Test
    @DisplayName("未知事件且無模板 → 略過（不送空訊息、不落 email），也不拋例外")
    void unknownEventWithoutTemplateIsSkipped() {
        givenConfigs(teamsConfig(baseUrl + "/hook", null));

        assertThatCode(() -> consumer("localhost")
                .handle(msg("no_such_event", "leave-approval", "mgr001")))
                .doesNotThrowAnyException();

        assertThat(hits.get()).as("沒有模板也沒有預設文案，就沒有東西可送").isZero();
        verifyNoInteractions(mailSender);
    }

    @Test
    @DisplayName("policy 拒絕＋email 設定同時存在：email 照常（各 channel 獨立）")
    void policyRejectionDoesNotBlockEmailConfig() {
        givenConfigs(teamsConfig("http://127.0.0.1:" + server.getAddress().getPort() + "/hook", null),
                emailConfig("tmpl-e"));
        when(templateRepo.findById("tmpl-e"))
                .thenReturn(Optional.of(template("主旨", "內文")));

        consumer("").handle(msg("task_assigned", "leave-approval", "mgr001"));

        assertThat(hits.get()).isZero();
        verify(mailSender, Mockito.description(
                        "不 fallback 指的是硬編 fallback；同一事件的 email 設定是獨立的 channel"))
                .send(any(SimpleMailMessage.class));
    }

    @Test
    @DisplayName("email 設定存在但沒有收件人：不寄信、也不落 fallback（既有語意不變）")
    void emailConfigWithoutRecipientsSendsNothing() {
        givenConfigs(emailConfig("tmpl-e"));
        when(templateRepo.findById("tmpl-e"))
                .thenReturn(Optional.of(template("主旨", "內文")));

        consumer("").handle(msg("task_assigned", "leave-approval", null));

        verify(mailSender, never()).send(any(SimpleMailMessage.class));
    }
}
