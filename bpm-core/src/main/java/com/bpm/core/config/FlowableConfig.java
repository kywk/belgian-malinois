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
            OrgService orgService,
            com.bpm.core.service.InitialAssigneeResolver assigneeResolver,
            BpmPermissionService permService,
            BpmQueryService bpmQueryService,
            NotifyTaskListener notifyTaskListener) {
        return config -> {
            config.setEventListeners(List.of(processCompletedListener));
            config.setBeans(Map.of(
                    "orgService", orgService,
                    "permService", permService,
                    "bpmQueryService", bpmQueryService,
                    // 第一個任務的受理人判斷（P2-7）。BPMN 的 managerReview 由它決定，
                    // 因為 initiator 在外部系統發起時是 system:<id>，不是人。
                    "assigneeResolver", assigneeResolver,
                    // ⚠️ 不可移除：purchase-approval 的 delegateExpression 依賴它
                    "notifyTaskListener", notifyTaskListener));
        };
    }
}
