-- 稽核表的 append-only 觸發器。
--
-- 這份內容原本在 infra/mssql/audit-log-triggers.sql，註解寫著「Run this after
-- Hibernate creates the table」—— 也就是手動執行。實務上沒人會記得，結果是
-- 不可篡改性在每個新環境都預設關閉。改成 migration 後由 Flyway 保證必然套用。
--
-- ⚠️ 這只擋 UPDATE／DELETE。它不是完整的防篡改方案：
--    應用自己用 sa 連線，可以 DROP TRIGGER（見 security-audit P0-3 與 backlog R-04）。
--    真正的修補是給應用一個沒有 DDL 權限的帳號 —— 不在本 migration 範圍內。
--
-- 不使用 GO 分隔：GO 是 sqlcmd 的批次分隔符，不是 T-SQL 語法，Flyway 不認。
-- CREATE TRIGGER 必須是批次中的第一個敘述，因此本檔一個 migration 放一個觸發器
-- 是做不到的 —— 改用 EXEC sp_executesql 包起來，讓每個 CREATE 各自成為一個批次。

IF OBJECT_ID('trg_audit_log_no_update', 'TR') IS NOT NULL
    DROP TRIGGER trg_audit_log_no_update;

EXEC sp_executesql N'
CREATE TRIGGER trg_audit_log_no_update
ON bpm_audit_log
INSTEAD OF UPDATE
AS
BEGIN
    RAISERROR(''UPDATE operations are not allowed on bpm_audit_log (append-only)'', 16, 1);
    ROLLBACK TRANSACTION;
END;';

IF OBJECT_ID('trg_audit_log_no_delete', 'TR') IS NOT NULL
    DROP TRIGGER trg_audit_log_no_delete;

EXEC sp_executesql N'
CREATE TRIGGER trg_audit_log_no_delete
ON bpm_audit_log
INSTEAD OF DELETE
AS
BEGIN
    RAISERROR(''DELETE operations are not allowed on bpm_audit_log (append-only)'', 16, 1);
    ROLLBACK TRANSACTION;
END;';
