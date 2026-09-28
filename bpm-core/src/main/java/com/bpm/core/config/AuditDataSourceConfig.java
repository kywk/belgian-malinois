package com.bpm.core.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.orm.jpa.EntityManagerFactoryBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

@Configuration
@EnableJpaRepositories(
        basePackages = "com.bpm.core.audit.repository",
        entityManagerFactoryRef = "auditEntityManagerFactory",
        transactionManagerRef = "auditTransactionManager"
)
public class AuditDataSourceConfig {

    @Bean
    @ConfigurationProperties("spring.datasource.audit")
    public DataSourceProperties auditDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    public DataSource auditDataSource() {
        return auditDataSourceProperties().initializeDataSourceBuilder().build();
    }

    // auditFlywayMigrator 必須先跑完 migration，EntityManagerFactory 才能對著存在的表初始化。
    // 關掉 ddl-auto 之後這個順序是硬需求，不再是最佳化。
    @Bean
    @DependsOn("auditFlywayMigrator")
    public LocalContainerEntityManagerFactoryBean auditEntityManagerFactory(
            EntityManagerFactoryBuilder builder) {
        return builder.dataSource(auditDataSource())
                .packages("com.bpm.core.audit.model")
                .persistenceUnit("audit")
                .build();
    }

    /**
     * 稽核交易管理器。
     *
     * <p>⚠️ {@code @Qualifier} 不可省略。容器裡有兩個
     * {@code LocalContainerEntityManagerFactoryBean}（primary 與 audit），而
     * {@code primaryEntityManagerFactory} 帶 {@code @Primary} ——
     * 依型別注入時 {@code @Primary} 的優先序高於「參數名稱剛好相同」，
     * 因此少了 qualifier 時這裡拿到的是 <b>primary</b> 的 EMF。
     *
     * <p>後果極度隱蔽：交易開在 primary 的 EntityManager 上，而
     * {@code AuditLogRepository} 用的是 audit 的 EntityManager ——
     * 兩者不同，於是 repository 的 {@code persist()} 落在一個沒有交易的
     * 暫時 EntityManager 上，<b>永遠不會 flush</b>；交易則對著一個沒有變更的
     * EntityManager 正常 commit。結果是稽核一筆都沒寫進去，
     * 而且完全沒有例外、沒有錯誤日誌。
     * （2026-09-28 以 AuditWritePathTest 定位，見該測試的註解。）
     */
    @Bean
    public PlatformTransactionManager auditTransactionManager(
            @Qualifier("auditEntityManagerFactory")
            LocalContainerEntityManagerFactoryBean auditEntityManagerFactory) {
        return new JpaTransactionManager(auditEntityManagerFactory.getObject());
    }
}
