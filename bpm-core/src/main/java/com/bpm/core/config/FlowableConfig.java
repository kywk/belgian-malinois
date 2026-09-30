package com.bpm.core.config;

import com.bpm.core.notify.NotifyTaskListener;
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
     * {@code notifyTaskListener} 走的是同一條分界。
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
            OrgService orgService,
            com.bpm.core.service.InitialAssigneeResolver assigneeResolver,
            com.bpm.core.service.ApplicantResolver applicantResolver,
            BpmPermissionService permService,
            BpmQueryService bpmQueryService,
            NotifyTaskListener notifyTaskListener,
            com.bpm.core.webhook.WebhookTaskListener webhookTaskListener) {
        return config -> {
            config.setEventListeners(List.of(processCompletedListener, unreachableTaskListener));
            config.setBeans(Map.of(
                    "orgService", orgService,
                    "permService", permService,
                    "bpmQueryService", bpmQueryService,
                    // 第一個任務的受理人判斷（P2-7）。BPMN 的 managerReview 由它決定，
                    // 因為 initiator 在外部系統發起時是 system:<id>，不是人。
                    "assigneeResolver", assigneeResolver,
                    // ⚠️ 補件關卡（#83）。三個 UserTask（leave-approval 的
                    // applicantRevision、purchase-approval 的 revisionFromManager
                    // 與 revisionFromFinance）原本寫死 ${initiator}，而外部系統發起時
                    // 那是 system:<id> —— 不是人，於是 TaskHolderGuard 的四個條件
                    // 全部不命中，沒有任何人能簽，案件靜默卡死。
                    // 必須與 BpmnLintService.EL_WHITELIST 同一份內容。
                    "applicantResolver", applicantResolver,
                    // ⚠️ 不可移除：purchase-approval 的 delegateExpression 依賴它
                    "notifyTaskListener", notifyTaskListener,
                    // ⚠️ 不可移除（#67）：兩支 BPMN 的每個 UserTask 都以
                    // delegateExpression="${webhookTaskListener}" 引用它。
                    // 漏掉它與漏掉 notifyTaskListener 的症狀完全相同 ——
                    // 任務建立時拋「無法解析 delegateExpression」，
                    // 而且是在部署之後、第一次送出案件時才發生。
                    "webhookTaskListener", webhookTaskListener));
        };
    }
}
