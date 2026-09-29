-- 修復既有環境中已損壞的表單名稱。
--
-- 為什麼需要這一支：V2 把欄位改成 NVARCHAR，但既有資料列的損壞是在
-- 「寫入當時」就發生的 —— 儲存的值本身已經是 '?????'，ALTER COLUMN 只是把
-- 問號轉成 NVARCHAR 的問號，救不回原始文字。
--
-- 為什麼 data.sql 不會自己修好：它的 MERGE 只有
-- WHEN NOT MATCHED THEN INSERT，沒有 WHEN MATCHED THEN UPDATE。
-- 也就是 seed 只補「不存在的列」，既有列永遠不會被更新 ——
-- 因此改 data.sql 對既有環境完全沒有效果。
--
-- 條件刻意收得很窄：只修「這 4 個 seed formKey」且「name 目前含問號」的列。
-- 不加後面那個條件的話，任何人透過 admin UI 改過的表單名稱都會被蓋回 seed 值。

UPDATE bpm_form_definition SET name = N'請假申請表'
 WHERE form_key = 'leave-request'    AND version = 1 AND name LIKE '%?%';
UPDATE bpm_form_definition SET name = N'請假審核表'
 WHERE form_key = 'leave-review'     AND version = 1 AND name LIKE '%?%';
UPDATE bpm_form_definition SET name = N'採購申請表'
 WHERE form_key = 'purchase-request' AND version = 1 AND name LIKE '%?%';
UPDATE bpm_form_definition SET name = N'採購審核表'
 WHERE form_key = 'purchase-review'  AND version = 1 AND name LIKE '%?%';
