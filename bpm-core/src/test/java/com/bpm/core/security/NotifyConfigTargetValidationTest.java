package com.bpm.core.security;

import com.bpm.core.model.NotifyConfig;
import com.bpm.core.repository.NotifyConfigRepository;
import com.bpm.core.repository.NotifyTemplateRepository;
import com.bpm.core.support.IntegrationTestBase;
import com.bpm.core.support.TestGatewayMockMvcCustomizer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #84：{@code PUT /api/admin/notify-configs/{id}} 的 {@code templateId} 守衛。
 *
 * <h2>缺陷（修補前的實際行為）</h2>
 *
 * <p>{@code NotifyAdminController.updateConfig} 拿到 {@code findById} 之後
 * <b>完全沒有驗證 templateId</b>，而同一個 controller 的
 * {@code deleteTemplate} 上方卻寫著「P1-13 只在 create/update 擋住了錯誤的
 * templateId」—— 註解描述的是一個不存在的行為。
 * 修補前實測：{@code PUT} 帶不存在的 templateId 回 <b>200</b> 且資料真的被寫進
 * 資料庫；同一個值走 {@code POST} 卻被擋成 <b>400</b>。
 *
 * <p>後果是「設定被靜默忽略」而不是「通知永久遺失」：{@code EmailConsumer}
 * 已在 P1-13 補上消費端防護（記 WARN、改用預設模板）。但危害並沒有因此
 * 變小 —— 沒有例外、沒有錯誤訊息，通知照常寄出，只有伺服器日誌一行 WARN，
 * 而後台顯示「設定成功」、稽核還記下 {@code after.templateId = <不存在的 id>}。
 * 通知設定是一條<b>隱藏簽核活動</b>的路徑（見該 controller 的類別註解），
 * 模板被靜默忽略等同於簽核活動被悄悄降級。
 *
 * <h2>⚠️ 為什麼狀態碼走真實 HTTP 而不是 MockMvc</h2>
 *
 * <p>{@code requireExistingTemplate} 與 {@code updateConfig} 拋的是
 * {@code ResponseStatusException}，容器會以 <b>ERROR dispatch</b> 轉到
 * {@code /error} 組回應，而線上的狀態碼正是那條路徑決定的
 * （{@code SecurityConfig} 的 {@code dispatcherTypeMatchers(ERROR).permitAll()}）。
 * MockMvc <b>不做 error dispatch</b>，所以用 MockMvc 寫，那條規則壞掉時
 * 測試照樣全綠 —— 見 {@link ErrorDispatchTest}。
 * 本專案 {@code security} 套件裡所有狀態碼斷言一律走真實 HTTP，本類別沿用。
 *
 * <h2>每一條都同時斷言「資料真的沒變」與「稽核真的沒寫」</h2>
 *
 * <p>只斷言狀態碼是不夠的。這個守衛最危險的失敗形狀不是「回 200」，
 * 而是「回 400 卻已經把 entity 改掉了」—— 那會讓只斷言狀態碼的測試以為
 * 守衛有效。因此每一條拒絕測試都另外驗證：
 * <ul>
 *   <li>被 PUT 的那一筆 <b>所有欄位</b>（{@code templateId}／
 *       {@code processDefinitionKey}／{@code eventType}／{@code channel}／
 *       {@code enabled}）都與改動前逐字相同；</li>
 *   <li>{@code bpm_notify_config} 的總筆數沒有變；</li>
 *   <li>稽核庫裡 <b>沒有</b> 任何 {@code CONFIG_CHANGE/update} 指向那個
 *       {@code targetId} —— 寫一筆「update」代表「這個變更發生了」，
 *       而實際上什麼都沒發生。</li>
 * </ul>
 *
 * <h2>fixture 是真的（不是空斷言）</h2>
 *
 * <p>模板與 config 都用<b>真實 HTTP 走完 controller</b> 建立，而不是直接
 * 呼叫 repository。理由：新守衛就擋在 {@code createConfig} 裡，若改用
 * repository 造 fixture，測試可能因為 fixture 本身不合法而提前失敗，
 * 讓後面的斷言全部變成「無論缺陷在不在都通過」。
 * 建立成功（{@code 200} ＋ 取得 id）本身就是「請求真的送達 controller」的證明。
 *
 * <p>而且 {@link #givenConfig(String, String, boolean)} 與
 * {@link #givenTemplate(String)} 都會在建立完成後<b>清空稽核</b> ——
 * 這樣「稽核裡沒有紀錄」這個斷言才是在說「<b>被測的那個請求</b>沒寫稽核」，
 * 而不是「整個測試沒寫稽核」。fixture 自己產生的 create 紀錄否則會讓
 * 這個斷言永遠失敗（而那正是本檔第一版的 bug）。
 */
class NotifyConfigTargetValidationTest extends IntegrationTestBase {

    /** 持有通配權限 {@code *} → {@code ROLE_ADMIN}；{@code /api/admin/**} 需要它。 */
    private static final String ADMIN = "admin001";

    @Autowired
    private NotifyConfigRepository configRepo;

    @Autowired
    private NotifyTemplateRepository templateRepo;

    private final HttpClient http = HttpClient.newHttpClient();

    private final List<String> createdTemplates = new ArrayList<>();
    private final List<String> createdConfigs = new ArrayList<>();

    @BeforeEach
    void clean() {
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

    // ── HTTP 小工具（走真實 HTTP，見類別註解）────────────────────────

    private HttpResponse<String> send(String method, String path, String userId, String body)
            throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + SERVLET_PORT + path))
                .header("X-Gateway-Secret", TestGatewayMockMvcCustomizer.GATEWAY_SECRET)
                .header("X-User-Id", userId)
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return send("POST", path, ADMIN, body);
    }

    private HttpResponse<String> put(String path, String body) throws Exception {
        return send("PUT", path, ADMIN, body);
    }

    // ── fixture：全部走真實 HTTP，確保真的走完 controller ──────────────

    /** 走 {@code POST /api/admin/notify-templates} 建立模板，回傳 id。 */
    private String givenTemplate(String name) throws Exception {
        var res = post("/api/admin/notify-templates",
                "{\"name\":\"" + name + "\",\"channel\":\"email\","
                        + "\"subjectTemplate\":\"" + name + " subject\","
                        + "\"bodyTemplate\":\"" + name + " body\"}");
        assertThat(res.statusCode())
                .as("前置條件：模板必須建立成功，否則後面的守衛斷言全部無意義")
                .isEqualTo(200);
        String id = res.body().replaceAll(".*\"id\":\"([^\"]*)\".*", "$1");
        assertThat(id).isNotBlank();
        createdTemplates.add(id);
        // 見類別註解：清掉 fixture 自己產生的稽核，之後的斷言才只關於被測請求。
        truncateAuditLog();
        return id;
    }

    /**
     * 走 {@code POST /api/admin/notify-configs} 建立設定，回傳 id。
     *
     * <p>{@code eventType} 帶隨機後綴是為了避開唯一約束
     * {@code (processDefinitionKey, eventType, channel)} ——
     * 測試共用同一個資料庫，固定值會撞到其他測試留下的資料
     * （撞到時會是 500，一個與本測試無關的失敗）。
     */
    private String givenConfig(String processDefinitionKey, String templateId, boolean enabled)
            throws Exception {
        String eventType = "task_assigned-" + UUID.randomUUID().toString().substring(0, 8);
        var res = post("/api/admin/notify-configs",
                "{\"processDefinitionKey\":\"" + processDefinitionKey + "\","
                        + "\"eventType\":\"" + eventType + "\","
                        + "\"channel\":\"email\","
                        + "\"templateId\":\"" + templateId + "\","
                        + "\"enabled\":" + enabled + "}");
        assertThat(res.statusCode())
                .as("前置條件：設定必須建立成功（走完 createConfig 的守衛），實際回 " + res.body())
                .isEqualTo(200);
        String id = res.body().replaceAll(".*\"id\":\"([^\"]*)\".*", "$1");
        assertThat(id).isNotBlank();
        createdConfigs.add(id);
        truncateAuditLog();
        return id;
    }

    /** 攻擊者想寫進去的內容。每個欄位都刻意與 fixture 不同。 */
    private record Tamper(String processDefinitionKey, String eventType, String channel,
                          String templateIdJson) {
        String body() {
            return "{\"processDefinitionKey\":\"" + processDefinitionKey + "\","
                    + "\"eventType\":\"" + eventType + "\","
                    + "\"channel\":\"" + channel + "\","
                    + "\"templateId\":" + templateIdJson + ","
                    + "\"enabled\":false}";
        }
    }

    /**
     * 每次呼叫都給不同的值。
     *
     * <p>除了避開唯一約束（撞到時資料庫會丟 500，讓負向控制組的失敗原因
     * 變得難以解讀），這也保證「被擋下來的請求確實沒寫入」和
     * 「寫入被擋下來了」不會互相干擾。
     */
    private static Tamper tamper(String templateIdJson) {
        String t = UUID.randomUUID().toString().substring(0, 8);
        return new Tamper("tamper-key-" + t, "task_completed-" + t, "teams-" + t, templateIdJson);
    }

    // ── 資料與稽核的驗證小工具 ──────────────────────────────────────

    /**
     * 設定的完整狀態。
     *
     * <p>用 record 而不是逐欄斷言，是為了讓「完全沒被改」這個主張可以用
     * 一次 equals 完成 —— 少寫一個欄位就是少驗一個欄位，
     * 而 record 的 {@code toString()} 會把新舊兩個狀態一起印在失敗訊息裡。
     */
    private record ConfigState(String processDefinitionKey, String eventType, String channel,
                               String templateId, Boolean enabled) {
    }

    private ConfigState stateOf(String configId) {
        NotifyConfig c = configRepo.findById(configId).orElseThrow(
                () -> new AssertionError("設定 " + configId + " 不見了 —— 測試前提失效"));
        return new ConfigState(c.getProcessDefinitionKey(), c.getEventType(), c.getChannel(),
                c.getTemplateId(), c.getEnabled());
    }

    /** 稽核庫裡 targetId 指向這個設定的 CONFIG_CHANGE 紀錄。 */
    private static List<String> configChangeDetailsOf(String targetId) {
        var out = new ArrayList<String>();
        withAuditConnection(c -> {
            try (var ps = c.prepareStatement(
                    "SELECT detail FROM bpm_audit_log WHERE operation_type = 'CONFIG_CHANGE' "
                            + "AND detail LIKE ? ORDER BY id")) {
                ps.setString(1, "%\"" + targetId + "\"%");
                var rs = ps.executeQuery();
                while (rs.next()) out.add(rs.getString(1));
            }
        });
        return out;
    }

    /**
     * 稽核掛在業務交易的 {@code beforeCommit}（見 {@code AuditEventPublisher}），
     * 所以 HTTP 回應回來時那一列已經 commit。這個短暫停頓只是為了
     * 讓「理論上非同步」的假設也不會讓「不得留下紀錄」這個斷言變成空斷言 ——
     * 多等只會讓斷言更嚴格，不會更鬆。
     */
    private static List<String> settledConfigChangeOf(String targetId) throws InterruptedException {
        Thread.sleep(300);
        return configChangeDetailsOf(targetId);
    }

    /** 等到出現至少一筆（稽核寫入容許短暫延遲）。 */
    private static List<String> awaitConfigChangeOf(String targetId) throws InterruptedException {
        List<String> found = List.of();
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline && found.isEmpty()) {
            found = configChangeDetailsOf(targetId);
            if (found.isEmpty()) Thread.sleep(100);
        }
        return found;
    }

    // ── ① POST：不存在的 templateId ────────────────────────────────

    @Test
    @DisplayName("#84：POST 帶不存在的 templateId → 400，且不得新增任何一筆設定")
    void createWithUnknownTemplateIsRejected() throws Exception {
        long rowsBefore = configRepo.count();
        String ghost = "no-such-template-" + UUID.randomUUID();

        var res = post("/api/admin/notify-configs",
                "{\"processDefinitionKey\":\"leave-approval\","
                        + "\"eventType\":\"task_assigned-post-" + UUID.randomUUID().toString().substring(0, 8) + "\","
                        + "\"channel\":\"email\",\"templateId\":\"" + ghost + "\",\"enabled\":true}");

        assertThat(res.statusCode())
                .as("create 端原本就有這道檢查（本次只是抽成共用方法），這條鎖住的是"
                        + "「重構沒有讓 create 退化」，不是缺陷本身的敏感點")
                .isEqualTo(400);
        assertThat(configRepo.count())
                .as("被拒的建立不得留下任何一列 —— 回 400 卻仍寫入，比沒有守衛更難察覺")
                .isEqualTo(rowsBefore);
        assertThat(configRepo.findByTemplateId(ghost))
                .as("資料庫不得有指向不存在模板的設定")
                .isEmpty();
    }

    // ── ② PUT：不存在的 templateId（缺陷的原始案例）─────────────────

    @Test
    @DisplayName("#84：PUT 帶不存在的 templateId → 400，且既有的設定一個欄位都沒被改")
    void updateWithUnknownTemplateIsRejectedAndChangesNothing() throws Exception {
        String templateId = givenTemplate("T84-unknown-target");
        String configId = givenConfig("leave-approval", templateId, true);
        ConfigState before = stateOf(configId);

        String ghost = "ghost-template-" + UUID.randomUUID();
        Tamper t = tamper("\"" + ghost + "\"");
        var res = put("/api/admin/notify-configs/" + configId, t.body());

        assertThat(res.statusCode())
                .as("缺陷期間這裡回 200，資料庫真的被寫成那個不存在的 templateId")
                .isEqualTo(400);
        assertThat(res.body())
                .as("訊息要說清楚是哪個參數、哪個值，否則呼叫端只會看到 400（#73）")
                .contains("templateId").contains(ghost);

        assertThat(stateOf(configId))
                .as("被拒的修改不得留下任何副作用 —— 特別是 enabled 被關掉、"
                        + "processDefinitionKey 被改掉（那會讓這筆設定轉到別的流程上）")
                .isEqualTo(before);
        assertThat(configRepo.findByTemplateId(ghost))
                .as("資料庫不得出現指向不存在模板的設定").isEmpty();
        assertThat(settledConfigChangeOf(configId))
                .as("被擋下的請求不得留下 CONFIG_CHANGE —— 一筆 update 代表「這個變更發生了」，"
                        + "而實際上什麼都沒發生")
                .isEmpty();
    }

    // ── ③ PUT：不存在的 id ────────────────────────────────────────

    @Test
    @DisplayName("#84：PUT 不存在的 id → 404（不是 500、不是靜默 no-op）")
    void updateOfUnknownIdIsNotFound() throws Exception {
        // 前置：建立一筆合法設定，證明端點與 fixture 本身都正常。
        // （若 fixture 壞掉，這條會以 400 通過，成為空斷言。）
        String templateId = givenTemplate("T84-unknown-id");
        givenConfig("leave-approval", templateId, true);
        long rowsBefore = configRepo.count();

        var res = put("/api/admin/notify-configs/no-such-config-id",
                tamper("\"" + templateId + "\"").body());

        assertThat(res.statusCode())
                .as("orElseThrow(NOT_FOUND) 是這條路徑原本就有的行為；這裡鎖住它，"
                        + "避免有人為了統一錯誤處理把它改回 orElseThrow()（→ 500）")
                .isEqualTo(404);
        assertThat(configRepo.count()).isEqualTo(rowsBefore);
        assertThat(settledConfigChangeOf("no-such-config-id"))
                .as("不存在的目標不得留下稽核").isEmpty();
    }

    // ── ④ PUT：有效的 templateId（防止「一律擋掉」的空通過）──────────

    @Test
    @DisplayName("#84：PUT 帶有效的 templateId → 200，且真的改到、稽核也真的寫了")
    void updateWithValidTemplateSucceeds() throws Exception {
        // 這一條與 ②③⑤ 必須成組存在。少了它，守衛可以靠
        // 「把所有人都擋掉」達成 —— 那樣 ② 與 ⑤ 的 400 都會照樣通過，
        // 而管理功能已經被打死。
        String templateId = givenTemplate("T84-valid-target");
        String configId = givenConfig("leave-approval", templateId, true);
        ConfigState before = stateOf(configId);

        String otherTemplateId = givenTemplate("T84-valid-target-2");
        Tamper t = tamper("\"" + otherTemplateId + "\"");
        var res = put("/api/admin/notify-configs/" + configId, t.body());

        assertThat(res.statusCode())
                .as("守衛不得把合法的修改也擋掉 —— 換模板、關通知都是正常的管理操作")
                .isEqualTo(200);

        NotifyConfig after = configRepo.findById(configId).orElseThrow();
        assertThat(after.getTemplateId()).isEqualTo(otherTemplateId);
        assertThat(after.getProcessDefinitionKey()).isEqualTo(t.processDefinitionKey());
        assertThat(after.getEventType()).isEqualTo(t.eventType());
        assertThat(after.getChannel()).isEqualTo(t.channel());
        assertThat(after.getEnabled())
                .as("enabled 從 true 變 false 是「讓相關人員不再收到通知」，必須真的改到")
                .isFalse();
        assertThat(stateOf(configId)).isNotEqualTo(before);

        var audits = awaitConfigChangeOf(configId);
        assertThat(audits)
                .as("成功的變更必須留下前後值 —— 稽核是這個缺陷真正傷害到的東西")
                .hasSize(1);
        assertThat(audits.get(0))
                .contains("\"action\":\"update\"")
                .contains("before.templateId").contains("after.templateId")
                .contains(templateId).contains(otherTemplateId)
                .contains("before.enabled").contains("after.enabled");
    }

    // ── ⑤ PUT：null / blank 的 templateId ───────────────────────────

    @Test
    @DisplayName("#84：PUT 帶 null、空字串或全空白的 templateId → 400，且設定完全沒被改")
    void updateWithBlankTemplateIdIsRejectedAndChangesNothing() throws Exception {
        String templateId = givenTemplate("T84-blank-target");

        // null／""／空白字串是三個不同的輸入，必須各測一次：
        // NotifyConfig.templateId 沒有 nullable=false，三者都存得進去，
        // 而只測其中一個，另外兩個仍可能漏。
        for (String templateIdJson : new String[]{"null", "\"\"", "\"   \""}) {
            String configId = givenConfig("leave-approval", templateId, true);
            ConfigState before = stateOf(configId);

            Tamper t = tamper(templateIdJson);
            var res = put("/api/admin/notify-configs/" + configId, t.body());

            assertThat(res.statusCode())
                    .as("templateId = " + templateIdJson + " 必須被擋 —— "
                            + "EmailConsumer 取不到模板時只能退回預設模板，"
                            + "管理員設定的模板被靜默忽略")
                    .isEqualTo(400);
            assertThat(stateOf(configId))
                    .as("templateId = " + templateIdJson + " 被拒時，其他欄位也不得被改")
                    .isEqualTo(before);
            assertThat(settledConfigChangeOf(configId))
                    .as("templateId = " + templateIdJson + " 被拒時不得留下 CONFIG_CHANGE")
                    .isEmpty();
        }
    }

    // ── ⑥ 對照組：守衛不得被「其他東西」擋掉 ─────────────────────────

    @Test
    @DisplayName("#84：非管理員仍然 403（補上守衛不得放寬 /api/admin/** 的授權）")
    void nonAdminIsStillForbidden() throws Exception {
        String templateId = givenTemplate("T84-authz");
        String configId = givenConfig("leave-approval", templateId, true);
        ConfigState before = stateOf(configId);

        var res = send("PUT", "/api/admin/notify-configs/" + configId, "user001",
                tamper("\"" + templateId + "\"").body());

        assertThat(res.statusCode())
                .as("通知設定是隱藏簽核活動的路徑，只能由管理員變更（SecurityConfig 的"
                        + "requestMatchers(\"/api/admin/**\").hasRole(ADMIN)）")
                .isEqualTo(403);
        assertThat(stateOf(configId)).isEqualTo(before);
        assertThat(settledConfigChangeOf(configId))
                .as("授權層擋下的請求不會進 controller，因此不會有稽核").isEmpty();
    }
}
