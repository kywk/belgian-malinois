package com.bpm.core.engine;

import org.flowable.bpmn.model.ServiceTask;
import org.flowable.engine.delegate.BpmnError;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 通用資料驗證 delegate（#48）：流程走到節點時檢查變數與條件，不合格就擋下。
 *
 * <pre>{@code
 * <serviceTask id="validate" flowable:delegateExpression="${dataValidationDelegate}">
 *   <extensionElements>
 *     <flowable:field name="requiredVariables" stringValue="leaveType,days"/>
 *     <flowable:field name="condition" expression="${days > 0}"/>
 *   </extensionElements>
 * </serviceTask>
 * }</pre>
 *
 * <h2>欄位</h2>
 * <ul>
 *   <li>{@code requiredVariables}：逗號分隔的<b>變數名稱</b>清單（直接寫名稱，
 *       不要包 {@code ${}}；名稱本身不會被當成運算式求值 —— 欄位仍經
 *       {@link BpmnFieldSupport} 解析，若寫了 {@code ${x}} 會先被替換成
 *       x 的值，那是誤用）。每個變數必須<b>存在且非空白</b>：{@code null}、
 *       空字串、純空白都算失敗。非字串值以 {@code toString()} 判斷
 *       （{@code 0}／{@code false} 都是「有值」）。</li>
 *   <li>{@code condition}（可選）：{@code expression="${...}"} 形式，用引擎
 *       {@link org.flowable.common.engine.impl.el.ExpressionManager} 求值，
 *       支援完整 JUEL（{@code ${days > 0}}、{@code ${status == 'DRAFT'}}）。
 *       求值結果為 false 算失敗；結果為 {@code null} 也算失敗（「不知道」
 *       不該當成「通過」）。</li>
 * </ul>
 *
 * <h2>失敗語意：{@link BpmnError}，不是 FlowableException、不是 fail-open</h2>
 *
 * <p>驗證失敗丟 {@code BpmnError("DATA_VALIDATION_FAILED", 訊息)}：
 *
 * <ul>
 *   <li><b>可建模</b>：設計師在節點上掛 boundary error event
 *       （{@code errorCode="DATA_VALIDATION_FAILED"}）就能接住、走替代路徑
 *       （退回補件、通知申請人、走人工審核）。{@code FlowableException}
 *       是 Java 例外，BPMN 沒有東西可以接住它 —— 只能讓案件炸掉，
 *       所以不採用。</li>
 *   <li><b>不 fail-open</b>：通知（{@code EmailNotifyDelegate}／
 *       {@code NotifyPublisher}）失敗可以吞，因為寄信是副作用；驗證是
 *       <b>閘門</b> —— 吞掉例外等於這個 delegate 從不擋人，裝了等於沒裝。
 *       沒接 boundary 時 BpmnError 會讓流程明顯失敗（Flowable 拋
 *       「No catching boundary event found for error with errorCode
 *       'DATA_VALIDATION_FAILED'」），而不是靜默放行。</li>
 * </ul>
 *
 * <p>訊息<b>指名</b>失敗的變數／條件（與 repo 的錯誤訊息風格一致），
 * 例如：{@code 資料驗證失敗：變數 'days' 不存在或為空白；condition 求值為 false}。
 *
 * <h2>設定錯誤也是失敗，不是 no-op</h2>
 *
 * <p>{@code requiredVariables} 與 {@code condition} <b>都沒有設定</b>時
 * （包含 delegate 掛在非 ServiceTask 上、讀不到任何欄位）丟 BpmnError。
 * 一個「什麼都不驗」的驗證節點是最危險的失敗型態：流程照跑、看起來有把關、
 * 實際上沒有。寧可讓它當場失敗並在訊息裡說明。
 *
 * <p>反之，{@code condition} 本身求值丟例外（運算式語法錯、參照不存在的
 * 識別字）也翻成同一種 BpmnError —— 驗證閘門無法回答「通過或不通過」時
 * 就是不能通過，而且訊息帶著底層原因，設計師看得到要修什麼。
 *
 * <h2>⚠️ 不依賴 {@code flowable:field} 的 setter 注入</h2>
 *
 * <p>欄位一律用 {@link BpmnFieldSupport} 從 model 讀取；單例 bean 的注入
 * 競態與 7.2.0 預設 MIXED 模式的位元碼證據寫在該類別註解，三個通用
 * delegate 共用同一份說明。
 */
