package com.bpm.core.security;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/** 註冊 {@link CallerId} 的解析器。 */
@Configuration
public class WebMvcSecurityConfig implements WebMvcConfigurer {

    private final CallerIdArgumentResolver callerIdResolver;

    public WebMvcSecurityConfig(CallerIdArgumentResolver callerIdResolver) {
        this.callerIdResolver = callerIdResolver;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(callerIdResolver);
    }
}
