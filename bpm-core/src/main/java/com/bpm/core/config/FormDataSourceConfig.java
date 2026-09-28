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

/**
 * 表單資料庫（第三個 DataSource）。
 *
 * <p>Stage 3（ADR-001）把 form-service 併入 bpm-core，但<b>只合併部署單元，
 * 不合併資料模型</b> —— {@code bpm_form_db} 保持獨立。因此這裡新增第三個
 * persistence unit，而不是把表單的表搬進 {@code bpm_core_db}。
 *
 * <h2>⚠️ 每一個注入點都必須寫明 @Qualifier</h2>
 *
 * <p>這不是風格偏好。commit {@code cecdbe4} 的教訓：
 * {@code primaryEntityManagerFactory} 帶 {@code @Primary}，而依型別注入時
 * {@code @Primary} 的優先序<b>高於「參數名稱剛好相同」</b>。
 * 少寫一個 qualifier，這裡的交易管理器就會建在 primary 的 EMF 上，
 * 於是表單寫入<b>靜默不落地</b>：沒有例外、沒有錯誤日誌、commit 還「成功」。
 *
 * <p>加入第三個 persistence unit 直接放大了這個風險，
 * 因此 {@code DataSourceBindingTest} 在本次變更之前就先建立起來，
 * 並已驗證它真的抓得到（移除 qualifier 後測試立刻失敗）。
 */
@Configuration
@EnableJpaRepositories(
        basePackages = "com.bpm.core.form.repository",
        entityManagerFactoryRef = "formEntityManagerFactory",
        transactionManagerRef = "formTransactionManager"
)
public class FormDataSourceConfig {

    @Bean
    @ConfigurationProperties("spring.datasource.form")
    public DataSourceProperties formDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    public DataSource formDataSource() {
        return formDataSourceProperties().initializeDataSourceBuilder().build();
    }

    // formFlywayMigrator 必須先跑完 migration，EntityManagerFactory 才能對著
    // 存在的表初始化。ddl-auto 是 none，因此這個順序是硬需求。
    @Bean
    @DependsOn("formFlywayMigrator")
    public LocalContainerEntityManagerFactoryBean formEntityManagerFactory(
            EntityManagerFactoryBuilder builder,
            @Qualifier("formDataSource") DataSource formDataSource) {
        return builder.dataSource(formDataSource)
                .packages("com.bpm.core.form.model")
                .persistenceUnit("form")
                .build();
    }

    /**
     * 表單交易管理器。
     *
     * <p>⚠️ {@code @Qualifier} 不可省略 —— 見類別註解。
     */
    @Bean
    public PlatformTransactionManager formTransactionManager(
            @Qualifier("formEntityManagerFactory")
            LocalContainerEntityManagerFactoryBean formEntityManagerFactory) {
        return new JpaTransactionManager(formEntityManagerFactory.getObject());
    }
}
