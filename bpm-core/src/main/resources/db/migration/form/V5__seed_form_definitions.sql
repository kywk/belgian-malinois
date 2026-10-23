-- 開發期初始化：直接建立 UniForm DSL v1 示範表單；更新格式時清除測試資料後重建。
-- 審核／補件共用主表，欄位權限由 BPMN uniform:access 定義。

INSERT INTO bpm_form_definition (id, form_key, version, schema_json, status, name, created_by, created_at, updated_at)
VALUES
  (NEWID(), 'leave-request', 1,
   N'{"$uniform":"1.0","key":"leave-request","title":{"zh-TW":"請假申請表"},"requires":{"core":"^1.0"},"root":[{"id":"n_leaveType","type":"select","key":"leaveType","label":"假別","required":true,"props":{"options":[{"value":"annual","label":"特休"},{"value":"personal","label":"事假"},{"value":"sick","label":"病假"}]}},{"id":"n_dateRange","type":"dateRange","key":"dateRange","label":"請假期間","required":true},{"id":"n_reason","type":"textarea","key":"reason","label":"事由","required":true,"props":{"maxLength":500}}]}',
   'published', N'請假申請表', 'system', GETUTCDATE(), GETUTCDATE()),
  (NEWID(), 'purchase-request', 1,
   N'{"$uniform":"1.0","key":"purchase-request","title":{"zh-TW":"採購申請表"},"requires":{"core":"^1.0"},"root":[{"id":"n_itemName","type":"text","key":"itemName","label":"品項名稱","required":true},{"id":"n_quantity","type":"number","key":"quantity","label":"數量","required":true,"props":{"min":1}},{"id":"n_amount","type":"number","key":"amount","label":"金額","required":true,"props":{"min":0,"precision":2}},{"id":"n_reason","type":"textarea","key":"reason","label":"採購事由","required":true,"props":{"maxLength":500}}]}',
   'published', N'採購申請表', 'system', GETUTCDATE(), GETUTCDATE());
