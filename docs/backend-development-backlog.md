# Greyhound BPM 平台 — 後端開發工項清單

> 產出日期：2026-06-08
> 基於規格文件 vs 實際程式碼差異分析

---

## 專案現況摘要

- **bpm-core**：61 個 Java 檔案，Controller/Model/Config 層大致齊備，Service 業務邏輯待完善
- **form-service**：11 個 Java 檔案，基本 CRUD 已完成
- **audit-log-service**：已合併入 bpm-core（2026-04-24 架構重構）
- 外圍系統整合（組織/權限）目前仍以 Mock 替代

---

## 一、BPM Core Service

### 1.1 流程引擎核心（部分完成，需補強）

| # | 工項 | 說明 | 估時 |
|---|------|------|------|
| 1 | 退件機制完善 | `returnTo=initiator` 邏輯、BPMN Gateway 退回路由 | 3d |
| 2 | 拒絕（終止）機制 | `rejected=true` 流程終止分支、通知申請人 | 2d |
| 3 | 加簽 - 動態子任務 | 建立 Sub Task、原任務暫停、加簽完成恢復、多人加簽 | 5d |
| 4 | 加簽 - Call Activity | 預定義加簽子流程模板整合、前端 Call Activity 節點配置 | 3d |
| 5 | 代理人機制 | `orgService.resolveEffective()` 考慮代理人、自動轉派 | 2d |
| 6 | 催辦功能 | 催辦 API、觸發通知、防頻繁催辦限制 | 1d |
| 7 | 流程撤回（申請人撤案） | 撤回 API、判斷是否可撤回（第一節點尚未處理） | 2d |

### 1.2 OrgService / PermService 整合（目前為 Mock）

| # | 工項 | 說明 | 估時 |
|---|------|------|------|
| 8 | OrgRestClient 正式實作 | 對接外圍組織系統 REST API（非 Mock） | 3d |
| 9 | PermRestClient 正式實作 | 對接外圍權限系統 REST API | 2d |
| 10 | Redis 快取實作 | 所有 org/perm 查詢加入 Redis TTL 快取 | 2d |
| 11 | 快取主動失效 Webhook | `/api/internal/cache-invalidate/org`、`/perm` 實作 | 1d |
| 12 | BpmQueryService 組合查詢 | `getManagerWithPermission`、`getDeptUsersWithPermission` | 2d |

### 1.3 外部系統接入（Controller 已建，邏輯需完善）

| # | 工項 | 說明 | 估時 |
|---|------|------|------|
| 13 | API Key 認證完整實作 | SHA-256 hash 儲存、X-API-Key 驗證、IP 白名單 | 2d |
| 14 | 外部系統權限檢查 | allowedProcessKeys、allowedActions 驗證邏輯 | 1d |
| 15 | 外部系統 initiator 處理 | `system:{systemId}` 識別、`effectiveInitiator` 設定 | 1d |
| 16 | 外部系統發起流程 | `firstTaskAssignee`、`firstTaskCandidateGroups` 邏輯 | 2d |
| 17 | 外部系統操作節點 | PUT /api/external/tasks/{taskId} 完整邏輯 | 1d |
| 18 | 外部系統查詢流程狀態 | status API、businessKey 查詢 | 1d |
| 19 | API Key 輪替 | rotate-key API、舊 Key 立即失效 | 1d |
| 20 | 外部系統使用紀錄 | usage-logs API | 1d |

### 1.4 非同步流程 / Callback

| # | 工項 | 說明 | 估時 |
|---|------|------|------|
| 21 | Callback 接收端 | HMAC Token 驗證、冪等檢查（Redis SetIfAbsent）、Message Correlation | 3d |
| 22 | External Worker Task 支援 | 輪詢認領機制 | 3d |
| 23 | Timer Event 超時處理 | 超時自動觸發、超時預警通知 | 2d |
| 24 | Signal Event 廣播 | 一對多喚醒流程 | 1d |

### 1.5 Webhook 觸發（Listener 已建，Payload 需完善）

| # | 工項 | 說明 | 估時 |
|---|------|------|------|
| 25 | Webhook Payload 完整化 | 依規格補齊所有事件欄位（task.created、completed、rejected、timeout、process.completed） | 3d |
| 26 | HMAC 簽章實作 | webhook payload HMAC-SHA256 簽章 | 1d |
| 27 | Webhook 重試機制 | 失敗指數退避重試（1s→2s→4s，max 3次）、DLQ | 2d |
| 28 | payloadTemplate 自訂 Payload | 允許外部系統客製 webhook payload 結構 | 2d |

### 1.6 通知服務

| # | 工項 | 說明 | 估時 |
|---|------|------|------|
| 29 | NotifyConfig CRUD API | 流程定義的通知渠道配置 | 2d |
| 30 | NotifyTemplate CRUD API | 通知模板管理、變數替換引擎 | 2d |
| 31 | Email 通知完整實作 | 模板渲染 + 發送（spring-boot-starter-mail 已引入） | 2d |
| 32 | Teams 通知整合 | Microsoft Teams webhook 推送 | 2d |
| 33 | 通知觸發事件完整化 | 任務指派、認領、加簽、催辦、退回、拒絕、完成、超時預警 | 3d |

