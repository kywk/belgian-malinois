package com.bpm.core.notify;

import com.bpm.core.model.NotifyConfig;
import com.bpm.core.repository.NotifyConfigRepository;
import com.bpm.core.repository.NotifyTemplateRepository;
import com.bpm.core.support.ExternalApiTestSink;
import com.bpm.core.support.IntegrationTestBase;
import com.bpm.core.support.TestGatewayMockMvcCustomizer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.AbstractMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #32：{@code channel=teams} 的 admin 驗證與端到端推送。
 *
 * <h2>為什麼 admin 驗證走真實 HTTP</h2>
 *
 * <p>{@code requireValidTeamsWebhook} 拋的是 {@code ResponseStatusException}，
 * 容器會以 ERROR dispatch 轉到 {@code /error} 組回應，而線上的狀態碼正是
 * 那條路徑決定的（見 {@code NotifyConfigTargetValidationTest} 的類別註解）。
 * MockMvc 不做 error dispatch，所以用 MockMvc 寫，那條規則壞掉時測試照樣
 * 全綠。本類別沿用同一條「狀態碼斷言一律走真實 HTTP」的規則。
 *
 * <h2>為什麼要有一條真的經過 RabbitMQ 的推送</h2>
 *
 * <p>單元測試（{@code EmailConsumerTeamsTest}）直接建構 consumer，證明不了
 * Spring 裝配真的成立（{@code WebhookUrlPolicy}／{@code ObjectMapper}／
 * {@code bpm.webhook.*} 逾時注入、{@code @RabbitListener} 的 Jackson 反
 * 序列化、測試 profile 的 {@code allowed-hosts: localhost}）。這裡用真的
 * {@code convertAndSend} 送一則通知訊息，從 {@link ExternalApiTestSink}
 * 的另一端確認 POST 真的抵達 ——「沒有拋例外」證明不了任何事。
 *
 * <h2>fixture 全部走真實 HTTP</h2>
 *
 * <p>與 #84 的測試同一條理由：新守衛就擋在 controller 裡，若改用 repository
 * 造 fixture，測試可能因為 fixture 本身不合法而提前失敗，讓後面的斷言變成
 * 空斷言。建立成功本身就是「請求真的送達 controller」的證明。
 */
class TeamsNotifyIntegrationTest extends IntegrationTestBase {

    /** 持有通配權限 {@code *} → {@code ROLE_ADMIN}；{@code /api/admin/**} 需要它。 */
    private static final String ADMIN = "admin001";

    @Autowired
    private NotifyConfigRepository configRepo;

    @Autowired
    private NotifyTemplateRepository templateRepo;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private AmqpAdmin amqpAdmin;

    @Autowired
    private RabbitListenerEndpointRegistry listenerRegistry;

    private final HttpClient http = HttpClient.newHttpClient();

    private final List<String> createdTemplates = new ArrayList<>();
    private final List<String> createdConfigs = new ArrayList<>();

    @BeforeEach
    void setUp() {
        ExternalApiTestSink.reset();
        truncateAuditLog();
    }

    @AfterEach
    void removeFixtures() {
        // 測試共用同一組容器（見 IntegrationTestBase 類別註解），不留垃圾給別的測試。
        createdConfigs.forEach(id -> configRepo.findById(id).ifPresent(configRepo::delete));
        createdTemplates.forEach(id -> templateRepo.findById(id).ifPresent(templateRepo::delete));
        createdConfigs.clear();
        createdTemplates.clear();
    }

    // ── HTTP 小工具 ─────────────────────────────────────────────────

