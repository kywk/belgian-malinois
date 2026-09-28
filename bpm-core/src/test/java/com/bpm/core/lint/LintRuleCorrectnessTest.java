package com.bpm.core.lint;

import com.bpm.core.model.ExternalSystem;
import com.bpm.core.repository.ExternalSystemRepository;
import com.bpm.core.support.IntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

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

    private void givenExternalSystemAllowing(String processKey) {
        var sys = new ExternalSystem();
        sys.setSystemId("lint-test-erp");
        sys.setSystemName("lint test");
        sys.setApiKey("irrelevant-hash");
        sys.setAllowedActions("[\"start_process\"]");
        sys.setAllowedProcessKeys("[\"" + processKey + "\"]");
        sys.setEnabled(true);
        sys.setCreatedAt(Instant.now());
        externalSystemRepo.save(sys);
        created.add(sys.getSystemId());
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
        givenExternalSystemAllowing("hruletest");
        var sys = externalSystemRepo.findBySystemId("lint-test-erp").orElseThrow();
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
