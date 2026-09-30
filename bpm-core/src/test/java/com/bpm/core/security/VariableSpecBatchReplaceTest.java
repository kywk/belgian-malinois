package com.bpm.core.security;

import com.bpm.core.model.ProcessVariableSpec;
import com.bpm.core.repository.ProcessVariableSpecRepository;
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
 * #86：{@code POST /api/admin/process-definitions/{key}/variable-spec} 的
 * 「整批取代」在有既有資料時必定 500。
 *
 * <h2>缺陷（修補前的實際行為）</h2>
 *
 * <p>{@code batchSave} 先 {@code deleteByProcessDefinitionKey(key)} 再
 * {@code saveAll(specs)}。衍生刪除是「SELECT 出 entity → {@code em.remove()}」，
 * 而 {@code em.remove()} 只<b>排程</b>刪除；Hibernate 的 flush 順序固定是
 * <b>INSERT 在 DELETE 之前</b>，於是同一個 flush 裡每一筆 INSERT 都撞上
 * 尚未刪掉的同名舊列 →
 * {@code Violation of UNIQUE KEY constraint 'uk_bpm_process_variable_spec_key_name'} → 500。
 *
 * <p><b>線上實測（缺陷期間）：</b>同一個 key 連續呼叫兩次（內容相同），
 * 第一次 {@code 200}、第二次 <b>{@code 500}</b>。而
 * {@code ProcessVariableSpecAdmin.vue} 的「儲存」按鈕正是走這條路徑 ——
 * 管理頁第二次按儲存必定壞。
 *
 * <h2>⚠️ 觸發條件比「key 已有資料」窄：必須<b>有同名變數</b></h2>
 *
 * <p>INSERT 之所以失敗，是因為它要寫的 {@code (key, variableName)}
 * 還被舊列佔著。所以缺陷期間：改內容或原樣重存 → 500；
 * 整批換成別的名字 → 200；送空陣列 → 200。
 *
 * <p>這一點在負向控制組裡是實測出來的，值得記在這裡給下一個人：
 * 把缺陷放回去時，<b>紅的是 4 條</b>——
 * {@code resavingIdenticalContentSucceeds}、
 * {@code resavingWithDifferentContentReplacesCorrectly}、
 * {@code removedVariablesActuallyDisappear}、
 * {@code auditMatchesWhatActuallyHappened}，全部是「新舊有重疊」形狀。
 * 剩下 4 條（{@code otherKeysAreUntouched}、{@code emptyBatchClearsEverything}、
 * {@code nonAdminIsRejectedAndNothingChanges}、
 * {@code smuggledIdDoesNotOverwriteOtherRows}）在缺陷期間<b>是綠的</b>：
 * 它們防的是「修法把語意改壞」或「修法動到授權面」，不是缺陷本身。
 * 沒有這 4 條，一個「只刪掉重疊的列」之類的半吊子修法也能讓紅的 4 條全綠。
 *
 * <h2>為什麼這個缺陷特別難被測試抓到</h2>
 *
 * <p>它<b>不需要任何攻擊者</b>，也不需要特殊的權限或身分：管理員照正常步驟
 * 按兩次儲存就會踩到。而且症狀是 500，測試若只斷言「第一次成功」
 * 就完全看不出問題——必須真的<b>連續存兩次</b>。
 * 414 個測試沒有一個覆蓋重複寫入（見 backlog #86），
 * 這是「測試全綠但線上有洞」又一個例子。
 *
 * <h2>⚠️ 每條狀態碼斷言都必須同時驗「資料確實換掉了」</h2>
 *
 * <p>只驗 200 不足以證明修好了：一個「什麼都不做」的實作也會回 200。
 * 所以每一條都同時驗三件事——舊列真的不見了、新列的每個欄位逐字比對正確、
 * 稽核的 before/after 與資料庫實際狀態一致（不是只比對字串）。
 *
 * <h2>非空性：每條「拒絕」都配一條「放行」對照</h2>
 *
 * <p>尤其 {@link #resavingIdenticalContentSucceeds()} 與
 * {@link #resavingWithDifferentContentReplacesCorrectly()} 必須成組存在。
 * 前者證明「完全相同的內容重存」可行（管理頁最常見的情況），
 * 後者證明替換的<b>內容</b>是對的而不只是沒報錯。
 */
class VariableSpecBatchReplaceTest extends IntegrationTestBase {

    /** 持有通配權限 {@code *} → {@code ROLE_ADMIN}；{@code /api/admin/**} 需要它。 */
    private static final String ADMIN = "admin001";

    @Autowired
    private ProcessVariableSpecRepository specRepo;

    private final HttpClient http = HttpClient.newHttpClient();

    private final List<String> usedKeys = new ArrayList<>();

    @BeforeEach
    void clean() {
        truncateAuditLog();
    }

    @AfterEach
    void removeFixtures() {
        // 變數規格是外部系統的輸入驗證定義，留下 required=true 的幽靈規格會讓
        // 其他測試（例如 ExternalApiTcA04Test）的外部發起莫名其妙回 400。
        // 逐鍵刪除而不是清空整張表：測試共用同一個資料庫。
        for (String key : usedKeys) {
            specRepo.findByProcessDefinitionKeyOrderByVariableName(key)
                    .forEach(specRepo::delete);
        }
        usedKeys.clear();
    }

    // ── HTTP 小工具 ────────────────────────────────────────────────

    private HttpResponse<String> post(String key, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + SERVLET_PORT
                                + "/api/admin/process-definitions/" + key + "/variable-spec"))
                .header("X-Gateway-Secret", TestGatewayMockMvcCustomizer.GATEWAY_SECRET)
                .header("X-User-Id", ADMIN)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    /** 每次呼叫換一個 key —— 測試共用同一個資料庫，撞到別人的規格會讓斷言失真。 */
    private String freshKey() {
        String key = "T86-" + UUID.randomUUID().toString().substring(0, 8);
        usedKeys.add(key);
        return key;
    }

    private static String spec(String name, String type, boolean required,
                               String description, String example) {
        return "{\"variableName\":\"" + name + "\",\"variableType\":\"" + type + "\","
                + "\"required\":" + required + ",\"description\":\"" + description + "\","
                + "\"example\":\"" + example + "\"}";
    }

    private static String batch(String... specs) {
        return "[" + String.join(",", specs) + "]";
    }

    private static String twoSpecs() {
        return batch(spec("amount", "number", true, "AMOUNT-DESC", "100"),
                spec("reason", "string", false, "REASON-DESC", "because"));
    }

    // ── ① 缺陷本身：重複儲存 ───────────────────────────────────────

    @Test
    @DisplayName("#86：同一個 key 連續存兩次都必須 200（缺陷期間第二次回 500）")
    void resavingIdenticalContentSucceeds() throws Exception {
        String key = freshKey();

        // 第一次：乾淨起點，本來就會成功。
        // 這一條存在的理由是「非空性」——若只斷言第二次，缺陷期間第一次的
        // 失敗會讓測試看起來像通過了。
        assertThat(post(key, twoSpecs()).statusCode())
                .as("前置條件：全新 key 的第一次儲存必須成功").isEqualTo(200);

        // 第二次：完全相同的內容。這是管理頁最常見的情況
        // （打開頁面 → 沒有改任何東西 → 再按一次儲存）。
        var second = post(key, twoSpecs());

        assertThat(second.statusCode())
                .as("缺陷期間這裡回 500：Hibernate 在同一次 flush 把 INSERT 排在 "
                        + "DELETE 之前，撞上 uk_bpm_process_variable_spec_key_name")
                .isEqualTo(200);
        assertThat(second.statusCode())
                .as("必須是 200 而非其他 2xx／3xx").isNotIn(201, 204, 302);

        // 狀態碼不夠：資料必須確實是那一批。
        assertThat(namesUnder(key))
                .as("重存後的變數集合").containsExactly("amount", "reason");
        assertThat(requiredOf(key, "amount"))
                .as("重存後 required 必須仍是 true").isTrue();
    }

    @Test
    @DisplayName("#86：重複儲存時資料必須真的換成新的一批（不是只回 200 而沒做事）")
    void resavingWithDifferentContentReplacesCorrectly() throws Exception {
        String key = freshKey();
        assertThat(post(key, twoSpecs()).statusCode())
                .as("前置條件：第一次儲存成功").isEqualTo(200);

        // 第二次送完全不同的內容：刪掉 reason、放寬 amount、改掉說明與範例。
        // 這是「整批取代」真正有意義的用法，也是只驗狀態碼抓不到的錯誤。
        String replacement = batch(
                spec("amount", "string", false, "RELAXED-DESC", "HACKED"),
                spec("reason", "string", false, "REASON-DESC", "because"),
                spec("approver", "string", true, "NEW-VAR-DESC", "mgr001"));
        var second = post(key, replacement);

        assertThat(second.statusCode())
                .as("缺陷期間這裡回 500").isEqualTo(200);

        // 逐欄位比對。「只回 200 卻沒替換」與「替換了但欄位錯」都會被這裡擋下。
        assertThat(namesUnder(key))
                .as("新的一批必須完全取代舊的").containsExactly("amount", "approver", "reason");
        assertThat(stateOf(key, "amount"))
                .as("amount 必須真的被換成新的內容 —— 只驗狀態碼會漏掉「回 200 但沒做事」")
                .isEqualTo(new SpecState("string", false, "RELAXED-DESC", "HACKED"));
        assertThat(stateOf(key, "reason"))
                .as("沒被改動的列必須原樣保留")
                .isEqualTo(new SpecState("string", false, "REASON-DESC", "because"));
        assertThat(requiredOf(key, "approver"))
                .as("新增的變數必須真的存在").isTrue();
    }

    @Test
    @DisplayName("#86：重複儲存時被移除的變數必須真的消失（整批取代，不是合併）")
    void removedVariablesActuallyDisappear() throws Exception {
        String key = freshKey();
        assertThat(post(key, twoSpecs()).statusCode())
                .as("前置條件：第一次儲存成功").isEqualTo(200);
        assertThat(namesUnder(key)).containsExactly("amount", "reason");

        // 第二次只留 amount：reason 必須消失。
        // ⚠️ 這一條防的是「修法把整批取代變成 upsert」——那樣不會 500，
        // 狀態碼全綠，但管理頁刪掉一列之後按儲存，那列會陰魂不散地回來。
        assertThat(post(key, batch(spec("amount", "number", true, "AMOUNT-DESC", "100")))
                .statusCode())
                .as("缺陷期間這裡回 500").isEqualTo(200);

        assertThat(namesUnder(key))
                .as("reason 必須消失 —— 端點語意是「整批取代」")
                .containsExactly("amount");
    }

    // ── ② 稽核的誠實性 ────────────────────────────────────────────

    @Test
    @DisplayName("#86：稽核的 before/after 必須與資料庫實際狀態一致")
    void auditMatchesWhatActuallyHappened() throws Exception {
        String key = freshKey();
        assertThat(post(key, twoSpecs()).statusCode())
                .as("前置條件：第一次儲存成功").isEqualTo(200);

        String replacement = batch(spec("amount", "string", false, "RELAXED-DESC", "HACKED"));
        assertThat(post(key, replacement).statusCode())
                .as("缺陷期間這裡回 500").isEqualTo(200);

        var audits = awaitConfigChangeOf(key);
        assertThat(audits)
                .as("兩次成功的替換都必須留下稽核")
                .hasSize(2);

        // 第二筆（最新）的 before 必須是第一次的 after，
        // 而它的 after 必須等於資料庫現況 —— 稽核與資料綁在一起驗，而不是比對字串。
        String latest = audits.get(audits.size() - 1);
        assertThat(latest)
                .contains("\"configType\":\"process-variable-spec\"")
                .contains("\"action\":\"replace\"")
                .contains("\"processDefinitionKey\":\"" + key + "\"")
                .contains("\"before\":\"amount,reason\"")
                .contains("\"after\":\"amount\"")
                .contains("\"requiredAfter\":\"\"");
        assertThat(latest)
                .as("requiredAfter 必須反映實際狀態：amount 已被改成 required=false，"
                        + "所以「取代後還有哪些必填變數」是空的。"
                        + "缺陷期間這裡會寫成 amount，因為它記的是「呼叫端送來的」"
                        + "而不是「資料庫裡的」")
                .doesNotContain("\"requiredAfter\":\"amount\"");

        // 稽核指名的 key 底下，資料必須真的是稽核說的那樣。
        assertThat(namesUnder(key))
                .as("稽核說 after=amount，資料就必須只剩 amount")
                .containsExactly("amount");
        assertThat(requiredOf(key, "amount"))
                .as("稽核說 requiredAfter 為空，資料裡就必須沒有任何 required 變數")
                .isFalse();
    }

    // ── ③ 非空性與邊界 ────────────────────────────────────────────

    @Test
    @DisplayName("#86：其他 key 的規格不得被這次取代牽連")
    void otherKeysAreUntouched() throws Exception {
        // 缺陷是「整批取代 + 刪除」的組合，最危險的連帶damage是刪錯範圍。
        // 兩條不同 key 的規格並存，才能問出「刪除有沒有越界」這個問題。
        String keyA = freshKey();
        String keyB = freshKey();
        assertThat(post(keyA, twoSpecs()).statusCode())
                .as("前置條件：key A 第一次儲存成功").isEqualTo(200);
        assertThat(post(keyB, twoSpecs()).statusCode())
                .as("前置條件：key B 第一次儲存成功").isEqualTo(200);

        assertThat(post(keyA, batch(spec("only", "string", true, "ONLY-DESC", "x")))
                .statusCode())
                .as("缺陷期間這裡回 500").isEqualTo(200);

        assertThat(namesUnder(keyA)).containsExactly("only");
        assertThat(namesUnder(keyB))
                .as("key B 必須完全不受影響 —— 刪除範圍不能越過路徑上的 {key}")
                .containsExactly("amount", "reason");
        assertThat(stateOf(keyB, "amount"))
                .as("key B 的內容也必須逐欄位不變")
                .isEqualTo(new SpecState("number", true, "AMOUNT-DESC", "100"));
    }

    @Test
    @DisplayName("#86：空陣列是「刪光」而不是「什麼都不做」，且不得 500")
    void emptyBatchClearsEverything() throws Exception {
        String key = freshKey();
        assertThat(post(key, twoSpecs()).statusCode())
                .as("前置條件：第一次儲存成功").isEqualTo(200);

        // 這個形狀值得獨立一條：它是「整批取代」最極端的狀態，
        // 而缺陷期間「刪光」因為沒有 INSERT 所以不會撞約束 —— 也就是說
        // 只測「刪光」會讓這個缺陷看起來是好的。
        var res = post(key, "[]");

        assertThat(res.statusCode())
                .as("空陣列應回 200（端點語意是整批取代，空就是清空）").isEqualTo(200);
        assertThat(namesUnder(key))
                .as("整批取代的語意下，空陣列必須真的清空")
                .isEmpty();
    }

    @Test
    @DisplayName("#86：非管理員不得呼叫，且資料不得改變（修法沒有削弱授權）")
    void nonAdminIsRejectedAndNothingChanges() throws Exception {
        // 這一條盯的是「修法有沒有意外改到授權面」。
        // 修法動的是 repository 與刪除時機，理論上碰不到授權 ——
        // 但「理論上」正是迴歸測試存在的原因。
        String key = freshKey();
        assertThat(post(key, twoSpecs()).statusCode())
                .as("前置條件：管理員第一次儲存成功").isEqualTo(200);

        HttpRequest req = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + SERVLET_PORT
                                + "/api/admin/process-definitions/" + key + "/variable-spec"))
                .header("X-Gateway-Secret", TestGatewayMockMvcCustomizer.GATEWAY_SECRET)
                .header("X-User-Id", "user001")   // 沒有任何權限碼
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("[]"))
                .build();
        var res = http.send(req, HttpResponse.BodyHandlers.ofString());

        assertThat(res.statusCode())
                .as("user001 沒有任何權限碼，必須被授權層擋下").isEqualTo(403);
        assertThat(namesUnder(key))
                .as("被擋下的請求不得刪掉任何東西")
                .containsExactly("amount", "reason");
    }

    @Test
    @DisplayName("#86：夾帶 id 不得覆寫其他流程的規格（#85 的防護不能因為修法而鬆掉）")
    void smuggledIdDoesNotOverwriteOtherRows() throws Exception {
        // batchSave 強制 setId(null) 是 security-audit P0-4 的防護。
        // 修法動的正好是這一段的鄰居（刪除時機），所以必須確認它沒有鬆掉。
        String victimKey = freshKey();
        String otherKey = freshKey();
        assertThat(post(victimKey, batch(
                        spec("victim", "number", true, "VICTIM-DESC", "999")))
                .statusCode())
                .as("前置條件：受攻擊者的規格已存在").isEqualTo(200);
        String victimId = specRepo.findByProcessDefinitionKeyOrderByVariableName(victimKey)
                .get(0).getId();
        SpecState before = stateOf(victimKey, "victim");

        // 夾帶 victim 的 id，送到另一個 key 去。
        String body = "[{\"id\":\"" + victimId + "\",\"variableName\":\"hijack\","
                + "\"variableType\":\"string\",\"required\":false,"
                + "\"description\":\"HIJACK-DESC\",\"example\":\"HIJACK\"}]";
        assertThat(post(otherKey, body).statusCode())
                .as("夾帶 id 應被忽略（id 必須被清成 null），而不是覆寫 victim")
                .isEqualTo(200);

        assertThat(stateOf(victimKey, "victim"))
                .as("victim 必須逐欄位不變 —— setId(null) 生效的證據")
                .isEqualTo(before);
        assertThat(namesUnder(otherKey))
                .as("夾帶的 id 不得讓新資料掛到 victim 那裡")
                .containsExactly("hijack");
    }

    // ── 資料驗證小工具 ────────────────────────────────────────────

    private record SpecState(String variableType, Boolean required,
                             String description, String example) {
    }

    private SpecState stateOf(String key, String variableName) {
        ProcessVariableSpec s = specRepo
                .findByProcessDefinitionKeyOrderByVariableName(key).stream()
                .filter(x -> x.getVariableName().equals(variableName))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "變數 " + variableName + " 不在 " + key + " 底下 —— 測試前提失效"));
        return new SpecState(s.getVariableType(), s.getRequired(), s.getDescription(), s.getExample());
    }

    private List<String> namesUnder(String key) {
        return specRepo.findByProcessDefinitionKeyOrderByVariableName(key).stream()
                .map(ProcessVariableSpec::getVariableName).toList();
    }

    private Boolean requiredOf(String key, String variableName) {
        return specRepo.findByProcessDefinitionKeyOrderByVariableName(key).stream()
                .filter(x -> x.getVariableName().equals(variableName))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "變數 " + variableName + " 不在 " + key + " 底下 —— 測試前提失效"))
                .getRequired();
    }

    /** 稽核掛在業務交易的 beforeCommit，短暫停頓只是讓斷言更嚴格。 */
    private static List<String> configChangeDetailsOf(String key) {
        var out = new ArrayList<String>();
        withAuditConnection(c -> {
            try (var ps = c.prepareStatement(
                    "SELECT detail FROM bpm_audit_log WHERE operation_type = 'CONFIG_CHANGE' "
                            + "AND detail LIKE ? ORDER BY id")) {
                ps.setString(1, "%\"processDefinitionKey\":\"" + key + "\"%");
                var rs = ps.executeQuery();
                while (rs.next()) out.add(rs.getString(1));
            }
        });
        return out;
    }

    private static List<String> awaitConfigChangeOf(String key) throws InterruptedException {
        List<String> found = List.of();
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline && found.size() < 2) {
            found = configChangeDetailsOf(key);
            if (found.size() < 2) Thread.sleep(100);
        }
        return found;
    }
}
