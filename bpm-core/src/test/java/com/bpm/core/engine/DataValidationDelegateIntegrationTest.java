package com.bpm.core.engine;

import com.bpm.core.support.IntegrationTestBase;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * #48 端到端：驗證失敗走 BPMN boundary error 的替代路徑；沒接住則明顯失敗。
 *
 * <h2>本測試組在證明什麼</h2>
 *
 * <ol>
 *   <li><b>條件真的交給引擎求值</b>：{@code condition expression="${days > 0}"}
 *       是完整 JUEL，單元測試的 {@code ${var}} 後備子集做不到。這裡用
 *       {@code days=0} 走替代路徑、{@code days=3} 走正常路徑，證明
 *       引擎 ExpressionManager 這條路真的接上了。</li>
 *   <li><b>失敗可建模</b>：boundary error
 *       （{@code errorRef="DATA_VALIDATION_FAILED"}）接得到 BpmnError，
 *       流程改走人工複核，而不是炸掉。</li>
 *   <li><b>沒接住不是靜默</b>：沒有 boundary 的流程在啟動時拋
 *       「No catching boundary event found for error with errorCode
 *       'DATA_VALIDATION_FAILED'」—— 錯誤碼就在訊息裡。</li>
 * </ol>
 *
 * <p>BPMN 的 {@code formKey} 用 {@code leave-review}（V5 migration 已 seed），
 * 讓測試 BPMN 也能通過 lint 的 {@code formkey-exists} 規則。
 */
class DataValidationDelegateIntegrationTest extends IntegrationTestBase {

    @Autowired
    private RepositoryService repositoryService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private com.bpm.core.lint.BpmnLintService lintService;

    /**
     * start → serviceTask(validate, boundary error) → end，
     * 失敗則 validate --boundary--> manualReview → end。
     *
     * @param withBoundary 是否掛 boundary error（false 時失敗直接炸）
     */
    private String deployValidationProcess(String key, boolean withBoundary) {
        String boundaryAndAlt = withBoundary ? """
                    <boundaryEvent id="validationFailed" attachedToRef="validate">
                      <errorEventDefinition errorRef="DATA_VALIDATION_FAILED"/>
                    </boundaryEvent>
                    <sequenceFlow id="alt" sourceRef="validationFailed" targetRef="manualReview"/>
                    <userTask id="manualReview" name="人工複核"
                              flowable:assignee="mgr001" flowable:formKey="leave-review"/>
                    <sequenceFlow id="altEnd" sourceRef="manualReview" targetRef="end"/>
                """ : "";
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:flowable="http://flowable.org/bpmn"
                             targetNamespace="http://bpm.com/validation-delegate-test">
                  <process id="%s" isExecutable="true">
                    <startEvent id="start"/>
                    <sequenceFlow id="f1" sourceRef="start" targetRef="validate"/>
                    <serviceTask id="validate" name="資料驗證"
                                 flowable:delegateExpression="${dataValidationDelegate}">
                      <extensionElements>
                        <flowable:field name="requiredVariables" stringValue="days"/>
                        <flowable:field name="condition" expression="${days > 0}"/>
                      </extensionElements>
                    </serviceTask>
                    <sequenceFlow id="ok" sourceRef="validate" targetRef="end"/>
                    %s
                    <endEvent id="end"/>
                  </process>
                </definitions>
                """.formatted(key, boundaryAndAlt);

        var lint = lintService.lint(xml);
        assertThat(lint.valid())
                .as("測試 BPMN 必須通過 lint，實際錯誤：%s", lint.errors())
                .isTrue();

        repositoryService.createDeployment()
                .name("validationdelegate-" + key)
                .addString(key + ".bpmn20.xml", xml)
                .deploy();
        return key;
    }

    @Test
    @DisplayName("#48 條件成立（${days > 0} 且 days=3）→ 走正常路徑結束")
    void validDataTakesNormalPath() {
        String key = deployValidationProcess("validation-ok", true);

        var instance = runtimeService.startProcessInstanceByKey(key, Map.of("days", 3));

        assertThat(runtimeService.createProcessInstanceQuery()
                .processInstanceId(instance.getId()).count())
                .isZero();
        assertThat(taskService.createTaskQuery().processInstanceId(instance.getId()).count())
                .as("不該產生人工複核任務")
                .isZero();
    }

    @Test
    @DisplayName("#48 必填變數缺失 → boundary error 接住，走人工複核替代路徑")
    void missingVariableTakesBoundaryPath() {
        String key = deployValidationProcess("validation-missing", true);

        var instance = runtimeService.startProcessInstanceByKey(key, Map.of());

        Task review = taskService.createTaskQuery()
                .processInstanceId(instance.getId()).singleResult();
        assertThat(review)
                .as("BpmnError 必須被 boundary error 接住，改走替代路徑")
                .isNotNull();
        assertThat(review.getTaskDefinitionKey()).isEqualTo("manualReview");
    }

    @Test
    @DisplayName("#48 條件為 false（days=0，引擎 ExpressionManager 求值）→ 走替代路徑")
    void falseConditionTakesBoundaryPath() {
        String key = deployValidationProcess("validation-false", true);

        var instance = runtimeService.startProcessInstanceByKey(key, Map.of("days", 0));

        Task review = taskService.createTaskQuery()
                .processInstanceId(instance.getId()).singleResult();
        assertThat(review)
                .as("days=0 不滿足 ${days > 0}；若走正常路徑代表條件根本沒被求值")
                .isNotNull();
        assertThat(review.getTaskDefinitionKey()).isEqualTo("manualReview");
    }

    @Test
    @DisplayName("#48 沒掛 boundary error → 流程明顯失敗，訊息含 DATA_VALIDATION_FAILED")
    void withoutBoundaryProcessFailsVisibly() {
        String key = deployValidationProcess("validation-noboundary", false);

        assertThatThrownBy(() -> runtimeService.startProcessInstanceByKey(key, Map.of()))
                .hasMessageContaining("No catching boundary event found")
                .hasMessageContaining("DATA_VALIDATION_FAILED");

        // 交易 rollback：失敗的啟動不得留下孤兒流程
        assertThat(runtimeService.createProcessInstanceQuery().processDefinitionKey(key).count())
                .isZero();
    }
}
