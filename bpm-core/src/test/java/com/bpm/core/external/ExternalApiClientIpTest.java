package com.bpm.core.external;

import com.bpm.core.model.ExternalSystem;
import com.bpm.core.repository.ExternalSystemRepository;
import com.bpm.core.support.IntegrationTestBase;
import org.flowable.engine.RuntimeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R-22：在反向代理後方，IP 白名單與稽核 ip 必須看到真實 client IP。
 *
 * <h2>缺陷形狀</h2>
 *
 * <p>容器部署只有 nginx 對外（{@code docker-compose.prod.yml} 不公開 bpm-core
 * 的 port），nginx 會帶 {@code X-Forwarded-For}。但 application.yml 原本沒有
 * {@code server.forward-headers-strategy}，Servlet 容器的
 * {@code request.getRemoteAddr()} 是 <b>nginx 容器位址</b>：
 * <ul>
 *   <li>白名單填真實系統 IP → 全部被擋（服務中斷）；</li>
 *   <li>白名單填 nginx IP → <b>對所有外部系統一律放行</b>（白名單形同虛設）；</li>
 *   <li>{@code ExternalApiAuthFilter} 的拒絕稽核 {@code ip} 也是 nginx IP，
 *       事故調查時分不出「哪一個來源在打」。</li>
 * </ul>
 *
 * <h2>修法與信任邊界</h2>
 *
 * <p>設 {@code server.forward-headers-strategy: native}（Tomcat RemoteIpValve）。
 * Valve <b>只在直接連線者（TCP peer）是內網／loopback 位址時</b>才採用
 * {@code X-Forwarded-For}；prod 能連到 bpm-core 的只有私有網路上的 nginx，
 * 公網打不到應用程式，因此無法用偽造的 header 冒充白名單來源。判定與
 * 訊息中的 IP 都跟著變成真實 client IP（見 application.yml 同段說明）。
 *
 * <h2>⚠️ 走真實 HTTP 而不是 MockMvc</h2>
 *
 * <p>RemoteIpValve 是 Servlet 容器層的元件，MockMvc 完全不經過它 ——
 * 用 MockMvc 寫這條測試，就算設定漏了也照樣全綠（{@code ErrorMessageDisclosureTest}
 * 的教訓：走真實 HTTP 才測得到容器層行為）。
 *
 * <h2>負向控制組（2026-10-05 實測）</h2>
 *
 * <p>拿掉 application.yml 的 {@code forward-headers-strategy: native} 後重跑：
 * <b>2 條都紅</b>。
 * <ul>
 *   <li>{@link #forwardedClientIpDecidesWhitelist}：XFF 命中白名單卻拿到
 *       403（{@code IP not in whitelist: 127.0.0.1}）—— remoteAddr 退回
 *       直接連線者。</li>
 *   <li>{@link #rejectedAuditRecordsForwardedClientIp}：稽核的 {@code ip}
 *       變成 127.0.0.1，不再是 XFF 的真實來源。</li>
 * </ul>
 * <p>這證明本組測試驗的是「容器真的採用了 XFF」，而不是只有斷言寫得漂亮。
 */
class ExternalApiClientIpTest extends IntegrationTestBase {

    /** 外部系統自稱的來源 IP（TEST-NET-3，不會與任何真實主機衝突）。 */
    private static final String WHITELISTED_IP = "203.0.113.9";
    /** 另一個 IP：用來證明「不命中白名單」時真的被擋，而不是一律放行。 */
    private static final String OTHER_IP = "198.51.100.7";

    @Autowired
    private ExternalSystemRepository repo;

    @Autowired
    private RuntimeService runtimeService;

    private final HttpClient http = HttpClient.newHttpClient();
    private final List<String> createdSystems = new ArrayList<>();
    private final List<String> createdInstances = new ArrayList<>();

    @BeforeEach
    void cleanAudit() {
        truncateAuditLog();
    }

    @AfterEach
    void cleanup() {
        // 先清流程實例再刪系統；留下運行中的實例會影響其他測試的計數斷言。
        for (String pid : createdInstances) {
            try {
                runtimeService.deleteProcessInstance(pid, "R-22 測試清理");
            } catch (Exception ignored) {
                // 已結束的實例（例如被 Rejected 的請求沒有建立）不影響後續斷言。
            }
        }
        createdInstances.clear();
        createdSystems.forEach(sid -> repo.findBySystemId(sid).ifPresent(repo::delete));
        createdSystems.clear();
    }

    // ── fixture／工具 ──────────────────────────────────────────────

    /**
     * 一筆只允許 {@link #WHITELISTED_IP} 的系統。
     *
     * <p>直接寫 repository：本測試要驗的是 filter 的來源判定，不是 admin API。
     */
    private String givenSystem() {
        String sid = "r22-" + UUID.randomUUID().toString().substring(0, 8);
        ExternalSystem sys = new ExternalSystem();
        sys.setSystemId(sid);
        sys.setSystemName("R22 client IP 測試系統");
        sys.setApiKey(ApiKeyUtil.hash("r22-key-" + sid));
        sys.setAllowedProcessKeys("[\"leave-approval\"]");
        sys.setAllowedActions("[\"start_process\"]");
        sys.setIpWhitelist(WHITELISTED_IP);
        sys.setEnabled(true);
        sys.setAllowOnBehalfOf(false);
        sys.setCreatedAt(java.time.Instant.now());
        repo.save(sys);
        createdSystems.add(sid);
        return sid;
    }

    private static String startBody(String businessKey) {
        return "{\"processDefinitionKey\":\"leave-approval\","
                + "\"businessKey\":\"" + businessKey + "\","
                + "\"firstTaskAssignee\":\"mgr001\","
                + "\"variables\":{\"leaveType\":\"annual\",\"days\":1}}";
    }

    /**
     * 對真實 Tomcat 發起一次外部 API 呼叫。
     *
     * @param forwardedFor {@code X-Forwarded-For} 的值；null ＝ 不帶這個 header
     *                     （模擬開發者直連 bpm-core，不經 nginx）
     */
    private HttpResponse<String> start(String systemId, String businessKey, String forwardedFor)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + SERVLET_PORT
                                + "/api/external/process-instances"))
                .header("X-API-Key", "r22-key-" + systemId)
                .header("X-System-Id", systemId)
                .header("Content-Type", "application/json");
        if (forwardedFor != null) {
            builder.header("X-Forwarded-For", forwardedFor);
        }
        return http.send(builder.POST(HttpRequest.BodyPublishers.ofString(
                startBody(businessKey))).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String pidOf(HttpResponse<String> res) {
        var matcher = java.util.regex.Pattern.compile("\"processInstanceId\":\"([^\"]+)\"")
                .matcher(res.body());
        return matcher.find() ? matcher.group(1) : null;
    }

    /** 某系統的 EXTERNAL_API_CALL 稽核 detail，依 id。 */
    private static List<String> externalAuditDetails(String operatorId) {
        List<String> out = new ArrayList<>();
        withAuditConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT detail FROM bpm_audit_log "
                            + "WHERE operation_type = 'EXTERNAL_API_CALL' AND operator_id = ? "
                            + "ORDER BY id")) {
                ps.setString(1, operatorId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(rs.getString(1));
                }
            }
        });
        return out;
    }

    private static List<String> awaitRejectedAudit(String operatorId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        List<String> rejected = List.of();
        while (System.currentTimeMillis() < deadline) {
            rejected = externalAuditDetails(operatorId).stream()
                    .filter(d -> d != null && d.contains("rejected")).toList();
            if (!rejected.isEmpty()) return rejected;
            Thread.sleep(100);
        }
        return rejected;
    }

    // ── 白名單判定必須用 XFF 的真實 client IP ───────────────────────

    @Test
    @DisplayName("R-22：XFF 命中白名單 → 放行；不命中／不帶 XFF → 403（dev 直連不被誤擋）")
    void forwardedClientIpDecidesWhitelist() throws Exception {
        String sid = givenSystem();

        // 1. nginx 轉送的真實 client IP 在白名單內 → 必須放行。
        //    改動前 remoteAddr 是 127.0.0.1（nginx 的位址在測試中是 loopback），
        //    這一筆會拿到 403。
        HttpResponse<String> allowed = start(sid, "r22-allowed-" + UUID.randomUUID(),
                WHITELISTED_IP);
        assertThat(allowed.statusCode())
                .as("XFF 命中白名單必須放行，body=%s", allowed.body())
                .isEqualTo(200);
        String pid = pidOf(allowed);
        assertThat(pid).isNotBlank();
        createdInstances.add(pid);

        // 2. 同一個系統、不同來源 IP → 403。證明白名單不是「一律放行」。
        HttpResponse<String> denied = start(sid, "r22-denied-" + UUID.randomUUID(), OTHER_IP);
        assertThat(denied.statusCode()).isEqualTo(403);
        assertThat(denied.body())
                .as("拒絕訊息帶的是請求自稱的 client IP")
                .contains(OTHER_IP)
                .as("不得回顯直接連線者（proxy）的位址 —— 那正是改動前的行為")
                .doesNotContain("127.0.0.1");

        // 3. 不帶 XFF（開發者直連 bpm-core）→ 以 TCP peer（127.0.0.1）判定。
        //    application.yml 說明的不被誤擋是指「沒有 header 時行為與改動前相同」，
        //    不是「直連一律放行」—— 白名單本來就不含 127.0.0.1。
        HttpResponse<String> direct = start(sid, "r22-direct-" + UUID.randomUUID(), null);
        assertThat(direct.statusCode()).isEqualTo(403);
    }

    @Test
    @DisplayName("R-22：拒絕稽核的 ip 是真實 client IP（XFF），不是 proxy 位址")
    void rejectedAuditRecordsForwardedClientIp() throws Exception {
        String sid = givenSystem();

        HttpResponse<String> denied = start(sid, "r22-audit-" + UUID.randomUUID(), OTHER_IP);
        assertThat(denied.statusCode()).isEqualTo(403);

        List<String> rejected = awaitRejectedAudit("system:" + sid);
        assertThat(rejected)
                .as("filter 的拒絕必須留痕，且 ip 必須是外層的真實來源")
                .anySatisfy(d -> assertThat(d)
                        .contains("rejected")
                        .contains("ip")
                        .contains(OTHER_IP)
                        // IP 檢查在任何 action 解析之前，所以 detail 只有 uri
                        // 沒有 action —— 這裡不假装有 action 欄位可驗。
                        .contains("uri"));
        assertThat(rejected)
                .as("不得把 proxy 位址寫進稽核 —— 那會讓鑑識失去價值")
                .noneSatisfy(d -> assertThat(d).contains("127.0.0.1"));
    }
}
