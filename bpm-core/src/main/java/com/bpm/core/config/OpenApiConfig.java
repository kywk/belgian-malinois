package com.bpm.core.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 文件的靜態資訊（#65）。
 *
 * <p>只有 title／version／description 這種不隨請求變動的欄位放在這裡；
 * 端點清單由 springdoc 掃描 {@code @RestController} 自動產生 ——
 * 手寫的第二份清單會漂移，產生的不會。
 *
 * <h2>prod 的關閉開關不在這裡</h2>
 *
 * <p>它在 {@code application.yml} 的 prod 文件（{@code springdoc.api-docs.enabled}
 * 與 {@code springdoc.swagger-ui.enabled} 皆為 false）。關閉由部署 profile 決定，
 * 而不是讓程式碼在執行期判斷環境 —— 後者只是多一條「判斷寫錯就外洩」的路徑。
 * 授權層（路徑 permitAll）在 {@link com.bpm.core.security.SecurityConfig}，
 * 兩層的關係寫在該處的註解。
 *
 * <p>⚠️ 這個 bean 在 prod 仍會被建立（{@code enabled: false} 只是不註冊端點），
 * 所以這裡不得放入任何敏感資訊 —— 它只是一段對外公開的自我介紹。
 */
@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI greyhoundOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Greyhound BPM 平台 API")
                // 與 pom.xml 的 <version> 同步；發版時一起改。
                .version("0.0.1-SNAPSHOT")
                .description("""
                        企業內部低代碼 BPM 流程平台（Flowable）的後端 API。

                        認證：使用者請求帶 `Authorization: Bearer <JWT>`；server 之間
                        走信任閘道（X-Gateway-Secret + X-User-Id）；外部系統 API 另有
                        API key。授權矩陣見 SecurityConfig 的類別註解。

                        本文件只在 dev/test 開放；prod 已由設定關閉。
                        """));
    }
}
