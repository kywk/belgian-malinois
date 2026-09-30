package com.bpm.core.lint;

import com.bpm.core.model.ExternalSystem;
import com.bpm.core.repository.ExternalSystemRepository;
import com.bpm.core.support.IntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * lint 規則本身的正確性（security-audit P2-5）。
 *
 * <h2>為什麼「誤擋」要當成缺陷來修</h2>
 *
 * <p>規則 d 原本對<b>每一個</b> ExclusiveGateway 都要求 default flow，
 * 包含匯流閘道 —— 而匯流是合併分支的標準畫法。業務人員畫出完全合法的流程
 * 卻部署不了，訊息又指向一個他無法滿足的要求（匯流沒有條件要選，
 * 談不上預設路徑）。
 *
 * <p>那會逼他去繞過 lint。一旦繞過成為常態，<b>所有</b>規則就一起失效了 ——
 * 包含真正在防事故的那幾條。所以誤擋的代價不只是不方便。
 */
class LintRuleCorrectnessTest extends IntegrationTestBase {

    @Autowired private BpmnLintService lintService;
    @Autowired private ExternalSystemRepository externalSystemRepo;
    @Autowired private MockMvc mockMvc;
    @Autowired private org.flowable.engine.RepositoryService repositoryService;

    private final List<String> created = new java.util.ArrayList<>();

    @AfterEach
    void cleanup() {
        created.forEach(id -> externalSystemRepo.findBySystemId(id)
                .ifPresent(externalSystemRepo::delete));
        created.clear();
    }

    private List<String> ruleIds(String xml) {
        return lintService.lint(xml).errors().stream()
                .map(BpmnLintService.LintError::rule).toList();
    }

    // ── 規則 d：預設路徑 ───────────────────────────────────────────

