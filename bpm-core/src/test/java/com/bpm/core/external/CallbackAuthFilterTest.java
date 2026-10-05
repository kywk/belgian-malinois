package com.bpm.core.external;

import com.bpm.core.model.ExternalSystem;
import com.bpm.core.repository.ExternalSystemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link CallbackAuthFilter} 的單元測試：回呼端點的認證鏈與授權閘門。
 *
 * <h2>這組測試在防什麼缺陷</h2>
 *
 * <p>認證鏈有五步，每一步的失敗都必須是 401 且<b>對外同一句</b>
 * {@code Authentication failed} —— 否則攻擊者不必持有任何密鑰，
 * 就能用回應內容把 systemId 一個一個分類出來（「查無此系統」／
 * 「未設定密鑰」／「時間戳過期」／「簽章錯誤」）。唯一的例外是
 * 「標頭缺漏」：它與系統存不存在無關，講清楚才不會讓正常呼叫端
 * 拿不到「你少帶了什麼」的線索。
 *
 * <p>另外兩條結構性契約：停用／IP／allowedActions 排在簽章<b>之後</b>
 * （只有持有密鑰的人看得到這些訊息），以及 body 快取後 controller
 * 仍能讀到同一份位元組（簽章是對原始位元組計算的）。
 */
class CallbackAuthFilterTest {

    private static final String SYSTEM_ID = "erp";
    private static final String SECRET = "cs-callback-secret";
    private static final byte[] BODY = "{\"event\":\"approved\"}".getBytes(StandardCharsets.UTF_8);

    private ExternalSystemRepository repo;
    private ExternalSystemAccessGuard accessGuard;
    private CallbackAuthFilter filter;
    private ExternalSystem system;

    @BeforeEach
    void setUp() {
        repo = mock(ExternalSystemRepository.class);
        accessGuard = mock(ExternalSystemAccessGuard.class);
        // tracker 用真的：它設定的 lastUsedAt 是本測試的斷言對象之一，
        // 只有 repo 是 mock（寫入不需要真的 DB）。門檻/窗口不是這裡的重點。
        filter = new CallbackAuthFilter(repo, accessGuard, new ObjectMapper(),
                new ExternalSystemUsageTracker(repo));

        system = new ExternalSystem();
        system.setSystemId(SYSTEM_ID);
        system.setEnabled(true);
        system.setCallbackSecret(SECRET);
        when(repo.findBySystemId(SYSTEM_ID)).thenReturn(Optional.of(system));
        when(accessGuard.rejectSystemOrIp(any(), anyString())).thenReturn(Optional.empty());
        when(accessGuard.rejectAction(any(), anyString())).thenReturn(Optional.empty());
    }

