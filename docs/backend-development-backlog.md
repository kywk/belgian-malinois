# Greyhound BPM 平台 — 後端開發工項清單

> 產出日期：2026-06-08
> 最後更新：2026-09-29（依 `feature/tech-debt-remediation` 合併後的程式碼逐項核對）
> 基於規格文件 vs 實際程式碼差異分析
>
> 狀態：✅ 完成　🟡 部分完成（說明欄寫缺什麼）　⬜ 未開始

---

## 專案現況摘要

2026-09-29 現況：

- **單一後端模組 `bpm-core`**：Spring Boot 3.5.16 + Flowable 7.2.0，三個 DataSource
  （`bpm_core_db`／`bpm_audit_db`／`bpm_form_db`），schema 由 Flyway 管理
- **form-service**：已於 2026-09-28 併入 bpm-core（ADR-001），程式碼在 `com.bpm.core.form.*`，
  資料庫仍獨立。下方「二、Form Service」的工項仍然有效
- **audit-log-service**：已於 2026-04-24 併入 bpm-core；稽核為 fail-closed（寫不進就回滾）
- **認證**：平台層 JWT 驗證＋信任閘道已完成（R-01）；個案層級授權仍由各 controller 負責
- **外圍系統整合（組織／權限）仍以 Mock 替代**，真正的權限中心見 `docs/rbac-enterprise-backlog.md`
- **測試**：後端 543 個（Testcontainers：真實 MSSQL／RabbitMQ／Redis），前端 80 個（Vitest）
- 安全與正確性修復（P0／P1／P2、R 編號）另見 `docs/plan/2026-09-28-security-audit.md`、
  `docs/plan/2026-09-28-remediation-backlog.md`，已完成部分列在 `docs/backend-completed-items.md` 第八節

---

## 一、BPM Core Service

### 1.1 流程引擎核心（部分完成，需補強）

| # | 工項 | 說明 | 估時 | 狀態 |
|---|------|------|------|------|
| 1 | 退件機制完善 | `returnTo=initiator` 邏輯、BPMN Gateway 退回路由 | 3d | 🟡 退回走 BPMN 預設路徑到「申請者補件」；缺 `returnTo=initiator`／退到任意節點 |
| 2 | 拒絕（終止）機制 | `rejected=true` 流程終止分支、通知申請人 | 2d | 🟡 `rejected=true` 導向終止、結案結果回報已修；缺通知申請人（無程式發出 `process_rejected`） |
| 3 | 加簽 - 動態子任務 | 建立 Sub Task、原任務暫停、加簽完成恢復、多人加簽 | 5d | ✅ `CountersignController`；有未完成子任務時不得 complete（409） |
| 4 | 加簽 - Call Activity | 預定義加簽子流程模板整合、前端 Call Activity 節點配置 | 3d | ⬜ |
| 5 | 代理人機制 | `orgService.resolveEffective()` 考慮代理人、自動轉派 | 2d | 🟡 `resolveEffective` 已有；持有人全不在時改派代理人（68d8518）；缺一般指派與既有任務的自動轉派 |
| 6 | 催辦功能 | 催辦 API、觸發通知、防頻繁催辦限制 | 1d | ⬜ 前端 `urge()` 只是假提示 |
| 7 | 流程撤回（申請人撤案） | 撤回 API、判斷是否可撤回（第一節點尚未處理） | 2d | ⬜ |

### 1.2 OrgService / PermService 整合（目前為 Mock）

| # | 工項 | 說明 | 估時 | 狀態 |
|---|------|------|------|------|
| 8 | OrgRestClient 正式實作 | 對接外圍組織系統 REST API（非 Mock） | 3d | 🟡 client 有逾時與容錯、mock fail-closed；仍只對接 `MockOrgController`（待權限中心） |
| 9 | PermRestClient 正式實作 | 對接外圍權限系統 REST API | 2d | 🟡 同 #8；條件式權限查詢刻意拋 Unsupported |
| 10 | Redis 快取實作 | 所有 org/perm 查詢加入 Redis TTL 快取 | 2d | ✅ TTL 快取、快取空值、Redis 故障時退化 |
| 11 | 快取主動失效 Webhook | `/api/internal/cache-invalidate/org`、`/perm` 實作 | 1d | ✅ 需 `ROLE_GATEWAY`（信任閘道） |
| 12 | BpmQueryService 組合查詢 | `getManagerWithPermission`、`getDeptUsersWithPermission` | 2d | ✅ |

### 1.3 外部系統接入（Controller 已建，邏輯需完善）

| # | 工項 | 說明 | 估時 | 狀態 |
|---|------|------|------|------|
| 13 | API Key 認證完整實作 | SHA-256 hash 儲存、X-API-Key 驗證、IP 白名單 | 2d | ✅ `lastUsedAt` 每請求寫一次 DB 仍待優化 |
| 14 | 外部系統權限檢查 | allowedProcessKeys、allowedActions 驗證邏輯 | 1d | ✅ 精確比對（R-09）；⚠️ 欄位留空 = 不限制（R-21） |
| 15 | 外部系統 initiator 處理 | `system:{systemId}` 識別、`effectiveInitiator` 設定 | 1d | ✅ 固定 `system:<id>`；代員工發起用 `onBehalfOf` 且需系統授權（R-20，4ee75d4） |
| 16 | 外部系統發起流程 | `firstTaskAssignee`、`firstTaskCandidateGroups` 邏輯 | 2d | ✅ 缺指定人員／群組的存在性驗證 |
| 17 | 外部系統操作節點 | PUT /api/external/tasks/{taskId} 完整邏輯 | 1d | ✅ 檢查 action 與案件歸屬 |
| 18 | 外部系統查詢流程狀態 | status API、businessKey 查詢 | 1d | ✅ 只能查到自己系統的案件 |
| 19 | API Key 輪替 | rotate-key API、舊 Key 立即失效 | 1d | ✅ 會記稽核 |
| 20 | 外部系統使用紀錄 | usage-logs API | 1d | 🟡 `usage-logs` 只是 placeholder，請改查 `/api/audit-logs` |

### 1.4 非同步流程 / Callback

| # | 工項 | 說明 | 估時 | 狀態 |
|---|------|------|------|------|
| 21 | Callback 接收端 | HMAC Token 驗證、冪等檢查（Redis SetIfAbsent）、Message Correlation | 3d | ⬜ |
| 22 | External Worker Task 支援 | 輪詢認領機制 | 3d | ⬜ |
| 23 | Timer Event 超時處理 | 超時自動觸發、超時預警通知 | 2d | ⬜ |
| 24 | Signal Event 廣播 | 一對多喚醒流程 | 1d | ⬜ |

### 1.5 Webhook 觸發（Listener 已建，Payload 需完善）

