package com.bpm.core.engine;

import org.flowable.bpmn.model.FieldExtension;
import org.flowable.bpmn.model.ServiceTask;
import org.flowable.common.engine.impl.el.ExpressionManager;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.impl.util.CommandContextUtil;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * BPMN {@code flowable:field} 的共用讀取器（#43／#48／#49）。
 *
 * <h2>⚠️ 為什麼不用 Flowable 的欄位注入，改在 execute 時讀 model</h2>
 *
 * <p>{@code delegateExpression} 上的 {@code flowable:field} 官方機制是
 * <b>每次執行對解析出來的 bean 呼叫 setter</b>。以 {@code javap} 證實
 * Flowable 7.2.0 的行為：
 *
 * <ul>
 *   <li>{@code ProcessEngineConfigurationImpl} 建構子把
 *       {@code delegateExpressionFieldInjectionMode} 初始化為
 *       {@code DelegateExpressionFieldInjectionMode.MIXED}
 *       （位元碼 {@code getstatic MIXED; putfield}）。</li>
 *   <li>{@code DelegateExpressionUtil.resolveDelegateExpression(..., List&lt;FieldDeclaration&gt;)}
 *       在 MIXED 模式下呼叫
 *       {@code ClassDelegate.applyFieldDeclaration(fields, bean, false)} ——
 *       也就是<b>每個 execution 都對同一個 bean 做 setter 注入</b>。</li>
 * </ul>
 *
 * <p>本專案的 delegate 都是 <b>Spring 單例</b>（{@code @Component}）。
 * 兩個流程同時執行時，A 的欄位值會覆蓋 B 的欄位值，B 可能把信寄給 A 的
 * 收件人 —— 這是資料外洩等級的競態，而且沒有例外、沒有 log。因此三個
 * 通用 delegate 都<b>不</b>依賴 setter 注入，改成在 {@code execute} 時
 * 從 BPMN model 讀欄位：{@code execution.getCurrentFlowElement()} 轉
 * {@link ServiceTask}，再取 {@code getFieldExtensions()}。讀到的是這次
 * 執行所屬節點的定義，天然 thread-safe、可單元測試。
 *
 * <h2>解析規則（兩個來源、兩套語意）</h2>
 *
 * <ol>
 *   <li><b>{@code expression="..."}（Flowable 官方語意）</b>：在引擎的
 *       command context 內用 {@link ExpressionManager} 求值，支援完整 JUEL
 *       （例如 {@code expression="${amount > 1000}"}）。求值結果為
 *       {@code null} 時回 {@code null}。不在 command context 時（純單元測試、
 *       或有人直接 new 出來呼叫）退回下面的 {@code ${var}} 子集 —— 這樣
 *       單元測試不需要啟動引擎就能驗簡單運算式。</li>
 *   <li><b>{@code stringValue="..."}</b>：只做 {@code ${var}} 文字替換。
 *       Flowable 官方<b>不</b>對 stringValue 求值；本專案刻意支援，
 *       讓設計器能寫 {@code to="a@x.com,${approver}"} 這種混合字串。</li>
 * </ol>
 *
 * <h2>{@code ${var}} 替換的明確語意</h2>
 *
 * <ul>
 *   <li>變數以 {@code execution.getVariable(name)} 取得（當前 execution 的
 *       可視範圍，含流程變數與區域變數）。</li>
 *   <li>變數<b>不存在或值為 {@code null} → 替換成空字串</b>。呼叫端因此
 *       看得到「欄位存在但內容塌掉」的結果（例如 {@code to} 只剩逗號分隔的
 *       空項），可以據以 no-op；若原樣保留 {@code ${x}}，通知就會寄到一個
 *       字面上不存在的位址，更糟。</li>
 *   <li>名稱前後空白會 trim（{@code ${ approver }} 等同 {@code ${approver}}）。</li>
 *   <li><b>只替換一輪</b>：變數值裡再出現 {@code ${...}} 不會被展開 ——
 *       避免遞迴與無限展開。</li>
 * </ul>
 *
 * <h2>執行期 API 的實測（不是猜的）</h2>
 *
 * <p>取得 {@link ExpressionManager} 的路徑是
 * {@code CommandContextUtil.getProcessEngineConfiguration()}，不是
 * {@code Context.getProcessEngineConfiguration()} —— 後者在 Flowable 7.2.0
 * <b>不存在</b>（{@code javap org.flowable.common.engine.impl.context.Context}
 * 只有 {@code getCommandContext()}）。{@code CommandContextUtil} 會從
 * {@code Context} 的 {@code ThreadLocal} 堆疊取出當前 command context 的
 * engine config，<b>取不到時回 {@code null}</b>（位元碼已確認 {@code ifnull}）。
 * ThreadLocal 意味著它對每個執行緒各自成立，而引擎的
 * {@code ExpressionManager} 本身是共用的無狀態工廠 —— 兩者合起來對
 * 單例 delegate 是安全的。
 */
