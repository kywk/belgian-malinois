package com.bpm.core.support;

import org.junit.jupiter.api.BeforeAll;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MSSQLServerContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

/**
 * 整合測試的共用基底：一組真實的 MSSQL／RabbitMQ／Redis 容器。
 *
 * <p><b>為什麼不用 H2。</b>本專案依賴的行為在 H2 上無法重現：
 * {@code DATETIMEOFFSET}、{@code NVARCHAR(MAX)}、{@code IDENTITY} 的語意、
 * {@code MERGE} 語法（form-service 的 data.sql 需要 {@code @@} 分隔符正是為此）、
 * 以及 {@code INSTEAD OF UPDATE/DELETE} 觸發器。稽核 hash chain 與表單版本鎖定
 * 剛好全部踩在這些行為上 —— 用 H2 測過的綠燈對這些功能毫無保證。
 *
 * <p><b>容器為何是 static。</b>MSSQL 容器啟動要數十秒。宣告成 static 讓所有
 * 繼承本類別的測試共用同一組容器（JVM 生命週期內只付一次啟動成本）。
 * 代價是測試之間共用資料庫狀態，因此每個測試必須自己負責隔離
 * —— 用 {@link #truncateAuditLog()} 這類工具方法，或以唯一的 businessKey 區隔。
 *
 * <p><b>三個 DB 的建立。</b>MSSQLServerContainer 只給一個預設實例，
 * 三個 DB（core／audit／form）由 {@link #createDatabases()} 在容器起來後建出來，
 * 等同 {@code infra/mssql/init-databases.sql} 在 dev 環境做的事。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc  // @SpringBootTest(MOCK) 本身不會建立 MockMvc bean
@ActiveProfiles("test")
public abstract class IntegrationTestBase {

    // 與 docker-compose.yml 同一個 image tag，避免「測試過了但 dev 壞掉」的版本落差。
    private static final MSSQLServerContainer<?> MSSQL =
            new MSSQLServerContainer<>(DockerImageName.parse("mcr.microsoft.com/mssql/server:2022-latest"))
                    .acceptLicense();

    private static final RabbitMQContainer RABBIT =
            new RabbitMQContainer(DockerImageName.parse("rabbitmq:3-management"));

    @SuppressWarnings("resource") // 容器生命週期與 JVM 相同，交由 Testcontainers 的 ryuk 回收
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    static {
        // 手動啟動而非 @Testcontainers/@Container：三個容器要在 @DynamicPropertySource
        // 被求值之前就緒，而 JUnit 的 @Container 生命週期晚於屬性解析。
        MSSQL.start();
        RABBIT.start();
        REDIS.start();
        createDatabases();
    }

    /** 建立三個應用資料庫（容器預設只有 master／tempdb）。 */
    private static void createDatabases() {
        try (Connection c = DriverManager.getConnection(
                MSSQL.getJdbcUrl(), MSSQL.getUsername(), MSSQL.getPassword());
             Statement st = c.createStatement()) {
            for (String db : new String[]{"bpm_core_db", "bpm_audit_db", "bpm_form_db"}) {
                st.execute("IF NOT EXISTS (SELECT name FROM sys.databases WHERE name = '" + db + "') "
                        + "CREATE DATABASE " + db);
            }
        } catch (Exception e) {
            throw new IllegalStateException("無法建立測試資料庫", e);
        }
    }

    private static String jdbc(String database) {
        // Testcontainers 給的 URL 指向預設 DB，改指定 databaseName。
        // encrypt=false 與 dev 一致；容器內自簽憑證無法驗證。
        return "jdbc:sqlserver://" + MSSQL.getHost() + ":" + MSSQL.getMappedPort(1433)
                + ";databaseName=" + database + ";encrypt=false;trustServerCertificate=true";
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> jdbc("bpm_core_db"));
        r.add("spring.datasource.username", MSSQL::getUsername);
        r.add("spring.datasource.password", MSSQL::getPassword);

        r.add("spring.datasource.audit.url", () -> jdbc("bpm_audit_db"));
        r.add("spring.datasource.audit.username", MSSQL::getUsername);
        r.add("spring.datasource.audit.password", MSSQL::getPassword);

        r.add("spring.rabbitmq.host", RABBIT::getHost);
        r.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        r.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        r.add("spring.rabbitmq.password", RABBIT::getAdminPassword);

        r.add("spring.data.redis.host", REDIS::getHost);
        r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired
    protected MockMvc mockMvc;

    @BeforeAll
    static void announce() {
        // 容器啟動較慢，留一行讓 CI log 看得出時間花在哪裡。
        System.out.println("[IntegrationTestBase] MSSQL=" + MSSQL.getMappedPort(1433)
                + " RabbitMQ=" + RABBIT.getAmqpPort() + " Redis=" + REDIS.getMappedPort(6379));
    }

    /** 直接對稽核 DB 執行 SQL（測試要驗證的是「真的寫進去了」，不能只信 repository）。 */
    protected static void withAuditConnection(ConnectionConsumer work) {
        try (Connection c = DriverManager.getConnection(
                jdbc("bpm_audit_db"), MSSQL.getUsername(), MSSQL.getPassword())) {
            work.accept(c);
        } catch (Exception e) {
            throw new IllegalStateException("稽核 DB 操作失敗", e);
        }
    }

    /**
     * 清空稽核表。
     *
     * <p>用 {@code DISABLE TRIGGER} 包起來是必要的 —— V2 的 append-only 觸發器
     * 會擋掉 DELETE，這正是它該做的事。測試需要乾淨起點，所以暫時停用再恢復。
     */
    protected static void truncateAuditLog() {
        withAuditConnection(c -> {
            try (Statement st = c.createStatement()) {
                st.execute("DISABLE TRIGGER trg_audit_log_no_delete ON bpm_audit_log");
                st.execute("DELETE FROM bpm_audit_log");
                st.execute("ENABLE TRIGGER trg_audit_log_no_delete ON bpm_audit_log");
            }
        });
    }

    @FunctionalInterface
    protected interface ConnectionConsumer {
        void accept(Connection c) throws Exception;
    }
}
