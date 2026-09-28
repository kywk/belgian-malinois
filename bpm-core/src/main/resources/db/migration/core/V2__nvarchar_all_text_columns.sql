-- 全 schema 的 VARCHAR 文字欄位改為 NVARCHAR。
--
-- ## 為什麼要做
--
-- 三個 DB 的定序都是 SQL_Latin1_General_CP1_CI_AS，而 Hibernate 把沒有明確
-- columnDefinition 的 String 欄位建成 VARCHAR。這造成兩個獨立的問題：
--
-- 1. **中文被靜默破壞**。寫入 VARCHAR 的非 ASCII 字元直接變成問號，
--    而且不報任何錯誤。已實際造成：表單名稱全是 ?????、通知信主旨全是亂碼、
--    以及（最嚴重）稽核 operator_name 的中文姓名毀損後，v2 hash 涵蓋該欄位
--    使 integrityCheck 把每一筆都誤報為遭篡改。
--    這對一個繁中企業系統是資料正確性問題，不是外觀問題。
--
-- 2. **索引無法 seek**。JDBC 的 sendStringParametersAsUnicode 預設為 true，
--    因此應用送出的字串參數是 NVARCHAR。NVARCHAR 參數與 VARCHAR 欄位比較時，
--    SQL Server 依型別優先序把「欄位」轉成 NVARCHAR —— 轉換發生在欄位側，
--    索引因此無法 seek，只能掃描。process_instance_id、operator_id、form_key、
--    system_id 這些查詢熱點目前全都踩在這上面。
--    統一改成 NVARCHAR 同時解決正確性與這個效能陷阱。
--
-- ## 為什麼連 id / hash / IP 這類純 ASCII 欄位也一起轉
--
-- 因為第 2 點。findById 是最頻繁的查詢，PK 若留 VARCHAR 就會一直吃轉換。
-- 代價是儲存與索引鍵變成兩倍寬，但這些欄位都遠小於 SQL Server 的
-- 1700 bytes 索引鍵上限（最寬的是 NVARCHAR(255) = 510 bytes，
-- 最寬的複合唯一鍵 process_definition_key+event_type+channel = 630 bytes）。
--
-- ## 約束名稱為何用動態查詢卸除、用固定名稱重建
--
-- 既有 dev 環境的 schema 是 ddl-auto 建的，約束名稱是 Hibernate 的隨機後綴
-- （PK__bpm_docu__3213E83F88FFBD8A、UKnph9fl3vdlll11fvrgwkj7ekw…）；
-- 而全新環境是 V1 baseline 建的，名稱是決定性的（pk_*／uk_*）。
-- 因此卸除時只能依「表 + 約束類型」動態查名，重建時統一用決定性名稱
-- —— 這個 migration 也順帶讓兩種環境的約束命名收斂為一致。

DECLARE @n SYSNAME, @s NVARCHAR(MAX);

-- ── 1. 卸除涵蓋 VARCHAR 欄位的約束與索引 ──────────────────────

SET @n = NULL;
SELECT @n = name FROM sys.indexes WHERE object_id = OBJECT_ID('bpm_document_request') AND is_unique_constraint = 1;
IF @n IS NOT NULL
BEGIN
    SET @s = N'ALTER TABLE bpm_document_request DROP CONSTRAINT [' + @n + N']';
    EXEC sp_executesql @s;
END

SET @n = NULL;
SELECT @n = name FROM sys.indexes WHERE object_id = OBJECT_ID('bpm_document_request') AND is_primary_key = 1;
IF @n IS NOT NULL
BEGIN
    SET @s = N'ALTER TABLE bpm_document_request DROP CONSTRAINT [' + @n + N']';
    EXEC sp_executesql @s;
END

SET @n = NULL;
SELECT @n = name FROM sys.indexes WHERE object_id = OBJECT_ID('bpm_external_system') AND is_unique_constraint = 1;
IF @n IS NOT NULL
BEGIN
    SET @s = N'ALTER TABLE bpm_external_system DROP CONSTRAINT [' + @n + N']';
    EXEC sp_executesql @s;
END

