package com.bpm.core.http;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.time.Duration;

/**
 * 對外 HTTP 客戶端的共用建立規則（#97）。
 *
 * <h2>🔴 不跟隨 3xx：SSRF 閘門的第二半</h2>
 *
 * <p>{@code WebhookUrlPolicy} 只檢查<b>原始 URL</b> 的 host —— 它是本 repo
 * 唯一一份 SSRF 閘門，職責是「這個位址能不能去」。但 {@code HttpURLConnection}
 * 預設會跟隨 3xx，於是一個通過政策的主機可以用
 * {@code Location: http://127.0.0.1:8080/...} 讓伺服器自己走到 loopback／內網
 * —— 閘門被繞過（#56 實作時揭露；當時 webhook 與外部 API delegate 仍是缺口）。
 *
 * <p>這裡把 {@code setInstanceFollowRedirects(false)} 設在
 * {@code prepareConnection()}（連線前的最後一個鉤子）。3xx 之後原樣回給
 * 呼叫端，由各客戶端「非 2xx 即失敗」的規則處理：外部 API delegate →
 * {@code EXTERNAL_API_FAILED}、動態選項 → 502、webhook → 拋出（重試 → DLQ）。
 *
 * <p>⚠️ <b>為什麼還要覆寫</b>：Spring 的 {@code SimpleClientHttpRequestFactory}
 * 只對 {@code GET} 設 {@code follow=true}，POST／PUT／PATCH／DELETE 本來就是
 * {@code false}（6.2.x 原始碼）。所以缺口實際落在 GET 客戶端 —— delegate 的
 * 預設 method 與動態選項都是 GET；webhook（POST／PUT）只是「剛好安全」。
 * 這裡一律覆寫成 {@code false}，讓規則與 HTTP method 無關、也與 Spring
 * 版本或預設值的變動無關。
 *
 * <h2>為什麼集中在這裡</h2>
 *
 * <p>「跟隨重導＝繞過 SSRF 閘門」這條規則若在每個客戶端各抄一份，
 * 任何一個新客戶端漏抄就回到同一個缺口。目前三個客戶端
 * （webhook／外部 API delegate／動態選項）都從 {@link #create(long, long)}
 * 建立；新客戶端也必須走這裡 —— <b>規則只有一份</b>。
 *
 * <h2>逾時不可省略</h2>
 *
 * <p>{@code RestClient.create()} 沒有逾時：掛住的 endpoint 會佔住執行緒直到
 * TCP 逾時。webhook listener 的 concurrency 預設是 1，一個壞掉的接收端就能
 * 阻塞整條佇列（security-audit P2-1）；delegate 則會卡住引擎執行緒。
 * connect／read timeout 因此是這個方法的必填參數。
 */
public final class SafeRestClients {

    private SafeRestClients() {
    }

    /**
     * 建立不跟隨 3xx、具備連線與讀取逾時的 {@link RestClient}。
     *
     * @param connectTimeoutMs TCP 連線逾時（毫秒）
     * @param readTimeoutMs    讀取逾時（毫秒）
     */
    public static RestClient create(long connectTimeoutMs, long readTimeoutMs) {
        var requestFactory = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(HttpURLConnection connection, String httpMethod)
                    throws IOException {
                super.prepareConnection(connection, httpMethod);
                // 必須在連線前設定；prepareConnection 是 SimpleClientHttpRequestFactory
                // 保證「還沒 connect」的鉤子。
                connection.setInstanceFollowRedirects(false);
            }
        };
        requestFactory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        requestFactory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        return RestClient.builder().requestFactory(requestFactory).build();
    }
}
