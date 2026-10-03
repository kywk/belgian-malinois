package com.bpm.core.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #65：prod 必須關閉 API 文件（api-docs 與 swagger-ui 兩個開關都要）。
 *
 * <p>為什麼不能只靠整合測試：測試套件跑的是 test profile，prod 的設定
 * 在那裡根本不會被載入 ——「prod 忘了關」在整個測試套件裡是隱形的。
 * 這裡不啟 Spring context，直接讀 application.yml，守的是設定檔的形狀
 * （比照 {@code WebhookHmacSecretValidatorTest} 讀同一份 yml 的作法）。
 *
 * <p>{@code YamlPropertiesFactoryBean} 把多文件 YAML 平坦化，後面的文件
 * 覆蓋前面的；prod 是最後一份，所以讀到的就是 prod profile 的值。
 * base 文件不設任何 springdoc 開關（維持預設 enabled: true），
 * 因此這兩個值只可能來自 prod 文件。
 *
 * <p>⚠️ 對應的授權層是 SecurityConfig 對文件路徑的 permitAll。若這裡守的
 * prod 關閉被拿掉，permitAll 會直接讓文件在 prod 公開 —— 這條測試就是
 * 那個開關的守門人。負控：把 false 改成 true（或整段刪掉），測試會紅。
 */
class OpenApiProdDisabledTest {

    @Test
    @DisplayName("application.yml 的 prod 文件把 api-docs 與 swagger-ui 都設為 false")
    void prodProfileDisablesApiDocsAndSwaggerUi() {
        var factory = new YamlPropertiesFactoryBean();
        factory.setResources(new ClassPathResource("application.yml"));
        var props = factory.getObject();
        assertThat(props).isNotNull();

        assertThat(props.getProperty("springdoc.api-docs.enabled"))
                .as("prod 必須明確關閉 /v3/api-docs —— 少了它，"
                        + "SecurityConfig 的 permitAll 會讓文件在 prod 公開")
                .isEqualTo("false");
        assertThat(props.getProperty("springdoc.swagger-ui.enabled"))
                .as("prod 必須明確關閉 /swagger-ui.html —— 只關 api-docs 的話，"
                        + "UI 的靜態資源仍在（頁面開得起來、只是載不到 spec）")
                .isEqualTo("false");
    }
}
