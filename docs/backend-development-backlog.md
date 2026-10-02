# Greyhound BPM 平台 — 後端開發工項清單

> 產出日期：2026-06-08
> 最後更新：2026-10-01（#91 完整完成：方向 A 復原 + 方向 B + 漏報①② + 測試缺口）
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
- **測試**：後端 **629** 個（Testcontainers：真實 MSSQL／RabbitMQ／Redis），前端 **93** 個（Vitest）
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
| 67 | Webhook 投遞接線 | 🟡 **2026-09-30 節點層完成、2026-10-01 流程層完成**（`2ed262e`／本輪）。**開工前實測 Flowable 保留未知 extension element**（部署→`getResourceAsStream`→`getBpmnModel()`→`convertToXML()` 四層都驗過），所以讀取走 `RepositoryService.getBpmnModel()`，不用自己解析 `ACT_GE_BYTEARRAY`。<br>**三段斷線全接**：**(A)** 新增 `WebhookConfigResolver`，listener 逐筆設定 `__webhookUrl`，**讀不到設定就完全不發訊息**（改動前是發一則沒有 URL 的訊息交給 consumer 丟掉，會讓 DLQ 混著從來不該投遞的訊息）；**(B)** 兩支出廠 BPMN 的每個 UserTask 加 `event="all"` 的 `webhookTaskListener`、`setBeans()` 加入它（`notifyTaskListener` 未動、`EL_WHITELIST` 未動）；**(C)** 前端改寫 `extensionElements`（spec §11.3 的格式），舊 `documentation` 格式保留相容性。⚠️ **投遞位址改來自業務人員可編輯的 BPMN，`WebhookUrlPolicy` 從「可有可無」變成「安全相依」** —— 設定 URL 的人與決定送什麼資料出去的人可能是不同人。⚠️ **向後相容的關鍵決定**：「有 `<flowable:webhooks>` 元素就是權威（即使內容是空）」而非「非空才是權威」，否則使用者在設計器刪掉最後一筆後回頭讀舊 documentation，**剛刪掉的設定會立刻復活**。⚠️ **連帶修掉一個原描述未提到的既有缺陷**：`flowableModdle.js` 的 `TaskListener` 型別缺 `superClass: ['Element']`，實測**用 bpmn-js 匯出一次出廠 BPMN，兩個 taskListener 全部消失** —— 出廠流程的通知機制本來就會在設計器存檔一次後消失。**PM 線上實測已完成**（主樹 **585** 全綠、前端 **87** 全綠、acceptance-test PASS 7/FAIL 0；且加了 `webhookTaskListener` 之後 `seed-data.sh` 仍能部署 —— 那是本工項最大的迴歸風險）。**端到端**：注入帶 `flowable:webhooks` 的 BPMN → 啟動流程 → listener 排入佇列 → `Webhook delivered`，payload 含 11 個欄位，**HMAC 簽章由接收端重算驗證通過**、重放偵測標頭齊全。**⚠️ 同時線上證實 SSRF 閘門真的生效**：URL 設 `127.0.0.1` 時被拒（`拒絕 loopback 位址`）。✅ **2026-10-01 流程層已接**（斷線 (A) 第四個實例）：`ProcessCompletedListener` 讀 `<process>` 的 `flowable:webhooks`，與節點層**共用同一份解析**；沒有設定就完全不發訊息。線上實測：帶設定的流程結案 → listener 排入佇列、consumer 收到 `__webhookUrl`（SSRF 閘門照樣拒絕 loopback）；沒設定 → 不發。❌ **仍未完成**：#25 的 payload 缺口（`task.timeout` 事件、候選人、`operatorName`、`comment`）未補；**前端 `modeling.updateProperties(element, { extensionElements })` 那一步沒有自動化測試覆蓋**（寫法與 bpmn-js 官方的 `addExtensionElements` 相同，但這格是空白） | 2d→4d | 🟡 |

**#67 實作結果（2026-09-30，`feature-67`）—— A/B/C 三段全部接上，但有兩件事沒做**

| 段 | 狀態 | 做法 |
|---|---|---|
| (A) 沒有程式碼設定 `__webhookUrl` | ✅ | 新增 `WebhookConfigResolver`（從 BPMN 讀設定）＋ `WebhookConfig` record。`WebhookTaskListener` 現在逐筆設定 `__webhookUrl`／`__webhookMethod`。**投遞維持既有的 RabbitMQ 路徑**（PM 決策），`WebhookUrlPolicy` 仍是唯一閘門 |
| (B) listener 未被引用、不在 `setBeans()` | ✅ | 兩支出廠 BPMN 的每個 UserTask 加 `event="all"` 的 `delegateExpression="${webhookTaskListener}"`；`FlowableConfig.setBeans()` 的 map 加入它（`notifyTaskListener` 未動） |
| (C) 前端寫 `documentation`、後端零讀取 | ✅ | 前端改寫進 `extensionElements`（`flowable:webhooks`／`flowable:webhook`）；後端優先讀新格式，**沒有新格式元素時**才回頭讀舊的 `documentation` |

**開工前的必要驗證（結果決定了整個讀取策略）**：實測 Flowable 7.2.0 **會**保留它不認識的 extension element（deploy → `getBpmnModel()` → `convertToXML()` 三段都保留，`<documentation>` 也讀得到），所以讀取走 `RepositoryService.getBpmnModel()` 而不是自己解析 `ACT_GE_BYTEARRAY`。已用 `ExtensionElementPreservationTest` 釘住 —— **Flowable 8 升級（Stage 5）時這條會是第一個紅的**。

**向後相容**：兩種格式並存時 `extensionElements` 為準；**「有 `<flowable:webhooks>` 元素就是權威」**（即使內容是空），所以使用者在設計器刪掉最後一筆時舊設定不會復活。轉換發生在**使用者下次在設計器存檔**時（`webhookStorage.js` 的 `save()` 順手清掉舊的 `__webhooks__:` documentation，但保留其他說明文字）—— 刻意**不在部署或執行期自動改寫**已部署的流程定義，那是稽核軌跡的一環。已部署的舊流程**不會壞**（`legacyDocumentationStillDelivers`）。

**⚠️ 連帶修掉一個原描述未提到的既有缺陷**：`flowableModdle.js` 的 `TaskListener` 沒有 `superClass: ['Element']`，因此 **bpmn-js 開啟再存檔會把 `<flowable:taskListener>` 整個刪掉** —— 也就是說出廠兩支流程的**通知機制**本來就會在設計器存檔一次後消失（實測匯出 `leave-approval.bpmn20.xml` 一次，兩個 taskListener 都不見）。這不是 #67 造成的，但 #67 加上 `webhookTaskListener` 之後會讓它同時弄掉 webhook 接線，所以一併修了。

**⚠️ 範圍超出預期的部分**（PM 請注意）：
1. **SSRF 的風險面變大了**。投遞位址改成來自 BPMN，而 BPMN 是業務人員在設計器裡編輯的內容 —— 「設定 URL 的人」與「決定送什麼資料出去的人」可能是不同人。`WebhookUrlPolicy` 是唯一閘門這件事從此是**安全相依**而非可有可無；已加整合測試證明它對 BPMN 來的 URL 真的生效（`loopbackUrlFromBpmnIsRejected`），單元測試證明不了這件事。
2. **`IntegrationTestBase.SERVLET_PORT` 是 static final 的單一 port → 整個測試套件只容得下一個 Spring context。** 任何測試類別加 `@TestPropertySource`／`@Import`／`@DynamicPropertySource` 都會讓**其他**測試整組紅掉（實測踩到）。`bpm.webhook.allowed-hosts` 因此只能放 `application-test.yml`。這是既有測試基礎設施的限制，不是本工項造成的，但下一個人一定會再踩。
3. **`FlowElement#getDocumentation()` 只保留最後一個 `<documentation>`**（Flowable 的 `DocumentationParser` 是單值欄位）。舊格式的相容性因此有先天限制：若有人在同一節點補了一段一般說明，舊的 webhook 設定就讀不到了。新格式不受影響。

