-- uni-form 重構（docs/plan/2026-10-12-uniform-form-refactor.md）：
-- 表單定義改存 uni-form DSL v1（`$uniform:"1.0"`）。
--
-- 專案尚在開發、無 legacy 資料：直接把兩張申請表的 seed 換成 DSL，
-- 並移除兩張獨立的 review 表 —— 審核／補件的權限改由 BPMN
-- `uniform:access`（ADR-23）在同一份 DSL 上表達：
--   managerReview   → mode=view, default=readonly
--   revision task   → mode=fill, default=readonly, reason 可編輯
-- 先刪相關 FormData 再刪定義，避免留下指向不存在定義的孤兒列
-- （既有 V5 的註解仍適用：Flyway 只執行一次、N'' 前綴不可省略）。
--
-- ⚠️ 本 migration 之後 bpm_form_definition 的世界只有 DSL；舊格式
-- schemaJson（扁平 fields[]）不再被任何程式碼路徑接受。

DELETE FROM bpm_form_data
WHERE form_definition_id IN (
    SELECT id FROM bpm_form_definition
    WHERE form_key IN ('leave-request', 'leave-review', 'purchase-request', 'purchase-review')
);

DELETE FROM bpm_form_definition
WHERE form_key IN ('leave-request', 'leave-review', 'purchase-request', 'purchase-review');

INSERT INTO bpm_form_definition (id, form_key, version, schema_json, status, name, created_by, created_at, updated_at)
VALUES
  (NEWID(), 'leave-request', 1,
   N'{"$uniform":"1.0","key":"leave-request","title":{"zh-TW":"請假申請表"},"requires":{"core":"^1.0"},"root":[{"id":"n_leaveType","type":"select","key":"leaveType","label":"假別","required":true,"props":{"options":[{"value":"annual","label":"特休"},{"value":"personal","label":"事假"},{"value":"sick","label":"病假"}]}},{"id":"n_dateRange","type":"dateRange","key":"dateRange","label":"請假期間","required":true},{"id":"n_reason","type":"textarea","key":"reason","label":"事由","required":true,"props":{"maxLength":500}}]}',
   'published', N'請假申請表', 'system', GETUTCDATE(), GETUTCDATE()),
  (NEWID(), 'purchase-request', 1,
   N'{"$uniform":"1.0","key":"purchase-request","title":{"zh-TW":"採購申請表"},"requires":{"core":"^1.0"},"root":[{"id":"n_itemName","type":"text","key":"itemName","label":"品項名稱","required":true},{"id":"n_quantity","type":"number","key":"quantity","label":"數量","required":true,"props":{"min":1}},{"id":"n_amount","type":"number","key":"amount","label":"金額","required":true,"props":{"min":0,"precision":2}},{"id":"n_reason","type":"textarea","key":"reason","label":"採購事由","required":true,"props":{"maxLength":500}}]}',
   'published', N'採購申請表', 'system', GETUTCDATE(), GETUTCDATE());