    /** 一個已簽好章、時間戳在窗內的回呼請求。 */
    private MockHttpServletRequest validRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/callback/events");
        request.setRemoteAddr("10.0.0.1");
        request.setContent(BODY);
        request.addHeader(CallbackAuthFilter.SYSTEM_HEADER, SYSTEM_ID);
        request.addHeader(CallbackAuthFilter.TIMESTAMP_HEADER, Instant.now().toString());
        request.addHeader(CallbackAuthFilter.SIGNATURE_HEADER,
                CallbackSignatureUtil.SIGNATURE_PREFIX + CallbackSignatureUtil.sign(SECRET, BODY));
        return request;
    }

    private static MockHttpServletRequest requestWithoutHeaders() {
        return new MockHttpServletRequest("POST", "/api/callback/events");
    }

    private MockHttpServletResponse run(MockHttpServletRequest request, MockFilterChain chain)
            throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response;
    }

    // ── 認證鏈 ────────────────────────────────────────────────────

    @Nested
    @DisplayName("認證失敗一律 401，且除缺標頭外訊息不可區分")
    class Authentication {

        @Test
        @DisplayName("缺任一標頭 → 401，訊息指名缺哪些標頭（唯一可區分的分支）")
        void missingHeadersAreSpelledOut() throws Exception {
            MockHttpServletResponse response = run(requestWithoutHeaders(), new MockFilterChain());

            assertThat(response.getStatus()).isEqualTo(401);
            assertThat(response.getContentAsString())
                    .contains("Missing")
                    .contains(CallbackAuthFilter.SYSTEM_HEADER)
                    .contains(CallbackAuthFilter.SIGNATURE_HEADER)
                    .contains(CallbackAuthFilter.TIMESTAMP_HEADER);
        }

        @Test
        @DisplayName("查無系統、未設定密鑰、時間戳過期、簽章錯誤 → 401 且都是同一句")
        void allAuthFailuresShareOneMessage() throws Exception {
            // 1. 查無系統
            MockHttpServletRequest unknown = validRequest();
            when(repo.findBySystemId(SYSTEM_ID)).thenReturn(Optional.empty());
            assertThat(run(unknown, new MockFilterChain()).getContentAsString())
                    .contains(CallbackAuthFilter.AUTH_FAILED);

            // 2. 系統存在但未設定 callback secret
            MockHttpServletRequest noSecret = validRequest();
            system.setCallbackSecret(null);
            assertThat(run(noSecret, new MockFilterChain()).getContentAsString())
                    .contains(CallbackAuthFilter.AUTH_FAILED);
            system.setCallbackSecret(SECRET);

            // 3. 時間戳過期（±5 分鐘外）
            MockHttpServletRequest stale = validRequest();
            stale.removeHeader(CallbackAuthFilter.TIMESTAMP_HEADER);
            stale.addHeader(CallbackAuthFilter.TIMESTAMP_HEADER,
                    Instant.now().minus(10, ChronoUnit.MINUTES).toString());
            assertThat(run(stale, new MockFilterChain()).getContentAsString())
                    .contains(CallbackAuthFilter.AUTH_FAILED);

            // 4. 簽章錯誤
            MockHttpServletRequest badSignature = validRequest();
            badSignature.removeHeader(CallbackAuthFilter.SIGNATURE_HEADER);
            badSignature.addHeader(CallbackAuthFilter.SIGNATURE_HEADER, "sha256=deadbeef");
            assertThat(run(badSignature, new MockFilterChain()).getContentAsString())
                    .contains(CallbackAuthFilter.AUTH_FAILED);
        }

        @Test
        @DisplayName("時間戳無法解析 → 401（不得當成 epoch 0 而放行）")
        void unparseableTimestampFails() throws Exception {
            MockHttpServletRequest request = validRequest();
            request.removeHeader(CallbackAuthFilter.TIMESTAMP_HEADER);
            request.addHeader(CallbackAuthFilter.TIMESTAMP_HEADER, "not-a-timestamp");

            assertThat(run(request, new MockFilterChain()).getStatus()).isEqualTo(401);
        }

        @Test
        @DisplayName("epoch 秒格式的時間戳也接受（外部系統不必做格式轉換）")
        void epochSecondsTimestampIsAccepted() throws Exception {
            MockHttpServletRequest request = validRequest();
            request.removeHeader(CallbackAuthFilter.TIMESTAMP_HEADER);
            request.addHeader(CallbackAuthFilter.TIMESTAMP_HEADER,
                    String.valueOf(Instant.now().getEpochSecond()));

            assertThat(run(request, new MockFilterChain()).getStatus()).isEqualTo(200);
        }

        @Test
        @DisplayName("body 超過 1 MiB → 413（回呼帶的是流程變數，不是檔案上傳）")
        void oversizedBodyIsRejected() throws Exception {
            MockHttpServletRequest request = validRequest();
            request.setContent(new byte[CallbackAuthFilter.MAX_BODY_BYTES + 1]);

            assertThat(run(request, new MockFilterChain()).getStatus()).isEqualTo(413);
        }
    }

    // ── 授權閘門（簽章之後） ─────────────────────────────────────

    @Nested
    @DisplayName("通過認證後的授權：停用／IP／allowedActions 一律 403")
    class Authorization {

        @Test
        @DisplayName("停用系統 → 403 System is disabled（只有持有密鑰的人看得到）")
        void disabledSystemIsForbidden() throws Exception {
            when(accessGuard.rejectSystemOrIp(any(), anyString()))
                    .thenReturn(Optional.of(new ExternalSystemAccessGuard.Rejection(403, "System is disabled")));

            MockHttpServletResponse response = run(validRequest(), new MockFilterChain());

            assertThat(response.getStatus()).isEqualTo(403);
            assertThat(response.getContentAsString()).contains("System is disabled");
        }

        @Test
        @DisplayName("IP 不在白名單 → 403")
        void deniedIpIsForbidden() throws Exception {
            when(accessGuard.rejectSystemOrIp(any(), anyString()))
                    .thenReturn(Optional.of(new ExternalSystemAccessGuard.Rejection(
                            403, "IP not in whitelist: 10.0.0.1")));

            MockHttpServletResponse response = run(validRequest(), new MockFilterChain());

            assertThat(response.getStatus()).isEqualTo(403);
            assertThat(response.getContentAsString()).contains("IP not in whitelist");
        }

        @Test
        @DisplayName("allowedActions 不含 callback → 403（用同一個 action 名稱比對）")
        void actionDeniedIsForbidden() throws Exception {
            when(accessGuard.rejectAction(any(), anyString()))
                    .thenReturn(Optional.of(new ExternalSystemAccessGuard.Rejection(
                            403, "Action not allowed: callback")));

            MockHttpServletResponse response = run(validRequest(), new MockFilterChain());

            assertThat(response.getStatus()).isEqualTo(403);
            verify(accessGuard).rejectAction(any(), org.mockito.ArgumentMatchers.eq("callback"));
        }
    }

    // ── 成功路徑 ─────────────────────────────────────────────────

    @Nested
    @DisplayName("通過後的請求：body 快取、lastUsedAt、request attribute")
    class Success {

        @Test
        @DisplayName("合法請求進入 controller，且 controller 能讀到同一份原始 body（可重讀）")
        void cachedBodyIsReplayable() throws Exception {
            MockHttpServletRequest request = validRequest();
            MockFilterChain chain = new MockFilterChain();

            MockHttpServletResponse response = run(request, chain);

            assertThat(response.getStatus()).isEqualTo(200);
            var forwarded = (jakarta.servlet.http.HttpServletRequest) chain.getRequest();
            assertThat(forwarded).isNotNull();
            assertThat(forwarded.getInputStream().readAllBytes()).isEqualTo(BODY);
            // 第二次讀取必須拿到相同位元組 —— servlet 的 input stream 原本只能讀一次，
            // 驗章與 controller 反序列化靠的就是這個 wrapper。
            assertThat(forwarded.getInputStream().readAllBytes()).isEqualTo(BODY);
        }

        @Test
        @DisplayName("成功才寫 EXTERNAL_API_CALL 之外的行為：更新 lastUsedAt 並設定 request attribute")
        void updatesLastUsedAtAndAttributes() throws Exception {
            MockHttpServletRequest request = validRequest();

            run(request, new MockFilterChain());

            verify(repo).save(system);
            assertThat(system.getLastUsedAt()).isNotNull();
            assertThat(request.getAttribute("externalSystemId")).isEqualTo(SYSTEM_ID);
            assertThat(request.getAttribute("externalSystem")).isSameAs(system);
        }

        @Test
        @DisplayName("認證失敗不得更新 lastUsedAt／不得進入 controller")
        void failuresDoNotTouchSystemOrChain() throws Exception {
            MockHttpServletRequest request = validRequest();
            request.removeHeader(CallbackAuthFilter.SIGNATURE_HEADER);
            request.addHeader(CallbackAuthFilter.SIGNATURE_HEADER, "sha256=wrong");
            MockFilterChain chain = new MockFilterChain();

            run(request, chain);

            verify(repo, never()).save(any());
            assertThat(chain.getRequest()).isNull();
        }
    }

    @Nested
    @DisplayName("shouldNotFilter：只有 /api/callback 前綴")
    class FilterScope {

        @Test
        @DisplayName("回呼路徑要過濾，其他路徑不過濾")
        void scope() {
            assertThat(filter.shouldNotFilter(new MockHttpServletRequest("POST", "/api/callback/events")))
                    .isFalse();
            assertThat(filter.shouldNotFilter(new MockHttpServletRequest("POST", "/api/callback")))
                    .isFalse();
            assertThat(filter.shouldNotFilter(new MockHttpServletRequest("POST", "/api/callbackx")))
                    .isTrue();
            assertThat(filter.shouldNotFilter(new MockHttpServletRequest("GET", "/api/tasks")))
                    .isTrue();
        }
    }
}
