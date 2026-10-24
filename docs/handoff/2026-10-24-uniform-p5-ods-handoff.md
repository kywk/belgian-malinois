# 2026-10-24 UniForm P5／OptionsProvider 正式組織來源交接

## 現況與決策

- 後續 SDK 升版已依使用者要求更新至本機上游 `67f6047`；版本、相容性與驗證結果見[SDK pin 更新](../plan/2026-10-24-uniform-application-integration.md#2026-10-24-sdk-pin-更新)。下列未升版敘述保留為本交接建立時的快照；正式 ODS 驗收仍待完成。
- Greyhound `main` 目前位於 `fa9a0f5`（`feat(forms): source dynamic options from scoped ODS data`），比 `github/main` 超前 7 個 commit，**尚未 push**。本交接另以文件 commit 收尾；接手時以實際 `HEAD` 為準。
- 申請流程必須綁定主申請表；新申請使用該表單最新已發布版，流程不必重發；案件鎖定送件時驗證的版本，後續審核與補件沿用。申請主表、送件驗證與鎖版已交付（`a47bc8b`）。
- OptionsProvider 已分三批交付：基本動態選項（`de641a6`）、相依參數與動態多選（`126c4f3`）、正式 ODS 來源接線（`fa9a0f5`）。開發期不保留 DSL／資料 migration；測試資料可刪除重建。沒有新增自動化測試，也沒有更新 Greyhound 的 UniForm SDK pin（`89708ef`）。
- UniForm 規格查核基準為獨立工作區 `/Users/kywk/Dropbox/nanshan/uni-form` 的 `67f6047`；接手時先核對最新 HEAD 與 `spec/`，不要把 Greyhound 的舊 pin 誤當最新規格。

## 已實作與驗收邊界

`org.myDepartmentMembers` 透過 Options 專用 `DepartmentMembersSource`，正式模式由 `OdsDepartmentMembersSource` 直接查 MASA 2.x ODS MSSQL；開發／測試仍可使用既有 mock。啟用、principal 命名空間、精確同部門篩選、上限、503 fail closed 及環境變數見[整合紀錄第三批](../plan/2026-10-24-uniform-application-integration.md#2026-10-24-第三批options-正式-ods-來源接線)。`OrgService` 的主管／代理來源沒有在這批替換。

本批通過後端編譯、既有定向測試 36/36、Compose 設定與差異檢查。隔離 MSSQL 假資料和 Greyhound API 端到端實測涵蓋內／外勤獨立命名空間、精確部門、相依篩選、動態多選、非法值拒絕、標籤快照與來源異動後重新 resolve；獨立審查無未解 P0～P3。這些證據**不等於正式資料驗收**。

本機共用 ODS 的 `StaffEmployee` 當時為 0 筆；尚未證明正式登入 principal（JWT 預設 `sub`）與 `StaffEmployee.UserId`／`AgentEmployee.AgCode` 相同，也未驗收真實內／外勤資料、單一部署是否混用身分、ODS 同步新鮮度與在職狀態。正式 ODS 沒有可靠的在職欄位；不能自行猜欄位條件。正式環境預設 `BPM_UNIFORM_OPTIONS_SOURCE=disabled`，需部署者明確選 `ods` 並提供專用唯讀 ODS 帳密、JDBC URL 與 `BPM_UNIFORM_OPTIONS_ODS_PRINCIPAL_ID_TYPE`；來源失聯不回退 mock。不得提交密碼或用測試資料冒充正式結果。

主工作區既有四支 `scripts/*.sh` 執行權限差異與 `vendor/uni-form` 狀態差異均不屬本批，請勿順手 stage、重設或提交。其他歷史交接與 backlog 以[交接索引](README.md)回查；索引中的舊 P5 估時是歷史快照。

## 下一個新對話可直接貼上的 prompt

```text
請接手 Greyhound 的 UniForm P5 OptionsProvider 正式組織來源驗收。工作區為 /Users/kywk/Dropbox/nanshan/greyhound；先核對 git status/HEAD，閱讀 CLAUDE.md、docs/handoff/2026-10-24-uniform-p5-ods-handoff.md 與 docs/plan/2026-10-24-uniform-application-integration.md 第三批，並查 /Users/kywk/Dropbox/nanshan/uni-form 的最新 spec/。目前程式已接 MASA 2.x ODS，但只以隔離假資料完成驗收；請優先查證正式 IdP principal 與 ODS UserId/AgCode 的權威對應、內外勤是否混用，以及真實 ODS 資料與同步/在職狀態，然後在可取得的正式或代表性環境驗證 list、送件 resolve 與成員異動後撤權。若缺權威資料或環境，清楚列出阻礙與所需輸入，不宣稱正式驗收完成；可先完成不依賴外部環境的查核。依專案規範評估是否併發原生 subagent，使用獨立 worktree。不要做 DSL／資料 migration；舊測試資料可刪除重建，不新增自動化測試，除非我另行要求。驗收通過後只 commit 本輪變更，不 push；保留主工作區原有 scripts 權限及 vendor/uni-form 差異。
```
