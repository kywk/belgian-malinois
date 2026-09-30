-- 外部系統「可指定哪些候選群組」的授權白名單（#88 政策 B，2026-09-30 使用者裁決）。
--
-- ## 為什麼需要
--
-- 改動前 firstTaskCandidateGroups 完全沒有任何驗證：外部系統可以把案件丟進
-- 任意群組的待辦池（部門代碼／權限碼／JWT authority 三種來源），
-- 那是授權範圍問題（docs/plan/2026-09-28-remediation-backlog.md:260）。
--
-- 為什麼是「授權維度的白名單」而不是「驗證群組存在」：群組名稱在本 repo 有
-- 三個互質的來源，其中只有部門代碼有存在性 API（見 ExternalApiController
-- 該段註解）。要驗存在性就必須假設每個群組都是部門，那會擋掉
-- hr:leave:approve 這種本專案自己的 BPMN 會產生的合法形狀。
-- 白名單不需要知道群組從哪裡來 —— 它只問「這個系統被授權用哪些群組」。
--
-- ## 為什麼可空、而且沒有預設值
--
-- 空值 = 「不限制」，與 allowedProcessKeys 同一條規則
-- （ExternalSystemPolicy.Kind.UNRESTRICTED）。所以：
--   * 既有資料列不需要回填，migration 之後立刻可上線；
--   * 明確寫 "[]" 才是「拒絕全部」（合法的設定，不是錯誤）。
--
-- ⚠️ 反過來說：對既有系統而言這個檢查完全沒有效果，直到管理員逐一設定。
-- 與 allowedProcessKeys 是同一個已知狀況（R-21），刻意不順手改那個政策。
--
-- 包存在性判斷：與 V3 相同，讓 migration 在 ddl-auto 建出來的舊 dev DB 上也能重跑。
IF COL_LENGTH('bpm_external_system', 'allowed_candidate_groups') IS NULL
    ALTER TABLE bpm_external_system
        ADD allowed_candidate_groups NVARCHAR(MAX) NULL;