SET @n = NULL;
SELECT @n = name FROM sys.indexes WHERE object_id = OBJECT_ID('bpm_external_system') AND is_primary_key = 1;
IF @n IS NOT NULL
BEGIN
    SET @s = N'ALTER TABLE bpm_external_system DROP CONSTRAINT [' + @n + N']';
    EXEC sp_executesql @s;
END
IF EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'idx_attach_process' AND object_id = OBJECT_ID('bpm_file_attachment'))
    DROP INDEX idx_attach_process ON bpm_file_attachment;

SET @n = NULL;
SELECT @n = name FROM sys.indexes WHERE object_id = OBJECT_ID('bpm_file_attachment') AND is_primary_key = 1;
IF @n IS NOT NULL
BEGIN
    SET @s = N'ALTER TABLE bpm_file_attachment DROP CONSTRAINT [' + @n + N']';
    EXEC sp_executesql @s;
END

SET @n = NULL;
SELECT @n = name FROM sys.indexes WHERE object_id = OBJECT_ID('bpm_notify_config') AND is_unique_constraint = 1;
IF @n IS NOT NULL
BEGIN
    SET @s = N'ALTER TABLE bpm_notify_config DROP CONSTRAINT [' + @n + N']';
    EXEC sp_executesql @s;
END

SET @n = NULL;
SELECT @n = name FROM sys.indexes WHERE object_id = OBJECT_ID('bpm_notify_config') AND is_primary_key = 1;
IF @n IS NOT NULL
BEGIN
    SET @s = N'ALTER TABLE bpm_notify_config DROP CONSTRAINT [' + @n + N']';
    EXEC sp_executesql @s;
END

SET @n = NULL;
SELECT @n = name FROM sys.indexes WHERE object_id = OBJECT_ID('bpm_notify_template') AND is_primary_key = 1;
IF @n IS NOT NULL
BEGIN
    SET @s = N'ALTER TABLE bpm_notify_template DROP CONSTRAINT [' + @n + N']';
    EXEC sp_executesql @s;
END

SET @n = NULL;
SELECT @n = name FROM sys.indexes WHERE object_id = OBJECT_ID('bpm_process_variable_spec') AND is_unique_constraint = 1;
IF @n IS NOT NULL
BEGIN
    SET @s = N'ALTER TABLE bpm_process_variable_spec DROP CONSTRAINT [' + @n + N']';
    EXEC sp_executesql @s;
END

SET @n = NULL;
SELECT @n = name FROM sys.indexes WHERE object_id = OBJECT_ID('bpm_process_variable_spec') AND is_primary_key = 1;
IF @n IS NOT NULL
BEGIN
    SET @s = N'ALTER TABLE bpm_process_variable_spec DROP CONSTRAINT [' + @n + N']';
    EXEC sp_executesql @s;
END

-- ── 2. 欄位轉型（保留長度與可空性）──────────────────────────

-- bpm_document_request
ALTER TABLE bpm_document_request ALTER COLUMN id NVARCHAR(255) NOT NULL;
ALTER TABLE bpm_document_request ALTER COLUMN category NVARCHAR(255) NULL;
ALTER TABLE bpm_document_request ALTER COLUMN created_by NVARCHAR(255) NULL;
ALTER TABLE bpm_document_request ALTER COLUMN document_number NVARCHAR(30) NOT NULL;
ALTER TABLE bpm_document_request ALTER COLUMN process_instance_id NVARCHAR(255) NULL;
ALTER TABLE bpm_document_request ALTER COLUMN title NVARCHAR(255) NOT NULL;
ALTER TABLE bpm_document_request ALTER COLUMN urgency_level NVARCHAR(20) NULL;

-- bpm_external_system
ALTER TABLE bpm_external_system ALTER COLUMN id NVARCHAR(255) NOT NULL;
ALTER TABLE bpm_external_system ALTER COLUMN api_key NVARCHAR(64) NOT NULL;
ALTER TABLE bpm_external_system ALTER COLUMN callback_url NVARCHAR(255) NULL;
ALTER TABLE bpm_external_system ALTER COLUMN contact_email NVARCHAR(255) NULL;
ALTER TABLE bpm_external_system ALTER COLUMN ip_whitelist NVARCHAR(255) NULL;
ALTER TABLE bpm_external_system ALTER COLUMN system_id NVARCHAR(50) NOT NULL;
ALTER TABLE bpm_external_system ALTER COLUMN system_name NVARCHAR(255) NOT NULL;