| # | 工項 | 說明 | 估時 | 狀態 |
|---|------|------|------|------|
| 25 | Webhook Payload 完整化 | 依規格補齊所有事件欄位（task.created、completed、rejected、timeout、process.completed） | 3d | 🟡 有 task／process 事件；缺 timeout 事件；**投遞整條未接上**（見 #67） |
| 26 | HMAC 簽章實作 | webhook payload HMAC-SHA256 簽章 | 1d | 🟡 HMAC-SHA256 簽章已實作，但投遞未接上，實際不會觸發（見 #67） |
| 27 | Webhook 重試機制 | 失敗指數退避重試（1s→2s→4s，max 3次）、DLQ | 2d | 🟡 retry 1s×2 最多 3 次＋`dlq.bpm`、SSRF 防護已有；同樣未接上（見 #67） |
| 28 | payloadTemplate 自訂 Payload | 允許外部系統客製 webhook payload 結構 | 2d | ⬜ |

### 1.6 通知服務

| # | 工項 | 說明 | 估時 | 狀態 |
|---|------|------|------|------|
| 29 | NotifyConfig CRUD API | 流程定義的通知渠道配置 | 2d | ✅ `/api/admin/notify-configs` |
| 30 | NotifyTemplate CRUD API | 通知模板管理、變數替換引擎 | 2d | ✅ `/api/admin/notify-templates`，`${var}` 替換 |
| 31 | Email 通知完整實作 | 模板渲染 + 發送（spring-boot-starter-mail 已引入） | 2d | ✅ 收件人仍寫死為 `userId@company.com` |
| 32 | Teams 通知整合 | Microsoft Teams webhook 推送 | 2d | ⬜ |
| 33 | 通知觸發事件完整化 | 任務指派、認領、加簽、催辦、退回、拒絕、完成、超時預警 | 3d | 🟡 只會發出 `task_assigned`；退回、拒絕、完成、加簽、催辦、逾時都沒有發送端 |

### 1.7 BPMN Lint 驗證（Service 已建，規則需補齊）

| # | 工項 | 說明 | 估時 | 狀態 |
|---|------|------|------|------|
| 34 | formKey 存在性驗證 | 呼叫 form-service 確認 formKey 對應表單存在 | 1d | ✅ 併入後直接呼叫 `FormService` |
| 35 | EL 函數白名單驗證 | 僅允許 orgService/permService/bpmQueryService 的合法方法 | 1d | 🟡 bean 層白名單＋執行期 `setBeans()`；未逐一檢查方法 |
| 36 | 外部系統流程 Lint | 檢查允許外部發起的流程第一個 UserTask 不使用 initiator EL | 1d | 🟡 規則 h 已可執行，但嚴重度仍是 warning |
| 37 | ExclusiveGateway default flow 驗證 | 確保每個 Gateway 都有 default sequence flow | 0.5d | ✅ 只要求「每條出線都有條件」的閘道 |
| 38 | Service Task 錯誤邊界事件驗證 | 確保 Service Task 都有 Error Boundary Event | 0.5d | ✅ warning |

### 1.8 稽核 Log（已合併，需完善）

| # | 工項 | 說明 | 估時 | 狀態 |
|---|------|------|------|------|
| 39 | Hash chain 完整性驗證 API | `/api/audit-logs/integrity-check` | 2d | ✅ 逐筆走鏈，v2 雜湊涵蓋全部欄位 |
| 40 | 匯出 CSV/Excel | `/api/audit-logs/export`，匯出操作本身也記錄 | 2d | ⬜ |
| 41 | 異常操作偵測 | 短時間大量審批、異常存取模式偵測 + 告警 | 3d | ⬜ （`UnreachableTaskListener` 只告警沒人看得到的任務，不算異常偵測） |
| 42 | 操作類型完整覆蓋 | 確保所有操作類型都有對應的 publish 呼叫 | 2d | ✅ `OperationTypeCoverageTest` 守住；未實作的操作列在 `NOT_YET_IMPLEMENTED` |

### 1.9 通用 Delegate Bean（全新開發）

| # | 工項 | 說明 | 估時 | 狀態 |
|---|------|------|------|------|
| 43 | EmailNotifyDelegate | 流程節點中觸發 Email 通知 | 1d | ⬜ |
| 44 | TeamsNotifyDelegate | 流程節點中觸發 Teams 通知 | 1d | ⬜ |
| 45 | ESignDelegate | 觸發電子簽章 + 等待 Callback 喚醒 | 3d | ⬜ |
| 46 | ErpSyncDelegate | 同步資料到 ERP 系統 | 2d | ⬜ |
| 47 | DynamicAssigneeDelegate | 運行時動態計算審核人 | 2d | 🟡 無 Delegate，但 `assigneeResolver`／`getManagerAtLevel` 已涵蓋部分需求 |
| 48 | DataValidationDelegate | 流程中資料驗證邏輯 | 1d | ⬜ |
| 49 | ExternalApiDelegate | 通用外部 API 呼叫（可配置 URL/method/payload） | 2d | ⬜ |

### 1.10 基礎設施 / 運維

| # | 工項 | 說明 | 估時 | 狀態 |
|---|------|------|------|------|
| 50 | JVM 記憶體配置 | Dockerfile 加入 JAVA_TOOL_OPTIONS、docker-compose resource limits | 0.5d | ⬜ Dockerfile 無 `JAVA_TOOL_OPTIONS`；prod compose 只有 mssql 有記憶體上限 |
| 51 | RabbitMQ DLQ 告警 | Dead Letter Queue 消費者 + 告警通知 | 1d | 🟡 `DeadLetterConsumer` 只記 ERROR log；缺主動告警 |
| 52 | 多版本流程並行處理 | 確保新案用新版、舊案繼續舊版的邏輯正確 | 1d | 🟡 依賴 Flowable 預設行為＋表單版本鎖定；無專門測試 |
| 53 | BPMN 環境變數替換 | 部署時依環境替換 `${ENV_*}` 變數 | 1d | ⬜ |

---

## 二、Form Service

| # | 工項 | 說明 | 估時 | 狀態 |
|---|------|------|------|------|
| 54 | 表單版本管理 | version 自增、歷史版本查詢、依版本取 schema | 2d | ✅ 改版路徑、依版本取 schema、撞號重試 |
| 55 | 表單 Schema 驗證 | 提交時驗證 dataJson 符合 schemaJson 定義 | 2d | ⬜ |
| 56 | 動態選項（API 載入） | 下拉選單 options 支援從外部 API 動態取得 | 2d | ⬜ |
| 57 | 檔案上傳支援 | 檔案上傳元件對應的 storage + API | 3d | ✅ `AttachmentController`：路徑圍堵、物件層授權、稽核人員唯讀調閱 |
| 58 | 表單資料更新 | 退回修改時 PUT form-data 的版本控制邏輯 | 1d | 🟡 `PUT /api/form-data/{id}` 可用；缺版本控制與本人／退回狀態檢查 |
| 59 | 封存/刪除保護完善 | archived 狀態完整測試、流程中使用的表單不可封存 | 1d | 🟡 只有 draft 可刪、published 可封存；缺「流程使用中不可封存」 |