public final class BpmnFieldSupport {

    /** {@code ${name}}：name 不含 {@code }}；名稱在此階段允許空白，稍後 trim。 */
    private static final Pattern VARIABLE = Pattern.compile("\\$\\{([^}]*)}");

    private BpmnFieldSupport() {
    }

    /**
     * 節點上是否存在指定名稱的 {@code flowable:field}。
     *
     * <p>與 {@link #field} 的差別是「有沒有設定」與「設定求值成什麼」：
     * {@code field()} 在運算式求值為 {@code null} 時也回 {@code null}，
     * 呼叫端若需要區分「沒設定這個欄位」與「設定了但求值為 null」
     * （例如 validation 的 condition 欄位：前者略過、後者算失敗），
     * 必須先問這裡。
     */
    public static boolean hasField(ServiceTask task, String name) {
        if (task == null || name == null || name.isBlank()) return false;
        for (FieldExtension field : task.getFieldExtensions()) {
            if (name.equals(field.getFieldName())) return true;
        }
        return false;
    }

    /**
     * 讀出節點上指定名稱的 {@code flowable:field} 並解析成字串。
     *
     * <p>解析規則見類別註解。欄位本身<b>不存在</b>時回 {@code null}
     * （呼叫端據此判斷「沒設定」）；欄位存在但求值為 {@code null} 也回
     * {@code null}；求值成空字串則回 {@code ""}。
     *
     * @param task      delegate 所在的 serviceTask。實務上由
     *                  {@code execution.getCurrentFlowElement()} 取得；
     *                  {@code null} 或不是 {@link ServiceTask} 時回
     *                  {@code null}（呼叫端負責記 log／決定語意）。
     * @param name      欄位名稱（例如 {@code to}／{@code requiredVariables}）。
     * @param execution 當前 execution，用於變數替換與運算式求值。
     * @return 解析結果；找不到欄位回 {@code null}
     */
    public static String field(ServiceTask task, String name, DelegateExecution execution) {
        if (task == null || name == null || name.isBlank() || execution == null) return null;

        FieldExtension found = null;
        for (FieldExtension field : task.getFieldExtensions()) {
            if (name.equals(field.getFieldName())) {
                found = field;
                break;
            }
        }
        if (found == null) return null;

        // expression 優先：那是 Flowable 官方會求值的來源。同一欄位同時有
        // expression 與 stringValue 時，官方 applyFieldDeclaration 也是
        // 先看 expression（stringValue 只有在沒有 expression 時才用）。
        String expression = found.getExpression();
        if (expression != null && !expression.isBlank()) {
            return evaluate(expression, execution);
        }
        String value = found.getStringValue();
        if (value == null) return null;
        return substitute(value, execution);
    }

    /**
     * 用引擎的 {@link ExpressionManager} 求值；沒有 command context 時
     * 退回 {@code ${var}} 替換子集（見類別註解）。
     *
     * <p>package-private：讓單元測試直接釘住「引擎路徑 vs 後備路徑」。
     */
    static String evaluate(String expression, DelegateExecution execution) {
        ExpressionManager expressionManager = expressionManager();
        if (expressionManager == null) {
            return substitute(expression, execution);
        }
        Object value = expressionManager.createExpression(expression).getValue(execution);
        return value == null ? null : value.toString();
    }

    /**
     * {@code ${var}} 文字替換。找不到的變數（含 {@code null}）替換成空字串，
     * 單輪、不遞迴 —— 完整語意見類別註解。
     */
    static String substitute(String raw, DelegateExecution execution) {
        Matcher matcher = VARIABLE.matcher(raw);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1).trim();
            Object value = name.isEmpty() ? null : execution.getVariable(name);
            matcher.appendReplacement(out,
                    Matcher.quoteReplacement(value == null ? "" : value.toString()));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /**
     * 當前 command context 的 {@link ExpressionManager}；不在引擎執行緒內時
     * 回 {@code null}（見類別註解「執行期 API 的實測」）。
     */
    static ExpressionManager expressionManager() {
        var configuration = CommandContextUtil.getProcessEngineConfiguration();
        return configuration == null ? null : configuration.getExpressionManager();
    }
}
