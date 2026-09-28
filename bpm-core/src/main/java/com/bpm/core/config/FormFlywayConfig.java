package com.bpm.core.config;

import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * 表單 DB 的 Flyway migration。
 *
 * <p>與 {@link AuditFlywayConfig} 同樣的理由與同樣的兩個坑：
 *
 * <p><b>1. 不可暴露 {@code Flyway} 型別的 bean。</b>
 * {@code FlywayAutoConfiguration} 的條件是
 * {@code @ConditionalOnMissingBean(Flyway.class)} —— 容器裡有任何 Flyway bean，
 * Boot 對主 DataSource 的 Flyway 就整組退讓，core 的 migration 一條都不會跑。
 * 因此這裡提供的是自訂型別 {@link FormFlywayMigrator}。
 *
 * <p><b>2. 必須用 {@code @Qualifier} 指定 DataSource</b>，否則會拿到
 * {@code @Primary} 的那一個，把表單的 migration 套到 {@code bpm_core_db} 上。
 * （這正是 Flyway 剛引入時實際發生過的事。）
 */
@Configuration
public class FormFlywayConfig {

    @Bean
    public FormFlywayMigrator formFlywayMigrator(
            @Qualifier("formDataSource") DataSource formDataSource,
            @Value("${bpm.form.flyway.locations:classpath:db/migration/form}") String locations) {
        return new FormFlywayMigrator(formDataSource, locations);
    }

    public static class FormFlywayMigrator {

        private static final Logger log = LoggerFactory.getLogger(FormFlywayMigrator.class);

        FormFlywayMigrator(DataSource dataSource, String locations) {
            Flyway flyway = Flyway.configure()
                    .dataSource(dataSource)
                    .locations(locations)
                    // 既有環境的 bpm_form_db 已有 V1–V4 的歷史（原 form-service
                    // 建立的），baselineVersion=0 讓既有歷史繼續沿用而不重跑。
                    .baselineOnMigrate(true)
                    .baselineVersion("0")
                    .load();
            var result = flyway.migrate();
            log.info("表單 DB migration 完成：套用 {} 個，目前版本 {}",
                    result.migrationsExecuted, result.targetSchemaVersion);
        }
    }
}