    private static String gatewayXml(String gatewayBody, String flows) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:flowable="http://flowable.org/bpmn" targetNamespace="t">
                  <process id="gwtest" isExecutable="true">
                    <startEvent id="s" name="開始"/>
                    <sequenceFlow id="fs" sourceRef="s" targetRef="gw"/>
                    %s
                    %s
                    <endEvent id="e" name="結束"/>
                  </process>
                </definitions>
                """.formatted(gatewayBody, flows);
    }

    @Test
    @DisplayName("匯流閘道不得被要求預設路徑（合併分支的標準畫法）")
    void convergingGatewayNeedsNoDefaultFlow() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:flowable="http://flowable.org/bpmn" targetNamespace="t">
                  <process id="gwtest" isExecutable="true">
                    <startEvent id="s" name="開始"/>
                    <sequenceFlow id="f1" sourceRef="s" targetRef="gwSplit"/>
                    <exclusiveGateway id="gwSplit" name="分流" default="fb"/>
                    <sequenceFlow id="fa" sourceRef="gwSplit" targetRef="gwJoin">
                      <conditionExpression xsi:type="tFormalExpression"
                           xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">${1==1}</conditionExpression>
                    </sequenceFlow>
                    <sequenceFlow id="fb" sourceRef="gwSplit" targetRef="gwJoin"/>
                    <exclusiveGateway id="gwJoin" name="匯流"/>
                    <sequenceFlow id="f2" sourceRef="gwJoin" targetRef="e"/>
                    <endEvent id="e" name="結束"/>
                  </process>
                </definitions>
                """;
        assertThat(ruleIds(xml))
                .as("匯流閘道沒有條件要選，談不上預設路徑 —— 這是合法的 BPMN")
                .doesNotContain("gateway-default");
    }

    @Test
    @DisplayName("出線含無條件流的分流閘道不得被要求預設路徑")
    void divergingGatewayWithUnconditionalFlowNeedsNoDefault() {
        // 無條件流恆為真，所以一定有路可走。
        String xml = gatewayXml(
                "<exclusiveGateway id=\"gw\" name=\"判斷\"/>",
                """
                <sequenceFlow id="fa" sourceRef="gw" targetRef="e">
                  <conditionExpression xsi:type="tFormalExpression"
                       xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">${1==1}</conditionExpression>
                </sequenceFlow>
                <sequenceFlow id="fb" sourceRef="gw" targetRef="e"/>
                """);
        assertThat(ruleIds(xml)).doesNotContain("gateway-default");
    }

    @Test
    @DisplayName("每條出線都有條件卻沒有預設路徑，必須擋下（規則的真正用途）")
    void divergingGatewayWithAllConditionalFlowsRequiresDefault() {
        // 這才是規則 d 要防的：所有條件都不成立時，流程會在閘道拋例外卡住。
        // 若上面兩個測試是靠「整條規則失效」達成的，這裡就會漏放。
        String xml = gatewayXml(
                "<exclusiveGateway id=\"gw\" name=\"判斷\"/>",
                """
                <sequenceFlow id="fa" sourceRef="gw" targetRef="e">
                  <conditionExpression xsi:type="tFormalExpression"
                       xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">${1==2}</conditionExpression>
                </sequenceFlow>
                <sequenceFlow id="fb" sourceRef="gw" targetRef="e">
                  <conditionExpression xsi:type="tFormalExpression"
                       xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">${1==3}</conditionExpression>
                </sequenceFlow>
                """);
        assertThat(ruleIds(xml)).contains("gateway-default");
    }

    @Test
    @DisplayName("既有的兩支流程必須通過 lint（回歸保護）")
    void shippedProcessesPassLint() throws Exception {
        for (String name : List.of("leave-approval", "purchase-approval")) {
            String xml = new String(getClass().getClassLoader()
                    .getResourceAsStream("processes/" + name + ".bpmn20.xml").readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
            var result = lintService.lint(xml);
            assertThat(result.errors().stream()
                    .filter(e -> "error".equals(e.severity()))
                    .map(BpmnLintService.LintError::rule).toList())
                    .as("%s 出現 error 級別的問題", name)
                    .isEmpty();
        }
    }

    // ── 規則 h：允許外部發起時不可用 initiator ─────────────────────

    /**
     * 建立一個<b>啟用中</b>且授權某流程的外部系統。
     *
     * <p>⚠️ {@code systemId} 帶亂數後綴，不是固定值：{@code uk_bpm_external_system_system_id}
     * 是唯一約束，而「同時授權兩支 BPMN」正是 {@code seedDataDeployment...} 需要的形狀 ——
     * 寫死 id 會讓那條測試在建第二筆時撞約束，而錯誤訊息（唯一約束違規）
     * 完全指不出真正的問題。
     */
    private String givenExternalSystemAllowing(String processKey) {
        var sys = new ExternalSystem();
        String systemId = "lint-test-erp-" + UUID.randomUUID().toString().substring(0, 8);
        sys.setSystemId(systemId);
        sys.setSystemName("lint test");
        sys.setApiKey("irrelevant-hash");
        sys.setAllowedActions("[\"start_process\"]");
        sys.setAllowedProcessKeys("[\"" + processKey + "\"]");
        sys.setEnabled(true);
        sys.setCreatedAt(Instant.now());
        externalSystemRepo.save(sys);
        created.add(sys.getSystemId());
        return systemId;
    }

    private static String initiatorTaskXml() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:flowable="http://flowable.org/bpmn" targetNamespace="t">
                  <process id="hruletest" isExecutable="true">
                    <startEvent id="s" name="開始"/>
                    <sequenceFlow id="f1" sourceRef="s" targetRef="t"/>
                    <userTask id="t" name="審核" flowable:formKey="leave-review"
                              flowable:assignee="${orgService.getDirectManager(initiator)}"/>
                    <sequenceFlow id="f2" sourceRef="t" targetRef="e"/>
                    <endEvent id="e" name="結束"/>
                  </process>
                </definitions>
                """;
    }

    @Test
    @DisplayName("沒有外部系統被授權時，規則 h 不該觸發")
    void ruleHDoesNotFireWithoutAnAuthorizedExternalSystem() {
        assertThat(ruleIds(initiatorTaskXml())).doesNotContain("external-initiator");
    }

    @Test
    @DisplayName("有外部系統被授權發起時，規則 h 必須觸發")
    void ruleHFiresWhenAnExternalSystemCanStartTheProcess() {
        // 改動前 isExternalAllowed 是寫死的 false，所以這條規則從未執行過。
        // 它要抓的問題是真的：外部系統發起時 initiator 是 system:<id>（不是人），
        // 組織系統查不到它的主管。
        givenExternalSystemAllowing("hruletest");

        assertThat(ruleIds(initiatorTaskXml()))
                .as("規則 h 仍是死碼 —— isExternalAllowed 沒有接上真實的授權資料")
                .contains("external-initiator");
    }

    @Test
    @DisplayName("停用的外部系統不該觸發規則 h")
    void disabledExternalSystemDoesNotFireRuleH() {
        String systemId = givenExternalSystemAllowing("hruletest");
        var sys = externalSystemRepo.findBySystemId(systemId).orElseThrow();
        sys.setEnabled(false);
        externalSystemRepo.save(sys);

        assertThat(ruleIds(initiatorTaskXml())).doesNotContain("external-initiator");
    }

    @Test
    @DisplayName("授權的是別的流程時不該觸發規則 h")
    void unrelatedProcessKeyDoesNotFireRuleH() {
        givenExternalSystemAllowing("some-other-process");

        assertThat(ruleIds(initiatorTaskXml())).doesNotContain("external-initiator");
    }

    // ── 規則 h 升為 error（#68d）──────────────────────────────────

    @Test
    @DisplayName("#68d：規則 h 必須是 error —— 否則部署不會擋，缺陷會留到執行期")
    void ruleHIsAnErrorNotAWarning() {
        givenExternalSystemAllowing("hruletest");

        var result = lintService.lint(initiatorTaskXml());
        var ruleH = result.errors().stream()
                .filter(e -> "external-initiator".equals(e.rule())).findFirst().orElseThrow();

        assertThat(ruleH.severity())
                .as("warning 不會讓 DeploymentController 擋下（它只檢查 severity == \"error\"），"
                        + "於是這條規則仍然只是訊息文字")
                .isEqualTo("error");
        assertThat(result.valid())
                .as("valid 必須是 false —— 這才是「部署會被擋」的實際條件")
                .isFalse();
    }

    /**
     * 上面那條「部署成功」<b>不能</b>被當成「升級是安全的」的理由 ——
     * 它綠是因為出廠 BPMN 根本不觸發規則 h，而不是因為 severity 是什麼。
     *
     * <p>這一條把那個「根本不觸發」的原因釘死：出廠兩支 BPMN 的第一個
     * UserTask 用的是 {@code ${assigneeResolver.resolve(execution)}}，
     * <b>字串裡沒有 "initiator"</b>。而規則 h 的判準是
     * {@code allExprs.contains("initiator")}。
     *
     * <p>為什麼值得單獨一條：負向控制組實測顯示，把 severity 改回 warning 時
     * {@code seedDataDeploymentIsNotBlockedByTheSeverityUpgrade} <b>仍然是綠的</b> ——
     * 也就是說那條測試證明不了 severity 升級的安全性，它證明的是
     * 「出廠 BPMN 不觸發規則 h」。若日後有人把第一關改回
     * {@code ${initiator}}（那正是 #83 修掉的形狀），這條會紅，
     * 而那時 {@code seed-data.sh} 就真的會被擋下 —— 這正是要讓它先紅的原因。
     */
    @Test
    @DisplayName("#68d：出廠 BPMN 的第一關不含 initiator 字樣 —— 這是 seed 不被擋的結構性原因")
    void shippedBpmnFirstTaskDoesNotMentionInitiator() throws Exception {
        for (String name : List.of("leave-approval", "purchase-approval")) {
            String xml = new String(getClass().getClassLoader()
                    .getResourceAsStream("processes/" + name + ".bpmn20.xml").readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);

            assertThat(xml)
                    .as("%s 的第一關若出現 initiator，規則 h（現在是 error）會擋下部署，"
                            + "而 seed-data.sh 正是部署這兩支 BPMN", name)
                    .contains("assigneeResolver.resolve(execution)");
            assertThat(firstUserTaskAssignee(xml))
                    .as("%s 的第一個 UserTask 是規則 h 唯一檢查的對象", name)
                    .doesNotContain("initiator");
        }
    }

    /** 取出第一個（StartEvent 直接連出去的）UserTask 的 assignee。 */
    private static String firstUserTaskAssignee(String xml) {
        var doc = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        try {
            var d = doc.newDocumentBuilder()
                    .parse(new java.io.ByteArrayInputStream(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            var nodes = d.getElementsByTagName("*");
            for (int i = 0; i < nodes.getLength(); i++) {
                var el = (org.w3c.dom.Element) nodes.item(i);
                if (!"userTask".equals(el.getLocalName())) continue;
                var candidate = el.getAttributeNS("http://flowable.org/bpmn", "assignee");
                if (candidate != null && !candidate.isBlank()) return candidate;
            }
            return "";
        } catch (Exception e) {
            throw new IllegalStateException("解析 BPMN 失敗", e);
        }
    }

    /**
     * ⚠️ 這一條是本工項最重要的驗證：<b>seed-data.sh 會不會被擋</b>。
     *
     * <p>{@code scripts/seed-data.sh} 呼叫 {@code POST /api/deployments} 部署兩支
     * 出廠 BPMN，而該端點在 {@code lintResult.valid() == false} 時回 400 →
     * {@code curl -sf} 失敗 → {@code fail()} → 腳本以 1 結束。
     *
     * <p>所以「升級前驗證過安全」不足以回答這個問題：那是<b>改動前</b>的驗證，
     * 而且當時 rule h 的 severity 是 warning，{@code valid()} 本來就會是 true。
     * 升級後必須重跑一次部署路徑本身。
     *
     * <p>這裡刻意走<b>真的部署端點</b>而不是只呼叫 {@code lintService.lint()}：
     * 兩者之間還隔著「{@code valid()} 決定 HTTP 狀態碼」這一步，
     * 而那一步正是本工項要改的東西。
     *
     * <p>⚠️ <b>負向控制組的實測結果必須記下來</b>：把 severity 改回 warning 時，
     * 這條測試<b>仍然是綠的</b>。所以它不是「升級安全」的證明，
     * 而是「出廠 BPMN 不觸發規則 h」的證明 —— 見上面那條把它釘死。
     */
    @Test
    @DisplayName("#68d：即使有外部系統被授權，seed-data.sh 部署的兩支 BPMN 仍必須部署成功")
    void seedDataDeploymentIsNotBlockedByTheSeverityUpgrade() throws Exception {
        // 前置條件：外部系統真的被授權了（否則這條測試只是「什麼都沒觸發」）。
        // ⚠️ 必須涵蓋兩支 BPMN 各自被授權的情形，而不只是其中一支。
        givenExternalSystemAllowing("leave-approval");
        givenExternalSystemAllowing("purchase-approval");

        for (String name : List.of("leave-approval", "purchase-approval")) {
            byte[] xml = getClass().getClassLoader()
                    .getResourceAsStream("processes/" + name + ".bpmn20.xml").readAllBytes();

            // 先確認 rule h 確實<b>沒有</b>被觸發 —— 否則下面的 200 是運氣。
            var lint = lintService.lint(new String(xml, java.nio.charset.StandardCharsets.UTF_8));
            assertThat(lint.errors().stream().map(BpmnLintService.LintError::rule))
                    .as("%s 在「外部系統已授權」的情況下觸發了規則 h —— "
                            + "seed-data.sh 會被擋，必須在升級前處理", name)
                    .doesNotContain("external-initiator");
            assertThat(lint.errors().stream()
                    .filter(e -> "error".equals(e.severity()))
                    .map(BpmnLintService.LintError::rule).toList())
                    .as("%s 出現 error 級別的問題 → POST /api/deployments 會回 400", name)
                    .isEmpty();

            // 最後走真的部署端點 —— 這是 seed-data.sh 走的那條路徑。
            MockMultipartFile file = new MockMultipartFile("file", name + ".bpmn20.xml",
                    "text/xml", xml);
            var res = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .multipart("/api/deployments")
                            .file(file)
                            .param("name", name)
                            .header("X-User-Id", "admin001"))
                    .andExpect(status().isOk())
                    .andReturn();
            // 收尾：刪掉這次部署。
            // ⚠️ 測試共用同一個資料庫（IntegrationTestBase 的類別註解），
            // 留下一個同名的新版本會改變其他測試 startProcessInstanceByKey
            // 撿到的定義版本。內容雖然相同，但「測試之間不互相影響」
            // 這條不變式不該靠「內容相同所以沒差」維持。
            String deploymentId = res.getResponse().getContentAsString()
                    .replaceAll(".*\"deploymentId\":\"([^\"]*)\".*", "$1");
            if (!deploymentId.contains("\"") && !deploymentId.isBlank()) {
                repositoryService.deleteDeployment(deploymentId, true);
            }
        }
    }

    // ── XML 解析入口的硬化 ────────────────────────────────────────

    @Test
    @DisplayName("DTD 必須被明確關閉（不可依賴 JDK 預設值）")
    void dtdProcessingIsExplicitlyDisabled() {
        // 實測（執行中的服務、無認證）：改動前內部實體 &inner; 會展開成字串，
        // 也就是 DTD 處理是開著的。外部實體與 billion laughs 當時走不通，
        // 但那是 JDK 預設值擋下的 —— 換 JDK 或調了 jdk.xml.* 系統屬性就沒了。
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE definitions [<!ENTITY inner "ENTITY_EXPANDED">]>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:flowable="http://flowable.org/bpmn" targetNamespace="t">
                  <process id="dtdtest" isExecutable="true">
                    <startEvent id="s" name="開始"/>
                    <sequenceFlow id="f1" sourceRef="s" targetRef="t"/>
                    <userTask id="t" name="&inner;"/>
                    <sequenceFlow id="f2" sourceRef="t" targetRef="e"/>
                    <endEvent id="e" name="結束"/>
                  </process>
                </definitions>
                """;
        var result = lintService.lint(xml);

        // 帶 DTD 的文件現在應該直接解析失敗，而不是讓實體展開。
        assertThat(result.errors()).isNotEmpty();
        assertThat(result.errors().stream().map(BpmnLintService.LintError::rule))
                .contains("parse");
        assertThat(result.errors().stream()
                .map(BpmnLintService.LintError::elementName).toList())
                .as("實體仍然展開了 —— DTD 沒有被關掉")
                .doesNotContain("ENTITY_EXPANDED");
    }

    @Test
    @DisplayName("解析失敗的訊息必須帶根因，不能只有「Error reading XML」")
    void parseErrorIncludesRootCause() {
        // Flowable 的 converter 把 StAX 的錯誤包成一句 Error reading XML，
        // 原因全在 cause 裡 —— 對著那句話排查等於沒有訊息。
        var result = lintService.lint("<definitions>這不是合法的 XML");

        String msg = result.errors().get(0).message();
        assertThat(msg).contains("BPMN XML 解析失敗");
        assertThat(msg.replace("BPMN XML 解析失敗: ", ""))
                .as("訊息只有外層那句，根因被丟掉了：%s", msg)
                .isNotEqualTo("Error reading XML");
    }

    @Test
    @DisplayName("過大的 XML 必須回 413，而不是進到解析器")
    void oversizedXmlIsRejected() throws Exception {
        // @RequestBody String 會把整份內容讀進記憶體，再轉成 byte[] 給解析器
        // —— 同一份內容至少存在兩份。而這是一個無認證的端點。
        String big = "<!-- " + "x".repeat(2_200_000) + " -->";

        mockMvc.perform(post("/api/bpmn/lint")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(big))
                .andExpect(status().isPayloadTooLarge());
    }

    @Test
    @DisplayName("正常大小的 XML 必須放行 —— 否則上面的斷言只是「全都擋掉」")
    void normalSizedXmlIsAccepted() throws Exception {
        mockMvc.perform(post("/api/bpmn/lint")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(initiatorTaskXml()))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("空 body 必須回 400")
    void blankBodyIsRejected() throws Exception {
        mockMvc.perform(post("/api/bpmn/lint")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("   "))
                .andExpect(status().isBadRequest());
    }
}
