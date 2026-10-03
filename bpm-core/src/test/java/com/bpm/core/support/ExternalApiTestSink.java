package com.bpm.core.support;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 測試用的外部 API 接收端（#49）。
 *
 * <h2>為什麼需要它</h2>
 *
 * <p>「externalApiDelegate 真的發了請求」不能用「流程沒拋例外」證明 ——
 * 一個根本沒送出請求的實作也會讓流程走完。斷言必須有一個真的 HTTP 端點
 * <b>收到</b>請求，並檢查 method／body 與回應 body 有沒有寫進流程變數。
 *
 * <p>與 {@code WebhookTestSink} 分開是刻意的：那個是 webhook 投遞（HMAC、
 * RabbitMQ 觸發）的 sink，這個是通用外部 API 呼叫的 sink。兩者共用同一條
 * 「測試端點放在 /mock/**、不加 @Import」的結構限制，理由見
 * {@code WebhookTestSink} 類別註解（第二個 Spring context 會搶同一個
 * {@code SERVLET_PORT} 而整組紅掉）。
 *
 * <h2>失敗注入</h2>
 *
 * <p>{@link #fail(String, int)} 讓指定目標回固定狀態碼（例如 503），
 * 用來驗「非 2xx → EXTERNAL_API_FAILED → boundary error 替代路徑」。
 * 與 WebhookTestSink 的失敗注入同一條無狀態設計：設定綁在 name 上，
 * 測試結束呼叫 {@link #reset()} 清掉。
 */
@RestController
@RequestMapping("/mock/test-external-api")
public class ExternalApiTestSink {

    /** 一次收到的呼叫。body 為 null 代表沒有 body。 */
    public record Received(String name, String method, String body) {
    }

    private static final List<Received> RECEIVED = new CopyOnWriteArrayList<>();

    /** name → 注入的回應狀態碼。沒有 entries 的 name 一律回 200。 */
    private static final Map<String, Integer> FAIL_STATUS = new ConcurrentHashMap<>();

    /** 讓 {@code name} 的回應固定為指定狀態碼（0 或 null 等於清除）。 */
    public static void fail(String name, int status) {
        if (status <= 0) {
            FAIL_STATUS.remove(name);
        } else {
            FAIL_STATUS.put(name, status);
        }
    }

    public static void reset() {
        RECEIVED.clear();
        FAIL_STATUS.clear();
    }

    public static List<Received> received() {
        return List.copyOf(RECEIVED);
    }

    public static List<Received> receivedTo(String name) {
        return RECEIVED.stream().filter(r -> r.name().equals(name)).toList();
    }

    private static ResponseEntity<String> respond(String name, String method, String body) {
        RECEIVED.add(new Received(name, method, body));
        Integer failStatus = FAIL_STATUS.get(name);
        if (failStatus != null) {
            return ResponseEntity.status(failStatus).body("{\"injected\":true}");
        }
        return ResponseEntity.status(HttpStatus.OK)
                .body("{\"sink\":\"" + name + "\",\"method\":\"" + method + "\"}");
    }

    @GetMapping("/{name}")
    public ResponseEntity<String> onGet(@PathVariable String name) {
        return respond(name, "GET", null);
    }

    @PostMapping("/{name}")
    public ResponseEntity<String> onPost(@PathVariable String name,
                                         @RequestBody(required = false) String body) {
        return respond(name, "POST", body);
    }

    @PutMapping("/{name}")
    public ResponseEntity<String> onPut(@PathVariable String name,
                                        @RequestBody(required = false) String body) {
        return respond(name, "PUT", body);
    }
}
