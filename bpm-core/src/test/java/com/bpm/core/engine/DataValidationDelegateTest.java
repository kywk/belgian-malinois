package com.bpm.core.engine;

import org.flowable.bpmn.model.FieldExtension;
import org.flowable.bpmn.model.ServiceTask;
import org.flowable.bpmn.model.UserTask;
import org.flowable.engine.delegate.BpmnError;
import org.flowable.engine.delegate.DelegateExecution;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * #48 {@link DataValidationDelegate} 的驗證規則。
 *
 * <h2>為什麼每個失敗都要斷言訊息內容</h2>
 *
 * <p>這個 delegate 的價值就在「指名哪一個變數／條件不合格」。只斷言
 * 「有拋 BpmnError」證明不了訊息可用 —— 而訊息是設計師唯一能看到的線索。
 * 所以每條失敗測試都同時釘 errorCode 與訊息中的名稱。
 *
 * <h2>condition 在單元測試走後備子集</h2>
 *
 * <p>沒有 command context 時 {@link BpmnFieldSupport} 用 {@code ${var}} 替換，
 * 完整 JUEL（{@code ${days > 0}}）由
 * {@code DataValidationDelegateIntegrationTest} 在真實引擎裡驗。
 * 這是刻意的分工，不是漏測。
 */
class DataValidationDelegateTest {

    private DelegateExecution execution;
    private DataValidationDelegate delegate;

    @BeforeEach
    void setUp() {
        execution = Mockito.mock(DelegateExecution.class);
        delegate = new DataValidationDelegate();
        when(execution.getProcessInstanceId()).thenReturn("pid-1");
        when(execution.getCurrentActivityId()).thenReturn("validate");
    }

    // ── 工具 ────────────────────────────────────────────────────────

    private static ServiceTask serviceTask(FieldExtension... fields) {
        ServiceTask task = new ServiceTask();
        task.setId("validate");
        for (FieldExtension field : fields) {
            task.getFieldExtensions().add(field);
        }
        return task;
    }

    private static FieldExtension field(String name, String stringValue) {
        FieldExtension field = new FieldExtension();
        field.setFieldName(name);
        field.setStringValue(stringValue);
        return field;
    }

    private static FieldExtension expression(String name, String expr) {
        FieldExtension field = new FieldExtension();
        field.setFieldName(name);
        field.setExpression(expr);
        return field;
    }

    private static void assertValidationFailed(DelegateExecution execution,
                                               DataValidationDelegate delegate,
                                               String messagePart) {
        assertThatThrownBy(() -> delegate.validate(execution))
                .isInstanceOf(BpmnError.class)
                .satisfies(e -> assertThat(((BpmnError) e).getErrorCode())
                        .isEqualTo(DataValidationDelegate.ERROR_CODE))
                .hasMessageContaining(messagePart);
    }

