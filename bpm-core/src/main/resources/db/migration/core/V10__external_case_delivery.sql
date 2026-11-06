-- A binding version is provisioned once. Disable it for new submissions; retain history.
CREATE TABLE bpm_case_binding (
    workflow_code NVARCHAR(64) COLLATE Latin1_General_100_BIN2 NOT NULL,
    binding_version BIGINT NOT NULL,
    caller_system NVARCHAR(50) COLLATE Latin1_General_100_BIN2 NOT NULL,
    domain NVARCHAR(32) NOT NULL,
    workflow_role NVARCHAR(32) NOT NULL,
    definition_key NVARCHAR(128) NOT NULL,
    definition_id NVARCHAR(128) NOT NULL,
    form_id NVARCHAR(36),
    form_version INT,
    routing_rule NVARCHAR(64) NOT NULL,
    approval_permission NVARCHAR(128) NOT NULL,
    approved_end NVARCHAR(128),
    rejected_end NVARCHAR(128),
    returned_node NVARCHAR(128),
    enabled BIT NOT NULL,
    PRIMARY KEY (workflow_code, binding_version, caller_system),
    CHECK ((form_id IS NULL AND form_version IS NULL) OR (form_id IS NOT NULL AND form_version IS NOT NULL AND form_version > 0)),
    CHECK (binding_version BETWEEN 1 AND 9007199254740991),
    CHECK (approved_end <> rejected_end AND approved_end <> returned_node AND rejected_end <> returned_node)
);

CREATE TABLE bpm_case_request (
    system_id NVARCHAR(50) COLLATE Latin1_General_100_BIN2 NOT NULL,
    request_id NVARCHAR(36) COLLATE Latin1_General_100_BIN2 NOT NULL,
    business_key NVARCHAR(195) COLLATE Latin1_General_100_BIN2 NOT NULL,
    submission_revision BIGINT NOT NULL,
    request_hash NVARCHAR(64) NOT NULL,
    hash_version INT NOT NULL,
    workflow_code NVARCHAR(64) NOT NULL,
    binding_version BIGINT NOT NULL,
    definition_key NVARCHAR(128) NOT NULL,
    definition_id NVARCHAR(128) NOT NULL,
    form_id NVARCHAR(36),
    form_version INT,
    actor_json NVARCHAR(MAX) NOT NULL,
    trace_id NVARCHAR(128) NOT NULL,
    approved_end NVARCHAR(128),
    rejected_end NVARCHAR(128),
    returned_node NVARCHAR(128),
    state NVARCHAR(16) NOT NULL,
    error_code NVARCHAR(64),
    attempt_id NVARCHAR(36) NOT NULL,
    process_id NVARCHAR(128) COLLATE Latin1_General_100_BIN2,
    reserved_at DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
    status_signature NVARCHAR(128),
    source_revision BIGINT NOT NULL DEFAULT 0,
    updated_at DATETIME2(3) NOT NULL DEFAULT SYSUTCDATETIME(),
    PRIMARY KEY (system_id, request_id),
    UNIQUE (system_id, business_key),
    CHECK (state IN ('PROCESSING','CONFIRMED','REJECTED','UNCERTAIN'))
);
CREATE UNIQUE INDEX idx_case_request_process ON bpm_case_request(process_id) WHERE process_id IS NOT NULL;

GO
-- Existing receipts and retries must always refer to the same semantic binding.
CREATE TRIGGER trg_case_binding_version ON bpm_case_binding AFTER UPDATE, DELETE AS
BEGIN
    SET NOCOUNT ON;
    IF NOT EXISTS (SELECT 1 FROM inserted)
        OR UPDATE(workflow_code) OR UPDATE(binding_version) OR UPDATE(caller_system)
        OR UPDATE(domain) OR UPDATE(workflow_role) OR UPDATE(definition_key) OR UPDATE(definition_id)
        OR UPDATE(form_id) OR UPDATE(form_version) OR UPDATE(routing_rule) OR UPDATE(approval_permission)
        OR UPDATE(approved_end) OR UPDATE(rejected_end) OR UPDATE(returned_node)
        THROW 51010, 'Provision a new binding version or change enabled only.', 1;
END;
