package com.bpm.core.config;

import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.orm.jpa.EntityManagerFactoryBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

@Configuration
@EnableJpaRepositories(
        basePackages = {
                "com.bpm.core.repository"
        },
        entityManagerFactoryRef = "primaryEntityManagerFactory",
        transactionManagerRef = "primaryTransactionManager"
)
public class PrimaryDataSourceConfig {

    @Primary
    @Bean
    @ConfigurationProperties("spring.datasource")
    public DataSourceProperties primaryDataSourceProperties() {
        return new DataSourceProperties();
    }

    /**
     * ⚠️ {@code @ConfigurationProperties} 指向 hikari 前綴不可省略
     * （security-audit P2-3）。
     *
     * <p>{@code initializeDataSourceBuilder().build()} 只綁
     * url／username／password／driver —— {@code hikari.*} 的設定
     * <b>完全不生效</b>。
     *
     * <p>後果：三個池都跑預設 {@code maximumPoolSize=10}，而 primary 池同時要
     * 餵 web 執行緒<b>和</b> Flowable 的 async executor → 負載一上來 job
     * executor 會餓死 web 層。而運維在 yml 加參數會<b>靜默無效</b>，
     * 那種問題在事故現場極難診斷 —— 設定看起來就在那裡。
     *
     * <p>把 {@code @ConfigurationProperties} 放在 bean 方法上，Spring 會把該前綴
     * 綁到回傳的 {@code HikariDataSource} 上，這是 Boot 多 DataSource 的標準做法。
     */
    @Primary
    @Bean
    @ConfigurationProperties("spring.datasource.hikari")
    public DataSource primaryDataSource() {
        return primaryDataSourceProperties().initializeDataSourceBuilder()
                .type(com.zaxxer.hikari.HikariDataSource.class).build();
    }

    @Primary
    @Bean
    public LocalContainerEntityManagerFactoryBean primaryEntityManagerFactory(
            EntityManagerFactoryBuilder builder) {
        return builder.dataSource(primaryDataSource())
                .packages("com.bpm.core.model")
                .persistenceUnit("primary")
                .build();
    }

    // 這裡目前是靠 @Primary 湊巧拿到正確的 EMF。明確寫出 qualifier，
    // 免得日後有人移除 @Primary 時，重演稽核那個「靜默不寫入」的問題。
    @Primary
    @Bean
    public PlatformTransactionManager primaryTransactionManager(
            @Qualifier("primaryEntityManagerFactory")
            EntityManagerFactory primaryEntityManagerFactory) {
        return new JpaTransactionManager(primaryEntityManagerFactory);
    }
}
