package com.bpm.core;

import org.flowable.spring.boot.FlowableJpaAutoConfiguration;
import org.flowable.spring.boot.ProcessEngineAutoConfiguration;
import org.flowable.spring.boot.ProcessEngineServicesAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

// Flowable 6.8.1 uses spring.factories (Spring Boot 2.x format).
// Spring Boot 3.x no longer reads EnableAutoConfiguration from spring.factories,
// so we must explicitly import Flowable's auto-configurations.
@SpringBootApplication
@EnableAsync
@ImportAutoConfiguration({
    ProcessEngineAutoConfiguration.class,
    ProcessEngineServicesAutoConfiguration.class,
    FlowableJpaAutoConfiguration.class
})
public class BpmCoreApplication {
    public static void main(String[] args) {
        SpringApplication.run(BpmCoreApplication.class, args);
    }
}
