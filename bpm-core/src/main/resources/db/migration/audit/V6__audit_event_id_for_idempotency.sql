-- 稽核事件的幂等鍵。
--
-- 問題（security-audit P1-14）：AuditEventConsumer 沒有幂等鍵。
-- RabbitMQ 的投遞保證是 at-least-once，因此 broker 重投（consumer ack 之前
-- 連線中斷、或 retry 之後又成功）會讓同一筆稽核被 append 兩次。
--
-- 後果比「多一列」嚴重：hash chain 會多出一個重複節點，
-- 而 integrityCheck 完全察覺不到 —— 它驗的是「鏈是否連續」，
-- 而重複 append 產生的鏈在數學上是完全合法的。也就是說稽核紀錄被污染，
-- 而完整性檢查會回報 intact。
--
-- event_id 可為 NULL：bpm-core 自己 in-process 呼叫 append() 的路徑沒有
-- 重投問題（沒有 broker），因此不強制要求。只有經 MQ 進來的事件需要它。
-- 用篩選索引（filtered index）而非普通 UNIQUE：MSSQL 的 UNIQUE 約束把
-- 多個 NULL 視為重複，會擋掉所有 in-process 寫入。
--
-- ⚠️ event_id 刻意**不納入 hash 計算**。它是投遞層的去重中介資料，
--    不是稽核的業務內容；納入會讓所有既有 v2 記錄的 hash 失效
--    （見 AuditLogService.computeHash 的版本說明）。

ALTER TABLE bpm_audit_log ADD event_id NVARCHAR(64) NULL;

-- ⚠️ 索引必須用 sp_executesql 包成獨立批次。
-- Flyway 把整個檔案當成一個批次送出，而 SQL Server 會在執行前先解析整批 ——
-- 上面剛加的 event_id 欄位在同一批次中還不存在，直接寫 CREATE INDEX 會得到
--     Msg 207: Invalid column name 'event_id'.
-- （與 V2 的觸發器同一個原因：DDL 與「引用該 DDL 結果」不能同批。）
EXEC sp_executesql N'
CREATE UNIQUE INDEX uq_audit_event_id
    ON bpm_audit_log (event_id)
 WHERE event_id IS NOT NULL;';
