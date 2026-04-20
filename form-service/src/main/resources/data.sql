-- Initial form schemas for BPM platform
-- Uses MERGE to avoid duplicate inserts on restart

MERGE INTO bpm_form_definition AS target
USING (VALUES
  ('leave-request', 1, N'{"formKey":"leave-request","version":1,"mode":"edit","fields":[{"id":"leaveType","type":"select","label":"假別","required":true,"editableOnRevision":false,"options":[{"label":"特休","value":"annual"},{"label":"事假","value":"personal"},{"label":"病假","value":"sick"}]},{"id":"dateRange","type":"dateRange","label":"請假期間","required":true,"editableOnRevision":false},{"id":"reason","type":"textarea","label":"事由","required":true,"maxLength":500,"editableOnRevision":true}]}', 'published', '請假申請表'),
  ('leave-review', 1, N'{"formKey":"leave-review","version":1,"mode":"review","fields":[{"id":"leaveType","type":"select","label":"假別","readonly":true,"options":[{"label":"特休","value":"annual"},{"label":"事假","value":"personal"},{"label":"病假","value":"sick"}]},{"id":"dateRange","type":"dateRange","label":"請假期間","readonly":true},{"id":"reason","type":"textarea","label":"事由","readonly":true}]}', 'published', '請假審核表'),
  ('purchase-request', 1, N'{"formKey":"purchase-request","version":1,"mode":"edit","fields":[{"id":"itemName","type":"text","label":"品項名稱","required":true,"editableOnRevision":false},{"id":"quantity","type":"number","label":"數量","required":true,"min":1,"editableOnRevision":false},{"id":"amount","type":"number","label":"金額","required":true,"min":0,"precision":2,"editableOnRevision":false},{"id":"reason","type":"textarea","label":"採購事由","required":true,"maxLength":500,"editableOnRevision":true}]}', 'published', '採購申請表'),
  ('purchase-review', 1, N'{"formKey":"purchase-review","version":1,"mode":"review","fields":[{"id":"itemName","type":"text","label":"品項名稱","readonly":true},{"id":"quantity","type":"number","label":"數量","readonly":true},{"id":"amount","type":"number","label":"金額","readonly":true},{"id":"reason","type":"textarea","label":"採購事由","readonly":true}]}', 'published', '採購審核表')
) AS source (formKey, version, schemaJson, status, name)
ON target.form_key = source.formKey AND target.version = source.version
WHEN NOT MATCHED THEN
  INSERT (id, form_key, version, schema_json, status, name, created_by, created_at, updated_at)
  VALUES (NEWID(), source.formKey, source.version, source.schemaJson, source.status, source.name, 'system', GETUTCDATE(), GETUTCDATE());
@@
