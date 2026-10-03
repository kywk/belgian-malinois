package com.bpm.core.config;

import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AMQP 訊息的 JSON 轉換器（Stage 6：Jackson 2 → 3）。
 *
 * <p>Spring AMQP 4 的 Jackson 3 對應類別是 {@link JacksonJsonMessageConverter}
 * （在 spring-amqp jar，不是 spring-rabbit）。用無參數建構子，與舊的
 * {@code Jackson2JsonMessageConverter()} 同一形狀：它自建一顆
 * {@code JsonMapper}（findAndAddModules、關閉 FAIL_ON_UNKNOWN_PROPERTIES／
 * DEFAULT_VIEW_INCLUSION），<b>刻意不注入</b> Boot 的 JsonMapper bean ——
 * 維持與遷移前完全相同的 producer／consumer 行為，訊息 JSON 與
 * {@code __TypeId__} 標頭都不變。
 */
@Configuration
public class JacksonAmqpConfig {

    @Bean
    public MessageConverter jacksonJsonMessageConverter() {
        return new JacksonJsonMessageConverter();
    }
}
