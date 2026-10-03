package com.bpm.core.client;

import com.bpm.core.service.OrgService;
import com.bpm.core.support.StubExternalApiServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.web.client.RestClientException;

import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * {@link OrgRestClient} 的契約測試（#8 正式化）：真 HTTP server、直接建構 client。
 *
 * <h2>測什麼</h2>
 *
 * <ol>
 *   <li><b>端點契約</b>（spec §5.1）：六個端點的路徑、query 與回應解析。
 *       這些是與真實組織系統的唯一介面，寫錯不會有任何編譯錯誤 ——
 *       只會在第一次查詢時 404，而錯誤訊息在服務層看起來像「查無此人」。</li>
 *   <li><b>認證注入</b>：token 設／未設時 header 的有無與原樣傳遞。
 *       未設＝完全不送，這是「dev mock 行為逐位元不變」的 HTTP 層證據。</li>
 *   <li><b>失敗映射</b>：非 2xx 與連線／逾時都必須是
 *       {@link ExternalApiException}（服務名＋狀態碼＋路徑），且不帶 body；
 *       同時仍是 {@link RestClientException}，既有 catch 語意不變。</li>
 *   <li><b>服務層語意不變</b>：404 是 fail-closed（拋例外），
 *       鏈頂的 200 + {@code {}} 才是 null；快取寫入行為與改動前相同。</li>
 * </ol>
 *
 * <h2>⚠️ 為什麼「不送 header」與「不複製 body」要真的用 HTTP 驗</h2>
 *
 * <p>這兩件事都是<b>沒有發生的事情</b>，而「沒有發生」最容易在改動後
 * 靜默反轉。用真的 socket 從另一端看：header 不在請求裡、
 * body 不在例外訊息裡 —— 這是唯一能區分「真的沒送」與「以為沒送」的證據。
 */
class OrgRestClientContractTest {

    private StubExternalApiServer server;

