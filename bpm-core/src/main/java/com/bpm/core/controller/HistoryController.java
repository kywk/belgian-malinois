package com.bpm.core.controller;

import com.bpm.core.audit.AuditEventPublisher;
import com.bpm.core.audit.model.OperationType;
import com.bpm.core.dto.AuditEvent;
import com.bpm.core.security.CallerId;
import com.bpm.core.security.ProcessAccessGuard;
import com.bpm.core.service.InitialAssigneeResolver;
import com.bpm.core.service.ProcessInvolvementService;
import org.flowable.engine.HistoryService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.task.api.Task;
import org.flowable.task.api.history.HistoricTaskInstance;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/history")
public class HistoryController {

    private final HistoryService historyService;
    private final TaskService taskService;
    private final ProcessAccessGuard accessGuard;
    private final ProcessInvolvementService involvementService;
    private final AuditEventPublisher auditPublisher;

    public HistoryController(HistoryService historyService, TaskService taskService,
                             ProcessAccessGuard accessGuard,
                             ProcessInvolvementService involvementService,
                             AuditEventPublisher auditPublisher) {
        this.historyService = historyService;
        this.taskService = taskService;
        this.accessGuard = accessGuard;
        this.involvementService = involvementService;
        this.auditPublisher = auditPublisher;
    }

