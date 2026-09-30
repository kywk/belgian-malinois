package com.bpm.core.security;

import com.bpm.core.model.DocumentRequest;
import com.bpm.core.repository.DocumentRequestRepository;
import com.bpm.core.support.IntegrationTestBase;
import com.bpm.core.support.TestGatewayMockMvcCustomizer;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.task.api.Task;
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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #80：{@code GET /api/process-instances/{id}/bpmn-xml} 與 {@code /api/documents} 的物件層授權。
 *
 * <h2>缺陷 (a)：{@code bpmn-xml} 零檢查，而它洩漏的是「現在輪到誰審」</h2>
 *
 * <p>改動前的方法簽章是 {@code getBpmnXml(@PathVariable String id)} ——
 * 沒有 {@code @CallerId}、沒有任何檢查。任何登入者拿任一 pid 就拿得到流程圖，
 * 而回應裡的 <b>{@code activeIds}</b> 正是「這張單現在卡在哪一關」。
 *
 * <p>危害比單純的 id 枚舉高：枚舉只能證明「某張單存在」，而 {@code activeIds}
 * 直接回答攻擊者最需要的那一題——<b>現在輪到誰審</b>。後續動作因此從
 * 「搜尋問題」變成「查表問題」：針對那個人下手（社交工程、釣魚、猜他的待辦網址），
 * 而不是對整間公司廣撒網。
 *
 * <h2>缺陷 (b)：{@code GET /api/documents} 不帶參數即 {@code findAll()}</h2>
 *
 * <p>回傳全公司所有公文，含 {@code processInstanceId}、{@code documentNumber}、
 * {@code title}、{@code urgencyLevel}。那是 pid 的<b>第二個枚舉來源</b>
 * （第一個是 #71 已修掉的 {@code GET /api/process-instances}）。
 * 而且 {@code ?createdBy=X} 也不是身分檢查 —— 帶任何人的 id 都會回傳那個人的全部公文。
 *
 * <h2>政策決定（b）：列出「自己參與的」還是「全部」→ 選<b>自己建立的</b></h2>
 *
 * <p>理由見 {@code DocumentController.list} 的 javadoc。摘要：
 * 省略參數 = 呼叫者自己（{@code requireSelf}）；帶了別人的 id = 明確 400。
 * <b>沒有任何前端呼叫這個端點</b>（{@code grep -rn "api/documents" bpm-frontend/src}
 * 零命中），所以收斂範圍不會讓任何畫面壞掉 —— 這是能安全「先關再議」的前提。
 * 審核人看自己審的那張單的公文需求，改走 {@code /api/process-instances/involved}
 * ＋ {@code GET /api/documents/{id}}（守衛相同），因此不需要發明第四組政策。
 *
 * <h2>⚠️ 狀態碼走真實 HTTP</h2>
 *
 * <p>MockMvc <b>不做 error dispatch</b>：{@code ResponseStatusException} 會被容器
 * 轉成 ERROR dispatch 打到 {@code /error}，而狀態碼正是那條路徑決定的
 * （見 {@link ErrorDispatchTest}）。若同時壞掉的是
 * {@code SecurityConfig} 的 {@code dispatcherTypeMatchers(ERROR)} 規則，
 * MockMvc 測試會照樣全綠而線上全部變 403 —— 本 repo 已經有 265 個測試這樣
 * 全綠地放過過那個缺陷。因此本類別<b>所有</b>狀態碼斷言都走真實 HTTP。
 *
 * <h2>⚠️ 非空斷言：每條拒絕都配一條「同樣參數、只有身分不同」的放行對照</h2>
 *
 * <p>只有負向斷言時，「整條守衛壞掉、所有人都 404」也會讓它們全綠；
 * 只有正向斷言時，「守衛放行所有人」也會讓它們全綠。兩者必須並列：
 *
 * <ul>
 *   <li>bpmn-xml：{@link #unrelatedUserCannotReadTheDiagram} ↔
 *       {@link #applicantCanStillReadTheDiagram}／{@link #taskHolderCanStillReadTheDiagram}</li>
 *   <li>bpmn-xml：{@link #outsiderCannotReadTheDiagram}（第二種「不關聯」的取樣）</li>
 *   <li>bpmn-xml：{@link #auditorCanStillReadTheDiagramAndItIsRecorded}（稽核旁路）</li>
 *   <li>documents list：{@link #omittedCreatedByMeansTheCaller} ↔
 *       {@link #foreignCreatedByIsRejected}／{@link #explicitOwnCreatedByIsAllowed}</li>
 *   <li>documents 明細：{@link #unrelatedUserCannotReadTheDocument} ↔
 *       {@link #applicantCanStillReadTheDocument}／{@link #taskHolderCanStillReadTheDocument}</li>
 * </ul>
 *
 * <h2>⚠️ 每條拒絕都同時驗「資料沒變」與「稽核寫了什麼」</h2>
 *
 * <p>只斷言狀態碼不夠。這個缺陷的危險之處是<b>真的讀到了</b>，所以被拒的請求
 * 一律另外驗證：bpmn-xml 的 {@code activeIds} 前後一致、公文資料列逐欄相同、
 * 稽核只有 {@code denied:true} 那一筆而<b>沒有</b> {@code auditBypass}。
 *
 * <h2>「非關係人」的兩種取樣</h2>
 *
 * <p>{@link #OUTSIDER} 沒有任何流程、沒有權限，也不在組織 fixture 裡；
 * {@code user002} 是一個<b>真實登入者</b>、與該案無關 —— 後者才是實測報告裡的
 * 攻擊者。兩個都取樣，因為它們失敗的方式不同：前者可能只是「沒這個人」，
 * 後者是「有這個人但他不該看」。
 *
 * <h2>負向控制組實測（把三個 controller 整份還原成 HEAD）</h2>
 *
 * <p><b>19 條中 12 條紅。</b>依 handoff 第 4.2 節的規定，綠的 7 條也要記下來 ——
 * 它們揭露了這個缺陷的實際邊界，而不只是「測試會紅」：
 *
 * <table border="1">
 *   <caption>缺陷期間仍綠的 7 條，以及它們各自在做什麼</caption>
 *   <tr><th>測試</th><th>為什麼缺陷期間是綠的</th></tr>
 *   <tr>
 *     <td>{@code applicantCanStillReadTheDiagram}<br>
 *         {@code taskHolderCanStillReadTheDiagram}<br>
 *         {@code applicantCanStillReadTheDocument}<br>
 *         {@code taskHolderCanStillReadTheDocument}</td>
 *     <td><b>本來就該綠</b> —— 缺陷是「沒有檢查」，而這些是<b>正放行</b>對照。
 *         它們防的是另一個方向的錯誤（守衛擋掉所有人）。正是它們的存在讓
 *         「12 條紅」這個數字有意義：少了它們，一個把所有人都擋掉的守衛
 *         也會是 19 紅。</td>
 *   </tr>
 *   <tr>
 *     <td>{@code explicitOwnCreatedByIsAllowed}</td>
 *     <td><b>揭露了缺陷的形狀</b>：{@code ?createdBy=自己} 在缺陷期間
 *         <b>就是 200 而且正確</b>。也就是說「帶自己的 id」這條路從來沒壞過，
 *         壞的只有「不帶參數」與「帶別人的 id」兩個分支 ——
 *         這正是本工項把它收斂成 {@code requireSelf}（單一規則）的原因，
 *         而不是各自補一個 if。</td>
 *   </tr>
 *   <tr>
 *     <td>{@code unknownDocumentIsNotFound}</td>
 *     <td><b>揭露了另一個邊界</b>：{@code GET /api/documents/{id}} 對
 *         「不存在的 id」<b>本來就回 404</b>（{@code orElseThrow(NOT_FOUND)}）。
 *         所以那個端點的缺陷<b>不是</b>「查不到也回 200」，而是
 *         「<b>查得到</b>任何人��公文都回 200」——
 *         測 {@code unrelatedUserCannotReadTheDocument} 才是對的那一條。
 *         這也說明為什麼不能只靠「不存在的 id 回 404」推論端點有做檢查。</td>
 *   </tr>
 *   <tr>
 *     <td>{@code participantDiagramReadIsNotRecordedAsBypass}</td>
 *     <td>守衛不存在時本來就沒有稽核旁路紀錄，所以「不得記成旁路」成立。
 *         它與 {@link #deniedDiagramReadIsAudited}（紅）配對：
 *         前者證明不該記的沒記、後者證明該記的記了。</td>
 *   </tr>
 * </table>
 *
 * <p>⚠️ <b>用整份還原而不是逐段抽掉守衛</b>：handoff 第 4.3／4.4 記錄過，
 * 把缺陷放回去的<b>位置</b>會影響結果（有一次把 initiator 放回 deny-list
 * 檢查之前，deny-list 反過來擋掉一切、12 個測試全綠 —— 等於沒驗到）。
 * 這裡整份 {@code cp} 回 HEAD，因此結果是可信的。
 */
class BpmnXmlAndDocumentListAuthorizationTest extends IntegrationTestBase {

    /** 沒有任何流程、沒有權限、也不在組織 fixture 裡的 id（真正的局外人）。 */
    private static final String OUTSIDER = "outsider001";

    /** 持有 {@code audit:log:read}（權限中心 fixture），但不是 leave-approval 案件的參與者。 */
    private static final String AUDITOR = "dir001";

    /**
     * 缺陷期間真的被讀到的情報內容。
     *
     * <p>{@code activeIds} 會包含目前點亮的活動 id；leave-approval 停在主管審核關時
     * 就是 {@code managerReview}。斷言「回應不得含有它」比只斷言 404 更有力 ——
     * 它直接對準這個端點<b>實際洩漏的東西</b>。
     */
    private static final String LEAKED_ACTIVE_ID = "managerReview";

    /** 公文標題。測試建立時帶隨機後綴，避免與其他測試共用同一個資料庫時互相干擾。 */
    private final List<String> createdDocumentIds = new ArrayList<>();

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private DocumentRequestRepository docRepo;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void clean() {
        truncateAuditLog();
    }

    // ── HTTP 小工具 ────────────────────────────────────────────────

    private HttpResponse<String> get(String path, String userId) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + SERVLET_PORT + path))
                .header("X-Gateway-Secret", TestGatewayMockMvcCustomizer.GATEWAY_SECRET)
                .header("X-User-Id", userId)
                .GET()
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String userId, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + SERVLET_PORT + path))
                .header("X-Gateway-Secret", TestGatewayMockMvcCustomizer.GATEWAY_SECRET)
                .header("X-User-Id", userId)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    // ── fixture ────────────────────────────────────────────────────

    /**
     * 用<b>真的走過 controller</b> 建立公文（不直接寫 repository）。
     *
     * <p>理由：fixture 若繞過被測的授權層，它驗的就是「repository 的行為」而不是
     * 「端點的授權」。而 {@code POST /api/documents} 本身是 #66 已修好的路徑
     * （{@code ProcessStartIdentityTest} 有它的完整測試），拿它當 fixture 不會
     * 把未驗證的假設帶進來。
     *
     * <p>它同時會啟動一個真的 leave-approval 實例，{@code initiator = 建立人} ——
     * 這正是「建立人是關係人」這個斷言的依據。
     */
    private record Doc(String documentId, String documentNumber, String processInstanceId, String title) {}

    private Doc givenDocument(String creator) throws Exception {
        String title = "T80-" + UUID.randomUUID();
        var res = post("/api/documents", creator,
                "{\"title\":\"" + title + "\",\"category\":\"leave-approval\"}");
        assertThat(res.statusCode())
                .as("前置條件：建立公文必須成功（category 是已部署的 leave-approval）")
                .isEqualTo(200);
        String json = res.body();
        String documentId = json.replaceAll(".*\"id\":\"([^\"]*)\".*", "$1");
        String number = json.replaceAll(".*\"documentNumber\":\"([^\"]*)\".*", "$1");
        String pid = json.replaceAll(".*\"processInstanceId\":\"([^\"]*)\".*", "$1");
        createdDocumentIds.add(documentId);
        return new Doc(documentId, number, pid, title);
    }

    /**
     * 一筆<b>沒有關聯流程</b>的公文（{@code processInstanceId} 為 null）。
     *
     * <p>這種資料列真的存在：{@code DocumentController.create} 是先存公文再啟動流程，
     * 流程啟動失敗時就會留下這種孤兒列（見該方法註解第 2 點）。
     * 它沒有案件可以「參與」，所以守衛必須有<b>另一個</b>分支 ——
     * 見 {@code DocumentController.requireReadable}。
     */
    private Doc givenOrphanDocument(String creator) {
        // ⚠️ document_number 是 VARCHAR(30) 且有 unique 約束（V1 migration），
        // 所以後綴只取 8 碼 —— 測試共用同一個資料庫，編號撞號會讓整個
        // fixture 建立失敗，而那個失敗訊息（截斷）完全指不出真正的問題。
        String number = "T80-ORPHAN-" + UUID.randomUUID().toString().substring(0, 8);
        DocumentRequest d = new DocumentRequest();
        // 刻意不設 id（見 ExternalApiTcA04Test 的說明：自行指定 id 會走 merge）。
        d.setDocumentNumber(number);
        d.setTitle("T80-ORPHAN-" + number);
        d.setCreatedBy(creator);
        d.setCategory("leave-approval");
        // processInstanceId 刻意留 null。
        d = docRepo.save(d);
        createdDocumentIds.add(d.getId());
        return new Doc(d.getId(), number, null, d.getTitle());
    }

    /** user001 發起的一張 leave-approval；第一關的持有者是 mgr001。 */
    private String givenRunningCaseOfUser001() {
        String pid = runtimeService.startProcessInstanceByKey("leave-approval",
                Map.of("initiator", "user001", "leaveType", "annual", "days", 1)).getId();
        Task manager = taskService.createTaskQuery().processInstanceId(pid).singleResult();
        assertThat(manager.getAssignee())
                .as("前置條件：主管審核關卡的持有者必須是 mgr001（user001 的直屬主管）")
                .isEqualTo("mgr001");
        return pid;
    }

    // ── 稽核小工具 ──────────────────────────────────────────────────

    /**
     * 某個身分的稽核紀錄 detail。
     *
     * <p>先睡 300ms：稽核寫入掛在交易的 {@code beforeCommit}，短暫停頓只是讓
     * 「不得留下紀錄」這個斷言更嚴格（少一次「還沒寫完所以是空」的機會）。
     * 反過來說，<b>有</b>紀錄的斷言也用同一個方法 —— 那兩個方向必須對稱，
     * 否則「有紀錄」可能是上一個測試留下的。
     */
    private static List<String> auditDetailsFor(String operatorId, String operationType)
            throws InterruptedException {
        Thread.sleep(300);
        List<String> out = new ArrayList<>();
        withAuditConnection(c -> {
            try (var ps = c.prepareStatement("SELECT detail FROM bpm_audit_log "
                    + "WHERE operator_id = ? AND operation_type = ? ORDER BY id")) {
                ps.setString(1, operatorId);
                ps.setString(2, operationType);
                var rs = ps.executeQuery();
                while (rs.next()) out.add(rs.getString(1));
            }
        });
        return out;
    }

    // ── 公文的逐欄快照（證明「資料沒變」）────────────────────────────

    /** 公文資料列的完整狀態。record 的 toString 會把新舊一起印在失敗訊息裡。 */
    private record DocState(String documentNumber, String processInstanceId, String title,
                            String urgencyLevel, String category, String createdBy) {}

    private DocState stateOf(String documentId) {
        DocumentRequest d = docRepo.findById(documentId).orElseThrow(
                () -> new AssertionError("公文 " + documentId + " 不見了 —— 測試前提失效"));
        return new DocState(d.getDocumentNumber(), d.getProcessInstanceId(), d.getTitle(),
                d.getUrgencyLevel(), d.getCategory(), d.getCreatedBy());
    }

    // ══════════════════════════════════════════════════════════════
    //  ① GET /api/process-instances/{id}/bpmn-xml
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("#80：無關的使用者讀不到別人的流程圖（404），且回應不得含 activeIds")
    void unrelatedUserCannotReadTheDiagram() throws Exception {
        String pid = givenRunningCaseOfUser001();
        assertThat(runtimeService.getActiveActivityIds(pid))
                .as("前置條件：這張單必須真的停在某個活動上，否則 activeIds 是空的，"
                        + "「回應不得洩漏 activeIds」這個斷言就沒有鑑別力")
                .contains(LEAKED_ACTIVE_ID);
        List<String> activeBefore = runtimeService.getActiveActivityIds(pid);

        var res = get("/api/process-instances/" + pid + "/bpmn-xml", "user002");

        assertThat(res.statusCode())
                .as("缺陷期間這裡回 200，且 user002 讀到「這張單現在卡在哪一關」—— "
                        + "那正是能精準指導後續攻擊的情報")
                .isEqualTo(404);
        assertThat(res.body())
                .as("回應不得洩漏目前點亮的活動 id")
                .doesNotContain(LEAKED_ACTIVE_ID);
        assertThat(res.body())
                .as("回應也不得洩漏流程定義本身（關卡名稱、審核人解析式）")
                .doesNotContain("leave-approval");

        // 被拒的讀取不得改動任何東西。
        assertThat(runtimeService.getActiveActivityIds(pid))
                .as("被拒的讀取不得改變案件的活動狀態").isEqualTo(activeBefore);
        assertThat(taskService.createTaskQuery().processInstanceId(pid).singleResult().getAssignee())
                .as("被拒的讀取不得改變任務的持有者").isEqualTo("mgr001");
        assertThat(runtimeService.createProcessInstanceQuery().processInstanceId(pid).count())
                .as("被拒的讀取不得改變案件的存活狀態").isEqualTo(1);
    }

    @Test
    @DisplayName("#80：完全不認識的身分也讀不到（不帶關聯性的第二種取樣）")
    void outsiderCannotReadTheDiagram() throws Exception {
        String pid = givenRunningCaseOfUser001();

        var res = get("/api/process-instances/" + pid + "/bpmn-xml", OUTSIDER);

        assertThat(res.statusCode()).isEqualTo(404);
        assertThat(res.body()).doesNotContain(LEAKED_ACTIVE_ID);
    }

    @Test
    @DisplayName("#80：不存在的 pid 也是 404（改動前回 200 + 空圖，與「已結案」無法分辨）")
    void unknownInstanceIsNotFound() throws Exception {
        String ghost = "no-such-pid-" + UUID.randomUUID();

        var res = get("/api/process-instances/" + ghost + "/bpmn-xml", "user001");

        // 改動前：runtime 查不到 → Map.of("xml","",...) → 200。
        // 「查不到」與「沒權看」塌成同一個回應，正是 ProcessAccessGuard.InstanceState
        // 存在的理由：呼叫端看到空物件時完全無法分辨該怎麼處理。
        assertThat(res.statusCode())
                .as("缺陷期間這裡回 200 + {\"xml\":\"\",\"activeIds\":[]}，"
                        + "與「這張單已結案」完全相同")
                .isEqualTo(404);
        assertThat(res.statusCode())
                .as("必須是 404 而非 403（授權層）或 401（認證層）")
                .isNotIn(401, 403, 500);
    }

    @Test
    @DisplayName("#80：申請人（initiator，isParticipant 條件 1）仍看得到流程圖與 activeIds")
    void applicantCanStillReadTheDiagram() throws Exception {
        String pid = givenRunningCaseOfUser001();

        var res = get("/api/process-instances/" + pid + "/bpmn-xml", "user001");

        assertThat(res.statusCode())
                .as("申請人是 initiator，屬於 isParticipant 的條件 1 —— 這條不能被擋")
                .isEqualTo(200);
        assertThat(res.body())
                .as("回應必須真的帶 activeIds，否則這條對「守衛擋掉所有人」同樣是綠的")
                .contains(LEAKED_ACTIVE_ID);
        assertThat(res.body()).contains("<");
    }

    @Test
    @DisplayName("#80：任務持有者仍看得到流程圖（DocumentDetail.vue:21 → ProcessDiagram.vue:22 的路徑）")
    void taskHolderCanStillReadTheDiagram() throws Exception {
        String pid = givenRunningCaseOfUser001();

        var res = get("/api/process-instances/" + pid + "/bpmn-xml", "mgr001");

        assertThat(res.statusCode())
                .as("審核人從待辦清單點進單據，畫面上就會呼叫這個端點 —— "
                        + "守衛不得把它擋掉，否則 DocumentDetail 的流程圖整片消失")
                .isEqualTo(200);
        assertThat(res.body()).contains(LEAKED_ACTIVE_ID);
    }

    @Test
    @DisplayName("#80：稽核人員（audit:log:read）讀得到，且旁路每次留痕")
    void auditorCanStillReadTheDiagramAndItIsRecorded() throws Exception {
        String pid = givenRunningCaseOfUser001();

        var res = get("/api/process-instances/" + pid + "/bpmn-xml", AUDITOR);

        assertThat(res.statusCode())
                .as("調查一張單時流程圖與目前卡關是關鍵證據，與 variables／附件同一政策")
                .isEqualTo(200);
        assertThat(res.body()).contains(LEAKED_ACTIVE_ID);
        assertThat(auditDetailsFor(AUDITOR, "DATA_ACCESS"))
                .as("旁路必須每次留痕，否則「誰以稽核身分調閱了哪些案件的流程圖」無從追查")
                .isNotEmpty()
                .allMatch(d -> d.contains("\"auditBypass\":true")
                        && d.contains("\"action\":\"get_bpmn_xml\""));
    }

    @Test
    @DisplayName("#80：關係人讀自己案件的流程圖不產生旁路紀錄（那是日常操作）")
    void participantDiagramReadIsNotRecordedAsBypass() throws Exception {
        String pid = givenRunningCaseOfUser001();

        get("/api/process-instances/" + pid + "/bpmn-xml", "mgr001");

        assertThat(auditDetailsFor("mgr001", "DATA_ACCESS"))
                .as("每次開單都會讀一次流程圖，留痕會把稽核表淹掉")
                .isEmpty();
    }

    @Test
    @DisplayName("#80：被拒絕的流程圖讀取必須留下 DATA_ACCESS 稽核，且不得記成旁路")
    void deniedDiagramReadIsAudited() throws Exception {
        String pid = givenRunningCaseOfUser001();

        get("/api/process-instances/" + pid + "/bpmn-xml", OUTSIDER);

        var details = auditDetailsFor(OUTSIDER, "DATA_ACCESS");
        assertThat(details)
                .as("不留痕就只剩下一堆沒有來源的 404")
                .isNotEmpty()
                .allMatch(d -> d.contains("\"denied\":true"));
        assertThat(details)
                .as("被拒絕的存取不得被記成 auditBypass —— 那會讓稽核看起來像它成功了")
                .noneMatch(d -> d.contains("\"auditBypass\":true"));
    }

    // ══════════════════════════════════════════════════════════════
    //  ② GET /api/documents（列表）
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("#80：GET /api/documents 不帶參數只回自己的，不得回傳全公司公文")
    void omittedCreatedByMeansTheCaller() throws Exception {
        Doc mine = givenDocument("user001");
        Doc theirs = givenDocument("user002");

        var res = get("/api/documents", "user001");

        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body())
                .as("自己的公文必須看得到 —— 否則這條對「擋掉所有人」同樣是綠的")
                .contains(mine.documentNumber()).contains(mine.title());
        assertThat(res.body())
                .as("缺陷期間：不帶參數即 findAll() → 全公司公文。"
                        + "documentNumber 與 title 都會洩漏")
                .doesNotContain(theirs.documentNumber()).doesNotContain(theirs.title());
        assertThat(res.body())
                .as("processInstanceId 是 pid 的第二個枚舉來源，"
                        + "這一條正是本端點存在的理由（#71 關掉的是第一個）")
                .doesNotContain(theirs.processInstanceId());
    }

    @Test
    @DisplayName("#80：帶了別人的 createdBy → 400，且不得洩漏對方的任何欄位")
    void foreignCreatedByIsRejected() throws Exception {
        Doc theirs = givenDocument("user002");
        DocState before = stateOf(theirs.documentId());

        var res = get("/api/documents?createdBy=user002", "user001");

        assertThat(res.statusCode())
                .as("靜默忽略會讓呼叫端以為它查得到對方的公文，而實際拿到自己的 —— "
                        + "那與 #71 對 initiator 參數的取捨完全相同")
                .isEqualTo(400);
        assertThat(res.body())
                .as("訊息要指名參數，否則呼叫端只會看到 400（#73）").contains("createdBy");
        assertThat(res.body())
                .as("被拒的請求不得洩漏對方的公文").doesNotContain(theirs.documentNumber());
        assertThat(stateOf(theirs.documentId()))
                .as("被拒的讀取不得改動任何資料列").isEqualTo(before);
    }

    @Test
    @DisplayName("#80：帶自己的 createdBy → 200（守衛不得過度阻擋）")
    void explicitOwnCreatedByIsAllowed() throws Exception {
        Doc mine = givenDocument("user001");

        var res = get("/api/documents?createdBy=user001", "user001");

        assertThat(res.statusCode())
                .as("「省略即自己」與「明確帶自己」必須是同一個結果，"
                        + "否則呼叫端會以為參數有額外意義")
                .isEqualTo(200);
        assertThat(res.body()).contains(mine.documentNumber());
    }

    @Test
    @DisplayName("#80：空白 createdBy 視同省略（改動前回 []，那是一個看起來像「我沒有公文」的謊話）")
    void blankCreatedByIsTreatedAsOmitted() throws Exception {
        Doc mine = givenDocument("user001");

        var res = get("/api/documents?createdBy=", "user001");

        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body())
                .as("空白不可能指向別人，因此不需要 400；回 [] 會讓呼叫端誤以為自己沒有公文")
                .contains(mine.documentNumber());
    }

    @Test
    @DisplayName("#80：稽核職能在列表端點不開旁路（本端點永遠只回呼叫者自己的公文）")
    void auditorGetsNoBypassOnTheDocumentList() throws Exception {
        Doc mine = givenDocument("user001");

        var res = get("/api/documents", AUDITOR);

        // 政策斷言，不是本測試的副作用：
        // 「我的清單」開旁路等於給稽核職能一個沒有對應需求的讀取權。
        // 稽核人員要查別人的案件請走稽核 API（PROCESS_START 事件帶 documentNumber
        // 與 title）或 GET /api/documents/{id}（那裡有旁路且每次留痕）。
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body())
                .as("dir001 自己沒有建立任何公文，回應應為空 —— "
                        + "而不是整份全公司公文")
                .doesNotContain(mine.documentNumber()).doesNotContain(mine.processInstanceId());
        assertThat(auditDetailsFor(AUDITOR, "DATA_ACCESS"))
                .as("本端點不開旁路，因此不該留下 DATA_ACCESS")
                .isEmpty();
    }

    // ══════════════════════════════════════════════════════════════
    //  ③ GET /api/documents/{id}（明細）
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("#80：無關的使用者讀不到別人的公文明細（404），且資料一欄都沒變")
    void unrelatedUserCannotReadTheDocument() throws Exception {
        Doc theirs = givenDocument("user002");
        DocState before = stateOf(theirs.documentId());

        var res = get("/api/documents/" + theirs.documentId(), "user001");

        assertThat(res.statusCode())
                .as("缺陷期間這裡回 200，回應含 title、documentNumber 與 processInstanceId。"
                        + "只修列表等於沒修 —— documentId 仍可從稽核紀錄取得")
                .isEqualTo(404);
        assertThat(res.body())
                .as("404 的回應不得洩漏任何公文欄位")
                .doesNotContain(theirs.title())
                .doesNotContain(theirs.documentNumber())
                .doesNotContain(theirs.processInstanceId());
        assertThat(stateOf(theirs.documentId()))
                .as("被拒的讀取不得改動任何資料列").isEqualTo(before);
    }

    @Test
    @DisplayName("#80：申請人（建立人）仍讀得到自己的公文明細")
    void applicantCanStillReadTheDocument() throws Exception {
        Doc mine = givenDocument("user001");

        var res = get("/api/documents/" + mine.documentId(), "user001");

        assertThat(res.statusCode())
                .as("建立人是 initiator，屬於 isParticipant 的條件 1 —— 這條不能被擋")
                .isEqualTo(200);
        assertThat(res.body()).contains(mine.title()).contains(mine.documentNumber());
    }

    @Test
    @DisplayName("#80：任務持有者（審核人）仍讀得到該案的公文明細")
    void taskHolderCanStillReadTheDocument() throws Exception {
        Doc doc = givenDocument("user001");
        Task manager = taskService.createTaskQuery()
                .processInstanceId(doc.processInstanceId()).singleResult();
        assertThat(manager.getAssignee())
                .as("前置條件：這個案件的持有者必須是 mgr001，不是建立人")
                .isEqualTo("mgr001");

        var res = get("/api/documents/" + doc.documentId(), "mgr001");

        assertThat(res.statusCode())
                .as("審核人看得到自己審的那張單的公文 —— 這是本工項刻意保留的能力，"
                        + "也是「不為它發明第四組政策」的前提")
                .isEqualTo(200);
        assertThat(res.body()).contains(doc.title());
    }

    @Test
    @DisplayName("#80：稽核人員讀得到公文明細，且旁路每次留痕")
    void auditorCanReadTheDocumentAndItIsRecorded() throws Exception {
        Doc theirs = givenDocument("user001");

        var res = get("/api/documents/" + theirs.documentId(), AUDITOR);

        assertThat(res.statusCode())
                .as("與 variables／附件同一政策：關係人 ∪ audit:log:read")
                .isEqualTo(200);
        assertThat(res.body()).contains(theirs.title());
        assertThat(auditDetailsFor(AUDITOR, "DATA_ACCESS"))
                .isNotEmpty()
                .allMatch(d -> d.contains("\"auditBypass\":true")
                        && d.contains("\"action\":\"get_document\""));
    }

    @Test
    @DisplayName("#80：沒有關聯流程的公文只有建立人讀得到（它沒有案件可以「參與」）")
    void orphanDocumentIsVisibleOnlyToItsCreator() throws Exception {
        Doc orphan = givenOrphanDocument("user001");

        var byCreator = get("/api/documents/" + orphan.documentId(), "user001");
        assertThat(byCreator.statusCode())
                .as("流程啟動失敗會留下這種孤兒列（見 DocumentController.create 的註解）。"
                        + "若連建立人都看不到，那筆資料就永遠沒有人能清理")
                .isEqualTo(200);
        assertThat(byCreator.body()).contains(orphan.title());

        var byOther = get("/api/documents/" + orphan.documentId(), "user002");
        assertThat(byOther.statusCode()).isEqualTo(404);
        assertThat(byOther.body()).doesNotContain(orphan.title());
    }

    @Test
    @DisplayName("#80：不存在的公文 id 是 404，且不得被當成「空集合」")
    void unknownDocumentIsNotFound() throws Exception {
        String ghost = "no-such-doc-" + UUID.randomUUID();

        var res = get("/api/documents/" + ghost, "user001");

        assertThat(res.statusCode()).isEqualTo(404);
        assertThat(docRepo.findById(ghost))
                .as("被拒的請求不得建立任何資料").isEmpty();
    }
}
