# BPM 文件入口

本服務的現行程式在 `bpm-core/`，前端在 `bpm-frontend/`。依賴版本以 [bpm-core/pom.xml](../bpm-core/pom.xml) 為準；歷史規格與根目錄 `CLAUDE.md` 的版本敘述可能落後，不應用來判定目前建置組合。

| 閱讀目的 | 文件 |
|---|---|
| API 呼叫、認證與表單版本 | [API 使用指南](api/README.md) |
| 建置與開發入口 | [專案 README](../README.md) |
| 系統需求、流程與設計背景 | [平台規格](bpm-platform-spec.md)；含歷史設計，請與實作核對 |
| 測試與驗證操作 | [測試指南](README-testing.md)、[瀏覽器測試](browser-e2e.md) |
| 架構決策與變更計畫 | [計畫索引](plan/README.md) |
| 交接與當時證據 | [交接索引](handoff/README.md)；紀錄日期不代表目前驗證結果 |

沿用既有 `plan/`、`handoff/`、`history/` 分類，新增呼叫端文件放在 `api/`。不搬動歷史文件；現行行為有異動時，更新 API 指南並連到相關來源，不把計畫中的功能寫成已完成。
