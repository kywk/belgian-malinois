package com.bpm.core;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Stage 4：移除 Flowable 6.8.1 時期的 @ImportAutoConfiguration workaround。
 *
 * <p>當時的註解是：
 * <pre>
 *   // Flowable 6.8.1 uses spring.factories (Spring Boot 2.x format).
 *   // Spring Boot 3.x no longer reads EnableAutoConfiguration from spring.factories,
 *   // so we must explicitly import Flowable's auto-configurations.
 *   @ImportAutoConfiguration({ProcessEngineAutoConfiguration.class,
 *                             ProcessEngineServicesAutoConfiguration.class,
 *                             FlowableJpaAutoConfiguration.class})
 * </pre>
 *
 * <p>Flowable 7 已改用 Boot 3 的
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 * 格式（已實際確認 7.2.0 的 flowable-spring-boot-autoconfigure jar 內含該檔案），
 * 因此那三行不再需要 —— 留著反而可能造成重複註冊。
 */
@SpringBootApplication
@EnableAsync
public class BpmCoreApplication {
    public static void main(String[] args) {
        SpringApplication.run(BpmCoreApplication.class, args);
    }
}
