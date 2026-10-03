-- bpm_form_data.form_definition_id 的索引（#59 遺留）。
--
-- ## 為什麼需要
--
-- #59 的封存保護（FormService.requireNotUsedByRunningProcess）在封存表單前
-- 以 FormDataRepository.findDistinctProcessInstanceIdsByFormDefinitionId
-- 查「這張表單被哪些案件用過」：
--
--   SELECT DISTINCT process_instance_id FROM bpm_form_data WHERE form_definition_id = ?
--
-- V1 baseline 只建了 process_instance_id 的 idx_form_data_process，
-- form_definition_id 上沒有任何索引 —— 這個查詢只能全表掃描。
-- bpm_form_data 隨每個案件累積、永不刪除（案件結束也不刪），掃描成本
-- 只會上升，而它擋在「封存」這個管理操作的必經路徑上。
--
-- ## 幂等
--
-- 與 V1／V4 相同：先查 sys.indexes 再建。baseline-on-migrate 讓 V1–V5 在
-- 既有環境可能被標記為已套用（見 V1 的註解），因此 V6 必須自己保證
-- 「重跑一次也不會壞」。名稱沿用既有前綴（idx_form_data_*）。
IF NOT EXISTS (SELECT 1 FROM sys.indexes
               WHERE name = 'idx_form_data_definition'
                 AND object_id = OBJECT_ID('bpm_form_data'))
CREATE INDEX idx_form_data_definition ON bpm_form_data (form_definition_id);