@Component("dataValidationDelegate")
public class DataValidationDelegate implements JavaDelegate {

    private static final Logger log = LoggerFactory.getLogger(DataValidationDelegate.class);

    /** boundary error event 要接住的 errorCode。 */
    public static final String ERROR_CODE = "DATA_VALIDATION_FAILED";

    /**
     * 沒有引擎依賴（{@link BpmnFieldSupport} 在執行期才取 command context），
     * 因此不需要 {@code @Lazy}：建構本 bean 不會回頭依賴 processEngine。
     */
    public DataValidationDelegate() {
    }

    @Override
    public void execute(DelegateExecution execution) {
        validate(execution);
    }

    /**
     * 可單獨測試的本文（package-private）：不吞例外，讓單元測試看得到
     * BpmnError 與訊息。
     */
    void validate(DelegateExecution execution) {
        ServiceTask task = execution.getCurrentFlowElement() instanceof ServiceTask st ? st : null;

        List<String> failures = new ArrayList<>();
        List<String> required = parseVariableNames(
                BpmnFieldSupport.field(task, "requiredVariables", execution));
        for (String name : required) {
            Object value = execution.getVariable(name);
            if (value == null || value.toString().isBlank()) {
                failures.add("變數 '" + name + "' 不存在或為空白");
            }
        }

        boolean hasCondition = BpmnFieldSupport.hasField(task, "condition");
        if (hasCondition) {
            String result = null;
            boolean evaluationFailed = false;
            try {
                result = BpmnFieldSupport.field(task, "condition", execution);
            } catch (Exception e) {
                // 見類別註解：閘門無法回答「通過或不通過」時就是不能通過。
                evaluationFailed = true;
                failures.add("condition 無法求值（" + e.getMessage() + "）");
            }
            if (!evaluationFailed) {
                if (result == null) {
                    failures.add("condition 求值為 null（不視為通過）");
                } else if (!truthy(result)) {
                    failures.add("condition 求值為 false");
                }
            }
        }

        if (required.isEmpty() && !hasCondition) {
            log.warn("DataValidationDelegate 沒有任何驗證規則，視為設定錯誤："
                    + "processInstanceId={} activityId={}",
                    execution.getProcessInstanceId(), execution.getCurrentActivityId());
            throw new BpmnError(ERROR_CODE,
                    "資料驗證失敗：未設定任何驗證規則（requiredVariables 與 condition 皆為空）"
                            + " —— 請補上欄位，或移除這個驗證節點");
        }

        if (!failures.isEmpty()) {
            String message = "資料驗證失敗：" + String.join("；", failures);
            log.info("DataValidationDelegate 擋下流程：processInstanceId={} activityId={} 原因={}",
                    execution.getProcessInstanceId(), execution.getCurrentActivityId(), message);
            throw new BpmnError(ERROR_CODE, message);
        }
    }

    /**
     * 逗號分隔的變數名稱：trim、去空白項、去重。與收件人解析同一條形狀，
     * 但這裡的每一項是變數名稱。
     */
    static List<String> parseVariableNames(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .distinct()
                .toList();
    }

    /**
     * condition 求值結果的真值判斷。
     *
     * <p>規則：{@code true}（不分大小寫）為真；數字（含數字字串）非 0 為真；
     * 其餘（含空字串、{@code false}、非數字文字）為假。這是 JUEL 對
     * Boolean／Number 的真值語意的近似 —— 引擎回傳的 Boolean／Number 會先
     * 被 {@link BpmnFieldSupport#field} 轉成字串，這裡再還原。
     */
    static boolean truthy(String value) {
        if (value == null) return false;
        String s = value.trim();
        if (s.equalsIgnoreCase("true")) return true;
        if (s.isEmpty() || s.equalsIgnoreCase("false")) return false;
        try {
            return Double.parseDouble(s) != 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
