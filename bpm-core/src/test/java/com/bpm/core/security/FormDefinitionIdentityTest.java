package com.bpm.core.security;

import com.bpm.core.form.model.FormDefinition;
import com.bpm.core.form.repository.FormDefinitionRepository;
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
 * #81：{@code POST /api/forms} 的 {@code createdBy} 冒用，以及兩個建立端點的一致性。
 *
 * <h2>缺陷（修補前的實際行為）</h2>
 *
 * <p>{@code FormDefinition.createdBy} <b>有 setter 且刻意沒有</b>標
 * {@code @JsonProperty(READ_ONLY)}（與 {@code id} 不同），所以 Jackson 會把它
 * 反序列化；{@code FormService.create()} 又<b>完全不碰它</b> —— 只用
 * {@code @CallerId} 餵稽核，資料列那一欄則是「body 送什麼就是什麼」。
 *
 * <p>對照同一個 controller 的 {@code POST /{formKey}/revisions}：
 * {@code createNextDraft(formKey, userId)} <b>有</b> {@code setCreatedBy}。
 * 同一個概念、兩個端點、兩套規則 —— 而那種組合型式的差異比沒有檢查更難察覺，
 * 因為兩邊單獨看起來都合理。
 *
 * <h2>危害的邊界（比 #66／#72 窄，這點要說清楚）</h2>
 *
 * <p><b>稽核從來沒有被污染。</b>{@code audit("FORM_UPDATE", userId, …)} 傳的
 * 一直是 {@code @CallerId}，不是 {@code def.getCreatedBy()}。
 * 所以這個缺陷的影響面<b>只有資料列那一欄</b>：
 * 「這張審核表是誰做的」不可信，而 schema 是流程行為的定義
 * （spec §8.5：欄位 id == 流程變數名）——
 * 因此它牽動的是「誰改了審核規則」的歸屬，不是稽核紀錄的完整性。
 *
 * <h2>⚠️ 前端相容性：查證結果是<b>零風險</b></h2>
 *
 * <p>{@code POST /api/forms} 的唯一前端呼叫者是 {@code FormEditor.vue:42}：
 * <pre>
 *   const payload = { name: formName.value, formKey: formKey.value,
 *                     schemaJson: JSON.stringify(schema) }
 *   await createForm(payload)
 * </pre>
 * {@code formApi.createForm} 原樣轉發，{@code bpm-frontend/src} 全樹
 * <b>grep 不到 {@code createdBy}</b>。{@code scripts/seed-data.sh} 只 GET
 * {@code /api/forms/{formKey}}，{@code docs/README-testing.md} 的 curl 範例也沒有。
 *
 * <p>這與 #79 的 {@code CommentRequest.userId} 不同：那個欄位前端
 * <b>真的</b>在送 {@code 'current_user'}，加守衛會讓它全變 400。
 * 這裡前端不送，所以「server 決定」不會破壞任何現有呼叫。
 *
 * <h2>⚠️ 狀態碼走真實 HTTP</h2>
 *
 * <p>{@code ResponseStatusException} 會被容器以 <b>ERROR dispatch</b> 轉到
 * {@code /error} 組回應，而線上的狀態碼正是那條路徑決定的
 * （{@code SecurityConfig} 的 {@code dispatcherTypeMatchers(ERROR).permitAll()}）。
 * MockMvc <b>不做 error dispatch</b>（見 {@code ErrorDispatchTest}），
 * 用它寫的話這些測試在缺陷完全存在時會照樣全綠。
 *
 * <h2>非空性與稽核誠實性</h2>
 *
 * <ul>
 *   <li>每一條拒絕都配一條<b>同樣 payload、只有 {@code createdBy} 不同</b>的放行對照。</li>
 *   <li>拒絕時驗「該 formKey 一列都沒建立」與「既有資料列一個欄位都沒被改」。</li>
 *   <li>拒絕時<b>不得有 {@code FORM_UPDATE}</b> —— 那正是「宣稱建立、實際沒建立」，
 *       與 #66／#72／#85 的稽核誠實性政策相同。</li>
 *   <li>拒絕時<b>會</b>有一筆 {@code DATA_ACCESS / denied=true}
 *       （{@code requireSelf} 既有的拒絕留痕政策，本工項沒有改動它）——
 *       刻意一併釘住，避免後來有人「清理」時把這個訊號一起刪掉。</li>
 * </ul>
 *
 * <h2>負向控制組：整份還原缺陷後 9 條紅 7 條</h2>
 *
 * <p>還原方式是把 {@code FormDefinitionController.java} 整份換回 HEAD 的版本
 * （<b>不是</b>把那一行刪掉 —— 前兩輪的教訓是「還原缺陷的位置會影響結果」，
 * 有一次把 initiator 放回 deny-list 之前，12 條測試全綠等於沒驗到）。
 *
 * <p><b>紅的 7 條</b>：兩條冒用（狀態碼 200 而非 400）、
 * 稽核誠實性、既有資料列未被改、兩個建立端點一致、
 * 省略時寫入登入者、null／空白視同省略、守衛真的掛在路徑上。
 *
 * <p><b>綠的 2 條 —— 記下來比「測試會紅」更有資訊</b>：
 * <ul>
 *   <li>{@code matchingCreatedByIsAccepted}：送與自己相同的值在缺陷期間本來就放行。
 *       它防的是「修法把合理呼叫形狀打死」，不是防缺陷。</li>
 *   <li>{@code lackingFormDesignAuthorityIsStillForbidden}：403 來自
 *       {@code SecurityFilterChain}，在 controller 之前，與本缺陷無關。
 *       它防的是「未來有人把守衛往上移到 filter 層」的迴歸。</li>
 * </ul>
 *
 * <h2>⚠️ 負向控制組揭露的<b>後果比 backlog 描述的更嚴重</b></h2>
 *
 * <p>缺陷期間省略 {@code createdBy} 時，資料庫存的是 <b>{@code null}</b>
 * 而不是「呼叫者」—— 斷言印出來的是 {@code expected: "mgr001" but was: null}。
 * 也就是說這不只是「可冒用」，而是<b>正常呼叫下這欄根本是空的</b>：
 * 每一張經由 {@code POST /api/forms} 建立的審核表都沒有作者。
 * 這也讓 ④ 的「兩個端點一致」測試印出 {@code [null, "mgr001"]} ——
 * v1（POST）沒有作者、v2（revisions）有，正是 backlog 說的「兩個端點不一致」，
 * 而且不一致的方向是「其中一條<b>壞掉</b>」，不是「其中一條比較嚴格」。
 */