**❌ 沒做（需要裁決）**：
- ✅ **（2026-10-01 已完成）`ProcessCompletedListener` 的 process 級 webhook**：讀 `<process>` 的 `flowable:webhooks`，與節點層共用同一份解析（`WebhookConfigResolver.resolveForProcess`）。沒有設定就完全不發訊息；事件對應 `all`／`process.completed`／`complete`／省略 → 投遞。SSRF 仍只由 consumer 端的 `WebhookUrlPolicy` 判定。線上實測：帶設定的流程結案 → listener 排入佇列且 consumer 收到 `__webhookUrl`（loopback 被拒）；沒設定 → 不發。
- **`#25` 的 payload 缺口未補**（`task.timeout` 事件、候選人、`operatorName`、`comment`）。那是 #25 的範圍。`event="all"` 已讓 `timeout` 在節點掛了邊界計時器時真的能觸發，但 payload 欄位仍由 #25 決定。
- **未做線上實測**（PM 統一做）。
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
| 88 | `firstTaskAssignee` 完全沒有驗證 | ✅ **2026-09-30 完成**。新增 `external/ExternalActorGuard`（`requireKnownPerson(field, userId)`），**三層、由便宜到昂貴**：① 空白 → 400（不打網路）② `ExternalActorIdentity.isSystemActor`（`system:` 前綴，**不區分大小寫**）→ 400（不打網路）③ **其餘一律 `orgService.getDirectManager()` 問組織系統**，任何例外 → 400（**fail-closed**）。**`onBehalfOf` 原本就有的 inline 驗證一併收進同一個類別**（規則只能有一份；行為不變，未知員工仍是 400）。⚠️ **為什麼「問組織系統」是主要規則而不是只擋前綴**：`ExternalActorIdentity` 主張「前綴比對就夠、不要打網路」的三個理由**只在 server 獨佔該命名空間時成立**（`initiator` 由 R-20 保證），而 `firstTaskAssignee` 由呼叫端自由指定、不受那個保證。缺陷的實質是「**任何組織系統不認識的字串**」而不是「`system:` 這個形狀」—— 全形 `ｓｙｓｔｅｍ：x`、拼錯的工號、前後空白、空白字串都一樣沒有人能簽。已回頭在 `ExternalActorIdentity` 加上反向警告，避免下一個人以為可以刪掉組織查詢。⚠️ **前綴比對仍保留為獨立一層**（不是多餘）：本專案自己的 `MockOrgController` 整整兩輪都是 **fail-open**（對不認識的 id 回 `mgr001`），而那正是 #83 缺陷兩輪沒被任何測試抓到的原因 —— **「組織系統說這個人存在」只有在它 fail-closed 時才是可信證據**。⚠️ **存在性判斷看的是「有沒有拋例外」而不是回傳值**：`dir001`／`admin001` 位於組織鏈頂，`getDirectManager` 回 `null` 代表「他存在但沒有主管」，寫成 `== null → 拒絕` 會擋掉總監本人（已用 `chainTopAssigneeStillStarts` 釘住）。⚠️ **政策（2026-09-30 使用者已裁決）**：組織系統不可用時 **fail-closed**（連線逾時／5xx／404 一律 400，並記 `log.warn`）。代價是組織系統掛掉時外部系統**全部發不起流程**，而且回 400（呼叫端通常不會重試）。✅ **裁決：組織系統「故障」回 503**（非 400）—— 守衛在啟動之前，重試是安全的；「查無此人」仍回 400。✅ **2026-09-30 已實作**（見下方「裁決 A/B 的實作結果」段落）。空白 assignee 刻意**拒絕**而不是當成「未指名」：缺陷期間 `firstTaskAssignee: ""` + 候選群組會讓 `setAssignee(taskId, "")` 產生 assignee 為空字串的任務，而 Flowable 候選群組查詢帶 `RES.ASSIGNEE_ is null`（`Task.xml:856`）→ **群組成員看不到 → 靜默卡死，且 `UnreachableTaskListener` 明確不告警**（`UnreachableTaskAlertTest.taskWithCandidateGroupIsNotAlerted`）。前後空白**不靜默 trim**：對組織系統那是另一個人，靜默修掉會讓呼叫端永遠不知道自己送錯了。**狀態碼 400**（不是 403/404：呼叫端該改的是 payload），且**排在兩個 403 之後**（不破壞 #80 的防枚舉順序，有 `authorizationStillWinsOverTheNewCheck` 釘住）。**PM 線上實測已完成**（合併後主樹 **534** 全綠、acceptance-test PASS 7/FAIL 0）：擋下 `system:evil`／`SYSTEM:x`／全形 `ｓｙｓｔｅｍ：x`／`nobody-x`／空字串／前後空白 六種形狀，放行 `mgr001`／`dir001`（鏈頂）／候選群組 `hr:leave:approve` 三種；**被拒的 6 筆在 `ACT_HI_PROCINST` 全部查無流程實例（無副作用）、放行的 3 筆各 1 筆**。另查證 `ApplicantResolver`（#83 的成果）經 `git diff` 確認**只有註解更新、零邏輯變動**，`UnreachableTaskListener` 未被修改。⚠️ **候選群組刻意不驗證（本工項最大的未完成項）**：候選群組名稱在本 repo 有**三個互質來源**（部門代碼／權限碼／JWT authority，見 `CandidateGroupMembership` 類別註解），只有部門代碼有存在性 API（`getDeptMembers`），權限中心只能由人反查、列不出權限碼全集，JWT authority 更不在管轄範圍 → 要驗就必須**假設每個群組都是部門**，那會擋掉 `hr:leave:approve` 這種**本專案自己的 BPMN 就會產生**的形狀（已用 `permissionCodeGroupIsAccepted` 釘住反向）。它的兩種危害（群組不存在 → 靜默卡死；把案件丟進任意特權群組 → **授權範圍**問題）都需 PM 裁決，合理下一步是 `allowedCandidateGroups` 白名單或權限中心補群組存在性 API。新增 `ExternalActorGuardTest` 10 條（純單元，含「每層有沒有打網路」的斷言）＋ `ExternalFirstTaskAssigneeTest` 15 條（真實 HTTP，每條拒絕都驗**流程實例數與稽核筆數都沒變**）。**負向控制組實測**：`ExternalApiController.java` **整份還原**成 HEAD → **15 條中 6 紅 9 綠**，紅的 6 條全部是 `expected: 400 but was: 200`（**流程真的被啟動了**），涵蓋系統身分／大小寫／查無此人／全形前綴／空白／前後空白；綠的 9 條是刻意的非回歸對照（6 條放行組＋1 條授權順序＋2 條 `onBehalfOf` 改動前就正確）。⚠️ **線上實測待 PM 統一進行**（獨立 worktree，不啟容器） | ~~0.5d~~ | ✅ |
| 89 | `UnreachableTaskListener` 漏掉「assignee 是空字串 + 有候選人」 | ✅ **2026-09-30 完成**。判準從 `if (hasCandidate && !assigneeIsSystemActor) return;` 改成 `if (hasCandidate && assignee == null) return;` —— **逐字對應 Flowable 那條 SQL 的條件**（`RES.ASSIGNEE_ IS NULL`），而不是「assignee 是不是某種特別的身分」。缺陷期間只把 `system:` 從候選人的救援範圍挖掉，於是**非 null 但不是系統身分的 assignee 一律讓候選人「救」成功**。⚠️ **實測修正了本工項原描述的一個前提**（用 Testcontainers + 真實 MSSQL 跑出來的，非推論）：原描述說「BPMN 的 `flowable:assignee=""` 字面值仍能產生它」—— **不成立**。`UserTaskActivityBehavior.handleAssignments` 對 assignee 有 `StringUtils.isNotEmpty` 前置判斷，實測 `flowable:assignee=""` 與 `flowable:assignee=" "` 產生的 assignee 都是 **null**，候選群組查得到（count=1）、listener 也不告警，**那條路徑行為是正確的**。已用 `bpmnLiteralIsNotTheDefect` 釘住。**但缺陷是真的**，有兩條真的能產生的路徑（皆實測，候選群組 count=0、缺陷期間 TASK_UNREACHABLE 也是 0 筆）：① `setAssignee(taskId, "")`／`" "` —— `TaskService.setAssignee` 沒有 isNotEmpty 判斷，空字串原樣寫入（#88 已在外部 API 輸入端擋住 `firstTaskAssignee`）；② **`flowable:assignee="${var}"` 而 `var` 為空白字串** —— 運算式求值**繞過**那道 isNotEmpty（判斷的是運算式**字串**非空，不是求值**結果**非空），**這一條管理員部署的流程就能觸發、不經任何 API**，是比原描述更嚴重的形狀。**為什麼不在 listener 裡把空白 assignee 正規化成 null**：那會讓這個 listener **改動別人寫入的資料**，而它的職責是觀察與告警（見類別註解「只告警，不硬擋」）；要擋空白 assignee 該在**寫入端**擋（#88 已做），listener 這層的職責是**寫入端漏掉時仍然看得見** —— 與 `BpmnLintService` 規則 h 對這個 listener 的註解同一個道理。**另外新增 `reason=assignee-blocks-candidates`**（第三種成因，既有兩個字串不得改名／合併）：空字串 assignee 在稽核裡**看起來有人負責**（有候選人），是三種成因裡唯一不會一眼看出該做什麼的；`detail.assignee` 也改成原樣帶 null（不寫成 `""`），否則 null 與空字串在稽核裡長得一樣。⚠️ **自己踩過一次坑**：`reasonOf` 一開始寫成 `!assignee.isBlank()`，但**空字串本身就是 `isBlank()==true`**，會讓本工項要修的形狀掉回 `no-assignee-no-candidate` —— 測試的 reason 斷言（非空斷言）直接抓到。**非回歸對照組是必要的**：`assignee=null + 有候選人` 仍不告警那條必須存在，否則「有候選人全部都報」的實作也能讓三條紅的測試通過。**負向控制組實測**：整份還原 `UnreachableTaskListener.java` 到 HEAD → **8 條中 3 紅 5 綠**，紅的正是三條缺陷測試（`expected: assignee-blocks-candidates but was: []`），綠的五條是刻意的非回歸對照 + 釘住 BPMN 字面值非缺陷路徑那條；`UnreachableTaskAlertTest`(3) 與 `UnreachableSystemAssigneeAlertTest`(6) 在缺陷期間**全綠**，證明未動 #83 的判準。`TaskHolderGuard` 與 `ExternalActorGuard` 經 `git diff` 確認**零改動**（沒有放寬任何授權）。`mvn verify` **551 全綠**（基線 543 + 本項 8）。⚠️ **線上實測待 PM 統一進行**（獨立 worktree，未啟容器） | ~~0.3d~~ | ✅ |
| 90 | 申請人端也沒有代發標示 | ✅ **2026-10-01 完成**。`MyApplications.vue` 的「流程名稱」欄加 `el-tag`，`row.onBehalf` 為真時顯示「外部系統代為提出」。**後端完全不用改** —— `onBehalf` 早就在 `ProcessController:278`／`HistoryController:296` 的回應裡，而查詢本來就比對 `initiator` **或** `onBehalfOf`，代發的單早已在結果中，缺的只是畫面上那一行。**三個 tab 是結構性保證**而非逐一檢查：三個 tab 共用同一張 `<el-table>`（`:data="list"` 在 `el-tabs` 之外），沒有 per-tab 模板可以漏掉；`completed`／`rejected` 只做陣列 filter（保留整個 row），`onBehalf` 原封不動跟著走。⚠️ **欄位名與型別是兩個端點刻意不同**（最容易寫錯處）：申請人端是 `row.onBehalf`（**boolean**），審核人端是 `row.onBehalfOf`（**字串**）。**文案不帶 userId 與審核人端刻意相反**：這兩個端點的條件是「`initiator==我` 或 `onBehalfOf==我`」，所以 `onBehalf=true` 代表**代我本人**送出、沒有第三方名字可寫；照抄 TaskInbox 只會得到「代 user001 發起」而 user001 就是看這頁的人。**測試 fixture 刻意不放 `onBehalfOf`** —— 否則寫成 `row.onBehalfOf` 的 bug 照樣全綠，因為它讀的正是 fixture 那個欄位。負向控制組跑三個版本：無修法 **3 紅 4 綠**、修太寬（`v-if="true"`）**7 紅 0 綠**、正確修法 **7 綠**；⚠️ **agent 主動揭露那 4 條綠的證明不了什麼**（缺陷期間畫面上根本沒任何標籤，「沒標錯欄位」是廢話），它們的價值只在「修太寬時轉紅」，定位是**迴歸防護**而非有效性證明。三條測試**真的點 `.el-tabs__item`** 走使用者路徑（含 `@tab-change` → `loadData`），不是直接改元件 ref。前端 **96 → 103** 全綠（基線是 **96**，不是 handoff 記的 93）。🔧 **連帶修掉一個先於本工項存在的缺陷**：`statusType('running')` 回傳 `''`，而 element-plus 2.7.3 的 `ElTag` 允許值是 `['primary','success','info','warning','danger']`、`default: 'primary'` —— `''` 是**允許值以外**的 prop，dev 模式每列噴一次警告；**更要命的是那個警告在 jsdom 下把 Node 的 `util.inspect` 帶進無窮遞迴（`formatProperty → formatValue → formatRaw → formatValue`）→ `Maximum call stack size exceeded` → 掛住整個 vitest worker**（不是測試失敗，是 run 沒有終止）。改為 `'primary'` 而非「不傳 type」，因為 **2.7.3 沒有「無色」這個合法值**、`default` 本身就是 `primary` —— 所以 `''` 從來沒生效過（產生的是 `el-tag--`，無 CSS 變數匹配），**「維持原外觀」不可達**。✅ PM 獨立重現：拿掉測試的 `warnHandler` 且缺陷仍在 → 120 秒無輸出卡死；修掉 prop 且拿掉 `warnHandler` → 103 綠 832ms（同時證實診斷正確，也證明那個繞路已無必要） | ~~0.2d~~ | ✅ |
| 91 | `flowable:assignee="${var}"` 求值為空白 → 案件沒有人看得到，且不經任何 API | ✅ **2026-10-01 完成**（方向 A 復原 / 方向 B / 漏報① / 漏報② / 測試缺口）。⚠️ **上一輪聲稱方向 A 完成，但它從未被合併** —— 只存在於三個 unreachable commit（`6682051`／`b053b77`／`0bd4dac`，基底 `e8572fd`，`git reflog` 無 feature/91 的 merge），本輪 cherry-pick 復原。方向 B 卡住的「兩難」是**假兩難**：handoff 只列「全域 engine event listener（落地前）」與「per-BPMN task listener（兩套形狀）」，但 Flowable 的 `CreateUserTaskInterceptor`（`setCreateUserTaskInterceptor`）**全域、且在 handleAssignments 之後** —— 兩個問題同時消失（詳見 `docs/handoff/2026-10-01-round5-handoff.md`）。以下保留方向 A 的完整記錄：PM 當時裁決**只做 lint 規則**（方向 B 被認為碰到 flush 順序風險）。**規則 `optional-assignee`（severity `warning`）**：UserTask 的指派欄位是純變數參照 `${var}`，而 `var` 在該流程的變數規格裡已宣告但 **`required=false`** → 警告。⚠️ **PM 的裁決前提原本有破口，是 agent 查出來的**：`ExternalApiController.validateVariables`（`:697`）**只判 `!variables.containsKey(...)`** —— 送 `{"dept":"  "}` 的 `containsKey` 為 true 所以通過 → BPMN `${dept}` 求值成空白 → **與本工項完全相同的下場**。也就是「`required=true` → 不會在執行期為空」**只對缺值成立，對送空白值不成立**。⚠️ **為什麼規則不能用「`${var}` + 無候選人」這種寬條件**：本平台最常見的設計就是「審核人由執行期變數決定」的單人審核流程，那種條件會對幾乎所有合法設計噴警告 —— 等於沒有規則。`required=true` 的變數被擋在 `validateVariables`，`required=false` 才是真的可能沒有值。**severity 是 warning 不是 error**（error 會讓 `POST /api/deployments` 直接擋下，而本規則描述的是「可能」而非「必然」）。**已知漏報（不涵蓋，待裁決）**：① 上述 `required=true` + 空白值；② 混合式 `${a}-${b}`（b 為 optional）會靜默卡死成 `"alice-"`。**位元碼證據（`javap -p -c` 取得，非推導）**：`UserTaskActivityBehavior.handleAssignments` 的 assignee 路徑有**兩個** `StringUtils.isNotEmpty`（offset 845 判運算式**字串**、offset 880 判**求值結果**），**中間沒有 `trim()`**，而 commons-lang3 的 `isEmpty` 是 `cs == null || length() == 0` → **`isNotEmpty("  ") == true`**。第一道判斷對 `${任何東西}` 都無用（判的是非空的運算式字串），第二道是寬鬆版。**順帶修掉既有隱患**：`declaredVariables()` 原本**每個 UserTask 查 3 次**（對 3 個欄位各呼叫一次），重構成整次 `lint()` 只查一次並攜帶 `required`。**平台變數明確略過**，連「規格表裡剛好有同名 required=false 列」也不警告。**沒有實作方向 B**。負向控制組（只在規則第一行加 `if (Boolean.TRUE) return;`，刻意不整份還原 —— 那只會得到編譯失敗）：**10 條中 3 紅 7 綠**；⚠️ **綠的 7 條證明不了新規則有接上**（它們斷言「沒有誤擋」，規則在不在都該綠），且 `severityIsWarningAndDoesNotBlockDeployment` **卡在第一個斷言**，所以「severity 一定是 warning」與「`valid()` 一定是 true」未被獨立驗證（拆開的話 `valid()==true` 會變成沒有規則也成立的廢斷言）。**沒有擋掉出廠 BPMN 的三層證據**：走真的 `POST /api/deployments` 得 200 → **先斷言新規則確實沒觸發**（`doesNotContain("optional-assignee")`）→ **把「沒觸發」釘到結構上**（出廠指派欄位全是方法呼叫，不可能匹配 `^\$\{(\w+)}$`；理由是部署成功證明不了「正確地不觸發」還是「根本沒執行」）。後端 10 條 + 既有 46 條全綠 | ~~0.5d~~ | ✅ |
| 92 | `setAssignee(taskId, "nobody-xyz")`（未知的人）不告警 | ✅ **2026-10-01 完成**。`TaskController` 的 `reassign` 分支原本**只擋空白 assignee**，任何 id 都放行 → `{"action":"reassign","assignee":"nobody-xyz"}` 回 200 `{"status":"ok"}`，而該任務從此沒有人看得到、沒有人能簽、也沒有任何告警（`assignee` 非 null → Flowable 候選人查詢帶 `ASSIGNEE_ IS NULL`，**候選人救不了它**；`nobody-xyz` 非空白非 `system:` → #89 的 listener 也不告警）。修法是重用 #88 的 `ExternalActorGuard.requireKnownPerson`（**只呼叫，未修改該檔**）—— 「指派給誰」這條規則在 #88 已有唯一一份實作，寫第三份只會得到「外部入口擋掉、加派擋掉，改派放行」那種組合差異。**排序**：排在 `requireHolder` 之後（對非持有者必須 **404** 授權而非 400 payload 形狀 —— 狀態碼差異就是枚舉管道）、排在既有空白檢查之後、排在 `setAssignee` 之前（擋在寫入後就留下一個已指派卻沒有人能簽的任務）。空白檢查**刻意留在本地**：actorGuard 對空白的訊息會叫人「改用 `firstTaskCandidateGroups」，而 `reassign` 的 body 裡沒有那個欄位。狀態碼沿用既有語意（查無此人 400／組織故障 503）。⚠️ **PM 的指示被 agent 查原文推翻（PM 錯了）**：PM 說「P1-4 若是要求候選人白名單那是另一個工項」，agent 回去查 `security-audit.md:144` —— P1-4 指定的修法是「改顯式 switch，未知 action 回 400」（#77 已做），「不檢查新 assignee 是否為候選人」只是**描述嚴重程度**的子句，**沒有任何文件要求候選人白名單**。而且驗的是「**是不是人**」而不是「是不是候選人」：候選清單是啟動時的**建議**不是改派白名單，主管把任務交給不在清單裡但確實該處理的人（出差／代班／跨部門支援）是正常業務行為，拿它當白名單唯一合法結果是把改派功能打死。負向控制組**整份還原 `TaskController.java`** → 14 條中 **6 紅 8 綠**，紅的全是 `expected 400|503 but was: 200`；**agent 額外把斷言順序調換（先資料後狀態碼）再跑一次**，得到 `expected "mgr001" but was "nobody-…"` —— **缺陷期間資料真的被改掉**。最關鍵的對照是 `reassignToKnownPersonOutsideCandidateListIsAllowed`：**先斷言候選清單真的含 `dir001`/`mgr001` 且不含 `user001`** 才做斷言（非空斷言，否則那條證明不了任何事）。排序正確性靠 `nonHolderCannotReassignToUnknownPerson`（**刻意送未知的 id**，才能分辨 404=持有者守衛先擋 vs 400=身分檢查插錯位置）—— 送合法 id 的話缺陷期間與修好之後都是 404，完全釘不住。`blankAssigneeIsRejected` 加了**訊息斷言**（`doesNotContain("firstTaskCandidateGroups")`），讓「刪掉本地空白檢查只留 actorGuard」那個錯誤實作變紅。7 種拒絕形狀全部**先斷 assignee 未變、後斷狀態碼**。⚠️ **兩個同型缺陷已回報、刻意未修**：(1) `CountersignController.requireKnownEmployee` 是**同一條規則的第二份實作**，缺空白與 `system:` 前綴兩層，`countersignUserId: "system:evil"` 目前只靠「組織系統查不到」擋下 —— **前提是組織系統 fail-closed**，而本專案自己的 `MockOrgController` 整整兩輪都是 fail-open；(2) `delegate` 分支的 `delegateUser` 完全同型（後果較輕，owner 仍可 `resolve` 收回） | ~~0.5d~~ | ✅ |
| 93 | `firstTaskCandidateGroups` 送 JSON array 會 500 | ✅ **2026-10-01 完成**（`2511e0d`／`ac9bdee`／`e36544a`）。`ExternalApiController:98` 的 `(String) body.get(...)` 對 `docs/bpm-platform-spec.md` 示範的**陣列**形狀拋 `ClassCastException` → 500。**危害不是「壞掉」而是「永遠不會成功還一直重試」**（500 的語意是「稍後重試」，payload 不變就永遠不會成功 → 批次無限重試），而且 #73 刻意只回傳「刻意丟出的」訊息，所以呼叫端連「你送錯形狀了」都拿不到。⚠️ **那個 cast 還排在所有檢查之前**（連 `allowedProcessKeys` 的 403 都沒查），未授權的系統送陣列會拿到 **500 而不是 403** —— 授權分支根本沒機會執行。✅ **使用者已裁決：陣列為 canonical，逗號分隔字串保留為相容形狀**（spec 示範的一直是陣列，而**已上線的整合方送的是字串**；只留陣列會讓那些整合在部署新版本那天全部壞掉，只留字串則讓 spec 從第一天起就是錯的）。**切法按責任而非欄位**：形狀（陣列／字串／元素型別）→ controller 的新 `parseCandidateGroups`；內容（trim／丟棄空白／去重／白名單）→ `ExternalActorGuard`（**簽章由 `String raw` 改為 `List<String>`**，已切開、元素未 trim）。守衛收到的是「已切開的清單」，**不再知道請求長什麼樣子**，兩段規則各只有一份。⚠️ **不選 Jackson `convertValue`**：它預設把數字強制轉字串，`["dept001",123]` → `["dept001","123"]` → 200，**建立一個沒有人是成員的群組 → 靜默卡死**，正是本工項要修的那類缺陷換個形狀。⚠️ **不把空清單（`[]`／`["  "]`）在解析處拒絕**：那會是第二條「至少要有一個」的規則 —— 交給 `startProcess` 既有的規則（它比對的是**解析後**的清單，#88 刻意如此）。邊界：`["dept001",123]`／`123`／`true`／`{}`／`null` 元素 → **400**（訊息指名第幾個元素、什麼型別）；`null` 欄位 → 未指定；`[]`／`["  "]` → 既有規則的 400；`["a","a"]` → 去重。✅ **狀態碼 400 經使用者追認**（沿用同一方法既有規則：`initiator` 冒用 → 400、查無此人 → 400、空白 → 400；**形狀錯回 400 而不是 500 是讓呼叫端在「改 payload」與「稍後重試」之間別選錯**）。**形狀解析排在兩個 403 之後**（與守衛同一個呼叫點），理由是 #80 的防枚舉順序 —— 否則未授權的流程 key 會拿到 400 而非 403。⚠️ **未授權群組 + 非字串元素會拿到 400**（形狀先判，沒有形狀就沒有名稱可比對）；**不構成枚舉風險**因為形狀錯誤的回應**不隨白名單內容改變**。變數 `firstTaskCandidateGroups` 仍寫**逗號分隔字串**且由**解析後的清單** join（`InitialAssigneeResolver` 的既定契約；用解析後的清單才讓「變數裡的」與「identity link 裡的」一致）。負向控制組 → 18 條中 **13 紅 5 綠**，紅的全是 `expected 200|403|400 but was: 500`；其中 `processKeyAuthorizationStillWins` 是 PM 分析的直接證實（**未授權的流程 key 拿到 500 而非 403**）。⚠️ **三種錯誤修法都會讓整個測試檔全綠**（`catch CCE` 一律 400／`String.valueOf()` 再 split／Jackson `convertValue`），抓它們的是正向測試與每一條 identity link 斷言 —— **13 條紅的只證明「500 不見了」**。⚠️ **負向控制組抓到的是 agent 自己測試寫錯**：`Ordering` 原本斷言「群組授權先於 initiator 冒用」，實測 `expected 403 but was 400`，讀碼才發現 `initiator` 檢查（`:89`）是全方法最早一格 —— 記錯順序，已改成斷言真實順序。⚠️ **實測發現 identity link 讀回順序不穩定**（`getIdentityLinksForTask` 底層無 `ORDER BY`，同一組輸入在不同 run 回兩種順序），agent 原本寫 `isEqualTo(List)` 的測試**時好時壞**（前兩次綠、後連三次紅）→ 改用不論順序比對並連三次重跑確認穩定。**副作用**：候選群組「保留書寫順序」從 identity link 讀不回來，只能在守衛層驗。**另外 agent 修正了 PM 兩處錯誤**：章節號是 **§9.2** 不是 §9.1.3（hypothesis 抄錯）；並在改 spec 時**發現 spec 與程式碼兩處矛盾**（見下）。✅ **PM 依「全面盤點」要求機械式比對後修正 spec 兩處**：① §9.2 請求範例含 `"initiator": "system:registration"`，而 `:89` **拒絕任何帶 `initiator` 的 body** → **照 spec 抄整個範例會拿到 400**（R-20 落地後文件沒跟上，先於本工項存在）；② §9.2 規則第 3 點寫「後續節點的 `initiator` 可替換為實際經辦人」，**程式碼沒有替換 `initiator`**（全程維持 `system:<id>`，另外寫入**獨立的** `effectiveInitiator`）—— **這一點寫錯過，而且它正是 #83 的成因**（設計師照著在補件關卡寫 `${initiator}` → `system:erp` → 永久卡死無告警）。保留：第 4 點的 lint 規則與 Java 草圖**準確**，未動。後端 **647** 綠（基線 629 + 18） | ~~0.3d~~ | ✅ |
| 82 | 前端沒有權限碼的概念 | ✅ **2026-10-01 完成**。**新增 `GET /api/me/permissions`**（`MeController`），回 `{userId, permissions[], admin}`；前端新增 `services/permissionsApi.js`、`stores/auth.js` 加權限碼狀態與 getter、`router/index.js` 兩條路由改用 `requiresPermission`。⚠️ **PM 原本以為這是「權限碼的資料來源」問題，實際上資料來源早就有**（`BpmPermissionService.getUserPermissions` + 已定案的 `GET /api/users/{userId}/permissions`），真正要決的是「怎麼送到前端」。修掉三個現成破口：`mgr001`（有 `bpm:form:design`）後端放行但選單看不到、`dir001`（有 `audit:log:read`）後端刻意放行但頁面進不去、`admin001` UI 看得見管理頁但 `/api/audit-logs` 點下去全 403（**後端只認 `audit:log:read`、刻意不接受 `ROLE_ADMIN`**）。🔴 **端點「沒有 `?userId=` 參數」是本工項最重要的約束，已釘死**：`@RequestParam` 一個都沒有，負向控制組把實作換成「接受 `?userId=`」→ **10 條中 7 紅 3 綠**（含 **14 個參數名 × 5 個他人 = 70 種組合**，每一種都斷言回應**與乾淨請求完全相同** —— 斷言「沒有某某權限碼」可能因剛好不重疊而假綠）。加了參數就是**組織結構的枚舉通道**（「誰有哪些權限碼」能畫出職能配置，枚舉不需付出被拒絕的代價）。⚠️ **回的是 SecurityContext 的 authorities，不是另查權限中心** —— 兩者只在「JWT 帶了 roles claim」時不同，而那正是 `authoritiesFromJwt` 採用 claim **並不去查權限中心**的情況，此時回權限中心等於回一個「權限中心自己沒被問到」的清單。**`ROLE_` 前綴剝離**成獨立欄位，所以閘道認證的 `ROLE_GATEWAY` 不外洩；`admin` 刻意是**獨立欄位且不等於萬能**。**前端降級路徑**：永遠查，失敗才降級（不因 claim 有碼就跳過 —— token 過期而前端未察覺時會繼續用一份不對應任何後端判斷的清單）；降級時只採信**形狀像權限碼**的 claim 項目並標記 `permissionsUnverified`，**不展開 `*`**。**不會閃**：guard 改 async 並 await 權限載入（否則「先放行、畫面閃一下、再被踢回首頁」，使用者會讀成功能壞了）；等待一定會結束。**沒有動 `SecurityConfig`** —— 新端點落在既有 `/api/** → authenticated()` 上，授權矩陣完全未修改。⚠️ **產品可見的選單位置變動（PM 未事前核准，但為必要）**：`App.vue` 把「表單編輯器」**移出** `v-if="isAdmin"` 的管理子選單。理由是留著會讓整個工項白做 —— mgr001 會「路由放行但選單看不到」，等於功能仍然不存在。**`/admin/forms` 寬嚴不一致刻意不動**：後端 GET 只要登入、前端要求 admin，維持 `requiresRole: 'admin'`（前端比後端嚴只是少顯示一項，放寬是產品決定）。前端負向控制組做了**四次獨立驗證**，其中最有價值的是「路由改回 `requiresRole`（原始缺陷）→ 紅 6 條」。⚠️ **agent 回報的事實**：改動前 `dir001` 在 dev 根本進不了 `/audit-log`，因為 `mintDevToken` 刻意不簽 `roles` claim —— 而 `dev-token.sh` 的註解正是教人用 `dir001` 讀稽核的，**註冊流程與實際可操作權限不一致**。前端 96 → 144 全綠 | ~~1.5d~~ | ✅ |
| 94 | `CountersignController.requireKnownEmployee` 是同一條規則的第二份實作；`delegate` 分支完全同型 | ✅ **2026-10-01 完成**。**收斂成 `ExternalActorGuard.requireKnownPerson` 唯一一份實作**（`CountersignController` 的私有實作刪掉，含 `HttpClientErrorException`／`OrgService` import；`TaskController` 的 `delegate` 分支接上同一個守衛）。⚠️ **PM 下令時的理由是「規則只能有一份」這條硬規則，不是嚴重度** —— 實測後必須修正描述：`/api/countersign/{taskId}` 有 `TaskHolderGuard.requireHolder`，所以**只有當前任務持有者**能加簽，後果是「多一個沒有人能簽的子任務」，**父任務仍可正常完成**；`delegate` 的 owner 仍可 `resolve` 收回。**兩者都比 #92 的 reassign 輕**（reassign 是讓整張單卡死）。**agent 推翻 PM 上一輪的辯護**：「可以 resolve 收回來不是緩解理由 —— owner 必須自己察覺到不對，而沒有任何人收到告警」，且「委派會增加交易內 HTTP 呼叫量」是**當時的範圍界定、不是技術判斷**。⚠️ **agent 最重要的誠實發現：加簽那 3 條紅的測試不是新缺陷** —— 舊實作在 fail-closed 的 `MockOrgController` 下**本來就回 400/503**。收斂帶來的實際改變只有兩件：`system:` 前綴改由**不打網路的字串比對**擋下（**不再依賴 fail-closed 這個前提** —— 而本專案自己的 mock 整整兩輪都是 fail-open）、以及訊息統一。它並指出加簽那 3 條只看訊息不看分層，所以**「把舊實作原封不動搬到別處」這種錯誤修法也會讓它們全綠**。負向控制組（兩個 controller 整份還原）：**22 條中 9 紅 13 綠**。**每個被拒形狀都驗資料未變**，且 delegate 連 **owner 都斷**（`delegateTask` 在 owner 為 null 時會寫入 owner，只斷 assignee 會漏掉一半）。🔧 **PM 收尾時另修兩件收斂後才浮現的後果（都是 PM 造成的）**：① **訊息把外部 API 語境寫死** —— 400 說「請改用 `onBehalfOf`」、503 說「未發起流程…未建立任何流程實例」，而五個呼叫點裡**只有外部 API 那兩個真的發起流程**，reassign／delegate／countersign 的 body 裡**根本沒有 `onBehalfOf` 這個欄位**（診斷訊息指向錯誤欄位比沒有訊息更糟）→ 新增 `action` 參數，後果句改為動作中立的「本次請求未做任何變更」，**刻意不做 per-call-site 客製化**（那會讓「規則只有一份」連訊息層都失守）；兩處測試斷言同步更新且**意圖一字未減**。② **加簽的 `assignee.trim()` 讓同一條規則有兩種答案** —— `" mgr002 "` 在加簽回 200、在改派／委派回 400；repo 早已有答案（**不靜默 trim**，對組織系統而言那是另一個人），拿掉後**行為變更**（200 → 400），經 PM 裁決 | ~~0.3d~~ | ✅ |
| 83 | assignee 為 `system:<id>` 的任務沒有人能簽 | ✅ **2026-09-30 完成**。新增 `ApplicantResolver` bean，3 個補件 UserTask（`applicantRevision`／`revisionFromManager`／`revisionFromFinance`）由 `${initiator}` 改為 `${applicantResolver.resolve(execution)}`；三段規則 `onBehalfOf` → `initiator`（是人的話）→ **權限碼 `bpm:external:revision` 指定的受理人**。**政策（2026-09-30 使用者已裁決：維持權限碼方案，列為上線檢查清單）**：第三段選「沿用既有權限中心」而非外部系統設定檔新欄位 `defaultHandler` —— 後者要 DB schema 變更＋migration＋entity＋admin API＋管理頁＋前端，且一個欄位只能指定一個 userId，而「這張單沒有申請人」是所有外部系統共用的事實；權限中心這條路**零 DB 變更、零前端改動**，且 `getFirstAvailableUser` 已處理代理人／休假（用靜態 userId 會把關卡派給不在的人＝同一缺陷換皮）。**找不到受理人時拋例外而非回 null**（回 null 是換一種方式製造靜默卡死），拋錯會讓「退回」整個回滾——主管當場看到錯誤、案件仍停在他手上。⚠️ **連帶修掉 `UnreachableTaskListener` 的告警繞過**：判準從「assignee 是否 null／空白」改成「有沒有人能動它」，`system:` 前綴（不區分大小寫，因 `firstTaskAssignee` 是 body 自由字串且無驗證）也告警，並加 `reason` 欄位分流兩種成因。**候選人救不了這種任務** —— Flowable 的 `taskCandidateUser` 帶 `ASSIGNEE_ IS NULL`，兩個條件互斥而非互補。負向控制組實測：整份還原後 8 紅 5 綠，紅的全是系統發起／代發形狀，綠的兩條是「不得放寬守衛」「不得改派人工單」的非回歸對照。⚠️ **必須在真實權限中心指派 `bpm:external:revision`**，否則外部系統發起的案件連「退回」都會 500。**PM 線上實測已完成**（合併後主樹 **509** 全綠、acceptance-test PASS 7/FAIL 0）：外部系統發起 → `mgr001` 退回 → 補件關卡 `assignee = dir001`（**不再是 `system:<id>`**）→ 無關的 `user002` 簽得到 **404**（守衛未鬆）→ `dir001` 簽得到 **200** 且流程回到「主管審核」給 `mgr001`。**整條回路打通 = 案件不再靜默卡死的實證**。另 `TaskHolderGuard` 經 `git diff` 確認**只有註解變更、零邏輯改動** | ~~1d~~ | ✅ |
| 84 | `NotifyAdminController.updateConfig` 未驗 templateId | ✅ **2026-09-29 完成**（`d4ddfae`）。抽出 `requireExistingTemplate` 給 create/update 共用（不是 DRY，是**規則只能有一份**：缺陷本身就是分散造成的 —— create 有擋、update 沒有，而 `deleteTemplate` 的註解宣稱兩者都擋了，描述的是不存在的行為）。⚠️ **實測到的後果與原描述不同**：P1-13 已替 `EmailConsumer` 加上消費端防護，所以線上實測是**設定被靜默忽略**（記 WARN、改用預設模板）而非通知永久遺失。危害較小但**更難察覺** —— 沒有例外、沒有錯誤訊息、通知照常寄出，後台還顯示「設定成功」。線上實測：不存在的 templateId／null／`""`／全空白 → 400 且五個欄位全未變、稽核無紀錄；有效 templateId → 200 且 `enabled` 真的翻轉；不存在的 id → 404；非管理員 → 403 且資料未變。**前端無相容性風險**（`grep` 確認 `bpm-frontend/src` 零 `notify-config` 呼叫） | ~~0.3d~~ | ✅ |
| 85 | `ProcessVariableSpecController.update` 稽核說謊 | ✅ **2026-09-29 完成**（`97eafd7`）。`findById` 後比對 `existing.getProcessDefinitionKey()`，不符回 **404**（非 403，沿用 `ProcessAccessGuard` 政策：403 會確認物件存在，對可枚舉的 id 等於留枚舉管道）。稽核改寫 `existing` 的 key；被擋時不寫稽核。連帶修掉 `orElseThrow()` 讓不存在的 id 從 **500 → 404**（修前 500 會讓呼叫端一直重試）。**端到端已驗**：`required` 決定 `ExternalApiController.validateVariables` 擋不擋，所以 required=true 時外部請求缺該變數回 400 → 被擋的冒用後**仍然**400（輸入驗證沒被放寬）→ 用正確 key 改成 false 後同一請求變 **200**，證明 400 確實來自那一筆規格。⚠️ 不選「靜默忽略 key 不一致、以 id 為準照樣更新」：那樣稽核仍會寫錯的 key，且回 200 讓呼叫端以為改對了 | ~~0.3d~~ | ✅ |
| 86 | `ProcessVariableSpecController.batchSave` 重複儲存必定 500 | ✅ **2026-09-30 完成**。`POST /api/admin/process-definitions/{key}/variable-spec` 是「整批取代」，原本先 `deleteByProcessDefinitionKey(key)`（衍生刪除 → `em.remove()`）再 `saveAll`（→ `em.persist()`）。**Hibernate 在同一次 flush 中把 INSERT 排在 DELETE 之前**，撞上 `@UniqueConstraint(processDefinitionKey, variableName)` → **500**。⚠️ **原描述「只要該 key 已有任何一筆規格就必定失敗」不精確，實測後修正**：觸發條件是**新批次與既有規格有同名變數**。新舊完全不重疊（整批換新名字）或送空陣列，缺陷期間都是 200 —— 沒有任何 INSERT 會撞到同名舊列。這讓它更難被手動試出來（剛建好規格時第一次存是好的），而管理頁的正常使用流程必然重疊。⚠️ 前端 `ProcessVariableSpecAdmin.vue` 的「儲存」按鈕正是走這條路徑 → **管理頁第二次按儲存必定壞**。**414 個測試沒有任何一個覆蓋重複寫入**。修法：刪除改成 `@Modifying @Query` 原生 JPQL（不選「衍生刪除 + `flush()`」——那樣也能修好，但規則會散在「刪除」與「記得 flush」兩處，而這正是本缺陷的成因）。**不**做「比對後只刪真正消失的列」：那會保留舊 id，而 `setId(null)` 是 P0-4 的防護。線上實測八種呼叫全 200 且稽核逐筆相符，見 commit `f6df3fb` | ~~0.5d~~ | ✅ |
| 87 | `batchSave` 同一批內重複變數名 → 500 | ✅ **2026-09-30 完成**。送 `[{"variableName":"x",…},{"variableName":"x",…}]` 原本仍 500，**根因與 #86 不同**：呼叫端送了互相衝突的資料，與刪除／寫入的順序無關，#86 的修法碰不到它。修法是**輸入驗證**：`requireDistinctVariableNames` 擋在 `deleteAllByProcessDefinitionKey` **之前**（順序決定「被拒的請求有沒有副作用」），回 **400 並指名重複的名字**（500 的語意是「稍後重試」，但重試永遠不會成功 —— payload 沒變結果就不會變）。⚠️ **「重複」不能用 `Set<String>` 判斷**：欄位定序是 `SQL_Latin1_General_CP1_CI_AS`，對 MSSQL 實查（`sqlcmd` + `sys.columns`）確認 **`Amount`／`amount`、`amount`／`amount `（ANSI padding）、`Ａ`／`A`（全形半形）在資料庫層面就是衝突** —— 使用者在表格裡打兩個大小寫不同的名字是最常見的失敗形狀，而 `Set<String>` 恰恰擋不住，修完等於沒修。`dbComparisonKey` 逐條對齊此定序（NFC、全形半形折疊、只 strip 尾端 U+0020、大小寫），**刻意不做**整串 NFKC（會把 `①` 折成 `1`，實測兩者在 DB **不**衝突）與重音折疊（定序是 AS）。26 組對照實測：24 組一致，2 組分歧（`ı`、`ǅ`）**方向都是「誤擋合法資料」而非漏擋**。**連帶修掉 `update` 改名撞到同一流程其他變數 → 500**（同一個唯一約束、共用同一個比較鍵；留下來的話「這個端點的 500 修掉了嗎」仍是無法回答的問題）。前端 `ProcessVariableSpecAdmin.vue` 加**儲存前檢查**（刻意是後端規則的**子集**，方向固定為「前端不得擋掉後端會接受的資料」）；`addRow()` 產生的空名稱是既有行為，**刻意不改**（自動命名等於替使用者決定外部系統要塞進流程的 key，猜錯了不會有人發現）。✅ **使用者已裁決：擋空白名稱**（null 亦改 400）。✅ **2026-09-30 第二輪已實作（#87-2／#87-3）**：裁決前的兩個狀態碼（`""`／`"  "` → **200**、`null` → **500**）都改成 **400**。`requireDistinctVariableNames` 合併成 `requireUsableVariableNames`（一次掃描、一次回報空白＋重複，呼叫端仍只處理一個狀態碼），判定規則是 **`dbComparisonKey(name).isEmpty()`** —— 沿用 #87 那份唯一的比較鍵，**刻意不寫新的 `isBlank()`**：`isBlank()` 走 `Character.isWhitespace`，會把 TAB／換行算成空白，但實測那些在資料庫裡**不是**空白（ANSI padding 只忽略尾端 U+0020），擋下等於禁止使用合法名稱、且違反「前端不得擋掉後端會接受的資料」；而 `"  "` 與全形空白在資料庫裡就是空字串，本來就是要擋的形狀。**位置**：`batchSave` 擋在 `deleteAllByProcessDefinitionKey` 之前（與 #87 同一個位置），`update` 擋在 **#85 的 404 守衛之後**、撞名檢查之前（**不選 Bean Validation `@NotBlank`**：它發生在 controller 方法之前，必定排在 404 守衛前面 → 狀態碼錯位，且它用的就是 `isBlank()` 那套定義、還需要新的例外處理與全域 advice）。前端 `addRow()` **維持產生空名稱**（選 (b)，理由見該處註解與 commit 訊息），改為把「空白名不被接受」**顯示在畫面上**（欄位標題＋placeholder）並在送出前指名第幾列。前端 **90** 全綠、後端 **593** 全綠（`VariableSpecDuplicateNameTest` 11 → 19 條；前端該檔 7 → 10 條）；負向控制組（整份還原 controller）紅 7／綠 12，詳見測試 javadoc。線上實測 42 項全過（每個狀態碼都同時驗資料未變）、後端 450 測試全綠（新增 11 條，`VariableSpecDuplicateNameTest`）、前端 66 全綠（新增 7 條）、`acceptance-test.sh` PASS 7/FAIL 0 | ~~0.3d~~ | ✅ |
---

