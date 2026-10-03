package com.bpm.core.external;

import com.bpm.core.audit.AuditEventPublisher;
import com.bpm.core.dto.AuditEvent;
import org.flowable.common.engine.api.FlowableObjectNotFoundException;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.runtime.Execution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 外部系統的回呼接收端（工項 #21，spec §10.2）。
 *
 * <p>{@code POST /api/callback/{type}}：{@code type} 是 Flowable 的 message name
 * （例如 {@code esign-completed}）。body：
 * {@code {"processInstanceId": "...", "variables": {...}, "deliveryId": "..."}}。
 *
 * <h2>處理順序（每一步的狀態碼都有理由）</h2>
 *
 * <ol>
 *   <li><b>認證（401）</b>與 <b>allowedActions（403）</b>已在
 *       {@link CallbackAuthFilter} 完成 —— 未認證的流量不該進 controller。</li>
 *   <li><b>形狀（400）</b>：缺 {@code processInstanceId}／{@code deliveryId}
 *       或 {@code variables} 不是物件。</li>
 *   <li><b>冪等</b>：Redis {@code SetIfAbsent}（key 含 systemId＋deliveryId）。
 *       重複 → 200 {@code {"status":"duplicate"}}，不再往下。</li>
 *   <li><b>correlation（404）</b>：查無等待中的訂閱 → 404。</li>
 * </ol>
 *
 * <p>為什麼形狀要排在冪等<b>之前</b>：呼叫端必須能分辨「改 payload」與
 * 「重試」。同一個 deliveryId 若第一次送錯形狀、第二次補齊再送，
 * 先查冪等會讓它永遠拿到 duplicate 而不知道第一次根本沒被處理。
 *
 * <p>為什麼冪等排在 correlation 之前：重複回呼不該再碰流程引擎；
 * 而且第一次成功後訂閱已被消耗，若先查 correlation 會回 404 ——
 * 對呼叫端而言「重試成功過的請求得到 404」比 duplicate 更難理解。
 *
 * <h2>⚠️ Redis 故障時 fail-closed（503），不 fail-open</h2>
 *
 * <p>催辦的冷卻鎖是 fail-open（放行只會多寄一封信），但冪等鎖相反：
 * 放行代表重複的 deliveryId 會被<b>再處理一次</b>。就算 correlation 本身
 * 會因為訂閱已消耗而失敗，呼叫端拿到的也是誤導的 404，而且每一次 Redis
 * 中斷期間的重試都真的打進流程引擎。回 503 讓呼叫端稍後重試，代價是可見
 * 且可恢復的。
 *
 * <h2>⚠️ 失敗時釋放冪等鍵</h2>
 *
 * <p>鍵是「先佔再處理」。若 correlation 回 404 或稽核在 commit 時失敗
 * （{@code AuditEventPublisher} 是 fail-closed），交易會回滾、流程沒有被喚醒，
 * 但鍵已經佔住 —— 呼叫端在 TTL 內重試只會拿到 duplicate，回呼就永久遺失。
 * 因此註冊一個 {@code afterCompletion}：交易沒 commit 就刪鍵。刪除失敗
 * 只寫 WARN（鍵會在 TTL 後過期，屆時重試可重新處理；代價是那段窗口內
 * 的 retry 會拿到 duplicate）。
 *
 * <p>⚠️ <b>仍存在的窗口</b>：行程在 {@code setIfAbsent} 之後、交易 commit
 * 之前被硬殺（crash／OOM），{@code afterCompletion} 不會執行，鍵會留到
 * 24 小時 TTL 過期 —— 那段時間內的重試會拿到 duplicate，但流程從未被喚醒。
 * 完全消除需要兩階段鍵（先短 TTL 佔位、成功後延長），本工項選擇規格描述的
 * 簡單版並留下此已知窗口：硬殺的機率遠低於 404／稽核失敗這類會走
 * {@code afterCompletion} 的路徑。
 *
 * <h2>稽核</h2>
 *
 * <p>成功（第一次通過冪等的 delivery）寫一筆 {@code EXTERNAL_API_CALL}，
 * action={@code callback}、type、pid、deliveryId。duplicate 與被拒
 * （400／404／401／403）都不寫 —— 前者沒有新事實，後者只寫 log。
 * detail 刻意<b>不含 variables</b>：那可能是薪資／簽核內容，而稽核庫
 * 的讀取權與案件內容的讀取權不是同一件事（同 P2-4 的稽核設計）。
 */
@RestController
@RequestMapping("/api/callback")
public class CallbackController {

    private static final Logger log = LoggerFactory.getLogger(CallbackController.class);

    /** 冪等鍵前綴。含 systemId 與 deliveryId，兩個不同系統可以各自重用同一組 deliveryId。 */
    static final String IDEMPOTENCY_KEY_PREFIX = "bpm:callback:";