    /**
     * 簽核時間軸（已完成的任務）。
     *
     * <h2>缺陷：整條攻擊鏈的 id 發射台</h2>
     *
     * <p>改動前沒有 {@code @CallerId}、沒有 {@code requireSelf}、沒有任何參與者檢查。
     * <b>不帶參數即回傳全公司所有已完成任務</b>：{@code id}（＝taskId）、
     * {@code name}、{@code assignee}、{@code processInstanceId}、起訖時間。
     * {@code ?assignee=任何人} 也可以指定他人。
     *
     * <p>它比其他讀端端點更關鍵，因為<b>它產出的 id 正是其餘端點的輸入</b>：
     * {@code PUT /api/tasks/{id}}（#77 已修守衛，但守衛需要先知道 id）、
     * {@code GET /api/tasks/{id}/comments}、{@code GET /api/history/tasks/{taskId}/comments}、
     * {@code GET /api/process-instances/{id}/bpmn-xml}、{@code GET /api/countersign/{taskId}}。
     * 也就是說：只在那些端點加上守衛，等於<b>在沒有門牌的地址上加門鎖</b> ——
     * 而這裡正是門牌能被大量取得的來源。
     *
     * <h2>⚠️ 為什麼不能單純套用 {@code requireSelf(assignee, …)}</h2>
     *
     * <p>前端的 {@code ApprovalTimeline.vue:41} 呼叫
     * {@code getHistoricTasks({ processInstanceId })}，<b>刻意不傳 assignee</b> ——
     * 審核人要看的是「這張單的完整簽核軌跡」（每一關的審核人、時間、意見），
     * 不是「我審過哪些關卡」。若用 {@code requireSelf} 把省略的 assignee 收斂成呼叫者，
     * 審核人打開任何案件都只會看到自己那一格。
     *
     * <p>因此規則必須按參數組合分岔，而<b>不能用「查不到就不查」的方式放寬</b>：
     * 那正是本專案已為之付出過代價的取捨（見 {@code DuplicateApprovalFilterTest}）。
     *
     * <h2>三種參數組合</h2>
     *
     * <table border="1">
     *   <caption>規則</caption>
     *   <tr><th>參數</th><th>檢查</th><th>查詢條件</th><th>誰在用</th></tr>
     *   <tr><td>都不帶</td><td>assignee 收斂成呼叫者</td>
     *       <td>{@code taskAssignee = 我}</td><td>（無既有呼叫端；這是安全預設）</td></tr>
     *   <tr><td>只帶 assignee</td><td>{@code requireSelf}：等於自己才放行，否則 400</td>
     *       <td>{@code taskAssignee = 我}</td><td>{@code Dashboard.vue:68}（本週已完成數）</td></tr>
     *   <tr><td>只帶 processInstanceId</td><td>{@code requireReadAccess}：非關係人 404</td>
     *       <td>{@code processInstanceId}</td><td>{@code ApprovalTimeline.vue:41}（簽核時間軸）</td></tr>
     *   <tr><td>兩個都帶</td><td>兩個條件都要滿足（400 與 404 各自成立）</td>
     *       <td>{@code taskAssignee = 我} <b>且</b> {@code processInstanceId}</td>
     *       <td>（無既有呼叫端）</td></tr>
     * </table>
     *
     * <h3>為什麼「兩個都帶」時查詢也套 assignee</h3>
     *
     * <p>規則層面「兩個條件都要滿足」是授權；查詢層面為什麼也要收斂 assignee：
     * 呼叫端同時指定了兩者，意思是「這張單裡我審過的那些」，那就照它說的做。
     * 少給資料是這條路徑的安全方向，而「多給」才是風險。
     *
     * <p><b>不影響既有前端</b>：{@code ApprovalTimeline} 只傳 pid、
     * {@code Dashboard} 只傳 assignee，兩者都不會同時帶，因此時間軸看到的
     * 仍是完整軌跡。這一條由 {@code HistoricTaskTimelineAuthorizationTest}
     * 的 {@code bothParametersNarrowToTheCaller} 固定住，避免它日後被誤改成
     * 「只驗不收斂」或「忽略 assignee」。
     *
     * <h3>為什麼用 {@code requireReadAccess} 而不是 {@code requireParticipant}</h3>
     *
     * <p>這是<b>讀</b>端點，資料是簽核軌跡（人名、時間、意見），
     * 與 {@code .../{id}/variables}／{@code /api/form-data/{pid}}／附件同屬
     * 「一張單的內容」。讀端政策已經統一為 {@code requireReadAccess}：
     * 關係人，<b>或</b>持有 {@code audit:log:read} 的稽核人員，且旁路每次留痕。
     * 用 {@code requireParticipant} 會讓稽核人員連時間軸都看不到 ——
     * 而「調查一張單時簽核軌跡往往是關鍵證據」正是開那個旁路的理由。
     *
     * <p><b>刻意不新增 @Transactional</b>：唯讀查詢 ＋ 一次稽核寫入，
     * 與 {@code ProcessController.getVariables}／{@code FormDataController.getByProcess}
     * 同一型（稽核失敗 → 503，見 {@code AuditFailClosedTest}）。
     * 加交易反而會讓稽核掛在 beforeCommit，回應組裝階段的例外會讓它永遠寫不進去。
     *
     * <h3>⚠️ 已知限制：只靠候選群組被指派的審核人看不到時間軸</h3>
     *
     * <p>{@code isParticipant} 不涵蓋候選群組（Flowable 的 {@code taskInvolvedUser}
     * 只比對 {@code LINK.USER_ID_}），而這是<b>使用者已明確決定維持現狀</b>的取捨。
     * 影響面不是這個端點獨有的：{@code /api/history/process-instances}、
     * {@code /api/process-instances/involved}、{@code .../variables}、
     * {@code /api/form-data/{pid}} 全部共用同一條判斷，
     * 因此「群組審核人看不到案件層的檢視」是平台層的既有狀態，
     * 這裡只是把簽核軌跡也納入同一條規則，而不是新增一個落差。
     * 真正能動手的群組審核人（claim／complete）走的是 {@code TaskHolderGuard}，
     * 不受影響。
     */
    @GetMapping("/tasks")
    public List<Map<String, Object>> getHistoricTasks(
            @RequestParam(required = false) String assignee,
            @RequestParam(required = false) String processInstanceId,
            @CallerId String callerId) {

        // 條件 1（永遠）：assignee 只准查自己。
        //
        // ⚠️ 為什麼在「有帶 processInstanceId」時<b>也要</b>跑這一行：
        // 若只在沒有 pid 時檢查，呼叫端就能用「pid 合法 + assignee 別人」讓伺服器
        // 替他過濾 —— 那是把伺服器變成一個身分查詢工具，
        // 而且回應內容（別人在這張單審了哪幾關）正是這個端點不該洩漏的。
        String self = accessGuard.requireSelf(assignee, callerId, "assignee");

        // 呼叫端<b>明確</b>帶了 assignee 與「省略」在查詢層是兩件事：
        // 省略時（只給案件）要看完整軌跡，明確帶上時才是「只看這些」。
        boolean explicitAssignee = assignee != null && !assignee.isBlank();

        // 條件 2（有帶 processInstanceId 時）：呼叫者必須是這個案件的關係人。
        String pid = blankToNull(processInstanceId);
        if (pid != null && accessGuard.requireReadAccess(pid, callerId)) {
            // 關係人讀自己的簽核軌跡不留痕（那是日常操作，開單就會讀一次）；
            // 稽核旁路必須留痕，否則「誰以稽核身分調閱了哪些案件」無從追查。
            auditPublisher.publish(new AuditEvent(OperationType.DATA_ACCESS.name(), callerId,
                    pid, null,
                    Map.of("action", "get_historic_tasks", "auditBypass", true)));
        }

        var query = historyService.createHistoricTaskInstanceQuery().finished();
        // ⚠️ assignee 條件<b>不是</b>無條件套用。
        //
        // 「只帶 processInstanceId」是審核時間軸那條路徑，而時間軸的用途
        // 就是看完整軌跡（每一關的審核人、時間、意見）。若在這裡也套上
        // taskAssignee = 呼叫者，審核人打開任何案件都只會看到自己那一格 ——
        // 而且回應看起來完全正常（200、資料齊全、只是少幾列），
        // 沒有任何錯誤訊息會指出「時間軸被收斂了」。
        // 這個型態的缺陷正是本 repo 反覆記錄過的那一種：
        // 授權過度收斂不會讓任何測試紅，只會讓功能少一塊。
        //
        // 授權面向仍然完整：requireSelf 已擋掉「帶別人的 assignee」，
        // requireReadAccess 已擋掉「看無關的案件」，所以「不套 assignee 條件」
        // 放行的只會是「自己參與過的案件的完整軌跡」，沒有任何一個字是
        // 呼叫端沒權看的。
        if (pid == null || explicitAssignee) query.taskAssignee(self);
        if (pid != null) query.processInstanceId(pid);
        return query.orderByHistoricTaskInstanceEndTime().desc().list().stream()
                .map(this::taskToMap).toList();
    }