## #88 裁決 A／B 的實作結果（2026-09-30）

兩個裁決在同一個 commit 實作，因為它們共用「組織查詢失敗時該回什麼」與
「外部系統能指名什麼」這條主線。

### 裁決 A：組織系統「查無此人」與「故障」怎麼分辨

**判準是 HTTP 404**，不是「有沒有例外」。`ExternalActorGuard.isDefinitiveRejection`
只認 `HttpClientErrorException.NotFound` → **400**；其餘一切（連線逾時、無法連線、
5xx、401/403、回應無法解析）→ **503**。

- **404 為什麼剛好等於「查無此人」**：`MockOrgController` 刻意 fail-closed（P2-7），
  對 fixture 外的 id 丟 404；而鏈頂人員（dir001／admin001）回 `{}`（存在但沒有主管），
  **不是 404** —— 那個區分必須保留，否則「查得到但沒主管」與「查不到」無法分辨。
- **為什麼 401／403 不是 400**：那描述的是**我們**沒被允許查（憑證／權限設定錯了），
  不是「這個人不存在」。回 400 會讓呼叫端去改一個根本不是問題來源的欄位。
- **為什麼方向仍然是 fail-closed**：裁決改的是「要不要重試」，不是「要不要拒絕」。
  放行等於回到 #88 的缺陷本身（指派給一個沒有人能持有的身分）。
