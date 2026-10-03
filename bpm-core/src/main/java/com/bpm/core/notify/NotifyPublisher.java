package com.bpm.core.notify;

import org.flowable.engine.TaskService;
import org.flowable.identitylink.api.IdentityLink;
import org.flowable.identitylink.api.IdentityLinkType;
import org.flowable.task.api.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 通知訊息的<b>唯一發送端</b>（#33 通知觸發事件完整化）。
 *
 * <h2>為什麼要收斂成一個元件</h2>
 *
 * <p>改動前只有 {@link NotifyTaskListener} 會送訊息，而它自己組 payload、
 * 自己呼叫 {@code rabbitTemplate}。本工項要把觸發點擴到
 * 「退回／拒絕／結案／認領／加簽／催辦」六種，如果每個呼叫端各自
 * {@code convertAndSend("bpm.exchange", "bpm.notify.task", ...)}，
 * 就會出現六份「事件名、收件人欄位、processDefinitionKey 要裁版號」
 * 的實作 —— 本 repo 反覆記載的缺陷成因（{@code #84}／{@code #86}／
 * {@code #87}：同一條規則有兩套形狀）。收斂在這裡之後，
 * {@code EmailConsumer} 那邊的契約（{@code event}／{@code assignee}／
 * {@code candidateUsers}／{@code processDefinitionKey}）只有一份。
 *
 * <h2>⚠️ 通知失敗<b>絕不</b>可以影響流程</h2>
 *
 * <p>{@code publish()} 吞掉所有例外（只記 log）。這不是「防禦性程式碼」，
 * 而是這個元件的核心語意：呼叫端包含 {@code TaskController.updateTask}
 * 的 {@code @Transactional} 簽核路徑與 {@code CountersignController} 的
 * 加簽路徑，若 RabbitMQ 不通時讓例外往外丟，<b>使用者會簽不了核</b>
 * —— 提醒信寄不出去不該讓簽核失敗。對照組：BPMN 的
 * {@code webhookTaskListener} 走的是 Flowable 的
 * {@code isFailOnException()=false}；controller 呼叫端沒有那層保護，
 * 所以保護必須在這裡。
 *
 * <h2>⚠️ P2-1 紅線</h2>
 *
 * <p>payload 不得包含流程變數、表單內容或簽核意見全文。
 * 這裡組出的欄位只有事件／任務／流程 id、事件名稱、受理人與申請人
 * ——與既有 {@code task_assigned} 同一組欄位。{@code vars} 只用於
 * <b>判定事件種類</b>（見 {@link #applicantEventFor}），值本身不外送。
 *
 * <h2>事件命名（本工項新增，已在交付報告說明）</h2>
 *
 * <ul>
 *   <li>{@code task_assigned} —— 既有。加簽子任務也走這個事件：
 *       對被加簽人而言它就是「一個指派給我的任務」，而 NotifyConfig
 *       的既有事件清單已經有它，不需要為加簽發明第六種事件。</li>
 *   <li>{@code task_claimed} —— 認領（spec §16.1：「通知已被認領（可選）」）。
 *       結尾用過去式，與 {@code task_assigned} 一致。</li>
 *   <li>{@code task_urged} —— 催辦。</li>
 *   <li>{@code process_returned}／{@code process_rejected}／
 *       {@code process_completed} —— {@code NotifyConfig.eventType} 既有清單。</li>
 *   <li>{@code task_timeout} —— #23 逾期提醒。⚠️ 它<b>不是</b> Flowable 的
 *       timeout task event：引擎 7.2.0 不發那個事件（已用位元碼證實，見
 *       {@code WebhookTaskListener.matches}），webhook 的 {@code timeout}
 *       選項因此沒有觸發點。替代機制是「BPMN 非中斷式 boundary timer ＋
 *       {@code timeoutNotifyDelegate}」，由 {@link TimeoutNotifyDelegate}
 *       呼叫 {@link #taskTimedOut}。名字像，機制不同，不要混淆。</li>
 *   <li>{@code process_cancelled} —— #7 殘餘收尾：申請人撤回案件後通知
 *       <b>現任受理人</b>。由 {@code ProcessController.cancelProcess} 呼叫
 *       {@link #processCancelled}；收件人規則與催辦／逾時同一條
 *       （{@link #taskRecipients}）。</li>
 * </ul>
 */
@Component
public class NotifyPublisher {

    /** exchange 與 routing key 是與 {@code RabbitMQConfig}／{@code EmailConsumer} 的契約。 */
    static final String EXCHANGE = "bpm.exchange";
    static final String ROUTING_KEY = "bpm.notify.task";

    private static final Logger log = LoggerFactory.getLogger(NotifyPublisher.class);

    private final RabbitTemplate rabbitTemplate;

    public NotifyPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    /**
     * 通知的唯一出口。
     *
     * <p>失敗只記 log，不往外拋 —— 理由見類別註解。
     */
    public void publish(Map<String, Object> payload) {
        try {
            rabbitTemplate.convertAndSend(EXCHANGE, ROUTING_KEY, payload);
        } catch (Exception e) {
            log.error("通知發送失敗（不影響流程）：event={} processInstanceId={}",
                    payload.get("event"), payload.get("processInstanceId"), e);
        }
    }

    /**
     * 任務指派（{@code task_assigned}）：BPMN 的 UserTask 建立，以及
     * <b>加簽子任務</b>建立。
     *
     * <p>加簽子任務是 {@code taskService.newTask()} 建立的 standalone task，
     * 不會經過 BPMN 的 {@code taskListener}（這也是它先前完全沒有通知的
     * 真正原因 —— 不是 assignee 太晚設定：{@code CountersignController}
     * 在 {@code saveTask} <b>之前</b>就設好 assignee 了）。因此由建立端
     * 呼叫這裡，與 BPMN 端共用同一個事件與同一份 payload 規則。
     *
     * @param candidateUsers 候選人。assignee 有值時傳 null／空；
     *                       沒有 assignee 的候選任務才會用到（EmailConsumer
     *                       對兩者的收件人解析是同一份）。
     */
    public void taskAssigned(String taskId, String taskName, String assignee,
                             List<String> candidateUsers, String processInstanceId,
                             String processDefinitionId, String initiator) {
        Map<String, Object> msg = base("task_assigned", taskId, taskName,
                processInstanceId, processDefinitionId, initiator);
        putIfPresent(msg, "assignee", assignee);
        if (candidateUsers != null && !candidateUsers.isEmpty()) {
            msg.put("candidateUsers", List.copyOf(candidateUsers));
        }
        publish(msg);
    }

    /**
     * 認領（{@code task_claimed}）。
     *
     * <p>收件人是<b>其他候選人</b>，認領者本人排除 —— spec §16.1 的語意是
     * 「通知已被認領（可選）」，收到信的人要做的事是「不用再處理這張」。
     * 因此 payload 刻意<b>不</b>放 {@code assignee}（那是 EmailConsumer 的
     * 收件人欄位，放了會把信寄回認領者自己），改用 {@code candidateUsers}，
     * 並以 {@code claimedBy} 讓模板可以寫出「由誰認領」。
     */
    public void taskClaimed(String taskId, String taskName, String processInstanceId,
                            String processDefinitionId, String claimedBy,
                            List<String> otherCandidates) {
        if (otherCandidates == null || otherCandidates.isEmpty()) {
            log.debug("任務 {} 沒有其他候選人，認領不發通知", taskId);
            return;
        }
        Map<String, Object> msg = base("task_claimed", taskId, taskName,
                processInstanceId, processDefinitionId, null);
        msg.put("candidateUsers", List.copyOf(otherCandidates));
        putIfPresent(msg, "claimedBy", claimedBy);
        publish(msg);
    }

    /**
     * 催辦（{@code task_urged}）。
     *
     * <p>收件人是目前的受理人（assignee）；候選任務則送候選人
     * ——與 {@code EmailConsumer.resolveRecipients} 的既有規則一致。
     * {@code applicant} 是發動催辦的人（只放在內文裡當署名，不是收件人）。
     */
    public void taskUrged(String taskId, String taskName, String processInstanceId,
                          String processDefinitionId, String assignee,
                          List<String> candidateUsers, String applicant) {
        Map<String, Object> msg = base("task_urged", taskId, taskName,
                processInstanceId, processDefinitionId, applicant);
        putIfPresent(msg, "assignee", assignee);
        if (candidateUsers != null && !candidateUsers.isEmpty()) {
            msg.put("candidateUsers", List.copyOf(candidateUsers));
        }
        publish(msg);
    }

    /**
     * 任務逾期提醒（{@code task_timeout}，#23）。
     *
     * <p>呼叫端是 {@link TimeoutNotifyDelegate}：BPMN 的非中斷式 boundary
     * timer 到期後，對<b>現任任務受理人</b>發提醒。任務保留、流程不變
     * —— 這個事件沒有任何「自動動作」語意，payload 與其他通知同一組
     * 非敏感欄位（P2-1 紅線：不放變數、不放 comment、不放表單內容）。
     *
     * <p>收件人欄位與催辦相同：assignee 優先，候選任務放
     * {@code candidateUsers}，由 {@code EmailConsumer.resolveRecipients}
     * 這唯一一份規則解析。沒有可送對象時 delegate 端已先 no-op，
     * 不會送出一則沒有收件人的空訊息。
     *
     * @param candidateUsers 候選人。有 assignee 時傳空清單即可。
     */
    public void taskTimedOut(String taskId, String taskName, String processInstanceId,
                             String processDefinitionId, String assignee,
                             List<String> candidateUsers) {
        Map<String, Object> msg = base("task_timeout", taskId, taskName,
                processInstanceId, processDefinitionId, null);
        putIfPresent(msg, "assignee", assignee);
        if (candidateUsers != null && !candidateUsers.isEmpty()) {
            msg.put("candidateUsers", List.copyOf(candidateUsers));
        }
        publish(msg);
    }

    /**
     * 案件撤回（{@code process_cancelled}，#7 殘餘收尾）：申請人撤案後
     * 通知<b>現任受理人</b>。
     *
     * <p>呼叫端是 {@code ProcessController.cancelProcess}。收件人必須在
     * {@code deleteProcessInstance} <b>之前</b>收集（任務隨實例一起消失，
     * 刪除後已無從得知受理人是誰），發送則在刪除成功之後 —— 被拒路徑
     * （400／403／404／409）因此保證零通知。時機的完整說明見該方法。
     *
     * <p>收件人欄位與催辦／逾時同一組契約：{@code assignee} 優先；
     * 候選任務沒有 assignee，放 {@code candidateUsers}，由
     * {@code EmailConsumer.resolveRecipients} 這唯一一份規則解析。
     * {@code applicant} 是撤回的人（署名用，放 {@code initiator}），
     * <b>不是</b>收件人 —— 申請人自己剛撤回，不需要再收一封「案件已撤回」。
     *
     * <p>payload 與其他事件同一組非敏感欄位（P2-1 紅線）：只有任務／流程
     * id、名稱、定義 key、受理人與申請人。沒有流程變數、沒有表單內容，
     * 也<b>沒有</b>撤回原因 —— {@code reason} 是呼叫端提供的自由文字，
     * 可能夾帶個資，不隨信外送。
     *
     * @param processDefinitionKey 流程定義 key（不是含版號的 id）。呼叫端
     *        以 {@link #extractProcessKey} 從任務的 id 裁出；這裡仍走
     *        {@code base} 同一條裁切路徑（對 key 是 idempotent），
     *        規則不因呼叫端而異。
     * @param candidateUsers 候選人。有 assignee 時傳空清單即可；要放什麼
     *        由 {@link #taskRecipients} 決定。
     */
    public void processCancelled(String taskId, String taskName, String processInstanceId,
                                 String processDefinitionKey, String assignee,
                                 List<String> candidateUsers, String applicant) {
        Map<String, Object> msg = base("process_cancelled", taskId, taskName,
                processInstanceId, processDefinitionKey, applicant);
        putIfPresent(msg, "assignee", assignee);
        if (candidateUsers != null && !candidateUsers.isEmpty()) {
            msg.put("candidateUsers", List.copyOf(candidateUsers));
        }
        publish(msg);
    }

    /**
     * 一個任務的通知收件人：assignee 優先；沒有 assignee 的候選任務取
     * 候選「人」；候選群組沒有 email，略過。
     *
     * <p>#7 殘餘收尾把這條規則抽成<b>共用實作</b>，撤回通知的收件人由此
     * 決定。判定與 {@code TaskController.taskRecipients} 刻意同形
     * （assignee 有值只送他；否則取 {@code candidate} identity link 的
     * userId）。
     *
     * <p>⚠️ {@code TaskController.taskRecipients} 與
     * {@code TimeoutNotifyDelegate.candidateUsers} 是同規則的既有副本
     * （各自成形於不同工項）。本次的檔案邊界不含那兩個檔案，因此尚未
     * 收斂；後續應把它們改成呼叫這裡，刪掉各自的實作，讓規則真的只有一份。
     *
     * @return 去重後的收件人；沒有任何可送對象時回空清單（呼叫端據此
     *         略過該任務，不送空訊息）
     */
    public static List<String> taskRecipients(TaskService taskService, Task task) {
        String assignee = task.getAssignee();
        if (assignee != null && !assignee.isBlank()) return List.of(assignee);
        return taskService.getIdentityLinksForTask(task.getId()).stream()
                .filter(l -> IdentityLinkType.CANDIDATE.equals(l.getType()))
                .map(IdentityLink::getUserId)
                .filter(u -> u != null && !u.isBlank())
                .distinct()
                .toList();
    }

    /**
     * 任務完成後對<b>申請人</b>的通知（{@code process_returned}／
     * {@code process_rejected}／{@code process_completed}）。
     *
     * <p>#96 起呼叫端是<b>全域</b>的 {@link CompletionNotifyListener}
     * （{@code FlowableConfig} 註冊），HTTP 與外部 API 兩條完成路徑共用；
     * 改動前只有 {@code TaskController} 的 complete 分支會呼叫。
     *
     * <h2>⚠️ 為什麼 {@code assignee} 放的是申請人</h2>
     *
     * <p>這三個事件的收件人不是「任務的受理人」（那是審核人），而是申請人。
     * {@code EmailConsumer.resolveRecipients} 的契約是「{@code assignee}
     * 優先、其次 {@code candidateUsers}」，所以這裡把申請人放在
     * {@code assignee} —— 收件人解析因此只需要一份規則，三種事件
     * （以及既有的 task_assigned／task_claimed／task_urged）全部共用。
     *
     * <h2>事件判定只有一份</h2>
     *
     * <p>{@link #applicantEventFor} 是唯一的判定函式：
     * 拒絕（{@code rejected=true}）優先於退回（{@code approved=false}）；
     * 結案（{@code process_completed}）只在「核准且流程真的結束」時發
     * —— 拒絕的單也結束了流程，若無條件再發一則「已核准完成」，
     * 申請人會同時收到「已被拒絕」與「已核准」兩封互相矛盾的信。
     *
     * @param applicant 自然人申請人（{@code onBehalfOf} 優先，其次
     *                  {@code initiator}）。{@code null} 或系統身分時不發
     *                  —— 寄給 {@code system:erp} 沒有意義。
     */
    public void taskCompleted(String processInstanceId, String processDefinitionId,
                              String taskId, String taskName, Map<String, Object> vars,
                              boolean processEnded, String applicant) {
        String event = applicantEventFor(vars, processEnded);
        if (event == null) return;
        if (applicant == null || applicant.isBlank()) {
            log.debug("流程 {} 的 {} 事件沒有自然人申請人，略過通知", processInstanceId, event);
            return;
        }
        Map<String, Object> msg = base(event, taskId, taskName,
                processInstanceId, processDefinitionId, applicant);
        msg.put("assignee", applicant);
        publish(msg);
    }

    /**
     * 任務完成後要不要通知申請人、要通知哪一種。
     *
     * <p>規則是 PM 指定的：退回＝{@code approved=false} 且不是拒絕；
     * 拒絕＝{@code rejected=true}；結案＝流程結束且結果是核准。
     * 條件順序即語意：{@code rejected} 先判，所以
     * {@code approved=false + rejected=true} 是拒絕而不是退回。
     *
     * <p>{@code process_returned}／{@code process_rejected} 不看
     * {@code processEnded}：退回一定還在跑（下一個是補件關卡），
     * 拒絕在 BPMN 裡通常直接結束，但自訂流程的 {@code rejected=true}
     * 也可能還繼續走 —— 兩者都該當下就通知申請人。
     *
     * <p>package-private：讓單元測試直接釘住這四條規則。端到端測試只證明
     * 「某個組合有送」，證明不了「拒絕沒有被誤判成退回」這種反例。
     */
    static String applicantEventFor(Map<String, Object> vars, boolean processEnded) {
        if (Boolean.TRUE.equals(vars.get("rejected"))) return "process_rejected";
        if (Boolean.FALSE.equals(vars.get("approved"))) return "process_returned";
        if (Boolean.TRUE.equals(vars.get("approved")) && processEnded) return "process_completed";
        return null;
    }

    /**
     * 這個任務是不是「補件」關卡（#33／#96）。
     *
     * <p>補件完成時的 vars 由 HTTP 路徑補上 {@code approved=false} 預設值，
     * 照 {@link #applicantEventFor} 判定會變成「您的申請已被退回」——
     * 申請人自己重送時收到退回信是荒謬的。排除條件因此是任務名稱。
     *
     * <p>⚠️ 判定只有一份：稽核端（{@code TaskController} 的
     * {@code TASK_RESUBMIT}）與通知端（{@link CompletionNotifyListener}）
     * 都呼叫這裡，不各自寫「name contains 補件」。
     */
    public static boolean isRevisionTask(String taskName) {
        return taskName != null && taskName.contains("補件");
    }

    /**
     * 組出共通欄位。
     *
     * <p>{@code processDefinitionKey} 必須是 key 而不是 id（含版號）——
     * 理由見 {@link NotifyTaskListener} 原本的長註解：EmailConsumer 拿它去查
     * NotifyConfig，存的是 key，送 id 會讓整套模板機制靜默失效
     * （security-audit P1-13）。
     */
    private static Map<String, Object> base(String event, String taskId, String taskName,
                                            String processInstanceId, String processDefinitionId,
                                            String initiator) {
        Map<String, Object> msg = new HashMap<>();
        msg.put("event", event);
        msg.put("timestamp", Instant.now().toString());
        putIfPresent(msg, "taskId", taskId);
        putIfPresent(msg, "taskName", taskName);
        putIfPresent(msg, "processInstanceId", processInstanceId);
        msg.put("processDefinitionKey", extractProcessKey(processDefinitionId));
        putIfPresent(msg, "initiator", initiator);
        return msg;
    }

    private static void putIfPresent(Map<String, Object> msg, String key, String value) {
        if (value != null && !value.isBlank()) msg.put(key, value);
    }

    /**
     * 從 processDefinitionId 取出 key。
     *
     * <p>格式是 {@code {key}:{version}:{deploymentId}}，因此取第一個冒號之前。
     * 已經是 key 的值（不含冒號）原樣回傳。
     *
     * <p>public 供 {@code ProcessController.cancelProcess} 使用：撤回路徑
     * 只有任務的 {@code processDefinitionId}，而通知 payload 必須是 key
     * （見類別註解）。裁切規則仍然只有這一份。
     */
    public static String extractProcessKey(String processDefinitionId) {
        if (processDefinitionId == null) return "";
        int idx = processDefinitionId.indexOf(':');
        return idx > 0 ? processDefinitionId.substring(0, idx) : processDefinitionId;
    }
}
