package com.bpm.core.config;

import com.bpm.core.support.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * #65：test profile 下 OpenAPI 文件必須真的打得開。
 *
 * <p>「加了依賴」與「端點可用」是兩件事 —— 中間隔著 SecurityConfig 的
 * permitAll 與 springdoc 的自動配置。這條測試走 MockMvc 打真正的過濾器鏈
 * 與 springdoc 端點，並用 title 斷言回應是 {@link OpenApiConfig} 產生的文件，
 * 而不是任何 200 的空殼。
 *
 * <p>繼承 {@link IntegrationTestBase} 沿用既有 context，<b>不</b>加
 * {@code @TestPropertySource}／{@code @Import}／{@code @DynamicPropertySource}：
 * 測試的 servlet port 是共用資源，另開 context 會多搶一個 port，
 * 也讓 Testcontainers 的連線多一份。
 *
 * <p>prod 的「關閉」不在這裡驗 —— 測試套件跑不到 prod profile，
 * 由 {@link OpenApiProdDisabledTest} 在設定層守。
 */
class OpenApiDocsIntegrationTest extends IntegrationTestBase {

    @Test
    @DisplayName("GET /v3/api-docs 回 200，且 JSON 帶 OpenApiConfig 的 title")
    void apiDocsAreAvailableInTestProfile() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("Greyhound BPM 平台 API"));
    }

    @Test
    @DisplayName("GET /swagger-ui.html 轉到 UI，且 UI 靜態資源打得開")
    void swaggerUiIsServedInTestProfile() throws Exception {
        mockMvc.perform(get("/swagger-ui.html"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/swagger-ui/index.html*"));
        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk());
    }
}