- **刻意不處理**：組織系統對未知 id 回 **200 + 空內容** 時會被當成「他存在但沒有主管」
  而放行。這是既有的前提（fail-closed 才可信），要多擋需要一個「這個人存不存在」的 API。

**測試要分兩組寫**，因為兩者都是「守衛拒絕發起流程」，只斷言「不是 200」會讓兩種實作都通過：
`ExternalOrgSystemFailureTest` 的 `Failure`（4 條，503）與 `Rejection`（2 條，400）
是獨立的 `@Nested`；單元層則是 `ExternalActorGuardTest.OrgSystemFailure`（4 條）
與 `OrgLookup`（2 條）。

### 裁決 B：`allowedCandidateGroups` 白名單

**白名單留空 = 不限制**，沿用 `ExternalSystemPolicy.Kind.UNRESTRICTED`，
與 `allowedProcessKeys` 完全同一條規則（**規則只能有一份**）；明確寫 `[]` 才是拒絕全部。

**既有資料不回填**，因為 null 的語意就是「照舊可以指定任何群組」。
⚠️ 反過來要明講：**對既有系統而言這個檢查完全沒有效果**，直到管理員逐一設定 ——
與 `allowedProcessKeys` 是同一個已知狀況（R-21）。`configuredWhitelistTakesEffectImmediately`
是這個決定的直接證據。

