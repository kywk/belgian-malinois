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
 * <p>⚠️ 本檔後來也釘住了 #87-2／#87-3（<b>空白與 null 的名稱要回 400</b>），
 * 類別名沿用原本的名字沒有改 —— 它是 #87 建立的，改名會讓
 * 「這批測試是誰寫的、動過什麼」在 git 歷史裡斷掉。
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
 * <h2>空白與 null 的名稱（#87-2／#87-3，使用者 2026-09-30 裁決要擋）</h2>
 *
 * <p>裁決前的實測（PM 線上實測）：{@code ""} 與 {@code "  "} → <b>200</b>
 * （真的寫進資料庫），{@code null} → <b>500</b>。危害是「宣告了一個外部系統
 * 永遠比對不到的 key」，而 {@code required=true} 的空白名會讓
 * <b>每一次</b>外部發起都回 400，管理頁上卻看不出任何設定有問題。
 * 理由與修法見 {@code ProcessVariableSpecController.requireUsableVariableNames}。
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
 * <h2>⚠️「空白」也不能用 {@code String.isBlank()} 判斷，同一個理由</h2>
 *
 * <p>{@code isBlank()} 走 {@code Character.isWhitespace}，會把 TAB、換行
 * 算成空白，但上表已證實那些<b>不是</b>資料庫認得的空白（ANSI padding 只忽略
 * 尾端 U+0020）。所以正確的判準是「{@code dbComparisonKey} 是不是空字串」
 * —— 也就是「資料庫分不出這個名字與空字串的差別」。
 * {@link #tabOnlyNameIsStillAccepted()} 是這條的對照組。
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
 * <p>驗證寫得太寬（例如一律用整串 {@code NFKC}、一律 trim、擋掉所有空白）
 * 也會讓這些「必須 400」的測試全綠。所以 {@link #namesTheDatabaseKeepsDistinctAreStillAccepted()}、
 * {@link #tabOnlyNameIsStillAccepted()}、{@link #updateRenamingToAFreeNameStillWorks()}
 * 與 {@link #keyMatchFallsThroughToTheBlankNameCheck()} 是必要的對照組。
 *
 * <h2>本檔改了哪些測試（#87-2／#87-3）</h2>
 *
 * <p><b>唯一被改掉的是 {@code singleBlankNameIsStillAccepted} → 拆成
 * {@link #singleBlankNameIsRejected()} 等五條。</b>那條測試存在的唯一目的
 * 就是釘住「單一空白名目前回 200」，而政策裁決後現況變了；
 * 留著一條會紅的測試等於在說謊，刪掉不等於解決問題。
 * 拆開的理由是三種形狀（空字串／只有空白／JSON null）各有各的危害與狀態碼，
 * 而「只由 TAB 組成的名稱必須放行」必須是<b>獨立的對照組</b>，
 * 否則它會被前面的迴圈蓋掉。
 *
 * <p>其餘測試（#87 的重複名那幾條與 #85／#86 的對照）<b>邏輯零改動</b>，
 * 只有類別 javadoc 補上本節。
 *
 * <h2>負向控制組：把整份 controller 還原成 HEAD 後的實測結果</h2>
 *
 * <p>用 {@code command cp} 備份／整份還原 {@code ProcessVariableSpecController.java}
 * 後重跑。⚠️ 只把「手動改的那幾行刪掉」會得到無效結果 —— handoff 第 4.4 節
 * 記過：缺陷放回去的<b>位置</b>會影響結果（那次把 initiator 放回檢查之前，
 * deny-list 反過來擋掉一切、12 條測試全綠，等於沒驗到）。所以整份還原。
 *
 * <p>實測：<b>19 條中紅 7 條、綠 12 條</b>。
 *
 * <ul>
 *   <li><b>紅（7）—— 全部是空白／null 政策那幾條</b>：
 *       {@code singleBlankNameIsRejected}（回 200）、
 *       {@code nullNameIsRejected}（回 500）、
 *       {@code twoBlankNamesReportBlanknessNotDuplication}（訊息說的是「不得重複」）、
 *       {@code blankAndDuplicateAreReportedTogether}（訊息沒有指名是哪一列）、
 *       {@code blankNameBatchLeavesExistingSpecsUntouched}（回 200）、
 *       {@code keyMatchFallsThroughToTheBlankNameCheck}（回 200）、
 *       {@code updateRenamingToBlankNameIsRejected}（回 200）。</li>
 *   <li><b>綠（12）</b>：#87 的 5 條重複名測試、
 *       {@code namesTheDatabaseKeepsDistinctAreStillAccepted}、
 *       {@code sameNameUnderDifferentKeysIsAllowed}、
 *       {@code tabOnlyNameIsStillAccepted}、
 *       {@code rejectedBatchLeavesExistingSpecsUntouched}、
 *       {@code keyMismatchOutranksBlankName}、
 *       {@code updateRenamingOntoAnotherVariableIsRejected}、
 *       {@code updateRenamingToAFreeNameStillWorks}。</li>
 * </ul>
 *
 * <p><b>綠的 12 條正是應該綠的</b>：#87 的那 5 條與兩條 400 測試在缺陷期間
 * 本來就成立（HEAD 已經有重複名檢查），它們是<b>非回歸</b>的守門員；
 * 4 條放行對照（合法的名稱必須照常存）則防的是「修法把語意改壞」。
 * 少了後者，一個「把整個端點都擋掉」的修法也能讓紅的 7 條全綠。
 *
 * <p><b>兩條對「缺陷形狀」必然是綠的，這點要誠實記下來</b>：
 * <ul>
 *   <li>{@code keyMismatchOutranksBlankName}（key 不符 + 空白名 → 404）：
 *       缺陷期間根本沒有空白檢查，所以回 404 是「本來就對」的。
 *       它釘的是<b>順序</b>（守衛必須排在形狀檢查之前），而順序這個屬性
 *       只能靠「<b>同時</b>存在兩種規則」的版本才驗得到 ——
 *       也就是本次這個版本。這也是它不能被負向控制組驗證的原因。</li>
 *   <li>{@code blankAndDuplicateAreReportedTogether} 第一次寫的版本也是綠的：
 *       缺陷期間的訊息是「不得重複: （空白）／Amount／amount」，
 *       剛好也含有「空白」「Amount」「amount」三個字。
 *       <b>已經補上 {@code contains("第 1 列")}</b>（指名是哪一列）才變紅 ——
 *       記在這裡是因為「測試全紅」不等於「測試有效」，
 *       中間還有一層「這些字串是不是剛好都出現了」。</li>
 * </ul>
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

    /** 送出 JSON 層的 {@code null}（不是空字串）—— 兩者在 HTTP 上是不同形狀。 */
    private static String nullNameSpec() {
        return "{\"variableName\":null,\"variableType\":\"string\",\"required\":false,"
                + "\"description\":\"D\",\"example\":\"E\"}";
    }

    /**
     * 名稱是一個跳脫過的 TAB。
     *
     * <p>⚠️ 這裡<b>不能</b>直接把 {@code "\t"} 塞進 {@link #spec}：JSON 字串裡
     * 未跳脫的控制字元是非法的，Jackson 會在解析階段就把它擋掉（400，
     * 但那是框架的解析錯誤、不是本項的驗證）—— 那會讓這條測試變成空斷言。
     */
    private static String specWithEscapedTabName() {
        return "{\"variableName\":\"\\t\",\"variableType\":\"string\",\"required\":false,"
                + "\"description\":\"D\",\"example\":\"E\"}";
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
    @DisplayName("#87-2：單一空白名稱 → 400（裁決前是 200：真的寫進了資料庫）")
    void singleBlankNameIsRejected() throws Exception {
        // ⚠️ 這一條**取代**了 #87 原本的 singleBlankNameIsStillAccepted ——
        // 那條測試刻意釘住「單一空白名目前回 200」這個現況。
        // 使用者 2026-09-30 裁決要擋，所以現況變了，測試必須跟著改；
        // 見類別 javadoc「本檔改了哪些測試」。
        //
        // 三個形狀在資料庫層面**都等於空字串**（ANSI padding 忽略尾端 U+0020、
        // 全形空白是寬度不敏感的同一個字），所以它們是同一個形狀的三種寫法：
        for (String blank : new String[]{"", "  ", "　"}) {
            String key = freshKey();

            var res = post(key, batch(spec(blank, "string", true), spec("amount", "string", true)));

            assertThat(res.statusCode())
                    .as("空白名稱 [" + blank + "] 裁決前回 200（真的存進了資料庫，"
                            + "而一個叫空白名的變數永遠比對不到外部系統送來的值）")
                    .isEqualTo(400);
            assertThat(res.body())
                    .as("訊息必須說清楚是「空白」而不是只說「你送錯了」—— "
                            + "required=true 的空白名會讓每一次外部發起都回 400，"
                            + "而管理頁上看不出任何設定有問題")
                    .contains("空白");
            assertThat(namesUnder(key)).as("被擋下的請求不得留下任何資料").isEmpty();
            assertThat(settledConfigChangeOf(key))
                    .as("被擋下的請求不得留下 CONFIG_CHANGE（一筆 replace 代表"
                            + "「這個變更發生了」，而實際上什麼都沒發生）")
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("#87-3：variableName 為 null → 400（裁決前是 500：撞 NOT NULL 約束）")
    void nullNameIsRejected() throws Exception {
        // 裁決前 PM 線上實測（2026-09-30）：null 回 500。500 的語意是
        // 「稍後重試」，而重試永遠不會成功（payload 沒變，約束照樣擋）。
        // 對呼叫端而言 null 與 "" 是同一類錯誤（沒有給名字），
        // 回不同的狀態碼等於要求它處理兩種形狀。
        String key = freshKey();

        var res = post(key, batch(nullNameSpec(), spec("amount", "string", true)));

        assertThat(res.statusCode())
                .as("裁決前這裡回 500（NOT NULL 約束），而 500 會讓呼叫端一直重試")
                .isEqualTo(400);
        assertThat(namesUnder(key)).isEmpty();
        assertThat(settledConfigChangeOf(key)).isEmpty();
    }

    @Test
    @DisplayName("#87-2：兩列空白名稱 → 400，且訊息講的是「空白」而不是「重複」")
    void twoBlankNamesReportBlanknessNotDuplication() throws Exception {
        // #87 原本就釘住「兩個空白名互相衝突 → 400」，那條斷言仍然成立；
        // 變的是**原因**（現在擋在空白規則上，不是撞唯一約束）。
        // 所以這一條不是重複的測試：它在釘「訊息講的是使用者真正要改的那件事」。
        // 兩個空字串之所以「重複」，只是因為兩者都沒有名字；
        // 叫使用者「把其中一個改名」是錯誤指引。
        String key = freshKey();

        var res = post(key, batch(spec("", "string", false), spec("", "string", true)));

        assertThat(res.statusCode()).isEqualTo(400);
        assertThat(res.body()).contains("空白");
        assertThat(res.body())
                .as("不得把這個形狀說成「重複」—— 修法是兩列都命名，不是改掉其中一列")
                .doesNotContain("不得重複");
        assertThat(namesUnder(key)).isEmpty();
    }

    @Test
    @DisplayName("#87-2：空白與重複同時存在時，兩個問題要一起回報")
    void blankAndDuplicateAreReportedTogether() throws Exception {
        // 只報第一個問題的話，使用者修完再按一次儲存才看到下一個 ——
        // 而「新增行」產生的本來就是空字串，兩種問題同時出現是最常見的形狀。
        String key = freshKey();

        var res = post(key, batch(spec("", "string", false),
                spec("Amount", "string", true), spec("amount", "string", false)));

        assertThat(res.statusCode()).isEqualTo(400);
        assertThat(res.body())
                .as("一次回報兩個問題，使用者才知道要改兩件事")
                .contains("Amount").contains("amount");
        assertThat(res.body())
                .as("必須指名是**哪一列**沒有名字 —— 缺陷期間的訊息只有"
                        + "「不得重複: （空白）／Amount／amount」，其中「（空白）」是"
                        + "碰撞說明的附帶產物，使用者拿它對不回畫面上的任何一列")
                .contains("第 1 列");
        assertThat(namesUnder(key)).isEmpty();
    }

    @Test
    @DisplayName("非空性：只由 TAB 組成的名稱必須照常儲存（判定用的是 dbComparisonKey，不是 isBlank()）")
    void tabOnlyNameIsStillAccepted() throws Exception {
        // 這一條與 singleBlankNameIsRejected 是成組的。少了它，一個
        // 「用 String.isBlank() 擋掉所有空白」的實作也能讓上面那些 400 全綠 ——
        // 但 isBlank() 走 Character.isWhitespace，會把 TAB、換行算成空白，
        // 而 #87 實測那些在資料庫裡**不是**空白（ANSI padding 只忽略尾端 U+0020）：
        // amount 與 "amount\t" 是兩個不同的變數。擋下等於禁止使用合法的名稱，
        // 而且前端不得擋掉後端會接受的資料（#87 建立的原則）。
        String key = freshKey();

        assertThat(post(key, batch(specWithEscapedTabName(), spec("amount", "string", true)))
                .statusCode())
                .as("實測 MSSQL：TAB 不是 ANSI padding，'\\t' 與 '' 是不同的字串")
                .isEqualTo(200);
        assertThat(namesUnder(key))
                .as("必須真的存進去 —— 只回 200 卻沒存是另一種缺陷")
                .containsExactlyInAnyOrder("\t", "amount");
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

    @Test
    @DisplayName("#87-2：含空白名的整批被拒時，既有規格一個欄位都不能被改")
    void blankNameBatchLeavesExistingSpecsUntouched() throws Exception {
        // 與上一條同型，但走的是**空白**規則而不是撞名規則：
        // 只驗過撞名那條的話，「空白檢查被放到 deleteAllBy… 之後」這個錯位
        // 就不會有人發現（兩條規則都在同一個位置，但只有一條有守門員時，
        // 下一個人把其中一條往下移是看不出來的）。
        String key = freshKey();
        assertThat(post(key, batch(spec("amount", "number", true, "AMOUNT-DESC", "100"),
                spec("reason", "string", false, "REASON-DESC", "because"))).statusCode())
                .as("前置條件：第一次儲存成功").isEqualTo(200);
        var before = statesUnder(key);
        int auditCountAfterSuccess = awaitConfigChangeCountOf(key, 1);

        // 第三列是「按了新增行還沒填名稱」——管理頁最常見的形狀，
        // 而它挾帶著「把 amount 放寬成非必填」的意圖。
        var res = post(key, batch(spec("amount", "string", false, "RELAXED-DESC", "HACKED"),
                spec("reason", "string", false, "REASON-DESC", "because"),
                spec("  ", "string", true, "BLANK-DESC", "x")));

        assertThat(res.statusCode()).isEqualTo(400);
        assertThat(statesUnder(key))
                .as("整批取代的端點若在驗證之前就刪除，這裡會變成空")
                .isEqualTo(before);
        assertThat(requiredOf(key, "amount"))
                .as("required 必須仍是 true —— 放寬外部系統的輸入驗證是這個端點"
                        + "真正的實質危害")
                .isTrue();

        Thread.sleep(300);
        assertThat(configChangeDetailsOfKey(key))
                .as("被拒的請求不得留下「這個變更發生了」的稽核紀錄")
                .hasSize(auditCountAfterSuccess)
                .allSatisfy(detail -> assertThat(detail)
                        .as("不得有記錄宣稱那個空白名已經被加進來")
                        .doesNotContain("BLANK-DESC"));
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
    @DisplayName("#87-2／#87-3：update 改名成空白／null → 400，且該筆規格一個欄位都不能被改")
    void updateRenamingToBlankNameIsRejected() throws Exception {
        // 只擋 POST 等於留下一個繞道：PUT 可以把既有變數改名成空白名，
        // 結果與允許建立空白名完全相同（空白名永遠比對不到外部系統送來的值）。
        String key = freshKey();
        assertThat(post(key, batch(spec("amount", "number", true, "AMOUNT-DESC", "100"),
                spec("reason", "string", false, "REASON-DESC", "because"))).statusCode())
                .as("前置條件：第一次儲存成功").isEqualTo(200);
        String amountId = idOf(key, "amount");
        var before = statesUnder(key);

        // 三種形狀：空字串、只有空白、JSON 的 null。前兩者裁決前回 200
        // （真的把 amount 改名成空白的），null 回 500（撞 NOT NULL）。
        for (String nameLiteral : new String[]{"\"\"", "\"  \"", "null"}) {
            var res = put(key, amountId, "{\"variableName\":" + nameLiteral
                    + ",\"variableType\":\"string\",\"required\":false,"
                    + "\"description\":\"BLANKED\",\"example\":\"BLANKED\"}");

            assertThat(res.statusCode())
                    .as("改名成 [" + nameLiteral + "] 必須是 400："
                            + "空白名是外部系統永遠比對不到的 key，而 null 裁決前是 500")
                    .isEqualTo(400);
            assertThat(statesUnder(key))
                    .as("被拒的改名不得留下任何副作用 —— 這一筆是整個流程裡"
                            + "唯一的必填變數，改名成空白等於刪掉它")
                    .isEqualTo(before);
            assertThat(requiredOf(key, "amount")).isTrue();
            assertThat(settledConfigChangeOfId(amountId))
                    .as("被擋下的請求不得留下稽核").isEmpty();
        }
    }

    @Test
    @DisplayName("#87-2／#85：key 不符**而且**名稱空白時必須是 404（守衛排在形狀檢查之前）")
    void keyMismatchOutranksBlankName() throws Exception {
        // 這條釘的是**順序**，不是擋不擋：#85 的 404 守衛（這筆資料存不存在、
        // 這個 key 是不是它的 key）必須排在 payload 形狀檢查之前。
        // 反過來的話，「key 對不上而且名字是空白」會回 400 而不是 404 ——
        // 那正是 handoff 第 7.1 節記下來要避開的狀態碼錯位，
        // 而且 Bean Validation 那條路徑（@NotBlank + @Valid）**必然**是錯的：
        // 它發生在 controller 方法之前，一定排在守衛前面。
        String realKey = freshKey();
        String otherKey = freshKey();
        assertThat(post(realKey, batch(spec("amount", "number", true, "AMOUNT-DESC", "100")))
                .statusCode()).as("前置條件：第一次儲存成功").isEqualTo(200);
        String amountId = idOf(realKey, "amount");
        var before = statesUnder(realKey);

        var res = put(otherKey, amountId, "{\"variableName\":\"  \",\"variableType\":\"string\","
                + "\"required\":false,\"description\":\"HIJACK\",\"example\":\"HIJACK\"}");

        assertThat(res.statusCode())
                .as("404 與 400 的差別正是「呼叫端該換 payload 還是該放棄這個目標」")
                .isEqualTo(404);
        assertThat(statesUnder(realKey)).isEqualTo(before);
        assertThat(namesUnder(otherKey))
                .as("另一個流程底下不得憑空長出一筆規格").isEmpty();
    }

    @Test
    @DisplayName("#87-2／#85：key 一致但名稱空白 → 400（守衛放行之後才輪到形狀檢查）")
    void keyMatchFallsThroughToTheBlankNameCheck() throws Exception {
        // 上一條的對照：payload 完全相同，只差 key 一致。
        // 少了它，「一律回 404」的實作也能讓上一條全綠 ——
        // 那等於 #85 的守衛擴張成「key 一律擋掉」，正常管理功能被打死。
        String key = freshKey();
        assertThat(post(key, batch(spec("amount", "number", true, "AMOUNT-DESC", "100")))
                .statusCode()).as("前置條件：第一次儲存成功").isEqualTo(200);
        String amountId = idOf(key, "amount");
        var before = statesUnder(key);

        var res = put(key, amountId, "{\"variableName\":\"  \",\"variableType\":\"string\","
                + "\"required\":false,\"description\":\"BLANKED\",\"example\":\"BLANKED\"}");

        assertThat(res.statusCode()).isEqualTo(400);
        assertThat(statesUnder(key))
                .as("被拒的改名不得留下任何副作用").isEqualTo(before);
        assertThat(settledConfigChangeOfId(amountId)).isEmpty();
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