-- bpm_file_attachment
ALTER TABLE bpm_file_attachment ALTER COLUMN id NVARCHAR(255) NOT NULL;
ALTER TABLE bpm_file_attachment ALTER COLUMN content_type NVARCHAR(255) NULL;
ALTER TABLE bpm_file_attachment ALTER COLUMN file_name NVARCHAR(255) NOT NULL;
ALTER TABLE bpm_file_attachment ALTER COLUMN file_path NVARCHAR(255) NOT NULL;
ALTER TABLE bpm_file_attachment ALTER COLUMN process_instance_id NVARCHAR(255) NOT NULL;
ALTER TABLE bpm_file_attachment ALTER COLUMN task_id NVARCHAR(255) NULL;
ALTER TABLE bpm_file_attachment ALTER COLUMN uploaded_by NVARCHAR(255) NULL;

-- bpm_notify_config
ALTER TABLE bpm_notify_config ALTER COLUMN id NVARCHAR(255) NOT NULL;
ALTER TABLE bpm_notify_config ALTER COLUMN channel NVARCHAR(30) NOT NULL;
ALTER TABLE bpm_notify_config ALTER COLUMN event_type NVARCHAR(30) NOT NULL;
ALTER TABLE bpm_notify_config ALTER COLUMN process_definition_key NVARCHAR(255) NOT NULL;
ALTER TABLE bpm_notify_config ALTER COLUMN template_id NVARCHAR(255) NULL;

-- bpm_notify_template
ALTER TABLE bpm_notify_template ALTER COLUMN id NVARCHAR(255) NOT NULL;
ALTER TABLE bpm_notify_template ALTER COLUMN channel NVARCHAR(30) NOT NULL;
ALTER TABLE bpm_notify_template ALTER COLUMN name NVARCHAR(255) NOT NULL;
ALTER TABLE bpm_notify_template ALTER COLUMN subject_template NVARCHAR(255) NULL;

-- bpm_process_variable_spec
ALTER TABLE bpm_process_variable_spec ALTER COLUMN id NVARCHAR(255) NOT NULL;
ALTER TABLE bpm_process_variable_spec ALTER COLUMN description NVARCHAR(255) NULL;
ALTER TABLE bpm_process_variable_spec ALTER COLUMN example NVARCHAR(255) NULL;
ALTER TABLE bpm_process_variable_spec ALTER COLUMN process_definition_key NVARCHAR(100) NOT NULL;
ALTER TABLE bpm_process_variable_spec ALTER COLUMN variable_name NVARCHAR(100) NOT NULL;
ALTER TABLE bpm_process_variable_spec ALTER COLUMN variable_type NVARCHAR(20) NOT NULL;

-- ── 3. 以決定性名稱重建 ─────────────────────────────────────
ALTER TABLE bpm_document_request ADD CONSTRAINT pk_bpm_document_request PRIMARY KEY (id);
ALTER TABLE bpm_document_request ADD CONSTRAINT uk_bpm_document_request_number UNIQUE (document_number);
ALTER TABLE bpm_external_system ADD CONSTRAINT pk_bpm_external_system PRIMARY KEY (id);
ALTER TABLE bpm_external_system ADD CONSTRAINT uk_bpm_external_system_system_id UNIQUE (system_id);
ALTER TABLE bpm_file_attachment ADD CONSTRAINT pk_bpm_file_attachment PRIMARY KEY (id);
CREATE INDEX idx_attach_process ON bpm_file_attachment (process_instance_id);
ALTER TABLE bpm_notify_config ADD CONSTRAINT pk_bpm_notify_config PRIMARY KEY (id);
ALTER TABLE bpm_notify_config ADD CONSTRAINT uk_bpm_notify_config_key_event_channel UNIQUE (process_definition_key, event_type, channel);
ALTER TABLE bpm_notify_template ADD CONSTRAINT pk_bpm_notify_template PRIMARY KEY (id);
ALTER TABLE bpm_process_variable_spec ADD CONSTRAINT pk_bpm_process_variable_spec PRIMARY KEY (id);
ALTER TABLE bpm_process_variable_spec ADD CONSTRAINT uk_bpm_process_variable_spec_key_name UNIQUE (process_definition_key, variable_name);