### 1.7 BPMN Lint 驗證（Service 已建，規則需補齊）

| # | 工項 | 說明 | 估時 |
|---|------|------|------|
| 34 | formKey 存在性驗證 | 呼叫 form-service 確認 formKey 對應表單存在 | 1d |
| 35 | EL 函數白名單驗證 | 僅允許 orgService/permService/bpmQueryService 的合法方法 | 1d |
| 36 | 外部系統流程 Lint | 檢查允許外部發起的流程第一個 UserTask 不使用 initiator EL | 1d |
| 37 | ExclusiveGateway default flow 驗證 | 確保每個 Gateway 都有 default sequence flow | 0.5d |
| 38 | Service Task 錯誤邊界事件驗證 | 確保 Service Task 都有 Error Boundary Event | 0.5d |

### 1.8 稽核 Log（已合併，需完善）

| # | 工項 | 說明 | 估時 |
|---|------|------|------|
| 39 | Hash chain 完整性驗證 API | `/api/audit-logs/integrity-check` | 2d |
| 40 | 匯出 CSV/Excel | `/api/audit-logs/export`，匯出操作本身也記錄 | 2d |
| 41 | 異常操作偵測 | 短時間大量審批、異常存取模式偵測 + 告警 | 3d |
| 42 | 操作類型完整覆蓋 | 確保所有操作類型都有對應的 publish 呼叫 | 2d |

### 1.9 通用 Delegate Bean（全新開發）

| # | 工項 | 說明 | 估時 |
|---|------|------|------|
| 43 | EmailNotifyDelegate | 流程節點中觸發 Email 通知 | 1d |
| 44 | TeamsNotifyDelegate | 流程節點中觸發 Teams 通知 | 1d |
| 45 | ESignDelegate | 觸發電子簽章 + 等待 Callback 喚醒 | 3d |
| 46 | ErpSyncDelegate | 同步資料到 ERP 系統 | 2d |
| 47 | DynamicAssigneeDelegate | 運行時動態計算審核人 | 2d |
| 48 | DataValidationDelegate | 流程中資料驗證邏輯 | 1d |
| 49 | ExternalApiDelegate | 通用外部 API 呼叫（可配置 URL/method/payload） | 2d |

### 1.10 基礎設施 / 運維

| # | 工項 | 說明 | 估時 |
|---|------|------|------|
| 50 | JVM 記憶體配置 | Dockerfile 加入 JAVA_TOOL_OPTIONS、docker-compose resource limits | 0.5d |
| 51 | RabbitMQ DLQ 告警 | Dead Letter Queue 消費者 + 告警通知 | 1d |
| 52 | 多版本流程並行處理 | 確保新案用新版、舊案繼續舊版的邏輯正確 | 1d |
| 53 | BPMN 環境變數替換 | 部署時依環境替換 `${ENV_*}` 變數 | 1d |

---

## 二、Form Service

| # | 工項 | 說明 | 估時 |
|---|------|------|------|
| 54 | 表單版本管理 | version 自增、歷史版本查詢、依版本取 schema | 2d |
| 55 | 表單 Schema 驗證 | 提交時驗證 dataJson 符合 schemaJson 定義 | 2d |
| 56 | 動態選項（API 載入） | 下拉選單 options 支援從外部 API 動態取得 | 2d |
| 57 | 檔案上傳支援 | 檔案上傳元件對應的 storage + API | 3d |
| 58 | 表單資料更新 | 退回修改時 PUT form-data 的版本控制邏輯 | 1d |
| 59 | 封存/刪除保護完善 | archived 狀態完整測試、流程中使用的表單不可封存 | 1d |

---

## 三、跨服務整合 / 端到端

| # | 工項 | 說明 | 估時 |
|---|------|------|------|
| 60 | 流程啟動完整流程 | 前端提交 → form-data 儲存 → variables 設定 → 流程啟動 → formVersion 鎖定 | 2d |
| 61 | BPMN 部署流程 | bpmn-js 設計 → Lint 驗證 → Git commit → 部署 Flowable → 版本管理 | 3d |
| 62 | 認證授權整合 | Sa-Token / JWT 對接、API Gateway 層 JWT 驗證 | 3d |
| 63 | 單元測試 | bpm-core Service/Controller 層單元測試 | 5d |
| 64 | 整合測試 | 流程端到端測試（啟動→審核→完成）、外部系統接入測試 | 5d |
| 65 | API 文件 | Swagger/OpenAPI 文件產生、外部系統對接文件 | 2d |

---

## 工項統計

| 類別 | 工項數 | 估計人天 |
|------|--------|---------|
| 流程引擎核心 | 7 | 18d |
| Org/Perm 正式整合 | 5 | 10d |
| 外部系統接入 | 8 | 10d |
| 非同步/Callback | 4 | 9d |
| Webhook | 4 | 8d |
| 通知服務 | 5 | 11d |
| BPMN Lint | 5 | 4d |
| 稽核 Log | 4 | 9d |
| 通用 Delegate | 7 | 12d |
| 基礎設施 | 4 | 3.5d |
| Form Service | 6 | 11d |
| 跨服務整合 | 6 | 20d |
| **合計** | **65** | **~125.5 人天** |

---

## 優先級建議

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