---

## 三、跨服務整合 / 端到端

| # | 工項 | 說明 | 估時 | 狀態 |
|---|------|------|------|------|
| 60 | 流程啟動完整流程 | 前端提交 → form-data 儲存 → variables 設定 → 流程啟動 → formVersion 鎖定 | 2d | 🟡 啟動＋formVersion 鎖定已有；form-data 不在同一交易；**initiator 取自 body**（見 #66） |
| 61 | BPMN 部署流程 | bpmn-js 設計 → Lint 驗證 → Git commit → 部署 Flowable → 版本管理 | 3d | 🟡 lint → 部署 → 稽核記 SHA-256；缺 Git commit |
| 62 | 認證授權整合 | Sa-Token / JWT 對接、API Gateway 層 JWT 驗證 | 3d | ✅ 後端：JWT 驗證＋信任閘道、預設 denyAll（R-01）；前端 OIDC 流程依決策延後 |
| 63 | 單元測試 | bpm-core Service/Controller 層單元測試 | 5d | 🟡 約 280 個測試，偏回歸與安全守衛，非系統性覆蓋 |
| 64 | 整合測試 | 流程端到端測試（啟動→審核→完成）、外部系統接入測試 | 5d | 🟡 Testcontainers＋`acceptance/`（TC-A01／A02／A04）＋`acceptance-test.sh` |
| 65 | API 文件 | Swagger/OpenAPI 文件產生、外部系統對接文件 | 2d | ⬜ |

---

## 四、2026-09-29 核對時新增的工項

逐項核對時發現、不在原 65 項內的缺口。

| # | 工項 | 說明 | 估時 | 狀態 |
|---|------|------|------|------|
| 66 | 內部發起流程的 initiator 改由 JWT 決定 | ✅ **2026-09-29 完成**（`0b3e7d8`／`4d6dd98`）。`@CallerId` 決定 initiator 與稽核 operatorId；body 帶 initiator 明確 400（對齊 R-20）；`variables` 套用 `TaskController` 的 deny-list，擋掉夾帶 `onBehalfOf`（繞過 R-20 授權）與 `_externalSystemId`（繞過 R-09）；`DocumentController.createdBy` 同步修 | ~~1d~~ | ✅ |
| 67 | Webhook 投遞接線 |**三段各自獨立斷線**（非一段）：(A) 沒有任何程式碼設定 `__webhookUrl`（自 2026-04-17 起從未存在過設定端）；(B) `WebhookTaskListener` 未被 BPMN 引用、也不在 `setBeans()`；(C) 前端 `WebhookProps.js` 寫進 BPMN `documentation`，後端零讀取。⚠️ 三種格式互不相通：spec 說 `extensionElements`、前端寫 `documentation`、consumer 期待 Rabbit payload 欄位。#25 缺 `task.timeout` 與候選人/operatorName/comment 欄位。規模 8～12 檔案，原估 2d 偏低。**已決策：仍是需求；設定來源待定（建議 `extensionElements`）** | 2d→4d || ⬜
| 68 | R-20 剩餘項 |**a/b/d 已於 2026-09-30 完成**（詳見各自條目）。四小項狀態：<br>**a** ✅ admin UI 開關 —— `ExternalSystemAdmin.vue` 加上 `allowOnBehalfOf` 開關與列表欄位，並修掉 `resetForm()` 的 **`Object.assign` 不刪鍵 → 授權繼承**（`applyForm()` 改成先刪鍵再賦值）。⚠️ **連帶修掉兩項原描述未提到的**：(1)「建立外部系統」按鈕原本只有 `showCreate = true`、不重設表單，於是「編輯 A → 取消 → 建立」會送出**對 A 的 PUT**（靜默的）；(2) `editSystem` 的 `Object.assign(form, row)` 讓 `id`／`apiKey`／`lastUsedAt` 一起進 payload（後端擋掉，但那是後端的防護）。⚠️ **發現並補上 `enabled`**：後端 PUT 是整欄覆寫且 `enabled` 是 NOT NULL，原來 `enabled` 是靠 `Object.assign(form, row)` **意外**帶進去的 —— 改用明確欄位清單時若漏掉，「只為了改代發授權而按儲存」會 500。<br>**b** ✅ 前端代發標示 —— 新增 `OnBehalfOfLookup` service，`/api/tasks` 與 `/api/history/tasks` 各多一個 `onBehalfOf` 欄位；`TaskInbox.vue` 標「代 user001 發起」、`DocumentDetail.vue` 在表單上方加警告條。⚠️ **授權是零新增揭露，已用測試釘住**：改動前審核人早就能從 `GET /api/process-instances/{id}/variables` 讀到 `onBehalfOf`（`requireReadAccess` 回整包流程變數），本項只是把它移到值該出現的地方。**刻意只放 `onBehalfOf`（人），不放 `initiator`（`system:<id>`）** —— 後者對「該問誰補件」毫無幫助卻多一個揭露面（誰送進來的），屬產品決定，已回報 PM。系統身分（`system:*`）在顯示端被濾掉，與 `ApplicantResolver` 同一條防線<br>**c** ✅ 已由 #83 一併完成（`ApplicantResolver` bean、3 個補件 UserTask、`UnreachableTaskListener` 告警繞過）<br>**d** ✅ lint rule h 升 error —— ⚠️ **實測確認 `seed-data.sh` 不會被擋**，但理由與 backlog 原本記的不同：**不是**「無 seed SQL 所以 `isExternalAllowed` 恆為 false」，而是**出廠兩支 BPMN 的第一關是 `${assigneeResolver.resolve(execution)}`、字串裡沒有 `initiator`**，所以規則 h 根本不觸發 —— 這在**已授權外部系統存在時也一樣**。已加兩條測試釘死：真的走 `POST /api/deployments` 部署兩支 BPMN 必須 200，以及第一關的 assignee 不得含 `initiator`。⚠️ **負向控制組實測：把 severity 改回 warning 時，「部署 200」那條仍然是綠的** —— 它證明的是「出廠 BPMN 不觸發規則 h」，不是「升級安全」，所以才需要第二條測試把原因釘死。升級的實際價值是：擋下**未來**被改成 `${initiator}` 的 BPMN（#83 的形狀） | ~~2d~~ | 🟡 a/b/d ✅、c ✅（#83）
| 69 | 不存在的流程 key 回 500 | ✅ **2026-09-29 完成**（`0b3e7d8`）。key 為 null/空 → 400；查不到定義 → 404（並 catch `FlowableObjectNotFoundException` 補 race window）。⚠️ 範圍比原描述廣：key 缺席與空字串原本也全是 500。`ExternalApiController` 的同一個洞未修（見 #80） | ~~0.5d~~ | ✅ |
| 70 | Spring Boot 4 + Flowable 8 升級 |Boot 3.5 已於 2026-06-30 EOL；兩者必須同步跳。計畫見 `docs/plan/2026-09-28-springboot4-upgrade.md` Stage 5～6 | 22d || ⬜
| 71 | 讀端授權：可列任何人的案件、可讀任何案件的變數 | ✅ **2026-09-29 完成**（`17896e0`／`15f58cf`）。四個 🔴 端點（`/api/process-instances`、`/api/history/process-instances`、`/api/tasks`、`/{id}/variables`）＋ 兩個新端點 `/involved`（執行中與歷史）。共用 `ProcessAccessGuard` 與 `CandidateGroupMembership`。**剩餘項目見 #74～#78** | ~~2d~~ | ✅ |
| 72 | `FormDataController.submittedBy` 可冒用 | ✅ **2026-09-29 完成**（`edfd118`）。`GET` 加 `requireReadAccess` ＋ 稽核旁路留痕；`PUT` 加 `getDataById` → `requireParticipant`（原本任何登入者都能改寫他人表單）；`POST`／`PUT` 的 `submittedBy` 改由 `@CallerId` 決定並明確 400。`FormService` 新增 `getDataById` | ~~0.5d~~ | ✅ |
| 73 | 錯誤回應看不到訊息 | ✅ **2026-09-29 完成**（`2bb3430`）。`DeliberateErrorMessageAttributes` 只在「沒有任何例外傳到容器」時回傳 `jakarta.servlet.error.message` → 對意外例外結構性不可能成立，嚴格強於 `include-message=always`。⚠️ 實際範圍比「只有 ResponseStatusException」略寬（Spring 自己的 `ErrorResponse` 理由字串也在內，兩者在 `/error` 屬性上無法區分） | ~~0.5d~~ | ✅ |

