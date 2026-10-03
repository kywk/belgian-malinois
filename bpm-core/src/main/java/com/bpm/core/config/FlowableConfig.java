package com.bpm.core.config;

import com.bpm.core.notify.EmailNotifyDelegate;
import com.bpm.core.notify.NotifyTaskListener;
import com.bpm.core.notify.TimeoutNotifyDelegate;
import com.bpm.core.service.BpmPermissionService;
import com.bpm.core.service.BpmQueryService;
import com.bpm.core.service.OrgService;
import com.bpm.core.webhook.ProcessCompletedListener;
import org.flowable.spring.SpringProcessEngineConfiguration;
import org.flowable.spring.boot.EngineConfigurationConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

@Configuration
public class FlowableConfig {

    /**
     * Flowable 引擎設定。
     *
     * <h2>為什麼一定要呼叫 setBeans()</h2>
     *
     * <p>{@code beans} 未設定時，Flowable 會<b>把整個 ApplicationContext 當成
     * BPMN 運算式的命名空間</b> —— 執行期任何 Spring bean（{@code taskService}、
     * {@code dataSource}、{@code auditLogService}…）都能從運算式取用。
     *
     * <p>這一點推翻了本專案先前的認知：{@code BpmnLintService} 的 EL 白名單
     * 只是<b>部署前的字串檢查</b>，執行期完全沒有限制。而這是低程式碼平台 ——
     * 業務人員能在設計器編輯 BPMN，等於能在應用權限下呼叫任意 bean。
     * 詳見 {@code docs/plan/2026-09-28-security-audit.md} P0-2。
     *
     * <p>設定 {@code beans} 之後，命名空間收斂為下面這張 map。
     *
     * <h2>⚠️ map 必須包含 notifyTaskListener</h2>
     *
     * <p>{@code purchase-approval.bpmn20.xml} 使用
     * {@code delegateExpression="${notifyTaskListener}"}。漏掉它，現有流程
     * 會在建立任務時立刻壞掉。名稱與 {@code @Component("notifyTaskListener")}
     * 一致，不是類別名。
     *
     * <p>另外三個與 {@code BpmnLintService.EL_WHITELIST} 刻意保持一致
     * （{@code orgService}／{@code permService}／{@code bpmQueryService}），
     * 都是 bean 上明確指定的名稱而非預設的類別名。兩份清單若漂移，
     * 會出現「lint 過了但執行期炸掉」或反之的狀況。
     *
     * <p>⚠️ {@code applicantResolver}（#83）也在這兩份清單裡 ——
     * {@code BpmnExpressionBeanScopeTest} 直接從 {@code EL_WHITELIST} 推導
     * 測試對象，所以只加其中一邊就會被那個測試擋下來。
     *
     * <p>⚠️ {@code webhookTaskListener}（#67）只加在<b>這一份</b>，
     * <b>不</b>進 {@code BpmnLintService.EL_WHITELIST}。它是
     * {@code delegateExpression} 要解析的對象，不是 BPMN 運算式要呼叫的函式；
     * 放進 EL 白名單等於宣告「運算式可以拿到它」，而它不該被拿到。
     * {@code notifyTaskListener} 與 {@code timeoutNotifyDelegate}（#23）
     * 走的是同一條分界。
     *
     * <h2>這不是完整的修補</h2>
     *
     * <p>{@code setBeans()} 限制的是「哪些 bean 在命名空間內」，<b>不會</b>
     * 阻止 EL 對字面值做反射（{@code ${''.getClass().forName(...)}}）。
     * 那條路需要 lint 改用 AST 走訪並拒絕 {@code getClass}/{@code forName}
     * 等 member access —— 見審查建議 (b)，尚未施作。
     */
    @Bean
    public EngineConfigurationConfigurer<SpringProcessEngineConfiguration> processEngineConfigurer(
            ProcessCompletedListener processCompletedListener,
            com.bpm.core.engine.UnreachableTaskListener unreachableTaskListener,
            // #96：完成路徑的通知收斂。註冊成全域 event listener，HTTP 與
            // 外部 API 兩條完成路徑都經過同一份判定（見該類別註解）。
            com.bpm.core.notify.CompletionNotifyListener completionNotifyListener,
            OrgService orgService,
            com.bpm.core.service.InitialAssigneeResolver assigneeResolver,
            com.bpm.core.service.ApplicantResolver applicantResolver,
            // #47：任何 UserTask 的執行期動態審核人。與 assigneeResolver 的
            // 分工是「第一個任務 vs 所有任務」，且套代理人、找不到人時拋例外。
            com.bpm.core.service.DynamicAssigneeResolver dynamicAssignee,
            BpmPermissionService permService,
            BpmQueryService bpmQueryService,
            NotifyTaskListener notifyTaskListener,
            TimeoutNotifyDelegate timeoutNotifyDelegate,
            com.bpm.core.webhook.WebhookTaskListener webhookTaskListener,
            EmailNotifyDelegate emailNotifyDelegate,
            com.bpm.core.engine.DataValidationDelegate dataValidationDelegate,
            com.bpm.core.engine.ExternalApiDelegate externalApiDelegate,
            com.bpm.core.engine.BlankAssigneeNormalizingInterceptor blankAssigneeNormalizingInterceptor) {
        return config -> {
            config.setEventListeners(List.of(processCompletedListener, unreachableTaskListener,
                    completionNotifyListener));
            // #91 方向 B：所有 UserTask 建立後，把求值為空白的 assignee 正規化成 null。
            // 必須是全域的 CreateUserTaskInterceptor —— 見該類別註解說明為什麼
            // engine event listener／per-BPMN task listener／ActivityBehaviorFactory
            // 都不行。它只在 handleAssignments 之後跑，所以是既有 row 的 UPDATE。
            config.setCreateUserTaskInterceptor(blankAssigneeNormalizingInterceptor);
            // ⚠️ Map.ofEntries 而非 Map.of：通用 delegate（#43／#48／#49）之後
            // 這張 map 會超過 Map.of 的 10 對上限。兩者都是不可變、不接受 null
            // 的 Map，語意相同。
            config.setBeans(Map.ofEntries(
                    Map.entry("orgService", orgService),
                    Map.entry("permService", permService),
                    Map.entry("bpmQueryService", bpmQueryService),
                    // 第一個任務的受理人判斷（P2-7）。BPMN 的 managerReview 由它決定，
                    // 因為 initiator 在外部系統發起時是 system:<id>，不是人。
                    Map.entry("assigneeResolver", assigneeResolver),
                    // ⚠️ 補件關卡（#83）。三個 UserTask（leave-approval 的
                    // applicantRevision、purchase-approval 的 revisionFromManager
                    // 與 revisionFromFinance）原本寫死 ${initiator}，而外部系統發起時
                    // 那是 system:<id> —— 不是人，於是 TaskHolderGuard 的四個條件
                    // 全部不命中，沒有任何人能簽，案件靜默卡死。
                    // 必須與 BpmnLintService.EL_WHITELIST 同一份內容。
                    Map.entry("applicantResolver", applicantResolver),
                    // #47 執行期動態審核人（任何 UserTask）。三個方法都套代理人、
                    // 找不到人時拋例外；與 EL_WHITELIST 必須同步。
                    Map.entry("dynamicAssignee", dynamicAssignee),
                    // ⚠️ 不可移除：purchase-approval 的 delegateExpression 依賴它
                    Map.entry("notifyTaskListener", notifyTaskListener),
                    // ⚠️ 不可移除（#23）：設計師在流程的 UserTask 上掛
                    // 非中斷式 boundary timer，再把 delegateExpression 指向它。
                    // 與 webhookTaskListener／notifyTaskListener 同一條分界：
                    // 只加在這份 map，不進 BpmnLintService.EL_WHITELIST ——
                    // 它是 delegateExpression 的解析對象，不是運算式可呼叫的函式。
                    Map.entry("timeoutNotifyDelegate", timeoutNotifyDelegate),
                    // ⚠️ 不可移除（#67）：兩支 BPMN 的每個 UserTask 都以
                    // delegateExpression="${webhookTaskListener}" 引用它。
                    // 漏掉它與漏掉 notifyTaskListener 的症狀完全相同 ——
                    // 任務建立時拋「無法解析 delegateExpression」，
                    // 而且是在部署之後、第一次送出案件時才發生。
                    Map.entry("webhookTaskListener", webhookTaskListener),
                    // #43 通用寄信 delegate。與 timeoutNotifyDelegate／
                    // webhookTaskListener 同一條分界：只加在這份 map，
                    // 不進 BpmnLintService.EL_WHITELIST —— 它是
                    // delegateExpression 的解析對象，不是運算式可呼叫的函式。
                    Map.entry("emailNotifyDelegate", emailNotifyDelegate),
                    // #48 通用資料驗證 delegate。失敗丟 BpmnError
                    // （errorCode=DATA_VALIDATION_FAILED），由設計師用 boundary
                    // error 接住；同樣只加在這份 map，不進 EL 白名單。
                    Map.entry("dataValidationDelegate", dataValidationDelegate),
                    // #49 通用外部 API delegate。🔴 它的 url 來自 BPMN，
                    // 因此 WebhookUrlPolicy（唯一的 SSRF 閘門）是它的安全相依。
                    // 同樣只加在這份 map，不進 EL 白名單。
                    Map.entry("externalApiDelegate", externalApiDelegate)));
        };
    }
}