class FormDefinitionIdentityTest extends IntegrationTestBase {

    /**
     * 持有 {@code bpm:form:design}、<b>不是</b>管理員的身分。
     *
     * <p>刻意不選 admin001：2026-09-29 決定表單設計用權限碼
     * {@code bpm:form:design} 而非 {@code ROLE_ADMIN}，用權限碼持有者才證明
     * 新守衛擋的是「冒用」而不是「管理員以外的人不能設計表單」。
     * 授權矩陣本身由 {@code FormDesignAuthorizationTest} 涵蓋。
     */
    private static final String DESIGNER = "mgr001";

    /** 另一個持有 {@code bpm:form:design} 的身分 —— 冒用的目標。 */
    private static final String OTHER_DESIGNER = "dir002";

    /** 通配權限 {@code *} → {@code ROLE_ADMIN}。攻擊者想冒充的那個「看起來很有權威」的身分。 */
    private static final String ADMIN = "admin001";

    private static final String SCHEMA = "{\\\"fields\\\":[{\\\"id\\\":\\\"amount\\\"}]}";

    @Autowired
    private FormDefinitionRepository defRepo;

    private final HttpClient http = HttpClient.newHttpClient();

    /** 本測試建立的 formKey，測試結束後刪掉（測試共用同一組容器）。 */
    private final List<String> createdFormKeys = new ArrayList<>();

    @BeforeEach
    void cleanAudit() {
        truncateAuditLog();
    }

    @AfterEach
    void removeFixtures() {
        for (String key : createdFormKeys) {
            defRepo.findByFormKeyAndStatus(key, "draft").ifPresent(defRepo::delete);
            defRepo.findByFormKeyAndStatus(key, "published").ifPresent(defRepo::delete);
        }
        createdFormKeys.clear();
    }

    // ── HTTP 小工具（走真實 HTTP，見類別註解）────────────────────────

