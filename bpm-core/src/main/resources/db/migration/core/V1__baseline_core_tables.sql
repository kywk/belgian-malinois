-- Baseline：bpm_core_db 的「應用自有」資料表。
--
-- 這份 baseline 是從 ddl-auto: update 所產生的實際 schema 反向擷取的，
-- 因此與既有 dev 環境逐欄一致（既有 DB 以 baseline-on-migrate 標記為已套用，不會重跑）。
--
-- ⚠️ 刻意不納入 Flowable 自己的表。Flowable 管的不只是 ACT_*（39 張），
--    還有 FLW_*（FLW_EVENT_*／FLW_CHANNEL_DEFINITION／FLW_RU_BATCH*／
--    FLW_EV_DATABASECHANGELOG*，共 8 張，事件註冊表用它自己的 Liquibase）。
--    兩者都由 flowable.database-schema-update 建立與升級 —— 納入 Flyway 會在
--    Flowable 6→7 升級時與它的 changelog 互相打架。
--
-- 約束命名：Hibernate 產生的是隨機後綴（PK__bpm_docu__3213E83F88FFBD8A、
-- UKnph9fl3vdlll11fvrgwkj7ekw…）。此處改為決定性名稱，讓 CI／Testcontainers
-- 建出來的 schema 每次都一樣、diff 可讀。既有 dev DB 不受影響（不會重跑本檔）。

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

IF OBJECT_ID('bpm_document_request', 'U') IS NULL
EXEC sp_executesql N'
CREATE TABLE bpm_document_request (
    id                      VARCHAR(255)    NOT NULL,
    category                VARCHAR(255)        NULL,
    created_at              DATETIMEOFFSET      NULL,
    created_by              VARCHAR(255)        NULL,
    document_number         VARCHAR(30)     NOT NULL,
    process_instance_id     VARCHAR(255)        NULL,
    title                   VARCHAR(255)    NOT NULL,
    urgency_level           VARCHAR(20)         NULL,
    CONSTRAINT pk_bpm_document_request PRIMARY KEY (id),
    CONSTRAINT uk_bpm_document_request_number UNIQUE (document_number)
);';

IF OBJECT_ID('bpm_external_system', 'U') IS NULL
EXEC sp_executesql N'
CREATE TABLE bpm_external_system (
    id                      VARCHAR(255)    NOT NULL,
    allowed_actions         NVARCHAR(MAX)       NULL,
    allowed_process_keys    NVARCHAR(MAX)       NULL,
    api_key                 VARCHAR(64)     NOT NULL,
    callback_url            VARCHAR(255)        NULL,
    contact_email           VARCHAR(255)        NULL,
    created_at              DATETIMEOFFSET      NULL,
    enabled                 BIT             NOT NULL,
    ip_whitelist            VARCHAR(255)        NULL,
    last_used_at            DATETIMEOFFSET      NULL,
    system_id               VARCHAR(50)     NOT NULL,
    system_name             VARCHAR(255)    NOT NULL,
    CONSTRAINT pk_bpm_external_system PRIMARY KEY (id),
    CONSTRAINT uk_bpm_external_system_system_id UNIQUE (system_id)
);';

IF OBJECT_ID('bpm_file_attachment', 'U') IS NULL
EXEC sp_executesql N'
CREATE TABLE bpm_file_attachment (
    id                      VARCHAR(255)    NOT NULL,
    content_type            VARCHAR(255)        NULL,
    file_name               VARCHAR(255)    NOT NULL,
    file_path               VARCHAR(255)    NOT NULL,
    file_size               BIGINT              NULL,
    process_instance_id     VARCHAR(255)    NOT NULL,
    task_id                 VARCHAR(255)        NULL,
    uploaded_at             DATETIMEOFFSET      NULL,
    uploaded_by             VARCHAR(255)        NULL,
    CONSTRAINT pk_bpm_file_attachment PRIMARY KEY (id)
);';
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'idx_attach_process' AND object_id = OBJECT_ID('bpm_file_attachment'))
CREATE INDEX idx_attach_process ON bpm_file_attachment (process_instance_id);

IF OBJECT_ID('bpm_notify_template', 'U') IS NULL
EXEC sp_executesql N'
CREATE TABLE bpm_notify_template (
    id                      VARCHAR(255)    NOT NULL,
    body_template           NVARCHAR(MAX)       NULL,
    channel                 VARCHAR(30)     NOT NULL,
    name                    VARCHAR(255)    NOT NULL,
    subject_template        VARCHAR(255)        NULL,
    CONSTRAINT pk_bpm_notify_template PRIMARY KEY (id)
);';

IF OBJECT_ID('bpm_notify_config', 'U') IS NULL
EXEC sp_executesql N'
CREATE TABLE bpm_notify_config (
    id                      VARCHAR(255)    NOT NULL,
    channel                 VARCHAR(30)     NOT NULL,
    enabled                 BIT             NOT NULL,
    event_type              VARCHAR(30)     NOT NULL,
    process_definition_key  VARCHAR(255)    NOT NULL,
    template_id             VARCHAR(255)        NULL,
    CONSTRAINT pk_bpm_notify_config PRIMARY KEY (id),
    CONSTRAINT uk_bpm_notify_config_key_event_channel
        UNIQUE (process_definition_key, event_type, channel)
);';

IF OBJECT_ID('bpm_process_variable_spec', 'U') IS NULL
EXEC sp_executesql N'
CREATE TABLE bpm_process_variable_spec (
    id                      VARCHAR(255)    NOT NULL,
    description             VARCHAR(255)        NULL,
    example                 VARCHAR(255)        NULL,
    process_definition_key  VARCHAR(100)    NOT NULL,
    required                BIT             NOT NULL,
    variable_name           VARCHAR(100)    NOT NULL,
    variable_type           VARCHAR(20)     NOT NULL,
    CONSTRAINT pk_bpm_process_variable_spec PRIMARY KEY (id),
    CONSTRAINT uk_bpm_process_variable_spec_key_name
        UNIQUE (process_definition_key, variable_name)
);';
