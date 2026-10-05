-- API Key v2 雜湊與輪替寬限期（R-25）。
--
-- ## 為什麼要改
--
-- 改動前 api_key 是無 salt 單輪 SHA-256，且 rotate-key 沒有寬限期：
--   1. 相同明文金鑰在不同系統／環境產生相同雜湊，DB 外洩時可跨系統
--      比對出重用的金鑰；拿到雜湊的人也能離線驗證候選明文（無 secret）。
--      改成 HMAC-SHA256(server secret, key)，落庫值加 "v2:" 前綴識別格式。
--   2. 輪替即中斷，實務上導致「不敢輪替」。新增 previous_api_key 與
--      previous_api_key_expires_at 讓新舊金鑰並存到舊的到期為止。
--
-- ## 為什麼只加寬欄位、不搬資料
--
-- 既有資料列是無前綴的舊雜湊，驗證端雙讀（無前綴＝舊格式路徑），
-- 所以 migration 不需要（也不能）改寫任何既有值 —— 舊雜湊沒有 server secret
-- 就算不出對應的 v2 值。每把舊金鑰在第一次通過驗證時由應用層透明升級，
-- 這是唯一安全的遷移方式：不需要明文、不需要停機、不需要維運逐系統操作。
--
-- ## 欄位長度
--
-- v2 值 = "v2:"（3）＋ HMAC-SHA256 小寫 hex（64）= 67 字元；
-- NVARCHAR(128) 留給未來格式演進的餘裕。舊值（64 字元）不受影響。
--
-- ## 為什麼可以用無條件 ALTER COLUMN
--
-- 對 MSSQL 而言把 NVARCHAR(64) 改成 NVARCHAR(128) 是 idempotent 的
-- （已是 128 再改一次也成功），且 api_key 上沒有索引／約束需要先卸下
-- （對照 V2 搬 id 前得先 DROP 約束）。因此不像 ADD COLUMN 那樣需要
-- COL_LENGTH 守衛。
ALTER TABLE bpm_external_system ALTER COLUMN api_key NVARCHAR(128) NOT NULL;

-- 包存在性判斷：與 V3／V4／V5／V7 相同，讓 migration 在 ddl-auto 建出來的
-- 舊 dev DB 上也能重跑。
IF COL_LENGTH('bpm_external_system', 'previous_api_key') IS NULL
    ALTER TABLE bpm_external_system
        ADD previous_api_key NVARCHAR(128) NULL;

IF COL_LENGTH('bpm_external_system', 'previous_api_key_expires_at') IS NULL
    ALTER TABLE bpm_external_system
        ADD previous_api_key_expires_at DATETIMEOFFSET NULL;