    /**
     * 空白視同未帶。
     *
     * <p>空白不可能指向別的案件，因此拒絕它只製造無意義的破壞
     * （與 {@code ProcessAccessGuard.requireSelf} 對空白的處理一致）。
     *
     * <p>⚠️ 必須正規化之後才判斷有沒有帶：{@code ?processInstanceId=} 若被當成
     * 「有帶」，{@code requireReadAccess} 會拿空字串去查參與者並回 404 ——
     * 那是把一個「沒有條件」講成「你沒權」；而若完全不理會，
     * {@code query.processInstanceId("")} 會變成一個永遠查不到東西的條件
     * （回 200 + []，看起來像「這張單沒有已完成任務」）。
     * 兩者都是謊話，正規化成「未帶」才是誠實的答案。
     */
    private static String blankToNull(String raw) {
        if (raw == null || raw.isBlank()) return null;
        return raw.trim();
    }

    /**
     * 已完成任務的簽核意見（#79：這裡原本<b>完全沒有</b>任何檢查）。
     *
     * <h2>缺陷：#76 修掉發射台之後，剩下<b>最後一扇門</b></h2>
     *
     * <p>{@link #getHistoricTasks}（#76）已不再回傳全公司所有已完成任務，
     * 但簽核意見是<b>同一批資料的另一面</b>，而且危害更高：時間軸只給
     * 關卡名稱、審核人與時間，這裡給的是<b>意見原文</b> ——
     * 退回理由、駁回原因、薪資調幅。
     *
     * <p>taskId 因此仍有取得路徑：稽核紀錄（持有 {@code audit:log:read} 的
     * 稽核人員看得到 taskId 欄位）與任何已知或猜測到的 id。
     * 實測：與該案無關的 {@code user002} 對 mgr001 的任務 {@code GET 200}，
     * 讀到「薪資調幅尚未報帳，請補附件後再簽」全文。
     *
     * <h2>守衛：{@code requireTaskReadAccess}，與本類別的時間軸<b>同一條規則</b></h2>
     *
     * <p>「這張單的簽核軌跡」與「這張單的簽核意見」都是「一張單的內容」，
     * 所以兩者都走 {@code requireReadAccess}（關係人 ∪ {@code audit:log:read}，
     * 旁路每次留痕），非關係人 404。
     *
     * <p><b>刻意不把 assignee 收斂成呼叫者</b>（對照 {@link #getHistoricTasks}
     * 刻意不收斂 assignee 的同一個理由）：{@code ApprovalTimeline.vue:44}
     * 會對時間軸上的<b>每一個</b> taskId 呼叫本端點，而審核人要看的正是
     * 「別人審了什麼、意見是什麼」。若收斂成「只看我審過的關卡」，
     * 時間軸上會出現一串 404 —— 而那正是 {@code ApprovalTimeline} 在
     * {@code try/catch} 裡吞掉、畫面上只是少幾段文字的失敗型態，
     * 沒有任何錯誤訊息會指出「時間軸被收斂了」。
     *
     * <p>⚠️ <b>本端點對 runtime 任務也有效</b>：
     * {@code ProcessAccessGuard.processInstanceIdOfTask} 是 runtime 優先、
     * 歷史次之。改動前這個「歷史」端點其實也讀得到執行中任務的批註
     * （{@code getTaskComments} 讀的是 {@code ACT_HI_COMMENT}，與任務是否
     * 結束無關），所以解析順序不能只查歷史，否則會把一條既有可用的路徑
     * 打成 404。
     */
    @GetMapping("/tasks/{taskId}/comments")
    public List<Map<String, Object>> getHistoricTaskComments(@PathVariable String taskId,
                                                              @CallerId
                                                              String callerId) {
        accessGuard.requireTaskReadAccess(taskId, callerId);
        return TaskController.mapComments(taskService.getTaskComments(taskId));
    }

