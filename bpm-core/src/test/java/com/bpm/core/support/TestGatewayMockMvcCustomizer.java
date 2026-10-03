package com.bpm.core.support;

import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.web.servlet.setup.ConfigurableMockMvcBuilder;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 讓測試以「信任閘道」的身分發出請求。
 *
 * <h2>為什麼用 defaultRequest 而不是改 113 個呼叫點</h2>
 *
 * <p>加上 SecurityFilterChain 之後，所有 MockMvc 呼叫都會變 401。
 * 逐一補上認證標頭是 113 處改動，而那種規模的機械修改很容易在過程中
 * 不小心弱化斷言 —— 尤其是那些原本斷言特定 HTTP 狀態碼的測試。
 *
 * <p>{@code defaultRequest} 只加「請求本身沒有指定」的標頭
 * （{@code MockHttpServletRequestBuilder.merge} 對 header 是
 * 「不存在才補」），所以：
 * <ul>
 *   <li>68 處明確寫了 {@code X-User-Id} 的測試，身分原樣生效。</li>
 *   <li>其餘沒寫的，套用預設身分 {@link #DEFAULT_USER}。</li>
 * </ul>
 *
 * <h2>預設身分刻意選一個沒有權限的人</h2>
 *
 * <p>{@code user001} 在權限 fixture 裡沒有任何權限碼。若預設成 admin001，
 * 所有授權規則在測試裡就形同虛設 —— 每個測試都會以管理員身分通過，
 * 而「這個端點該不該讓一般人用」這個問題永遠不會被問到。
 *
 * <p>代價是：原本打管理端點卻沒宣告身分的測試會變成 403。那是有資訊的失敗
 * —— 它指出那個測試一直在用「無身分」存取需要權限的端點。
 */
@TestConfiguration
public class TestGatewayMockMvcCustomizer {

    /** 與 application-test.yml 的 bpm.security.gateway.shared-secret 一致。 */
    public static final String GATEWAY_SECRET = "test-only-gateway-secret";

    /** 沒有任何權限碼的一般使用者。見類別註解。 */
    public static final String DEFAULT_USER = "user001";

    @Bean
    MockMvcBuilderCustomizer gatewayIdentityCustomizer() {
        return (ConfigurableMockMvcBuilder<?> builder) -> builder.defaultRequest(
                get("/")
                        .header("X-Gateway-Secret", GATEWAY_SECRET)
                        .header("X-User-Id", DEFAULT_USER));
    }
}
