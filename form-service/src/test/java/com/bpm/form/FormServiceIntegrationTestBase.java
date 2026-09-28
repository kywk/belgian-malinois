package com.bpm.form;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MSSQLServerContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

/**
 * form-service 整合測試的共用基底。
 *
 * <p>與 bpm-core 的 IntegrationTestBase 同樣的理由不用 H2 —— 本模組的 seed
 * 依賴 MSSQL 專屬語法：{@code MERGE}（需要 {@code spring.sql.init.separator: "@@"}）
 * 以及 {@code N'...'} 的 NVARCHAR 字面值。這兩者在 H2 上的行為都不同，
 * 而「中文會不會變成問號」剛好完全取決於後者。
 *
 * <p>本模組只有一個 DataSource，因此不需要像 bpm-core 那樣手動驅動第二個 Flyway。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
public abstract class FormServiceIntegrationTestBase {

    // 與 docker-compose.yml 同一個 image tag，避免測試與 dev 的版本落差。
    private static final MSSQLServerContainer<?> MSSQL =
            new MSSQLServerContainer<>(DockerImageName.parse("mcr.microsoft.com/mssql/server:2022-latest"))
                    .acceptLicense();

    static {
        MSSQL.start();
        createDatabase();
    }

    private static void createDatabase() {
        try (Connection c = DriverManager.getConnection(
                MSSQL.getJdbcUrl(), MSSQL.getUsername(), MSSQL.getPassword());
             Statement st = c.createStatement()) {
            st.execute("IF NOT EXISTS (SELECT name FROM sys.databases WHERE name = 'bpm_form_db') "
                    + "CREATE DATABASE bpm_form_db");
        } catch (Exception e) {
            throw new IllegalStateException("無法建立測試資料庫", e);
        }
    }

    protected static String jdbcUrl() {
        return "jdbc:sqlserver://" + MSSQL.getHost() + ":" + MSSQL.getMappedPort(1433)
                + ";databaseName=bpm_form_db;encrypt=false;trustServerCertificate=true";
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", FormServiceIntegrationTestBase::jdbcUrl);
        r.add("spring.datasource.username", MSSQL::getUsername);
        r.add("spring.datasource.password", MSSQL::getPassword);
    }

    /** 直接對 DB 查詢（要驗證的是「真的存成什麼」，不能只信 API 回傳）。 */
    protected static void withConnection(ConnectionConsumer work) {
        try (Connection c = DriverManager.getConnection(
                jdbcUrl(), MSSQL.getUsername(), MSSQL.getPassword())) {
            work.accept(c);
        } catch (Exception e) {
            throw new IllegalStateException("DB 操作失敗", e);
        }
    }

    @FunctionalInterface
    protected interface ConnectionConsumer {
        void accept(Connection c) throws Exception;
    }
}
