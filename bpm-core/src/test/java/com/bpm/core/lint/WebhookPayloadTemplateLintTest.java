package com.bpm.core.lint;

import com.bpm.core.external.ExternalSystemPolicy;
import com.bpm.core.form.service.FormService;
import com.bpm.core.repository.ExternalSystemRepository;
import com.bpm.core.repository.ProcessVariableSpecRepository;
import com.bpm.core.webhook.WebhookPayloadTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * lint 規則 l：webhook {@code payloadTemplate} 的未知欄位（#28）—— 純單元測試。
 *
 * <h2>為什麼是 warning 而不是 error（測試要守住的事）</h2>
 *
 * <p>未知欄位在投遞時是<b>原樣保留</b>：body 仍送得出去，接收端看到的是
 * 沒被代換的 {@code {{typo}}}。這是「可能寫錯」而不是「必然壞掉」，而且
 * 模板本身是合法的 BPMN。用 error 擋部署會讓一支能跑的流程部署不了，
 * 而誤擋會逼人繞過 lint（見 {@code LintRuleCorrectnessTest} 類別註解）。
 *
 * <p>所以這裡除了斷言「未知欄位有警告」，還必須斷言
 * <b>{@code valid()} 仍為 true</b> —— 只驗警告存在的話，把 severity
 * 改成 error 的實作照樣會過，而那正是這條規則最不該走的方向。
 *
 * <h2>為什麼用 mock 而不是 Spring context</h2>
 *
 * <p>這條規則只讀 BPMN 的 extensionElements，與資料庫無關；
 * {@code BpmnLintService} 的四個依賴在這裡都是「不該被走到」的路徑
 * （流程沒有 serviceTask、沒有流程變數規格、外部系統清單為空）。
 * 用 mock 讓這組測試不必起 Testcontainers。
 */
class WebhookPayloadTemplateLintTest {

    private static final BpmnLintService LINT = new BpmnLintService(
            mock(FormService.class),
            mock(ProcessVariableSpecRepository.class),
            mock(ExternalSystemRepository.class),
            mock(ExternalSystemPolicy.class));

    // ── 工具 ────────────────────────────────────────────────────────

    private static String xmlEscape(String s) {
        return s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;");
    }

    /** 單一 UserTask（有效指派與 formKey）＋節點層 webhook；template 為 null 代表不帶屬性。 */
    private static String nodeHookXml(String template) {
        String attr = template == null ? ""
                : " payloadTemplate=\"" + xmlEscape(template) + "\"";
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:flowable="http://flowable.org/bpmn" targetNamespace="t">
                  <process id="whtpl" isExecutable="true">
                    <startEvent id="s" name="開始"/>
                    <sequenceFlow id="f1" sourceRef="s" targetRef="t1"/>
                    <userTask id="t1" name="審核" flowable:assignee="mgr001" flowable:formKey="external:x">
                      <extensionElements>
                        <flowable:webhooks>
                          <flowable:webhook event="create" url="https://erp.example/hook" method="POST"%s/>
                        </flowable:webhooks>
                      </extensionElements>
                    </userTask>
                    <sequenceFlow id="f2" sourceRef="t1" targetRef="e"/>
                    <endEvent id="e" name="結束"/>
                  </process>
                </definitions>
                """.formatted(attr);
    }

    /** 流程層（{@code <process>} 的 extensionElements）帶模板的版本。 */
    private static String processHookXml(String template) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:flowable="http://flowable.org/bpmn" targetNamespace="t">
                  <process id="whtpl" isExecutable="true">
                    <extensionElements>
                      <flowable:webhooks>
                        <flowable:webhook event="process.completed" url="https://erp.example/done"
                                         method="POST" payloadTemplate="%s"/>
                      </flowable:webhooks>
                    </extensionElements>
                    <startEvent id="s" name="開始"/>
                    <sequenceFlow id="f1" sourceRef="s" targetRef="t1"/>
                    <userTask id="t1" name="審核" flowable:assignee="mgr001" flowable:formKey="external:x"/>
                    <sequenceFlow id="f2" sourceRef="t1" targetRef="e"/>
                    <endEvent id="e" name="結束"/>
                  </process>
                </definitions>
                """.formatted(xmlEscape(template));
    }

