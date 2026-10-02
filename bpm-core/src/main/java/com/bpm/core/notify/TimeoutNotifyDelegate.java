package com.bpm.core.notify;

import org.flowable.bpmn.model.BoundaryEvent;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.FlowNode;
import org.flowable.bpmn.model.SequenceFlow;
import org.flowable.bpmn.model.UserTask;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.TaskService;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.flowable.identitylink.api.IdentityLink;
import org.flowable.identitylink.api.IdentityLinkType;
import org.flowable.task.api.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * 邊界計時器到期時的逾期提醒發送端（#23）。
 *
 * <h2>政策：只提醒，不動作</h2>
 *
 * <p>計時器到期<b>不</b>自動完成、退回或取消任務，只對「現任任務受理人」
 * 發一則提醒。因此 BPMN 必須使用<b>非中斷式</b> boundary timer
 * （{@code cancelActivity="false"}）—— 中斷式會取消任務，等於自動動作。
 * 本類別不碰任務狀態（不 complete／claim／setAssignee），任務與流程都
 * 原樣保留。中斷式接法會被明確擋下（log warn + no-op）：取消後的任務
 * 可能因為同一個 command 尚未 flush 而查得到，靠「查不到任務」擋不住，
 * 詳見 {@link #notifyTimedOut} 的檢查。
 *
 * <h2>⚠️ 為什麼不能直接用 {@code execution.getCurrentActivityId()} 當 boundary id</h2>
 *
 * <p>本工項的原始假設是「delegate 執行時 current activity 是 boundary event」。
 * 以 Flowable 7.2.0 原始碼與實測驗證後<b>不成立</b>：
 * {@code ContinueProcessOperation.continueThroughSequenceFlow} 在走 sequence
 * flow 時就把 execution 的 currentFlowElement 換成<b>目標節點</b>，
 * {@code BoundaryEventActivityBehavior} 也把 boundary 的 execution 交給
 * 下游使用。因此當 delegate 掛在 boundary 之後的 serviceTask 上時，
 * {@code getCurrentActivityId()} 是<b>serviceTask 的 id</b>，
 * {@code getAttachedToRef()} 也就無從取得。實測（H2 引擎，非中斷式
 * boundary → serviceTask）印出的是 {@code currentActivityId=notifyTimeout}，
 * 而任務仍在、流程仍在跑。
 *
 * <p>本類別因此支援兩種接法，順序即優先序：
 *
 * <ol>
 *   <li><b>delegate 直接掛在 boundary event 上</b>（{@code executionListener}）：
 *       current activity 就是 {@link BoundaryEvent}，直接使用。</li>
 *   <li><b>delegate 掛在 boundary 之後的 serviceTask 上</b>（本工項測試與
 *       設計器的主要用法）：由該節點的 incoming sequence flow 反推來源
 *       是不是 boundary event。恰好一個才採用；零個或多個都 log warn
 *       並 no-op —— 兩個 boundary 匯入同一個 delegate 時無法分辨哪一個
 *       到期，寧可不提醒也不要提醒錯的任務。</li>
 * </ol>
 *
 * <p>邊界條件：boundary 與 delegate 之間若夾了閘道等中繼節點，反推不到
 * boundary（incoming flow 的來源是閘道）→ log warn + no-op。設計器請把
 * delegate 直接接在 boundary 之後。
 *
 * <h2>為什麼要繞這一圈找任務</h2>
 *
 * <p>boundary event 的 execution <b>不是</b>任務本身，身上沒有 taskId。
 * 唯一的關聯是 BPMN 模型的 {@code attachedToRef}（任務的 definition key），
 * 再用 {@code processInstanceId + taskDefinitionKey} 查「現在還活著的任務」。
 * 查不到＝任務已完成 → no-op。
 *
 * <h2>收件人</h2>
 *
 * <p>與催辦／通知的既有天花板一致：assignee 優先；沒有 assignee 的候選任務
 * 取候選「人」（{@code candidate} identity link 的 userId）；候選群組沒有
 * email，略過。兩者皆無 → no-op。收件人解析仍只有一份，在
 * {@code EmailConsumer.resolveRecipients}；這裡只決定 payload 放
 * {@code assignee} 還是 {@code candidateUsers}。
 *
 * <h2>⚠️ fail-open：提醒失敗絕不影響流程</h2>
 *
 * <p>{@link #execute} 吞掉所有例外（只記 log）。delegate 在 Flowable 的
 * job 裡執行，例外往外丟會讓 timer job 失敗、重試，甚至讓案件卡住 ——
 * 提醒信寄不出去不該有這種後果。與 {@link NotifyPublisher#publish} 的
 * 語意一致（那邊吞的是 broker 例外）。BPMN 查不到、attachedToRef 不是
 * UserTask、任務已消失，全部走同一條 no-op 路徑。
 *
 * <h2>與 webhook {@code timeout} 的關係：不同機制，不要混淆</h2>
 *
 * <p>Flowable 7.2.0 <b>不發</b> timeout task event（2026-10-02 以
 * {@code javap -p -c} 證實；{@code WebhookTaskListener.matches} 有完整
 * 說明），所以 webhook 的 {@code event="timeout"} 設定沒有觸發點、
 * payload 分支也休眠。本工項<b>不復活</b>那條路：改用「BPMN boundary
 * timer ＋ delegate」作為替代機制，事件名是 {@code task_timeout}，
 * 走 {@code bpm.exchange/bpm.notify.task} 這條既有通知鏈。
 * 兩者名字裡都有 timeout，但一個是（不存在的）task event，
 * 一個是 boundary timer 驅動的 delegate 事件。
 */
@Component("timeoutNotifyDelegate")
public class TimeoutNotifyDelegate implements JavaDelegate {

    private static final Logger log = LoggerFactory.getLogger(TimeoutNotifyDelegate.class);

    private final RepositoryService repositoryService;
    private final TaskService taskService;
    private final NotifyPublisher publisher;

    /**
     * ⚠️ {@code @Lazy} 不可移除 —— 與 {@code ProcessCompletedListener}／
     * {@code WebhookConfigResolver} 是<b>同一個</b>結構性循環：
     * 本 bean 由 {@code FlowableConfig.processEngineConfigurer} 收進引擎的
     * beans map，而 map 是建構 processEngine 的輸入；若這裡直接注入
     * {@code RepositoryService}／{@code TaskService}（兩者都由 processEngine
     * 產生），啟動就會是 {@code BeanCurrentlyInCreationException}
     * （已實測：ApplicationContext 直接起不來）。延遲到第一次 execute
     * 才解析，循環就斷了。
     */
    public TimeoutNotifyDelegate(@Lazy RepositoryService repositoryService,
                                 @Lazy TaskService taskService,
                                 NotifyPublisher publisher) {
        this.repositoryService = repositoryService;
        this.taskService = taskService;
        this.publisher = publisher;
    }

    /**
     * Flowable 的進入點。所有失敗都吞在這裡 —— 理由見類別註解。
     */
    @Override
    public void execute(DelegateExecution execution) {
        try {
            notifyTimedOut(execution);
        } catch (Exception e) {
            log.warn("逾時提醒失敗（不影響流程）：processInstanceId={} activityId={}",
                    execution.getProcessInstanceId(), execution.getCurrentActivityId(), e);
        }
    }

    /** 可單獨測試的本文（package-private）：不吞例外，讓單元測試看得到行為。 */
    void notifyTimedOut(DelegateExecution execution) {
        BoundaryEvent boundary = resolveBoundaryEvent(execution);
        if (boundary == null) return;

        // 政策要求非中斷式。中斷式在 BPMN 層已經取消任務（自動動作），
        // 這裡再發提醒就是對一個已消失、無法處理的任務發誤導信；而且
        // 同一個 command 內任務列可能尚未 flush，查詢還看得到它 ——
        // 靠「查不到任務」擋不住，必須明確檢查。BPMN 的預設值是 true
        // （未寫 cancelActivity 就是中斷式），所以 false 必須明寫。
        if (boundary.isCancelActivity()) {
            log.warn("boundary event {} 是中斷式（cancelActivity=true）—— 逾時政策只提醒、不動作，"
                    + "請改用 cancelActivity=\"false\"；本次略過", boundary.getId());
            return;
        }

        var attached = boundary.getAttachedToRef();
        if (attached == null) {
            log.warn("boundary event {} 沒有 attachedToRef，無法找到任務，略過逾時提醒",
                    boundary.getId());
            return;
        }
        if (!(attached instanceof UserTask)) {
            log.warn("boundary event {} 掛在非 UserTask（{}），沒有任務可提醒，略過",
                    boundary.getId(), attached.getClass().getSimpleName());
            return;
        }

        List<Task> tasks = taskService.createTaskQuery()
                .processInstanceId(execution.getProcessInstanceId())
                .taskDefinitionKey(attached.getId())
                .list();
        if (tasks.isEmpty()) {
            log.debug("任務 {} 已不存在（可能已完成），略過逾時提醒", attached.getId());
            return;
        }

        for (Task task : tasks) {
            String assignee = task.getAssignee();
            List<String> candidates = List.of();
            if (assignee == null || assignee.isBlank()) {
                candidates = candidateUsers(task);
                if (candidates.isEmpty()) {
                    log.debug("任務 {} 沒有 assignee 也沒有候選人，略過逾時提醒", task.getId());
                    continue;
                }
            }
            publisher.taskTimedOut(task.getId(), task.getName(),
                    execution.getProcessInstanceId(), execution.getProcessDefinitionId(),
                    assignee, candidates);
        }
    }

    /**
     * 找出觸發本次執行的 boundary event。
     *
     * <p>兩種接法與取捨見類別註解。任何解析不出來的狀況都回
     * {@code null}（呼叫端 no-op），只有 warn 沒有例外。
     */
    private BoundaryEvent resolveBoundaryEvent(DelegateExecution execution) {
        String activityId = execution.getCurrentActivityId();
        if (activityId == null || activityId.isBlank()) {
            log.warn("逾時提醒找不到 current activity，略過：processInstanceId={}",
                    execution.getProcessInstanceId());
            return null;
        }

        BpmnModel model;
        try {
            model = repositoryService.getBpmnModel(execution.getProcessDefinitionId());
        } catch (Exception e) {
            log.warn("逾時提醒讀不到 BPMN model，略過：processDefinitionId={}",
                    execution.getProcessDefinitionId(), e);
            return null;
        }
        if (model == null || model.getMainProcess() == null) {
            log.warn("逾時提醒找不到 BPMN model／main process，略過：processDefinitionId={}",
                    execution.getProcessDefinitionId());
            return null;
        }

        FlowElement element = model.getMainProcess().getFlowElement(activityId, true);
        if (element == null) {
            log.warn("逾時提醒在 BPMN 裡找不到節點 {}，略過", activityId);
            return null;
        }

        // 接法 1：delegate 就是 boundary event 上的 executionListener。
        if (element instanceof BoundaryEvent boundary) {
            return boundary;
        }

        // 接法 2：delegate 掛在 boundary 之後的節點（通常是 serviceTask）。
        // 由 incoming sequence flow 反推，恰好一個 boundary 才採用。
        if (element instanceof FlowNode node) {
            List<BoundaryEvent> boundarySources = node.getIncomingFlows().stream()
                    .map(SequenceFlow::getSourceFlowElement)
                    .filter(BoundaryEvent.class::isInstance)
                    .map(BoundaryEvent.class::cast)
                    .distinct()
                    .toList();
            if (boundarySources.size() == 1) {
                return boundarySources.get(0);
            }
            log.warn("節點 {} 的上游有 {} 個 boundary event，無法分辨逾時來源，略過提醒",
                    activityId, boundarySources.size());
            return null;
        }

        log.warn("逾時提醒的 current activity {} 不是 boundary event 也不是可回溯的節點，略過",
                activityId);
        return null;
    }

    /**
     * 候選「人」清單。
     *
     * <p>候選群組（groupId）沒有 email，刻意不回傳 —— 與
     * {@code TaskController.taskRecipients}／{@code NotifyTaskListener} 同一條天花板。
     */
    private List<String> candidateUsers(Task task) {
        return taskService.getIdentityLinksForTask(task.getId()).stream()
                .filter(l -> IdentityLinkType.CANDIDATE.equals(l.getType()))
                .map(IdentityLink::getUserId)
                .filter(Objects::nonNull)
                .filter(u -> !u.isBlank())
                .distinct()
                .toList();
    }
}