    /** 冪等窗口：與回呼時間戳的 ±5 分鐘無關，這裡回答的是「同一個 delivery 重送」。 */
    static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);

    private final RuntimeService runtimeService;
    private final StringRedisTemplate redis;
    private final AuditEventPublisher auditPublisher;

    public CallbackController(RuntimeService runtimeService, StringRedisTemplate redis,
                              AuditEventPublisher auditPublisher) {
        this.runtimeService = runtimeService;
        this.redis = redis;
        this.auditPublisher = auditPublisher;
    }

    @PostMapping("/{type}")
    @Transactional("primaryTransactionManager")
    public Map<String, Object> receive(@PathVariable String type,
                                       @RequestBody(required = false) Map<String, Object> body,
                                       @RequestAttribute("externalSystemId") String systemId) {
        // ── 形狀（400）────────────────────────────────────────────
        String processInstanceId;
        String deliveryId;
        Map<String, Object> variables;
        try {
            processInstanceId = requireString(body, "processInstanceId");
            deliveryId = requireString(body, "deliveryId");
            variables = parseVariables(body);
        } catch (ResponseStatusException e) {
            // 被拒只寫 log（工項 #21 的決策）：不寫成功稽核。
            log.warn("回呼形狀被拒 systemId={} type={}: {}", systemId, type, e.getReason());
            throw e;
        }

        // ── 冪等（fail-closed）─────────────────────────────────────
        String key = IDEMPOTENCY_KEY_PREFIX + systemId + ":" + deliveryId;
        if (!acquireIdempotencyKey(key)) {
            // 重複的 delivery：不碰引擎、不寫稽核。回 200 讓呼叫端的重試收斂。
            return Map.of("status", "duplicate");
        }
        releaseOnRollback(key);

        // ── Correlation（404）──────────────────────────────────────
        //
        // ⚠️ Flowable 7 已移除 createMessageCorrelationBuilder（6.x 的 API）。
        // 等價動作是「先查等待中的 execution，再 messageEventReceived」——
        // 兩者都只在該 processInstanceId 上比對 message name，語意相同。
        List<Execution> waiting = runtimeService.createExecutionQuery()
                .processInstanceId(processInstanceId)
                .messageEventSubscriptionName(type)
                .list();
        if (waiting.isEmpty()) {
            log.warn("回呼找不到等待中的訂閱 systemId={} type={} pid={} deliveryId={}",
                    systemId, type, processInstanceId, deliveryId);
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "沒有等待中的流程訂閱訊息: " + type + "（processInstanceId=" + processInstanceId + "）");
        }
        if (waiting.size() > 1) {
            // 平行分支同時等待同一個訊息：任意挑一個都是錯的，而且會靜默丟掉另一邊。
            log.warn("回呼有多個等待中的訂閱，無法決定喚醒哪一個 systemId={} type={} pid={} count={}",
                    systemId, type, processInstanceId, waiting.size());
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "有多個等待中的訂閱，無法決定要喚醒哪一個: " + type);
        }

        try {
            runtimeService.messageEventReceived(type, waiting.get(0).getId(), variables);
        } catch (FlowableObjectNotFoundException e) {
            // 查詢與喚醒之間實例被取消的競爭窗口。轉成 404，而不是裸 500。
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "沒有等待中的流程訂閱訊息: " + type, e);
        }

        auditPublisher.publish(new AuditEvent("EXTERNAL_API_CALL",
                ExternalActorIdentity.of(systemId),
                "external_api", null, processInstanceId, null, null,
                Map.of("action", "callback", "type", type, "deliveryId", deliveryId),
                Instant.now()));

        return Map.of("status", "ok", "processInstanceId", processInstanceId);
    }

    /**
     * 取得冪等許可。Redis 故障 → 503（fail-closed，見類別註解）。
     *
     * @return {@code false} 代表這個 deliveryId 已經處理過（或正在處理）
     */
    private boolean acquireIdempotencyKey(String key) {
        try {
            Boolean acquired = redis.opsForValue().setIfAbsent(key, "1", IDEMPOTENCY_TTL);
            if (acquired == null) {
                // 非 pipeline 模式下不該發生；視為故障而不是「已存在」或「已取得」。
                throw new IllegalStateException("setIfAbsent 回傳 null");
            }
            return acquired;
        } catch (Exception e) {
            log.error("回呼冪等檢查無法取得 Redis 許可，拒絕本次回呼（key={}）: {}",
                    key, e.toString());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "冪等檢查暫時無法使用，請稍後重試");
        }
    }

    /**
     * 交易沒有 commit 就釋放冪等鍵（見類別註解「失敗時釋放冪等鍵」）。
     *
     * <p>沒有進行中的交易時（例如單元測試直接呼叫）不註冊 —— 那種情況下
     * 也沒有 rollback 可言，鍵留著過期即可。
     */
    private void releaseOnRollback(String key) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == TransactionSynchronization.STATUS_COMMITTED) {
                    return;
                }
                try {
                    redis.delete(key);
                } catch (Exception e) {
                    log.warn("回呼冪等鍵釋放失敗（交易已回滾，key={}）: {}", key, e.toString());
                }
            }
        });
    }

    private static String requireString(Map<String, Object> body, String field) {
        Object value = body == null ? null : body.get(field);
        if (!(value instanceof String s) || s.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "缺少必填欄位 " + field + "（非空字串）");
        }
        return s;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseVariables(Map<String, Object> body) {
        Object raw = body == null ? null : body.get("variables");
        if (raw == null) return new HashMap<>();
        if (!(raw instanceof Map)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "variables 必須是物件（變數名稱 → 值）");
        }
        return new HashMap<>((Map<String, Object>) raw);
    }
}
