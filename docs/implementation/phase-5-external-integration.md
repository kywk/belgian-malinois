# Phase 5（3週）— 加簽與外部整合

## 目標

完成外部系統接入全套功能（API Key 管理、流程發起、狀態查詢、自動化審批）和特定子流程加簽。Phase 結束時外部系統應能：申請 API Key → 查詢流程變數規格 → 發起流程 → 查詢狀態 → 收到 Webhook 通知。

## 前置條件

- Phase 4 完成：BPMN Editor、Webhook 後端、BPMN Lint、CI/CD

---

## Implementation Plan

### Week 1：外部系統管理 + API 認證

1. ExternalSystem Entity + 管理 API
   - Entity：id, systemId(unique), systemName, apiKey(SHA-256 hash), contactEmail, allowedProcessKeys(JSON), allowedActions(JSON), callbackUrl, ipWhitelist, enabled, createdAt, lastUsedAt
   - Admin API：
     POST /api/admin/external-systems — 建立（回傳明文 API Key 僅此一次）
     GET /api/admin/external-systems — 列表
     GET /api/admin/external-systems/{systemId} — 詳情
     PUT /api/admin/external-systems/{systemId} — 更新
     DELETE /api/admin/external-systems/{systemId} — 停用
     POST /api/admin/external-systems/{systemId}/rotate-key — 重新產生 Key
     GET /api/admin/external-systems/{systemId}/usage-logs — 使用紀錄