    private HttpResponse<String> send(String method, String path, String userId, String body)
            throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + SERVLET_PORT + path))
                .header("X-Gateway-Secret", TestGatewayMockMvcCustomizer.GATEWAY_SECRET)
                .header("X-User-Id", userId)
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String userId, String body) throws Exception {
        return send("POST", path, userId, body);
    }

    // ── fixture ────────────────────────────────────────────────────

    /**
     * formKey 帶隨機後綴是為了避開 {@code (formKey, version)} 唯一約束 ——
     * 測試共用同一個資料庫，固定字串會互相撞。
     */
    private static String uniqueFormKey(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** 建立 payload；{@code createdByJson} 為空字串代表<b>省略該欄位</b>。 */
    private static String createBody(String formKey, String name, String schemaJson,
                                     String createdByJson) {
        return "{\"formKey\":\"" + formKey + "\",\"name\":\"" + name + "\","
                + "\"schemaJson\":\"" + schemaJson + "\""
                + (createdByJson.isEmpty() ? "" : "," + createdByJson) + "}";
    }

    private static String createdByField(String json) {
        var m = java.util.regex.Pattern.compile("\"createdBy\":\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    private static String idField(String json) {
        var m = java.util.regex.Pattern.compile("\"id\":\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    /** 直接查 form DB：不經 repository，因為斷言要證明「真的寫進去了」。 */
    private int rowCountOf(String formKey) {
        int[] rows = {-1};
        withFormConnection(c -> {
            try (var ps = c.prepareStatement(
                    "SELECT COUNT(*) FROM bpm_form_definition WHERE form_key = ?")) {
                ps.setString(1, formKey);
                var rs = ps.executeQuery();
                rs.next();
                rows[0] = rs.getInt(1);
            }
        });
        return rows[0];
    }

    private static List<String> createdByOfEveryRow(String formKey) {
        var out = new ArrayList<String>();
        withFormConnection(c -> {
            try (var ps = c.prepareStatement(
                    "SELECT created_by FROM bpm_form_definition WHERE form_key = ? ORDER BY version")) {
                ps.setString(1, formKey);
                var rs = ps.executeQuery();
                while (rs.next()) out.add(rs.getString(1));
            }
        });
        return out;
    }

    /** 稽核庫裡提到這個 formKey 的 FORM_UPDATE 紀錄（action=create）。 */
    private static List<String> formUpdateAuditsOf(String formKey) {
        var out = new ArrayList<String>();
        withAuditConnection(c -> {
            try (var ps = c.prepareStatement("SELECT operator_id, detail FROM bpm_audit_log "
                    + "WHERE operation_type = 'FORM_UPDATE' AND detail LIKE ? ORDER BY id")) {
                ps.setString(1, "%\"" + formKey + "\"%");
                var rs = ps.executeQuery();
                while (rs.next()) out.add(rs.getString(1) + " | " + rs.getString(2));
            }
        });
        return out;
    }

    /**
     * 稽核掛在交易的 beforeCommit，短暫停頓只是讓「不得留下紀錄」更嚴格。
     *
     * <p>400 的情境走的是 {@code @Transactional} 方法的中途拋例外 → 回滾，
     * 所以「掛在 beforeCommit 的稽核根本沒機會寫入」本來就是預期行為；
     * 這裡仍然停頓再查，是因為<b>守衛寫的是 {@code publishDetached}</b>
     * （同步寫、不跟隨交易），不能假設「回滾 = 沒有紀錄」。
     */
    private static List<String> settledFormUpdateAuditsOf(String formKey)
            throws InterruptedException {
        Thread.sleep(300);
        return formUpdateAuditsOf(formKey);
    }

    private static List<String> awaitFormUpdateAuditsOf(String formKey)
            throws InterruptedException {
        List<String> found = List.of();
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline && found.isEmpty()) {
            found = formUpdateAuditsOf(formKey);
            if (found.isEmpty()) Thread.sleep(100);
        }
        return found;
    }

    /** 拒絕嘗試的留痕（{@code requireSelf} 用 {@code publishDetached} 同步寫入）。 */
    private static List<String> deniedIdentityAttempts(String operatorId) {
        var out = new ArrayList<String>();
        withAuditConnection(c -> {
            try (var ps = c.prepareStatement("SELECT detail FROM bpm_audit_log "
                    + "WHERE operation_type = 'DATA_ACCESS' AND operator_id = ? "
                    + "AND detail LIKE '%identity mismatch%' ORDER BY id")) {
                ps.setString(1, operatorId);
                var rs = ps.executeQuery();
                while (rs.next()) out.add(rs.getString(1));
            }
        });
        return out;
    }

    // ── ① 冒用：明確 400，且什麼都沒發生 ────────────────────────────

    @Test
    @DisplayName("#81：POST /api/forms 帶冒用的 createdBy → 400，且不建立任何資料")
    void createCannotImpersonateCreatedBy() throws Exception {
        String formKey = uniqueFormKey("T81-impersonate");
        createdFormKeys.add(formKey);

        var res = post("/api/forms", DESIGNER,
                createBody(formKey, "冒用測試", SCHEMA,
                        "\"createdBy\":\"" + ADMIN + "\""));

        assertThat(res.statusCode())
                .as("缺陷期間這裡回 200，資料列的 createdBy 真的寫成 %s —— "
                        + "「這張審核表是誰做的」從此不可信", ADMIN)
                .isEqualTo(400);
        assertThat(res.body())
                .as("訊息要指名欄位與登入身分，否則呼叫端只會看到一個 400（#73）")
                .contains("createdBy").contains(DESIGNER);
        assertThat(rowCountOf(formKey))
                .as("被拒的請求不得留下任何資料列 —— 400 必須真的擋下寫入")
                .isZero();
    }

    @Test
    @DisplayName("#81：冒用被擋下時不得留下 FORM_UPDATE（稽核不得說謊）")
    void rejectedCreateLeavesNoAudit() throws Exception {
        String formKey = uniqueFormKey("T81-no-audit");
        createdFormKeys.add(formKey);

        assertThat(post("/api/forms", DESIGNER,
                createBody(formKey, "稽核誠實性", SCHEMA,
                        "\"createdBy\":\"" + OTHER_DESIGNER + "\"")).statusCode())
                .as("前置條件：冒用必須先被擋下").isEqualTo(400);

        assertThat(settledFormUpdateAuditsOf(formKey))
                .as("不得留下 FORM_UPDATE —— 那正是「稽核宣稱這張表單被建立了」"
                        + "而實際沒發生（#66／#72／#85 的稽核誠實性政策）")
                .isEmpty();

        // 對照：requireSelf 既有的拒絕留痕<b>必須</b>還在。這是「有人嘗試冒用」
        // 本身值得知道，與「變更發生了」是兩件不同的事 ——
        // 刻意釘住它，避免後來有人清理稽核時把這個訊號一併刪掉。
        assertThat(deniedIdentityAttempts(DESIGNER))
                .as("requireSelf 的拒絕留痕是既有政策，本工項沒有改動它")
                .isNotEmpty();
    }

    @Test
    @DisplayName("#81：冒用時既有資料列一個欄位都不得被改（守衛排在 service 之前）")
    void rejectedCreateLeavesExistingRowsUntouched() throws Exception {
        String formKey = uniqueFormKey("T81-existing");
        createdFormKeys.add(formKey);

        // 先由合法呼叫者建立一列，作為「原本的樣子」。
        assertThat(post("/api/forms", DESIGNER,
                createBody(formKey, "原本的名字", SCHEMA, "")).statusCode())
                .as("前置條件：省略 createdBy 時必須放行，否則下面的對照無意義")
                .isEqualTo(200);
        FormDefinition before = defRepo.findByFormKeyAndVersion(formKey, 1).orElseThrow();

        // 同一個 formKey 帶冒用的 createdBy 再送一次。
        // ⚠️ 這一次同時觸發兩件事：formKey 已存在（service 的檢查）與冒用（守衛）。
        // 沒有非空斷言的話，這條測試分不出 400 來自哪一個 ——
        // 而分不出來就等於沒驗到「守衛排在 service 之前」這件事。
        var res = post("/api/forms", DESIGNER,
                createBody(formKey, "被改掉的名字", "{\\\"fields\\\":[{\\\"id\\\":\\\"injected\\\"}]}",
                        "\"createdBy\":\"" + ADMIN + "\""));

        assertThat(res.statusCode()).isEqualTo(400);
        assertThat(res.body())
                .as("必須是身分冒用這一項錯誤，而不是 formKey 已存在 —— "
                        + "反過來會讓呼叫端以為換個 formKey 就能把冒用送出去")
                .contains("createdBy").doesNotContain("已存在");

        FormDefinition after = defRepo.findByFormKeyAndVersion(formKey, 1).orElseThrow();
        assertThat(after.getName())
                .as("既有資料列的名稱不得被改").isEqualTo(before.getName());
        assertThat(after.getSchemaJson())
                .as("既有資料列的 schemaJson 不得被改（欄位 id == 流程變數名）")
                .isEqualTo(before.getSchemaJson());
        assertThat(after.getCreatedBy())
                .as("既有資料列的 createdBy 不得被冒用請求改寫").isEqualTo(DESIGNER);
        assertThat(rowCountOf(formKey))
                .as("不得因為這次請求多出一列").isEqualTo(1);
    }

    // ── ② 放行對照：同樣 payload、只有身分欄位不同 ────────────────────

    @Test
    @DisplayName("#81：POST 省略 createdBy → 200，且建立者與稽核 operatorId 都是登入者")
    void createdByComesFromTheAuthenticatedCaller() throws Exception {
        String formKey = uniqueFormKey("T81-omitted");
        createdFormKeys.add(formKey);

        var res = post("/api/forms", DESIGNER, createBody(formKey, "省略", SCHEMA, ""));

        assertThat(res.statusCode())
                .as("省略 createdBy 時必須放行 —— 表單設計是這個平台的核心功能")
                .isEqualTo(200);
        assertThat(createdByField(res.body()))
                .as("省略時必須<b>寫入</b>登入者而不是留 null：欄位可為 null 會讓"
                        + "「這張審核表是誰做的」變成無解")
                .isEqualTo(DESIGNER);
        assertThat(createdByOfEveryRow(formKey))
                .as("資料庫裡的那一欄必須同樣是登入者（不只看回應）")
                .containsExactly(DESIGNER);

        var audits = awaitFormUpdateAuditsOf(formKey);
        assertThat(audits).as("成功的建立必須留下稽核").hasSize(1);
        assertThat(audits.get(0))
                .as("operatorId 要記「誰做的」。它本來就用 @CallerId，"
                        + "這條斷言是為了確保後來沒有人改成讀 createdBy")
                .startsWith(DESIGNER + " | ");
    }

    @Test
    @DisplayName("#81：帶與自己相同的 createdBy 視為省略，不得拒絕")
    void matchingCreatedByIsAccepted() throws Exception {
        // DocumentController.createdBy 的同型取捨：createdBy 是 JPA entity 欄位，
        // Jackson 反序列化後無法分辨「沒送」與「送了 null」；
        // 而送出與自己相同的身分並不構成冒用，拒絕它只會製造無意義的破壞。
        String formKey = uniqueFormKey("T81-matching");
        createdFormKeys.add(formKey);

        var res = post("/api/forms", DESIGNER,
                createBody(formKey, "自己", SCHEMA, "\"createdBy\":\"" + DESIGNER + "\""));

        assertThat(res.statusCode())
                .as("送出與自己相同的值不得被拒絕 —— 有一種合理的呼叫形狀就是"
                        + "前端把整份表單物件（含 createdBy）原樣送回")
                .isEqualTo(200);
        assertThat(createdByField(res.body())).isEqualTo(DESIGNER);
    }

    @Test
    @DisplayName("#81：createdBy 為 null 或全空白時視同省略，建立者仍是登入者")
    void nullOrBlankCreatedByIsTreatedAsOmitted() throws Exception {
        // requireSelf 的第三條規則：空白視同省略。空白不可能指向別人，
        // 拒絕它只製造無意義的破壞（與 DocumentController 的判斷一致）。
        String nullKey = uniqueFormKey("T81-null");
        String blankKey = uniqueFormKey("T81-blank");
        createdFormKeys.add(nullKey);
        createdFormKeys.add(blankKey);

        assertThat(post("/api/forms", DESIGNER,
                createBody(nullKey, "null", SCHEMA, "\"createdBy\":null")).statusCode())
                .isEqualTo(200);
        assertThat(post("/api/forms", DESIGNER,
                createBody(blankKey, "blank", SCHEMA, "\"createdBy\":\"   \"")).statusCode())
                .isEqualTo(200);

        assertThat(createdByOfEveryRow(nullKey)).containsExactly(DESIGNER);
        assertThat(createdByOfEveryRow(blankKey))
                .as("全空白必須被當成「沒送」而不是被寫成一串空白")
                .containsExactly(DESIGNER);
    }

    // ── ③ 兩個建立端點必須是同一條規則 ──────────────────────────────

    @Test
    @DisplayName("#81：POST 與 POST /{formKey}/revisions 的 createdBy 都是登入者")
    void bothCreateEndpointsAgreeOnCreatedBy() throws Exception {
        // 這條直接釘住 backlog 的核心主張「兩個端點不一致」。
        // 少了它，守衛只加在 POST 上時這份測試仍然全綠 ——
        // 而缺陷恰恰是兩邊形狀不同。
        String formKey = uniqueFormKey("T81-both");
        createdFormKeys.add(formKey);

        var created = post("/api/forms", DESIGNER, createBody(formKey, "兩端點", SCHEMA, ""));
        assertThat(created.statusCode()).isEqualTo(200);
        String firstId = idField(created.body());

        // 發布，否則 createNextDraft 會因為 draft 已存在而回 409。
        assertThat(send("POST", "/api/forms/" + firstId + "/publish", DESIGNER, null)
                .statusCode()).as("前置條件：先發布才能改版").isEqualTo(200);

        var revised = post("/api/forms/" + formKey + "/revisions", DESIGNER, null);
        assertThat(revised.statusCode())
                .as("前置條件：改版路徑必須放行 —— 它本來就有 setCreatedBy").isEqualTo(200);

        assertThat(createdByOfEveryRow(formKey))
                .as("v1 與 v2 的建立者必須都是登入者 —— 這就是「同一條規則」的證明")
                .containsExactly(DESIGNER, DESIGNER);
        assertThat(createdByField(created.body())).isEqualTo(DESIGNER);
        assertThat(createdByField(revised.body())).isEqualTo(DESIGNER);
    }

    // ── ④ 非空斷言：守衛必須真的掛在路徑上 ──────────────────────────

    @Test
    @DisplayName("#81：守衛掛在路徑上（同一個 body 換登入者就放行，400 不是路由造成的）")
    void theGuardIsActuallyOnThePath() throws Exception {
        // 沒有這條，上面那些 400 有可能來自路由、授權矩陣或 formKey 已存在，
        // 與 requireSelf 毫無關係 —— 而這正是「非空斷言」要防的情況。
        String blockedKey = uniqueFormKey("T81-nonempty-block");
        String allowedKey = uniqueFormKey("T81-nonempty-ok");
        createdFormKeys.add(blockedKey);
        createdFormKeys.add(allowedKey);

        String body = "{\"formKey\":\"%s\",\"name\":\"非空\",\"schemaJson\":\"%s\","
                + "\"createdBy\":\"%s\"}";
        // 冒用 ADMIN → 400
        assertThat(post("/api/forms", DESIGNER,
                body.formatted(blockedKey, SCHEMA, ADMIN)).statusCode())
                .isEqualTo(400);
        // 唯一差別：body 的 createdBy 改成「呼叫者自己」→ 200
        var ok = post("/api/forms", DESIGNER,
                body.formatted(allowedKey, SCHEMA, DESIGNER));
        assertThat(ok.statusCode())
                .as("同一個 payload 只換 createdBy 為自己的身分就必須放行 —— "
                        + "少了這條，守衛可以靠「擋掉所有人」達成空斷言")
                .isEqualTo(200);
        assertThat(createdByOfEveryRow(allowedKey)).containsExactly(DESIGNER);
    }

    @Test
    @DisplayName("#81：沒有 bpm:form:design 的人仍是 403（#77 的授權政策不得被這個工項改變）")
    void lackingFormDesignAuthorityIsStillForbidden() throws Exception {
        // 順序斷言：SecurityFilterChain 在 controller 之前，所以
        // 「沒有權限」必須先回 403，不能變成守衛的 400。
        // （user001 在權限中心 fixture 裡沒有任何權限碼。）
        String formKey = uniqueFormKey("T81-forbidden");
        createdFormKeys.add(formKey);

        var res = post("/api/forms", "user001", createBody(formKey, "沒權限", SCHEMA, ""));

        assertThat(res.statusCode())
                .as("授權矩陣必須維持在 URL 層 —— 這個工項只處理物件層的身分欄位")
                .isEqualTo(403);
        assertThat(rowCountOf(formKey))
                .as("被授權層擋下的請求同樣不得留下資料").isZero();
    }
}