    /**
     * 我的申請（歷史）（#71：讀端授權）。
     *
     * <p>規則與 {@code GET /api/process-instances} 完全相同：省略參數 = 呼叫者自己、
     * 帶了別人的 id = 明確 400。改動前不帶參數即回傳<b>全公司</b>的歷史實例。
     *
     * <p>⚠️ <b>曾經完全沒有物件層授權</b>：{@code GET /api/history/tasks}（簽核時間軸）
     * 與 {@code .../comments}（簽核意見）。前者的授權已在
     * {@link #getHistoricTasks} 補上（以 processInstanceId 為條件時驗關係人）；
     * <b>後者已於 #79 補上</b>（{@link #getHistoricTaskComments}，走同一條
     * {@code requireReadAccess}）。也就是說「審核軌跡」與「審核意見」現在
     * 由<b>同一條規則</b>把守 —— 這正是 {@code ProcessAccessGuard} 類別註解
     * 說的「規則只能有一份」：若只修時間軸不修意見，taskId 仍可被逐筆列出，
     * 而那條鏈上就還留著一扇門。
     * 但收件匣頁面就是靠時間軸渲染的，且「點開一個自己參與過的案件看簽核軌跡」
     * 是正常需求，因此它需要的是「以 processInstanceId 為條件時驗參與者」
     * 而不是照搬本方法。見 backlog #71 剩餘項目。
     */
    @GetMapping("/process-instances")
    public List<Map<String, Object>> getHistoricProcessInstances(
            @RequestParam(required = false) String initiator,
            @RequestParam(required = false, defaultValue = "false") boolean finished,
            @CallerId String callerId) {
        String self = accessGuard.requireSelf(initiator, callerId, "initiator");
        var query = historyService.createHistoricProcessInstanceQuery();
        // 代員工發起的案件（R-20）：initiator 是 system:<id>，員工記在 onBehalfOf。
        // 兩者都要比對，否則代發的單不會出現在那位員工的「我的申請」。
        // 回應以 onBehalf=true 標示，讓前端能顯示「由外部系統代為提出」——
        // 使用者看到一張自己沒送過的單，必須知道它是怎麼來的。
        query.or().variableValueEquals("initiator", self)
                .variableValueEquals(InitialAssigneeResolver.ON_BEHALF_OF_VAR, self).endOr();
        java.util.Set<String> onBehalf = historyService.createHistoricProcessInstanceQuery()
                .variableValueEquals(InitialAssigneeResolver.ON_BEHALF_OF_VAR, self).list()
                .stream().map(HistoricProcessInstance::getId).collect(java.util.stream.Collectors.toSet());
        if (finished) query.finished();
        final java.util.Set<String> delegated = onBehalf;
        return query.orderByProcessInstanceStartTime().desc().list().stream()
                .map(p -> {
                    Map<String, Object> m = processToMap(p);
                    m.put("onBehalf", delegated.contains(p.getId()));
                    return m;
                }).toList();
    }

