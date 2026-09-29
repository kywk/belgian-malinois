-- Baseline：bpm_form_db 的表單定義與表單資料。
--
-- 從 ddl-auto: update 產生的實際 schema 反向擷取。
--
-- 附帶確認一件 CLAUDE.md 記為「潛在 bug」的事（已技術債 #9）：
-- FormDefinition 的 @UniqueConstraint(columnNames = {"formKey","version"}) 用 camelCase，
-- 而 data.sql 用 snake_case。在乾淨 DB 上實測，Hibernate 正確解析成
-- (form_key, version) —— 這不是 bug，此處照實反映。

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

IF OBJECT_ID('bpm_form_definition', 'U') IS NULL
EXEC sp_executesql N'
CREATE TABLE bpm_form_definition (
    id                      VARCHAR(255)    NOT NULL,
    created_at              DATETIMEOFFSET      NULL,
    created_by              VARCHAR(255)        NULL,
    form_key                VARCHAR(100)    NOT NULL,
    name                    VARCHAR(255)    NOT NULL,
    schema_json             NVARCHAR(MAX)       NULL,
    status                  VARCHAR(20)     NOT NULL,
    updated_at              DATETIMEOFFSET      NULL,
    version                 INT             NOT NULL,
    CONSTRAINT pk_bpm_form_definition PRIMARY KEY (id),
    CONSTRAINT uk_bpm_form_definition_key_version UNIQUE (form_key, version)
);';

IF OBJECT_ID('bpm_form_data', 'U') IS NULL
EXEC sp_executesql N'
CREATE TABLE bpm_form_data (
    id                      VARCHAR(255)    NOT NULL,
    data_json               NVARCHAR(MAX)       NULL,
    form_definition_id      VARCHAR(255)    NOT NULL,
    process_instance_id     VARCHAR(255)    NOT NULL,
    submitted_at            DATETIMEOFFSET      NULL,
    submitted_by            VARCHAR(255)        NULL,
    task_id                 VARCHAR(255)        NULL,
    CONSTRAINT pk_bpm_form_data PRIMARY KEY (id)
);';
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'idx_form_data_process' AND object_id = OBJECT_ID('bpm_form_data'))
CREATE INDEX idx_form_data_process ON bpm_form_data (process_instance_id);
