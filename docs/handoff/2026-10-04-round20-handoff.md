# 接手文件 — 2026-10-04 #63／#64 測試覆蓋＋端到端（Wave K）

**寫給下一個接手的 PM Agent。** 撰寫時間 2026-10-04（接續 round19，同日）。
上一輪交接見 `docs/handoff/2026-10-04-round19-handoff.md`（**仍然有效**：#41／#53）、
`round18`（#60／#61）、`round17`（走查＋#28／#32／#35）、`round16`（#70 結案）、
`round15`（Stage 5）、`round14`（Wave G）、`round13`（Wave F）、`round12`（Wave E）、
`round11`（Wave D）、`round10`（Wave C）、`round9`（Wave B）、`round8`（Wave A）、
`round7`、`2026-10-02-round6-handoff.md`、`2026-10-01-round4-handoff.md`（環境陷阱總表）、
`2026-09-30-round3-handoff.md`。

本檔只寫本輪新事實。兩個分支 `feature/63-unit-tests`／`feature/64-integration-tests`
已合併並清除；`main` = `3a59d29`（＋文件收尾 commit）。

---

## 0. 現況

| | |
|---|---|
| 後端 | Spring Boot 4.1.1 + Flowable 8.0.0 + Jackson 3 + JGit 7.8 |
| 前端 | Vue 3.4 + Vite 5 + Element Plus |
| 測試 | 後端 **1584**（`mvn clean verify`）、前端 **206** |
| `main` | `3a59d29`（本輪 2 merge）＋文件收尾 |
| push | ⚠️ `nsl` 仍不可達；累積約 **115 顆 commit** 在本機 `main`，恢復後 `git push nsl main`（**永遠不要裸 push**） |
| worktree | 全部清除 |
| 容器 | dev 容器跑本輪 build、預設設定；health 200；acceptance 7/0 |
| DB | dev core schema v8；Flowable schema 8.0.0.0（不可逆） |

---

## 1. 本輪完成

### 1a. #63 單元測試（merge `6aea769`，4 commits）

- 盤點後選 **15 個高風險類別**（授權守衛／認證鏈／parser／狀態機），新增 **198 條**純 Mockito
  單元測試（15 個測試檔，+3484 行）：`ProcessAccessGuard`（36）、`TaskHolderGuard`（14）、
  `AuthorityResolver`（15）、`GatewayAuthenticationFilter`（14）、`JwtSecurityValidator`（10）、
  `CallerIdArgumentResolver`（7）、`ProcessInvolvementService`（12）、`ApplicantIdentityLookup`（10）、
  `FormVersionLocker`（10）、`ExternalSystemAccessGuard`（7）、`ExternalApiAuthFilter.resolveAction`（15）、
  `CallbackSignatureUtil`（11）、`ApiKeyUtil`（5）、`CallbackAuthFilter`（12）、`WebhookConfigResolver`（20）。
- 3 條負控（稽核旁路、任務持有者 owner 條件、webhook 解析閘門）——改壞必紅。
- 生產程式碼零修改；**未加 JaCoCo**（不想動共享 pom；選題以盤點＋負控佐證）。
- 已知限制：mock 驗程式分支、不驗 Flowable SQL 語意（由整合測試守）。

### 1b. #64 整合測試＋flake 修復＋真 bug 修復（merge `3a59d29`，3 commits）

- **flake 修復**（`34f1f77`）：`AuditDeliveryTest` 背景訊息隔離——stop 後非同步歸還的訊息會落在
  drain 之後；改為確定性隔離。證明：連續 3 次＋與 `AuditCoverageTest` 交錯皆綠（PM 亦複驗）。
- **端到端**（`b00ce46`）：`LeaveFlowLifecycleTest`（5）、`PurchaseFlowLifecycleTest`（6）、
  `ExternalApiLifecycleTest`（9）——formData 啟動→核准／拒絕／完成、外部 API 代發全鏈
  （外部啟動走 `EXTERNAL_API_CALL`、**不寫** `PROCESS_START`）。
- 🔴 **生產真 bug 修復**（`52753a4`，端到端測試發現）：`PROCESS_COMPLETED` 在 complete 命令**之內**
  同步派發，而核准／拒絕變數正是該命令才寫入的 → 歷史查詢看不到未 flush 的變數 →
  **每一張正常核准單的 `result` 都被通報成 `unknown`**（內建兩支流程都只在最後一關寫變數）。
  既有測試繞過了事件當下的視窗（啟動時先放 approved／commit 後才判定）。
  修法：歷史為底、事件實體 `exec.getVariables()` 覆蓋；`vars` 只用於 result 判定、不進 payload（P2-1 不變）。

---

## 2. 線上實測（dev 容器，2026-10-04）

| 項目 | 結果 |
|---|---|
| 熱啟動＋seed＋acceptance | health 200；PASS 7 / FAIL 0 |
| 完整請假流程（線上） | user001 以 **formData 啟動** → mgr001 核准 → `PROCESS_COMPLETE` 稽核 **`result=approved`**（修復前為 unknown） |
| 完整套件 | **1584/1584 綠**（含 flake 修復後的 AuditDeliveryTest） |

---

## 3. 程序備註（給下一位 PM）

- #64 agent 的 session 在「等測試跑完」時結束，留下**未提交**的生產修復＋測試，
  以及**未還原的兩處負控**（`ExternalApiController`／`ProcessVariableSpecController` 的 `false &&`）。
  PM 已：等背景 Maven 跑完 → 讀 surefire 報告 → 發現 `ExternalApiLifecycleTest` 的舊版斷言已由 agent
  修好 → 重跑 57 條綠 → 還原負控 → 提交 → 合併。
  **教訓：agent 的驗證 run 可能在 session 結束後仍在跑；合併前務必看 `git status` 與最新 surefire 報告。**

---

## 4. 剩餘工項（統計 ~5 人天）

| 類別 | 工項 | 估時 |
|---|---|---|
| Org/Perm | #8 OrgRestClient 正式實作（3d）／#9 PermRestClient 正式實作（2d）——**使用者指示下一批進行** | 5d |

- #8／#9 現況：client 有逾時與容錯、mock fail-closed；仍只對接 `MockOrgController`／`MockPermController`。
  正式實作需要**外圍系統的真實 API 契約**（base URL、認證、回應形狀）。mock 端點定義的形狀目前是
  事實上的契約；若使用者能提供真實規格，先對齊再開工。

---

## 5. 統計

- 工項 **97**：✅ **95**、🟡 **2**（#8／#9）、⬜ **0**。
- 後端 **1584**；前端 **206**；`acceptance-test` PASS 7 / FAIL 0。