    private HttpResponse<String> send(String method, String path, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + SERVLET_PORT + path))
                .header("X-Gateway-Secret", TestGatewayMockMvcCustomizer.GATEWAY_SECRET)
                .header("X-User-Id", ADMIN)
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return send("POST", path, body);
    }

    private HttpResponse<String> put(String path, String body) throws Exception {
        return send("PUT", path, body);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return send("GET", path, null);
    }

    // ── fixture ─────────────────────────────────────────────────────

    /** 走 {@code POST /api/admin/notify-templates} 建立模板，回傳 id。 */
    private String givenTemplate(String name) throws Exception {
        var res = post("/api/admin/notify-templates",
                "{\"name\":\"" + name + "\",\"channel\":\"email\","
                        + "\"subjectTemplate\":\"[E2E] ${taskName}\","
                        + "\"bodyTemplate\":\"申請人 ${initiatorName}\"}");
        assertThat(res.statusCode())
                .as("前置條件：模板必須建立成功，否則後面的守衛斷言全部無意義")
                .isEqualTo(200);
        String id = res.body().replaceAll(".*\"id\":\"([^\"]*)\".*", "$1");
        assertThat(id).isNotBlank();
        createdTemplates.add(id);
        return id;
    }

    /** 唯一的 eventType：避開 (processDefinitionKey, eventType, channel) 唯一約束。 */
    private static String uniqueEvent(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String teamsBody(String key, String eventType, String templateId, String webhookUrlJson) {
        return "{\"processDefinitionKey\":\"" + key + "\","
                + "\"eventType\":\"" + eventType + "\","
                + "\"channel\":\"teams\","
                + "\"webhookUrl\":" + webhookUrlJson + ","
                + "\"templateId\":\"" + templateId + "\","
                + "\"enabled\":true}";
    }

    /** 走 admin API 建立一筆合法的 teams 設定，回傳 id。 */
    private String givenTeamsConfig(String key, String eventType, String templateId, String webhookUrl)
            throws Exception {
        var res = post("/api/admin/notify-configs",
                teamsBody(key, eventType, templateId, "\"" + webhookUrl + "\""));
        assertThat(res.statusCode())
                .as("前置條件：teams 設定必須建立成功（走完 requireValidTeamsWebhook），實際回 " + res.body())
                .isEqualTo(200);
        String id = res.body().replaceAll(".*\"id\":\"([^\"]*)\".*", "$1");
        assertThat(id).isNotBlank();
        createdConfigs.add(id);
        return id;
    }

    // ── admin 驗證：POST ────────────────────────────────────────────

    @Test
    @DisplayName("POST channel=teams 帶合法 webhookUrl → 200，且真的存進去")
    void createTeamsConfigWithValidUrlSucceeds() throws Exception {
        String templateId = givenTemplate("T32-valid");
        String url = sinkUrl("teams-admin-ok");

        String id = givenTeamsConfig("leave-approval", uniqueEvent("task_assigned"), templateId, url);

        NotifyConfig saved = configRepo.findById(id).orElseThrow();
        assertThat(saved.getChannel()).isEqualTo("teams");
        assertThat(saved.getWebhookUrl()).isEqualTo(url);
    }

    @Test
    @DisplayName("POST channel=teams 缺 webhookUrl → 400，且不得新增任何一筆設定")
    void createTeamsConfigWithoutUrlIsRejected() throws Exception {
        String templateId = givenTemplate("T32-missing-url");
        long rowsBefore = configRepo.count();

        var res = post("/api/admin/notify-configs",
                teamsBody("leave-approval", uniqueEvent("task_assigned"), templateId, "null"));

        assertThat(res.statusCode()).isEqualTo(400);
        assertThat(res.body())
                .as("訊息要說清楚是哪個參數，否則呼叫端只會看到 400（#73）")
                .contains("webhookUrl");
        assertThat(configRepo.count())
                .as("被拒的建立不得留下任何一列 —— 回 400 卻仍寫入，比沒有守衛更難察覺")
                .isEqualTo(rowsBefore);
    }

    @Test
    @DisplayName("🔴 POST channel=teams 帶 policy 拒絕的 URL（127.0.0.1）→ 400 帶原因，且不寫入")
    void createTeamsConfigWithRejectedUrlIsRejected() throws Exception {
        String templateId = givenTemplate("T32-rejected-url");
        long rowsBefore = configRepo.count();

        var res = post("/api/admin/notify-configs",
                teamsBody("leave-approval", uniqueEvent("task_assigned"), templateId,
                        "\"http://127.0.0.1:" + SERVLET_PORT + "/mock/test-external-api/teams-admin-bad\""));

        assertThat(res.statusCode())
                .as("這個 URL 是伺服器會主動 POST 的目標；寫入端是 SSRF 的第一道閘門")
                .isEqualTo(400);
        assertThat(res.body())
                .contains("webhookUrl")
                .contains("loopback");
        assertThat(configRepo.count()).isEqualTo(rowsBefore);
    }

    // ── admin 驗證：PUT ─────────────────────────────────────────────

    @Test
    @DisplayName("PUT channel=teams 把 webhookUrl 改成空白 → 400，原值一個欄位都沒被改")
    void updateTeamsConfigWithBlankUrlIsRejected() throws Exception {
        String templateId = givenTemplate("T32-update-blank");
        String key = "leave-approval";
        String eventType = uniqueEvent("task_assigned");
        String url = sinkUrl("teams-admin-put");
        String id = givenTeamsConfig(key, eventType, templateId, url);

        var res = put("/api/admin/notify-configs/" + id,
                teamsBody(key, eventType, templateId, "\"   \""));

        assertThat(res.statusCode()).isEqualTo(400);
        assertThat(res.body()).contains("webhookUrl");
        assertThat(configRepo.findById(id).orElseThrow().getWebhookUrl())
                .as("被拒的修改不得留下任何副作用 —— 特別是目標被清空")
                .isEqualTo(url);
    }

    @Test
    @DisplayName("🔴 PUT channel=teams 改成 policy 拒絕的 URL → 400，原值不變")
    void updateTeamsConfigWithRejectedUrlIsRejected() throws Exception {
        String templateId = givenTemplate("T32-update-rejected");
        String key = "leave-approval";
        String eventType = uniqueEvent("task_assigned");
        String url = sinkUrl("teams-admin-put2");
        String id = givenTeamsConfig(key, eventType, templateId, url);

        var res = put("/api/admin/notify-configs/" + id,
                teamsBody(key, eventType, templateId,
                        "\"http://127.0.0.1:" + SERVLET_PORT + "/mock/test-external-api/teams-admin-bad2\""));

        assertThat(res.statusCode())
                .as("只在 create 檢查的話，這裡就是繞過寫入端閘門的路徑")
                .isEqualTo(400);
        assertThat(configRepo.findById(id).orElseThrow().getWebhookUrl()).isEqualTo(url);
    }

    @Test
    @DisplayName("email 設定帶 webhookUrl → 200 且原樣存下（其他 channel 可空、存了不使用）")
    void emailConfigMayCarryUnusedWebhookUrl() throws Exception {
        String templateId = givenTemplate("T32-email-url");
        String url = sinkUrl("teams-email-unused");

        var res = post("/api/admin/notify-configs",
                "{\"processDefinitionKey\":\"leave-approval\","
                        + "\"eventType\":\"" + uniqueEvent("task_assigned") + "\","
                        + "\"channel\":\"email\","
                        + "\"webhookUrl\":\"" + url + "\","
                        + "\"templateId\":\"" + templateId + "\","
                        + "\"enabled\":true}");

        assertThat(res.statusCode())
                .as("webhookUrl 的必填與政策檢查只屬於 channel=teams")
                .isEqualTo(200);
        String id = res.body().replaceAll(".*\"id\":\"([^\"]*)\".*", "$1");
        createdConfigs.add(id);
        assertThat(configRepo.findById(id).orElseThrow().getWebhookUrl()).isEqualTo(url);
    }

    // ── 讀取端遮蔽（#32 收尾）───────────────────────────────────────

    @Test
    @DisplayName("GET notify-configs 遮蔽 webhookUrl：回 ***、不回 bearer token 全文")
    void listConfigsMasksWebhookUrl() throws Exception {
        String templateId = givenTemplate("T32-mask-list");
        String url = sinkUrl("teams-mask-list-" + UUID.randomUUID());
        String id = givenTeamsConfig("leave-approval", uniqueEvent("task_assigned"), templateId, url);

        var res = get("/api/admin/notify-configs?processDefinitionKey=leave-approval");

        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body())
                .as("讀取端不得回傳 bearer token 全文（同 apiKey／callbackSecret 的遮蔽慣例）")
                .doesNotContain(url)
                .contains("\"webhookUrl\":\"***\"");
        assertThat(res.body())
                .as("遮蔽的是值不是整列 —— 管理端仍看得到這筆設定")
                .contains(id);
    }

    @Test
    @DisplayName("PUT 帶回遮蔽值 *** → 視為不改：DB 原值不變、回應仍遮蔽")
    void updateWithMaskedUrlKeepsExisting() throws Exception {
        String templateId = givenTemplate("T32-mask-put");
        String key = "leave-approval";
        String eventType = uniqueEvent("task_assigned");
        String url = sinkUrl("teams-mask-put-" + UUID.randomUUID());
        String id = givenTeamsConfig(key, eventType, templateId, url);

        var res = put("/api/admin/notify-configs/" + id,
                teamsBody(key, eventType, templateId, "\"***\""));

        assertThat(res.statusCode())
                .as("*** 是「不改」，不是不合法 URL；回 " + res.body())
                .isEqualTo(200);
        assertThat(configRepo.findById(id).orElseThrow().getWebhookUrl())
                .as("round-trip 不得把遮蔽字串寫成 URL，也不得把原值清掉")
                .isEqualTo(url);
        assertThat(res.body()).doesNotContain(url).contains("\"webhookUrl\":\"***\"");
    }

    @Test
    @DisplayName("PUT 換新 URL → DB 更新，但回應仍只回遮蔽值")
    void updateWithNewUrlReturnsMasked() throws Exception {
        String templateId = givenTemplate("T32-mask-put2");
        String key = "leave-approval";
        String eventType = uniqueEvent("task_assigned");
        String id = givenTeamsConfig(key, eventType, templateId,
                sinkUrl("teams-mask-old-" + UUID.randomUUID()));
        String newUrl = sinkUrl("teams-mask-new-" + UUID.randomUUID());

        var res = put("/api/admin/notify-configs/" + id,
                teamsBody(key, eventType, templateId, "\"" + newUrl + "\""));

        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(configRepo.findById(id).orElseThrow().getWebhookUrl()).isEqualTo(newUrl);
        assertThat(res.body()).doesNotContain(newUrl).contains("\"webhookUrl\":\"***\"");
    }

    // ── 端到端：真的經過 RabbitMQ 打到 sink ─────────────────────────

    @Test
    @DisplayName("#32 端到端：teams 設定 → bpm.notify.queue → POST 真的抵達 sink，body 是渲染後文案")
    void teamsConfigPushReachesSinkEndToEnd() throws Exception {
        String templateId = givenTemplate("T32-e2e");
        String key = "teams-e2e-" + UUID.randomUUID().toString().substring(0, 8);
        String eventType = "task_assigned";
        givenTeamsConfig(key, eventType, templateId, sinkUrl("teams-e2e"));

        // ⚠️ 隔離：前一批整合測試的通知在測試環境寄不出去（mail port 65000），
        // listener 是單執行緒，每則要重試 1+2 次（1s／2s backoff）才進 DLQ；
        // 而 prefetch 讓這些訊息以「未 ack」狀態卡在 client 端，purgeQueue
        // 清不掉（完整 notify 套件實測：本測試的訊息被推到 60 秒外）。
        // 停掉 container 會讓未 ack 的訊息回到 queue，此時才能清空；
        // 與 DlqAlertReplayTest 的 pauseListener + drain 同一條路徑。
        AbstractMessageListenerContainer notifyContainer = notifyContainer();
        notifyContainer.stop();
        try {
            amqpAdmin.purgeQueue("bpm.notify.queue");
        } finally {
            // 無論清空成功與否都必須恢復，否則後續測試沒有 consumer。
            notifyContainer.start();
        }

        Map<String, Object> msg = new HashMap<>();
        msg.put("event", eventType);
        msg.put("processDefinitionKey", key);
        msg.put("taskName", "主管審核");
        msg.put("assignee", "mgr001");
        msg.put("initiator", "user001");
        rabbitTemplate.convertAndSend("bpm.exchange", "bpm.notify.task", msg);

        List<ExternalApiTestSink.Received> received = awaitReceived("teams-e2e");
        assertThat(received)
                .as("通知的失敗型態是『什麼都沒送』；必須從接收端確認")
                .hasSize(1);
        assertThat(received.get(0).method()).isEqualTo("POST");
        assertThat(received.get(0).body())
                .as("模板渲染後的 subject／body 變成 Teams 的 {\"text\": ...}")
                .isEqualTo("{\"text\":\"[E2E] 主管審核\\n申請人 user001\"}");
    }

    /** 找出消費 {@code bpm.notify.queue} 的 listener container。 */
    private AbstractMessageListenerContainer notifyContainer() {
        return listenerRegistry.getListenerContainers().stream()
                .filter(AbstractMessageListenerContainer.class::isInstance)
                .map(AbstractMessageListenerContainer.class::cast)
                .filter(c -> List.of(c.getQueueNames()).contains("bpm.notify.queue"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "找不到 bpm.notify.queue 的 listener container："
                                + listenerRegistry.getListenerContainerIds()));
    }

    /** 從另一端等 POST 抵達；逾時回目前結果（讓斷言給出清楚的失敗訊息）。 */
    private static List<ExternalApiTestSink.Received> awaitReceived(String name)
            throws InterruptedException {
        // 60s：purge 之後正常只要數十毫秒；留大是因為 listener 可能正在處理
        // 一則 in-memory retry（1s／2s backoff），而 CI 上容器較慢。
        long deadline = System.currentTimeMillis() + 60_000;
        List<ExternalApiTestSink.Received> received = List.of();
        while (System.currentTimeMillis() < deadline) {
            received = ExternalApiTestSink.receivedTo(name);
            if (!received.isEmpty()) return received;
            Thread.sleep(100);
        }
        return received;
    }

    /** ⚠️ 必須是 localhost：allowed-hosts 比對的是 URL 裡的字面 host。 */
    private static String sinkUrl(String name) {
        return "http://localhost:" + SERVLET_PORT + "/mock/test-external-api/" + name;
    }
}
