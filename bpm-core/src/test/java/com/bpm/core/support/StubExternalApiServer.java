package com.bpm.core.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

/**
 * 外部系統契約測試用的替身（#8／#9）：真 HTTP server，綁在臨時埠。
 *
 * <h2>為什麼不能用 Spring 的 {@code MockOrgController}</h2>
 *
 * <p>契約測試要驗的是 <b>client 自己</b>：它送出的路徑／query／header，
 * 以及它如何解析回應、如何映射失敗。用應用 context 裡的 mock controller
 * 會把「client 對不對」與「Spring context 起不起來」綁在一起 ——
 * 而且 {@code IntegrationTestBase} 的 DEFINED_PORT 只能有一個 context。
 * 這裡直接建構 client、對一個真的 socket 說話，測的是真正的 HTTP 行為
 * （header 有沒有送出去、逾時有沒有生效），不是 mock 掉的方法呼叫。
 *
 * <h2>無狀態設計</h2>
 *
 * <p>stub 綁在 method＋path 上，測試自己設定、自己清理（每個測試一個
 * server 實例）。與 {@code ExternalApiTestSink} 的失敗注入同一條原則：
 * 有狀態的全域開關一旦有一個測試失敗就會污染後續測試。
 */
public final class StubExternalApiServer implements AutoCloseable {

    /** 一次收到的請求。header 已複製成不可變的 map。 */
    public record Received(String method, String path, String rawQuery,
                           Map<String, List<String>> headers) {

        /** 大小寫不敏感的 header 查詢；不存在回 {@code null}。 */
        public String header(String name) {
            return headers.entrySet().stream()
                    .filter(e -> e.getKey().equalsIgnoreCase(name))
                    .map(e -> e.getValue().isEmpty() ? null : e.getValue().get(0))
                    .findFirst()
                    .orElse(null);
        }

        /** 是否存在指定 header（含值為空字串的情形）。 */
        public boolean hasHeader(String name) {
            return headers.keySet().stream().anyMatch(k -> k.equalsIgnoreCase(name));
        }

        /**
         * 解碼後的 query 參數值；不存在回 {@code null}。
         *
         * <p>刻意解碼而不是直接比對 {@code rawQuery}：query 的編碼（例如
         * 冒號變 {@code %3A}）是傳輸層細節，不是契約；契約是「另一端解碼後
         * 拿到什麼」。直接比對 raw 字串會把測試綁死在 Spring 的編碼實作上。
         */
        public String queryParam(String name) {
            if (rawQuery == null) return null;
            for (String pair : rawQuery.split("&")) {
                int eq = pair.indexOf('=');
                if (eq < 0 || !pair.substring(0, eq).equals(name)) continue;
                return java.net.URLDecoder.decode(pair.substring(eq + 1),
                        java.nio.charset.StandardCharsets.UTF_8);
            }
            return null;
        }
    }

    private record Stub(int status, String body, long delayMs) {
    }

    private final HttpServer server;
    private final Map<String, Stub> stubs = new ConcurrentHashMap<>();
    private final List<Received> received = new CopyOnWriteArrayList<>();

    public StubExternalApiServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException("測試用的外部系統替身起不來", e);
        }
        server.createContext("/", this::handle);
        // 每個請求一個執行緒：slow stub（逾時測試）在睡的時候，
        // 其他請求仍能進來；也讓 server.stop 不會被睡眠卡住。
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
    }

    private void handle(HttpExchange exchange) throws IOException {
        var uri = exchange.getRequestURI();
        Map<String, List<String>> headers = new LinkedHashMap<>();
        exchange.getRequestHeaders().forEach((k, v) -> headers.put(k, List.copyOf(v)));
        received.add(new Received(exchange.getRequestMethod(), uri.getPath(), uri.getRawQuery(),
                Map.copyOf(headers)));

        Stub stub = stubs.get(key(exchange.getRequestMethod(), uri.getPath()));
        if (stub == null) {
            // 沒設 stub 就是 404 —— 測試端打錯路徑時會拿到與外部系統
            // 「查無此資源」相同的形狀，而收到的請求紀錄仍可指出實際路徑。
            respond(exchange, 404, "{\"error\":\"no stub\"}");
            return;
        }
        if (stub.delayMs() > 0) {
            try {
                Thread.sleep(stub.delayMs());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        respond(exchange, stub.status(), stub.body());
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
        exchange.close();
    }

    private static String key(String method, String path) {
        return method + " " + path;
    }

    /** 例如 {@code http://127.0.0.1:54321}（不含路徑）。 */
    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** 讓 method＋path 的回應固定為指定狀態碼與 body。 */
    public void respond(String method, String path, int status, String body) {
        stubs.put(key(method, path), new Stub(status, body, 0));
    }

    /** 同上，但先睡 {@code delayMs} 毫秒（用來觸發 client 的 read timeout）。 */
    public void respondSlowly(String method, String path, int status, String body, long delayMs) {
        stubs.put(key(method, path), new Stub(status, body, delayMs));
    }

    /** 依到達順序回傳所有請求（斷言 header／query／path 用）。 */
    public List<Received> received() {
        return List.copyOf(received);
    }

    public Received lastReceived() {
        List<Received> all = received();
        if (all.isEmpty()) throw new AssertionError("測試的 HTTP server 沒有收到任何請求");
        return all.getLast();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
