package com.bpm.core.support;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 測試用的 webhook 接收端（#67）。
 *
 * <h2>為什麼需要它</h2>
 *
 * <p>「webhook 有接上」這件事<b>不能用「沒有拋例外」來證明</b>。
 * 一個什麼都不做的 listener（改動前的 {@code WebhookTaskListener} 就是），
 * 會讓「發流程 → 建任務 → 簽核 → 結案」整條路徑完全正常，
 * 而投遞從未發生。差別只在於有沒有人真的收到 HTTP 請求。
 *
 * <p>所以這裡提供一個真的 HTTP 端點，斷言「請求抵達了、body 內容是對的、
 * 簽章可以驗過」。這是本工項唯一能區分「接上了」與「看起來接上了」的證據。
 *
 * <h2>⚠️ 為什麼放在 {@code com.bpm.core.support} 而不加 {@code @Import}</h2>
 *
 * <p>{@code IntegrationTestBase.SERVLET_PORT} 是 <b>static final</b>，
 * 也就是整個 JVM 只有一個 port。而 Spring 測試 context 的快取鍵包含
 * {@code @Import}／{@code @TestPropertySource}／{@code @DynamicPropertySource} ——
 * 任何一項不同就是<b>第二個 context</b>，而第二個 context 會去綁同一個
 * port 而啟動失敗（#67 實測：{@code ExtensionElementPreservationTest}
 * 在 {@code WebhookDeliveryWiringTest} 之後執行時整組紅掉，
 * 錯誤是「Failed to load ApplicationContext」）。
 *
 * <p>所以這個類別刻意留在 {@code com.bpm.core} 的 component scan 範圍內、
 * 不加任何額外 annotation。代價是它存在於<b>所有</b>測試 context；
 * 這可以接受：它只寫進一個 static 清單，而只有
 * {@code WebhookDeliveryWiringTest} 會去讀那個清單。
 *
 * <h2>為什麼放在 /mock 底下</h2>
 *
 * <p>{@code SecurityConfig} 對 {@code /mock/**} 是 permitAll（且僅在
 * {@code bpm.external.mock-enabled} 為 true 時註冊，測試 profile 是 true）。
 * 走與 MockOrgController／MockPermController 同一條路徑，
 * 而不是另外開一條測試專用的免認證路徑 —— 後者是有可能誤上 prod 的旁路
 * （application-test.yml 對信任閘道那條路的註解就是講這個）。
 */
@RestController
@RequestMapping("/mock/test-webhook-sink")
public class WebhookTestSink {

    /**
     * 一次收到的投遞。
     *
     * @param name       路徑末段的識別碼，讓同一個測試組可以分辨多個目標
     * @param method     HTTP 方法
     * @param body       原始 body（不做 DTO 反序列化 —— 簽章是對<b>原始 body</b>
     *                   算的，中間過一層 Jackson 會改掉空白與鍵序）
     * @param signature  {@code X-BPM-Signature} 標頭
     * @param deliveryId {@code X-BPM-Delivery-Id} 標頭
     * @param timestamp  {@code X-BPM-Timestamp} 標頭
     */
    public record Received(String name, String method, String body,
                           String signature, String deliveryId, String timestamp) {
    }

    private static final List<Received> RECEIVED = new CopyOnWriteArrayList<>();

    @PostMapping("/{name}")
    public ResponseEntity<Void> onPost(@PathVariable String name,
                                       @RequestBody String body,
                                       @RequestHeader(value = "X-BPM-Signature", required = false) String signature,
                                       @RequestHeader(value = "X-BPM-Delivery-Id", required = false) String deliveryId,
                                       @RequestHeader(value = "X-BPM-Timestamp", required = false) String timestamp) {
        RECEIVED.add(new Received(name, "POST", body, signature, deliveryId, timestamp));
        return ResponseEntity.status(HttpStatus.OK).build();
    }

    @PutMapping("/{name}")
    public ResponseEntity<Void> onPut(@PathVariable String name,
                                      @RequestBody String body,
                                      @RequestHeader(value = "X-BPM-Signature", required = false) String signature,
                                      @RequestHeader(value = "X-BPM-Delivery-Id", required = false) String deliveryId,
                                      @RequestHeader(value = "X-BPM-Timestamp", required = false) String timestamp) {
        RECEIVED.add(new Received(name, "PUT", body, signature, deliveryId, timestamp));
        return ResponseEntity.status(HttpStatus.OK).build();
    }

    public static void reset() {
        RECEIVED.clear();
    }

    public static List<Received> received() {
        return List.copyOf(RECEIVED);
    }

    public static List<Received> receivedTo(String name) {
        return RECEIVED.stream().filter(r -> r.name().equals(name)).toList();
    }
}
