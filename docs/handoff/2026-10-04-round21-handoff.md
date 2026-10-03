# 接手文件 — 2026-10-04 #8／#9 外圍 client 正式化（Wave L）—— **97 項全清**

**寫給下一個接手的 PM Agent。** 撰寫時間 2026-10-04（接續 round20，同日）。
上一輪交接見 `docs/handoff/2026-10-04-round20-handoff.md`（**仍然有效**：#63／#64）、
`round19`（#41／#53）、`round18`（#60／#61）、`round17`（走查＋#28／#32／#35）、
`round16`（#70 結案）、`round15`（Stage 5）、`round14`（Wave G）、`round13`（Wave F）、
`round12`（Wave E）、`round11`（Wave D）、`round10`（Wave C）、`round9`（Wave B）、
`round8`（Wave A）、`round7`、`2026-10-02-round6-handoff.md`、
`2026-10-01-round4-handoff.md`（環境陷阱總表）、`2026-09-30-round3-handoff.md`。

本檔只寫本輪新事實。分支 `feature/8-9-external-formalization` 已合併並清除；
`main` = `d511a95`（＋文件收尾 commit）。

---

## 0. 現況（backlog 結清）

| | |
|---|---|
| 後端 | Spring Boot 4.1.1 + Flowable 8.0.0 + Jackson 3 + JGit 7.8 |
| 前端 | Vue 3.4 + Vite 5 + Element Plus |
| 測試 | 後端 **1617**（`mvn clean verify`）、前端 **206** |
| `main` | `d511a95`（本輪 1 merge）＋文件收尾 |
| push | ⚠️ `nsl` 仍不可達；累積約 **120 顆 commit** 在本機 `main`，恢復後 `git push nsl main`（**永遠不要裸 push**） |
| worktree | 全部清除 |
| 容器 | dev 容器跑本輪 build、預設設定；health 200；acceptance 7/0 |
| backlog | **97 項：✅ 97、🟡 0、⬜ 0；剩餘 0d** |

---

## 1. 本輪完成（#8／#9 依 mock 契約正式化，使用者裁決）

- **認證注入**：`bpm.external.auth-header`（預設 `Authorization`）＋
  `org-auth-token`／`perm-auth-token`（預設空）。非空以**原樣**放進 header
  （`Bearer xxx` 或 `satoken xxx` 由部署端決定）；空＝完全不送。
  prod：`docker-compose.prod.yml` 注入 `ORG_AUTH_TOKEN`／`PERM_AUTH_TOKEN`。
  **token 不進 log／例外／稽核**（線上實測 0 命中）。
- **錯誤映射**：`ExternalApiException extends RestClientException`（service／kind／status／path，
  **不含 response body**）；`ExternalApiErrors` 以 status handler＋request interceptor 兩個掛載點集中映射。
  404 的 `isNotFound()` 保留「查無此人」語意——**刻意不轉 null**（轉 null 會讓 `ExternalActorGuard`
  放行沒有人能簽的身分）；服務層 fail-closed／快取語意不變。
- **相容 hook（必要，超出原點名）**：`ExternalActorGuard.isDefinitiveRejection` 與
  `SubstituteForwardController` 的 404 catch 同步認得新型別（否則 404 被當故障：400→503／批次中止）。
- **契約測試**：真 HTTP server（`StubExternalApiServer`）33 條（Org 19／Perm 14）——端點路徑與 query、
  auth 有／無、404/5xx/401／逾時／拒線映射、服務層 fail-closed 與快取哨兵。
- 不動：mock gating、URL validator、P1-7 兩 stub（`getAuthorizedManager`／
  `getUsersByPermissionAndCondition` 維持 lint 擋下）、spec／plan／backlog／history。

---

## 2. 線上實測（dev 容器，2026-10-04）

| 項目 | 結果 |
|---|---|
| 熱啟動（設 `ORG_AUTH_TOKEN`／`PERM_AUTH_TOKEN`） | health 200；seed＋acceptance **7/0** |
| token 洩漏 | app log 0 命中 |
| 收尾 | dev 容器已重建為**預設設定**（無 token） |

---

## 3. 已知殘餘（本輪新增）

| 殘餘 | 說明 |
|---|---|
| 真實系統上線 | 只需換 URL＋token；真實 API 的認證方式／錯誤信封若有差異，屆時對齊（目前事實契約＝mock） |
| `SubstituteForwardController` 404-skip | 改動只是「同一判準多認一個型別」，該路徑**沒有專屬測試**（既有缺口），建議日後補 |
| connect timeout | 未實測（`Kind.TIMEOUT` 涵蓋連線與讀取逾時；read 有真測） |
| 空白 token | 原樣送出（依「原樣」設計）；若要視為未設是一行改動 |
| 3xx | 現在一律映射 `ExternalApiException`（先前 GET 由 JDK 自動跟隨） |

---

## 4. 部署前檢查清單（完整）

1. 權限碼指派（prod IdP／權限中心）。
2. prod secrets：JWT（`OIDC_ISSUER_URI`）、DB 密碼、**`ORG_AUTH_TOKEN`／`PERM_AUTH_TOKEN`**、
   webhook HMAC、callback secret rotate。
3. backfill（R-21／R-24）；worker topic 專屬化；#51 告警收件人。
4. Teams webhook URL 設定（#32；prod `WebhookUrlPolicy` 白名單）。
5. BPMN Git 版控（#61）：`BPM_BPMN_GIT_ENABLED=true`＋持久化可寫的 `bpmn-definitions`＋repo 巡檢。
6. 表單 schema 修復（既有 corrupt names）；JVM／limits 依 GC log 調。
7. 部署驗證：acceptance-test、既有 dev DB 熱啟動（Flyway v8）、`PROCESS_COMPLETE result` 正確。
8. prod 映像已是 Boot 4／Flowable 8／Jackson 3。

---

## 5. 統計

- 工項 **97**：✅ **97**、🟡 **0**、⬜ **0**；剩餘 **0d**。
- 後端 **1617**；前端 **206**；`acceptance-test` PASS 7 / FAIL 0。
- 本日（2026-10-03～04）完成：#96／#51／#3／#6 → Wave A～L（#70 Stage 5／6、走查、#28／#32／#35、
  #60／#61、#41／#53、#63／#64、#8／#9），約 **120 顆 commit**（等 `nsl` 恢復一次推送）。
