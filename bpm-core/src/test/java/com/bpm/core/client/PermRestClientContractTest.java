package com.bpm.core.client;

import com.bpm.core.service.BpmPermissionService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * {@link PermRestClient} 的契約測試（#9 正式化）：真 HTTP server、直接建構 client。
 *
 * <p>測項與 {@link OrgRestClientContractTest} 對稱：端點契約（spec §6.2）、
 * 認證注入（設／未設、原樣傳遞）、失敗映射（一致型別、不複製 body、
 * 連線與逾時可分辨）以及服務層 fail-closed 語意。理由見該類別註解。
 */
class PermRestClientContractTest {

    private StubExternalApiServer server;

    @BeforeEach
    void setUp() {
        server = new StubExternalApiServer();
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    private PermRestClient client() {
        return client("", "Authorization");
    }

    private PermRestClient client(String token, String headerName) {
        return new PermRestClient(server.baseUrl(), 2000, 3000, headerName, token);
    }

    // ── 端點契約（spec §6.2）────────────────────────────────────────

    @Nested
    @DisplayName("端點契約：路徑、query 與回應解析")
    class Endpoints {

        @Test
        @DisplayName("GET /api/permissions/{code}/users → 權限持有者陣列（不帶 deptId）")
        void getUsersByPermission() {
            server.respond("GET", "/api/permissions/hr:leave:approve/users", 200,
                    "[\"mgr001\",\"mgr002\",\"dir001\"]");

            assertThat(client().getUsersByPermission("hr:leave:approve"))
                    .containsExactly("mgr001", "mgr002", "dir001");
            assertThat(server.lastReceived().path())
                    .as("權限碼含冒號，是路徑的一部分（不是 query）")
                    .isEqualTo("/api/permissions/hr:leave:approve/users");
            assertThat(server.lastReceived().rawQuery())
                    .as("全域查詢不得夾帶 deptId —— 否則會靜默變成部門範圍查詢")
                    .isNull();
        }

        @Test
        @DisplayName("GET /api/permissions/{code}/users?deptId= → 部門範圍持有者")
        void getUsersByPermissionAndDept() {
            server.respond("GET", "/api/permissions/hr:leave:approve/users", 200, "[\"mgr001\"]");

            assertThat(client().getUsersByPermissionAndDept("hr:leave:approve", "dept001"))
                    .containsExactly("mgr001");
            assertThat(server.lastReceived().path()).isEqualTo("/api/permissions/hr:leave:approve/users");
            assertThat(server.lastReceived().rawQuery()).isEqualTo("deptId=dept001");
        }

        @Test
        @DisplayName("GET /api/users/{id}/permissions → 權限碼陣列")
        void getUserPermissions() {
            server.respond("GET", "/api/users/mgr001/permissions", 200,
                    "[\"hr:leave:approve\",\"bpm:form:design\"]");

            assertThat(client().getUserPermissions("mgr001"))
                    .containsExactly("hr:leave:approve", "bpm:form:design");
            assertThat(server.lastReceived().path()).isEqualTo("/api/users/mgr001/permissions");
        }

        @Test
        @DisplayName("GET /api/users/{id}/has-permission?code= → {hasPermission}")
        void hasPermission() {
            server.respond("GET", "/api/users/mgr001/has-permission", 200,
                    "{\"hasPermission\":true}");
            assertThat(client().hasPermission("mgr001", "hr:leave:approve")).isTrue();
            assertThat(server.lastReceived().queryParam("code"))
                    .as("權限碼是 query 參數，值必須原樣（解碼後）到達另一端")
                    .isEqualTo("hr:leave:approve");

            server.respond("GET", "/api/users/mgr001/has-permission", 200,
                    "{\"hasPermission\":false}");
            assertThat(client().hasPermission("mgr001", "finance:payment:approve")).isFalse();

            // 欄位缺席（判定型端點對未知使用者回空集合的等價形狀）→ false。
            server.respond("GET", "/api/users/mgr001/has-permission", 200, "{}");
            assertThat(client().hasPermission("mgr001", "anything"))
                    .as("沒有明確的 true 就是拒絕（fail-closed）")
                    .isFalse();
        }
    }

    // ── 認證注入 ───────────────────────────────────────────────────

    @Nested
    @DisplayName("認證注入：設定值原樣進指定 header")
    class Auth {

        @Test
        @DisplayName("token 設 → Authorization 原樣帶出")
        void tokenIsSentVerbatim() {
            server.respond("GET", "/api/users/mgr001/permissions", 200, "[]");

            client("Bearer perm-secret-token", "Authorization").getUserPermissions("mgr001");

            assertThat(server.lastReceived().header("Authorization"))
                    .isEqualTo("Bearer perm-secret-token");
        }

        @Test
        @DisplayName("auth-header 自訂 → 只送自訂名稱")
        void customHeaderName() {
            server.respond("GET", "/api/users/mgr001/permissions", 200, "[]");

            client("perm-token-value", "X-Perm-Token").getUserPermissions("mgr001");

            assertThat(server.lastReceived().header("X-Perm-Token")).isEqualTo("perm-token-value");
            assertThat(server.lastReceived().hasHeader("Authorization")).isFalse();
        }

        @Test
        @DisplayName("token 未設（空字串）→ 完全不送 auth header")
        void emptyTokenSendsNoHeader() {
            server.respond("GET", "/api/users/mgr001/permissions", 200, "[]");

            client("", "Authorization").getUserPermissions("mgr001");

            assertThat(server.lastReceived().hasHeader("Authorization")).isFalse();
        }
    }

    // ── 失敗映射 ───────────────────────────────────────────────────

    @Nested
    @DisplayName("失敗映射：一致型別、可分辨、不外洩")
    class Failures {

        @Test
        @DisplayName("404（查無此權限碼）→ ExternalApiException（isNotFound），不複製 body")
        void notFoundIsMapped() {
            server.respond("GET", "/api/permissions/nope/users", 404,
                    "{\"secret\":\"SECRET-PERM-404\"}");

            Throwable thrown = catchThrowable(() -> client().getUsersByPermission("nope"));

            assertThat(thrown)
                    .as("既有的 catch (RestClientException) 仍必須接得住")
                    .isInstanceOf(RestClientException.class)
                    .isInstanceOf(ExternalApiException.class);
            var e = (ExternalApiException) thrown;
            assertThat(e.service()).isEqualTo(ExternalApiException.Service.PERM);
            assertThat(e.kind()).isEqualTo(ExternalApiException.Kind.HTTP_STATUS);
            assertThat(e.status().value()).isEqualTo(404);
            assertThat(e.path()).isEqualTo("/api/permissions/nope/users");
            assertThat(e.isNotFound()).isTrue();
            assertThat(e.getMessage()).doesNotContain("SECRET-PERM-404");
            assertThat(e.getCause()).isNull();
        }

        @Test
        @DisplayName("5xx → 保留狀態碼且 isNotFound=false")
        void serverErrorIsMapped() {
            server.respond("GET", "/api/users/mgr001/permissions", 503, "{}");

            var e = (ExternalApiException) catchThrowable(
                    () -> client().getUserPermissions("mgr001"));

            assertThat(e.service()).isEqualTo(ExternalApiException.Service.PERM);
            assertThat(e.status().value()).isEqualTo(503);
            assertThat(e.isNotFound()).isFalse();
        }

        @Test
        @DisplayName("read timeout → Kind.TIMEOUT，status null")
        void timeoutIsMapped() {
            server.respondSlowly("GET", "/api/users/slow/permissions", 200, "[]", 2000);
            var slowClient = new PermRestClient(server.baseUrl(), 2000, 200,
                    "Authorization", "");

            var e = (ExternalApiException) catchThrowable(
                    () -> slowClient.getUserPermissions("slow"));

            assertThat(e.kind()).isEqualTo(ExternalApiException.Kind.TIMEOUT);
            assertThat(e.status()).isNull();
            assertThat(e.path()).isEqualTo("/api/users/slow/permissions");
        }

        @Test
        @DisplayName("連線被拒 → Kind.CONNECTION")
        void connectionRefusedIsMapped() throws Exception {
            int freePort;
            try (var socket = new ServerSocket(0)) {
                freePort = socket.getLocalPort();
            }
            var refusedClient = new PermRestClient(
                    "http://127.0.0.1:" + freePort, 500, 500, "Authorization", "");

            var e = (ExternalApiException) catchThrowable(
                    () -> refusedClient.getUserPermissions("mgr001"));

            assertThat(e.kind()).isEqualTo(ExternalApiException.Kind.CONNECTION);
            assertThat(e.status()).isNull();
        }
    }

    // ── 服務層語意（真 client + 假 Redis）──────────────────────────

    @Nested
    @DisplayName("服務層語意：404／5xx fail-closed、空清單仍快取")
    class ServiceSemantics {

        private StringRedisTemplate redis;
        private ValueOperations<String, String> ops;
        private BpmPermissionService permService;

        @BeforeEach
        @SuppressWarnings("unchecked")
        void setUpService() {
            redis = Mockito.mock(StringRedisTemplate.class);
            ops = Mockito.mock(ValueOperations.class);
            when(redis.opsForValue()).thenReturn(ops);
            // 預設 cache miss（ops.get 回 null）。
            permService = new BpmPermissionService(client(), redis, Mockito.mock(OrgService.class));
        }

        @Test
        @DisplayName("404 → 拋例外、不快取；不得吞成空清單")
        void notFoundFailsClosed() {
            server.respond("GET", "/api/permissions/nope/users", 404, "{}");

            assertThatThrownBy(() -> permService.getUsersByPermission("nope"))
                    .isInstanceOf(ExternalApiException.class);
            Mockito.verify(ops, Mockito.never())
                    .set(anyString(), anyString(), any(Duration.class));
        }

        @Test
        @DisplayName("空清單（合法的「還沒指派任何人」）→ 回空清單並快取")
        void emptyListIsCached() {
            server.respond("GET", "/api/permissions/nobody/users", 200, "[]");

            assertThat(permService.getUsersByPermission("nobody")).isEmpty();
            Mockito.verify(ops).set("perm:users:nobody", "", Duration.ofMinutes(5));
        }

        @Test
        @DisplayName("5xx → 拋例外、不快取")
        void serverErrorFailsClosed() {
            server.respond("GET", "/api/permissions/hr:leave:approve/users", 500, "{}");

            assertThatThrownBy(() -> permService.getUsersByPermission("hr:leave:approve"))
                    .isInstanceOf(ExternalApiException.class);
            Mockito.verify(ops, Mockito.never())
                    .set(anyString(), anyString(), any(Duration.class));
        }
    }
}