| 74 | 任務動作缺少持有者檢查（**本輪最嚴重**） | ✅ **2026-09-29 完成**（`c3f042d`）。`PUT /api/tasks/{id}` 從未比對呼叫者是否為 assignee／candidateUser／候選群組成員 → **任何登入者可批准或拒絕任意請假單、任意採購單**（實測：無關的 user002 簽掉 assignee=mgr001 的任務，回 200，流程走完 PROCESS_COMPLETE）。且這是前端實際在用的表單寫入路徑。新增 `TaskHolderGuard` 供讀寫兩端共用（含 owner，讓 delegate／resolve 不被自己打死） | ~~1d~~ | ✅ |
| 75 | 加簽的授權與持久化 DoS | ✅ **2026-09-29 完成**（`15f58cf`）。`CountersignController` 三個端點全接 `TaskHolderGuard`。`POST` 原本可讓攻擊者建立子任務使受害者父任務被 409 **永久**擋住（DoS）；稽核 operatorId 原本記的是被指派人而非呼叫者 | ~~1d~~ | ✅ |
| 76 | `GET /api/history/tasks` 是枚舉鑰匙 | ✅ **2026-09-29 完成**（`15f58cf`）。原本不帶參數回傳**全公司**所有已完成任務，是 #74／#77／#78／#79／#80 五個 id-based 端點的 taskId 發射台。`assignee` 帶他人 → 400；帶 `processInstanceId` → 驗參與者（`ApprovalTimeline.vue` 刻意不傳 assignee，審核人仍看得到完整軌跡） | ~~0.5d~~ | ✅ |
| 77 | 表單 schema 改寫無 ADMIN 限制 | ✅ **2026-09-29 完成**（`cc530df`）。`/api/forms/**` 掛在 `/api/** → authenticated()`，任何登入者能三步改版並發布全公司審核表。⚠️ 依 spec §8.5（欄位 id == 變數名）能改 schema 就能加一個欄位叫 `approved`。新增權限碼 `bpm:form:design`（**非** ADMIN，因會擋掉「業務人員自行設計」的產品定位），讀維持登入即可 | ~0.5d | ✅ |
| 78 | 稽核的 ROLE_ADMIN 旁路是側門 | ✅ **2026-09-29 完成**（`cc530df`）。`SecurityConfig` 讓 `/api/audit-logs/**` 接受 ADMIN，但 `AuditEvent.detail` 帶整包流程變數，而 `ProcessAccessGuard` 明確拒絕 ADMIN 讀同一批資料 → 側門。已移除。⚠️ **之後讀稽核要用 `dev-token.sh dir001`**，不要用 `admin001 admin` | 0.2d | ✅ |
| 79 | 簽核意見零授權 | ✅ **2026-09-30 完成**。三個端點（`GET /api/tasks/{id}/comments`、`GET /api/history/tasks/{taskId}/comments`、`POST /api/tasks/{id}/comments`）新增 `ProcessAccessGuard.requireTaskReadAccess`／`requireTaskParticipant` —— **規則與時間軸／variables／附件完全同一條**（關係人 ∪ `audit:log:read`，旁路必留痕；寫入端不開旁路）。連「taskId → 哪個案件」都收進守衛，理由是授權規則只能有一份。線上實測：`user002`／`mgr002` 由 200 變 404，審核人逐筆讀完自己參與案件的完整軌跡（含 assignee 不是自己的補件關卡）。**⚠️ 原描述「`userId` 靠 `firstNonBlank` 順序僥倖」經實測<b>不成立</b>**：`req.userId()` 從未生效（`callerId` 優先，且守衛保證它非空白），冒用沒有成功 —— 真正的缺陷只是授權缺失。`userId` 欄位維持現狀，屬 #66／#72／#81 同型的另案。**⚠️ 連帶修掉**：對不存在的 taskId 留言由裸 500 → 404。**新發現（已回報 PM）**：對**已完成**的關卡留言仍是裸 500（`AddCommentCmd` 的既有行為，改動前完全相同），✅ **使用者已裁決：回 404**。🔧 **待實作** | ~~1d~~ | ✅ |
| 80 | `GET /api/process-instances/{id}/bpmn-xml` 與 `GET /api/documents` | ✅ **2026-09-30 完成**（`28e44ef`）。`bpmn-xml` 與 `GET /api/documents/{id}` 接 `ProcessAccessGuard.requireReadAccess`（關係人 ∪ `audit:log:read`，旁路必留痕，非關係人 **404**）；`GET /api/documents` 接 `requireSelf`。**規則與 variables／附件／表單資料／簽核軌跡完全同一條**——沒有新造第四組。⚠️ **政策決定（2026-09-30 使用者已裁決：維持，列為上線檢查清單）**：`/api/documents` 選**自己建立的**而非「自己參與的」：省略 `createdBy` = 呼叫者、帶他人 = 400。放棄了「全公司公文清單」與稽核旁路（稽核職能改走 `/api/audit-logs` 的 `PROCESS_START` 事件或 `GET /api/documents/{id}` 的旁路）。**關鍵前提：沒有任何前端呼叫這個端點**（`grep` 零命中），所以收斂範圍不會讓畫面壞掉；日後若需要應**新增** `/api/documents/involved` 而非把這個放寬回去。**連帶修掉兩項原描述未提到的**：(1) `GET /api/documents/{id}` **同樣零檢查**——只修列表等於沒修，documentId 仍可從稽核紀錄取得逐筆列出；(2) `ExternalApiController` 啟動不存在的流程 key 的**裸 500**（#69 條目自己指名留給本工項的別名，`allowedProcessKeys` 是自由文字無必填驗證 → 管理員打錯一個字就踩到，而批次重試住列會無限重試）。⚠️ **bpmn-xml 對「已結案」維持 200 + 空圖**（`ProcessDiagram.vue:23` 依賴它顯示「無流程圖資料」；「從未存在」已由守衛擋成 404）。⚠️ **孤兒公文**（`processInstanceId` 為 null，`create()` 先存檔再啟流程的失敗殘留）**只有建立人讀得到**——它沒有案件可「參與」。負向控制組：三個 controller 整份還原 → bpmn/documents **19 條中 12 條紅**（綠的 7 條逐條記在測試 javadoc，其中「`?createdBy=自己` 缺陷期間本來就正確」證明壞的只有兩個分支，支撐了收斂成單一規則的決定）、external **4 條中 1 條紅**。後端 **473** 測試全綠（基線 450 + 新增 23）。**PM 線上實測已完成**（合併後主樹 **482** 全綠、acceptance-test PASS 7/FAIL 0）：`bpmn-xml` 非關係人 404／關係人與稽核職能 200、從未存在的 pid 404（不再是 200 空圖）、`/api/documents` 帶他人 `createdBy` 回 400 且訊息指名該怎麼改、`ExternalApiController` 已授權但未部署的 key 回 404（原本 500）。**⚠️ 防枚舉順序線上確認**：未授權的 key（`purchase-approval`）回 **403** 且不因流程存不存在而改變 —— 沒有 discovery oracle | ~~0.5d~~ | ✅ |
| 81 | `POST /api/forms` 的 `createdBy` 可冒用 | ✅ **完成**。`FormDefinitionController.create` 接上 `ProcessAccessGuard.requireSelf`（**不**另寫一份判斷），`createdBy` 一律由 `@CallerId` 決定。⚠️ **負向控制組揭露後果比原描述嚴重**：缺陷期間省略 `createdBy` 時資料庫存的是 **`null`** 而不是呼叫者 —— 不只是「可冒用」，而是**正常呼叫下這欄根本是空的，每一張經本端點建立的審核表都沒有作者**（測試印出 `expected: "mgr001" but was: null`）。`bothCreateEndpointsAgreeOnCreatedBy` 印出的 `[null, "mgr001"]` 就是 backlog 說的「兩個端點不一致」的實證：v1（POST）沒作者、v2（revisions）有。**危害邊界要說清楚**：`audit("FORM_UPDATE", userId, …)` 傳的一直是 `@CallerId` 而非 `def.getCreatedBy()`，所以**稽核從未被污染**，缺陷的影響面只有資料列那一欄（與 #66／#72 的「operatorId 一起被冒用」不同）。**前端相容性：零風險**（實查 `FormEditor.vue:42` 的 payload 只有 `{name, formKey, schemaJson}`，`formApi.createForm` 原樣轉發，`bpm-frontend/src` 全樹 grep 不到 `createdBy`；`seed-data.sh` 與 `README-testing.md` 的 curl 也都沒有）—— 與 #79 刻意**不**改 `CommentRequest.userId` 的情況不同，那個前端真的在送 `'current_user'`。`PUT /api/forms/{id}` **刻意不**加守衛：`FormService.update` 只搬 `name` 與 `schemaJson`，`createdBy` 留在原資料列上，本來就沒有可冒用的欄位。新增 `FormDefinitionIdentityTest` 9 條，負向控制組紅 7 綠 2（綠的兩條防的是「修法打死合理呼叫」與「未來把守衛上移到 filter 層」） | ~~0.3d~~ | ✅ |
| 88 | `firstTaskAssignee` 完全沒有驗證 | ✅ **2026-09-30 完成**。新增 `external/ExternalActorGuard`（`requireKnownPerson(field, userId)`），**三層、由便宜到昂貴**：① 空白 → 400（不打網路）② `ExternalActorIdentity.isSystemActor`（`system:` 前綴，**不區分大小寫**）→ 400（不打網路）③ **其餘一律 `orgService.getDirectManager()` 問組織系統**，任何例外 → 400（**fail-closed**）。**`onBehalfOf` 原本就有的 inline 驗證一併收進同一個類別**（規則只能有一份；行為不變，未知員工仍是 400）。⚠️ **為什麼「問組織系統」是主要規則而不是只擋前綴**：`ExternalActorIdentity` 主張「前綴比對就夠、不要打網路」的三個理由**只在 server 獨佔該命名空間時成立**（`initiator` 由 R-20 保證），而 `firstTaskAssignee` 由呼叫端自由指定、不受那個保證。缺陷的實質是「**任何組織系統不認識的字串**」而不是「`system:` 這個形狀」—— 全形 `ｓｙｓｔｅｍ：x`、拼錯的工號、前後空白、空白字串都一樣沒有人能簽。已回頭在 `ExternalActorIdentity` 加上反向警告，避免下一個人以為可以刪掉組織查詢。⚠️ **前綴比對仍保留為獨立一層**（不是多餘）：本專案自己的 `MockOrgController` 整整兩輪都是 **fail-open**（對不認識的 id 回 `mgr001`），而那正是 #83 缺陷兩輪沒被任何測試抓到的原因 —— **「組織系統說這個人存在」只有在它 fail-closed 時才是可信證據**。⚠️ **存在性判斷看的是「有沒有拋例外」而不是回傳值**：`dir001`／`admin001` 位於組織鏈頂，`getDirectManager` 回 `null` 代表「他存在但沒有主管」，寫成 `== null → 拒絕` 會擋掉總監本人（已用 `chainTopAssigneeStillStarts` 釘住）。⚠️ **政策（2026-09-30 使用者已裁決）**：組織系統不可用時 **fail-closed**（連線逾時／5xx／404 一律 400，並記 `log.warn`）。代價是組織系統掛掉時外部系統**全部發不起流程**，而且回 400（呼叫端通常不會重試）。✅ **裁決：組織系統「故障」回 503**（非 400）—— 守衛在啟動之前，重試是安全的；「查無此人」仍回 400。🔧 **待實作**。空白 assignee 刻意**拒絕**而不是當成「未指名」：缺陷期間 `firstTaskAssignee: ""` + 候選群組會讓 `setAssignee(taskId, "")` 產生 assignee 為空字串的任務，而 Flowable 候選群組查詢帶 `RES.ASSIGNEE_ is null`（`Task.xml:856`）→ **群組成員看不到 → 靜默卡死，且 `UnreachableTaskListener` 明確不告警**（`UnreachableTaskAlertTest.taskWithCandidateGroupIsNotAlerted`）。前後空白**不靜默 trim**：對組織系統那是另一個人，靜默修掉會讓呼叫端永遠不知道自己送錯了。**狀態碼 400**（不是 403/404：呼叫端該改的是 payload），且**排在兩個 403 之後**（不破壞 #80 的防枚舉順序，有 `authorizationStillWinsOverTheNewCheck` 釘住）。**PM 線上實測已完成**（合併後主樹 **534** 全綠、acceptance-test PASS 7/FAIL 0）：擋下 `system:evil`／`SYSTEM:x`／全形 `ｓｙｓｔｅｍ：x`／`nobody-x`／空字串／前後空白 六種形狀，放行 `mgr001`／`dir001`（鏈頂）／候選群組 `hr:leave:approve` 三種；**被拒的 6 筆在 `ACT_HI_PROCINST` 全部查無流程實例（無副作用）、放行的 3 筆各 1 筆**。另查證 `ApplicantResolver`（#83 的成果）經 `git diff` 確認**只有註解更新、零邏輯變動**，`UnreachableTaskListener` 未被修改。⚠️ **候選群組刻意不驗證（本工項最大的未完成項）**：候選群組名稱在本 repo 有**三個互質來源**（部門代碼／權限碼／JWT authority，見 `CandidateGroupMembership` 類別註解），只有部門代碼有存在性 API（`getDeptMembers`），權限中心只能由人反查、列不出權限碼全集，JWT authority 更不在管轄範圍 → 要驗就必須**假設每個群組都是部門**，那會擋掉 `hr:leave:approve` 這種**本專案自己的 BPMN 就會產生**的形狀（已用 `permissionCodeGroupIsAccepted` 釘住反向）。它的兩種危害（群組不存在 → 靜默卡死；把案件丟進任意特權群組 → **授權範圍**問題）都需 PM 裁決，合理下一步是 `allowedCandidateGroups` 白名單或權限中心補群組存在性 API。新增 `ExternalActorGuardTest` 10 條（純單元，含「每層有沒有打網路」的斷言）＋ `ExternalFirstTaskAssigneeTest` 15 條（真實 HTTP，每條拒絕都驗**流程實例數與稽核筆數都沒變**）。**負向控制組實測**：`ExternalApiController.java` **整份還原**成 HEAD → **15 條中 6 紅 9 綠**，紅的 6 條全部是 `expected: 400 but was: 200`（**流程真的被啟動了**），涵蓋系統身分／大小寫／查無此人／全形前綴／空白／前後空白；綠的 9 條是刻意的非回歸對照（6 條放行組＋1 條授權順序＋2 條 `onBehalfOf` 改動前就正確）。⚠️ **線上實測待 PM 統一進行**（獨立 worktree，不啟容器） | ~~0.5d~~ | ✅ |
| 89 | `UnreachableTaskListener` 漏掉「assignee 是空字串 + 有候選人」 | 🟠 **2026-09-30 修 #88 時發現，是 #83 修漏的第二個實例**。`UnreachableTaskListener:138` 的判準是 `if (hasCandidate && !assigneeIsSystemActor) return;` —— 只檢查了系統身分，**沒檢查 assignee 是否為空字串**。而 `setAssignee(taskId, "")` 產生的是空字串（不是 null），Flowable 候選群組查詢帶 `RES.ASSIGNEE_ IS NULL`（`Task.xml:856`）不命中 → **群組成員看不到任務，但 listener 不告警**。比 `system:` 更糟：外部 API 入口已被 #88 堵住，但 **BPMN 的 `flowable:assignee=""` 字面值、管理員部署的流程仍能產生它**。修法很小（把第 126 行 `!assignee.isBlank()` 的判斷一併納入「沒有人能動它」），但那是 listener 的判準改動、屬另一個工項。⚠️ 現有測試 `UnreachableTaskAlertTest.taskWithCandidateGroupIsNotAlerted` 用 assignee=null（那個是對的），**空字串這一格沒有測試** | 0.3d | ⬜ |
| 90 | 申請人端也沒有代發標示 | 🟡 **2026-09-30 修 #68b 時發現**。後端 `onBehalf` 布林值早就在 `GET /api/process-instances` 的回應裡，但 `MyApplications.vue` **從來沒有渲染它** —— 員工看到一張自己沒送過的單，畫面一樣不解釋是誰代送的。#68b 修的是「審核人端」缺口（主管要知道代誰發起）；這是**申請人端**的對稱缺口。純前端，估時約 0.2d | 0.2d | ⬜ |
| 82 | 前端沒有權限碼的概念 | 🟠 **`bpm:form:design` 在 UI 上看不到效果**。`bpm-frontend/src/services/session.js` 只讀 JWT 的 `roles` claim，而權限中心的權限碼**不在 token 裡**；`router/index.js:24,28` 的 `/admin/form-editor` 與 `/admin/forms` 是 `requiresRole: 'admin'`。所以只持有 `bpm:form:design` 的業務人員後端放行但前端擋掉。這不是 #77 的 regression（那兩條路由本來就要求 admin），但它讓產品價值看不到。修法需要新的資料來源：後端 `/api/me/authorities` 端點，或 IdP 簽發時把權限碼放進 token —— **架構決定** | 1.5d | ⬜ |
| 83 | assignee 為 `system:<id>` 的任務沒有人能簽 | ✅ **2026-09-30 完成**。新增 `ApplicantResolver` bean，3 個補件 UserTask（`applicantRevision`／`revisionFromManager`／`revisionFromFinance`）由 `${initiator}` 改為 `${applicantResolver.resolve(execution)}`；三段規則 `onBehalfOf` → `initiator`（是人的話）→ **權限碼 `bpm:external:revision` 指定的受理人**。**政策（2026-09-30 使用者已裁決：維持權限碼方案，列為上線檢查清單）**：第三段選「沿用既有權限中心」而非外部系統設定檔新欄位 `defaultHandler` —— 後者要 DB schema 變更＋migration＋entity＋admin API＋管理頁＋前端，且一個欄位只能指定一個 userId，而「這張單沒有申請人」是所有外部系統共用的事實；權限中心這條路**零 DB 變更、零前端改動**，且 `getFirstAvailableUser` 已處理代理人／休假（用靜態 userId 會把關卡派給不在的人＝同一缺陷換皮）。**找不到受理人時拋例外而非回 null**（回 null 是換一種方式製造靜默卡死），拋錯會讓「退回」整個回滾——主管當場看到錯誤、案件仍停在他手上。⚠️ **連帶修掉 `UnreachableTaskListener` 的告警繞過**：判準從「assignee 是否 null／空白」改成「有沒有人能動它」，`system:` 前綴（不區分大小寫，因 `firstTaskAssignee` 是 body 自由字串且無驗證）也告警，並加 `reason` 欄位分流兩種成因。**候選人救不了這種任務** —— Flowable 的 `taskCandidateUser` 帶 `ASSIGNEE_ IS NULL`，兩個條件互斥而非互補。負向控制組實測：整份還原後 8 紅 5 綠，紅的全是系統發起／代發形狀，綠的兩條是「不得放寬守衛」「不得改派人工單」的非回歸對照。⚠️ **必須在真實權限中心指派 `bpm:external:revision`**，否則外部系統發起的案件連「退回」都會 500。**PM 線上實測已完成**（合併後主樹 **509** 全綠、acceptance-test PASS 7/FAIL 0）：外部系統發起 → `mgr001` 退回 → 補件關卡 `assignee = dir001`（**不再是 `system:<id>`**）→ 無關的 `user002` 簽得到 **404**（守衛未鬆）→ `dir001` 簽得到 **200** 且流程回到「主管審核」給 `mgr001`。**整條回路打通 = 案件不再靜默卡死的實證**。另 `TaskHolderGuard` 經 `git diff` 確認**只有註解變更、零邏輯改動** | ~~1d~~ | ✅ |
| 84 | `NotifyAdminController.updateConfig` 未驗 templateId | ✅ **2026-09-29 完成**（`d4ddfae`）。抽出 `requireExistingTemplate` 給 create/update 共用（不是 DRY，是**規則只能有一份**：缺陷本身就是分散造成的 —— create 有擋、update 沒有，而 `deleteTemplate` 的註解宣稱兩者都擋了，描述的是不存在的行為）。⚠️ **實測到的後果與原描述不同**：P1-13 已替 `EmailConsumer` 加上消費端防護，所以線上實測是**設定被靜默忽略**（記 WARN、改用預設模板）而非通知永久遺失。危害較小但**更難察覺** —— 沒有例外、沒有錯誤訊息、通知照常寄出，後台還顯示「設定成功」。線上實測：不存在的 templateId／null／`""`／全空白 → 400 且五個欄位全未變、稽核無紀錄；有效 templateId → 200 且 `enabled` 真的翻轉；不存在的 id → 404；非管理員 → 403 且資料未變。**前端無相容性風險**（`grep` 確認 `bpm-frontend/src` 零 `notify-config` 呼叫） | ~~0.3d~~ | ✅ |
| 85 | `ProcessVariableSpecController.update` 稽核說謊 | ✅ **2026-09-29 完成**（`97eafd7`）。`findById` 後比對 `existing.getProcessDefinitionKey()`，不符回 **404**（非 403，沿用 `ProcessAccessGuard` 政策：403 會確認物件存在，對可枚舉的 id 等於留枚舉管道）。稽核改寫 `existing` 的 key；被擋時不寫稽核。連帶修掉 `orElseThrow()` 讓不存在的 id 從 **500 → 404**（修前 500 會讓呼叫端一直重試）。**端到端已驗**：`required` 決定 `ExternalApiController.validateVariables` 擋不擋，所以 required=true 時外部請求缺該變數回 400 → 被擋的冒用後**仍然**400（輸入驗證沒被放寬）→ 用正確 key 改成 false 後同一請求變 **200**，證明 400 確實來自那一筆規格。⚠️ 不選「靜默忽略 key 不一致、以 id 為準照樣更新」：那樣稽核仍會寫錯的 key，且回 200 讓呼叫端以為改對了 | ~~0.3d~~ | ✅ |
| 86 | `ProcessVariableSpecController.batchSave` 重複儲存必定 500 | ✅ **2026-09-30 完成**。`POST /api/admin/process-definitions/{key}/variable-spec` 是「整批取代」，原本先 `deleteByProcessDefinitionKey(key)`（衍生刪除 → `em.remove()`）再 `saveAll`（→ `em.persist()`）。**Hibernate 在同一次 flush 中把 INSERT 排在 DELETE 之前**，撞上 `@UniqueConstraint(processDefinitionKey, variableName)` → **500**。⚠️ **原描述「只要該 key 已有任何一筆規格就必定失敗」不精確，實測後修正**：觸發條件是**新批次與既有規格有同名變數**。新舊完全不重疊（整批換新名字）或送空陣列，缺陷期間都是 200 —— 沒有任何 INSERT 會撞到同名舊列。這讓它更難被手動試出來（剛建好規格時第一次存是好的），而管理頁的正常使用流程必然重疊。⚠️ 前端 `ProcessVariableSpecAdmin.vue` 的「儲存」按鈕正是走這條路徑 → **管理頁第二次按儲存必定壞**。**414 個測試沒有任何一個覆蓋重複寫入**。修法：刪除改成 `@Modifying @Query` 原生 JPQL（不選「衍生刪除 + `flush()`」——那樣也能修好，但規則會散在「刪除」與「記得 flush」兩處，而這正是本缺陷的成因）。**不**做「比對後只刪真正消失的列」：那會保留舊 id，而 `setId(null)` 是 P0-4 的防護。線上實測八種呼叫全 200 且稽核逐筆相符，見 commit `f6df3fb` | ~~0.5d~~ | ✅ |
| 87 | `batchSave` 同一批內重複變數名 → 500 | ✅ **2026-09-30 完成**。送 `[{"variableName":"x",…},{"variableName":"x",…}]` 原本仍 500，**根因與 #86 不同**：呼叫端送了互相衝突的資料，與刪除／寫入的順序無關，#86 的修法碰不到它。修法是**輸入驗證**：`requireDistinctVariableNames` 擋在 `deleteAllByProcessDefinitionKey` **之前**（順序決定「被拒的請求有沒有副作用」），回 **400 並指名重複的名字**（500 的語意是「稍後重試」，但重試永遠不會成功 —— payload 沒變結果就不會變）。⚠️ **「重複」不能用 `Set<String>` 判斷**：欄位定序是 `SQL_Latin1_General_CP1_CI_AS`，對 MSSQL 實查（`sqlcmd` + `sys.columns`）確認 **`Amount`／`amount`、`amount`／`amount `（ANSI padding）、`Ａ`／`A`（全形半形）在資料庫層面就是衝突** —— 使用者在表格裡打兩個大小寫不同的名字是最常見的失敗形狀，而 `Set<String>` 恰恰擋不住，修完等於沒修。`dbComparisonKey` 逐條對齊此定序（NFC、全形半形折疊、只 strip 尾端 U+0020、大小寫），**刻意不做**整串 NFKC（會把 `①` 折成 `1`，實測兩者在 DB **不**衝突）與重音折疊（定序是 AS）。26 組對照實測：24 組一致，2 組分歧（`ı`、`ǅ`）**方向都是「誤擋合法資料」而非漏擋**。**連帶修掉 `update` 改名撞到同一流程其他變數 → 500**（同一個唯一約束、共用同一個比較鍵；留下來的話「這個端點的 500 修掉了嗎」仍是無法回答的問題）。前端 `ProcessVariableSpecAdmin.vue` 加**儲存前檢查**（刻意是後端規則的**子集**，方向固定為「前端不得擋掉後端會接受的資料」）；`addRow()` 產生的空名稱是既有行為，**刻意不改**（自動命名等於替使用者決定外部系統要塞進流程的 key，猜錯了不會有人發現）。✅ **使用者已裁決：擋空白名稱**（null 亦改 400）。🔧 **待實作**：單一空白名目前仍 200（測試已釘住現狀），但兩個空白名互相衝突已回 400 —— **使用者已裁決：擋**（理由見 commit 訊息與測試 javadoc）。線上實測 42 項全過（每個狀態碼都同時驗資料未變）、後端 450 測試全綠（新增 11 條，`VariableSpecDuplicateNameTest`）、前端 66 全綠（新增 7 條）、`acceptance-test.sh` PASS 7/FAIL 0 | ~~0.3d~~ | ✅ |
---

