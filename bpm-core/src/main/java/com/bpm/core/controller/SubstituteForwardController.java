package com.bpm.core.controller;

import com.bpm.core.audit.AuditEventPublisher;
import com.bpm.core.audit.model.OperationType;
import com.bpm.core.client.ExternalApiException;
import com.bpm.core.dto.AuditEvent;
import com.bpm.core.external.ExternalActorIdentity;
import com.bpm.core.notify.NotifyPublisher;
import com.bpm.core.security.CallerId;
import com.bpm.core.service.OrgService;
import org.flowable.engine.TaskService;
import org.flowable.task.api.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 既有任務轉派給代理人（#5）。
 *
 * <h2>它補的是哪個缺口</h2>
 *
 * <p>{@code InitialAssigneeResolver} 讓<b>新任務</b>的第一關受理人自動走
 * {@code resolveEffective}（有人休假 → 派代理人）。但「設定代理人」是
 * 隨時可以發生的事：案件已經在休假者的收件匣裡時才設定代理人，那些
 * <b>既有任務</b>不會自己移動 —— 它們會等到休假者回來，而代理人明明在。
 * 本端點是管理員對這個缺口的手動補償：掃描所有執行中任務，
 * 把受理人「現在有代理人」的那些轉派給代理人。
 *
 * <h2>為什麼是管理員手動，而不是自動轉派</h2>
 *
 * <p>自動掃描需要一個觸發點（排程或組織異動推送），兩者都不在本次範圍；
 * 而且「一次把全公司多少任務換手」是一個需要人看得到結果的操作。
 * 手動端點讓管理員決定時機，並拿到 {@code scanned／forwarded／skipped}
 * 的明確結果。將來自動化時，<b>掃描與轉派的規則應該重用本類別</b>
 * （而不是另寫一份排程版本）——「規則只能有一份」。
 *
 * <h2>授權：{@code /api/admin/**} 既有的 ROLE_ADMIN 規則</h2>
 *
 * <p>本端點不新增任何授權規則（{@code SecurityConfig} 零變更）。
 * 它會改動別人的待辦、寄出通知信，與 DLQ 重放同一級 —— 都是
 * 「有外部副作用的管理操作」。operator 取 {@code @CallerId}，
 * 不是 body 參數（與 {@code TaskController} 的 P1-1 同一條原則）。
 *
 * <h2>分批掃描，以及為什麼 offset 分頁是安全的</h2>
 *
 * <p>一次 {@code list()} 全表會把所有執行中任務載進記憶體；改用
 * {@code listPage(offset, size)} 每批 {@value #CHUNK_SIZE} 筆，
 * 讓「一次載入多少」有上限。<b>這不是總量上限</b> —— 端點會掃到完，
 * 每一筆轉派都是獨立的 UPDATE。
 *
 * <p>掃描中的 {@code setAssignee} 不會改變查詢結果的<b>成員</b>
 * （查詢條件是「執行中任務」，不是「受理人是誰」），所以配合同定的
 * {@code orderByTaskId().asc()} 之後，offset 前進不會跳過或重複任何一筆。
 *
 * <p>⚠️ 交易仍然持有本輪所有 UPDATE 到 commit：這是刻意的，
 * 稽核失敗時整批回滾，不會留下「改了任務但沒有軌跡」的狀態。
 * 代價是極大量轉派時的交易較大；目前由「管理員手動、一次一輪」控住。
 *
 * <h2>過濾規則（唯一一份）</h2>
 *
 * <ul>
 *   <li><b>沒有受理人</b>（候選任務）→ 跳過。沒有對象可以代換。</li>
 *   <li><b>系統身分</b>（{@code system:*}）→ 跳過。那不是人，不可能有代理人；
 *       用 {@link ExternalActorIdentity#isSystemActor} 判斷，不重寫前綴規則，
 *       也刻意不打組織系統（會 404）。</li>
 *   <li><b>組織系統明確回答查無此人</b>（HTTP 404）→ 跳過並記 warn。
 *       歷史任務可能有已離職者；讓整個批次因此失敗，會使端點對
 *       其他數千筆可用任務都無法使用。</li>
 *   <li><b>組織系統故障</b>（逾時／5xx／其他 4xx）→ <b>整批 503 並回滾</b>。
 *       與「查無此人」分開的理由見 {@code ExternalActorGuard}：
 *       故障可以重試，拒絕不能。</li>
 *   <li><b>沒有代理人</b>（或代理人就是本人）→ 跳過。</li>
 * </ul>
 *
 * <h2>冪等性</h2>
 *
 * <p>第二次呼叫時，已轉派的任務受理人已是代理人；代理人若沒有自己的
 * 代理人，{@code resolveEffective} 回本人 → 不再轉派，{@code forwarded=0}。
 * ⚠️ 已知邊界：若代理人<b>自己</b>也設了代理人，第二次呼叫會再往下轉一層
 * —— 這是 {@code resolveEffective} 的既定語意（只解一層，每次呼叫解一層），
 * 與 {@code BpmPermissionService.getFirstAvailableUser} 一致。
 *
 * <h2>稽核：一筆總結，不是逐任務</h2>
 *
 * <p>批次轉派 500 筆就寫 500 筆稽核會把軌跡淹沒。這裡只寫一筆
 * {@link OperationType#TASK_SUBSTITUTE_FORWARD}：operator＝呼叫者，
 * detail 放 {@code scanned／forwarded／skipped} 計數。逐任務的
 * 「誰被轉給誰」可從 Flowable 的任務歷史（assignee 變更）追，
 * 稽核要回答的是「誰發動了這一批、影響多大」。
 *
 * <p>用 fail-closed 的 {@code publish}（掛在交易 beforeCommit）：
 * 稽核寫不進去就整批回滾，不會出現「轉派成功但沒有紀錄」。
 *
 * <h2>通知：每個被轉派的任務通知新受理人</h2>
 *
 * <p>不通知的話，代理人不知道任務來了（任務是從別人的待辦「移動」過來，
 * 沒有建立事件、也就沒有 BPMN 的 taskListener 會發信）。事件沿用
 * {@code task_assigned}（與加簽相同：對新受理人而言它就是一個指派給他的任務），
 * 發送端仍是唯一出口 {@link NotifyPublisher#taskAssigned}。
 *
 * <p>量：forwarded 幾筆就幾則訊息（與既有 {@code NotifyTaskListener}
 * 每個任務一則相同）。本端點是管理員手動觸發、一次一輪，不是排程，
 * 因此沒有「每次掃描都重複寄信」的放大；但一次轉派 1000 筆就是 1000 封信，
 * 這是操作的可見後果，回報給呼叫端。
 *
 * <p>⚠️ 已知窗口：通知在 commit <b>之前</b>同步送出（與 {@code TaskController}
 * 的 claim／催辦相同）。若之後稽核在 beforeCommit 失敗導致整批回滾，
 * 那幾封信已經寄出 —— 收件人會看到「任務轉來了」但待辦沒有那一筆。
 * 這個窗口是「通知不與交易同生共死」的必然結果（見 {@code NotifyPublisher}
 * 為何吞例外），比「通知晚於 commit 而漏寄」容易察覺，因此保留。
 */
@RestController
@RequestMapping("/api/admin/tasks")
public class SubstituteForwardController {

    private static final Logger log = LoggerFactory.getLogger(SubstituteForwardController.class);

    /**
     * 每批載入的任務數。
     *
     * <p>100 是「記憶體有上限」與「往返次數不至於太多」之間的折衷。
     * 它與 {@code DlqReplayService.MAX_MESSAGES_PER_REQUEST} 不同 ——
     * 那個是防止誤觸的<b>總量</b>上限，本端點刻意沒有總量上限：
     * 管理員要的是「所有既有任務都補上」，漏掉一部分會讓結果無法預期。
     */
    static final int CHUNK_SIZE = 100;

    private final TaskService taskService;
    private final OrgService orgService;
    private final NotifyPublisher notifyPublisher;
    private final AuditEventPublisher auditPublisher;

    public SubstituteForwardController(TaskService taskService,
                                       OrgService orgService,
                                       NotifyPublisher notifyPublisher,
                                       AuditEventPublisher auditPublisher) {
        this.taskService = taskService;
        this.orgService = orgService;
        this.notifyPublisher = notifyPublisher;
        this.auditPublisher = auditPublisher;
    }

    /**
     * 掃描所有執行中任務，把受理人「現在有代理人」的轉派給代理人。
     *
     * @param operatorId 已認證的呼叫者（稽核用）。正常情況 {@code /api/admin/**}
     *                   的 ROLE_ADMIN 規則已擋掉未認證；這裡仍防禦性檢查，
     *                   與 {@code TaskController.urgeTask} 一致 ——
     *                   稽核不記一個 null 的 operator。
     * @throws ResponseStatusException 組織系統故障（503）；被拒時整批未變更
     */
    @PostMapping("/forward-substitutes")
    @Transactional("primaryTransactionManager")
    public ForwardResult forwardSubstitutes(@CallerId String operatorId) {
        if (operatorId == null || operatorId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "無法確認身分，請先登入");
        }

        int scanned = 0;
        int forwarded = 0;
        int skipped = 0;

        // offset 掃描；成員不因 setAssignee 改變，所以分頁安全（見類別註解）。
        int offset = 0;
        for (;;) {
            List<Task> batch = taskService.createTaskQuery()
                    .orderByTaskId().asc()
                    .listPage(offset, CHUNK_SIZE);
            if (batch.isEmpty()) break;

            for (Task task : batch) {
                scanned++;
                String assignee = task.getAssignee();
                if (assignee == null || assignee.isBlank()) {
                    skipped++;
                    continue;
                }
                if (ExternalActorIdentity.isSystemActor(assignee)) {
                    // 系統身分不可能有代理人；不打組織系統（會 404）。
                    skipped++;
                    continue;
                }

                String substitute;
                try {
                    substitute = orgService.resolveEffective(assignee);
                } catch (ExternalApiException e) {
                    // 正式 client（#8）把 404 映射成 ExternalApiException：
                    // 組織系統明確回答查無此人 → 他不可能有代理人。
                    // 跳過這一筆，不讓整個批次失敗（歷史任務可能有離職者）。
                    if (!e.isNotFound()) {
                        // 其他狀態碼（401／5xx）與逾時都是故障 —— 與下面的
                        // RestClientException 同一條規則，見 catch 區塊的說明。
                        throw orgSystemUnavailable(task, assignee, e);
                    }
                    log.warn("任務 {} 的受理人 {} 不是組織系統認識的人員，跳過轉派",
                            task.getId(), assignee);
                    skipped++;
                    continue;
                } catch (HttpClientErrorException.NotFound e) {
                    // 組織系統明確回答查無此人 → 他不可能有代理人。
                    // 跳過這一筆，不讓整個批次失敗（歷史任務可能有離職者）。
                    log.warn("任務 {} 的受理人 {} 不是組織系統認識的人員，跳過轉派",
                            task.getId(), assignee);
                    skipped++;
                    continue;
                } catch (RestClientException e) {
                    // 故障與拒絕分開（ExternalActorGuard 的同一組政策）：
                    // 無法判定時 fail-closed —— 整批 503 並回滾，重試安全。
                    throw orgSystemUnavailable(task, assignee, e);
                }

                if (substitute == null || substitute.equals(assignee)) {
                    skipped++;
                    continue;
                }

                taskService.setAssignee(task.getId(), substitute);
                forwarded++;
                notifyNewAssignee(task, substitute);
            }

            offset += batch.size();
        }

        auditForward(operatorId, scanned, forwarded, skipped);
        log.info("代理人轉派完成：operator={} scanned={} forwarded={} skipped={}",
                operatorId, scanned, forwarded, skipped);
        return new ForwardResult(scanned, forwarded, skipped);
    }

    /**
     * 組織系統故障（非「查無此人」）→ 503 並中止本輪。
     *
     * <p>抽出來只為了讓兩個 catch（正式 client 的 {@code ExternalApiException}
     * 與其他來源的 {@code RestClientException}）走同一條處置 ——
     * 「故障一律 fail-closed、重試安全」的訊息只寫一份。
     */
    private ResponseStatusException orgSystemUnavailable(Task task, String assignee, Exception cause) {
        log.error("組織系統查詢失敗，本輪轉派中止（交易將回滾）。"
                + "taskId={} assignee={} 原因={}",
                task.getId(), assignee, cause.getMessage(), cause);
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "組織系統目前無法查詢，本輪未轉派任何任務，請稍後重試", cause);
    }

    /**
     * 通知新受理人（{@code task_assigned}）。
     *
     * <p>initiator 只在任務屬於某個流程時才查 —— standalone 任務（例如加簽）
     * 沒有流程變數可讀。{@code taskService.getVariable} 在變數不存在時回
     * null，不是例外。發送端吞例外，通知失敗不影響轉派。
     */
    private void notifyNewAssignee(Task task, String substitute) {
        String initiator = null;
        if (task.getProcessInstanceId() != null) {
            Object v = taskService.getVariable(task.getId(), "initiator");
            initiator = v != null ? v.toString() : null;
        }
        notifyPublisher.taskAssigned(task.getId(), task.getName(), substitute, null,
                task.getProcessInstanceId(), task.getProcessDefinitionId(), initiator);
    }

    /**
     * 一筆總結稽核。detail 只放計數，不放逐任務清單 —— 理由見類別註解。
     *
     * <p>掛在交易 beforeCommit（{@code publish}）：稽核失敗整批回滾。
     */
    private void auditForward(String operatorId, int scanned, int forwarded, int skipped) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("action", "forward_substitutes");
        detail.put("scanned", scanned);
        detail.put("forwarded", forwarded);
        detail.put("skipped", skipped);
        auditPublisher.publish(new AuditEvent(
                OperationType.TASK_SUBSTITUTE_FORWARD.name(),
                operatorId, null, null, detail));
    }

    /**
     * 轉派結果。
     *
     * @param scanned   本輪掃描的執行中任務數（含沒有受理人的候選任務）
     * @param forwarded 實際轉派給代理人的任務數
     * @param skipped   評估後不需轉派或無法轉派的任務數
     */
    public record ForwardResult(int scanned, int forwarded, int skipped) {
    }
}
