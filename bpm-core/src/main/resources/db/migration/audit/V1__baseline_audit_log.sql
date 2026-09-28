-- Baseline：bpm_audit_db 的稽核表。
--
-- 這是第二個 DataSource（auditDataSource）。Spring Boot 的 Flyway 自動配置只認
-- 主 DataSource，因此本目錄由 config/AuditFlywayConfig.java 手動建立的 Flyway
-- bean 負責，且必須在 auditEntityManagerFactory 之前執行。
--
-- ⚠️ created_at / hash_value / operation_type 為 NOT NULL —— 稽核記錄若缺這三者
--    就失去意義（沒有時間、沒有鏈結、沒有操作別）。id 為 IDENTITY，
--    hash chain 的順序依賴它遞增。

--
-- ⚠️ 為何每個 CREATE 都包存在性判斷（而不是裸 CREATE TABLE）：
--    這個 schema 不只有我們的表 —— Flowable 會在同一個 DB 建 ACT_*／FLW_*。
--    若 Flowable 的 schema 建立先於 Flyway，schema 就已經「非空」，
--    Flyway 的 baseline-on-migrate 會把 V1 直接標記為已套用而不執行，
--    結果是應用自有的表永遠不會被建出來（且沒有任何錯誤）。
--    因此本檔設計成幂等：搭配 baseline-version: 0，V1 一定會執行，
--    已存在的表跳過、缺少的表補建。三種情境都正確：
--      (a) 既有 dev DB（ddl-auto 已建好）→ 全部跳過
--      (b) 全新 DB 但 Flowable 先跑 → 補建應用表
--      (c) 全新空 DB → 全部建立

IF OBJECT_ID('bpm_audit_log', 'U') IS NULL
EXEC sp_executesql N'
CREATE TABLE bpm_audit_log (
    id                      BIGINT          IDENTITY(1,1) NOT NULL,
    business_key            VARCHAR(255)        NULL,
    created_at              DATETIMEOFFSET  NOT NULL,
    detail                  NVARCHAR(MAX)       NULL,
    hash_value              VARCHAR(64)     NOT NULL,
    ip_address              VARCHAR(255)        NULL,
    new_state               NVARCHAR(MAX)       NULL,
    operation_type          VARCHAR(30)     NOT NULL,
    operator_id             VARCHAR(255)        NULL,
    operator_name           VARCHAR(255)        NULL,
    operator_source         VARCHAR(20)         NULL,
    previous_hash           VARCHAR(64)         NULL,
    previous_state          NVARCHAR(MAX)       NULL,
    process_definition_key  VARCHAR(255)        NULL,
    process_instance_id     VARCHAR(255)        NULL,
    task_id                 VARCHAR(255)        NULL,
    trace_id                VARCHAR(255)        NULL,
    user_agent              VARCHAR(255)        NULL,
    CONSTRAINT pk_bpm_audit_log PRIMARY KEY (id)
);';

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'idx_audit_created' AND object_id = OBJECT_ID('bpm_audit_log'))
CREATE INDEX idx_audit_created  ON bpm_audit_log (created_at);
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'idx_audit_operator' AND object_id = OBJECT_ID('bpm_audit_log'))
CREATE INDEX idx_audit_operator ON bpm_audit_log (operator_id);
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'idx_audit_process' AND object_id = OBJECT_ID('bpm_audit_log'))
CREATE INDEX idx_audit_process  ON bpm_audit_log (process_instance_id);
