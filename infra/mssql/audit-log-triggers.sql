-- MSSQL append-only trigger for bpm_audit_log
-- Prevents UPDATE and DELETE operations on the audit log table
-- Run this after Hibernate creates the table (ddl-auto: update)

USE bpm_audit_db;
GO

CREATE OR ALTER TRIGGER trg_audit_log_no_update
ON bpm_audit_log
INSTEAD OF UPDATE
AS
BEGIN
    RAISERROR('UPDATE operations are not allowed on bpm_audit_log (append-only)', 16, 1);
    ROLLBACK TRANSACTION;
END;
GO

CREATE OR ALTER TRIGGER trg_audit_log_no_delete
ON bpm_audit_log
INSTEAD OF DELETE
AS
BEGIN
    RAISERROR('DELETE operations are not allowed on bpm_audit_log (append-only)', 16, 1);
    ROLLBACK TRANSACTION;
END;
GO
