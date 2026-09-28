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
-- 注意：bpm_form_definition.name 已於 V2 轉為 NVARCHAR，因此不在下方清單內。

-- ── 1. 卸除涵蓋 VARCHAR 欄位的約束與索引 ──────────────────────
IF EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'idx_form_data_process' AND object_id = OBJECT_ID('bpm_form_data'))
    DROP INDEX idx_form_data_process ON bpm_form_data;

SET @n = NULL;
SELECT @n = name FROM sys.indexes WHERE object_id = OBJECT_ID('bpm_form_data') AND is_primary_key = 1;
IF @n IS NOT NULL
BEGIN
    SET @s = N'ALTER TABLE bpm_form_data DROP CONSTRAINT [' + @n + N']';
    EXEC sp_executesql @s;
END

SET @n = NULL;
SELECT @n = name FROM sys.indexes WHERE object_id = OBJECT_ID('bpm_form_definition') AND is_unique_constraint = 1;
IF @n IS NOT NULL
BEGIN
    SET @s = N'ALTER TABLE bpm_form_definition DROP CONSTRAINT [' + @n + N']';
    EXEC sp_executesql @s;
END

SET @n = NULL;
SELECT @n = name FROM sys.indexes WHERE object_id = OBJECT_ID('bpm_form_definition') AND is_primary_key = 1;
IF @n IS NOT NULL
BEGIN
    SET @s = N'ALTER TABLE bpm_form_definition DROP CONSTRAINT [' + @n + N']';
    EXEC sp_executesql @s;
END

-- ── 2. 欄位轉型（保留長度與可空性）──────────────────────────

-- bpm_form_data
ALTER TABLE bpm_form_data ALTER COLUMN id NVARCHAR(255) NOT NULL;
ALTER TABLE bpm_form_data ALTER COLUMN form_definition_id NVARCHAR(255) NOT NULL;
ALTER TABLE bpm_form_data ALTER COLUMN process_instance_id NVARCHAR(255) NOT NULL;
ALTER TABLE bpm_form_data ALTER COLUMN submitted_by NVARCHAR(255) NULL;
ALTER TABLE bpm_form_data ALTER COLUMN task_id NVARCHAR(255) NULL;

-- bpm_form_definition
ALTER TABLE bpm_form_definition ALTER COLUMN id NVARCHAR(255) NOT NULL;
ALTER TABLE bpm_form_definition ALTER COLUMN created_by NVARCHAR(255) NULL;
ALTER TABLE bpm_form_definition ALTER COLUMN form_key NVARCHAR(100) NOT NULL;
ALTER TABLE bpm_form_definition ALTER COLUMN status NVARCHAR(20) NOT NULL;

-- ── 3. 以決定性名稱重建 ─────────────────────────────────────
ALTER TABLE bpm_form_data ADD CONSTRAINT pk_bpm_form_data PRIMARY KEY (id);
CREATE INDEX idx_form_data_process ON bpm_form_data (process_instance_id);
ALTER TABLE bpm_form_definition ADD CONSTRAINT pk_bpm_form_definition PRIMARY KEY (id);
ALTER TABLE bpm_form_definition ADD CONSTRAINT uk_bpm_form_definition_key_version UNIQUE (form_key, version);