**驗的是「授權」不是「存在」**：候選群組名稱有三個互質來源（部門代碼／權限碼／JWT authority），
只有部門代碼有存在性 API，要驗存在性就必須假設每個群組都是部門，那會擋掉
`hr:leave:approve` 這種本專案自己的 BPMN 會產生的合法形狀。
**「卡死」那一半仍未根治**，可行替代是權限中心提供群組存在性 API（跨系統工程）。

**前端一定要加，而且理由不是「功能完整」**：不加以後，
`ExternalSystemAdmin.vue` 的 `applyForm()` 不會把這個欄位從列資料帶進表單
（它只挑 `blankForm()` 認得的鍵），而 `submitForm()` 送的是 `{...form}` →
**編輯任一系統都會讓 payload 沒有這個鍵 → 後端 PUT 整欄覆寫 → 儲存一次就把白名單
靜默清成「不限制」**。形狀與 #68a 的授權繼承相同，但方向更糟（移除的是保護）。

### 新發現（未修，已回報 PM）

1. ~~**`firstTaskCandidateGroups` 送成 JSON array 會 500**~~ —— ✅ **2026-10-01 已修，見 #93**。
   `ExternalApiController` 讀的是 `(String) body.get(...)`，而 spec 示範的是
   `"firstTaskCandidateGroups": ["hr_dept"]` → `ClassCastException` → 500 → 呼叫端無限重試。
   當時刻意不修（請求契約未定）；✅ 使用者已裁決**陣列為 canonical**、字串保留相容。