## 工項統計

「剩餘估時」為未完成（🟡＋⬜）工項的**原始估時**加總；部分完成的項目實際剩餘會較少，這是上限。

| 類別 | 工項數 | ✅ | 🟡 | ⬜ | 剩餘估時（上限） |
|------|--------|----|----|----|---------|
| 流程引擎核心 | 7 | 1 | 3 | 3 | 13d |
| Org/Perm 正式整合 | 5 | 3 | 2 | 0 | 5d |
| 外部系統接入 | 8 | 7 | 1 | 0 | 1d |
| 非同步/Callback | 4 | 0 | 0 | 4 | 9d |
| Webhook | 4 | 0 | 3 | 1 | 8d |
| 通知服務 | 5 | 3 | 1 | 1 | 5d |
| BPMN Lint | 5 | 3 | 2 | 0 | 2d |
| 稽核 Log | 4 | 2 | 0 | 2 | 5d |
| 通用 Delegate | 7 | 0 | 1 | 6 | 12d |
| 基礎設施 | 4 | 0 | 2 | 2 | 3.5d |
| Form Service | 6 | 2 | 2 | 2 | 6d |
| 跨服務整合 | 6 | 1 | 4 | 1 | 17d |
| 2026-09-29 新增 | 25 | 19 | 0 | 6 | 27d |
| **合計** | **90** | **41** | **21** | **28** | **~113.3 人天** |