2. API Key 認證 Filter
   - ExternalApiAuthFilter：攔截 /api/external/** 請求
   - 驗證流程：X-API-Key + X-System-Id → 查 DB → 檢查 enabled → 檢查 IP 白名單 → 檢查 allowedActions → 檢查 allowedProcessKeys
   - 認證失敗回傳 401/403 + 寫入稽核 log
3. ProcessVariableSpec Entity + API
   - Entity：id, processDefinitionKey, variableName, variableType(string|number|date|boolean), required, description, example
   - Admin API：POST/PUT /api/admin/process-definitions/{key}/variable-spec
   - External API：GET /api/external/process-definitions/{key}/variable-spec

### Week 2：外部系統業務 API

4. 外部系統發起流程
   - POST /api/external/process-instances
   - 接收：processDefinitionKey, businessKey, initiator(system:xxx), firstTaskAssignee, firstTaskCandidateGroups, variables
   - 處理：驗證 variables 符合 ProcessVariableSpec → 啟動流程 → 設定 effectiveInitiator → 回傳 processInstanceId + currentTask
5. 外部系統查詢流程狀態
   - GET /api/external/process-instances/{processInstanceId}/status
   - GET /api/external/process-instances?businessKey={businessKey}
   - 回傳：processInstanceId, businessKey, status(running|completed|rejected|cancelled), startedAt, currentTasks[], result
   - 權限控制：只能查詢自己發起的流程（依 systemId 過濾）
6. 節點 API 觸發（自動化審批）
   - PUT /api/external/tasks/{taskId}
   - 接收：action(complete|claim), variables
   - 權限控制：allowedActions 包含 complete_task + allowedProcessKeys 包含該流程
   - 稽核 log 標記 operatorSource=external_api
7. 外部表單連結機制（後端）
   - 流程啟動時，若 formKey 為 external:，將 URL 模板存入流程變數
   - 前端已在 Phase 3 完成 ExternalFormLink.vue

### Week 3：特定子流程加簽 + 前端管理頁面 + 測試

8. 特定子流程加簽（Call Activity）
   - 預定義加簽子流程 BPMN：
     法務加簽流程（legal-countersign-process）
     財務覆核流程（finance-review-process）
   - Call Activity 配置：calledElement, inheritVariables, in/out 變數映射
   - BPMN Editor 中 Call Activity 節點的 Properties Panel：選擇已部署的子流程
9. 前端外部系統管理頁面
   - ExternalSystemAdmin.vue（路由 /admin/external-systems）
   - 列表：systemId, systemName, enabled, lastUsedAt, 操作（編輯/停用/輪換 Key）
   - 建立對話框：systemName, contactEmail, allowedProcessKeys(多選), allowedActions(多選), ipWhitelist
   - 建立成功後顯示 API Key（僅此一次，提示複製）
10. 前端流程變數規格管理 UI
    - ProcessVariableSpecAdmin.vue（路由 /admin/process-definitions/:key/variables）
    - 表格：variableName, variableType, required, description, example
    - 新增/編輯/刪除行
    - 從 BPMN 流程定義自動掃描使用到的變數名稱（輔助功能）
11. 整合測試
    - 外部系統完整流程：建立 API Key → 發起流程 → 查詢狀態 → 自動審批 → 收到 Webhook
    - 加簽子流程：主流程 → Call Activity → 子流程完成 → 主流程繼續
    - 安全測試：無效 Key、IP 不在白名單、超出 allowedProcessKeys

---

## Tasks Breakdown

| # | Task | 服務 | 預估 | 依賴 |
|---|------|------|------|------|
| 5.1 | ExternalSystem Entity + Admin CRUD API | BPM Core | 1.5d | Phase 4 |
| 5.2 | API Key 生成 + SHA-256 儲存 + 輪換 | BPM Core | 1d | 5.1 |
| 5.3 | ExternalApiAuthFilter（認證 + 授權） | BPM Core | 1.5d | 5.1 |
| 5.4 | ProcessVariableSpec Entity + API | BPM Core | 1d | Phase 4 |
| 5.5 | 外部系統發起流程 API + effectiveInitiator | BPM Core | 2d | 5.3, 5.4 |
| 5.6 | 外部系統查詢流程狀態 API | BPM Core | 1d | 5.3 |
| 5.7 | 節點 API 觸發（自動化審批） | BPM Core | 1d | 5.3 |
| 5.8 | 外部表單連結後端處理 | BPM Core | 0.5d | Phase 3 |
| 5.9 | 特定子流程加簽 BPMN + Call Activity 配置 | BPM Core | 1.5d | Phase 4 |
| 5.10 | Call Activity Properties Panel 擴充 | Frontend | 1d | Phase 4 |
| 5.11 | ExternalSystemAdmin.vue | Frontend | 2d | 5.1 |
| 5.12 | ProcessVariableSpecAdmin.vue | Frontend | 1.5d | 5.4 |
| 5.13 | 整合測試 + 安全測試 | All | 2d | All |

---

## Subagent Handoff Prompts

### Agent A：外部系統管理 + 認證（Week 1）

```
你負責建立外部系統管理和 API Key 認證機制。

請閱讀 docs/bpm-platform-spec.md §九（9.1 外部系統管理）。

請完成：

1. ExternalSystem JPA Entity：
   - id(UUID), systemId(unique), systemName, apiKey(String, 儲存 SHA-256 hash),
     contactEmail, allowedProcessKeys(JSON string → List<String>),
     allowedActions(JSON string → List<String>: start_process|complete_task|query_status|callback),
     callbackUrl(nullable), ipWhitelist(nullable, 逗號分隔),
     enabled(boolean), createdAt, lastUsedAt

2. API Key 機制：
   - 建立時：生成 UUID-based key（如 sk-{UUID}），回傳明文，DB 存 SHA-256 hash
   - 驗證時：對傳入 key 做 SHA-256，比對 DB
   - 輪換：生成新 key，舊 key 立即失效

3. Admin API（需系統管理員權限，從 JWT 取角色）：
   - POST /api/admin/external-systems → 建立，回傳 { systemId, apiKey(明文) }
   - GET /api/admin/external-systems → 列表（不含 apiKey）
   - GET /api/admin/external-systems/{systemId} → 詳情
   - PUT /api/admin/external-systems/{systemId} → 更新
   - DELETE /api/admin/external-systems/{systemId} → 軟刪除（enabled=false）
   - POST /api/admin/external-systems/{systemId}/rotate-key → 回傳新 key
   - GET /api/admin/external-systems/{systemId}/usage-logs → 從 audit log 查詢

4. ExternalApiAuthFilter（Spring OncePerRequestFilter）：
   - 攔截路徑：/api/external/**
   - 流程：
     a. 取 X-API-Key + X-System-Id header
     b. SHA-256(apiKey) 比對 DB
     c. 檢查 enabled=true
     d. 檢查 IP 白名單（若有設定）：request.getRemoteAddr() in ipWhitelist
     e. 檢查 allowedActions 包含當前操作
     f. 檢查 allowedProcessKeys 包含目標流程（從 request body 取）
     g. 通過 → 設定 SecurityContext，繼續
     h. 失敗 → 401/403 + 寫入稽核 log（EXTERNAL_API_CALL, detail 含失敗原因）
   - 更新 lastUsedAt

5. ProcessVariableSpec Entity + API：
   - Entity：id, processDefinitionKey, variableName, variableType(string|number|date|boolean), required(boolean), description, example
   - POST /api/admin/process-definitions/{key}/variable-spec（批次儲存）
   - PUT /api/admin/process-definitions/{key}/variable-spec/{id}
   - GET /api/external/process-definitions/{key}/variable-spec（外部系統查詢，需 API Key）
```

### Agent B：外部系統業務 API（Week 2）

```
你負責實作外部系統的業務 API（發起流程、查詢狀態、自動化審批）。

請閱讀 docs/bpm-platform-spec.md §九（9.2-9.5）。

Agent A 已完成 ExternalSystem 管理和 ExternalApiAuthFilter，你的 API 都在 /api/external/ 路徑下，已自動經過認證。

請完成：

1. 外部系統發起流程（POST /api/external/process-instances）：
   - 接收：processDefinitionKey, businessKey, initiator("system:{systemId}"), firstTaskAssignee(可選), firstTaskCandidateGroups(可選), variables, callbackUrl(可選)
   - 驗證：
     a. processDefinitionKey 在 allowedProcessKeys 內（Filter 已檢查）
     b. variables 符合 ProcessVariableSpec（required 欄位必填，型別正確）
     c. initiator 以 system: 開頭時，firstTaskAssignee 或 firstTaskCandidateGroups 至少一個必填
   - 處理：
     a. 啟動 Flowable 流程：runtimeService.startProcessInstanceByKey(key, businessKey, variables)
     b. 若 initiator 以 system: 開頭：variables.put("effectiveInitiator", firstTaskAssignee)
     c. 若有 firstTaskAssignee：設定第一個 task 的 assignee
     d. 若有 callbackUrl：存入流程變數 _callbackUrl
   - 回傳：{ processInstanceId, businessKey, status:"running", currentTask: { taskId, taskName, assignee } }

2. 查詢流程狀態：
   - GET /api/external/process-instances/{processInstanceId}/status
   - GET /api/external/process-instances?businessKey={businessKey}
   - 權限：只能查詢由該 systemId 發起的流程（比對 initiator 含 systemId）
   - 回傳：{ processInstanceId, businessKey, status(running|completed|rejected|cancelled), startedAt, currentTasks[{taskId,taskName,assignee,createdAt}], result(null|approved|rejected), completedAt }

3. 節點 API 觸發：
   - PUT /api/external/tasks/{taskId}
   - 接收：{ action:"complete", variables: { approved:true, ... } }
   - 權限：allowedActions 含 complete_task，且該 task 所屬流程在 allowedProcessKeys 內
   - 稽核 log：operatorSource="external_api", operatorId="system:{systemId}"

4. 外部表單連結後端：
   - 流程啟動時，若 UserTask 的 formKey 為 external:，將完整 URL（替換變數後）存入流程變數 _externalFormUrl
   - 前端取流程變數時可直接使用
```

### Agent C：前端管理頁面（Week 3）

```
你負責建立外部系統管理和流程變數規格的前端管理頁面。

請完成：

1. ExternalSystemAdmin.vue（路由 /admin/external-systems）：
   - 列表頁：表格顯示 systemId, systemName, enabled(tag), allowedProcessKeys(tags), lastUsedAt
   - 操作列：編輯、停用/啟用、輪換 Key
   - 建立對話框：
     systemName(必填), contactEmail(必填),
     allowedProcessKeys(多選，從已部署流程列表選),
     allowedActions(多選 checkbox: start_process, complete_task, query_status, callback),
     ipWhitelist(textarea, 逗號分隔),
     callbackUrl(可選)
   - 建立成功：彈出對話框顯示 API Key，提示「此 Key 僅顯示一次，請立即複製」，附複製按鈕
   - 輪換 Key：確認對話框（「舊 Key 將立即失效」）→ 顯示新 Key

2. ProcessVariableSpecAdmin.vue（路由 /admin/process-definitions/:key/variables）：
   - 頂部：流程名稱 + processDefinitionKey
   - 可編輯表格：variableName, variableType(下拉), required(checkbox), description, example
   - 新增行 / 刪除行
   - 儲存按鈕（批次 POST）
   - 「從 BPMN 掃描」按鈕：解析該流程 BPMN XML 中使用的變數名稱，自動填入

3. services/externalApi.js：
   - Admin API：CRUD external-systems, rotate-key
   - Admin API：CRUD process variable spec

4. 路由配置：
   - /admin/external-systems → ExternalSystemAdmin
   - /admin/process-definitions/:key/variables → ProcessVariableSpecAdmin
```