    private static List<BpmnLintService.LintError> rule(String rule, String xml) {
        return LINT.lint(xml).errors().stream()
                .filter(e -> rule.equals(e.rule())).toList();
    }

    // ── 規則本身 ────────────────────────────────────────────────────

    @Test
    @DisplayName("全部欄位都在白名單內 → 不警告，且流程有效")
    void knownFieldsProduceNoWarning() {
        var result = LINT.lint(nodeHookXml(
                "{\"task\":\"{{taskName}}\",\"pid\":\"{{processInstanceId}}\",\"n\":{{overdueHours}}}"));

        assertThat(result.errors().stream()
                .filter(e -> "webhook-payload-template".equals(e.rule())).toList())
                .as("KNOWN_FIELDS 內的欄位不得被警告")
                .isEmpty();
        assertThat(result.valid()).isTrue();
    }

    @Test
    @DisplayName("未知欄位 → warning，但 valid() 仍為 true（不擋部署）")
    void unknownFieldIsWarningNotError() {
        var result = LINT.lint(nodeHookXml("{\"salary\":\"{{salary}}\"}"));

        var warnings = result.errors().stream()
                .filter(e -> "webhook-payload-template".equals(e.rule())).toList();
        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0).severity()).isEqualTo("warning");
        assertThat(warnings.get(0).elementId()).isEqualTo("t1");
        assertThat(warnings.get(0).message())
                .as("訊息必須指出是哪個欄位、以及可用欄位有哪些")
                .contains("salary")
                .contains("taskName");
        assertThat(result.valid())
                .as("未知欄位只影響 body 內容（原樣保留），不得讓部署失敗")
                .isTrue();
    }

    @Test
    @DisplayName("多個未知欄位去重後只發一條警告")
    void multipleUnknownFieldsAreOneWarning() {
        var warnings = rule("webhook-payload-template",
                nodeHookXml("{{a}} {{b}} {{a}} {{taskName}}"));

        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0).message()).contains("a").contains("b");
    }

    @Test
    @DisplayName("流程層（<process>）的模板走同一條檢查")
    void processLevelTemplateIsChecked() {
        var warnings = rule("webhook-payload-template", processHookXml("{\"x\":\"{{nope}}\"}"));

        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0).elementId()).isEqualTo("whtpl");
    }

    @Test
    @DisplayName("流程層引用 result／businessKey 等流程層欄位不警告")
    void processLevelKnownFieldsProduceNoWarning() {
        assertThat(rule("webhook-payload-template",
                processHookXml("{\"r\":\"{{result}}\",\"bk\":\"{{businessKey}}\"}")))
                .isEmpty();
    }

    @Test
    @DisplayName("沒有 payloadTemplate 的設定不警告（既有 BPMN 零影響）")
    void absentTemplateProducesNoWarning() {
        assertThat(rule("webhook-payload-template", nodeHookXml(null))).isEmpty();
    }

    @Test
    @DisplayName("路徑式參照（{{variables.salary}}）視為未知 —— 只支援最上層欄位")
    void dottedPathIsUnknown() {
        var warnings = rule("webhook-payload-template",
                nodeHookXml("{{variables.salary}}"));

        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0).message()).contains("variables.salary");
    }

    @Test
    @DisplayName("KNOWN_FIELDS 是 lint 與渲染共用的同一份清單")
    void usesSharedKnownFields() {
        // 這條看起來多餘，但它把「lint 自己維護一份白名單」這個錯誤釘死：
        // 兩份清單的漂移症狀是「lint 放行、執行期卻代換不了」（或反之）。
        assertThat(WebhookPayloadTemplate.KNOWN_FIELDS).contains("taskName", "result");
        assertThat(rule("webhook-payload-template", nodeHookXml("{{taskName}}")))
                .as("白名單成員在 lint 不得被警告")
                .isEmpty();
    }
}