原始 65 項的估計總量為 ~125.5 人天（2026-06-08）。

> 2026-09-29 晚間更新：#66、#69 完成（+3 項新發現 #71～#73）。
> #67 的估時由 2d 上修為 4d（三段斷線、8～12 檔案、三種格式互不相通）。
> #71 讀端授權的實際範圍遠大於原先預期的 3 個端點，估時 2d 仍可能偏低。
> 2026-09-30 更新：#84、#85、#86 完成（+1 項新發現 #87）。統計表已重算
> （「2026-09-29 新增」實際是 21 項，不是先前誤植的 8 項）。
> 2026-09-30：#79、#87 完成（後端測試 450、前端 66）。
> 2026-09-30：#80 完成（後端測試 **473**，基線 450 + 新增 23）。**未做線上實測**
> （在獨立 worktree 中進行，同機器另有兩個 agent，資源會衝突）—— 待 PM 統一進行。
> ⚠️ **測試數只有 `mvn verify` 輸出的 `Tests run: N` 是真的** ——
> `target/surefire-reports` 不涵蓋 `@Nested` 內類別，逐類加總會少 34 條；
> 併行跑測試時該目錄還會被互相覆寫。

---

## 優先級建議

> 2026-09-29 註：P0 中 #62 認證（後端）與 #54 表單版本管理已完成；#1、#2 退回／駁回的主幹可用，
> 缺的是退到任意節點與駁回通知；#8～#12 的快取、失效與組合查詢已完成，剩下去 mock（等權限中心）。
> 新增的 #66（內部 initiator）與 #70（Boot 4 升級）建議列為 P0：前者是身分冒用，後者是 EOL 後無安全修補。

