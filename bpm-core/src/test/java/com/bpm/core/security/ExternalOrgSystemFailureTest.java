package com.bpm.core.security;

import com.bpm.core.external.ApiKeyUtil;
import com.bpm.core.model.ExternalSystem;
import com.bpm.core.repository.ExternalSystemRepository;
import com.bpm.core.support.IntegrationTestBase;
import org.flowable.engine.RuntimeService;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #88 政策 A：組織系統<b>故障</b>回 503、<b>查無此人</b>回 400，兩者分開。
 *
 * <h2>為什麼狀態碼斷言要走真實 HTTP</h2>
 *
 * <p>本專案的教訓（{@code ErrorDispatchTest}）：{@code ResponseStatusException}
 * 走 ERROR dispatch，而 MockMvc 不做那次 dispatch —— 用 MockMvc 寫出來的
 * 狀態碼斷言在缺陷存在時照樣是綠的。503 是本專案<b>新引進</b>在這條路徑上的
 * 狀態碼，所以必須走真的。
 *
 * <h2>⚠️ 兩條路徑必須分開測（否則測不出它們有沒有混為一談）</h2>
 *
 * <table border="1">
 *   <caption>兩種組織查詢失敗</caption>
 *   <tr><th>情境</th><th>怎麼製造</th><th>預期</th></tr>
 *   <tr><td>查無此人</td><td>送一個 fixture 裡沒有的 id → mock 丟 404</td>
 *       <td><b>400</b>，呼叫端該改 payload</td></tr>
 *   <tr><td>組織系統故障</td><td>送 {@code fault-503}／{@code fault-500} → mock 丟 5xx</td>
 *       <td><b>503</b>，呼叫端該重試</td></tr>
 * </table>
 *
 * <p>兩者都是「守衛拒絕發起流程」，所以只寫一條測試（只斷言「不是 200」）
 * 會讓<b>兩種實作都通過</b>。這裡每一條都精確斷言狀態碼。
 *
 * <h2>⚠️ 每條拒絕都驗「什麼都沒發生」</h2>
 *
 * <p>只斷言狀態碼的測試會被「先啟動流程、再丟 503」完全騙過 ——
 * 而那正是 #88 要修的缺陷本身（案件已經存在，只是沒有人能簽）。
 * 所以每一條都另外驗流程實例數與稽核筆數都沒變。
 *
 * <h2>故障形狀是怎麼製造的（以及為什麼不用全域開關）</h2>
 *
 * <p>{@code MockOrgController} 對 {@code fault-503}／{@code fault-500} 這兩個
 * id 刻意丟 5xx。選「特殊 id」而不是「全域開關」的理由寫在該類別的註解裡：
 * 所有整合測試共用同一個 Spring context 與同一個 Tomcat，有狀態的開關只要
 * 有一個測試在結束前失敗就會污染後續所有測試。
 *
 * <p>⚠️ <b>「連線逾時／無法連線」不在本檔</b>：它需要 mock 睡到超過
 * {@code read-timeout-ms}，等於讓測試的有效性依賴呼叫端的逾時設定。
 * 那個形狀由 {@code ExternalActorGuardTest} 以 {@code ResourceAccessException}
 * 直接驗證 —— 那也是它唯一能被精確產生的方式。
 */
class ExternalOrgSystemFailureTest extends IntegrationTestBase {

    private static final String PLAIN_KEY = "sk-t88-failure-testkey";

    @Autowired
    private ExternalSystemRepository repo;

    @Autowired
    private RuntimeService runtimeService;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void seedExternalSystem() {
        repo.deleteAll();
    }

    private ExternalSystem given(boolean allowOnBehalfOf) {
        ExternalSystem sys = new ExternalSystem();
        sys.setSystemId("erp");
        sys.setSystemName("T88 故障測試系統");
        sys.setApiKey(ApiKeyUtil.hash(PLAIN_KEY));
        sys.setAllowedActions("[\"start_process\"]");
        sys.setAllowedProcessKeys("[\"leave-approval\"]");
        sys.setAllowOnBehalfOf(allowOnBehalfOf);
        sys.setEnabled(true);
        sys.setCreatedAt(Instant.now());
        return repo.save(sys);
    }

