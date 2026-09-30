package com.bpm.core.controller;

import org.springframework.transaction.annotation.Transactional;
import com.bpm.core.security.CallerId;
import com.bpm.core.audit.AuditEventPublisher;
import com.bpm.core.dto.AuditEvent;
import com.bpm.core.audit.model.OperationType;
import com.bpm.core.dto.CommentRequest;
import org.flowable.common.engine.api.FlowableTaskAlreadyClaimedException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.flowable.common.engine.impl.identity.Authentication;
import com.bpm.core.dto.TaskActionRequest;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.flowable.task.api.TaskQuery;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Stream;

@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    /**
     * 不可由呼叫端以任務變數改寫的變數名。
     *
     * <p>{@code initiator} 是關鍵：兩支已部署的 BPMN 解析第一關的主管時
     * 會讀它（{@code assigneeResolver} 內部），申請人在完成自己的補件任務時
     * 附帶一個偽造的 initiator，下一輪主管審核就會派給他指定的人的主管
     * —— 等於簽核人自選審核者（security-audit P0-5）。
     *
     * <p>{@code effectiveInitiator} 是伺服器由外部系統請求推導出來的身分，
     * 同理不可由呼叫端指定。
     *
     * <p>{@code _} 前綴的一律拒絕（R-23）：那是伺服器的內部狀態，
     * 包含 {@code _formVersions}（表單版本鎖定）與
     * {@code _externalSystemId}（外部系統擁有權判定的依據）。
     *
     * <p>⚠️ 這是保護名單（deny-list）而非完整的白名單。真正的白名單應該來自
     * formKey 的 schema（spec §8.5：欄位 id == 變數名），但那需要在此查詢
     * form-service 並處理它不可用時的行為，範圍更大。目前的做法擋住了所有
     * 「改寫引擎與身分語意」的變數，而一般業務欄位照常放行。
     */
    private static final java.util.Set<String> PROTECTED_VARIABLES =
            java.util.Set.of("initiator", "effectiveInitiator", "onBehalfOf");

    private static boolean isProtectedVariable(String name) {
        if (name == null || name.isBlank()) return true;
        return name.startsWith("_") || PROTECTED_VARIABLES.contains(name);
    }

    private final TaskService taskService;
    private final RuntimeService runtimeService;
    private final RepositoryService repositoryService;
    private final AuditEventPublisher auditPublisher;
    private final com.bpm.core.security.ProcessAccessGuard accessGuard;
    private final com.bpm.core.security.TaskHolderGuard holderGuard;

    public TaskController(TaskService taskService, RuntimeService runtimeService,
                          RepositoryService repositoryService,
                          AuditEventPublisher auditPublisher,
                          com.bpm.core.security.ProcessAccessGuard accessGuard,
                          // #77：讀寫兩端共用的持有者判斷。待辦清單與任務動作
                          // 必須是同一條規則 —— 兩處各自維護時，只要有人改了
                          // 其中一處，就會出現「看得到、點進去被拒」那種組合
                          // 型式的差異，而那種差異比沒有檢查更難察覺。
                          com.bpm.core.security.TaskHolderGuard holderGuard) {
        this.taskService = taskService;
        this.runtimeService = runtimeService;
        this.repositoryService = repositoryService;
        this.auditPublisher = auditPublisher;
        this.accessGuard = accessGuard;
        this.holderGuard = holderGuard;
    }

    /**
     * 我的待辦（指派給我 ＋ 我是候選人 ＋ 我所屬的候選群組），依 taskId 去重。
     *
     * <h2>改動前是什麼（#71）</h2>
     *
     * <p>{@code assignee}／{@code candidateUser}／{@code candidateGroups}
     * 三個參數全是 {@code required=false} 且<b>完全不檢查是否等於呼叫者</b>；
     * 三個都不帶時還有一條「return all」—— 也就是回傳全公司待辦，
     * 洩漏「誰在審什麼」。那是收件匣資訊，不是公開資訊。
     *
     * <h2>三個參數三種處理</h2>
     *
     * <ol>
     *   <li><b>{@code assignee}／{@code candidateUser}</b>：省略 → 用呼叫者；
     *       帶了別人的 id → 明確 400（與 #66 同一政策）。
     *       這兩個參數保留是為了讓呼叫端能明確表達意圖，不是為了授權 ——
     *       它們的結果永遠與「省略」相同。</li>
     *   <li><b>{@code candidateGroups}：帶了任何值 → 一律 400。</b>
     *       這是本方法最需要解釋的一條。assignee／candidateUser 至少還能
     *       解讀成「查自己」，而群組是一個<b>集合</b>的自稱 —— 等於要求
     *       伺服器相信「我屬於這個組織」。放行它的後果與 #66 的 initiator
     *       冒用完全同型，而且更隱蔽（拿到的結果看起來完全正常）。
     *       即使帶的值剛好等於呼叫端自己的群組也拒絕：呼叫端送出這個參數
     *       本身就表示它期待該值被採信。</li>
     * </ol>
     *
     * <h2>⚠️ 省略 candidateGroups 時<b>不能</b>完全不查群組</h2>
     *
     * <p>設計器的「發起人所屬單位」會產生
     * {@code flowable:candidateGroups="${orgService.getDeptId(initiator)}"}
     * —— 一個<b>沒有受理人</b>的任務（見 {@code InitialAssigneeResolver}
     * 對「只給候選群組」的說明）。若完全不查群組，這類任務會從<b>所有人</b>
     * 的收件匣消失，而且沒有任何錯誤訊息：案件就那樣卡住。
     * 「查不到就不查」正是 {@code DuplicateApprovalFilterTest} 記錄過的
     * 教訓（候選任務被靜默隱藏 → 案件卡死）。
     *
     * <p>所以省略時由 {@link com.bpm.core.service.CandidateGroupMembership}
     * 計算呼叫端實際所屬的群組（部門代碼 ∪ 權限碼 ∪ authorities）。
     *
     * <p>⚠️ 已部署的 {@code purchase-approval} 的 {@code financeReview} 用的是
     * {@code candidateUsers} 不是 candidateGroups，所以這段不影響它 ——
     * 由 {@code InvolvedInstancesTest.candidateUserFlowIsUnaffected} 證明。
     *
     * <h2>⚠️ 不加稽核</h2>
     *
     * <p>本端點只回傳呼叫者自己的待辦，沒有稽核旁路。被拒的參數是 400，
     * 且已由 {@link com.bpm.core.security.ProcessAccessGuard} 留下
     * {@code DATA_ACCESS {denied:true}}。
     */
    @GetMapping
    public List<Map<String, Object>> getTasks(
            @RequestParam(required = false) String assignee,
            @RequestParam(required = false) String candidateUser,
            @RequestParam(required = false) String candidateGroups,
            @CallerId String callerId) {

        String self = accessGuard.requireSelf(assignee, callerId, "assignee");
        accessGuard.requireSelf(candidateUser, callerId, "candidateUser");
        accessGuard.rejectCallerSuppliedGroups(candidateGroups, callerId);

        // ⚠️ 三個查詢條件由 TaskHolderGuard 產生，而不是在這裡各寫一份
        // （#77）。PUT /api/tasks/{id} 的授權檢查走的是同一組建構子，
        // 見 TaskHolderGuard.inboxQueries 為什麼讀端保留批次查詢的形狀。
        Map<String, Task> taskMap = new LinkedHashMap<>();
        for (TaskQuery q : holderGuard.inboxQueries(self)) {
            q.list().forEach(t -> taskMap.putIfAbsent(t.getId(), t));
        }

        // ── 「同一人不得重複簽核」的過濾已移除（2026-09-28 決策）───────
        //
        // 移除的原因（security-audit P1-5，該項是審查中唯一沒有給出修法的）：
        //
        //  1. **它從未真正強制任何規則**。過濾只發生在待辦查詢，
        //     PUT /api/tasks/{id} 沒有對應檢查 —— 知道 taskId 就能簽第二次。
        //     一個被當成業務規則展示、實際上只是隱藏的機制，
        //     比沒有這個機制更糟：它讓人以為規則已經生效。
        //
        //  2. **它會讓案件靜默卡死**。過濾範圍是整個流程實例而非節點：
        //     只要該使用者在此 instance 完成過任何任務，所有未指派的候選任務
        //     都被移除。實際情境 —— 財務退回 → 主管再審通過 → 案子回到
        //     financeReview（candidateUsers），但當初退件的財務人員已有
        //     finished 歷史 → 該任務對他永久隱藏。若該權限只有一人，
        //     案件就此卡住且沒有任何錯誤訊息。
        //     （改用 taskDefinitionKey 也解不掉：退回後回到的就是同一個節點。
        //       真正要區分的是「同一輪」，而系統目前沒有輪次的概念。）
        //
        // 若日後確實需要「不得重複簽核」，正確做法是：先定義「一輪」的界線，
        // 然後在 complete() 加上真正的檢查（拒絕而非隱藏），
        // 而不是在查詢端過濾。

        return taskMap.values().stream()
                .sorted(Comparator.comparing(Task::getCreateTime).reversed())
                .map(this::toMap).toList();
    }

    /**
     * 任務動作。
     *
     * <p>改動前這裡有三個問題（security-audit P1-1／P1-3／P1-4），
     * 三者互相牽動因此一併處理：
     *
     * <p><b>P1-1 稽核查不出是誰核准的。</b>operatorId 一律取
     * {@code req.assignee()}，但前端主要簽核入口的 payload 只有 action 與
     * variables → TASK_APPROVE／TASK_RETURN／TASK_REJECT 的 operatorId 全是
 * null。改為已認證的呼叫者（{@code @CallerId}）。R-01 完成後第一順位
 * 不再是可偽造的標頭；而「fallback 到任務的 assignee」那一層在 #77
 * 加上守衛之後已經是死碼，理由見下面 operatorId 那段。
     *
     * <p><b>P1-3 守門回 HTTP 200。</b>有未完成加簽時回
     * {@code {"status":"error"}} 卻是 200，前端只看 axios 是否 throw →
     * 顯示「操作成功」並導航離開，而 comment 在 complete 之前就已寫入 →
     * DB 留下一筆「核准意見」而核准從未發生。改為 409 CONFLICT，
     * 與本檔其他錯誤路徑一致。
     *
     * <p><b>P1-4 未知 action 靜默改派。</b>改為顯式 switch：未知或缺少
     * action 一律 400。改派必須明確指定 {@code action=reassign}，
     * 不再是「有 assignee 就改派」—— 後者讓 typo 把核准變成改派且回報成功。
     *
     * <h2>#77：這裡原本<b>沒有任何持有者檢查</b>（最嚴重的授權缺陷）</h2>
     *
     * <p>{@code @CallerId} 解析出來的 {@code callerId} 改動前<b>只用於稽核</b>，
     * 從未拿去與 {@code task.getAssignee()} 比對。取得任務之後就直接
     * {@code complete}／{@code delegateTask}／{@code setAssignee}／
     * {@code resolveTask}，於是任何登入者只要知道 taskId 就能動任何任務。
     *
     * <p>實測（真實 JWT，線上服務）：user001 送出 leave-approval，
     * 該案主管審核任務的 assignee 是 mgr001；讓與該案無關的 user002 去簽
     * {@code PUT /api/tasks/{id} {"action":"complete","variables":[approved=true]}}
     * 回 <b>200 {@code {"status":"ok"}}</b>，稽核留下
     * {@code TASK_APPROVE | operatorId = user002}，流程直接走完。
     * <b>任何登入者都能批准或拒絕系統裡的任意請假單、任意採購單。</b>
     *
     * <p>而且這條路徑正是前端實際在用的表單寫入路徑
     * （{@code DocumentDetail.vue} → {@code PUT /api/tasks/{taskId}} 帶 variables），
     * 也就是說 #72 剛修好的 {@code FormDataController} 保護力<b>低於</b>
     * 前端真正在走的那條路 —— 拿表單資料的權限比簽核的權限還大。
     *
     * <h2>⚠️ 為什麼守衛排在「action 形狀檢查」<b>之後</b></h2>
     *
     * <p>順序是「形狀 → 授權 → 身分欄位」，與 {@code FormDataController.submit}
     * 相同。請求本身不完整（沒有 action）與「你沒權」是兩件事：
     * 先把形狀講清楚，呼叫端才知道要改 payload 還是改流程選擇。
     *
     * <p>反過來說，404 與 400 在此處本來就無法完全不可區分 ——
     * 「任務不存在」是 404、「任務存在但 body 壞掉」是 400，
     * 這個落差在改動前就存在，守衛插在前面並不會新開一條枚舉管道
     * （見 {@code TaskActionHardeningTest.cannotHijackByBareAssignee}：
     * 攻擊者送出 {@code {"assignee":"自己"}} 時斷言的就是 400）。
     */
    @PutMapping("/{id}")
    @Transactional("primaryTransactionManager")
    public Map<String, Object> updateTask(@PathVariable String id,
                                          @RequestBody TaskActionRequest req,
                                          @CallerId
                                          String callerId) {
        Task task = taskService.createTaskQuery().taskId(id).singleResult();
        if (task == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "任務不存在: " + id);
        }
        String processInstanceId = task.getProcessInstanceId();
        String action = req.action();

        OperationType auditType;

        if (action == null || action.isBlank()) {
            // 改動前：沒有 action 但有 assignee 就直接改派 ——
            // 任何人都能用 {"assignee":"自己"} 無條件奪取他人任務。
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "缺少 action（可用：claim, complete, delegate, resolve, reassign）");
        }

        // ── #77：持有者守衛 ───────────────────────────────────────────
        //
        // 非持有者（含完全無關的使用者）→ 404，且**在碰到任務之前**就擋下，
        // 因此 complete／reassign／delegate 都不會改動任何資料。
        // 未認證 → 401。判斷規則見 TaskHolderGuard（與待辦清單同一份）。
        holderGuard.requireHolder(task, callerId);

        // 操作者就是呼叫者，一層 fallback 都不留。
        //
        // 改動前是 firstNonBlank(callerId, firstNonBlank(task.getAssignee(), req.assignee()))。
        // 後面那兩層在守衛加入後<b>永遠不會生效</b>：requireHolder 保證 callerId
        // 非空白（未認證已先被 401 擋下），firstNonBlank 的第一個參數就不會是
        // null／空白。而它們描述的心智模型 —— 「操作者可能不是呼叫者」——
        // 正是這個缺陷的成因：operatorId 只拿去稽核，從不與 task.getAssignee()
        // 比對，於是稽核看起來健全，實際上任何人都能簽任何人的單。
        //
        // 稽核要記的是「誰按的按鈕」，不是「誰持有這個任務」——那是兩個不同
        // 的事實，混用只會在出事時指錯方向。
        String operatorId = callerId;

        switch (action) {
            case "claim" -> {
                // 認領者就是呼叫者，不接受 body 指定。
                //
                // 改動前是 firstNonBlank(callerId, req.assignee())，而
                // "claim 必須指定認領者" 的 400 與「空 body 可強制釋放他人任務」
                // 那段說明都建立在 assignee 可能是 null 之上。守衛加入後
                // callerId 必定非空白，那個 fallback 與那個 400 都是死碼。
                //
                // 順帶關掉一個洞：改動前 {"action":"claim","assignee":"其他人"}
                // 會<b>代別人認領</b>。那不是 claim 的語意（claim 是「這是我的」），
                // 而且等於讓持有者指定任意受理人 —— 那是 reassign 的工作，
                // 而 reassign 自己也還沒驗證新受理人（P1-4，尚未施作）。
                String claimant = callerId;
                try {
                    taskService.claim(id, claimant);
                } catch (FlowableTaskAlreadyClaimedException e) {
                    // 改動前這個例外變成裸 500；語意上它是衝突。
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "任務已被他人認領", e);
                }
                auditType = OperationType.TASK_CLAIM;
            }
            case "complete" -> {
                // 語意檢查（#77）：完成是持有者的權力，與其他 action 同一條守衛。
                // leave-approval／purchase-approval 的補件關卡 assignee 由
                // ${applicantResolver.resolve(execution)} 決定（#83 之前是 ${initiator}），
                // 所以申請人能簽自己的補件任務；外部系統發起、沒有自然人申請人時
                // 會派給權限碼 bpm:external:revision 指定的受理人。
                //
                // ⚠️ 引擎層的既有行為（非本次引入）：被 delegate 出去、尚處於
                // PENDING 的任務不能被 complete —— TaskHelper.completeTask 會
                // 拋 FlowableException（"should be resolved instead"）→ 裸 500。
                // 守衛不得（也沒有）把它變成 404：那會對 delegatee 謊稱任務不存在，
                // 把一個可診斷的規則藏起來。委派要能走完，正確的動作是 resolve
                // （見下面的 resolve 分支與 TaskHolderGuard 的生命週期分析）。
                // 把這個 500 變成明確的 409 屬於另一件事，本次不做。
                if (!taskService.getSubTasks(id).isEmpty()) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "有未完成的加簽子任務");
                }
                Map<String, Object> vars = new HashMap<>();
                if (req.variables() != null) {
                    // 拒絕而非靜默丟棄：靜默丟棄會讓攻擊嘗試無跡可循，
                    // 也會讓正常使用者以為自己送出的值生效了。
                    req.variables().stream()
                            .filter(v -> isProtectedVariable(v.name()))
                            .findFirst()
                            .ifPresent(v -> {
                                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                        "不允許以任務變數改寫受保護的變數: " + v.name());
                            });
                    req.variables().forEach(v -> vars.put(v.name(), v.value()));
                }
                // Ensure gateway variables are always set to avoid EL PropertyNotFoundException
                vars.putIfAbsent("rejected", false);
                vars.putIfAbsent("approved", false);
                taskService.complete(id, vars);
                auditType = task.getName() != null && task.getName().contains("補件")
                        ? OperationType.TASK_RESUBMIT : resolveCompleteAuditType(vars);
            }
            case "delegate" -> {
                if (req.delegateUser() == null || req.delegateUser().isBlank()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "delegate 必須指定 delegateUser");
                }
                // 語意檢查（#77）：被 delegate 出去之後 delegatee 必須能簽。
                // 這是本次修改最大的迴歸風險 —— Flowable 的 delegateTask 會把
                // assignee 換成 delegatee、把原本的 assignee 寫進 owner，
                // 守衛若不放行 delegatee 就等於把委派功能打死。
                // 由 TaskHolderGuard 的條件 1（assignee）涵蓋，
                // 見 TaskHolderGuard 類別註解的 delegate／resolve 生命週期分析。
                taskService.delegateTask(id, req.delegateUser());
                auditType = OperationType.TASK_DELEGATE;
            }
            case "resolve" -> {
                // 語意檢查（#77）：resolve 是「原指派人把被委派的任務收回來」，
                // Flowable 的 resolveTask 把 assignee 還給 owner ——
                // 呼叫者只可能是 owner（delegate 之前 owner 為 null 的話，
                // 引擎會在 delegateTask 時把它設成原本的 assignee）。
                // 由 TaskHolderGuard 的條件 2（owner）涵蓋。
                //
                // ⚠️ 未被 delegate 的任務上呼叫 resolve，引擎會拋例外 →
                // 裸 500。這是既有行為（改動前任何人都能觸發），本次不處理；
                // 真正該做的是「不是委派中的任務就回 409」，留待後續。
                taskService.resolveTask(id);
                auditType = OperationType.TASK_RESOLVE;
            }
            case "reassign" -> {
                // 改派現在必須明確指定 action。
                //
                // 語意檢查（#77）：改派是持有者的權力，所以與其他 action
                // 套用同一條守衛 —— 非持有者不得改派任何人的任務。
                //
                // ⚠️ 仍未檢查新 assignee 是否為該任務的候選人 —— 持有者目前
                // 可以把任務指給任意 id。那是 P1-4 的另一半，本次不處理
                // （範圍是「誰能動這個任務」，不是「能指派給誰」）。
                if (req.assignee() == null || req.assignee().isBlank()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "reassign 必須指定 assignee");
                }
                taskService.setAssignee(id, req.assignee());
                auditType = OperationType.TASK_REASSIGN;
            }
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "未知的 action: " + action
                            + "（可用：claim, complete, delegate, resolve, reassign）");
        }

        Map<String, Object> detail = new HashMap<>();
        detail.put("action", action);
        if (req.variables() != null) {
            req.variables().forEach(v -> detail.put(v.name(), v.value()));
        }

        // operationType 改傳 enum 的 name()：編譯期就綁定，
        // 不會再出現「字串不在 enum 裡 → valueOf 拋例外 → 稽核靜默遺失」。
        auditPublisher.publish(new AuditEvent(auditType.name(), operatorId,
                processInstanceId, id, detail));
        return Map.of("taskId", id, "status", "ok");
    }

    /**
     * 留言（#79：這裡原本<b>完全沒有</b>物件層授權）。
     *
     * <h2>缺陷（真實 JWT 線上實測）</h2>
     *
     * <p>{@code callerId} 從 R-01 起只用於稽核，<b>從未拿去與案件或任務比對</b>。
     * 任何登入者只要知道 taskId 就能在別人的單上留言，稽核留下
     * {@code TASK_COMMENT | operatorId = user002}。改動前 taskId 還能從
     * {@code GET /api/history/tasks} 大量取得（那是 #76 修掉的發射台）。
     *
     * <h2>守衛：{@code requireTaskParticipant}（寫個案，不是動作任務）</h2>
     *
     * <p>批註不影響流程走向（spec §4.5），所以適用「寫個案」政策 ——
     * {@code requireParticipant}，<b>不開稽核旁路</b>（稽核人員的職責是查閱，
     * 不是替別人的案子補簽核意見）。規則與理由見 {@code ProcessAccessGuard}。
     *
     * <p>守衛<b>排在最前面</b>：與 {@link #updateTask} 不同，這個方法沒有
     * 「請求形狀」檢查可排，而訊息為空不會改變任何東西（{@code message} 可為 null），
     * 因此沒有理由讓未授權的呼叫端先走到資料層。
     *
     * <h2>連帶修掉：對不存在的 taskId 留言是<b>裸 500</b></h2>
     *
     * <p>負向控制組實測（把守衛整段拿掉之後）：
     * {@code POST /api/tasks/{unknownId}/comments} →
     * {@code FlowableObjectNotFoundException: Cannot find task with id …} → <b>500</b>。
     * 引擎的 {@code AddCommentCmd} <b>確實會</b>驗任務存在，所以不會產生
     * 孤兒批註（這點與「Flowable 不驗外鍵」的直覺相反，是實測確認的），
     * 但那個例外沒有被翻成 {@code ResponseStatusException}，於是呼叫端拿到 500。
     *
     * <p>500 的後果與 #85 記錄過的同一個：修好之後呼叫端才會停止重試。
     * 而更根本的問題是改動前<b>連「這個任務存不存在」都沒人問</b> ——
     * 守衛先確認任務真的存在，這條路徑於是回 404，
     * 與 {@link #updateTask} 對不存在任務的處理一致。
     *
     * <p>順帶一提，{@code processInstanceId} 現在由守衛回傳，
     * 這也讓「pid 一定是真實的」變成結構性保證，而不是 {@code task != null} 的副產品。
     *
     * <h3>⚠️ 仍未修：對<b>已完成</b>的關卡留言是裸 500（既有行為，非本工項引入）</h3>
     *
     * <p>2026-09-30 線上實測：{@code POST} 到一個已結束的 taskId，
     * 授權會通過（守衛看得到歷史，所以 pid 找得到、關係人也成立），
     * 接著 {@code AddCommentCmd} 因為 runtime 裡沒有這個任務而拋同一個例外 → <b>500</b>。
     *
     * <p>這與改動前完全相同（改動前 {@code task == null} → pid 傳 null → 同一個例外），
     * 所以不是本次引入的迴歸。但守衛放行之後才 500，對呼叫端是誤導的 ——
     * 「你有權，但這件事做不成」被講成「伺服器壞了」。
     *
     * <p><b>刻意不在此修</b>：正確答案是 404（任務已結束）還是 409（狀態衝突）
     * 屬於 API 語意的政策性決定，且「完成後還能不能補留言」本身是產品問題
     * （實務上常有「審完想附註」的需求）。回報 PM 決定後再處理。
     * 前端目前不會走到這條路徑：{@code ApprovalTimeline.vue:44} 只對
     * {@code t.endTime} 為真的 taskId 呼叫<b>讀</b>端點，寫入端
     * （{@code CommentPanel}／{@code ActionDialog}）綁的則是待辦清單裡的
     * 執行中任務。
     */
    @PostMapping("/{id}/comments")
    @Transactional("primaryTransactionManager")
    public Map<String, String> addComment(@PathVariable String id,
                                          @RequestBody CommentRequest req,
                                          @CallerId
                                          String callerId) {
        // 非關係人 → 404（不是 403），且留痕；任務不存在 → 404。理由見
        // ProcessAccessGuard.requireTaskParticipant 與 denyNonParticipant。
        String processInstanceId = accessGuard.requireTaskParticipant(id, callerId);

        // ⚠️ 這裡的 fallback 現在是<b>死碼</b>，而且它「看起來安全」是巧合：
        // 安全性完全建立在 firstNonBlank 的<b>參數順序</b>上 ——
        // callerId 在前，所以 req.userId() 永遠輪不到（requireTaskParticipant
        // 已保證 callerId 非空白，未認證會先被 404 擋下）。
        //
        // 刻意<b>不</b>在這裡改成 requireSelf(req.userId(), callerId, "userId")：
        // 「身分欄位帶了別人的值 → 明確 400」是 #66／#72／#81 那一組規則，
        // 套用在這裡會讓 body 帶 userId 的既有呼叫端（spec §4.5 的範例、
        // 前端 ActionDialog.vue:61 與 CommentPanel.vue:35 都送
        // {@code userId:'current_user'}）整個變成 400。那是政策性決定，
        // 另案處理；這裡只把「順序是承重結構」這件事寫下來。
        //
        // 2026-09-30 線上實測（#79）：送 {"userId":"dir001"} 時，批註作者與
        // 稽核 operatorId 都是實際的呼叫者 —— 冒用沒有成功。
        String author = firstNonBlank(callerId, req.userId());

        // 用 try/finally 還原原值：Authentication 存放在 ThreadLocal，
        // 而 servlet 容器的執行緒是重複使用的 —— 不還原會讓下一個請求
        // 沿用上一個使用者的身分。
        String previous = Authentication.getAuthenticatedUserId();
        try {
            Authentication.setAuthenticatedUserId(author);
            taskService.addComment(id, processInstanceId, req.message());
        } finally {
            Authentication.setAuthenticatedUserId(previous);
        }

        auditPublisher.publish(new AuditEvent("TASK_COMMENT", author,
                processInstanceId, id, Map.of("message", req.message())));
        return Map.of("status", "ok");
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        if (b != null && !b.isBlank()) return b;
        return null;
    }

    /**
     * 讀取批註（#79：這裡原本<b>完全沒有</b>任何檢查）。
     *
     * <h2>缺陷（真實 JWT 線上實測）</h2>
     *
     * <p>taskId {@code ba0454b0-…}（user001 的請假單，持有者 mgr001）上
     * 掛著 mgr001 寫的「薪資調幅尚未報帳，請補附件後再簽」。
     * 實測：{@code user001}／{@code user002}／{@code mgr002} 三個身分
     * 全部 {@code GET 200}，也就是<b>完全無關的人讀得到簽核意見全文</b>。
     * 這是整條鏈上<b>最後一扇還開著的門</b>：taskId 的發射台已由 #76 關掉，
     * 但 taskId 仍可從稽核紀錄（{@code audit:log:read}）取得。
     *
     * <h2>守衛：{@code requireTaskReadAccess}（讀個案內容）</h2>
     *
     * <p>與 variables／form-data／附件／簽核軌跡<b>同一條</b>
     * {@code requireReadAccess}：關係人 ∪ {@code audit:log:read}（旁路每次留痕），
     * 非關係人 404。規則與「為什麼旁路留痕寫在守衛裡」見
     * {@code ProcessAccessGuard.requireTaskReadAccess}。
     *
     * <p>⚠️ <b>刻意不新增 {@code @Transactional}</b>：唯讀查詢 ＋ 一次稽核寫入，
     * 與 {@code ProcessController.getVariables}／{@code HistoryController.getHistoricTasks}
     * 同一型（稽核失敗 → 503，見 {@code AuditFailClosedTest}）。
     * 加交易反而會讓稽核掛在 beforeCommit，響應組裝階段的例外會讓它永遠寫不進去。
     *
     * <p>⚠️ <b>前端的相容性由構造保證</b>：{@code CommentPanel.vue:31} 呼叫本端點，
     * 而它綁的 taskId 來自 {@code DocumentDetail.vue} 的待辦清單 ——
     * 也就是呼叫者<b>持有或可認領</b>的任務，而 assignee／owner／candidateUser
     * 全部落在 {@code isParticipant} 條件 2（{@code taskInvolvedUser}）的比對範圍內。
     * 由 {@code CommentAuthorizationTest} 的兩條正向測試固定住：
     * {@code taskHolderCanStillReadIt}、
     * {@code reviewerCanStillTraverseTheWholeApprovalTimeline}。
     */
    @GetMapping("/{id}/comments")
    public List<Map<String, Object>> getComments(@PathVariable String id,
                                                 @CallerId
                                                 String callerId) {
        accessGuard.requireTaskReadAccess(id, callerId);
        return mapComments(taskService.getTaskComments(id));
    }

    private OperationType resolveCompleteAuditType(Map<String, Object> vars) {
        if (Boolean.TRUE.equals(vars.get("rejected"))) return OperationType.TASK_REJECT;
        if (Boolean.FALSE.equals(vars.get("approved"))) return OperationType.TASK_RETURN;
        if (Boolean.TRUE.equals(vars.get("approved"))) return OperationType.TASK_APPROVE;
        return OperationType.TASK_APPROVE;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(Task t) {
        Map<String, Object> m = new HashMap<>();
        m.put("taskId", t.getId());
        m.put("taskName", t.getName());
        m.put("assignee", t.getAssignee());
        m.put("processInstanceId", t.getProcessInstanceId());
        m.put("createTime", t.getCreateTime());
        m.put("dueDate", t.getDueDate());
        m.put("formKey", t.getFormKey());
        // Resolve locked formVersion from process variable
        try {
            Map<String, Integer> versions = (Map<String, Integer>)
                    runtimeService.getVariable(t.getProcessInstanceId(), "_formVersions");
            if (versions != null && t.getFormKey() != null) {
                m.put("formVersion", versions.get(t.getFormKey()));
            }
        } catch (Exception ignored) {}
        // Enrich with processDefinitionKey and businessKey
        try {
            ProcessInstance pi = runtimeService.createProcessInstanceQuery()
                    .processInstanceId(t.getProcessInstanceId()).singleResult();
            if (pi != null) {
                m.put("processDefinitionKey", pi.getProcessDefinitionKey());
                m.put("businessKey", pi.getBusinessKey());
            }
        } catch (Exception ignored) {}
        return m;
    }

    static List<Map<String, Object>> mapComments(List<org.flowable.engine.task.Comment> comments) {
        return comments.stream()
                .map(c -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("id", c.getId());
                    m.put("message", c.getFullMessage());
                    m.put("userId", c.getUserId() != null ? c.getUserId() : "");
                    m.put("time", c.getTime().toString());
                    return m;
                }).toList();
    }
}
