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
 * #87：{@code POST /api/admin/process-definitions/{key}/variable-spec} 送
 * 同一批內重複的 {@code variableName} 必定 500。
 *
 * <h2>缺陷（修補前的實際行為）</h2>
 *
 * <p>{@code ProcessVariableSpec} 上有
 * {@code @UniqueConstraint(processDefinitionKey, variableName)}，
 * 而 {@code batchSave} 對 payload 完全不驗證就直接 {@code saveAll}，
 * 所以送 {@code [{"variableName":"x",…},{"variableName":"x",…}]}
 * 必然在 flush 時撞約束 → {@code DataIntegrityViolationException} → 500。
 *
 * <p><b>與 #86 是不同的根因，#86 的修法碰不到它。</b>#86 是「刪除與寫入在同一次
 * flush 的順序問題」；本項是<b>呼叫端送了互相衝突的資料</b> ——
 * 資料庫層面兩筆本來就不可能同時存在，換任何刪除方式都不會改變這件事。
 * 實測（#86 已修好之後）：全新 key 送兩筆同名 → 仍然 500。
 *
 * <h2>危害不是 500 本身，而是 500 會教呼叫端做錯事</h2>
 *
 * <p>500 的慣例語意是「伺服器的問題，稍後重試」，但重試<b>永遠不會成功</b>
 * —— payload 沒變，結果就不會變。呼叫端（與看 log 的人）會把時間花在
 * 重試與查伺服器狀態上，而真正的修法是改 payload。
 * 400 才會說「這是請求本身的問題，改 payload」。
 *
 * <h2>⚠️ 「重複」不能用 {@code Set<String>} 判斷：資料庫的定序不是逐字比對</h2>
 *
 * <p>本專案三個資料庫的文字欄位是 {@code SQL_Latin1_General_CP1_CI_AS}
 * （CI = 不分大小寫、AS = 分重音）。對 MSSQL 實查（{@code sqlcmd} 直接比對，
 * 並用 {@code sys.columns} 確認欄位 collation）確認下列名稱<b>在資料庫層面
 * 互相衝突</b>，也就是修補前照樣 500，而 {@code Set<String>} 恰恰擋不住：
 *
 * <table border="1">
 *   <caption>實測（sqlcmd 對照）</caption>
 *   <tr><th>比對</th><th>MSSQL</th><th>理由</th></tr>
 *   <tr><td>{@code amount} / {@code AMOUNT}</td><td>衝突</td><td>CI</td></tr>
 *   <tr><td>{@code amount} / {@code amount }</td><td>衝突</td><td>ANSI padding</td></tr>
 *   <tr><td>{@code Ａ} / {@code A}</td><td>衝突</td><td>全形半形同一字</td></tr>
 *   <tr><td>{@code café} / {@code cafe}</td><td>不衝突</td><td>AS（分重音）</td></tr>
 *   <tr><td>{@code ①} / {@code 1}</td><td>不衝突</td><td>相容不等價</td></tr>
 * </table>
 *
 * <p><b>「大小寫不同」不是雞蛋肋，它就是最常見的失敗形狀</b> ——
 * 使用者在表格裡輸入 {@code Amount} 與 {@code amount} 是很自然的事。
 * 所以本類別的測試有一半是在釘住「哪些形狀要擋、哪些形狀不能擋」。
 *
 * <h2>⚠️ 每條狀態碼斷言都必須同時驗「資料沒被變」</h2>
 *
 * <p>這個端點是<b>整批取代</b>，所以「驗證放在刪除之前」不是風格問題：
 * 放在刪除之後就是「先清空、再回 400」，資料要靠交易回捲才救得回來。
 * 因此每條拒絕都同時驗三件事：舊列逐欄位不變、沒有新增任何列、
 * 稽核庫不得有指向這個 key 的 {@code CONFIG_CHANGE}
 * （一筆 {@code replace} 代表「這個變更發生了」，而實際什麼都沒發生）。
 *
 * <h2>狀態碼走真實 HTTP</h2>
 *
 * <p>{@code ResponseStatusException} 走的是 <b>ERROR dispatch</b>，
 * 而 MockMvc 不做 error dispatch（見 {@code ErrorDispatchTest}）——
 * 線上的狀態碼正是那條路徑決定的（{@code SecurityConfig} 的
 * {@code dispatcherTypeMatchers(ERROR).permitAll()}）。
 * 本類別的斷言全部走真實 HTTP。
 *
 * <h2>非空性：每一條拒絕都配一條放行對照</h2>
 *
 * <p>驗證寫得太寬（例如一律用整串 {@code NFKC}、或一律 trim）也會讓這些
 * 「必須 400」的測試全綠。所以 {@link #namesTheDatabaseKeepsDistinctAreStillAccepted()}
 * 與 {@link #singleBlankNameIsStillAccepted()} 是必要的對照組。
 *
 * <h2>負向控制組的實測結果：11 條中紅 8 條、綠 3 條</h2>
 *
 * <p>把整個 controller 還原成 HEAD 版本（也就是缺陷期間的樣子）後重跑：
 * <ul>
 *   <li><b>紅（8）</b>：{@code exactDuplicateInOneBatchIsRejected}、
 *       {@code caseInsensitiveDuplicateIsRejected}、
 *       {@code trailingSpaceDuplicateIsRejected}、
 *       {@code fullwidthDuplicateIsRejected}、
 *       {@code messageNamesEveryCollidingSpelling}、
 *       {@code singleBlankNameIsStillAccepted}（後半段）、
 *       {@code rejectedBatchLeavesExistingSpecsUntouched}、
 *       {@code updateRenamingOntoAnotherVariableIsRejected}。</li>
 *   <li><b>綠（3）</b>：{@code namesTheDatabaseKeepsDistinctAreStillAccepted}、
 *       {@code sameNameUnderDifferentKeysIsAllowed}、
 *       {@code updateRenamingToAFreeNameStillWorks}。</li>
 * </ul>
 *
 * <p><b>綠的那 3 條正是應該綠的</b>：它們防的是「修法把語意改壞」
 * （擋掉合法名稱、擋掉不同流程的同名、擋掉所有改名），
 * 不是缺陷本身。少了它們，一個「把整個端點都擋掉」的修法也能讓紅的 7 條全綠。
 *
 * <h2>⚠️ 一個必須記錄的觀察：哪些斷言在負向控制組裡是「空斷言」</h2>
 *
 * <p>{@code rejectedBatchLeavesExistingSpecsUntouched} 失敗在
 * <b>狀態碼斷言</b>上（500 而非 400），它的資料斷言在缺陷期間<b>也是綠的</b> ——
 * 因為缺陷路徑是「整個方法跑到 flush 才炸」，交易回捲本來就會把資料還原。
 * 換句話說，那些資料斷言<b>無法區分</b>「驗證放在刪除之前（正確）」與
 * 「驗證放在刪除之後（會先清空再回 400）」—— 只要例外有被交易接住，
 * 兩者的資料庫結果一樣。
 *
 * <p>它們仍然要留著：一旦有人把驗證移到 {@code deleteAllBy…} 之後，
 * 而呼叫鏈上有別人把例外吃掉（{@code @Transactional} 的回捲就失效了），
 * 這些斷言是唯一會出聲的。但要誠實地知道它們對<b>本缺陷</b>沒有鑑別力。
 *
 * <p>同一組觀察也解釋了 {@code singleBlankNameIsStillAccepted} 為什麼紅：
 * 它紅在<b>後半段</b>（「兩個空白名稱互相衝突 → 400」），
 * 前半段（單一空白名 → 200）在缺陷期間本來就成立。
 */
class VariableSpecDuplicateNameTest extends IntegrationTestBase {

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

    private HttpResponse<String> put(String key, String id, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + SERVLET_PORT
                                + "/api/admin/process-definitions/" + key
                                + "/variable-spec/" + id))
                .header("X-Gateway-Secret", TestGatewayMockMvcCustomizer.GATEWAY_SECRET)
                .header("X-User-Id", ADMIN)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    /** 每次呼叫換一個 key —— 測試共用同一個資料庫，撞到別人的規格會讓斷言失真。 */
    private String freshKey() {
        String key = "T87-" + UUID.randomUUID().toString().substring(0, 8);
        usedKeys.add(key);
        return key;
    }

    private static String spec(String name, String type, boolean required) {
        return spec(name, type, required, "D", "E");
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

    // ── ① 缺陷本身：逐字相同的重複 ─────────────────────────────────

    @Test
    @DisplayName("#87：同一批內逐字相同的變數名 → 400（缺陷期間回 500），且指名是哪個名字")
    void exactDuplicateInOneBatchIsRejected() throws Exception {
        String key = freshKey();

        var res = post(key, batch(spec("amount", "string", true),
                spec("amount", "number", false)));

        assertThat(res.statusCode())
                .as("缺陷期間這裡回 500：同一個 flush 裡的兩筆 INSERT 互相撞 "
                        + "uk_bpm_process_variable_spec_key_name")
                .isEqualTo(400);
        assertThat(res.body())
                .as("400 必須指名重複的名字 —— 呼叫端要靠這句話知道要改哪一列，"
                        + "只說「你送錯了」等於把比對的工作丟回去給它")
                .contains("amount");
        assertThat(res.body())
                .as("必須是 400 而不是 500：500 的語意是「稍後重試」，"
                        + "而重試永遠不會成功（payload 沒變，結果就不會變）")
                .doesNotContain("\"status\":500");

        // 狀態碼不夠：必須同時驗資料沒被動過。
        assertThat(namesUnder(key))
                .as("被擋下的請求不得留下任何資料").isEmpty();
        assertThat(settledConfigChangeOf(key))
                .as("被擋下的請求不得留下 CONFIG_CHANGE —— 那正是「稽核說這個變更發生了」"
                        + "而實際沒發生的情形（與 #85 的 404 同一個政策）")
                .isEmpty();
    }

    // ── ② 資料庫層面也算重複的形狀（Set<String> 擋不住的）───────────

    @Test
    @DisplayName("#87：只差大小寫的名稱 → 400（欄位定序是 CI，資料庫層面就是衝突）")
    void caseInsensitiveDuplicateIsRejected() throws Exception {
        String key = freshKey();

        var res = post(key, batch(spec("Amount", "string", true),
                spec("amount", "string", false)));

        assertThat(res.statusCode())
                .as("實測 MSSQL：'Amount' = 'amount' 在 SQL_Latin1_General_CP1_CI_AS 下"
                        + "為真，所以缺陷期間這裡是 500。用 Set<String> 判斷的修法"
                        + "擋不住這個形狀，使用者在表格裡打兩個大小寫不同的名字就會踩到")
                .isEqualTo(400);
        assertThat(namesUnder(key)).isEmpty();
    }

    @Test
    @DisplayName("#87：只在尾端差一個空白的名稱 → 400（MSSQL 的 ANSI padding）")
    void trailingSpaceDuplicateIsRejected() throws Exception {
        String key = freshKey();

        var res = post(key, batch(spec("amount", "string", true),
                spec("amount ", "string", false)));

        assertThat(res.statusCode())
                .as("實測 MSSQL：'amount' = 'amount ' 為真（ANSI padding 只忽略"
                        + "尾端的 U+0020，TAB／LF／NBSP 都不算）")
                .isEqualTo(400);
        assertThat(namesUnder(key)).isEmpty();
    }

    @Test
    @DisplayName("#87：全形與半形同一個字 → 400（全形輸入是中文語系鍵盤的常見產物）")
    void fullwidthDuplicateIsRejected() throws Exception {
        String key = freshKey();

        var res = post(key, batch(spec("Ａ", "string", true), spec("A", "string", false)));

        assertThat(res.statusCode())
                .as("實測 MSSQL：全形 'Ａ' = 半形 'A' 為真。這個形狀最常見的來源"
                        + "是使用者用全形輸入法打了變數名")
                .isEqualTo(400);
        assertThat(namesUnder(key)).isEmpty();
    }

    // ── ③ 非空性：驗證不得過度阻擋 ─────────────────────────────────

    @Test
    @DisplayName("#87：400 的訊息必須把互相衝突的「兩種寫法」都列出來")
    void messageNamesEveryCollidingSpelling() throws Exception {
        // 只報「第一個看到的」不夠：最常見的形狀是 Amount 與 amount，
        // 使用者看到「重複: Amount」仍然不知道自己打錯了哪一個。
        String key = freshKey();
        var res = post(key, batch(spec("Amount", "string", true),
                spec("amount", "string", false)));

        assertThat(res.statusCode()).isEqualTo(400);
        assertThat(res.body())
                .as("兩種寫法都要出現，呼叫端才知道資料庫是怎麼判它們相同的")
                .contains("Amount").contains("amount");

        // 同一個寫法送三次只報一次 —— 報三次「a、a、a」沒有任何新資訊。
        String thrice = freshKey();
        var again = post(thrice, batch(spec("a", "string", true),
                spec("a", "string", false), spec("a", "string", true)));
        assertThat(again.statusCode()).isEqualTo(400);
        assertThat(countOccurrences(again.body(), "：a"))
                .as("同一個寫法重複出現時只報一次").isLessThanOrEqualTo(1);

        // 兩個重複群組都要報，不能只報第一個。
        String twoGroups = freshKey();
        var multi = post(twoGroups, batch(spec("a", "string", true),
                spec("b", "string", false), spec("a", "string", false),
                spec("b", "string", true)));
        assertThat(multi.statusCode()).isEqualTo(400);
        assertThat(multi.body())
                .as("兩個群組都要報 —— 只報一個會讓使用者修完再按一次才看到下一個")
                .contains("a").contains("b");
    }

    private static int countOccurrences(String text, String needle) {
        int n = 0;
        int i = text.indexOf(needle);
        while (i >= 0) {
            n++;
            i = text.indexOf(needle, i + needle.length());
        }
        return n;
    }

    @Test
    @DisplayName("#87：資料庫分得開的名稱必須照常儲存（不得誤擋合法資料）")
    void namesTheDatabaseKeepsDistinctAreStillAccepted() throws Exception {
        // 這一條與 ② 必須成組存在。驗證寫得太寬（整串 NFKC、trim()、
        // 去掉重音）也會讓 ② 的 400 全綠 —— 但那等於禁止使用合法的變數名。
        // 每一組都是實測過 MSSQL 判定為「不衝突」的組合。
        String accents = freshKey();
        assertThat(post(accents, batch(spec("café", "string", true),
                        spec("cafe", "string", false))).statusCode())
                .as("欄位定序是 AS（分重音），所以 café 與 cafe 是兩個不同的變數，"
                        + "擋下來就是禁止使用合法的名稱")
                .isEqualTo(200);
        assertThat(namesUnder(accents))
                .as("兩筆都要真的存進去 —— 只回 200 卻沒存是另一種缺陷")
                .containsExactlyInAnyOrder("café", "cafe");

        String circled = freshKey();
        assertThat(post(circled, batch(spec("①", "string", true),
                        spec("1", "string", false))).statusCode())
                .as("實測 MSSQL：'①' ≠ '1'。整串 NFKC 會把 ① 折成 1 而誤擋，"
                        + "所以這裡只折疊寬度不敏感的三段範圍")
                .isEqualTo(200);

        String leading = freshKey();
        assertThat(post(leading, batch(spec(" amount", "string", true),
                        spec("amount", "string", false))).statusCode())
                .as("實測 MSSQL：' amount' ≠ 'amount' —— 只有尾端空白被忽略，"
                        + "String.trim() 會誤擋這個形狀")
                .isEqualTo(200);
    }

    @Test
    @DisplayName("#87：不同流程底下同名是允許的（唯一約束含 key，不得過度阻擋）")
    void sameNameUnderDifferentKeysIsAllowed() throws Exception {
        // 唯一約束是 (processDefinitionKey, variableName) —— 沒有 key 就不重複。
        // 驗證若忘了帶上 key 維度，這裡會回 400，而那會讓「兩個流程都有 amount」
        // 這種完全正常的設定存不進去。
        String keyA = freshKey();
        String keyB = freshKey();
        String sameName = batch(spec("amount", "string", true), spec("reason", "string", false));

        assertThat(post(keyA, sameName).statusCode()).as("前置條件：key A").isEqualTo(200);
        assertThat(post(keyB, sameName).statusCode())
                .as("兩個流程各自有 amount／reason 是完全正常的設定")
                .isEqualTo(200);
        assertThat(namesUnder(keyA)).containsExactlyInAnyOrder("amount", "reason");
        assertThat(namesUnder(keyB)).containsExactlyInAnyOrder("amount", "reason");
    }

    @Test
    @DisplayName("#87：單一空白名稱目前仍允許（政策性決定未拍板，此處固定現狀）")
    void singleBlankNameIsStillAccepted() throws Exception {
        // ⚠️ 空白名稱該不該擋是**政策性決定**，本工項刻意不代做。
        // 這條測試的用途是把「現狀」釘住：#87 只處理重複，不改變
        // 「單一空白名稱可以存」這件事 —— 免得後續有人以為那是順帶修好的。
        // PM 線上實測（2026-09-30）也確認單一空字串回 200。
        String key = freshKey();

        assertThat(post(key, batch(spec("", "string", false),
                        spec("amount", "string", true))).statusCode())
                .as("單一空白名稱的現行政策是允許（ProcessVariableSpec.variableName "
                        + "是 nullable=false 但空字串過得去）。若政策改成要擋，"
                        + "這條測試必須跟著改")
                .isEqualTo(200);

        // 對照：兩個空白名稱互相重複 —— 那不是政策問題，是明確的資料衝突。
        String dup = freshKey();
        assertThat(post(dup, batch(spec("", "string", false), spec("", "string", true)))
                .statusCode())
                .as("兩個空白名稱互相衝突，資料庫層面必然撞約束 —— "
                        + "這與「空白名稱該不該允許」無關")
                .isEqualTo(400);
    }

    // ── ④ 整批取代的語意：被拒時舊資料必須原封不動 ─────────────────

    @Test
    @DisplayName("#87：含重複的整批被拒時，既有規格一個欄位都不能被改")
    void rejectedBatchLeavesExistingSpecsUntouched() throws Exception {
        // 這個端點是「整批取代」：驗證若放在 deleteAllByProcessDefinitionKey
        // 之後，回 400 的那一刻舊規格已經被刪掉，資料要靠交易回捲才救得回來。
        // 這條測試就是那個「順序放錯」的守門員。
        String key = freshKey();
        assertThat(post(key, batch(spec("amount", "number", true, "AMOUNT-DESC", "100"),
                spec("reason", "string", false, "REASON-DESC", "because"))).statusCode())
                .as("前置條件：第一次儲存成功").isEqualTo(200);
        var before = statesUnder(key);
        // 第一次成功儲存本來就會留下一筆 replace —— 所以這裡斷言的是
        // 「被拒的請求沒有**多**留下一筆」，而不是「整個 key 沒有紀錄」。
        int auditCountAfterSuccess = awaitConfigChangeCountOf(key, 1);

        // 第二批裡的 approver 重複，但理由欄位寫的是「想偷偷放寬 amount 的必填」。
        // 如果驗證放錯順序，舊資料會先被刪掉。
        var res = post(key, batch(spec("amount", "string", false, "RELAXED-DESC", "HACKED"),
                spec("approver", "string", true, "APPROVER-DESC", "mgr001"),
                spec("approver", "string", false, "DUPLICATE-DESC", "x")));

        assertThat(res.statusCode()).isEqualTo(400);
        assertThat(statesUnder(key))
                .as("整批取代的端點若在驗證之前就刪除，這裡會變成空 —— "
                        + "舊資料只會靠交易回捲救回來，而回捲能不能救回來取決於"
                        + "呼叫鏈上有沒有別人把例外吃掉")
                .isEqualTo(before);
        assertThat(requiredOf(key, "amount"))
                .as("required 必須仍是 true —— 放寬外部系統的輸入驗證是這個端點"
                        + "真正的實質危害（理由見 VariableSpecTargetValidationTest）")
                .isTrue();

        Thread.sleep(300);
        assertThat(configChangeDetailsOfKey(key))
                .as("被拒的請求不得留下「這個變更發生了」的稽核紀錄 —— "
                        + "被記下來的那一筆必須仍然只是前面那次成功的 replace")
                .hasSize(auditCountAfterSuccess)
                .allSatisfy(detail -> assertThat(detail)
                        .as("不得有記錄宣稱 approver 已經被加進來")
                        .doesNotContain("approver"));
    }

    // ── ⑤ 相鄰缺陷：update 改名撞到其他變數 ────────────────────────

    @Test
    @DisplayName("#87：update 改名撞到同一流程底下的其他變數 → 400（缺陷期間回 500）")
    void updateRenamingOntoAnotherVariableIsRejected() throws Exception {
        // 與 batchSave 的重複是**同一個資料庫約束**，所以是同一個工項。
        // 留下來的話，「這個端點的 500 修掉了嗎」仍然是無法回答的問題。
        String key = freshKey();
        assertThat(post(key, batch(spec("amount", "number", true, "AMOUNT-DESC", "100"),
                spec("reason", "string", false, "REASON-DESC", "because"))).statusCode())
                .as("前置條件：第一次儲存成功").isEqualTo(200);
        String amountId = idOf(key, "amount");
        var before = statesUnder(key);

        var res = put(key, amountId, "{\"variableName\":\"reason\",\"variableType\":\"string\","
                + "\"required\":false,\"description\":\"HIJACK\",\"example\":\"HIJACK\"}");

        assertThat(res.statusCode())
                .as("缺陷期間這裡回 500（撞同一個唯一約束），而 500 會讓呼叫端一直重試")
                .isEqualTo(400);
        assertThat(res.body())
                .as("必須指名衝突的名字").contains("reason");
        assertThat(statesUnder(key))
                .as("被拒的改名不得留下任何副作用").isEqualTo(before);
        assertThat(requiredOf(key, "amount"))
                .as("把 amount 改名成 reason 等於刪掉一個必填變數 —— "
                        + "而實際上它只是改了名字，required 必須仍是 true")
                .isTrue();
        assertThat(settledConfigChangeOfId(amountId))
                .as("被擋下的請求不得留下稽核")
                .isEmpty();
    }

    @Test
    @DisplayName("#87：update 改成沒人用的名字必須照常成功（擋太寬的對照組）")
    void updateRenamingToAFreeNameStillWorks() throws Exception {
        // 少這一條，上一條的 400 可能來自「擋掉所有改名」而不是撞名檢查。
        // 管理頁最常見的操作就是改名字，必須確定它還能用。
        String key = freshKey();
        assertThat(post(key, batch(spec("amount", "number", true, "AMOUNT-DESC", "100"),
                spec("reason", "string", false, "REASON-DESC", "because"))).statusCode())
                .as("前置條件：第一次儲存成功").isEqualTo(200);
        String amountId = idOf(key, "amount");

        // (1) 改成與自己相同的名字（只改型別與說明）—— 最常見的操作。
        var res = put(key, amountId, "{\"variableName\":\"amount\",\"variableType\":\"string\","
                + "\"required\":true,\"description\":\"SAME-NAME\",\"example\":\"E\"}");
        assertThat(res.statusCode())
                .as("送回與現況相同的名字不是撞名 —— 拿它跟自己比會擋掉正常功能")
                .isEqualTo(200);

        // (2) 改成一個沒有人用的名字。
        assertThat(put(key, amountId, "{\"variableName\":\"total\",\"variableType\":\"string\","
                        + "\"required\":true,\"description\":\"RENAMED\",\"example\":\"E\"}")
                .statusCode())
                .as("改名到空的名字必須放行").isEqualTo(200);

        assertThat(namesUnder(key)).containsExactlyInAnyOrder("total", "reason");
        assertThat(stateOf(key, "total").description()).isEqualTo("RENAMED");
    }

    // ── 資料驗證小工具 ────────────────────────────────────────────

    private record SpecState(String variableName, String variableType, Boolean required,
                             String description, String example) {
    }

    private List<SpecState> statesUnder(String key) {
        return specRepo.findByProcessDefinitionKeyOrderByVariableName(key).stream()
                .map(s -> new SpecState(s.getVariableName(), s.getVariableType(), s.getRequired(),
                        s.getDescription(), s.getExample()))
                .toList();
    }

    private SpecState stateOf(String key, String variableName) {
        return statesUnder(key).stream()
                .filter(s -> s.variableName().equals(variableName))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "變數 " + variableName + " 不在 " + key + " 底下 —— 測試前提失效"));
    }

    private List<String> namesUnder(String key) {
        return specRepo.findByProcessDefinitionKeyOrderByVariableName(key).stream()
                .map(ProcessVariableSpec::getVariableName).toList();
    }

    private Boolean requiredOf(String key, String variableName) {
        return stateOf(key, variableName).required();
    }

    private String idOf(String key, String variableName) {
        return specRepo.findByProcessDefinitionKeyOrderByVariableName(key).stream()
                .filter(s -> s.getVariableName().equals(variableName))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "變數 " + variableName + " 不在 " + key + " 底下 —— 測試前提失效"))
                .getId();
    }

    private static List<String> configChangeDetailsOf(String pattern) {
        var out = new ArrayList<String>();
        withAuditConnection(c -> {
            try (var ps = c.prepareStatement(
                    "SELECT detail FROM bpm_audit_log WHERE operation_type = 'CONFIG_CHANGE' "
                            + "AND detail LIKE ? ORDER BY id")) {
                ps.setString(1, pattern);
                var rs = ps.executeQuery();
                while (rs.next()) out.add(rs.getString(1));
            }
        });
        return out;
    }

    private static List<String> configChangeDetailsOfKey(String key) {
        return configChangeDetailsOf("%\"processDefinitionKey\":\"" + key + "\"%");
    }

    private static List<String> configChangeDetailsOfId(String id) {
        return configChangeDetailsOf("%\"" + id + "\"%");
    }

    /** 稽核掛在業務交易的 beforeCommit，短暫停頓只是讓「不得留下紀錄」更嚴格。 */
    private static List<String> settledConfigChangeOf(String key) throws InterruptedException {
        Thread.sleep(300);
        return configChangeDetailsOfKey(key);
    }

    /** 等到指向這個 key 的 CONFIG_CHANGE 至少 {@code min} 筆，回傳目前的筆數。 */
    private static int awaitConfigChangeCountOf(String key, int min) throws InterruptedException {
        int count = 0;
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline && count < min) {
            count = configChangeDetailsOfKey(key).size();
            if (count < min) Thread.sleep(100);
        }
        assertThat(count)
                .as("前置條件失效：應該已經有 %d 筆 CONFIG_CHANGE 指向 %s", min, key)
                .isGreaterThanOrEqualTo(min);
        return count;
    }

    private static List<String> settledConfigChangeOfId(String id) throws InterruptedException {
        Thread.sleep(300);
        return configChangeDetailsOfId(id);
    }
}
