-- Create databases for BPM platform
IF NOT EXISTS (SELECT name FROM sys.databases WHERE name = 'bpm_core_db')
    CREATE DATABASE bpm_core_db;
GO

IF NOT EXISTS (SELECT name FROM sys.databases WHERE name = 'bpm_form_db')
    CREATE DATABASE bpm_form_db;
GO

IF NOT EXISTS (SELECT name FROM sys.databases WHERE name = 'bpm_audit_db')
    CREATE DATABASE bpm_audit_db;
GO
