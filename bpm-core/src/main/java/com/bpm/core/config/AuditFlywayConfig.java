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
 * 稽核 DB（第二個 DataSource）的 Flyway migration。
 *
 * <p>為什麼需要手寫：Spring Boot 的 {@code FlywayAutoConfiguration} 只會綁定
 * <b>主</b> DataSource。bpm-core 有兩個 DataSource，稽核那一個完全不在自動配置的
 * 視野內，因此 {@code spring.flyway.*} 對它無效 —— 不手動驅動的話，
 * {@code db/migration/audit} 下的 migration 永遠不會執行。關掉 ddl-auto 之後，
 * 這意味著稽核表根本不會存在。
 *
 * <h2>兩個踩過的坑（皆由 AuditWritePathTest 抓到）</h2>
 *
 * <p><b>1. 不可以暴露 {@code Flyway} 型別的 bean。</b>
 * {@code FlywayAutoConfiguration} 的條件是 {@code @ConditionalOnMissingBean(Flyway.class)} ——
 * 只要容器裡有任何一個 {@code Flyway} bean，Boot 對主 DataSource 的 Flyway
 * <b>就整組退讓</b>，結果是 core 的 migration 一條都不會跑。
 * 因此本類別提供的是 {@link AuditFlywayMigrator}（自訂型別），
 * Flyway 實例只存在於它的內部。
 *
 * <p><b>2. 必須用 {@code @Qualifier} 指定 DataSource。</b>
 * 參數名稱寫成 {@code auditDataSource} 是不夠的 —— {@code primaryDataSource} 帶
 * {@code @Primary}，依型別注入時 {@code @Primary} 優先於參數名稱匹配，
 * 於是稽核的 migration 會被套到 {@code bpm_core_db} 上（而且不會有任何錯誤）。
 */
@Configuration
public class AuditFlywayConfig {

    @Bean
    public AuditFlywayMigrator auditFlywayMigrator(
            @Qualifier("auditDataSource") DataSource auditDataSource,
            @Value("${bpm.audit.flyway.locations:classpath:db/migration/audit}") String locations) {
        return new AuditFlywayMigrator(auditDataSource, locations);
    }

    /**
     * 稽核 migration 的執行器。
     *
     * <p>刻意不是 {@code Flyway} 的子類別也不暴露 {@code Flyway} ——
     * 見上方第 1 點。建構時即完成 migration，因此任何
     * {@code @DependsOn("auditFlywayMigrator")} 的 bean 都能確定表已存在。
     */
    public static class AuditFlywayMigrator {

        private static final Logger log = LoggerFactory.getLogger(AuditFlywayMigrator.class);

        AuditFlywayMigrator(DataSource dataSource, String locations) {
            Flyway flyway = Flyway.configure()
                    .dataSource(dataSource)
                    .locations(locations)
                    // 既有 dev DB 已由 ddl-auto 建好表（schema 非空但沒有歷史表）。
                    // baselineVersion=0 讓 Flyway 標記在 V1 之前，因此 V1 仍會執行 ——
                    // 這是刻意的，配合 V1 的幂等寫法（已存在則跳過、缺少則補建）。
                    // 若用預設的 baselineVersion=1，V1 會被標記為已套用而永不執行。
                    .baselineOnMigrate(true)
                    .baselineVersion("0")
                    .load();
            var result = flyway.migrate();
            log.info("稽核 DB migration 完成：套用 {} 個，目前版本 {}",
                    result.migrationsExecuted, result.targetSchemaVersion);
        }
    }
}
