-- 外部系統「可代員工發起」的授權開關（R-20，2026-09-29 決策：依系統授權，預設不允許）。
--
-- ## 為什麼需要
--
-- 改動前外部系統可在 body 指定任意 initiator。擁有權判定已不依賴它（R-09），
-- 但下游仍廣泛信任它：主管路由、「我的申請」、通知信的申請人。持有 API key 的
-- 任何系統都能偽造一張「看似由某位員工提出」的單，並送到那位員工的主管。
--
-- 現在 initiator 一律由 server 寫成 system:<systemId>。「代員工發起」改用
-- onBehalfOf，而且只有這個欄位為 1 的系統可以使用。
--
-- ## 為什麼預設 0
--
-- 代發是一項需要明確授予的能力，不是預設行為。既有資料列一律為 0 ——
-- 若既有系統確實需要代發，由管理員逐一開啟（留有 CONFIG_CHANGE 稽核）。
--
-- 包存在性判斷：與 V1 相同，讓 migration 在 ddl-auto 建出來的舊 dev DB 上也能重跑。
IF COL_LENGTH('bpm_external_system', 'allow_on_behalf_of') IS NULL
    ALTER TABLE bpm_external_system
        ADD allow_on_behalf_of BIT NOT NULL
            CONSTRAINT df_bpm_external_system_allow_on_behalf_of DEFAULT 0;