    @BeforeEach
    void setUp() {
        server = new StubExternalApiServer();
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    private OrgRestClient client() {
        return client("", "Authorization");
    }

    private OrgRestClient client(String token, String headerName) {
        return new OrgRestClient(server.baseUrl(), 2000, 3000, headerName, token);
    }

    // ── 端點契約（spec §5.1）────────────────────────────────────────

    @Nested
    @DisplayName("端點契約：路徑、query 與回應解析")
    class Endpoints {

        @Test
        @DisplayName("GET /api/users/{id} → 人員資料")
        void getUser() {
            server.respond("GET", "/api/users/user001", 200,
                    "{\"userId\":\"user001\",\"name\":\"王小明\",\"deptId\":\"dept001\"}");

            var user = client().getUser("user001");

            assertThat(user).containsEntry("userId", "user001").containsEntry("deptId", "dept001");
            assertThat(server.lastReceived().method()).isEqualTo("GET");
            assertThat(server.lastReceived().path()).isEqualTo("/api/users/user001");
        }

        @Test
        @DisplayName("GET /api/users/{id}/manager → {managerId}")
        void getManager() {
            server.respond("GET", "/api/users/user001/manager", 200, "{\"managerId\":\"mgr001\"}");

            assertThat(client().getManager("user001")).isEqualTo("mgr001");
            assertThat(server.lastReceived().path()).isEqualTo("/api/users/user001/manager");
        }

        @Test
        @DisplayName("manager 回 {}（此人存在但位於鏈頂）→ null，不是查無此人")
        void chainTopManagerIsNull() {
            server.respond("GET", "/api/users/dir001/manager", 200, "{}");

            assertThat(client().getManager("dir001"))
                    .as("回 null 是「沒有主管」的事實；把它當查無此人會擋掉總監本人")
                    .isNull();
        }

        @Test
        @DisplayName("GET /api/users/{id}/manager-chain?levels=N → 主管 id 陣列（由近而遠）")
        void getManagerChain() {
            server.respond("GET", "/api/users/user001/manager-chain", 200,
                    "[\"mgr001\",\"dir001\"]");

            assertThat(client().getManagerChain("user001", 5)).containsExactly("mgr001", "dir001");
            assertThat(server.lastReceived().path()).isEqualTo("/api/users/user001/manager-chain");
            assertThat(server.lastReceived().rawQuery())
                    .as("levels 是 query 參數；寫成 path 的一部分會讓真實系統忽略它")
                    .isEqualTo("levels=5");
        }

        @Test
        @DisplayName("GET /api/users/{id}/department → {deptId}")
        void getDepartment() {
            server.respond("GET", "/api/users/user001/department", 200, "{\"deptId\":\"dept001\"}");

            assertThat(client().getDepartment("user001")).isEqualTo("dept001");
            assertThat(server.lastReceived().path()).isEqualTo("/api/users/user001/department");
        }

        @Test
        @DisplayName("GET /api/users/{id}/substitute → {substituteId}；無代理人時欄位缺席")
        void getSubstitute() {
            server.respond("GET", "/api/users/user001/substitute", 200, "{\"substituteId\":\"sub001\"}");
            assertThat(client().getSubstitute("user001")).isEqualTo("sub001");

            server.respond("GET", "/api/users/user002/substitute", 200, "{}");
            assertThat(client().getSubstitute("user002"))
                    .as("沒有代理人時不得捏造任何 id")
                    .isNull();
            assertThat(server.lastReceived().path()).isEqualTo("/api/users/user002/substitute");
        }

        @Test
        @DisplayName("GET /api/departments/{deptId}/members → 使用者 id 陣列")
        void getDeptMembers() {
            server.respond("GET", "/api/departments/dept001/members", 200,
                    "[\"user001\",\"user002\",\"user003\"]");

            assertThat(client().getDeptMembers("dept001"))
                    .containsExactly("user001", "user002", "user003");
            assertThat(server.lastReceived().path()).isEqualTo("/api/departments/dept001/members");
        }
    }

    // ── 認證注入 ───────────────────────────────────────────────────

    @Nested
    @DisplayName("認證注入：設定值原樣進指定 header")
    class Auth {

        @Test
        @DisplayName("token 設 → Authorization 原樣帶出（Bearer 形式不重組）")
        void bearerTokenIsSentVerbatim() {
            server.respond("GET", "/api/users/user001/manager", 200, "{\"managerId\":\"mgr001\"}");

            client("Bearer org-secret-token", "Authorization").getManager("user001");

            assertThat(server.lastReceived().header("Authorization"))
                    .as("服務不解析也不加前綴；真實系統要什麼形式就填什麼形式")
                    .isEqualTo("Bearer org-secret-token");
        }

        @Test
        @DisplayName("Sa-Token 形式同樣原樣送出")
        void satokenIsSentVerbatim() {
            server.respond("GET", "/api/users/user001/manager", 200, "{\"managerId\":\"mgr001\"}");

            client("satoken abc123", "Authorization").getManager("user001");

            assertThat(server.lastReceived().header("Authorization")).isEqualTo("satoken abc123");
        }

        @Test
        @DisplayName("auth-header 自訂 → 只送自訂名稱，不送 Authorization")
        void customHeaderName() {
            server.respond("GET", "/api/users/user001/manager", 200, "{\"managerId\":\"mgr001\"}");

            client("org-token-value", "X-Org-Token").getManager("user001");

            assertThat(server.lastReceived().header("X-Org-Token")).isEqualTo("org-token-value");
            assertThat(server.lastReceived().hasHeader("Authorization"))
                    .as("真實系統用非 Authorization 的 header 時，不得兩者都送")
                    .isFalse();
        }

        @Test
        @DisplayName("token 未設（空字串）→ 完全不送 auth header")
        void emptyTokenSendsNoHeader() {
            server.respond("GET", "/api/users/user001/manager", 200, "{\"managerId\":\"mgr001\"}");

            client("", "Authorization").getManager("user001");

            assertThat(server.lastReceived().hasHeader("Authorization"))
                    .as("dev mock 不驗證身分；多送一個空 header 是行為差異")
                    .isFalse();
        }
    }

    // ── 失敗映射 ───────────────────────────────────────────────────

    @Nested
    @DisplayName("失敗映射：一致型別、可分辨、不外洩")
    class Failures {

        @Test
        @DisplayName("404 → ExternalApiException（isNotFound），且不複製 response body")
        void notFoundIsMapped() {
            server.respond("GET", "/api/users/nobody/manager", 404,
                    "{\"secret\":\"SECRET-404-BODY\"}");

            Throwable thrown = catchThrowable(() -> client().getManager("nobody"));

            assertThat(thrown)
                    .as("既有的 catch (RestClientException) 仍必須接得住")
                    .isInstanceOf(RestClientException.class)
                    .isInstanceOf(ExternalApiException.class);
            var e = (ExternalApiException) thrown;
            assertThat(e.service()).isEqualTo(ExternalApiException.Service.ORG);
            assertThat(e.kind()).isEqualTo(ExternalApiException.Kind.HTTP_STATUS);
            assertThat(e.status().value()).isEqualTo(404);
            assertThat(e.path()).isEqualTo("/api/users/nobody/manager");
            assertThat(e.isNotFound()).isTrue();
            assertThat(e.getMessage())
                    .as("外部系統的錯誤內容可能含內部細節，紅線是不外洩")
                    .doesNotContain("SECRET-404-BODY");
            assertThat(e.getCause())
                    .as("HTTP 失敗路徑不保留任何能讀到 response body 的參照")
                    .isNull();
        }

        @Test
        @DisplayName("5xx → ExternalApiException（狀態碼原樣保留、不是 404）")
        void serverErrorIsMapped() {
            server.respond("GET", "/api/users/user001/manager", 503,
                    "{\"secret\":\"SECRET-503-BODY\"}");

            Throwable thrown = catchThrowable(() -> client().getManager("user001"));

            var e = (ExternalApiException) thrown;
            assertThat(e.status().value()).isEqualTo(503);
            assertThat(e.isNotFound())
                    .as("5xx 是故障（503 重試），不是查無此人（400 改 payload）")
                    .isFalse();
            assertThat(e.getMessage()).doesNotContain("SECRET-503-BODY");
        }

        @Test
        @DisplayName("401（我們沒被允許查）→ 保留 401 且 isNotFound=false")
        void upstreamAuthErrorIsNotNotFound() {
            server.respond("GET", "/api/users/user001/manager", 401, "{\"error\":\"unauthorized\"}");

            var e = (ExternalApiException) catchThrowable(() -> client().getManager("user001"));

            assertThat(e.status().value()).isEqualTo(401);
            assertThat(e.isNotFound())
                    .as("401 描述的是我們的設定，不是「這個人不存在」")
                    .isFalse();
        }

        @Test
        @DisplayName("read timeout → Kind.TIMEOUT，status 為 null，且不洩漏 token")
        void timeoutIsMapped() {
            server.respondSlowly("GET", "/api/users/slow/manager", 200,
                    "{\"managerId\":\"mgr001\"}", 2000);
            var slowClient = new OrgRestClient(server.baseUrl(), 2000, 200,
                    "Authorization", "Bearer super-secret-token");

            Throwable thrown = catchThrowable(() -> slowClient.getManager("slow"));

            assertThat(thrown).isInstanceOf(ExternalApiException.class);
            var e = (ExternalApiException) thrown;
            assertThat(e.kind()).isEqualTo(ExternalApiException.Kind.TIMEOUT);
            assertThat(e.status()).isNull();
            assertThat(e.path()).isEqualTo("/api/users/slow/manager");
            assertThat(e.getMessage())
                    .as("token 只在 request header 裡，任何例外訊息都不得帶出")
                    .doesNotContain("super-secret-token");
        }

        @Test
        @DisplayName("連線被拒 → Kind.CONNECTION，status 為 null")
        void connectionRefusedIsMapped() throws Exception {
            int freePort;
            try (var socket = new ServerSocket(0)) {
                freePort = socket.getLocalPort();
            }
            var refusedClient = new OrgRestClient(
                    "http://127.0.0.1:" + freePort, 500, 500, "Authorization", "");

            Throwable thrown = catchThrowable(() -> refusedClient.getManager("user001"));

            assertThat(thrown).isInstanceOf(ExternalApiException.class);
            var e = (ExternalApiException) thrown;
            assertThat(e.kind())
                    .as("拒線是位址設錯（重試不會成功），與逾時（重試可能成功）分開")
                    .isEqualTo(ExternalApiException.Kind.CONNECTION);
            assertThat(e.status()).isNull();
        }
    }

    // ── 服務層語意（真 client + 假 Redis）──────────────────────────

    /**
     * ⚠️ 「404 的 null 語意」的釐清（實作時逐行核對過）：
     *
     * <p>本 repo 的契約是 —— <b>鏈頂人員</b>沒有主管＝200 + {@code {}}，
     * client 回 {@code null}，服務層回 {@code null} 並快取哨兵值；
     * <b>查無此人</b>＝404，服務層讓例外往外傳（fail-closed），不回 null。
     * 這是 {@code ExternalActorGuard} 把兩者分成 400／503 的前提。
     *
     * <p>這組測試把兩條路徑<b>分開</b>釘住：只驗其中一條會讓
     * 「404 吞成 null」的實作全綠 —— 而它會讓不存在的人通過存在性檢查。
     */
    @Nested
    @DisplayName("服務層語意：404 fail-closed、鏈頂才是 null、快取行為不變")
    class ServiceSemantics {

        private StringRedisTemplate redis;
        private ValueOperations<String, String> ops;

        @BeforeEach
        @SuppressWarnings("unchecked")
        void setUpRedis() {
            redis = Mockito.mock(StringRedisTemplate.class);
            ops = Mockito.mock(ValueOperations.class);
            when(redis.opsForValue()).thenReturn(ops);
            // 預設 cache miss（ops.get 回 null）。
        }

        @Test
        @DisplayName("404 → 拋例外、不快取；不得吞成 null")
        void notFoundFailsClosed() {
            server.respond("GET", "/api/users/nobody/manager", 404, "{}");
            var orgService = new OrgService(client(), redis);

            assertThatThrownBy(() -> orgService.getDirectManager("nobody"))
                    .isInstanceOf(ExternalApiException.class);
            Mockito.verify(ops, Mockito.never())
                    .set(anyString(), anyString(), any(Duration.class));
        }

        @Test
        @DisplayName("鏈頂 {} → null，且快取空字串哨兵（避免每次重查）")
        void chainTopIsNullAndCached() {
            server.respond("GET", "/api/users/dir001/manager", 200, "{}");
            var orgService = new OrgService(client(), redis);

            assertThat(orgService.getDirectManager("dir001")).isNull();
            Mockito.verify(ops).set("org:manager:dir001", "", Duration.ofMinutes(60));
        }

        @Test
        @DisplayName("5xx → 拋例外、不快取（fail-closed，不得回舊值或 null）")
        void serverErrorFailsClosed() {
            server.respond("GET", "/api/users/user001/manager", 500, "{}");
            var orgService = new OrgService(client(), redis);

            assertThatThrownBy(() -> orgService.getDirectManager("user001"))
                    .isInstanceOf(ExternalApiException.class);
            Mockito.verify(ops, Mockito.never())
                    .set(anyString(), anyString(), any(Duration.class));
        }
    }
}
