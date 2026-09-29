-- 稽核表中會存放人類文字的欄位改為 NVARCHAR。
--
-- ⚠️ 這不是外觀問題，它會直接破壞稽核完整性驗證。
--
-- 三個 DB 的定序都是 SQL_Latin1_General_CP1_CI_AS，而 Hibernate 把沒有明確
-- columnDefinition 的 String 欄位建成 VARCHAR。中文寫入 VARCHAR 時會被
-- 靜默換成問號（不報錯）。
--
-- 在 v1 的 hash 只涵蓋 7 個欄位時這只是顯示問題；但 v2 把 operator_name、
-- business_key 納入 hash 之後：
--   寫入時 hash 用的是原始值「主管 1」
--   讀回來的欄位卻是「?????」
--   → integrityCheck 重算後對不上 → 每一筆都被誤報為遭篡改
-- 也就是說，只要有人的姓名是中文，稽核完整性檢查就永遠回報 broken。
--
-- 只改真正會存人類文字的兩個欄位。其餘 VARCHAR 欄位存的是 id、hash、
-- IP、user-agent、流程定義 key，都是 ASCII，不需要轉型（轉了只是白白
-- 增加儲存與索引成本）。detail／previous_state／new_state 本來就是
-- NVARCHAR(MAX)。
--
-- 兩個欄位都不屬於索引或約束，可直接 ALTER。既有的問號資料無法還原
-- （損壞發生在寫入當時），但既有記錄是 v1 hash、不涵蓋這兩個欄位，
-- 因此不影響它們的驗證結果。

ALTER TABLE bpm_audit_log ALTER COLUMN operator_name NVARCHAR(255) NULL;
ALTER TABLE bpm_audit_log ALTER COLUMN business_key  NVARCHAR(255) NULL;
