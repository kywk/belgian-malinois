-- 表單定義的 seed 資料。
--
-- 原本由 form-service 的 spring.sql.init 執行（data.sql + separator: "@@"）。
-- Stage 3 併入 bpm-core 之後不能再這樣做：spring.sql.init 只作用於
-- **主** DataSource（bpm_core_db），而表單的表在 bpm_form_db。
--
-- 因此改為 Flyway migration。行為差異（刻意記錄）：
--   舊：mode: always → 每次啟動都跑一次 MERGE，改 data.sql 會在重啟後生效
--       （但 MERGE 只有 WHEN NOT MATCHED INSERT，所以既有列本來就不會更新）
--   新：只執行一次。之後要調整 seed 必須新增 migration。
-- 由於原本的 MERGE 就沒有 UPDATE 分支，既有列從來也不會被 data.sql 更新，
-- 因此這個差異在實務上不改變任何結果。
--
-- N'...' 前綴不可省略（見 V2／V4 的註解）：沒有它，中文會在寫入前就變成問號。

-- Initial form schemas for BPM platform
-- Uses MERGE to avoid duplicate inserts on restart

MERGE INTO bpm_form_definition AS target
USING (VALUES
  ('leave-request', 1, N'{"formKey":"leave-request","version":1,"mode":"edit","fields":[{"id":"leaveType","type":"select","label":"假別","required":true,"editableOnRevision":false,"options":[{"label":"特休","value":"annual"},{"label":"事假","value":"personal"},{"label":"病假","value":"sick"}]},{"id":"dateRange","type":"dateRange","label":"請假期間","required":true,"editableOnRevision":false},{"id":"reason","type":"textarea","label":"事由","required":true,"maxLength":500,"editableOnRevision":true}]}', 'published', N'請假申請表'),
  ('leave-review', 1, N'{"formKey":"leave-review","version":1,"mode":"review","fields":[{"id":"leaveType","type":"select","label":"假別","readonly":true,"options":[{"label":"特休","value":"annual"},{"label":"事假","value":"personal"},{"label":"病假","value":"sick"}]},{"id":"dateRange","type":"dateRange","label":"請假期間","readonly":true},{"id":"reason","type":"textarea","label":"事由","readonly":true}]}', 'published', N'請假審核表'),
  ('purchase-request', 1, N'{"formKey":"purchase-request","version":1,"mode":"edit","fields":[{"id":"itemName","type":"text","label":"品項名稱","required":true,"editableOnRevision":false},{"id":"quantity","type":"number","label":"數量","required":true,"min":1,"editableOnRevision":false},{"id":"amount","type":"number","label":"金額","required":true,"min":0,"precision":2,"editableOnRevision":false},{"id":"reason","type":"textarea","label":"採購事由","required":true,"maxLength":500,"editableOnRevision":true}]}', 'published', N'採購申請表'),
  ('purchase-review', 1, N'{"formKey":"purchase-review","version":1,"mode":"review","fields":[{"id":"itemName","type":"text","label":"品項名稱","readonly":true},{"id":"quantity","type":"number","label":"數量","readonly":true},{"id":"amount","type":"number","label":"金額","readonly":true},{"id":"reason","type":"textarea","label":"採購事由","readonly":true}]}', 'published', N'採購審核表')
) AS source (formKey, version, schemaJson, status, name)
ON target.form_key = source.formKey AND target.version = source.version
WHEN NOT MATCHED THEN
  INSERT (id, form_key, version, schema_json, status, name, created_by, created_at, updated_at)
  VALUES (NEWID(), source.formKey, source.version, source.schemaJson, source.status, source.name, 'system', GETUTCDATE(), GETUTCDATE());