> **2026-09-29 晚間追加的項目（#66、#71～#78）已全部完成並合併進 main**（`a07e250`）。
> 這一輪從「啟動流程的身分冒用」一路查到「任務動作本身沒有持有者檢查」——
> 實測確認任何登入者可以批准或拒絕系統裡的任意請假單與採購單。
>
> **下一輪的 P0 建議**：
> - **#83（`system:<id>` 的任務沒有人能簽）** —— 這是 #74 造成的行為變化。
>   嚴重度雖是功能而非安全，但「案件永久卡死」比「案件被誤簽」更難察覺。
> - ~~#79（簽核意見零授權）~~ —— **2026-09-30 已完成**，「誰審的、審核意見原文」
>   這條路已關閉（規則與時間軸同一條）。
> - **#82（前端接權限碼）** —— `bpm:form:design` 目前在 UI 上完全看不到效果，
>   需要架構決定（權限碼的資料來源）。
> - #80、#81 修法都很短，約 0.8d 全部可關上。
> - **待 PM 政策決定**：對**已完成**的關卡 `POST .../comments` 回 500
>   （`AddCommentCmd` 的既有行為，非 #79 引入）。404（任務已結束）還是
>   409（狀態衝突），以及「審完能不能補留言」是否要支援。
>
> **2026-09-30 追加**：#84、#85、#86 已完成。**#87 建議併入 #80／#81 那一批** ——
> 兩個都是 0.3d 左右的輸入驗證，而 #87 的前端（`addRow()` 產生空 `variableName`
> 且無重複檢查）與後端（回 400 指出重複的名字）要一起改才對得上，
> 只改後端會讓 UI 繼續送出必然被拒的資料。

### P0 — 核心流程可用（必須先完成）
- #1~#2 退件/拒絕機制
- #8~#12 Org/Perm 正式整合（去除 Mock）
- #54 表單版本管理
- #60 流程啟動完整流程
- #62 認證授權整合

### P1 — 企業級功能
- #3~#7 加簽/代理人/催辦/撤回
- #13~#20 外部系統接入
- #21~#24 非同步/Callback
- #29~#33 通知服務
- #39~#42 稽核 Log 完善

### P2 — 進階功能
- #25~#28 Webhook 完善
- #34~#38 BPMN Lint 規則
- #43~#49 通用 Delegate Bean
- #55~#59 Form Service 進階

### P3 — 品質保證
- #63~#65 測試與文件
- #50~#53 基礎設施優化
