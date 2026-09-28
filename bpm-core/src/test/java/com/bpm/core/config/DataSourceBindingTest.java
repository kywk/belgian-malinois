package com.bpm.core.config;

import com.bpm.core.audit.model.AuditLog;
import com.bpm.core.audit.model.OperationType;
import com.bpm.core.audit.repository.AuditLogRepository;
import com.bpm.core.model.DocumentRequest;
import com.bpm.core.repository.DocumentRequestRepository;
import com.bpm.core.support.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 每個 repository 必須真的寫進它該寫的資料庫。
 *
 * <h2>這個測試要抓的是哪一類 bug</h2>
 *
 * <p>本專案最嚴重的一次缺陷（commit {@code cecdbe4}）就是這一類：
 * {@code auditTransactionManager} 以型別注入
 * {@code LocalContainerEntityManagerFactoryBean}，而
 * {@code primaryEntityManagerFactory} 帶 {@code @Primary} ——
 * 依型別注入時 {@code @Primary} 的優先序高於「參數名稱剛好相同」，
 * 所以稽核的交易管理器實際上建在 <b>primary 的 EMF</b> 上。
 *
 * <p>後果：交易開在 primary 的 EntityManager，而 repository 用的是 audit 的
 * EntityManager。兩者不同 → {@code persist()} 落在一個沒有交易的暫時
 * EntityManager 上，<b>永遠不會 flush</b>；交易則對著一個沒有變更的
 * EntityManager 正常 commit。
 *
 * <p><b>完全靜默</b>：沒有例外、沒有錯誤日誌、commit 還「成功」。
 * 稽核一筆都寫不進去，而所有業務操作都回 200。任何「只檢查 API 回應」
 * 的測試都看不出來。
 *
 * <h2>為什麼要「寫入後另開連線直接查該 DB」</h2>
 *
 * <p>只斷言 {@code save()} 有回傳、或 {@code count()} 增加，都可能被同一個
 * EntityManager 的 first-level cache 騙過去。唯一可靠的方式是<b>另開一條
 * JDBC 連線到預期的那個資料庫</b>，確認資料列真的在那裡。
 *
 * <p>⚠️ Stage 3 會加入第三個 persistence unit（form）。屆時在此補一組對應的
 * 斷言 —— 這個測試存在的目的就是讓那次變更不會重演 cecdbe4。
 */
class DataSourceBindingTest extends IntegrationTestBase {

    @Autowired
    @Qualifier("primaryDataSource")
    private DataSource primaryDataSource;

    @Autowired
    @Qualifier("auditDataSource")
    private DataSource auditDataSource;

    @Autowired
    @Qualifier("formDataSource")
    private DataSource formDataSource;

    @Autowired
    @Qualifier("primaryTransactionManager")
    private PlatformTransactionManager primaryTx;

    @Autowired
    @Qualifier("auditTransactionManager")
    private PlatformTransactionManager auditTx;

    @Autowired
    @Qualifier("formTransactionManager")
    private PlatformTransactionManager formTx;

    @Autowired
    private DocumentRequestRepository docRepo;

    @Autowired
    private AuditLogRepository auditLogRepo;

    @Autowired
    private com.bpm.core.form.repository.FormDefinitionRepository formDefRepo;

