package com.bpm.core.config;

import com.bpm.core.webhook.ProcessCompletedListener;
import org.flowable.spring.SpringProcessEngineConfiguration;
import org.flowable.spring.boot.EngineConfigurationConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class FlowableConfig {

    @Bean
    public EngineConfigurationConfigurer<SpringProcessEngineConfiguration> processEngineConfigurer(
            ProcessCompletedListener processCompletedListener) {
        return config -> config.setEventListeners(List.of(processCompletedListener));
    }
}
