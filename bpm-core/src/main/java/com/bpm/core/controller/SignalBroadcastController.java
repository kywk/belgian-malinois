package com.bpm.core.controller;

import com.bpm.core.audit.AuditEventPublisher;
import com.bpm.core.audit.model.OperationType;
import com.bpm.core.dto.AuditEvent;
import com.bpm.core.security.CallerId;
import org.flowable.engine.RuntimeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Signal Event 廣播（工項 #24，spec §10.1）：一次喚醒所有等待同一個訊號的
 * 流程實例。
 *
 * <p>{@code POST /api/admin/signals/{signalName}/broadcast}，body 可選
 * {@code {"variables": {...}}}。回應
 * {@code {signalName, waiting, variablesApplied}}，其中 {@code waiting} 是
 * 廣播前查到的等待者數，{@code variablesApplied} 是套用到每個被喚醒實例的
 * 變數<b>個數</b>。
 *
 * <h2>為什麼由管理員 API 觸發</h2>
 *
 * <p>廣播會同時改動<b>多個</b>案件的狀態（喚醒等待中的執行、寫入變數），
 * 而且沒有單一 {@code processInstanceId} 可以套個案層級的參與者授權 ——
 * 影響範圍由 signal name 決定，沒有資料層的授權對象。這種操作與 DLQ 重放
 * 同一級：由 {@code SecurityConfig} 既有的 {@code /api/admin/** → ROLE_ADMIN}
 * 規則保護，這裡不新增授權規則（授權矩陣只有一份）。
 *
 * <h2>0 個等待者 → 404（與 callback 一致）</h2>
 *
 * <p>Flowable 的全域廣播在沒有訂閱時是<b>靜默 no-op</b>：
 * {@code SignalEventReceivedCmd} 找不到訂閱就結束，不拋例外
 * （Flowable 7.2.0 原始碼確認）。回 200 會讓呼叫端以為「喚醒了 N 個」，
 * 而實際上什麼都沒發生 —— 這正是最需要被看見的失敗。404 的語意是
 * 「這個廣播的目標不存在」：沒有等待中的訂閱，與
 * {@code CallbackController} 查無訂閱時回 404 一致，呼叫端在兩種觸發方式
 * 下得到同一個可辨識的訊號。
 *
 * <p>多個等待者不是衝突：一對多正是 signal 的用途，因此不學 callback 的
 * 「多個訂閱 → 409」（那是 message 一次只能喚一個的語意）。
 *
 * <h2>⚠️ 已知窗口：count 與廣播之間</h2>
 *
 * <p>{@code waiting} 是廣播<b>前</b>的查詢結果。查詢與廣播之間，實例可能被
 * 取消（訂閱消失）或新增（新的等待者）：
 * <ul>
 *   <li>被取消 → 實際少喚了，但回應仍宣稱 {@code waiting} 個。</li>
 *   <li>新增 → 實際多喚了，回應的 {@code waiting} 低估。</li>
 * </ul>
 * 兩者都無法用「廣播後再查一次」可靠消除（還有並行廣播的競態）。
 * 本端點接受此窗口，並把 {@code waiting} 定義為「廣播前查到的等待者數」。
 *
 * <h2>⚠️ 只喚醒 global scope 的訊號</h2>
 *
 * <p>Flowable 的全域廣播只投遞給 {@code isGlobalScoped()} 的訂閱
 * （{@code SignalEventReceivedCmd} 的註解：process instance scoped signals
 * must be thrown within the process itself）。訊號在 definitions 層級且未標記
 * {@code flowable:scope} 時是 global；標記
 * {@code flowable:scope="processInstance"} 的訊號不會被本端點喚醒。
 * {@code ExecutionQuery.signalEventSubscriptionName} 不過濾 scope，
 * 因此若有實例正等待 process-scoped 訊號，{@code waiting} 會高於實際可喚醒數。
 * 平台的 BPMN 規範是訊號一律宣告於 definitions 層級。
 *
 * <h2>稽核：fail-closed，且不記變數值</h2>
 *
 * <p>用 {@code publish}（掛在交易 beforeCommit）而不是
 * {@code publishDetached}：廣播與稽核在同一個
 * {@code primaryTransactionManager} 交易裡，稽核寫入失敗 → 交易回滾 →
 * 實例仍在等待，呼叫端拿到 503 後可以安全重試。這與 DLQ 重放不同
 * （那裡 broker 已 ack，回滾不了），所以不沿用 detached。
 *
 * <p>detail 只放 {@code action／signalName／waiting／variables}（個數），
 * <b>不放變數值</b>：變數會寫進每一個被喚醒的實例，可能含業務資料，
 * 而稽核庫的讀取權與案件內容的讀取權不是同一件事（同 callback 的決策）。
 *
 * <h2>形狀驗證排在等待查詢之前</h2>
 *
 * <p>與 callback 相同：先 400 再 404。請求形狀錯誤、目標也不存在時，
 * 呼叫端應該先修 payload —— 先回 404 會讓人以為「目標不存在」，
 * 改了目標還是 400。
 */
@RestController
@RequestMapping("/api/admin/signals")
public class SignalBroadcastController {

    private static final Logger log = LoggerFactory.getLogger(SignalBroadcastController.class);

    /**
     * signal name 長度上限。
     *
     * <p>Flowable 的 {@code ACT_RU_EVENT_SUBSCR.EVENT_NAME_} 是
     * {@code NVARCHAR(255)}（7.2.0 的 mssql create 腳本），比它長的名字
     * 不可能有等待中的訂閱 —— 直接 400，而不是讓呼叫端拿到一個誤導的 404。
     */
    static final int SIGNAL_NAME_MAX_LENGTH = 255;

    private final RuntimeService runtimeService;
    private final AuditEventPublisher auditPublisher;

    public SignalBroadcastController(RuntimeService runtimeService, AuditEventPublisher auditPublisher) {
        this.runtimeService = runtimeService;
        this.auditPublisher = auditPublisher;
    }

    /**
     * 廣播訊號給所有等待中的實例。
     *
     * @param signalName BPMN definitions 層級宣告的 signal name（路徑變數）
     * @param body       可選；{@code {"variables": {...}}} 會套用到每個被喚醒的實例
     * @param operatorId 已認證的呼叫者，寫入 {@code SIGNAL_BROADCAST} 稽核
     * @throws ResponseStatusException 400（形狀）／404（沒有等待中的訂閱）／
     *                                 401（無法確認身分，防禦性；正常由 SecurityConfig 擋下）
     */
    @PostMapping("/{signalName}/broadcast")
    @Transactional("primaryTransactionManager")
    public Map<String, Object> broadcast(@PathVariable String signalName,
                                         @RequestBody(required = false) Map<String, Object> body,
                                         @CallerId String operatorId) {
        // 與 SubstituteForwardController 一致：稽核不記一個 null 的 operator。
        // 正常情況下 /api/admin/** 的 ROLE_ADMIN 規則已擋掉未認證流量。
        if (operatorId == null || operatorId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "無法確認身分，請先登入");
        }

        // 形狀（400）先於存在性（404），順序理由見類別註解。
        String name = validateSignalName(signalName);
        Map<String, Object> variables = parseVariables(body);

        // 等待中的訂閱：ExecutionQuery 的 eventSubscription("signal", name)，
        // 等同 SignalEventReceivedCmd 找訂閱的條件（但不過濾 scope，見類別註解）。
        long waiting = runtimeService.createExecutionQuery()
                .signalEventSubscriptionName(name)
                .count();
        if (waiting == 0) {
            log.warn("訊號廣播找不到等待中的訂閱 signal={} operator={}", name, operatorId);
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "沒有等待中的訊號訂閱: " + name);
        }

        // 全域廣播：變數 payload 會由 EventSubscriptionUtil 套用到每一個
        // 被喚醒的 execution。沒有訂閱時是 no-op，但上面已擋掉 0 的情況。
        runtimeService.signalEventReceived(name, variables);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("action", "broadcast");
        detail.put("signalName", name);
        detail.put("waiting", waiting);
        detail.put("variables", variables.size());
        auditPublisher.publish(new AuditEvent(
                OperationType.SIGNAL_BROADCAST.name(),
                operatorId, null, null, detail));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("signalName", name);
        response.put("waiting", waiting);
        response.put("variablesApplied", variables.size());
        return response;
    }

    /**
     * signal name 的形狀驗證：非空、不超過 255 字元、不含控制字元。
     *
     * <p>空白只拒絕<b>全空白</b>與控制字元；名字中間的空白是合法的 BPMN
     * signal name，不因為它在路徑變數裡就禁止（那是 URL 編碼的事，
     * 不是訊號語意的事）。
     *
     * <p>package-private 是刻意的：控制字元分支無法從 HTTP 測
     * （{@code StrictHttpFirewall} 會在請求進到 controller 前就擋掉編碼的
     * 控制字元），測試直接呼叫這個方法才能釘住那一行。
     */
    static String validateSignalName(String signalName) {
        if (signalName == null || signalName.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "signalName 不可為空");
        }
        if (signalName.length() > SIGNAL_NAME_MAX_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "signalName 過長（上限 " + SIGNAL_NAME_MAX_LENGTH + " 字元）");
        }
        for (int i = 0; i < signalName.length(); i++) {
            if (Character.isISOControl(signalName.charAt(i))) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "signalName 含控制字元");
            }
        }
        return signalName;
    }

    /**
     * {@code variables} 必須是物件（變數名稱 → 值）；缺欄位或 null 視為空。
     *
     * <p>⚠️ 這 6 行與 {@code CallbackController.parseVariables} 是同一份契約的
     * 兩份實作（該方法為 private，且 callback 不在本工項的檔案邊界內）。
     * 契約改動時必須兩處同步，否則「外部回呼收的 variables」與
     * 「管理廣播收的 variables」會出現兩種形狀。
     */
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