    // ── 通過 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("requiredVariables 全部存在且非空白 → 通過")
    void allRequiredVariablesPresentPasses() {
        when(execution.getCurrentFlowElement()).thenReturn(serviceTask(
                field("requiredVariables", "leaveType, days")));
        when(execution.getVariable("leaveType")).thenReturn("annual");
        when(execution.getVariable("days")).thenReturn(3);

        assertThatCode(() -> delegate.validate(execution)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("非字串值只要有值就算通過（0／false 不是空白）")
    void nonStringValuesCountAsPresent() {
        when(execution.getCurrentFlowElement()).thenReturn(serviceTask(
                field("requiredVariables", "count,flag")));
        when(execution.getVariable("count")).thenReturn(0);
        when(execution.getVariable("flag")).thenReturn(false);

        assertThatCode(() -> delegate.validate(execution)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("condition=${flag} 且 flag=true → 通過（沒有 requiredVariables 也可以只驗條件）")
    void trueConditionPasses() {
        when(execution.getCurrentFlowElement()).thenReturn(serviceTask(
                expression("condition", "${flag}")));
        when(execution.getVariable("flag")).thenReturn(true);

        assertThatCode(() -> delegate.validate(execution)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("condition 為數字：非 0 為真、0 為假（JUEL Number 真值語意）")
    void numericConditionUsesNonZeroAsTrue() {
        when(execution.getCurrentFlowElement()).thenReturn(serviceTask(
                expression("condition", "${count}")));
        when(execution.getVariable("count")).thenReturn(5);
        assertThatCode(() -> delegate.validate(execution)).doesNotThrowAnyException();

        when(execution.getVariable("count")).thenReturn(0);
        assertValidationFailed(execution, delegate, "condition 求值為 false");
    }

    // ── 失敗：requiredVariables ─────────────────────────────────────

    @Test
    @DisplayName("變數不存在 → BpmnError，訊息指名該變數")
    void missingVariableFailsAndNamesIt() {
        when(execution.getCurrentFlowElement()).thenReturn(serviceTask(
                field("requiredVariables", "leaveType,days")));
        when(execution.getVariable("leaveType")).thenReturn("annual");
        when(execution.getVariable("days")).thenReturn(null);

        assertValidationFailed(execution, delegate, "變數 'days' 不存在或為空白");
    }

    @Test
    @DisplayName("空字串與純空白 → 都算失敗（空白字串是常見的靜默缺值形狀）")
    void blankValuesFail() {
        when(execution.getCurrentFlowElement()).thenReturn(serviceTask(
                field("requiredVariables", "empty,spaces")));
        when(execution.getVariable("empty")).thenReturn("");
        when(execution.getVariable("spaces")).thenReturn("   ");

        assertValidationFailed(execution, delegate, "變數 'empty' 不存在或為空白");
        assertValidationFailed(execution, delegate, "變數 'spaces' 不存在或為空白");
    }

    @Test
    @DisplayName("多個變數失敗 → 同一則訊息列出全部（設計師一次看完）")
    void multipleFailuresAreAggregated() {
        when(execution.getCurrentFlowElement()).thenReturn(serviceTask(
                field("requiredVariables", "a,b,c")));
        when(execution.getVariable("a")).thenReturn("x");
        when(execution.getVariable("b")).thenReturn(" ");
        when(execution.getVariable("c")).thenReturn(null);

        assertThatThrownBy(() -> delegate.validate(execution))
                .isInstanceOf(BpmnError.class)
                .hasMessageContaining("變數 'b' 不存在或為空白")
                .hasMessageContaining("變數 'c' 不存在或為空白");
    }

    @Test
    @DisplayName("名稱清單 trim／去空項／去重（重複的失敗只出現一次）")
    void variableNamesAreTrimmedAndDeduplicated() {
        when(execution.getCurrentFlowElement()).thenReturn(serviceTask(
                field("requiredVariables", " days ,, days ,")));
        when(execution.getVariable("days")).thenReturn(null);

        assertThatThrownBy(() -> delegate.validate(execution))
                .isInstanceOf(BpmnError.class)
                .hasMessageContaining("變數 'days' 不存在或為空白")
                .satisfies(e -> assertThat(e.getMessage())
                        .as("重複的名稱不得產生兩次同樣的失敗")
                        .containsOnlyOnce("變數 'days'"));
    }

    // ── 失敗：condition ─────────────────────────────────────────────

    @Test
    @DisplayName("condition 求值為 false → BpmnError")
    void falseConditionFails() {
        when(execution.getCurrentFlowElement()).thenReturn(serviceTask(
                expression("condition", "${flag}")));
        when(execution.getVariable("flag")).thenReturn(false);

        assertValidationFailed(execution, delegate, "condition 求值為 false");
    }

    @Test
    @DisplayName("condition 的變數缺失 → 替換成空字串 → 視為 false（不當成通過）")
    void conditionWithMissingVariableFails() {
        when(execution.getCurrentFlowElement()).thenReturn(serviceTask(
                expression("condition", "${missing}")));
        when(execution.getVariable("missing")).thenReturn(null);

        assertValidationFailed(execution, delegate, "condition 求值為 false");
    }

    @Test
    @DisplayName("condition 求值丟例外 → 翻成同一種 BpmnError 並帶底層原因（閘門不能說『不知道』）")
    void conditionEvaluationExceptionBecomesBpmnError() {
        when(execution.getCurrentFlowElement()).thenReturn(serviceTask(
                expression("condition", "${flag}")));
        when(execution.getVariable(anyString())).thenThrow(new IllegalStateException("boom"));

        assertValidationFailed(execution, delegate, "condition 無法求值");
        assertThatThrownBy(() -> delegate.validate(execution))
                .hasMessageContaining("boom");
    }

    // ── 設定錯誤 ────────────────────────────────────────────────────

    @Test
    @DisplayName("requiredVariables 與 condition 都沒設定 → BpmnError（不是靜默通過）")
    void noRulesConfiguredFails() {
        when(execution.getCurrentFlowElement()).thenReturn(serviceTask());

        assertValidationFailed(execution, delegate, "未設定任何驗證規則");
    }

    @Test
    @DisplayName("delegate 掛在非 ServiceTask 上（讀不到欄位）→ 同一條設定錯誤路徑")
    void nonServiceTaskCurrentElementFailsAsMisconfiguration() {
        when(execution.getCurrentFlowElement()).thenReturn(new UserTask());

        assertValidationFailed(execution, delegate, "未設定任何驗證規則");
    }

    @Test
    @DisplayName("只有空白名稱清單、沒有 condition → 一樣是設定錯誤")
    void blankVariableListWithoutConditionFails() {
        when(execution.getCurrentFlowElement()).thenReturn(serviceTask(
                field("requiredVariables", " , , ")));

        assertValidationFailed(execution, delegate, "未設定任何驗證規則");
    }
}
