package com.bpm.core.controller;

import org.springframework.transaction.annotation.Transactional;
import com.bpm.core.security.CallerId;
import com.bpm.core.security.TaskHolderGuard;
import com.bpm.core.external.ExternalActorGuard;
import com.bpm.core.audit.AuditEventPublisher;
import com.bpm.core.dto.AuditEvent;
import org.flowable.engine.TaskService;
import org.flowable.task.api.Task;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 加簽（附屬簽）。
 *
 * <h2>#77：這個 class 改動前<b>連 {@code @CallerId} 都沒有</b></h2>
 *
 * <p>三個端點全部零授權檢查，而它們的後果比「讀到不該讀的資料」嚴重：
 * 加簽子任務是 <b>standalone task</b>，一旦建立，父任務就會被
 * {@code TaskController} 的「有未完成的加簽子任務」守門<b>永久</b>擋住
 * （409），直到那筆子任務完成為止。攻擊者只要建立一筆自己不去完成的子任務，
 * 就能讓案件停擺 —— <b>這不是資訊洩漏，是業務停擺</b>，而且沒有任何錯誤訊息，
 * 受害者只會看到「這張單一直卡在主管審核」。
 *
 * <h2>三個端點為什麼全部接 {@link TaskHolderGuard}（而不是 ProcessAccessGuard）</h2>
 *
 * <p>加簽是<b>對「這個任務」動手</b>，不是對「這個案件」動手。
 * 判斷對象是任務的持有者（assignee ∪ owner ∪ candidateUser ∪ 候選群組），
 * 而不是案件的關係人（initiator ∪ 曾／正在處理該案任務的人）。兩者條件不同，
 * 混用會出現兩種錯誤：
 *
 * <ul>
 *   <li>用 {@code requireParticipant}：申請人（案件的 initiator）會被放行 ——
 *       等於「任何人只要自己送的單，就能替主管決定要找誰加簽」，
 *       審核權責被基層改寫，而且那筆子任務照樣會把主管的任務卡住。</li>
 *   <li>用 {@code requireHolder}：只有正在處理這個任務的人能加簽。
 *       這與 delegate／reassign 是同一種權力（把工作轉給別人），
 *       也與 {@code PUT /api/tasks/{id}} 走同一條規則（#77）。</li>
 * </ul>
 *
 * <p>讀端（{@code getSubtasks}）刻意<b>不比寫端寬</b>：它回傳的
 * subtaskId 正是 {@code completeSubtask} 的路徑參數，也就是「替別人簽掉加簽」
 * 這條攻擊鏈的下一站。放寬讀端等於把下一站的輸入免費送給攻擊者。
 * 前端不受影響：{@code DocumentDetail.vue:128-134} 只在
 * {@code getTasks()}（自己的收件匣）回傳的任務上呼叫它，
 * 因此「看得到加簽狀態的人」本來就是持有者。
 *
 * <h2>⚠️ 授權一律排在任何狀態改變之前</h2>
 *
 * <p>{@code saveTask}／{@code addComment}／{@code complete} 都是不可逆的，
 * 而 {@code ResponseStatusException} 會讓交易回滾 —— 但稽核事件走的是
 * {@code publishDetached}／{@code publish}，前者不跟隨交易。
 * 順序若寫反，就會出現「回 404 但資料已經被改掉」或
 * 「留下稽核紀錄說有人做了這件事，但其實沒有」。授權、參數形狀、
 * 目標對象的驗證全部在任何寫入之前完成。
 */
@RestController
@RequestMapping("/api/countersign")
public class CountersignController {

    private final TaskService taskService;
    private final AuditEventPublisher auditPublisher;
    private final TaskHolderGuard holderGuard;
    // #93a：「加簽對象必須是組織系統認識的人」的實作只有這一份
    // （ExternalActorGuard.requireKnownPerson），與 #88 的 firstTaskAssignee／
    // onBehalfOf、#92 的 reassign assignee 共用。
    private final ExternalActorGuard actorGuard;

