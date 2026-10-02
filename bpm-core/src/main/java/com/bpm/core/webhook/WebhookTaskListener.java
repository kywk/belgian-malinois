package com.bpm.core.webhook;

import org.flowable.engine.delegate.TaskListener;
import org.flowable.task.service.delegate.DelegateTask;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 節點層 webhook 的投遞來源（#67 的斷線 (A) 與 (B)）。
 *
 * <h2>⚠️ 這個類別在 2026-09-30 之前是整條鏈路上唯一「什麼都沒做」的一環</h2>
 *
 * <p>改動前它確實把 payload 送進 {@code bpm.exchange}，但 payload 裡
 * <b>沒有 {@code __webhookUrl}</b> —— 因為沒有任何地方去讀節點上設定的 webhook。
 * 訊息被 {@code bpm.webhook.#} 的 binding 收到、進 queue、被
 * {@link WebhookConsumer} 讀出 {@code __webhookUrl} 拿到 null、
 * 記一行 debug 後丟掉。整條路徑沒有例外、沒有錯誤、沒有稽核，
 * 所以「投遞從來沒有發生過」這件事在監控上看起來像一切正常。
 *
 * <p>而且它<b>沒有被任何 BPMN 引用</b>，也不在
 * {@code FlowableConfig.setBeans()} 的命名空間裡，所以連「被呼叫」都不會發生。
 * 這是 #67 要接的三段斷線之一。
 *
 * <h2>為什麼仍然走 RabbitMQ 而不是直接發 HTTP</h2>
 *
 * <p>這是 2026-09-30 的既定決策：投遞維持既有的 RabbitMQ 路徑。
 * 理由在這裡記錄，因為它是本工項最容易被「順手優化」掉的地方：
 *
 * <ul>
 *   <li><b>簽核是同步路徑。</b>listener 在引擎的 command 裡被呼叫，
 *       也就是在「建立任務／完成任務」那個 DB 交易內。直接發 HTTP 會把
 *       外部系統的延遲與可用性灌進簽核回應 —— 接收端掛掉，使用者就簽不了。
 *       （同一個顧慮已經在 application.yml 的 org/perm 註解裡記錄过一次，
 *       那次造成過 Tomcat 執行緒自我死鎖。）</li>
 *   <li><b>SSRF 閘門與簽章只需要一份。</b>{@link WebhookUrlPolicy} 與 HMAC
 *       都在 consumer 端；走同一條路就不會出現「直發路徑繞過了閘門」。</li>
 *   <li><b>重試與 DLQ 已經存在。</b>queue 綁了 {@code dlx.exchange}，
 *       consumer 失敗會拋例外觸發重試。直發 HTTP 沒有這些。</li>
 * </ul>
 */
@Component("webhookTaskListener")
public class WebhookTaskListener implements TaskListener {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(WebhookTaskListener.class);

    private final RabbitTemplate rabbitTemplate;
    private final WebhookConfigResolver configResolver;

    public WebhookTaskListener(RabbitTemplate rabbitTemplate, WebhookConfigResolver configResolver) {
        this.rabbitTemplate = rabbitTemplate;
        this.configResolver = configResolver;
    }

    @Override
    public void notify(DelegateTask task) {
        String event = task.getEventName(); // create, complete, delete, assignment（timeout 見 matches 的說明）

        // ⚠️ 讀不到設定就<b>完全不发訊息</b>。
        //
        // 改動前是「無論有沒有設定都發一則沒有 __webhookUrl 的訊息」，
        // 交給 consumer 丟掉。那樣做的問題是 queue 上永遠有雜訊，
        // 而且真的發生投遞失敗時，DLQ 裡會混著從來不該投遞的訊息 ——
        // 排查時分不出哪一筆是「該投但失敗」與「本來就不該投」。
        List<WebhookConfig> configs = configResolver.resolve(
                task.getProcessDefinitionId(), task.getTaskDefinitionKey());
        if (configs.isEmpty()) {
            log.debug("節點 {} 沒有 webhook 設定，不發送（事件 {}）", task.getTaskDefinitionKey(), event);
            return;
        }

        List<WebhookConfig> matching = configs.stream()
                .filter(c -> matches(c.event(), event, task))
                .toList();
        if (matching.isEmpty()) {
            log.debug("節點 {} 有 {} 筆 webhook 設定，但沒有一筆對應事件 {}",
                    task.getTaskDefinitionKey(), configs.size(), event);
            return;
        }

        for (WebhookConfig config : matching) {
            Map<String, Object> payload = buildPayload(task, event);
            // ⚠️ 這兩個欄位是 WebhookConsumer 與本 listener 之間唯一的契約。
            // 欄位名不可改：consumer 用 payload.remove("__webhookUrl") 讀，
            // 改名等於把整條鏈路再斷一次（而斷掉之後不會有任何錯誤）。
            payload.put("__webhookUrl", config.url());
            payload.put("__webhookMethod", config.method());
            rabbitTemplate.convertAndSend("bpm.exchange", "bpm.webhook.task", payload);
            log.info("節點 {} 的 {} 事件已排入投遞佇列：{} {}",
                    task.getTaskDefinitionKey(), event, config.method(), config.url());
        }
    }

    /**
     * 這一筆設定是否對應目前發生的事件。
     *
     * <p>package-private：讓測試能直接驗這段對應規則。<b>測試若只是
     * 「跑一整個流程看看有沒有投遞」，這個函式裡最容易錯的三條規則
     * （all／reject 的推導／大小寫）都不會被抓到。</p>
     *
     * <h2>三條規則與理由</h2>
     *
     * <ol>
     *   <li><b>{@code all}</b> —— 每個事件都投遞。這是給「不管哪一步發生
     *       都要通知」用的逃生門。</li>
     *   <li><b>{@code reject}</b> —— Flowable <b>沒有</b> reject 這個 task event，
     *       但 spec §11.4 的前端選單有它。所以它被<b>推導</b>成
     *       「complete 事件且 {@code rejected == true}」。
     *       刻意不用「complete 事件且 approved != true」：那會把
     *       <b>退回</b>（approved=false、rejected 為 null）也算成駁回，
     *       而 spec 明確分開這兩者（見 CLAUDE.md 必讀事實 3）。</li>
     *   <li><b>大小寫不拘</b> —— 設計器輸入的 event 值不經過驗證，
     *       {@code Complete} 與 {@code complete} 指的是同一件事。</li>
     * </ol>
     *
     * <p>刻意<b>不</b>做的事：{@code timeout} 不在這裡被推導 ——
     * 它必須由 engine 以 task event 發出，而不是從其他事件揣測。</p>
     *
     * <p>⚠️ 但 Flowable 7.2.0 <b>不會發出 timeout task event</b>：engine 只在
     * create／assignment／complete／delete 四個時機呼叫
     * {@code ListenerNotificationHelper.executeTaskListeners}
     * （2026-10-02 以 {@code javap -p -c} 驗證整個 flowable-engine 7.2.0，
     * 沒有任何呼叫點傳入 {@code timeout}；{@code BaseTaskListener} 的常數
     * 也只有這四個加 {@code all}）。{@code timeout} 是 Camunda 的事件名。
     * 所以「設定 {@code timeout} 卻永遠收不到」目前是上游語意，
     * 不是這個類別能修的缺陷；payload 分支（見 {@link #buildPayload}）
     * 先依 spec §11.4 墊好，待替代機制（例如從 delete 推導，或換引擎）
     * 確定後即可生效。</p>
     */
    static boolean matches(String configEvent, String flowableEvent, DelegateTask task) {
        if (configEvent == null || configEvent.isBlank()) return false;
        if (flowableEvent == null) return false;
        String want = configEvent.trim();
        if ("all".equalsIgnoreCase(want)) return true;
        if (want.equalsIgnoreCase(flowableEvent)) return true;
        if ("reject".equalsIgnoreCase(want)
                && "complete".equalsIgnoreCase(flowableEvent)
                && Boolean.TRUE.equals(task.getVariable("rejected"))) {
            return true;
        }
        return false;
    }

    /**
     * 組出一個事件的 payload。
     *
     * <p>package-private（比照 {@link #matches} 的理由）：讓測試能直接釘住
     * 每個事件送出的欄位，不必繞一整個流程或反射。這裡是與外部系統的
     * 欄位契約，欄位名或內容錯了<b>不會有任何錯誤訊息</b> —— 只有把欄位
     * 本身釘住才防得住。</p>
     *
     * <p>⚠️ 敏感欄位的紅線（security-audit P2-1）見 {@code complete} 分支的
     * 說明：流程變數、簽核意見（comment）、簽核人姓名（operatorName）與
     * 候選人（candidateUsers／candidateGroups）一律不外送。</p>
     */
    Map<String, Object> buildPayload(DelegateTask task, String event) {
        // timestamp 與 overdueHours 共用同一個瞬間，兩者不會互相矛盾。
        Instant now = Instant.now();
        Map<String, Object> payload = new HashMap<>();
        payload.put("event", "task." + event);
        payload.put("timestamp", now.toString());
        payload.put("processInstanceId", task.getProcessInstanceId());
        payload.put("processDefinitionKey", extractProcessKey(task.getProcessDefinitionId()));
        payload.put("businessKey", task.getVariable("businessKey"));
        payload.put("taskId", task.getId());
        payload.put("taskName", task.getName());

        switch (event) {
            case "create" -> {
                payload.put("assignee", task.getAssignee());
                payload.put("dueDate", task.getDueDate());
            }
            case "complete" -> {
                payload.put("operatorId", task.getAssignee());
                Object approved = task.getVariable("approved");
                Object rejected = task.getVariable("rejected");
                if (Boolean.TRUE.equals(rejected)) {
                    payload.put("action", "rejected");
                    payload.put("rejectReason", task.getVariable("rejectReason"));
                } else if (Boolean.FALSE.equals(approved)) {
                    payload.put("action", "returned");
                } else {
                    payload.put("action", "approved");
                }
                // ⚠️ 刻意不外送流程變數（security-audit P2-1）。
                //
                // 表單欄位 id 就是流程變數名（spec §8.5），因此
                // getVariablesLocal() 等於把該關卡表單的全部內容
                // （可能含薪資、身分證號）原封不動送到外部 URL，
                // 而且沒有任何白名單。
                //
                // ⚠️ 這一段在 #67 之後<b>更</b>重要：投遞位址現在來自 BPMN，
                // 而 BPMN 是低程式碼平台裡業務人員可以編輯的內容 ——
                // 也就是說設定 URL 的人與決定送什麼資料出去的人可能是不同人。
                // 少送一個欄位就是少一條外洩路徑。
                //
                // 簽核結果已由上面的 action／rejectReason 表達；
                // 需要明細的接收端應回頭呼叫 API（那條路徑有授權）。
            }
            case "delete" -> {
                payload.put("assignee", task.getAssignee());
            }
            case "timeout" -> {
                // ⚠️ 依 spec §11.4 補齊非敏感欄位（#25 的使用者裁決：
                // 沿用 P2-1 紅線，只送排程資訊，不送表單內容）。
                // 這個事件在 Flowable 7.2.0 不會被 engine 發出（見 matches）；
                // payload 先墊好，替代機制確定後即可直接生效。
                payload.put("assignee", task.getAssignee());
                payload.put("dueDate", task.getDueDate());
                payload.put("overdueHours", overdueHours(task.getDueDate(), now));
            }
            default -> {
                // assignment 等事件不帶額外欄位（timeout 已於上面處理）。
                // 刻意保留 default 而不是漏掉：新增事件時忘了處理，
                // 應該是「送出基本欄位」而不是「送出一個沒有 event 的 payload」。
            }
        }
        return payload;
    }

    /**
     * {@code task.timeout} 的 {@code overdueHours}：任務距離 {@code dueDate}
     * 已經過幾個整點小時。
     *
     * <h2>定義（由 {@code WebhookTaskPayloadTest} 逐條釘住）</h2>
     *
     * <ul>
     *   <li><b>起點</b>：任務自己的 {@code dueDate}（業務截止時間），
     *       不是邊界計時器的到期時間 —— 兩者在 BPMN 裡可以分開設定。</li>
     *   <li><b>終點</b>：payload 產生的時刻（{@code eventTime}），
     *       與 payload 的 {@code timestamp} 是同一個 {@link Instant}。</li>
     *   <li><b>單位</b>：整點小時，無條件捨去（{@link Duration#toHours()}）。
     *       逾期 90 分鐘 → {@code 1}；逾期 59 分鐘 → {@code 0}。</li>
     *   <li><b>下限 0</b>：事件時間早於 {@code dueDate} 時回 {@code 0}。
     *       計時器的觸發時間與 {@code dueDate} 是兩個獨立設定，計時器可能
     *       早於 {@code dueDate} 觸發；這時「尚未逾期」是 0，不是負數。</li>
     *   <li><b>dueDate 為 null</b>：回 {@code null}（沒有基準點就不猜）。
     *       payload 仍保留 {@code overdueHours} 鍵、值為 null，
     *       讓接收端的 schema 固定。</li>
     * </ul>
     */
    static Long overdueHours(Date dueDate, Instant eventTime) {
        if (dueDate == null) return null;
        long hours = Duration.between(dueDate.toInstant(), eventTime).toHours();
        return Math.max(0L, hours);
    }

    private String extractProcessKey(String processDefinitionId) {
        if (processDefinitionId == null) return "";
        int idx = processDefinitionId.indexOf(':');
        return idx > 0 ? processDefinitionId.substring(0, idx) : processDefinitionId;
    }
}