    private static String databaseOf(DataSource ds) throws Exception {
        try (Connection c = ds.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT DB_NAME()")) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static int countIn(DataSource ds, String sql) throws Exception {
        try (Connection c = ds.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    @Test
    @DisplayName("三個 DataSource 必須指向不同且正確的資料庫")
    void dataSourcesPointAtTheirOwnDatabases() throws Exception {
        assertThat(databaseOf(primaryDataSource)).isEqualTo("bpm_core_db");
        assertThat(databaseOf(auditDataSource)).isEqualTo("bpm_audit_db");
        // Stage 3：form 是第三個 persistence unit，資料模型仍分離（ADR-001）
        assertThat(databaseOf(formDataSource)).isEqualTo("bpm_form_db");
    }

    @Test
    @DisplayName("三個交易管理器必須互不相同（綁到同一個就是 cecdbe4）")
    void transactionManagersAreDistinct() {
        assertThat(auditTx)
                .as("稽核與主資料庫共用同一個交易管理器時，稽核寫入會靜默不落地")
                .isNotSameAs(primaryTx);
        assertThat(formTx)
                .as("表單與主資料庫共用交易管理器時，表單寫入會靜默不落地")
                .isNotSameAs(primaryTx);
        assertThat(formTx).isNotSameAs(auditTx);
    }

    @Test
    @DisplayName("表單 repository 必須寫進 bpm_form_db（Stage 3 新增的第三個 unit）")
    void formRepositoryWritesToFormDatabase() throws Exception {
        String key = "binding-" + UUID.randomUUID().toString().substring(0, 8);

        new TransactionTemplate(formTx).executeWithoutResult(s -> {
            var d = new com.bpm.core.form.model.FormDefinition();
            d.setFormKey(key);
            d.setName("綁定測試表單");
            d.setVersion(1);
            d.setStatus("draft");
            d.setSchemaJson("{}");
            formDefRepo.save(d);
        });

        assertThat(countIn(formDataSource,
                "SELECT COUNT(*) FROM bpm_form_definition WHERE form_key = '" + key + "'"))
                .as("資料列必須真的出現在 bpm_form_db")
                .isEqualTo(1);
        // 反向確認：表單的表不該出現在另外兩個 DB
        assertThat(countIn(primaryDataSource,
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES "
                        + "WHERE TABLE_NAME = 'bpm_form_definition'"))
                .as("bpm_core_db 不該有表單的表 —— 若有，代表 migration 套錯 DataSource")
                .isZero();
    }

    @Test
    @DisplayName("稽核 repository 必須寫進 bpm_audit_db")
    void auditRepositoryWritesToAuditDatabase() throws Exception {
        String marker = "binding-" + UUID.randomUUID();
        int before = countIn(auditDataSource,
                "SELECT COUNT(*) FROM bpm_audit_log WHERE task_id = '" + marker + "'");

        new TransactionTemplate(auditTx).executeWithoutResult(s -> {
            AuditLog log = new AuditLog();
            log.setOperationType(OperationType.DATA_ACCESS);
            log.setOperatorId("binding-test");
            log.setTaskId(marker);
            log.setHashValue("v2:" + "0".repeat(64));
            log.setCreatedAt(Instant.now());
            auditLogRepo.save(log);
        });

        assertThat(countIn(auditDataSource,
                "SELECT COUNT(*) FROM bpm_audit_log WHERE task_id = '" + marker + "'"))
                .as("資料列必須真的出現在 bpm_audit_db —— 這是 cecdbe4 唯一驗得出來的方式")
                .isEqualTo(before + 1);
    }

    @Test
    @DisplayName("主要 repository 必須寫進 bpm_core_db")
    void primaryRepositoryWritesToCoreDatabase() throws Exception {
        String marker = "BND-" + UUID.randomUUID().toString().substring(0, 8);

        new TransactionTemplate(primaryTx).executeWithoutResult(s -> {
            DocumentRequest d = new DocumentRequest();
            d.setDocumentNumber(marker);
            d.setTitle("綁定測試");
            d.setCreatedBy("binding-test");
            docRepo.save(d);
        });

        assertThat(countIn(primaryDataSource,
                "SELECT COUNT(*) FROM bpm_document_request WHERE document_number = '" + marker + "'"))
                .as("資料列必須真的出現在 bpm_core_db")
                .isEqualTo(1);

        assertThat(countIn(auditDataSource,
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES "
                        + "WHERE TABLE_NAME = 'bpm_document_request'"))
                .as("bpm_audit_db 不該有主資料庫的表 —— 若有，代表 schema 被寫錯位置")
                .isZero();
    }

    @Test
    @DisplayName("稽核 DB 只該有稽核表（schema 未被寫錯位置）")
    void auditDatabaseContainsOnlyAuditTables() throws Exception {
        assertThat(countIn(auditDataSource,
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES "
                        + "WHERE TABLE_NAME LIKE 'ACT[_]%' OR TABLE_NAME LIKE 'FLW[_]%'"))
                .as("Flowable 的表屬於 bpm_core_db")
                .isZero();
        assertThat(countIn(auditDataSource,
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES "
                        + "WHERE TABLE_NAME LIKE 'bpm[_]%' AND TABLE_NAME <> 'bpm_audit_log'"))
                .as("稽核 DB 只該有 bpm_audit_log")
                .isZero();
        assertThat(countIn(formDataSource,
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES "
                        + "WHERE TABLE_NAME LIKE 'ACT[_]%' OR TABLE_NAME LIKE 'FLW[_]%' "
                        + "OR TABLE_NAME = 'bpm_audit_log'"))
                .as("表單 DB 只該有表單的表")
                .isZero();
    }
}
