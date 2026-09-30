package com.bpm.core.security;

import com.bpm.core.external.ApiKeyUtil;
import com.bpm.core.model.ExternalSystem;
import com.bpm.core.repository.ExternalSystemRepository;
import com.bpm.core.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #80：{@code ExternalApiController} 的啟動路徑 —— #69 的同一個洞在此仍未修。
 *
 * <h2>它確實屬於本工項：backlog #69 的條目自己指名的</h2>
 *
 * <p>{@code docs/backend-development-backlog.md} 的 #69 列末句寫著
 * 「{@code ExternalApiController} 的同一個洞未修（見 #80）」。
 * 也就是說這不是<b>順手</b>擴大範圍，而是前一輪做完之後<b>刻意留下</b>的
 * 待辦 —— 留下它的理由正是「#80 會碰到同一條規則」。
 *
 * <h2>缺陷（修補前的實際行為）</h2>
 *
 * <p>{@code #69} 在 {@code ProcessController.startProcess} 做了兩件事：
 * <ol>
 *   <li>key 為 null／空字串 → 400（請求不完整）</li>
 *   <li>key 有值但查不到定義 → 404，並保留對
 *       {@code FlowableObjectNotFoundException} 的轉譯以補 race window</li>
 * </ol>
 *
 * <p>而 {@code ExternalApiController.startProcess} 做了第 1 件事
 * （{@code if (processDefKey == null || processDefKey.isBlank()) → 400}），
 * <b>第 2 件完全沒有</b>：它直接呼叫 {@code startProcessInstanceByKey}，
 * 而那是個 {@code @Transactional} 方法，於是查不到的 key 會讓整個交易
 * rollback 並拋出 {@code FlowableObjectNotFoundException} → <b>裸 500</b>。
 *
 * <h2>危害：500 會教外部系統做錯事，而外部系統通常是批次</h2>
 *
 * <p>500 的慣例語意是「伺服器的問題，稍後重試」。ERP／HR 系統接上批次排程後，
 * 看到 500 的直覺就是「丟回重試佇列」—— 而重試<b>永遠不會成功</b>
 * （payload 沒變，結果就不會變）。於是一個打錯的流程 key 會變成
 * <b>無限重試的來源</b>，而且每一次重試都在伺服器上留下一筆
 * {@code ExternalApiAuthFilter} 的 {@code lastUsedAt} 寫入。
 *
 * <p>這與 #69 在 {@code ProcessController} 記錄的「使用者看到 500 的直覺是
 * 『再按一次』，於是重送造成重複案件」是同一個失效型態，只是受害���換成機器。
 *
 * <h2>⚠️ 為什麼預先檢查放在 403（allowedProcessKeys）<b>之後</b></h2>
 *
 * <p>若順序顛倒，一個只被授權 {@code leave-approval} 的外部系統就能用
 * 「403 變成 404」這個變化<b>枚舉伺服器上部署了哪些流程定義</b> ——
 * 那等於把授權檢查變成一個 discovery 工具。授權先決，規則才有唯一的落點。
 *
 * <h2>⚠️ 為什麼仍保留 catch（race window）</h2>
 *
 * <p>預先檢查與真正啟動之間，定義真的可能被管理員刪掉（部署與外部請求同時發生）。
 * 那是預檢消除不了的競爭窗口，沒有這一層它就會變回 500。
 *
 * <h2>狀態碼走真實 HTTP</h2>
 *
 * <p>{@code ResponseStatusException} 走 ERROR dispatch，MockMvc 不做那次 dispatch
 * （見 {@link ErrorDispatchTest}）。缺陷期間這裡是 500，兩者都不會被 MockMvc 翻面，
 * 但若同時壞掉的是 {@code SecurityConfig} 的
 * {@code dispatcherTypeMatchers(ERROR)} 規則，MockMvc 測試會照樣全綠。
 * 因此本類別的狀態碼斷言走真實 HTTP（見 {@link #startOverRealHttp}）。
 *
 * <h2>非空斷言：每條拒絕都配一條同樣參數的放行對照</h2>
 *
 * <p>{@link #existingProcessStillStarts} 是 {@link #unknownButAllowedProcessKeyIsNotFound}
 * 的放行對照 —— 少了它，「預先檢查寫錯（例如比對 deploymentId 而非 key）」
 * 會讓整組測試全綠而所有正常發起都變 404。
 *
 * <h2>負向控制組實測（把 {@code ExternalApiController} 整份還原成 HEAD）</h2>
 *
 * <p><b>4 條中 1 條紅</b>，而且紅的正是 {@link #unknownButAllowedProcessKeyIsNotFound}
 * —— 也就是缺陷本身。另外 3 條綠，這是<b>預期</b>且必要的：
 *
 * <ul>
 *   <li>{@link #existingProcessStillStarts}：缺陷期間本來就能啟動
 *       （{@code leave-approval} 確實存在），所以它綠。它防的是
 *       「修法擋掉正常路徑」—— 也就是本組測試<b>唯一</b>能抓到的那類錯誤修法。</li>
 *   <li>{@link #unauthorizedKeyIsStillForbidden}：403 是改動<b>之前</b>就正確的
 *       行為（{@code isProcessKeyAllowed} 早就在）。它綠，而且<b>必須綠</b> ——
 *       它的作用是證明 404 的預先檢查沒有被擺到 403 前面。
 *       若有人日後「順手」把檢查往前移，這一條會由綠轉紅，
 *       那正是它存在的理由（見該測試內對 403/404 差異的說明）。</li>
 *   <li>{@link #missingProcessKeyIsStillBadRequest}：400 也是改動前就正確的
 *       行為（R-20 已做）。它綠證明 404 的預先檢查沒有蓋掉 key 缺席的 400 ——
 *       兩者對呼叫端的處置方式不同（改 payload vs 改流程選擇）。</li>
 * </ul>
 *
 * <p>換句話說：這個缺陷的邊界<b>只有一個形狀</b>（key 通過授權但查不到定義），
 * 與 #86／#87 那種「要特定 payload 才觸發」的情況不同 ——
 * 因為 {@code allowedProcessKeys} 欄位是自由文字、無必填驗證，
 * 管理員打錯一個字就會踩到（見 {@code ExternalSystemPolicy} 的類別註解）。
 */
class ExternalApiProcessKeyNotFoundTest extends IntegrationTestBase {

    private static final String PLAIN_KEY = "sk-t80-external-testkey";

    @Autowired
    private ExternalSystemRepository repo;

    @Autowired
    private org.flowable.engine.RuntimeService runtimeService;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void seedExternalSystem() {
        repo.deleteAll();
    }

    /**
     * 建立一個外部系統。
     *
     * <p>apiKey 以 SHA-256 雜湊存放（明文只給呼叫端，見 {@code ApiKeyUtil}）。
     * {@code allowedProcessKeys} 給 {@code ["leave-approval"]} ——
     * 見 {@link #unknownButAllowedProcessKeyIsNotFound}：要觸發這個缺陷，
     * 那個 key 必須<b>通過</b> allowedProcessKeys 檢查。
     */
    private ExternalSystem given(String systemId, String allowedProcessKeys) {
        ExternalSystem sys = new ExternalSystem();
        // 刻意不設 id（見 ExternalApiTcA04Test 的說明：自行指定 id 會走 merge）。
        sys.setSystemId(systemId);
        sys.setSystemName("T80 測試系統");
        sys.setApiKey(ApiKeyUtil.hash(PLAIN_KEY));
        sys.setAllowedActions("[\"start_process\"]");
        sys.setAllowedProcessKeys(allowedProcessKeys);
        sys.setEnabled(true);
        sys.setCreatedAt(Instant.now());
        return repo.save(sys);
    }

    private static String body(String processKey) {
        return "{\"processDefinitionKey\":\"" + processKey + "\","
                + "\"businessKey\":\"T80-" + UUID.randomUUID() + "\","
                + "\"firstTaskAssignee\":\"mgr001\","
                + "\"variables\":{\"leaveType\":\"annual\",\"days\":1}}";
    }

    /** 走真實 HTTP（見類別註解「狀態碼走真實 HTTP」）。 */
    private HttpResponse<String> startOverRealHttp(String processKey) throws Exception {
        return postJson(body(processKey));
    }

    private HttpResponse<String> postJson(String payload) throws Exception {
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

    // ── 缺陷：allowed 但不存在的 key ─────────────────────────────────

    @Test
    @DisplayName("#80/#69：allowedProcessKeys 內但查不到定義 → 404（缺陷期間是裸 500）")
    void unknownButAllowedProcessKeyIsNotFound() throws Exception {
        // 這個 key 必須在 allowedProcessKeys 內，否則會先被 403 擋下，
        // 測到的就是另一條規則（而那條規則是對的）。
        String ghostKey = "t80-no-such-process-" + UUID.randomUUID();
        given("erp", "[\"" + ghostKey + "\"]");
        long instancesBefore = runtimeService.createProcessInstanceQuery().count();

        var res = startOverRealHttp(ghostKey);

        assertThat(res.statusCode())
                .as("缺陷期間這裡是 FlowableObjectNotFoundException → 500，"
                        + "而外部系統看到 500 的直覺是丟回重試佇列 —— "
                        + "重試永遠不會成功，於是一個打錯的 key 變成無限重試的來源")
                .isEqualTo(404);
        assertThat(res.statusCode())
                .as("必須是 404 而非 403（授權層）或 400（key 形狀不完整）")
                .isNotIn(400, 403, 500);
        assertThat(runtimeService.createProcessInstanceQuery().count())
                .as("被拒的請求不得留下流程實例 —— 500 若被重試，這裡會開始累積垃圾")
                .isEqualTo(instancesBefore);
    }

    // ── 對照：已部署的 key 必須仍能啟動 ──────────────────────────────

    @Test
    @DisplayName("#80/#69：已部署的流程仍然啟動成功（預先檢查不得擋掉正常路徑）")
    void existingProcessStillStarts() throws Exception {
        // 這一條與 unknownButAllowedProcessKeyIsNotFound 必須成組存在：
        // 少了它，預先檢查寫錯（例如比對 deploymentId、或用 startsWith 之類的
        // 模糊比對）會讓所有正常發起都變 404，而測試組仍然全綠。
        given("erp", "[\"leave-approval\"]");

        var res = startOverRealHttp("leave-approval");

        assertThat(res.statusCode())
                .as("預先檢查必須用 processDefinitionKey 精確比對")
                .isEqualTo(200);
        assertThat(res.body()).contains("processInstanceId");

        String pid = res.body().replaceAll(".*\"processInstanceId\":\"([^\"]*)\".*", "$1");
        assertThat(runtimeService.getVariable(pid, "_externalSystemId"))
                .as("正向對照必須真的啟動了流程，而不是回 200 但什麼都沒做")
                .isEqualTo("erp");
    }

    // ── 對照：授權檢查仍然先於存在性檢查（不可順序顛倒）──────────────

    @Test
    @DisplayName("#80/#69：不在 allowedProcessKeys 的已存在流程仍然是 403（順序不可顛倒）")
    void unauthorizedKeyIsStillForbidden() throws Exception {
        // 為什麼這條重要：若把 404 的預先檢查擺在 403 之前，
        // 這個請求就會回 404 —— 而那等於告訴外部系統
        // 「leave-approval 這個流程確實存在」。一個只被授權別的 key 的系統
        // 因此可以逐字探測出伺服器上部署了哪些流程定義：
        // 403 = 「存在但你不能用」，404 = 「不存在」——
        // 兩個答案合起來就是一份流程定義清單。
        given("erp", "[\"purchase-approval\"]");

        var res = startOverRealHttp("leave-approval");

        assertThat(res.statusCode())
                .as("授權檢查必須先於存在性檢查，否則 403/404 的差異成了部署清單的探測工具")
                .isEqualTo(403);
    }

    // ── 對照：key 缺席／空字串仍是 400（不得被 404 蓋掉）────────────

    @Test
    @DisplayName("#80/#69：processDefinitionKey 缺席或空字串仍是 400（請求不完整 ≠ 資源不存在）")
    void missingProcessKeyIsStillBadRequest() throws Exception {
        given("erp", "[\"leave-approval\"]");

        for (String payload : new String[]{
                "{\"firstTaskAssignee\":\"mgr001\"}",
                "{\"processDefinitionKey\":null,\"firstTaskAssignee\":\"mgr001\"}",
                "{\"processDefinitionKey\":\"\",\"firstTaskAssignee\":\"mgr001\"}",
                "{\"processDefinitionKey\":\"   \",\"firstTaskAssignee\":\"mgr001\"}"}) {
            var res = postJson(payload);
            assertThat(res.statusCode())
                    .as("payload=" + payload + " 應回 400 —— 缺 key 是請求不完整，"
                            + "與「key 查不到」（404）必須能分辨，"
                            + "否則呼叫端不知道該改 payload 還是改流程選擇")
                    .isEqualTo(400);
        }
    }
}
