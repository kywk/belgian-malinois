-- 加寬 hash_value 以容納演算法版本前綴。
--
-- 為什麼要版本前綴：v1 的 hash 只涵蓋 17 個欄位中的 7 個
-- （security-audit P0-3），必須換演算法。但換掉之後所有既有記錄在重算時
-- 都會對不上 —— 若不區分版本，integrityCheck 會把歷史資料全部誤報為遭篡改，
-- 於是這個功能在第一次真正被使用時就失去可信度，反而訓練使用者忽略它。
--
-- 因此新記錄寫成 'v2:' + 64 位十六進位 = 67 字元，超過原本的 VARCHAR(64)。
-- 加寬到 80 留一點餘裕給未來的版本。
--
-- ⚠️ previous_hash 必須一起加寬 —— 它存放的就是「前一筆的 hash_value」，
-- 只加寬其中一個會在插入第二筆時就爆
-- （String or binary data would be truncated）。
--
-- 兩個欄位都不屬於任何索引或約束（idx_audit_* 建在 created_at／operator_id／
-- process_instance_id 上），因此可直接 ALTER。
-- previous_hash 保持可為 NULL（原本就是）。

ALTER TABLE bpm_audit_log ALTER COLUMN hash_value   VARCHAR(80) NOT NULL;
ALTER TABLE bpm_audit_log ALTER COLUMN previous_hash VARCHAR(80) NULL;