    public CountersignController(TaskService taskService, AuditEventPublisher auditPublisher,
                                 TaskHolderGuard holderGuard, ExternalActorGuard actorGuard) {
        this.taskService = taskService;
        this.auditPublisher = auditPublisher;
        this.holderGuard = holderGuard;
        this.actorGuard = actorGuard;
    }

    /**
     * 建立加簽子任務。
     *
     * <h2>改動前的三個缺陷（依嚴重程度）</h2>
     *
     * <ol>
     *   <li><b>持久化 DoS</b>：任何登入者都能對<b>任何</b>任務加簽。
     *       子任務落地後，{@code TaskController} 的 409 守門會把父任務
     *       <b>永久</b>擋住 —— 只要攻擊者不去完成那筆子任務，
     *       案件就停擺，而且沒有任何錯誤訊息指出原因。</li>
     *   <li><b>審核權責錯置</b>：{@code countersignUserId} 由呼叫端指定，
     *       沒有任何驗證。無關的同事可以替某人「安排」一筆他不知道的審核。</li>
     *   <li><b>稽核指向無辜的第三人</b>：{@code AuditEvent} 的 operatorId
     *       傳的是 <b>被指派人</b>（{@code assignee}），不是呼叫者。
     *       於是稽核紀錄會說「user003 做了加簽」，
     *       而實際按下去的是 mgr001。這比沒有稽核更糟：
     *       它讓稽核指向一個從未參與該動作的人。</li>
     * </ol>
     *
     * <h2>檢查順序：授權 → 形狀 → 目標對象 → 寫入</h2>
     *
     * <p>與 {@code TaskController.updateTask} 的「形狀 → 授權」<b>刻意相反</b>。
     * 那裡先驗形狀是因為 action 欄位固定、body 形狀有限；
     * 這裡的 body 是自由 map，而 {@code taskId} 是一個永久性業務後果的
     * 業務物件 id。若先驗形狀，非持有者就能用「送出空白 body → 400」
     * 與「送出合法 body → 404」分辨<b>某個 taskId 是否存在</b> ——
     * 那就是把枚舉管道留在原地。先驗授權則兩種請求一律 404，
     * 「不存在」與「不是你的」無法分辨。
     *
     * <h2>⚠️ 為什麼還要驗證 {@code countersignUserId} 是不是組織系統認識的人</h2>
     *
     * <p>這是同一個死鎖的<b>另一個觸發點</b>，不是另一個問題：
     * 本方法原本已經為了「assignee 為 null → {@code Map.of} 拋 NPE → 500
     * 但子任務已落地 → 案件死鎖」而擋下空白 assignee。一個<b>非空白但不存在</b>
     * 的 id 有完全相同的後果，而且更難察覺：子任務指派給一個沒有人認得的人，
     * 沒有人看得到它、沒有人能完成它，父任務被 409 永遠擋住。
     * 空白至少會回 400 讓前端立刻發現；打錯一個員工編號不會有任何訊號。
     *
     * <p><b>先例</b>：{@code ExternalApiController.startProcess} 對
     * {@code onBehalfOf} 做的正是這件事（問組織系統，例外即「不是組織系統認識的人」）。
     * 沿用同一個做法與同一個理由：一個查不到的對象會讓下游無法路由，
     * 且只會製造混亂。
     *
     * <h2>⚠️ #93a：這條規則的實作只有一份，在 {@code ExternalActorGuard}</h2>
     *
     * <p>本方法原本自己有一份私有實作（只問一次組織系統）。現在改為呼叫
     * {@code ExternalActorGuard.requireKnownPerson}，與 {@code firstTaskAssignee}／
     * {@code onBehalfOf}（#88）、{@code reassign} 的 assignee（#92）共用同一份。
     * 收斂的完整理由見下方呼叫處的註解；這裡只補一句本方法特有的：
     * 空白那一層對本端點是死碼（第 2 段的形狀檢查先擋掉），但仍然呼叫完整的
     * 三層，因為「規則只有一份」意味著呼叫端不該知道哪一層對它有意義 ——
     * 而 {@code actorGuard} 的空白訊息會提到 {@code firstTaskCandidateGroups}，
     * 那是 {@code firstTaskAssignee} 的欄位，本 body 沒有。與 #92 的
     * {@code reassign} 完全同理。
     *
     * <p>⚠️ <b>交易內的外部呼叫</b>：這一次查詢發生在
     * {@code @Transactional("primaryTransactionManager")} 之内，
     * 而 {@code org-service-url} 在 mock／開發階段指向 bpm-core 自己 ——
     * 也就是容器內對自己發同步 HTTP（見 {@code OrgRestClient} 類別註解的
     * security-audit P1-10 自我死鎖論述）。接受這個風險的三個理由：
     * <ol>
     *   <li>既有先例就在做同一件事：{@code ExternalApiController.startProcess}
     *       同樣在 {@code @Transactional} 內呼叫 {@code getDirectManager}。</li>
     *   <li>它有 <b>60 分鐘 TTL</b>快取（{@code OrgService.getDirectManager}），
     *       所以同一個人一小時內只有第一次會真的打出去 ——
     *       暴露量被壓到極低頻率，這也是當初刻意讓「鏈頂人員」也進快取的原因。</li>
     *   <li>要把它移出交易就得把這個方法拆成「非交易驗證 ＋ 另一個 bean 的交易」，
     *       為了一個已有快取、有既有先例的查詢引入 self-invocation 的 proxy 陷阱，
     *       複雜度與風險都高於收益。</li>
     * </ol>
     * 而 {@link TaskHolderGuard} 之所以在交易內先做一次本地 identity link 檢查
     * 才算群組，是因為它<b>每個簽核請求都會</b>走到那裡；這裡是使用者明確
     * 按下「加簽」才發生的一次性動作，量級完全不同。
     *
     * <p><b>組織系統故障時擋下（fail-closed），並把兩種原因分開回報</b>：
     * 「這個人不存在」是呼叫端的輸入錯誤（400，改選人即可）；
     * 「組織系統現在查不到」是暫時故障（503，稍後重試）。
     * 把兩者合併成同一個 400 會讓使用者以為是自己選錯人，
     * 而實際上重試當下就會成功。
     * 反過來<b>不</b>放行的理由：放行等於在故障期間持續製造死鎖。
     */
    @PostMapping("/{taskId}")
    @Transactional("primaryTransactionManager")
    public Map<String, Object> createSubtask(@PathVariable String taskId,
                                              @RequestBody Map<String, String> req,
                                              @CallerId
                                              String callerId) {
        Task parent = taskService.createTaskQuery().taskId(taskId).singleResult();
        if (parent == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found");

        // ── 1. 授權（最優先）──────────────────────────────────────
        // 未認證 → 401；非持有者 → 404。兩種狀態都發生在任何寫入之前。
        // 拒絕會由 requireHolder 留一筆 DATA_ACCESS {denied:true}。
        holderGuard.requireHolder(parent, callerId);

        String assignee = req == null ? null : req.get("countersignUserId");

        // ── 2. 形狀 ──────────────────────────────────────────────
        // ⚠️ 這個驗證必須在 saveTask 之前，順序是重點而非防禦性程式碼。
        //
        // 改動前沒有任何驗證：assignee 為 null 時子任務仍會在下面落地
        // （assignee 為 null → 任務清單查不到它、沒有人看得到），
        // 接著 Map.of("assignee", null) 拋 NPE 變成 500。
        // 結果是父任務被「有未完成加簽子任務」的守門永遠攔住 ——
        // 案件死鎖，只能進 DB 手動清。
        //
        // 前端過去正是送出 {assignee, description}（欄位名與後端不一致），
        // 因此每一次加簽都會踩到這條路徑。
        if (assignee == null || assignee.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "countersignUserId 為必填（注意：欄位名不是 assignee）");
        }
        assignee = assignee.trim();

        // ── 3. 目標對象必須是組織系統認識的人 ────────────────────
        //
        // ⚠️ #93a：這裡原本呼叫本檔的私有實作 requireKnownEmployee(userId)，
        // 內容是「try orgService.getDirectManager(userId)，404 → 400、
        // 其他例外 → 503」。它已刪除，現在與 firstTaskAssignee／onBehalfOf（#88）、
        // reassign 的 assignee（#92）共用 ExternalActorGuard 這一份。
        //
        // 為什麼刪除而不是只在舊實作上補掉缺的兩層：
        //
        //   1. 同一條規則有兩份實作，正是本專案反覆記載的缺陷成因
        //      （#84／#86／#87）。若本檔留著私有實作，就會變成
        //      「外部入口擋一種形狀、加簽擋另一種形狀」——
        //      而兩邊的錯誤訊息不一致，正是本 repo 記錄過的失敗型態。
        //   2. 舊實作缺的「system: 前綴」那一層<b>不是裝飾</b>：舊實作只能靠
        //      「組織系統查不到」擋下 system:evil，而那個證據
        //      <b>只有在組織系統 fail-closed 時才可信</b>。本專案自己的
        //      MockOrgController 整整兩輪都是 fail-open（對不認識的 id 回 mgr001），
        //      而那正是 #83 的缺陷兩輪沒被任何測試抓到的原因。收斂之後
        //      system: 前綴由<b>不打網路</b>的純字串比對擋下，不依賴任何前提。
        //
        // 狀態碼語意<b>不變</b>（舊實作本來就分 400／503，ExternalActorGuard
        // 是同一組政策），差異只在多了兩層與統一了訊息。刻意不在本檔自創第四組
        // 狀態碼。
        //
        // 不寫稽核事件：這是一個<b>已授權</b>操作上的輸入錯誤（400），
        // 不是授權拒絕。DATA_ACCESS {denied:true} 的語意是「有人嘗試存取
        // 他沒有權限的東西」，把它用在打錯字上會稀釋它的訊號。
        //
        // ⚠️ 空白那一層對本端點是<b>死碼</b>（第 2 段的形狀檢查先擋掉），
        // 而這一點必須寫下來。仍然呼叫完整的三層是刻意的 ——
        // 「規則只有一份」意味著呼叫端不該知道哪一層對它有意義；
        // 而且 actorGuard 的空白訊息提到 firstTaskCandidateGroups
        // （那是 firstTaskAssignee 的欄位），若真的讓它觸發，
        // 訊息會指向一個本 body 沒有的欄位。與 #92 的 reassign 同理：
        // 空白形狀檢查保留在本地，訊息才指得準。
        //
        // 舊實作註解裡記錄的兩個仍然有效的觀察，一併留在這裡以免遺失：
        //   * 鏈頂人員（dir001／admin001）回 null 而非例外 —— 「此人存在但沒有主管」。
        //     所以判斷依據是「有沒有拋例外」，絕不可寫成
        //     `if (getDirectManager(x) == null) reject`（那會擋掉總監本人）。
        //     ExternalActorGuard 的第三層正是這樣寫的。
        //   * 「組織系統說這個人存在」只在 fail-closed 時可信。若對接的真實組織
        //     系統會捏造預設值，這條規則會退化 —— 正確的補法是改用明確的存在性
        //     查詢（OrgRestClient 已有 getUser，但 OrgService 尚未暴露它，
        //     也沒有為它決定快取政策）。那是另一個工項，不在這裡順手決定。
        actorGuard.requireKnownPerson("countersignUserId", assignee);

        Task subtask = taskService.newTask();
        subtask.setParentTaskId(taskId);
        subtask.setAssignee(assignee);
        subtask.setName("加簽審核 - " + parent.getName());
        subtask.setDescription(req.getOrDefault("message", ""));
        taskService.saveTask(subtask);

        // Map.of 不接受 null 值。assignee 在上面已驗證非空，
        // 但 subtask.getName() 仍可能為 null（parent.getName() 為 null 時），
        // 因此回傳值改用允許 null 的 HashMap，避免同一類 NPE 再次把
        // 成功的操作變成 500。
        //
        // ⚠️ operatorId 改為呼叫者（#77 的同一條原則，見 TaskController 的說明）：
        // 改動前傳的是 assignee，也就是被指派的人 —— 稽核會記成
        // 「user003 發起了這筆加簽」，而實際按下去的是 mgr001。
        // 被指派的人放進 detail 才是它該待的地方：稽核要回答的是
        // 「誰做的決定」與「決定了什麼」兩個不同的事實。
        //
        // detail 的鍵維持 assignee（不改成 countersignUserId）：那是既有的
        // 稽核欄位契約，改名對這次授權修正沒有任何好處，卻會讓既有下游
        // （稽核檢視、匯出）突然讀不到值。request body 欄位名不一致是
        // 另一件事，不該順手在稽核紀錄上再製造一次。
        //
        // 一層 fallback 都不留：requireHolder 保證 callerId 非空白
        // （未認證已先被 401 擋下）。
        auditPublisher.publish(new AuditEvent("TASK_COUNTERSIGN", callerId,
                parent.getProcessInstanceId(), subtask.getId(),
                Map.of("parentTaskId", taskId, "assignee", assignee)));

        Map<String, Object> result = new HashMap<>();
        result.put("taskId", subtask.getId());
        result.put("parentTaskId", taskId);
        result.put("assignee", assignee);
        result.put("name", subtask.getName());
        return result;
    }