    /**
     * 我參與的案件（歷史，含仍在執行中的）。
     *
     * <p>回應形狀沿用 {@link #processToMap}（它已涵蓋 running／completed／
     * cancelled 三種狀態），再補上 {@code currentTask} 與
     * {@code currentTaskCount} —— 前者讓使用者知道這張單現在卡在誰手上，
     * 後者是因為併發任務時只顯示一個會讓人以為只等一個人。
     *
     * <p>⚠️ {@code processToMap} 的輸出<b>永遠不可</b>用
     * {@code singleResult()} 取得 currentTask（見 ProcessController 內的註解）：
     * 平行閘道／multi-instance 會簽會產生併發任務，那會讓整個端點對所有人 500。
     * 這裡改成一次撈回所有相關實例的未完成任務（見 {@link ProcessInvolvementService}）。
     *
     * <p>授權：不帶任何參數，範圍就是「{@code isParticipant} 為真的案件」。
     */
    @GetMapping("/process-instances/involved")
    public List<Map<String, Object>> getInvolvedHistoricProcessInstances(@CallerId String callerId) {
        if (callerId == null || callerId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "無法確認身分，請先登入");
        }
        java.util.Set<String> ids = involvementService.involvedHistoricInstanceIds(callerId);
        if (ids.isEmpty()) return List.of();

        // currentTask 只對執行中的實例有意義；已結束的實例在 runtime 查不到任務。
        java.util.Map<String, List<Task>> tasksByInstance = new java.util.HashMap<>();
        for (Task t : involvementService.findOpenTasks(ids)) {
            tasksByInstance.computeIfAbsent(t.getProcessInstanceId(), k -> new java.util.ArrayList<>()).add(t);
        }
        return involvementService.findHistoric(ids).stream()
                .map(p -> {
                    Map<String, Object> m = processToMap(p);
                    // 標示「這不是我的申請，是我參與的」，否則使用者會以為
                    // 這張自己沒送過的單出現在自己的清單裡。
                    m.put("involved", true);
                    List<Task> tasks = tasksByInstance.getOrDefault(p.getId(), List.of());
                    if (!tasks.isEmpty()) {
                        Task task = tasks.get(0);
                        m.put("currentTask", Map.of(
                                "taskName", task.getName() != null ? task.getName() : "",
                                "assignee", task.getAssignee() != null ? task.getAssignee() : ""));
                        m.put("currentTaskCount", tasks.size());
                    }
                    return m;
                }).toList();
    }

    private Map<String, Object> taskToMap(HistoricTaskInstance t) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", t.getId());
        m.put("name", t.getName());
        m.put("assignee", t.getAssignee());
        m.put("processInstanceId", t.getProcessInstanceId());
        m.put("startTime", t.getStartTime());
        m.put("endTime", t.getEndTime());
        return m;
    }

    /**
     * 歷史實例的對外表示法。
     *
     * <p>{@code status} 由 {@code endTime}／{@code deleteReason} 推導，
     * 因此同一份形狀涵蓋執行中與已結束 —— 這是「我參與的」新端點
     * 能只維護一份對映的原因。
     */
    Map<String, Object> processToMap(HistoricProcessInstance p) {
        Map<String, Object> m = new HashMap<>();
        m.put("processInstanceId", p.getId());
        m.put("processDefinitionKey", p.getProcessDefinitionKey());
        m.put("businessKey", p.getBusinessKey());
        m.put("startTime", p.getStartTime());
        m.put("endTime", p.getEndTime());
        // Derive status
        if (p.getEndTime() == null) {
            m.put("status", "running");
        } else if (p.getDeleteReason() != null) {
            m.put("status", "cancelled");
        } else {
            m.put("status", "completed");
        }
        return m;
    }
}
