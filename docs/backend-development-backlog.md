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
- **測試**：後端約 280 個（Testcontainers：真實 MSSQL／RabbitMQ／Redis），前端 59 個（Vitest）
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
| 66 | 內部發起流程的 initiator 改由 JWT 決定 | `POST /api/process-instances` 仍直接採用 body 的 `initiator`，未與已認證的呼叫者比對 —— 登入的使用者可以用別人的名義送單。外部 API 那條路已由 R-20 修好，這條還沒有 | 1d | ⬜ |
| 67 | Webhook 投遞接線 | 沒有任何程式碼設定 `__webhookUrl`，`WebhookConsumer` 收到即返回；`WebhookTaskListener` 未被 BPMN 引用、也不在 `setBeans()`。#25～#27 的程式碼因此全部不會觸發 | 2d | ⬜ |
| 68 | R-20 剩餘項 | admin UI 的 `allowOnBehalfOf` 開關、前端「代發」標示（API 已回 `onBehalf`）、代發案件補件仍派給 `system:<id>`（需決定 BPMN 語意）、lint rule h 升為 error | 2d | ⬜ |
| 69 | 不存在的流程 key 回 500 | 應回 404 | 0.5d | ⬜ |
| 70 | Spring Boot 4 + Flowable 8 升級 | Boot 3.5 已於 2026-06-30 EOL；兩者必須同步跳。計畫見 `docs/plan/2026-09-28-springboot4-upgrade.md` Stage 5～6 | 22d | ⬜ |

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
| 2026-09-29 新增 | 5 | 0 | 0 | 5 | 27.5d |
| **合計** | **70** | **22** | **21** | **27** | **~114 人天** |

原始 65 項的估計總量為 ~125.5 人天（2026-06-08）。

---

## 優先級建議

> 2026-09-29 註：P0 中 #62 認證（後端）與 #54 表單版本管理已完成；#1、#2 退回／駁回的主幹可用，
> 缺的是退到任意節點與駁回通知；#8～#12 的快取、失效與組合查詢已完成，剩下去 mock（等權限中心）。
> 新增的 #66（內部 initiator）與 #70（Boot 4 升級）建議列為 P0：前者是身分冒用，後者是 EOL 後無安全修補。

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
