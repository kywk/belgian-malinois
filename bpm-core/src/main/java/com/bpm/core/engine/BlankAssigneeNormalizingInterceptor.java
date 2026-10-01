package com.bpm.core.engine;

import org.flowable.engine.impl.util.TaskHelper;
import org.flowable.engine.interceptor.CreateUserTaskAfterContext;
import org.flowable.engine.interceptor.CreateUserTaskBeforeContext;
import org.flowable.engine.interceptor.CreateUserTaskInterceptor;
import org.flowable.task.service.impl.persistence.entity.TaskEntity;
import org.springframework.stereotype.Component;

/**
 * 把「求值為空白」的 assignee 正規化成 {@code null}（#91 方向 B）。
 *
 * <h2>它修的缺陷是什麼</h2>
 *
 * <p>BPMN 寫 {@code flowable:assignee="${dept}"}，而 {@code dept} 在執行期
 * 求值成空白字串（例如 {@code "  "}）時，assignee 會被寫成<b>空白字串，不是
 * null</b>。{@code UserTaskActivityBehavior.handleAssignments} 對 assignee
 * 有 {@code StringUtils.isNotEmpty} 的前置判斷，但它判斷的是<b>運算式字串
 * 本身</b>（{@code "${dept}"} 非空），<b>不是求值結果</b> —— 所以求值出的
 * {@code "  "} 照樣被寫入。
 *
 * <p>而 Flowable 的候選群組／候選人查詢帶著 {@code RES.ASSIGNEE_ IS NULL}
 * （{@code Task.xml} 的 {@code selectTaskByCandidateGroup*}）。於是
 * <b>只要 ASSIGNEE_ 不是 null（即使是空白），候選人就看不到那個任務</b>：
 * 任務建立成功、沒有例外、沒有錯誤訊息，案件靜默卡死。
 *
 * <p>這一條路徑<b>管理員部署的 BPMN 就能觸發，不經任何 API</b> —— 這也是
 * {@link UnreachableTaskListener}（#89）只能事後告警、擋不住它的原因。
 * 本類別是<b>寫入端的根治</b>，與 listener 的「觀察、告警」互補。
 *
 * <h2>⚠️ 為什麼是 {@code CreateUserTaskInterceptor}（本項的核心決策）</h2>
 *
 * <p>PM 已用位元碼在 Flowable 7.2.0 查證，
 * {@code UserTaskActivityBehavior.execute(DelegateExecution)} 的呼叫順序是：
 * <ol>
 *   <li>{@code TaskService.createTask()}（建立 entity）</li>
 *   <li>{@code CreateUserTaskInterceptor.beforeCreateUserTask(...)}</li>
 *   <li>{@code TaskHelper.insertTask(...)} ← <b>任務落地</b></li>
 *   <li>{@code handleAssignments(...)} ← 於此把空白字串寫進 assignee</li>
 *   <li>{@code CreateUserTaskInterceptor.afterCreateUserTask(...)}
 *       ← <b>全域、且在 handleAssignments 之後</b></li>
 * </ol>
 *
 * <p>所以本 interceptor 是唯一同時滿足以下兩者的切入點：
 * <ul>
 *   <li><b>全域</b>：對所有 UserTask 生效，包含使用者自行部署的 BPMN，
 *       不像 per-BPMN task listener 只對「掛了 listener 的 BPMN」生效
 *       —— 那是同一條規則兩套形狀，正是本專案硬規則「規則只能有一份」
 *       要避免的（#84／#86／#87 的成因）。</li>
 *   <li><b>在 handleAssignments 之後</b>：是既有 row 的 UPDATE，
 *       不是 #86 那種「INSERT 排在 DELETE 之前」的 flush 排序問題。</li>
 * </ul>
 *
 * <h2>⚠️ 為什麼不用其他三個切入點</h2>
 *
 * <ul>
 *   <li><b>engine event listener {@code TASK_CREATED}</b>：它在
 *       {@code beforeCreateUserTask} 之後、任務落地<b>之前</b>觸發，此處
 *       改動 entity 仍在同一個 command 內、且事件 listener 的職責是<b>觀察</b>
 *       而不是改資料（見 {@link UnreachableTaskListener} 的類別註解
 *       「只告警，不硬擋」）。把「修正寫入值」塞進觀察者是職責錯置。</li>
 *   <li><b>per-BPMN task listener</b>：只對掛了 listener 的 BPMN 生效，
 *       使用者新部署的流程不會有它 —— 同一條規則兩套形狀。</li>
 *   <li><b>{@code ActivityBehaviorFactory} 覆寫 {@code handleAssignments}</b>：
 *       要複製一份 Flowable 的指派邏輯（候選人／候選群組／owner／事件／
 *       歷史），任何一處漂移都是新的缺陷；而且它改的是引擎核心行為，
 *       影響面遠大於「把空白正規化成 null」這一件事。</li>
 * </ul>
 *
 * <h2>⚠️ 為什麼是等於 null 而不是丟掉該 UserTask 的指派</h2>
 *
 * <p>正規化成 {@code null} 之後，Flowable 的候選查詢（帶
 * {@code ASSIGNEE_ IS NULL}）就會命中 —— 有候選群組的關卡立刻恢復可見，
 * 這正是 {@code flowable:assignee="${var}"} 求值為空白時使用者想要語意
 * （「這個人這次沒填到，交給候選群組」）。這也與
 * {@code UserTaskActivityBehavior} 對 {@code flowable:assignee=""} 字面值
 * 的既有行為一致：空字串被 {@code isNotEmpty} 擋掉、assignee 維持 null。
 * 本類別只是把「運算式求值出的空白」對齊到同一個結果。
 *
 * <p>⚠️ 本類別<b>不</b>改動非空白的 assignee（真人 id 原樣保留），
 * 也<b>不</b>碰 {@code owner}／候選人／候選群組 —— 職責單一。
 */
@Component
public class BlankAssigneeNormalizingInterceptor implements CreateUserTaskInterceptor {

    /**
     * 刻意留空。
     *
     * <p>{@code beforeCreateUserTask} 階段 {@code handleAssignments} 還沒跑，
     * 求值後的 assignee 還不存在於 {@code TaskEntity} 上 —— 此時沒有東西可以
     * 正規化。正規化<b>必須</b>在 {@code handleAssignments} 之後（見類別註解）。
     */
    @Override
    public void beforeCreateUserTask(CreateUserTaskBeforeContext context) {
        // 見 javadoc：這裡沒有可正規化的值。
    }

    /**
     * 任務落地且指派完成後，把空白 assignee 收斂成 {@code null}。
     *
     * <p>用 {@code TaskHelper.changeTaskAssignee(task, null)} 而不是直接
     * {@code task.setAssignee(null)}：前者是 Flowable 自己的正規入口，會
     * 一併處理歷史紀錄、指派事件與 identity link（assignee 為 null 時
     * {@code addAssigneeIdentityLinks} 不會建 link），語意與引擎其他地方
     * 改指派完全一致 —— 直接 setter 會繞過這些副作用。
     */
    @Override
    public void afterCreateUserTask(CreateUserTaskAfterContext context) {
        TaskEntity task = context.getTaskEntity();
        if (task == null) return;

        String assignee = task.getAssignee();
        // ⚠️ 用 isBlank() 而不是 isEmpty()：缺陷的形狀是「純空白」，
        // isEmpty() 會放過 "  "，而那正是候選查詢不命中的成因。
        if (assignee != null && assignee.isBlank()) {
            TaskHelper.changeTaskAssignee(task, null);
        }
    }
}