    /**
     * 加簽鏈（子任務清單）。
     *
     * <h2>⚠️ 這是 subtaskId 的發射台</h2>
     *
     * <p>回傳的 {@code taskId} 就是 {@code completeSubtask} 的路徑參數。
     * 改動前任何登入者都能對任意 taskId 列出加簽鏈與被指派人 ——
     * 也就是同時洩漏「誰正在被要求加簽」，並把下一站攻擊所需的 id 交出來。
     *
     * <h2>為什麼用 {@code requireHolder}（持有者）而不是參與者</h2>
     *
     * <ol>
     *   <li>前端唯一的呼叫者是 {@code DocumentDetail} 的「加簽狀態」卡片，
     *       而該頁只在 <b>自己的收件匣</b>（{@code getTasks()}）找到任務時才渲染 ——
     *       看得到這個清單的人本來就是持有者，用參與者不會多給任何東西。</li>
     *   <li>用更寬的規則會讓讀寫兩端不一致：能讀加簽鏈卻不能加簽，
     *       是一個沒有人需要的中間狀態（見 {@code TaskHolderGuard} 類別註解
     *       「為什麼授權規則只能有一份」）。</li>
     * </ol>
     */
    @GetMapping("/{taskId}")
    public List<Map<String, Object>> getSubtasks(@PathVariable String taskId,
                                                 @CallerId String callerId) {
        Task parent = taskService.createTaskQuery().taskId(taskId).singleResult();
        if (parent == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found");
        holderGuard.requireHolder(parent, callerId);

        return taskService.getSubTasks(taskId).stream().map(t -> {
            Map<String, Object> m = new HashMap<>();
            m.put("taskId", t.getId());
            m.put("name", t.getName());
            m.put("assignee", t.getAssignee());
            m.put("description", t.getDescription());
            m.put("createTime", t.getCreateTime());
            return m;
        }).toList();
    }

    /**
     * 完成加簽子任務。
     *
     * <p>改動前有三個獨立問題，合起來讓「加簽」可以被第三方跳過且無痕
     * （security-audit P0-6）：
     *
     * <ol>
     *   <li><b>兩個路徑參數從未驗證屬於同一組</b> —— {@code getParentTaskId()}
     *       根本沒被讀取。帶任意 {@code taskId} 加上他人的 {@code subtaskId}
     *       即可刪掉尚未審的加簽子任務；子任務一消失，父任務的守門立刻放行
     *       → 加簽人從未表態，案子照樣過關。</li>
     *   <li><b>{@code deleteTask(subtaskId, true)} 的 cascade=true 連歷史一併
     *       刪除</b> —— 「誰加簽、何時完成」完全無跡可查。</li>
     *   <li><b>不發任何稽核事件</b>。</li>
     * </ol>
     *
     * <h2>#77：還有第四個 —— 父子配對正確<b>也不等於</b>你可以簽</h2>
     *
     * <p>配對驗證只證明「這兩個 id 是一組」，不證明「你與這組有關」。
     * 任何登入者只要同時知道一組配對的 {@code taskId}/{@code subtaskId}，
     * 就能替別人把加簽簽掉 —— <b>加簽人從未表態，案子照樣過關</b>，
     * 而且稽核的 operatorId 會記成那個真正該簽的人（改動前 operatorId 優先取
     * {@code @CallerId}，但呼叫端那時還沒有 {@code @CallerId}，落到
     * {@code subtask.getAssignee()}，也就是把動作記在無辜的加簽人頭上）。
     * {@code CountersignTamperTest} 只測了不配對，沒測「配對正確但非本人」。
     *
     * <p><b>授權排在父子驗證之後、任何寫入之前</b>：父子驗證先做是因為它
     * 只花一次本地查詢，且能擋掉「拿別的案件的 taskId 來配對」的探測；
     * 兩者都必須在 {@code addComment}／{@code complete} 之前，
     * 否則會出現「回 404 但意見已經寫進去、子任務也已經被完成」。
     */
    @PutMapping("/{taskId}/{subtaskId}/complete")
    @Transactional("primaryTransactionManager")
    public Map<String, Object> completeSubtask(@PathVariable String taskId,
                                                @PathVariable String subtaskId,
                                                @RequestBody(required = false) Map<String, String> req,
                                                @CallerId
                                                String operatorId) {
        Task subtask = taskService.createTaskQuery().taskId(subtaskId).singleResult();
        if (subtask == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);

        // ── 父子關係驗證（P0-6 的核心）──────────────────────────────
        // 不相符時回 404 而非 403：不洩漏「這個 subtaskId 存在」這件事，
        // 否則就成了列舉他人加簽任務的管道。
        if (!subtaskId.equals(taskId) && !taskId.equals(subtask.getParentTaskId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "子任務不屬於指定的父任務");
        }

        // ── 授權：呼叫者必須是這個子任務的持有者（#77）───────────────
        //
        // 加簽子任務的持有者事實上就是它的 assignee（建立時指定，
        // 沒有候選人、沒有候選群組），但仍走 requireHolder 而不是
        // `subtask.getAssignee().equals(operatorId)`：
        // 前者與 PUT /api/tasks/{id} 用同一份規則，delegate 之後
        // delegatee 與 owner 都還能完成自己的加簽（見 TaskHolderGuard
        // 的生命週期分析）；後者會在未來支援「候選人領取加簽」時
        // 靜默把它打死，而那正是「查不到就不查」的老問題。
        holderGuard.requireHolder(subtask, operatorId);

        Task parent = taskService.createTaskQuery().taskId(taskId).singleResult();
        String processInstanceId = parent != null ? parent.getProcessInstanceId() : null;

        String opinion = req != null ? req.getOrDefault("opinion", "") : "";
        if (!opinion.isBlank()) {
            taskService.addComment(taskId, processInstanceId,
                    "[加簽意見 - " + subtask.getAssignee() + "] " + opinion);
        }

        // ── 用 complete 而非 deleteTask ─────────────────────────────
        // complete() 讓 standalone 任務正常進入歷史；deleteTask(.., true)
        // 的 cascade 會把歷史一起刪掉，等於抹除加簽曾經發生的證據。
        // 兩者都會讓它離開待辦，因此父任務的守門一樣會放行。
        taskService.complete(subtaskId);

        // operatorId 一律是呼叫者，fallback 到 subtask.getAssignee() 已移除。
        //
        // requireHolder 保證 operatorId 非空白（未認證先被 401 擋下），
        // 所以那一層<b>已經是死碼</b>；而它描述的心智模型 ——
        // 「操作者可能不是呼叫者」—— 正是這個缺陷的成因。
        //
        // ⚠️ 副作用（刻意接受）：守衛加上之後，這裡的 operatorId
        // 必然等於 subtask.getAssignee()，因為持有者只可能是被指派人。
        // 因此「稽核的 operatorId 必須是呼叫者」這條規則<b>真正決定性的
        // 證據在 createSubtask</b>（那裡呼叫者與被指派人是兩個不同的人）。
        auditPublisher.publish(new AuditEvent("TASK_COUNTERSIGN", operatorId,
                processInstanceId, subtaskId,
                Map.of("parentTaskId", taskId,
                       "action", "complete",
                       "opinion", opinion)));

        return Map.of("subtaskId", subtaskId, "allSubtasksDone", taskService.getSubTasks(taskId).isEmpty());
    }
}