    private static String body(String extraFields) {
        return "{\"processDefinitionKey\":\"leave-approval\","
                + "\"businessKey\":\"T88F-" + UUID.randomUUID() + "\","
                + "\"variables\":{\"leaveType\":\"annual\",\"days\":1}"
                + (extraFields.isEmpty() ? "" : "," + extraFields) + "}";
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

    private long instances() {
        return runtimeService.createProcessInstanceQuery().count();
    }

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

    /**
     * 斷言「被拒，而且<b>什麼都沒發生</b>」。
     *
     * <p>後兩個斷言才是重點 —— 只斷言狀態碼會被「先啟動再丟錯」騙過。
     */
    private void assertRejectedWithoutSideEffect(HttpResponse<String> res, int expectedStatus,
                                                 long instancesBefore, long auditsBefore) {
        assertThat(res.statusCode())
                .as("組織查詢失敗的兩種語意必須分開（見類別註解）")
                .isEqualTo(expectedStatus);
        assertThat(instances())
                .as("被拒的請求不得啟動流程（擋在啟動之後就會留下沒有人能簽的案件）")
                .isEqualTo(instancesBefore);
        assertThat(startProcessAudits())
                .as("被拒的請求不得留下「已發起」的稽核（不宣稱沒發生的變更）")
                .isEqualTo(auditsBefore);
    }

    // ── 故障 → 503 ─────────────────────────────────────────────────

    @Nested
    @DisplayName("組織系統故障 → 503（暫時性，呼叫端該重試）")
    class Failure {

        @Test
        @DisplayName("#88：firstTaskAssignee 指向故障中的組織系統 → 503 且不啟動流程")
        void assigneeLookupFailsWith503() throws Exception {
            given(false);
            long before = instances(), audits = startProcessAudits();

            var res = post(body("\"firstTaskAssignee\":\"fault-503\""));

            assertRejectedWithoutSideEffect(res, 503, before, audits);
            assertThat(res.body())
                    .as("訊息必須說明這是暫時性問題、且重試不會產生重複案件 —— "
                            + "只看狀態碼的呼叫端無從判斷該不該重試")
                    .contains("組織系統目前無法查詢")
                    .contains("請稍後以相同的參數重試")
                    .contains("本次請求未建立任何流程實例");
        }

        @Test
        @DisplayName("#88：組織系統回 500 與 503 都必須映射成 503（不一樣的故障，同一個答案）")
        void bothServerErrorsMapTo503() throws Exception {
            // 為什麼要兩種：真實世界的「故障」不是一個固定的狀態碼，
            // 而呼叫端要的是「這是暫時性的、請重試」這一個判斷。
            given(false);
            long before = instances(), audits = startProcessAudits();

            for (String faultId : new String[]{"fault-503", "fault-500"}) {
                var res = post(body("\"firstTaskAssignee\":\"" + faultId + "\""));
                assertRejectedWithoutSideEffect(res, 503, before, audits);
            }
        }

        @Test
        @DisplayName("#88：⚠️ onBehalfOf 走同一條規則 —— 故障時也是 503")
        void onBehalfOfLookupAlsoMapsTo503() throws Exception {
            // 為什麼重要：onBehalfOf 的驗證原本是 startProcess 裡的一段 inline
            // try/catch，#88 把它收進共用規則。若收斂時只改了 firstTaskAssignee
            // 的那一條，這條會留在舊的「全部 400」行為上，而呼叫端看到
            // 「代發失敗是永久錯誤」就不會重試 —— 規則有兩套形狀。
            given(true);
            long before = instances(), audits = startProcessAudits();

            var res = post(body("\"onBehalfOf\":\"fault-500\""));

            assertRejectedWithoutSideEffect(res, 503, before, audits);
        }

        @Test
        @DisplayName("#88：組織系統回 401（我們沒有被允許查）→ 503，不是 400")
        void upstreamAuthFailureIsNotA400() throws Exception {
            // 401 描述的是我們的設定有問題，不是「這個人不存在」。
            // 回 400 會讓呼叫端去改一個根本不是問題來源的欄位。
            given(false);
            long before = instances(), audits = startProcessAudits();

            var res = post(body("\"firstTaskAssignee\":\"fault-401\""));

            assertRejectedWithoutSideEffect(res, 503, before, audits);
        }
    }

    // ── 拒絕 → 400 ─────────────────────────────────────────────────

    @Nested
    @DisplayName("組織系統查無此人 → 400（payload 的問題，呼叫端該改參數）")
    class Rejection {

        @Test
        @DisplayName("#88：未知員工 → 400（對照組：與 503 那組形狀不同、分開驗）")
        void unknownEmployeeIs400() throws Exception {
            given(false);
            long before = instances(), audits = startProcessAudits();

            var res = post(body("\"firstTaskAssignee\":\"nobody-" + UUID.randomUUID() + "\""));

            assertRejectedWithoutSideEffect(res, 400, before, audits);
            assertThat(res.body())
                    .as("400 的訊息不得混入「請重試」—— 那會讓呼叫端對一個"
                            + "永遠不會成功的請求一直重試")
                    .doesNotContain("請稍後以相同的參數重試");
        }

        @Test
        @DisplayName("#88：⚠️ onBehalfOf 查無此人仍然是 400（不得被 503 的改動連帶改掉）")
        void onBehalfOfUnknownEmployeeStays400() throws Exception {
            // 這是本次改動最容易犯的錯：把整個 catch 改成 503，
            // 於是「查無此人」也變成 503 —— 而 503 會讓批次一直重試
            // 一個 payload 永遠錯的請求。
            given(true);
            long before = instances(), audits = startProcessAudits();

            var res = post(body("\"onBehalfOf\":\"nobody-" + UUID.randomUUID() + "\""));

            assertRejectedWithoutSideEffect(res, 400, before, audits);
        }
    }

    // ── 對照組：同一組參數，正常時必須仍然啟動 ────────────────────

    @Nested
    @DisplayName("對照組：組織系統正常時必須仍然放行（不得「查不到就全部拒絕」）")
    class Passes {

        @Test
        @DisplayName("#88：一般的員工 mgr001 → 200（故障與否都與他無關）")
        void normalEmployeeStillStarts() throws Exception {
            // 少了這一條，一個「只要組織系統有任何異常就拒絕」的實作
            // 能讓上面所有測試全綠 —— 而它會讓正常的外部整合完全不能用。
            given(false);

            var res = post(body("\"firstTaskAssignee\":\"mgr001\""));

            assertThat(res.statusCode())
                    .as("組織系統正常時不得被這次改動影響")
                    .isEqualTo(200);
        }

        @Test
        @DisplayName("#88：鏈頂主管 dir001（存在但沒有主管）→ 200")
        void chainTopStillStarts() throws Exception {
            // 「回傳 null」是「他存在但沒有主管」的事實，不是查不到。
            // 把它當成查不到的實作會擋掉總監本人。
            given(false);

            assertThat(post(body("\"firstTaskAssignee\":\"dir001\"")).statusCode())
                    .isEqualTo(200);
        }
    }
}