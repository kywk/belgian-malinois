package com.bpm.core.security;

import com.bpm.core.external.ApiKeyUtil;
import com.bpm.core.model.ExternalSystem;
import com.bpm.core.repository.ExternalSystemRepository;
import com.bpm.core.support.IntegrationTestBase;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #93：{@code firstTaskCandidateGroups} 送 <b>JSON 陣列</b> → 500。
 *
 * <h2>缺陷（修補前的實際行為）</h2>
 *
 * <pre>
 *   外部系統照 docs/bpm-platform-spec.md §9.2 送
 *   {"firstTaskCandidateGroups": ["hr_dept"], ...}
 *   → body 是 Map&lt;String,Object&gt;，所以這個欄位是 java.util.ArrayList
 *   → ExternalApiController: String firstGroups = (String) body.get(...)
 *   → ClassCastException → 裸 500（而且回應<b>沒有 message</b>）
 * </pre>
 *
 * <p><b>危害不是「壞掉」，是「永遠不會成功還一直重試」。</b>500 的語意是
 * 「稍後重試」，而外部系統的發起通常是計時批次 —— payload 不變就永遠不會成功，
 * 於是無限重試、log 被灌滿、真正的故障被蓋掉。而且 #73 刻意只回傳
 * 「刻意丟出的」訊息，{@code ClassCastException} 不是刻意丟出的，
 * 所以呼叫端連「你送錯形狀了」這句話都拿不到。
 *
 * <p><b>spec 與實作不一致是成因</b>：spec §9.2 示範的一直是陣列，
 * 後端卻只收字串。已上線的整合方照 spec 抄就會踩到。
 *
 * <h2>⚠️ 這條 cast 發生在<b>所有檢查之前</b></h2>
 *
 * <p>改動前第 98 行的 cast 排在授權檢查之前，所以一個
 * <b>完全未授權</b>的外部系統送陣列也會拿到 500 而不是 403 ——
 * 授權分支根本沒機會執行。
 *
 * <h2>⚠️ 狀態碼斷言走<b>真實 HTTP</b>而不是 MockMvc</h2>
 *
 * <p>{@code ResponseStatusException} 走 ERROR dispatch，MockMvc 不做那次 dispatch
 * （見 {@code ErrorDispatchTest} 與 {@code ExternalFirstTaskAssigneeTest}）。
 * 而本工項最危險的失敗型態是「狀態碼對了但流程還是啟動了」，所以除了狀態碼，
 * 每一條拒絕都另外驗<b>資料沒有變</b>（流程實例數與稽核筆數都不動）。
 *
 * <h2>⚠️ 每條形狀斷言都看「真的掛上去的 identity link」而不只是 200</h2>
 *
 * <p>{@link CanonicalArray} 與 {@link NonRegressions} 的每一條都斷言
 * {@code candidateGroupsOf(...)} 的內容。
 * 這是<b>刻意的</b>：一個「用 {@code String.valueOf()} 把整個值轉成字串再 split」
 * 的實作會讓陣列請求回 <b>200</b>，但掛上去的群組名會是
 * {@code "[dept001]"}（{@code ArrayList.toString()}）——
 * 一個沒有任何人是其成員的群組，也就是本工項要修的缺陷換個形狀又回來。
 * 只斷言狀態碼的測試會讓它綠。
 *
 * <h2>⚠️ 負向控制組實測（2026-10-01，原始碼未修改時跑本檔）</h2>
 *
 * <p><b>18 條中 13 紅 5 綠。</b>紅的 13 條全部是
 * {@code expected 200/403/400 but was 500}，也就是<b>缺陷本身</b>。
 *
 * <table border="1">
 *   <caption>負向控制組結果</caption>
 *   <tr><th>結果</th><th>測試</th><th>意義</th></tr>
 *   <tr><td>🔴 紅（13）</td>
 *       <td>{@link CanonicalArray} 全部 7 條<br>
 *           {@code nonStringElementIsRejected}、{@code wrongFieldTypeIsRejected}、<br>
 *           {@code emptyAndBlankArraysFallThroughToTheAtLeastOneRule}、<br>
 *           {@link Ordering#processKeyAuthorizationStillWins}、<br>
 *           {@link NonRegressions#assigneeValidationIsUnaffected}</td>
 *       <td>每一種陣列形狀都是 500。回應<b>沒有 message</b>（#73 只回傳
 *           刻意丟出的理由），所以呼叫端連「你送錯形狀了」都拿不到 ——
 *           那正是無限重試的成因。</td></tr>
 *   <tr><td>🟢 綠（5）</td>
 *       <td>{@link NonRegressions} 的 {@code commaSeparatedStringStillWorks}、
 *           {@code stringShapeStillForbiddenWhenUnlisted}、
 *           {@code fieldAbsentStillBehavesTheSame}；<br>
 *           {@code malformedJsonIsRejected}、{@code nullMeansUnspecified}</td>
 *       <td>前 3 條是刻意的<b>非回歸對照</b>：缺陷期間就該綠，修好之後
 *           <b>仍必須</b>綠 —— 一個「把陣列當非法形狀擋掉」或「根本沒接受
 *           陣列」的錯誤修法，能讓 13 條紅的變綠，卻會讓這 3 條紅。
 *           後 2 條綠是因為 malformedJson 由 Spring 自己擋、null 本來就
 *           等於未指定，兩者都沒碰到本次改動的那一行。</td></tr>
 * </table>
 *
 * <h3>⚠️「綠了哪幾條」揭露了什麼：三種錯誤修法都會讓本檔全綠</h3>
 *
 * <p>這是本檔最重要的部分 —— <b>「測試全綠」不等於「修法正確」</b>：
 *
 * <ol>
 *   <li><b>catch ClassCastException 一律回 400</b>（不做形狀解析）。
 *       13 條紅的<b>全部變綠</b>，因為它們都只要求「不是 500」。
 *       但陣列仍然不能用 —— 缺陷從「500 無限重試」換成「照 spec 抄
 *       永遠被拒」。抓它的是 {@link CanonicalArray} 那 7 條
 *       （要 200 <b>而且</b>要看到正確的 identity link）。</li>
 *   <li><b>{@code String.valueOf()} 整個值再 split</b>。
 *       陣列請求回 <b>200</b>（所以所有 400 測試都綠），但掛上去的群組名會是
 *       {@code "[dept001]"} —— 一個沒有任何人會是成員的群組，
 *       也就是本工項要修的缺陷換個形狀又回來。
 *       抓它的是每一條 {@code candidateGroupsOf(...)} 斷言，
 *       特別是 {@code singleElementArrayIsAccepted}。</li>
 *   <li><b>Jackson {@code convertValue(raw, List<String>.class)}</b>。
 *       Jackson 預設把數字<b>強制轉成字串</b>，於是 {@code ["dept001",123]}
 *       變成 {@code ["dept001","123"]} → 200，而
 *       {@code nonStringElementIsRejected} 會紅（它要 400）。
 *       這一條證明「默默轉型」不可接受：那會建立一個叫「123」的候選群組。</li>
 * </ol>
 *
 * <p>換句話說：<b>13 條紅的測試只證明「500 不見了」</b>；
 * 證明修法正確的是 {@link CanonicalArray} 與每一條 identity link 斷言。</p>
 *
 * <h3>⚠️ 負向控制組抓到的是<b>我自己的測試</b>寫錯，不是程式壞了</h3>
 *
 * <p>{@link Ordering} 裡我原本寫「群組授權（403）先於 initiator 冒用（400）」，
 * 實測是 {@code expected 403 but was 400}。回頭讀碼才發現
 * {@code ExternalApiController:89} 的 initiator 檢查是整個方法<b>最早</b>的一格，
 * <b>早於</b>群組白名單（:133）—— 是我把順序記錯了。
 * 已改成斷言真實順序（initiator 仍然最早）。
 * 記在這裡是因為<b>「測試紅了」不一定代表缺陷</b>，也可能代表測試的前提錯了。
 *
 * <h3>⚠️ 實測發現：identity link 的讀回順序<b>不穩定</b></h3>
 *
 * <p>{@code taskService.getIdentityLinksForTask} 回的順序沒有保證（底層沒有
 * ORDER BY）。實測同一組輸入 {@code ["dept001","hr:leave:approve"]} 在不同 run
 * 分別回 {@code [dept001, hr:leave:approve]} 與
 * {@code [hr:leave:approve, dept001]}，<b>而且與請求用的是哪一種形狀無關</b>。
 *
 * <p>所以比對多個群組<b>必須</b>用「不論順序」：
 * {@link CanonicalArray#arrayAndCommaSeparatedStringAgree} 一開始寫成
 * {@code isEqualTo(List)}，於是<b>時好時壞</b> —— 前兩次 run 綠、後面連三次紅。
 * <b>這種測試比沒有測試更糟</b>：它會訓練人忽略紅燈。
 * 既有 {@code ExternalCandidateGroupWhitelistTest} 早已對多元素用
 * {@code containsExactlyInAnyOrder}，就是同一個道理。
 *
 * <p>附帶 consequence：候選群組「保留書寫順序」這個性質
 * <b>從 identity link 讀不回來</b>，只能由
 * {@code ExternalActorGuardTest.returnsTheSameListItValidates} 在守衛那一層驗。
 *
 * <h3>為什麼每一條拒絕都驗「流程實例沒被建立」</h3>
 *
 * <p>只斷言狀態碼會被「先啟動流程、再回 400」完全騙過 —— 而那正是
 * 「留下沒有人能簽的案件」這個缺陷本身。本 repo 反覆出現「回 400／404
 * 但資料已被改掉」。所以每條拒絕都另外驗流程實例數與稽核筆數都沒變。
 */
class ExternalCandidateGroupShapeTest extends IntegrationTestBase {

    private static final String PLAIN_KEY = "sk-t93-shape-testkey";

    @Autowired
    private ExternalSystemRepository repo;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private TaskService taskService;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void seedExternalSystem() {
        repo.deleteAll();
    }

    /**
     * 建立一個外部系統。
     *
     * @param whitelist {@code allowedCandidateGroups}；null = 不限制
     *                  （與 {@code allowedProcessKeys} 同一條規則，見 ExternalSystemPolicy）
     */
    private ExternalSystem given(String whitelist) {
        ExternalSystem sys = new ExternalSystem();
        // 刻意不設 id（@GeneratedValue(strategy = UUID)；自行指定會走 merge）。
        sys.setSystemId("erp");
        sys.setSystemName("T93 形狀測試系統");
        sys.setApiKey(ApiKeyUtil.hash(PLAIN_KEY));
        sys.setAllowedActions("[\"start_process\"]");
        sys.setAllowedProcessKeys("[\"leave-approval\"]");
        sys.setAllowedCandidateGroups(whitelist);
        sys.setEnabled(true);
        sys.setCreatedAt(Instant.now());
        return repo.save(sys);
    }

    /** request body。{@code extra} 是要併進去的原始 JSON 片段（形狀由測試自己決定）。 */
    private static String body(String extra) {
        return "{\"processDefinitionKey\":\"leave-approval\","
                + "\"businessKey\":\"T93-" + UUID.randomUUID() + "\","
                + "\"variables\":{\"leaveType\":\"annual\",\"days\":1}"
                + (extra.isEmpty() ? "" : "," + extra) + "}";
    }

    private HttpResponse<String> post(String payload) throws Exception {
        var req = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + SERVLET_PORT
                                + "/api/external/process-instances"))
                .header("X-API-Key", PLAIN_KEY)
                .header("X-System-Id", "erp")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    // ── 斷言工具 ──────────────────────────────────────────────────────

    private long instances() {
        return runtimeService.createProcessInstanceQuery().count();
    }

    /** 外部 API 啟動流程的稽核筆數（用來驗「被拒的請求不留痕」）。 */
    private long startProcessAudits() {
        final long[] count = {0};
        withAuditConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COUNT(*) FROM bpm_audit_log WHERE operation_type = 'EXTERNAL_API_CALL'")) {
                var rs = ps.executeQuery();
                if (rs.next()) count[0] = rs.getLong(1);
            }
        });
        return count[0];
    }

    /** 第一關實際掛上的候選群組名稱（identity link），不是 request body 的值。 */
    private List<String> candidateGroupsOf(String pid) {
        return taskService.getIdentityLinksForTask(
                        taskService.createTaskQuery().processInstanceId(pid).singleResult().getId())
                .stream()
                .filter(l -> "candidate".equals(l.getType()))
                .map(l -> l.getGroupId())
                .toList();
    }

    private String pidOf(HttpResponse<String> res) {
        return res.body().replaceAll(".*\"processInstanceId\":\"([^\"]*)\".*", "$1");
    }

    /**
     * 斷言「被拒，而且<b>什麼都沒發生</b>」。
     *
     * <p>後兩個斷言才是重點。只斷言狀態碼的測試會被「先啟動流程、再下 400」
     * 完全騙過 —— 而那正是「留下沒有人能簽的案件」這個缺陷本身。
     */
    private void assertRejectedWithoutSideEffect(HttpResponse<String> res, int expectedStatus,
                                                 long instancesBefore, long auditsBefore) {
        assertThat(res.statusCode())
                .as("形狀錯誤是 payload 的問題 → 400（呼叫端該改 payload），"
                        + "不是 500：500 的語意是「稍後重試」，而 payload 不變就永遠不會成功")
                .isEqualTo(expectedStatus);
        assertThat(instances())
                .as("被拒的請求不得啟動流程")
                .isEqualTo(instancesBefore);
        assertThat(startProcessAudits())
                .as("被拒的請求不得留下「已發起」的稽核（不宣稱沒發生的變更）")
                .isEqualTo(auditsBefore);
    }

    // ── 缺陷本體：spec 示範的陣列形狀 ─────────────────────────────────

    @Nested
    @DisplayName("缺陷：JSON 陣列（spec §9.2 示範的 canonical 形狀）")
    class CanonicalArray {

        @Test
        @DisplayName("#93：[\"dept001\"] → 200，且第一關真的掛上 dept001")
        void singleElementArrayIsAccepted() throws Exception {
            // 這一條與下面每一條拒絕測試必須成組存在。少了它，一個
            // 「拒絕一切非字串」的實作（把 array 當成非法形狀擋掉）
            // 能讓整個 RejectedShapes 全綠 —— 而那正是缺陷換個方向重現：
            // 呼叫端照 spec 抄就永久無法發起流程。
            given(null);

            var res = post(body("\"firstTaskCandidateGroups\":[\"dept001\"]"));

            assertThat(res.statusCode())
                    .as("spec §9.2 示範的就是陣列形狀，它必須可用")
                    .isEqualTo(200);
            assertThat(candidateGroupsOf(pidOf(res)))
                    .as("必須真的掛上呼叫端指定的群組名，"
                            + "而不是 ArrayList.toString() 的 \"[dept001]\"")
                    .containsExactly("dept001");
        }

        @Test
        @DisplayName("#93：多元素陣列 → 200，且每個元素都掛上去")
        void multiElementArrayIsAccepted() throws Exception {
            // 陣列與逗號分隔字串必須產生<b>同一組</b>候選群組 ——
            // 那是「規則只有一份」的實際意義：形狀不同，結果不能不同。
            given(null);

            var res = post(body("\"firstTaskCandidateGroups\":[\"dept001\",\"hr:leave:approve\"]"));

            assertThat(res.statusCode()).isEqualTo(200);
            assertThat(candidateGroupsOf(pidOf(res)))
                    .as("每個元素都要成為一個群組，不能被 join 成單一字串")
                    .containsExactlyInAnyOrder("dept001", "hr:leave:approve");
        }

        @Test
        @DisplayName("#93：⚠️ 陣列元素照樣走白名單（授權不會因為換形狀而失效）")
        void arrayElementsStillGoThroughTheWhitelist() throws Exception {
            // 這是本工項最重要的安全斷言。若實作只對字串形狀做白名單比對
            // （例如「陣列就直接放行」），一個未授權的系統就能用陣列把案件
            // 丟進任意特權群組 —— 那正是 #88 政策 B 要根治的越權。
            given("[\"dept001\"]");
            long before = instances(), audits = startProcessAudits();

            var res = post(body("\"firstTaskCandidateGroups\":[\"dept001\",\"hr:leave:approve\"]"));

            assertRejectedWithoutSideEffect(res, 403, before, audits);
            assertThat(res.body())
                    .as("403 的訊息必須指名那個未授權的群組（與字串形狀同一句話）")
                    .contains("hr:leave:approve").contains("allowedCandidateGroups");
        }

        @Test
        @DisplayName("#93：陣列與逗號分隔字串必須得到完全相同的結果")
        void arrayAndCommaSeparatedStringAgree() throws Exception {
            // 「規則只有一份」的跨形狀對照：同一組群組名，兩種形狀，
            // 必須掛上完全一樣的 identity link。這條抓的是
            // 「陣列路徑少做了 trim」或「少走了某個步驟」這類分岔。
            //
            // ⚠️ 斷言訊息刻意<b>附上回應 body</b>：這條在第一次跑時曾間歇性
            // 失敗（expected 200 but was 失敗），而只有狀態碼的訊息看不出
            // 到底是哪一個形狀壞掉、伺服器說了什麼。狀態碼斷言附上 body
            // 是本 repo 的既有慣例（見 ExternalFirstTaskAssigneeTest）。
            given(null);

            var viaArray = post(body("\"firstTaskCandidateGroups\":[\"dept001\",\"hr:leave:approve\"]"));
            var viaString = post(body("\"firstTaskCandidateGroups\":\"dept001,hr:leave:approve\""));

            assertThat(viaArray.statusCode())
                    .as("陣列形狀必須 200；body=%s", viaArray.body()).isEqualTo(200);
            assertThat(viaString.statusCode())
                    .as("相容形狀必須仍然可用（已上線的整合方在送字串）；body=%s", viaString.body())
                    .isEqualTo(200);
            // ⚠️ 必須用「不論順序」比對：getIdentityLinksForTask 回的順序
            // 不穩定（底層沒有 ORDER BY），這是實測出來的 —— 同一組輸入
            // 在不同 run 回 ["dept001","hr:leave:approve"] 與
            // ["hr:leave:approve","dept001"]，而且與哪個形狀無關。
            // 寫成 isEqualTo(List) 會得到一個「時好時壞」的測試，
            // 而這種測試比沒有測試更糟（它會讓人開始忽略紅燈）。
            // 既有 ExternalCandidateGroupWhitelistTest 也是這樣比對的。
            //
            // 順帶記錄一件事：候選群組「保留書寫順序」這個性質
            // <b>從 identity link 讀不回來</b>，所以它只能靠
            // ExternalActorGuardTest.returnsTheSameListItValidates
            // 在守衛那一層驗。
            assertThat(candidateGroupsOf(pidOf(viaArray)))
                    .as("pid=%s", pidOf(viaArray))
                    .containsExactlyInAnyOrderElementsOf(candidateGroupsOf(pidOf(viaString)));
        }

        @Test
        @DisplayName("#93：空白字串元素被丟棄（與字串形狀同一條規則）")
        void blankArrayElementIsDropped() throws Exception {
            given(null);

            var res = post(body("\"firstTaskCandidateGroups\":[\"dept001\",\"  \"]"));

            assertThat(res.statusCode()).isEqualTo(200);
            assertThat(candidateGroupsOf(pidOf(res)))
                    .as("不得寫出空字串的 identity link —— 它會讓 UnreachableTaskListener"
                            + "誤判為「有候選人」而對真的沒有人能簽的情況不告警")
                    .containsExactly("dept001");
        }

        @Test
        @DisplayName("#93：重複元素去重（與字串形狀同一條規則）")
        void duplicateArrayElementsAreDeduplicated() throws Exception {
            given(null);

            var res = post(body("\"firstTaskCandidateGroups\":[\"dept001\",\"dept001\"]"));

            assertThat(res.statusCode()).isEqualTo(200);
            assertThat(candidateGroupsOf(pidOf(res)))
                    .as("同樣的群組只寫一次；結果因此與書寫順序無關")
                    .containsExactly("dept001");
        }

        @Test
        @DisplayName("#93：同時給 assignee 與陣列群組時，兩者都照送")
        void assigneeAndArrayCoexist() throws Exception {
            given(null);

            var res = post(body("\"firstTaskAssignee\":\"mgr001\","
                    + "\"firstTaskCandidateGroups\":[\"dept001\"]"));

            assertThat(res.statusCode()).isEqualTo(200);
            String pid = pidOf(res);
            assertThat(taskService.createTaskQuery().processInstanceId(pid)
                    .singleResult().getAssignee()).isEqualTo("mgr001");
            assertThat(candidateGroupsOf(pid)).containsExactly("dept001");
        }
    }

    // ── 邊界形狀：每一種都必須 400 且不啟動流程 ───────────────────────

    @Nested
    @DisplayName("邊界形狀：非法的 firstTaskCandidateGroups 一律 400，且不啟動流程")
    class RejectedShapes {

        @Test
        @DisplayName("#93：元素非字串 → 400，訊息指名是哪一個元素、什麼型別")
        void nonStringElementIsRejected() throws Exception {
            // 訊息必須指名<b>索引</b>與<b>型別</b>。只說「格式錯誤」會讓
            // 呼叫端一個欄位一個欄位試錯；「你送了很多群組」則不知道是哪一個。
            //
            // ⚠️ given() 只在迴圈外呼叫一次：systemId 有 UNIQUE 約束
            // （@BeforeEach 的 deleteAll 只在每個測試開始時跑一次）。
            given(null);
            long before = instances(), audits = startProcessAudits();

            for (String shape : new String[]{
                    "[\"dept001\",123]",       // 數字
                    "[\"dept001\",true]",      // 布林
                    "[\"dept001\",{}]",        // 物件
                    "[\"dept001\",[\"x\"]]",   // 巢狀陣列
                    "[\"dept001\",null]"}) {   // null 元素
                var res = post(body("\"firstTaskCandidateGroups\":" + shape));

                assertRejectedWithoutSideEffect(res, 400, before, audits);
                assertThat(res.body())
                        .as("必須指名「第二個元素」不是字串: " + shape)
                        .contains("1");
            }
        }

        @Test
        @DisplayName("#93：整個欄位型別錯（數字／布林／物件）→ 400")
        void wrongFieldTypeIsRejected() throws Exception {
            // 這三種在改動前全都是 500（cast 失敗），而且 cast 發生在
            // <b>所有檢查之前</b> —— 連授權都還沒查。
            //
            // ⚠️ given() 只在迴圈外呼叫一次（systemId 有 UNIQUE 約束）。
            given(null);
            long before = instances(), audits = startProcessAudits();

            for (String shape : new String[]{"123", "true", "{}", "1.5"}) {
                var res = post(body("\"firstTaskCandidateGroups\":" + shape));

                assertRejectedWithoutSideEffect(res, 400, before, audits);
                assertThat(res.body())
                        .as("必須指名欄位名，呼叫端才知道要改哪裡: " + shape)
                        .contains("firstTaskCandidateGroups");
            }
        }

        @Test
        @DisplayName("#93：JSON 語法壞掉 → 400（不是 500）")
        void malformedJsonIsRejected() throws Exception {
            // 邊界的邊界：body 本身不是合法 JSON。這條由 Spring 的
            // HttpMessageNotReadableException 處理，不走本工項的解析器，
            // 放在這裡是為了記錄「它也必須是 400」這個事實。
            given(null);
            long before = instances(), audits = startProcessAudits();

            var req = HttpRequest.newBuilder(
                            URI.create("http://localhost:" + SERVLET_PORT
                                    + "/api/external/process-instances"))
                    .header("X-API-Key", PLAIN_KEY)
                    .header("X-System-Id", "erp")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "{\"processDefinitionKey\":\"leave-approval\","
                                    + "\"firstTaskCandidateGroups\":[\"dept001\""))
                    .build();
            var res = http.send(req, HttpResponse.BodyHandlers.ofString());

            assertRejectedWithoutSideEffect(res, 400, before, audits);
        }

        @Test
        @DisplayName("#93：[] 與只有空白 → 沿用「至少有一個」規則的 400")
        void emptyAndBlankArraysFallThroughToTheAtLeastOneRule() throws Exception {
            // ⚠️ 這兩條刻意<b>不</b>由形狀解析直接拒絕：
            //   []       → 解析後是空清單 → 被 startProcess:220 的
            //               「至少有一個」規則回 400（那是 #88 刻意做的既有規則）
            //   ["  "]   → 空白元素被丟棄 → 同樣落到那條規則
            //
            // 為什麼不新增一條「空陣列要拒絕」的規則：那就是同一條規則
            // 兩套形狀（#84／#86 的成因）。既有規則只要看到「解析後沒有群組」
            // 就會拒絕，新增一條只是在旁邊再寫一份。
            // 訊息仍必須指向 firstTaskCandidateGroups，呼叫端才知道要補什麼。
            //
            // ⚠️ given() 只在迴圈外呼叫一次（systemId 有 UNIQUE 約束）。
            given(null);
            long before = instances(), audits = startProcessAudits();

            // ⚠️ 最後一個 " , ,," 是<b>相容形狀</b>的同一個情境（只送分隔符），
            // 證明兩種形狀落到同一條既有規則、同一句錯誤訊息 ——
            // 而不是陣列走一套規則、字串走另一套。
            for (String shape : new String[]{"[]", "[\"  \"]", "[\"\",\"\"]", "\" , ,\""}) {
                var res = post(body("\"firstTaskCandidateGroups\":" + shape));

                assertRejectedWithoutSideEffect(res, 400, before, audits);
                assertThat(res.body())
                        .as("訊息必須指向「必須指定受理人或群組」這條規則: " + shape)
                        .contains("firstTaskCandidateGroups");
            }
        }

        @Test
        @DisplayName("#93：null（未指定）→ 不啟動流程，且訊息來自「至少有一個」規則")
        void nullMeansUnspecified() throws Exception {
            // null 沿用現況（= 未指定），不是錯誤。它和「欄位不存在」不可區分，
            // 而呼叫端兩種都會送。
            given(null);
            long before = instances(), audits = startProcessAudits();

            var res = post(body("\"firstTaskCandidateGroups\":null"));

            assertRejectedWithoutSideEffect(res, 400, before, audits);
            assertThat(res.body()).contains("firstTaskCandidateGroups");
        }
    }

    // ── 順序：授權先決（#80 建立的防枚舉順序不可被新檢查蓋掉）─────────

    @Nested
    @DisplayName("順序：新的形狀檢查必須排在兩個 403 之後")
    class Ordering {

        @Test
        @DisplayName("#93：⚠️ 流程 key 未授權 → 403，即使陣列形狀也是非法的")
        void processKeyAuthorizationStillWins() throws Exception {
            // 這是本工項最容易被寫錯的一格。若把形狀解析排在
            // allowedProcessKeys 檢查<b>之前</b>，一個只被授權
            // leave-approval 的系統就能用「403 變 400」探測出伺服器上
            // 部署了哪些流程定義 —— 那等於把授權檢查變成枚舉工具，
            // 正是 #80 建立這個順序的原因。
            //
            // ⚠️ 改動前這個請求回的是 <b>500</b>（cast 在所有檢查之前），
            // 所以這條同時是「cast 位置錯了」的證據。
            repo.deleteAll();
            ExternalSystem sys = new ExternalSystem();
            sys.setSystemId("erp");
            sys.setSystemName("T93 順序測試系統");
            sys.setApiKey(ApiKeyUtil.hash(PLAIN_KEY));
            sys.setAllowedActions("[\"start_process\"]");
            sys.setAllowedProcessKeys("[\"purchase-approval\"]");
            sys.setEnabled(true);
            sys.setCreatedAt(Instant.now());
            repo.save(sys);
            long before = instances();

            var res = post(body("\"firstTaskCandidateGroups\":[\"dept001\",123]"));

            assertThat(res.statusCode())
                    .as("授權檢查必須先於形狀檢查，否則 400/403 的差異成了部署清單的探測工具")
                    .isEqualTo(403);
            assertThat(instances()).isEqualTo(before);
        }

        @Test
        @DisplayName("#93：initiator 冒用的 400 仍是最早的那一格（形狀檢查不得插到它前面）")
        void initiatorRejectionStillComesFirst() throws Exception {
            // ⚠️ 這一條的順序是<b>讀碼確認</b>的，不是推想的：
            // initiator 冒用的檢查在 ExternalApiController:89，是整個方法
            // 最早的一格，<b>早於</b>群組白名單（:133）。我第一版把這裡寫成
            // 「群組授權先於 initiator」，負向控制組立刻用
            // 「expected 403 but was 400」把它抓出來 —— 測試寫錯了，
            // 而不是程式壞了。
            //
            // 這裡斷言的是真正的不變式：形狀檢查<b>不得</b>插到 initiator
            // 檢查前面。插到前面的話，一個冒用 initiator 又送錯形狀的請求
            // 會拿到「你的群組格式錯了」—— 呼叫端去改一個根本不是
            // 問題來源的欄位，而真正的問題（冒用 server 鑄造的身分）被蓋掉。
            given("[\"dept001\"]");
            long before = instances();

            var res = post(body("\"initiator\":\"someone-else\","
                    + "\"firstTaskCandidateGroups\":[\"hr:leave:approve\"]"));

            assertThat(res.statusCode()).isEqualTo(400);
            assertThat(res.body())
                    .as("必須是 initiator 那一句，而不是群組形狀或白名單那一句")
                    .contains("initiator");
            assertThat(instances()).isEqualTo(before);
        }
    }

    // ── 對照組：相容形狀與其他欄位不受影響 ────────────────────────────

    @Nested
    @DisplayName("對照組：相容形狀與既有行為一律不變（不得「為了修 array 弄壞別的」）")
    class NonRegressions {

        @Test
        @DisplayName("#93：相容形狀 \"dept001,hr:leave:approve\" 仍然 200")
        void commaSeparatedStringStillWorks() throws Exception {
            given(null);

            var res = post(body("\"firstTaskCandidateGroups\":\"dept001,hr:leave:approve\""));

            assertThat(res.statusCode())
                    .as("已上線的整合方送的是逗號分隔字串，不可被這次改動弄壞")
                    .isEqualTo(200);
            assertThat(candidateGroupsOf(pidOf(res)))
                    .containsExactlyInAnyOrder("dept001", "hr:leave:approve");
        }

        @Test
        @DisplayName("#93：相容形狀的白名單 403 不可變")
        void stringShapeStillForbiddenWhenUnlisted() throws Exception {
            given("[\"dept001\"]");
            long before = instances(), audits = startProcessAudits();

            var res = post(body("\"firstTaskCandidateGroups\":\"dept001,hr:leave:approve\""));

            assertRejectedWithoutSideEffect(res, 403, before, audits);
        }

        @Test
        @DisplayName("#93：完全不給候選群組欄位時行為不變")
        void fieldAbsentStillBehavesTheSame() throws Exception {
            given(null);

            var res = post(body("\"firstTaskAssignee\":\"mgr001\""));

            assertThat(res.statusCode()).isEqualTo(200);
            assertThat(candidateGroupsOf(pidOf(res)))
                    .as("沒給就不得凭空生出候選群組")
                    .isEmpty();
        }

        @Test
        @DisplayName("#93：firstTaskAssignee 仍然照 org 查詢驗證（本次改動不碰它）")
        void assigneeValidationIsUnaffected() throws Exception {
            // 本工項只改 firstTaskCandidateGroups 的<b>形狀解析</b>。
            // 這一條確認沒有為了統一兩個欄位的解析而動到另一個欄位的規則 ——
            // 那會讓 firstTaskAssignee 的錯誤訊息或狀態碼悄悄改變。
            given(null);
            long before = instances(), audits = startProcessAudits();

            var res = post(body("\"firstTaskAssignee\":\"nobody-" + UUID.randomUUID() + "\","
                    + "\"firstTaskCandidateGroups\":[\"dept001\"]"));

            assertThat(res.statusCode()).isEqualTo(400);
            assertThat(instances()).isEqualTo(before);
            assertThat(startProcessAudits()).isEqualTo(audits);
        }
    }
}
