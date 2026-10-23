# 2026-10-24：開發環境驗證與動態指派設計器

本批以 `f49e63e` 為基準，完成 #47 設計器動態指派及 R-16 根目錄 README。
兩個原生 subagent 在獨立 worktree 實作，由主代理審查、整合與驗證；未變更後端契約或 SDK pin。
功能提交：`e66a101`（`feat(bpmn): expose dynamic assignee settings`）。

## 交付

- BPMN 使用者任務「審核對象」新增三種設定：第 N 階主管、首位可受理的權限持有人、具指定權限的最近主管；三者沿用後端 `dynamicAssignee`，包含既有代理人語意。
- 原主管設定與候選人認領模式保留。新增設定只解析已知完整運算式，未知運算式顯示原文；除非明確改選類型，不覆寫原值。
- 階數限制為 1 至 Java int 上限；權限碼採單一字串，處理 EL 單引號與反斜線跳脫。
- 根目錄 `README.md` 補齊實際版本、uni-form 建置、localenv 入口、API、驗證及 MockOrg／MockPerm 邊界。

## 本輪驗證

| 項目 | 結果 |
|---|---|
| localenv `validate` | PASS |
| localenv `greyhound ready --strict-health`／`demo greyhound start` | PASS；API `:18110` health UP，三個 MSSQL DataSource、Redis、RabbitMQ、MailHog 均 UP；前端 `:3000` HTTP 200 |
| 既有 seed／acceptance | PASS；三支 BPMN 與兩張 DSL 表單確認成功，黑箱驗收 7/7 |
| Worker 既有 targeted Vitest | `assigneeExpressions.spec.js`＋`FlowablePropertiesProvider.spec.js`，18/18 PASS |
| Worker 一次性手動斷言 | 三式 build／parse、非法階數與未知式負控、20 個 moddle XML 往返 PASS；未新增測試檔 |
| 實際 API 契約 | 從前端 helper 產生三種運算式，代入既有請假 BPMN，`POST /api/bpmn/lint` 三次皆 `valid:true`／零錯誤；未部署這些驗證變體 |
| 瀏覽器局部操作 | 管理員登入、使用者任務顯示三選項、第 N 階主管與首位權限持有人輸入可操作；未宣稱完成 uni-form 全流程視覺驗收 |
| 文件與差異 | README 6 個相對連結存在，`git diff --check` PASS |
| 完整後端 | Java 21＋Docker socket 設定後 `cd bpm-core && mvn verify`：1677 tests，0 failures／errors／skipped，BUILD SUCCESS（8 分 46 秒，含首次映像下載） |
| 完整前端 | `cd bpm-frontend && npm test`：25 檔、259 tests PASS；`npm run build` PASS，仍有 bundle 大於 500 kB 警告 |

Worker requested model／effort：功能 `gpt-6.1-sol/medium`、文件 `gpt-6-luna/low`；工具未另外回報 effective model，實際生效值未獨立觀測。使用者指定併行，初估單代理 18–26k、派工 20–30k token，不宣稱節省 token。

## 環境注意事項

- 本機 Java 21 位於 SDKMAN；`/usr/libexec/java_home` 未能找到它。Maven 需設定正確 `JAVA_HOME`。
- 首次 `mvn -o verify` 因 Surefire provider 尚未快取而失敗，改跑線上 `mvn verify` 下載既有相依；未調整版本。
- 原工作區四支腳本已有 executable bit 差異，`vendor/uni-form` 也有既有修改，均保留且不納入本批提交。seed／acceptance 使用內容相同的暫存副本，僅恢復副本內 `dev-token.sh` 執行權限，並明列 BPM／FORM URL 同為 `:18110`。
- 共用 scripts repo 已有 Greyhound 環境準備變更，本批不提交或覆寫該 repo。

## 後續

uni-form P5、R-07／R-08、供應商 ESign 回應契約及其他殘餘仍以[交接入口](README.md)為準。
本輪沒有新增後端能力、正式部署或 push，也未把歷史 13/13 uni-form E2E 當成本輪實測。
