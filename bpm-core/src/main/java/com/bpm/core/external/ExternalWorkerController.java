package com.bpm.core.external;

import com.bpm.core.audit.AuditEventPublisher;
import com.bpm.core.dto.AuditEvent;
import org.flowable.common.engine.api.FlowableException;
import org.flowable.common.engine.api.FlowableIllegalArgumentException;
import org.flowable.common.engine.api.FlowableObjectNotFoundException;
import org.flowable.engine.ManagementService;
import org.flowable.job.api.AcquiredExternalWorkerJob;
import org.flowable.job.api.ExternalWorkerJob;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工項 #22：External Worker Task 的外部 API（輪詢認領）。
 *
 * <h2>這是什麼</h2>
 *
 * <p>BPMN 的 {@code <serviceTask flowable:type="external-worker" flowable:topic="..."/>}
 * 會在流程走到該節點時建立一個「外部工作者任務」（Flowable 7 的
 * {@code ACT_RU_EXTERNAL_JOB}），等待某個外部系統認領後回報完成或失敗。
 * 本類別把 {@link ManagementService} 的 external worker API 包成
 * {@code /api/external/worker/**}，認證走既有的 {@link ExternalApiAuthFilter}
 * （X-API-Key ＋ X-System-Id），授權走既有的
 * {@link ExternalSystemPolicy}（allowedActions 需含 {@code external_worker}）。
 *
 * <h2>⚠️ workerId 一律由伺服器鑄造：{@code system:<systemId>}</h2>
 *
 * <p>Flowable 的完成／失敗／釋放都要求傳入<b>當初認領時使用的 workerId</b>，
 * 並以它比對 job 的 lock owner（{@code AbstractExternalWorkerJobCmd.resolveJob}）。
 * 換句話說 workerId 就是鎖的擁有權憑證。
 *
 * <p>若讓呼叫端自由指定 workerId，系統 B 只要在請求裡填
 * {@code "workerId":"system:erp"} 就能完成系統 A 鎖定的 job —— 認證過了、
 * 授權過了，而擁有權檢查被一個可偽造的字串繞過。因此本 API 的有效
 * workerId 固定是 {@link ExternalActorIdentity#of} 鑄造的系統身分；
 * body 的 {@code workerId} 只在「省略、空白、或等於 {@code system:<id>}」
 * 時接受，其餘一律 400（指名拒絕，不靜默忽略 —— 與 R-20 的 initiator 同一個
 * 政策：靜默忽略會讓呼叫端以為指定的 workerId 生效了）。
 *
 * <p>代價：同一個外部系統的多個 worker 行程共用一個身分。這是刻意的
 * —— 本平台的授權單位是外部系統，不是行程；同一系統的 worker 彼此
 * 可以接手對方鎖定的 job，跨系統則不行。
 *
 * <h2>⚠️ topic 是共用佇列，擁有權從「認領」開始</h2>
 *
 * <p>Flowable 的 acquire 只按 topic ＋「尚未鎖定」挑 job（原始碼
 * {@code selectExternalWorkerJobsToExecute} 的 {@code LOCK_EXP_TIME_ is null}），
 * 沒有任何「這個 job 屬於哪個系統」的維度。所以一個尚未被認領的 job
 * 是<b>先搶先贏</b>：任何被授權 {@code external_worker} 的系統都可能認領到它。
 * 擁有權保證的是「認領之後只有認領者能完成／失敗／釋放」。
 * 需要嚴格隔離時，請用系統專屬的 topic 名稱（例如 {@code erp-invoices}），
 * 而不是多個系統共用一個 topic。
 *
 * <p>⚠️ {@code allowedProcessKeys} 不套用在 worker 路徑上：job 的
 * processDefinitionKey 只能認領後才知道，而 acquire API 不提供
 * 「只認領授權流程」的過濾。此為已知限制，未自行擴大本工項範圍。
 *
 * <h2>狀態碼的分工</h2>
 *
 * <ul>
 *   <li><b>401</b>：缺 X-API-Key／X-System-Id，或金鑰查無系統
 *       （由 {@link ExternalApiAuthFilter} 決定，本類別不經手）。</li>
 *   <li><b>403</b>：系統停用、IP 不在白名單、allowedActions 不含
 *       {@code external_worker}，或未知的 worker 路徑（filter fail-closed）。</li>
 *   <li><b>400</b>：payload 不合法 —— 缺 topic、lockDurationSeconds
 *       不是正整數、workerId 指名他人、variables 形狀錯或用了
 *       {@code _} 保留前綴。</li>
 *   <li><b>404</b>：job 不存在<b>或</b>未由本系統鎖定。兩者刻意共用同一個
 *       狀態碼與訊息：若「存在但別人的」回 409、「不存在」回 404，
 *       任何通過認證的系統就能用狀態碼差異枚舉他人的 jobId。
 *       （使用者 2026-10-03 裁決：404 或 409 皆可，選 404 並保持一致。）</li>
 * </ul>
 *
 * <h2>稽核</h2>
 *
 * <p>acquire（僅在真的認領到 job 時）／complete／fail／unacquire 各寫一筆
 * {@code EXTERNAL_API_CALL}，operator 是 {@code system:<id>}，
 * detail 帶 {@code action}（{@code external_worker_acquire} 等）、
 * {@code jobId}、{@code topic}。⚠️ <b>流程變數一律不進稽核</b> ——
 * 稽核庫是 append-only 且保存期遠長於流程資料，變數內容（含個資）不該
 * 沉澱在那裡。空的 acquire（佇列沒東西）刻意不寫稽核：worker 是輪詢的，
 * 每次空輪詢都寫會讓稽核量隨輪詢頻率無上限成長。
 *
 * <h2>⚠️ 變數的保留前綴檢查為什麼在這裡又出現一次</h2>
 *
 * <p>{@code ExternalApiController.completeTask} 已有一份
 * {@code rejectReservedVariableNames}，而本工項的檔案邊界不得動那個檔案，
 * 所以這裡以<b>相同訊息</b>重述同一條規則。這不是新政策：worker 的
 * completion builder 會把變數直接寫進流程實例，{@code _externalSystemId}
 * 一旦可被覆寫，任何認領到 job 的系統都能把別人的案件過戶給自己
 * （R-19／R-23 的同一個缺陷面）。後續應把兩份抽成共用（規則只能有一份）。
 */
@RestController
@RequestMapping("/api/external/worker")
public class ExternalWorkerController {

    /** 未指定 lockDurationSeconds 時的鎖定長度（秒）。 */
    static final int DEFAULT_LOCK_SECONDS = 300;

    /** lockDurationSeconds 的上限（一天）。避免呼叫端用超大值長期佔住 job。 */
    static final int MAX_LOCK_SECONDS = 86_400;

    private final ManagementService managementService;
    private final AuditEventPublisher auditPublisher;

    public ExternalWorkerController(ManagementService managementService,
                                    AuditEventPublisher auditPublisher) {
        this.managementService = managementService;
        this.auditPublisher = auditPublisher;
    }

    // ── 1. Acquire（認領並鎖定）──────────────────────────────────────

    /**
     * 從 topic 佇列認領<b>一個</b>尚未鎖定的 job 並鎖定給本系統。
     *
     * <p>body：{@code {"topic":"...", "workerId":"...", "lockDurationSeconds":300}}。
     * 每次最多認領一個（{@code acquireAndLock(1, ...)}）：worker 是輪詢模型，
     * 一次一個讓失敗的 blast radius 最小，呼叫端要平行度就多打幾次。
     * 回應 {@code {"tasks":[...]}}，佇列空時是 {@code {"tasks":[]}}（不是 404
     * —— 「現在沒有工作」是正常狀態，不是錯誤）。
     *
     * <p>⚠️ 回傳的 {@code variables} 是該流程實例當下的變數（Flowable
     * {@link AcquiredExternalWorkerJob#getVariables()}）。worker 需要輸入
     * 資料才能工作，所以認領時帶回；見類別註解「topic 是共用佇列」——
     * 需要嚴格隔離請用系統專屬 topic。
     */
    @PostMapping("/tasks/acquire")
    @Transactional("primaryTransactionManager")
    public Map<String, Object> acquire(@RequestBody(required = false) Map<String, Object> body,
                                       @RequestAttribute("externalSystemId") String systemId) {
        String topic = requireTopic(body);
        String workerId = effectiveWorkerId(systemId, body == null ? null : body.get("workerId"));
        Duration lockDuration = lockDuration(body == null ? null : body.get("lockDurationSeconds"));

        List<AcquiredExternalWorkerJob> acquired = managementService
                .createExternalWorkerJobAcquireBuilder()
                .topic(topic, lockDuration)
                // 本平台只有 BPMN 引擎；把 CMMN 的 external job 排除在外。
                .onlyBpmn()
                .acquireAndLock(1, workerId);

        List<Map<String, Object>> tasks = new ArrayList<>(acquired.size());
        for (AcquiredExternalWorkerJob job : acquired) {
            tasks.add(jobToMap(job, true));
            audit(systemId, "external_worker_acquire", job, null);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tasks", tasks);
        return result;
    }
    // ── 2. Query（唯讀）──────────────────────────────────────────────

    /**
     * 列出某 topic 下本系統可見的 job：尚未鎖定的（佇列存量）＋ 本系統鎖定的。
     *
     * <p>body 省略；topic 以 query string 指定，缺漏 → 400。
     *
     * <p>⚠️ 為什麼是兩次查詢而不是一次：{@code ExternalWorkerJobQuery} 沒有
     * topic 過濾（topic 存在 {@code HANDLER_CFG_} 欄位，查詢 API 不暴露它），
     * 所以 topic 只能在記憶體過濾。而「所有 job 撈出來再過濾」會把其他系統
     * 鎖定的 job 也載進記憶體；拆成 {@code unlocked()}（本系統視角的佇列）
     * 與 {@code lockOwner(本系統)}（本系統持有）兩次查詢，就只會載到
     * 本系統有權看見的列。代價是這個端點在外部 job 總量很大時仍然會
     * 載入所有「未鎖定」的列 —— 已知限制，見類別註解。
     *
     * <p>⚠️ 回應<b>不含</b>流程變數：這個端點是佇列檢視，不是認領，
     * 而其中未鎖定的 job 可能屬於任何系統（見類別註解「topic 是共用佇列」）。
     * 變數只在 acquire 時回傳給實際認領者。
     */
    @GetMapping("/tasks")
    public Map<String, Object> query(@RequestParam(required = false) String topic,
                                     @RequestAttribute("externalSystemId") String systemId) {
        if (topic == null || topic.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "缺少 topic（必須是非空白字串）");
        }
        String workerId = ExternalActorIdentity.of(systemId);

        // 以 jobId 去重：理論上一個 job 不會同時出現在兩邊，但用 Map
        // 合併可以保證即使 Flowable 的鎖狀態出現中間態（owner 非 null、
        // 到期時間 null）也不會重複列出。
        Map<String, ExternalWorkerJob> visible = new LinkedHashMap<>();
        managementService.createExternalWorkerJobQuery()
                .unlocked().orderByJobCreateTime().asc().list().stream()
                .filter(job -> topic.equals(job.getJobHandlerConfiguration()))
                .forEach(job -> visible.put(job.getId(), job));
        managementService.createExternalWorkerJobQuery()
                .lockOwner(workerId).orderByJobCreateTime().asc().list().stream()
                .filter(job -> topic.equals(job.getJobHandlerConfiguration()))
                .forEach(job -> visible.putIfAbsent(job.getId(), job));

        List<Map<String, Object>> tasks = new ArrayList<>(visible.size());
        for (ExternalWorkerJob job : visible.values()) {
            tasks.add(jobToMap(job, false));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tasks", tasks);
        return result;
    }

    // ── 3. Complete（完成）───────────────────────────────────────────

    /**
     * 完成一個本系統鎖定的 job，並把變數寫進流程。
     *
     * <p>body：{@code {"workerId":"...", "variables":{...}}}。
     *
     * <p>⚠️ 完成之後流程<b>不會在同一條執行緒上續行</b>：Flowable 把
     * external job 換成一個 executable async job（handler
     * {@code external-worker-complete}），由 async executor 執行後才推進流程。
     * 正式環境的 async executor 是開著的；測試環境刻意關閉（見
     * application-test.yml），測試以 {@code managementService.executeJob} 手動執行。
     *
     * <p>順序：先確認 job 是本系統鎖定的（404），再檢查變數（400）。
     * 被拒絕的請求零副作用 —— 不寫變數、不動 job、不寫稽核。
     */
    @PostMapping("/tasks/{jobId}/complete")
    @Transactional("primaryTransactionManager")
    public Map<String, Object> complete(@PathVariable String jobId,
                                        @RequestBody(required = false) Map<String, Object> body,
                                        @RequestAttribute("externalSystemId") String systemId) {
        String workerId = effectiveWorkerId(systemId, body == null ? null : body.get("workerId"));
        ExternalWorkerJob job = requireLockedJob(jobId, workerId);
        Map<String, Object> variables = variablesOf(body);

        try {
            var builder = managementService.createExternalWorkerCompletionBuilder(jobId, workerId);
            if (!variables.isEmpty()) builder.variables(variables);
            builder.complete();
        } catch (FlowableObjectNotFoundException | FlowableIllegalArgumentException e) {
            // 預檢之後、builder 之前 job 被同系統的另一個 worker 完成／釋放，
            // 或 async executor 把過期的鎖重設了。與預檢失敗同一個回應，
            // 不因競態而多洩漏任何資訊。
            throw notHeld();
        }

        audit(systemId, "external_worker_complete", job, null);
        return Map.of("jobId", jobId, "status", "completed");
    }

    // ── 4. Fail（失敗）──────────────────────────────────────────────

    /**
     * 回報一個本系統鎖定的 job 失敗。
     *
     * <p>body：{@code {"workerId":"...", "errorCode":"...", "errorMessage":"..."}}。
     *
     * <h2>重試語意（Flowable 7.2 原始碼實測，非推測）</h2>
     *
     * <p>{@code ExternalWorkerJobFailureBuilder} 的 {@code retries} 預設是
     * {@code -1}（{@code ExternalWorkerJobFailureBuilderImpl} 建構子），
     * 表示「沿用 job 目前的 retries 減一」。external worker job 建立時的
     * retries 是引擎設定 {@code asyncExecutorNumberOfRetries}（預設 3）。
     * 因此：
     *
     * <ol>
     *   <li>fail 一次：retries 3 → 2，清除 lockOwner 與到期時間
     *       （未指定 retryTimeout）→ job <b>立刻</b>回到可認領狀態。</li>
     *   <li>第三次 fail：retries 1 → 0 → job 被移到死信佇列
     *       （{@code ACT_RU_DEADLETTER_JOB}），流程停在該節點，需人工處理
     *       （管理 API 或 {@code moveDeadLetterJobToExecutableJob}）。</li>
     * </ol>
     *
     * <p>本 API 不暴露 retries／retryTimeout 覆寫 —— 使用者裁決的 body
     * 只有 errorCode／errorMessage；要改重試政策應由平台統一決定，而不是
     * 每個呼叫端各自設定。
     *
     * <p>⚠️ Flowable 的 builder <b>沒有 errorCode 欄位</b>。errorCode 放進
     * {@code errorDetails}（Flowable 原生的「細節」欄位，可用
     * {@code managementService.getExternalWorkerJobErrorDetails(jobId)} 讀取），
     * errorMessage 放進 {@code errorMessage}。兩者都留在 Flowable 的 job 表，
     * 不進稽核（稽核只記 errorCode，見 {@link #audit}）。
     *
     * <p>回應帶 {@code retriesLeft}：0 代表已進死信、不會再被認領。
     */
    @PostMapping("/tasks/{jobId}/fail")
    @Transactional("primaryTransactionManager")
    public Map<String, Object> fail(@PathVariable String jobId,
                                    @RequestBody(required = false) Map<String, Object> body,
                                    @RequestAttribute("externalSystemId") String systemId) {
        String workerId = effectiveWorkerId(systemId, body == null ? null : body.get("workerId"));
        String errorCode = optionalString(body, "errorCode");
        String errorMessage = optionalString(body, "errorMessage");
        ExternalWorkerJob job = requireLockedJob(jobId, workerId);

        try {
            var builder = managementService.createExternalWorkerJobFailureBuilder(jobId, workerId);
            if (errorMessage != null) builder.errorMessage(errorMessage);
            if (errorCode != null) builder.errorDetails(errorCode);
            builder.fail();
        } catch (FlowableObjectNotFoundException | FlowableIllegalArgumentException e) {
            throw notHeld();
        }

        // fail 之後查一次：還查得到 = 還有重試次數；查不到 = 已進死信。
        ExternalWorkerJob after = managementService.createExternalWorkerJobQuery()
                .jobId(jobId).singleResult();
        int retriesLeft = after == null ? 0 : after.getRetries();

        audit(systemId, "external_worker_fail", job, errorCode);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("jobId", jobId);
        result.put("status", "failed");
        result.put("retriesLeft", retriesLeft);
        return result;
    }

    // ── 5. Unacquire（主動釋放，可選端點）────────────────────────────

    /**
     * 主動釋放本系統鎖定的 job，讓它能被重新認領。
     *
     * <p>body：{@code {"workerId":"..."}}（可省略）。
     *
     * <p>用途：worker 收到關機訊號、或判斷這個 job 不該由自己處理時，
     * 不必等鎖到期（正式環境由 async executor 的 ResetExpiredJobsRunnable
     * 回收，最長可達 reset interval）就能把工作還回去。
     *
     * <p>⚠️ <b>實測 Flowable 7.2：release 不等於立即可被認領。</b>
     * external worker job 預設是 exclusive，acquire 時除了 job 的鎖，還會
     * 經由 {@code lockJobScope → updateProcessInstanceLockTime} 把
     * {@code ACT_RU_EXECUTION.LOCK_TIME_} 設成 job 的到期時間。
     * {@code fail}／{@code complete} 會經由
     * {@code AbstractExternalWorkerJobCmd → UnlockExclusiveJobCmd} 一併清掉
     * 範圍鎖，而 {@code unacquireExternalWorkerJob} 是獨立 command，
     * 只清 job 的鎖、<b>不清範圍鎖</b>。
     * 範圍鎖的更新條件是 {@code LOCK_TIME_ is null OR LOCK_TIME_ < now}，
     * 未到期前任何系統都認領不到（而 acquire 對這種失敗會重試後回空清單，
     * 不是錯誤）。所以本端點是「把工作還回去排隊」，實際可再被認領的時間
     * 仍是原鎖的到期時間。要讓釋放立即生效，BPMN 需把該 serviceTask 設為
     * {@code flowable:exclusive="false"}（跳過 lockJobScope），或改用
     * fail／complete 讓 Flowable 一併解範圍鎖。
     *
     * <p>⚠️ 與 fail 的差別：unacquire 不遞減 retries、不寫錯誤資訊，
     * 是「沒做事，還回去」；fail 是「做了但失敗了」，語意不同。
     */
    @PostMapping("/tasks/{jobId}/unacquire")
    @Transactional("primaryTransactionManager")
    public Map<String, Object> unacquire(@PathVariable String jobId,
                                         @RequestBody(required = false) Map<String, Object> body,
                                         @RequestAttribute("externalSystemId") String systemId) {
        String workerId = effectiveWorkerId(systemId, body == null ? null : body.get("workerId"));
        ExternalWorkerJob job = requireLockedJob(jobId, workerId);

        try {
            managementService.unacquireExternalWorkerJob(jobId, workerId);
        } catch (FlowableException e) {
            // UnacquireExternalWorkerJobCmd 對「查無 job」與「owner 不符」
            // 都丟 FlowableException（不是 ObjectNotFound）—— 統一翻譯成
            // 同一個 404，不讓狀態碼或訊息洩漏他人 job 是否存在。
            throw notHeld();
        }

        audit(systemId, "external_worker_unacquire", job, null);
        return Map.of("jobId", jobId, "status", "unacquired");
    }

    // ── Helpers ──────────────────────────────────────────────────────

    /**
     * 有效 workerId：伺服器鑄造的 {@code system:<systemId>}。
     *
     * <p>body 的 {@code workerId} 只在三種情況被接受：欄位不存在、值是
     * 空白字串、或值等於 {@code system:<id>}（不分大小寫，與
     * {@link ExternalActorIdentity} 的慣例一致）。其他值一律 400 ——
     * 見類別註解「workerId 一律由伺服器鑄造」。
     */
    private static String effectiveWorkerId(String systemId, Object requested) {
        String expected = ExternalActorIdentity.of(systemId);
        if (requested == null) return expected;
        if (!(requested instanceof String s)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "workerId 必須是字串；本平台的有效 workerId 固定是 " + expected);
        }
        if (s.isBlank() || expected.equalsIgnoreCase(s)) return expected;
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "workerId 由伺服器決定（" + expected + "），不可由呼叫端指定");
    }

    /** 取出必填的 topic（非空白字串）。 */
    private static String requireTopic(Map<String, Object> body) {
        Object raw = body == null ? null : body.get("topic");
        if (!(raw instanceof String s) || s.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "缺少 topic（必須是非空白字串）");
        }
        return s;
    }

    /**
     * 解析 lockDurationSeconds（選填，正整數，秒）。
     *
     * <p>不採「寬容地取整」：{@code 1.5} 這種值代表呼叫端對單位有誤解，
     * 靜默截斷只會讓鎖的長度與它以為的不同。缺省 5 分鐘，上限一天。
     */
    private static Duration lockDuration(Object raw) {
        if (raw == null) return Duration.ofSeconds(DEFAULT_LOCK_SECONDS);
        if (!(raw instanceof Number n) || n.doubleValue() != Math.floor(n.doubleValue())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "lockDurationSeconds 必須是整數秒數");
        }
        long seconds = n.longValue();
        if (seconds < 1 || seconds > MAX_LOCK_SECONDS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "lockDurationSeconds 必須介於 1 與 " + MAX_LOCK_SECONDS + " 之間");
        }
        return Duration.ofSeconds(seconds);
    }

    /**
     * 取出 variables（選填，必須是 JSON 物件），並拒絕 {@code _} 保留前綴。
     *
     * <p>見類別註解「變數的保留前綴檢查為什麼在這裡又出現一次」。
     * 訊息與 {@code ExternalApiController} 的同一條規則相同，讓兩個入口的
     * 呼叫端看到一樣的診斷。
     */
    private static Map<String, Object> variablesOf(Map<String, Object> body) {
        Object raw = body == null ? null : body.get("variables");
        if (raw == null) return Map.of();
        if (!(raw instanceof Map)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "variables 必須是 JSON 物件");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> variables = new HashMap<>((Map<String, Object>) raw);
        List<String> reserved = variables.keySet().stream()
                .filter(name -> name != null && name.startsWith("_"))
                .sorted()
                .toList();
        if (!reserved.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "變數名稱不可使用 '_' 前綴（伺服器保留的命名空間）: "
                            + String.join(", ", reserved));
        }
        return variables;
    }

    /** 取出選填的字串欄位；空白視同未提供。 */
    private static String optionalString(Map<String, Object> body, String field) {
        Object raw = body == null ? null : body.get(field);
        if (raw == null) return null;
        if (!(raw instanceof String s)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    field + " 必須是字串");
        }
        return s.isBlank() ? null : s;
    }

    /**
     * 查一個「存在且鎖定給本系統」的 job；否則 404（不存在與他人的 job
     * 共用同一個回應，見類別註解「狀態碼的分工」）。
     */
    private ExternalWorkerJob requireLockedJob(String jobId, String workerId) {
        ExternalWorkerJob job = managementService.createExternalWorkerJobQuery()
                .jobId(jobId).singleResult();
        if (job == null || !workerId.equals(job.getLockOwner())) {
            throw notHeld();
        }
        return job;
    }

    /** 「不存在或未由本系統鎖定」的統一拒絕。 */
    private static ResponseStatusException notHeld() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND,
                "任務不存在或未由本系統鎖定");
    }

    /**
     * 寫一筆外部 API 稽核。
     *
     * <p>⚠️ 只放 jobId／topic／errorCode，<b>不放流程變數</b>（見類別註解）。
     * processInstanceId 放進稽核的結構欄位而不是 detail，讓「這筆操作屬於
     * 哪個案件」能用 SQL 直接查。
     */
    private void audit(String systemId, String action, ExternalWorkerJob job, String errorCode) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("action", action);
        detail.put("jobId", job.getId());
        detail.put("topic", job.getJobHandlerConfiguration());
        if (errorCode != null) detail.put("errorCode", errorCode);

        auditPublisher.publish(new AuditEvent("EXTERNAL_API_CALL",
                ExternalActorIdentity.of(systemId), "external_api", null,
                job.getProcessInstanceId(), null, null, detail, Instant.now()));
    }

    /**
     * job 的可回傳欄位。
     *
     * <p>用 {@link LinkedHashMap} 而非 {@code Map.of}：{@code lockOwner} 與
     * {@code lockExpirationTime} 在未鎖定時是 null，而 {@code Map.of} 不接受
     * null value（會拋 NPE 把 200 蓋成 500）。
     *
     * @param withVariables true = acquire 的回應（帶回流程變數，讓 worker 有
     *                      輸入資料可用）；false = query 的佇列檢視（見 query 的註解）
     */
    private static Map<String, Object> jobToMap(ExternalWorkerJob job, boolean withVariables) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jobId", job.getId());
        m.put("topic", job.getJobHandlerConfiguration());
        m.put("processInstanceId", job.getProcessInstanceId());
        m.put("processDefinitionId", job.getProcessDefinitionId());
        m.put("elementId", job.getElementId());
        m.put("elementName", job.getElementName());
        m.put("retries", job.getRetries());
        m.put("createTime", job.getCreateTime());
        m.put("locked", job.getLockOwner() != null);
        m.put("lockOwner", job.getLockOwner());
        m.put("lockExpirationTime", job.getLockExpirationTime());
        if (withVariables && job instanceof AcquiredExternalWorkerJob acquired) {
            Map<String, Object> variables = acquired.getVariables();
            m.put("variables", variables != null ? variables : Map.of());
        }
        return m;
    }
}
