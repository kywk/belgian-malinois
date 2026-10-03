# 接手文件 — 2026-10-03 Wave D 完成（#22＋#24＋#20＋小殘餘）

**寫給下一個接手的 PM Agent。** 撰寫時間 2026-10-03（同日，接續 round10）。
上一輪交接見 `docs/handoff/2026-10-03-round10-handoff.md`（**仍然有效**：Wave C）、
`round9`（Wave B）、`round8`（Wave A）、`round7`（#96／#51 告警／#3／#6／taskId）、
`2026-10-02-round6-handoff.md`、`2026-10-01-round4-handoff.md`（環境陷阱總表）、
`2026-09-30-round3-handoff.md`（授權政策、並行流程）。本檔只寫 Wave D 的新事實。

四個工項各在獨立 worktree 並行，全部經 PM 驗收、合併、完整套件與線上實測；
worktree 與 feature 分支已全部清除。

---

## 0. 現況

| | |
|---|---|
| 後端 | Spring Boot **3.5.16** + Flowable **7.2.0**（未升級） |
| 前端 | Vue 3.4 + Vite 5 + Element Plus |
| 測試 | 後端 **1044**（`mvn clean verify`）、前端 **187**（Vitest） |
| `main` | merges 至 `7c216f5`／`90ea773`／`915f02c`／`2c24417` ＋ 文件收尾 commit |
| push | ⚠️ `nsl` 主機本日整天不可達（`10.127.42.141`）——本日五輪 commit 都在本機 `main`，網路恢復後 `git push nsl main`（**永遠不要裸 push**） |
| worktree | 全部清除 |
| 容器 | dev 容器運行中；`acceptance-test.sh` **PASS 7 / FAIL 0**；form V6 migration 已套用 |

---

## 1. 本輪完成

| 工項 | commit / merge | 內容 |
|---|---|---|
| **#22 External Worker** | `0d8a3c9` → `7c216f5` | BPMN external-worker＋`/api/external/worker/**`（acquire／查詢／complete／fail／unacquire）；workerId 伺服器鑄造；`allowedActions: external_worker` |
| **#24 Signal 廣播** | `fa8bf07` → `90ea773` | `POST /api/admin/signals/{name}/broadcast`（ROLE_ADMIN）；0 等待 → 404；`SIGNAL_BROADCAST` 稽核 |
| **#20 usage-logs** | `b6672c3` → `915f02c` | 接上真實稽核查詢（operatorId=`system:<id>`＋`EXTERNAL_API_CALL`）；分頁／日期與 `/api/audit-logs` 相同 |
| **小殘餘批次** | `7343eb9`／`19d37f2`／`b98bd94`／`6dfefcc` → `2c24417` | form index V6、建立時顯示 callbackSecret、收件人規則收斂、撤回 reason 上限 1000 |

---

## 2. 本輪重要技術結論

### 2.1 #22：Flowable 內建 external worker API 與兩個實測陷阱

- BPMN：`flowable:type="external-worker"`（別名 `external`）＋`flowable:topic`
  （以 `javap -c` 對 flowable-bpmn-converter 7.2.0 確認）。
- 引擎 API：`ManagementService.createExternalWorkerJobAcquireBuilder()`／
  `createExternalWorkerCompletionBuilder(jobId, workerId)`／
  `createExternalWorkerJobFailureBuilder(jobId, workerId)`／`unacquireExternalWorkerJob`。
- **workerId 一律伺服器鑄造 `system:<id>`**：body 只接受省略／空白／等於它，其餘 400
  （信任 body 會讓 B 系統完成 A 的 job；負控組實測過）。
- ⚠️ **job 預設 exclusive**：acquire 會鎖 process instance；complete／fail 會解範圍鎖，
  但 `unacquireExternalWorkerJob` 只清 job 鎖——主動釋放後仍要等原鎖到期才真能被再認領。
- ⚠️ **跨系統共享 topic 先搶先贏**：未鎖定 job 任何被授權系統可認領，
  `allowedProcessKeys` 不套用；且 acquire 回傳流程變數（共用 topic 時可見他人變數）。
  **建議系統使用專屬 topic。**
- fail 依 Flowable 預設：retries 3→2→1→0，前兩次清鎖回佇列、第三次進死信。
- 稽核：`EXTERNAL_API_CALL`（action `external_worker_acquire/_complete/_fail/_unacquire`），
  **variables 不進稽核**；空 acquire 不寫（避免輪詢洪水）。
- ⚠️ `_` 保留前綴在 worker complete 重述一份（不動 `ExternalApiController` 邊界下的第二份），
  待抽出共用。

### 2.2 #24：一對多與 Flowable 的 scope 語意

- `runtimeService.signalEventReceived(name, vars)` 全域廣播；0 訂閱是**靜默 no-op** →
  以「廣播前查等待數」補 404（與 callback 一致）。
- 變數由 `EventSubscriptionUtil` 套用到**每一個**被喚醒的 execution。
- ⚠️ **process-scoped signal 會被 `waiting` 計數但全域廣播跳過**（Flowable 語意；
  測試釘住、spec 加註）——若日後要消除需改計數來源（目前無公開 API 可過濾 scope）。
- 稽核 fail-closed（`publish`）：廣播與稽核同交易，失敗可回滾且重試安全。

### 2.3 #20：使用紀錄的範圍

- 只收 `operatorId=system:<id>` 的 `EXTERNAL_API_CALL`（`CONFIG_CHANGE` 是設定史，不是使用史）。
- 含被拒絕的 401／403（以 `X-System-Id` 歸戶）——代價是未認證者可冒用 systemId 產生紀錄，
  查詢結果不宜當成「對方持有金鑰」的證據（javadoc 已載）。
