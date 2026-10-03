package com.bpm.core.engine;

import org.flowable.bpmn.model.FieldExtension;
import org.flowable.bpmn.model.ServiceTask;
import org.flowable.engine.delegate.DelegateExecution;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * {@link BpmnFieldSupport} 的解析規則（#43／#48／#49 的共用底層）。
 *
 * <h2>為什麼用真的 {@code ServiceTask}／{@code FieldExtension}</h2>
 *
 * <p>要釘住的是「欄位怎麼從 model 讀出來」的物件圖
 * （{@code getFieldExtensions()}、{@code getExpression()} 優先於
 * {@code getStringValue()}），mock 掉 model 等於跳過真正要測的部分。
 * execution 只提供變數，mock 即可。
 *
 * <h2>單元測試跑在 command context 外</h2>
 *
 * <p>{@code expression} 欄位會走 {@link BpmnFieldSupport#evaluate} 的後備路徑
 * （{@code ${var}} 替換子集）—— 引擎 {@code ExpressionManager} 路徑由整合
 * 測試（{@code DataValidationDelegateIntegrationTest} 的 {@code ${amount > 1000}}）
 * 覆蓋。這是刻意的分工：單元測試證明規則，整合測試證明真的接上引擎。
 */
class BpmnFieldSupportTest {

    private DelegateExecution execution;

    @BeforeEach
    void setUp() {
        execution = Mockito.mock(DelegateExecution.class);
    }

    private static ServiceTask serviceTask(FieldExtension... fields) {
        ServiceTask task = new ServiceTask();
        task.setId("svc");
        for (FieldExtension field : fields) {
            task.getFieldExtensions().add(field);
        }
        return task;
    }

    private static FieldExtension stringField(String name, String value) {
        FieldExtension field = new FieldExtension();
        field.setFieldName(name);
        field.setStringValue(value);
        return field;
    }

    private static FieldExtension expressionField(String name, String expression) {
        FieldExtension field = new FieldExtension();
        field.setFieldName(name);
        field.setExpression(expression);
        return field;
    }

    // ── stringValue：${var} 替換 ─────────────────────────────────────

    @Test
    @DisplayName("stringValue 原樣回傳（沒有 ${} 時不做任何事）")
    void literalStringValueIsReturnedAsIs() {
        ServiceTask task = serviceTask(stringField("to", "a@x.com"));

        assertThat(BpmnFieldSupport.field(task, "to", execution)).isEqualTo("a@x.com");
    }

    @Test
    @DisplayName("stringValue 內嵌 ${var} 以 execution 變數替換（混合字串）")
    void substitutesEmbeddedVariable() {
        when(execution.getVariable("approver")).thenReturn("mgr001@x.com");
        ServiceTask task = serviceTask(stringField("to", "a@x.com,${approver}"));

        assertThat(BpmnFieldSupport.field(task, "to", execution))
                .isEqualTo("a@x.com,mgr001@x.com");
    }

    @Test
    @DisplayName("變數不存在或值為 null → 替換成空字串（不是原樣留下 ${x}）")
    void missingOrNullVariableBecomesEmptyString() {
        when(execution.getVariable("present")).thenReturn(null);
        ServiceTask task = serviceTask(stringField("to", "${missing},${present},b@x.com"));

        assertThat(BpmnFieldSupport.field(task, "to", execution)).isEqualTo(",,b@x.com");
    }

    @Test
    @DisplayName("變數名稱前後空白會 trim；${} 空名稱視為缺失")
    void trimsVariableNameAndTreatsEmptyNameAsMissing() {
        when(execution.getVariable("approver")).thenReturn("mgr001");
        ServiceTask task = serviceTask(stringField("x", "${ approver }|${}"));

        assertThat(BpmnFieldSupport.field(task, "x", execution)).isEqualTo("mgr001|");
    }

    @Test
    @DisplayName("只替換一輪：變數值裡的 ${...} 不會再展開（不遞迴）")
    void substitutionIsSinglePass() {
        when(execution.getVariable("outer")).thenReturn("${inner}");
        ServiceTask task = serviceTask(stringField("x", "${outer}"));

        assertThat(BpmnFieldSupport.field(task, "x", execution)).isEqualTo("${inner}");
    }

    @Test
    @DisplayName("變數值含 $ 或反斜線時不因 appendReplacement 而炸掉（quoteReplacement）")
    void replacementWithRegexSpecialCharactersIsSafe() {
        when(execution.getVariable("price")).thenReturn("$5\\6");
        ServiceTask task = serviceTask(stringField("x", "price=${price}"));

        assertThat(BpmnFieldSupport.field(task, "x", execution)).isEqualTo("price=$5\\6");
    }

    // ── expression：無 command context 時走後備子集 ──────────────────

    @Test
    @DisplayName("expression=${var} 在 command context 外以 ${var} 替換後備（單元測試路徑）")
    void expressionFallsBackToSubstitutionOutsideCommandContext() {
        when(execution.getVariable("flag")).thenReturn(true);
        ServiceTask task = serviceTask(expressionField("condition", "${flag}"));

        assertThat(BpmnFieldSupport.field(task, "condition", execution)).isEqualTo("true");
    }

    @Test
    @DisplayName("同一欄位同時有 expression 與 stringValue 時 expression 優先（與官方一致）")
    void expressionWinsOverStringValue() {
        FieldExtension field = new FieldExtension();
        field.setFieldName("x");
        field.setStringValue("string-value");
        field.setExpression("${flag}");
        when(execution.getVariable("flag")).thenReturn("expr-value");
        ServiceTask task = serviceTask(field);

        assertThat(BpmnFieldSupport.field(task, "x", execution)).isEqualTo("expr-value");
    }

    // ── 找不到／不適用 ──────────────────────────────────────────────

    @Test
    @DisplayName("欄位不存在 → null（呼叫端據此判斷『沒設定』）")
    void unknownFieldIsNull() {
        ServiceTask task = serviceTask(stringField("to", "a@x.com"));

        assertThat(BpmnFieldSupport.field(task, "subject", execution)).isNull();
    }

    @Test
    @DisplayName("task 為 null（掛錯位置）→ null")
    void nullTaskIsNull() {
        assertThat(BpmnFieldSupport.field(null, "to", execution)).isNull();
    }

    @Test
    @DisplayName("欄位名稱空白或 execution 為 null → null")
    void blankNameOrNullExecutionIsNull() {
        ServiceTask task = serviceTask(stringField("to", "a@x.com"));

        assertThat(BpmnFieldSupport.field(task, " ", execution)).isNull();
        assertThat(BpmnFieldSupport.field(task, "to", null)).isNull();
    }

    @Test
    @DisplayName("同名欄位取第一個（Flowable 的 field 名稱慣例上不重複）")
    void firstMatchingFieldWins() {
        ServiceTask task = serviceTask(
                stringField("to", "first@x.com"),
                stringField("to", "second@x.com"));

        assertThat(BpmnFieldSupport.field(task, "to", execution)).isEqualTo("first@x.com");
    }
}