2. **`firstTaskCandidateGroups: " , "` 原本會靜默卡死**（本次連帶修掉）：raw 非 null 而通過
   「至少有一個」檢查，但實際上沒有任何群組被掛上去，而且空字串的 identity link 會讓
   #89 的 `UnreachableTaskListener` 誤判為「有候選人」而**不告警**。
   現在空白項目被丟棄，「至少有一個」規則因此看到事實而回 400。
3. **`allowedCandidateGroups` 沿用 R-21 的既有風險**：PUT 是整欄覆寫，
   「少帶欄位 = 放寬授權」。刻意沒有改成「方向固定為失去能力」——
   那是「不限制」的預設語意，誤判成「拒絕全部」會讓照 UI 流程建立的系統一個群組都不能用。

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
| 2026-09-29 新增 | 30 | 29 | 1 | 0 | 18.5d |
| **合計** | **94** | **51** | **21** | **22** | **~104.8 人天** |

原始 65 項的估計總量為 ~125.5 人天（2026-06-08）。

> 2026-09-29 晚間更新：#66、#69 完成（+3 項新發現 #71～#73）。
> #67 的估時由 2d 上修為 4d（三段斷線、8～12 檔案、三種格式互不相通）。
> #71 讀端授權的實際範圍遠大於原先預期的 3 個端點，估時 2d 仍可能偏低。
> 2026-09-30 更新：#84、#85、#86 完成（+1 項新發現 #87）。統計表已重算
> （「2026-09-29 新增」實際是 21 項，不是先前誤植的 8 項）。
> 2026-09-30：#79、#87 完成（後端測試 450、前端 66）。
> 2026-09-30：#80 完成（後端測試 **473**，基線 450 + 新增 23）。**未做線上實測**
> （在獨立 worktree 中進行，同機器另有兩個 agent，資源會衝突）—— 待 PM 統一進行。
> 2026-09-30：#79-2 完成（後端測試 **587**，基線 585 − 1 條被取代 + 新增 3）。
> 同樣**未做線上實測**（`/tmp/gh-79x` worktree，資源限制）。
> ⚠️ **測試數只有 `mvn verify` 輸出的 `Tests run: N` 是真的** ——
> `target/surefire-reports` 不涵蓋 `@Nested` 內類別，逐類加總會少 34 條；
> 併行跑測試時該目錄還會被互相覆寫。
>
> **2026-10-01：#90、#92、#93 完成（+1 項新發現 #93 已補編號列）。**
> 後端 **661** 全綠（基線 629 ＋ #93 的 18 ＋ #92 的 14）、前端 **103** 全綠
> （基線 **96**，不是先前記的 93）。⚠️ **前端基線數字更正：96**。
>
> **2026-10-01：#93a、#91（方向 A）、#82 完成。**
> 後端 **693** 全綠（661 ＋ #91 的 10 ＋ #94 的 22）、前端 **144** 全綠
> （96 ＋ #82 的 48）。統計：93 → 94 項，✅ 48 → 50，🟡 21 → 22，⬜ 24 → 22。
> ⚠️ **「2026-09-29 新增」分類的 ⬜ 歸零**（#82、#91 都動過），
> 但 #91 是 **🟡 不是 ✅** —— 方向 B（指派層把求值為空白的 assignee 視為
> 未指定）**未實作**，因為它碰到 `TASK_CREATED` 裡改 assignee 的 flush 順序
> 風險（#86 那一類）。它的兩個已知漏報記在該列。
>
> 🔴 **2026-10-01（晚間）—— #91 方向 A 以「復原」形式才真正進入 main。**
> 上一輪把 #91 方向 A 寫成已完成，但它**從未被合併**：三個 commit
> （`6682051` 重構、`b053b77` 規則 k、`0bd4dac` 測試）只存在於
> **unreachable commit**（基底 `e8572fd`），`git reflog` 裡沒有任何
> feature/91 的 merge 記錄。本次從 main 切 `feature/91a-recover`
> cherry-pick 復原、零衝突。
> ⚠️ **測試數更正**：`main` 的基線是 **693**，而上一輪的
> 「693（＝661＋#91 的 10＋#94 的 22）」把 #91 的 10 條算進去了 ——
> 693 其實是在**不含** #91 的主樹上量的。復原後完整套件
> **703** 全綠（693 ＋ 10）。
> **教訓**：`git show --stat`／`mvn verify` 只能證明「這一刻的樹」，
> 證明不了「那份成果在 main 上」。收工前應以 `git log --oneline <branch>..main`
> 或 worktree 的 merge 記錄逐一核對每個工項**真的有 merge commit**。
>
> **2026-10-01（晚間第二輪）—— #91 完整完成，後端 727 全綠。**
> 五件：方向 A 復原（10 條）、方向 B 空白 assignee 正規化（`BlankAssigneeNormalizingInterceptor`，5 條）、
> 漏報① required=true 的空白值（`ExternalRequiredVariableTest`，10 條）、
> 漏報② 規則 k 對 `assignee` 放寬到混合式（9 條）、測試缺口（`flowable:assignee=" "` 已由方向 B 的測試釘住）。
> 另修一條 #89 的舊斷言（`UnreachableBlankAssigneeAlertTest`）—— 它釘的是方向 B 改掉的缺陷行為。
> 完整套件 **727**：703（#91 方向 A）＋10（漏報①）＋9（漏報②）＋5（方向 B）＝727。
> 線上實測（真實 JWT／容器）：probe-91b `flowable:assignee="${dept}"` 以 `dept="  "` 啟動 →
> **DB 的 `ACT_RU_TASK.ASSIGNEE_` 是 NULL**、identity link 只有 `candidate/dept001`、
> mgr001（dept001）查得到它；外部 API required=true 送空白／null／缺值 → 400 且
> `ACT_HI_PROCINST` 零筆、合法值 → 200 且 +1。`acceptance-test.sh` PASS 7 / FAIL 0。
>
> ⚠️ **dev 庫新增探測殘留**：`probe-91b`、`probe-91c`（流程定義與規格），
> 以及一個**已停用**的外部系統 `t91req`（`DELETE /api/admin/external-systems/{id}`
> 是**軟停用**不是刪除，`ENABLED_=0`）。與既有的 `probe-blank-literal` 同類，無害。
> ⚠️ 另記一個觀察（非本輪工項）：`POST /api/deployments` 的 `name` 參數若**不含
> `.bpmn20.xml` 副檔名**，Flowable 不會把它當 BPMN 解析 → 回 200 與 deploymentId，
> 但**不產生任何流程定義**。用上傳檔名（含副檔名）或省略 `name` 即正常。
>
> **2026-10-01（晚間第三輪）—— #67 流程層 webhook 接上，後端 749 全綠。**
> 使用者裁決設定來源＝`<process>` 的 `flowable:webhooks`（與節點層對稱）。
> `ProcessCompletedListener` 讀到設定才發、帶 `__webhookUrl`／`__webhookMethod`；
> 沒有設定完全不發。附帶修正 `docs/bpm-platform-spec.md` §11.4 三處文件與程式落差
> （流程層來源、`process.completed` 不送 `allVariables`、`hmacSignature` 走標頭而非
> body、節點事件不送 `variables`）。後端 **749**（727 ＋ `ProcessCompletedListenerTest`
> 11 ＋ `ProcessWebhookWiringTest` 11）。線上實測見 #67 列。`#67` 仍 🟡（剩 #25 payload
> 與前端測試缺口）。
> ⚠️ **dev 庫再增探測殘留**：`probe-67p`／`probe-67p-noconfig`。
>
> 🔴 **`mvn verify` 失敗但 `mvn test-compile` 成功 —— 記在這裡因為它極難診斷。**
> 2026-10-01 實測：`mvn verify` 報 **53 errors**，訊息是
> `Unresolved compilation problem: TestGatewayMockMvcCustomizer cannot be
> resolved to a variable`（ECJ 的標記），但 `mvn -q test-compile` **回 exit 0**、
> 零錯誤。原因是 `bpm-core/` 底下有**未納入版控**的 Eclipse 專案檔
> （`.classpath`／`.project`／`.settings/`），而 `target/test-classes` 裡躺著
> **Eclipse JDT 編譯的 class 檔**（帶著上述未解析標記）。`.classpath`
> **不含任何 junit／mockito 的測試相依**，所以那些 class 編譯時就是壞的。
> Maven 的增量判斷**只看時間戳**，`.class` 比 `.java` 新 → 直接沿用 →
> **`mvn clean verify` 才會全部重編**，一次就全綠。
> **遇到「Unresolved compilation problem」就想到 `mvn clean verify`**，
> 不要去逐個修測試原始碼 —— 那些原始碼沒有錯。
>
> ⚠️ **dev 庫有一個殘留的流程定義 `probe-blank-literal`**（2026-10-01 留下）。
> 那是為了裁決「`flowable:assignee="   "`（空格字面值）到底產生 null 還是
> 空白字串」而部署的 —— 結論是 **assignee 為 NULL**（既有
> `UnreachableTaskListener` 的註解是對的）。**它刪不掉**：沒有部署刪除端點，
> 而手工刪 Flowable 內部表被 `ACT_FK_EXE_PROCDEF` 等外鍵擋住（四種刪除
> 次序都試過）。`acceptance-test.sh` 仍 PASS 7 / FAIL 0，它只是一個無害的
> 殘留。**要清掉只能重建 dev volume。**

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
> - ~~#79-2（對**已完成**的關卡 `POST .../comments` 回 500）~~ ——
>   ✅ **2026-09-30 完成**（使用者裁決：**回 404**）。`TaskController.addComment`
>   捕捉 `AddCommentCmd` 丟出的 `FlowableObjectNotFoundException` 並翻成
>   `ResponseStatusException(404)`。**風險評估結論：不會誤傷真正存在的任務** ——
>   用 `javap` 逐一檢查 Flowable 7.2.0 的 `AddCommentCmd.execute` 位元碼，
>   確認它**只在兩處**拋這個例外（`taskService.getTask(taskId) == null`、
>   `findById(pid) == null`），兩者都是「runtime 裡查不到」；
>   暫停中的任務／流程實例拋的是 `FlowableException`（不同類別，維持 500），
>   資料庫問題拋 `DataIntegrityViolationException`（也維持 500）。
>   **刻意不用「先查再留言」**：那會在 controller 再寫一份
>   「taskId 有沒有在 runtime」的規則（`ProcessAccessGuard.processInstanceIdOfTask`
>   已經是那條規則），違反「規則只能有一份」；而且預先檢查消除不了競態，
>   終究還是要同一層 catch。**讀端完全未動** ——
>   `GET /api/tasks/{id}/comments` 與 `GET /api/history/tasks/{id}/comments`
>   讀的是 `ACT_HI_COMMENT`，與任務是否在 runtime 無關，已用測試釘死
>   （否則審結案件的簽核軌跡會在 `ApprovalTimeline` 上整段消失，
>   而它把錯誤 `catch` 成 `[]` **不會報錯**）。
>   測試 +2（17 → 19）；三組負向控制組見 commit 訊息。
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