- 查詢本身留 `DATA_ACCESS`；停用後仍可查歷史。

### 2.4 小殘餘

- **收件人規則收斂**：`NotifyPublisher.taskRecipients`＋`candidateUsers` 為唯一一份；
  `TaskController` 私有副本刪除、`TimeoutNotifyDelegate` 改呼叫（保留外層 assignee 判斷）。
  ⚠️ `TaskController` 認領通知（排除認領者）仍是刻意的第三種語意，未收斂。
- **form index V6**：幂等 `IF NOT EXISTS`；測試查 `sys.indexes` 釘住。
  ⚠️ 負控曾「假綠」——`target/classes` 留有 stale 資源，Maven resources 不會刪已移除來源檔；
  驗 migration 類測試要用 clean build 或手動清 stale。
- **撤回 reason 上限 1000**（DB 欄位 nvarchar(4000) 的 4 倍餘裕）：超過 400、零副作用。

---

## 3. 已知殘餘

| 殘餘 | 說明 |
|---|---|
| #22 | 共享 topic 先搶先贏＋acquire 回變數；`unacquire` 範圍鎖限制；`_` 前綴第二份；required 規格未套用 |
| #24 | process-scoped signal 計數／廣播不一致；count 與廣播競態窗口 |
| #20 | 被拒絕呼叫的歸戶可被冒用（既有稽核性質） |
| 沿用 | probe 殘留（`probe-22w`／`probe-24sig`／`probe-4call`／`probe-21cb`／`probe-23timer`／`probe-96ext`）、已停用外部系統、已封存表單、R-21／R-24、#70 Stage 5 |

---

## 4. 環境風險（新增，其餘見 round10／round9／round8／round4）

1. **worker topic 隔離**：多系統共用 topic＝先搶先贏＋變數可見；部署時用系統專屬 topic。
2. **exclusive job 的範圍鎖**：`unacquire` 後仍可能等原鎖到期；要立即釋放需
   `flowable:exclusive="false"`。
3. **migration 類測試的 stale 資源**：負控／改名測試前清 `target/classes` 的舊檔，
   否則會假綠（見 §2.4）。
4. 沿用：Flyway placeholder、多工項 migration 版本、`POST /api/deployments` name 後綴、
   `mvn clean verify`、`DOCKER_HOST` exports、外部 API 雙標頭、`git commit` 整 index、
   `IntegrationTestBase.SERVLET_PORT` 單一 port。

---

## 5. 已裁決與待裁決

### 5.1 本輪已裁決（使用者 2026-10-03）

1. **#22 認證**：沿用外部系統 API Key＋`allowedActions: external_worker`。
2. **#24 觸發**：管理員 API（ROLE_ADMIN）。

### 5.2 待裁決／可開新工項

1. **#22 共享 topic 風險**：要不要加 topic 白名單（per-system）或要求專屬 topic。
2. **#58 表單修改版本控制**（語意待確認：PUT 的版本與「退回狀態」檢查）。
3. **#56 動態選項**（任意 URL 抓取有 SSRF 政策面）。
4. **#65 OpenAPI**（prod 曝露與否）。
5. 下一輪主軸（見 §6）。

---

## 6. 建議的下一輪優先序

| 順序 | 工項 | 估時 | 備註 |
|---|---|---|---|
| 1 | **通用 Delegate 批次**：#43 EmailNotify／#48 DataValidation／#49 ExternalApi（SSRF 沿用 `WebhookUrlPolicy`） | 1d／1d／2d | 低政策風險；#49 需確認 URL 政策 |
| 2 | **#50 JVM 記憶體**／**#53 BPMN 環境變數**（後者有 secrets 政策） | 0.5d／1d | 上線準備 |
| 3 | **#58／#56** | 1d／2d | 需先裁決 |
| 4 | **#65 API 文件**／**#63～#64 測試覆蓋** | 2d／5d | 上線前 |
| 5 | **#70 Stage 5**（Boot 4＋Flowable 8） | 22d | `ExtensionElementPreservationTest` 先紅 |

**部署前檢查清單（沿用＋新增）**：權限碼指派；prod secrets；`allowedProcessKeys` backfill；
#51 告警收件人；外部系統 callback secret 逐一 rotate；**worker 系統的 `allowedActions`
需含 `external_worker` 且建議專屬 topic**。

---

## 7. 統計

- 工項 **96**：✅ **73**、🟡 **9**、⬜ **14**，剩餘估時上限 **~59 人天**。
- 後端 **1044**；前端 **187**；`acceptance-test` PASS 7 / FAIL 0。

---

## 8. 線上實測明細（2026-10-03 Wave D，PM 序列）

| 工項 | 實測內容與結果 |
|---|---|
| **#22** | 建立 `e2e-worker`（`external_worker`）→ 部署 `probe-22w`（external-worker task, topic `e2e-topic`）→ 啟動 → acquire 取得 job（lockOwner `system:e2e-worker`）→ **owner complete 200、流程結束**；**別系統 complete 404**（「任務不存在或未由本系統鎖定」，不洩漏存在） |
| **#24** | 部署 `probe-24sig`（signal catch）→ 啟動 2 實例 → 廣播 `waiting:2` → **兩個都醒**；第二次 **404**；`SIGNAL_BROADCAST` 稽核（waiting=2／variables=1） |
| **#20** | `GET /api/admin/external-systems/e2e-worker/usage-logs` → **2 筆**（acquire＋complete，operator `system:e2e-worker`，detail 含 jobId／topic） |
| **小殘餘** | 撤回 `reason` 1001 字元 → **400**；正常 → **200**（案件撤回）；前端 callbackSecret 顯示僅 vitest（未瀏覽器驗證） |
