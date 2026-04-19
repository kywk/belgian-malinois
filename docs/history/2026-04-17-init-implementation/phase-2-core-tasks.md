# Phase 2（4週）— 核心待辦 + 基礎表單

## 目標

完成核心審批流程的端到端功能。Phase 結束時應能：發起請假/採購流程 → 審核人在待辦清單看到 → 開啟動態表單 → 同意/退回/拒絕 → 申請人收到 Email 通知。

## 前置條件

- Phase 1 完成：三個微服務骨架、Nginx、RabbitMQ、Redis、Flowable 引擎、OrgService/PermService wrapper、Audit Log Service

---

## Implementation Plan

### Week 1：Form Service + BPM Core API

1. Form Service 微服務建置
   - Spring Boot + JPA + MSSQL（bpm_form_db）
   - `FormDefinition` Entity（id, name, formKey, version, schemaJson, status, createdBy, createdAt, updatedAt）
   - `FormData` Entity（id, formDefinitionId, processInstanceId, taskId, dataJson, submittedBy, submittedAt）
   - API：POST/GET/PUT /api/forms, POST /api/forms/{id}/publish
   - API：POST/GET/PUT /api/form-data, GET /api/form-data/{processInstanceId}
2. 手動建立請假/採購表單 Schema
   - 請假：leaveType(select), dateRange(dateRange), reason(textarea)
   - 採購：itemName(text), quantity(number), amount(number), reason(textarea)
   - 審核表單（review mode）：同上欄位 readonly + approverComment(textarea)
3. BPM Core 待辦清單 API
   - `GET /api/tasks?assignee={userId}` — 指派給我
   - `GET /api/tasks?candidateUser={userId}` — 我可認領
   - `GET /api/tasks?candidateGroups={groups}` — 群組待辦
   - 合併去重邏輯在後端完成，回傳統一格式
4. BPM Core 我發起的流程 API
   - `GET /api/process-instances?initiator={userId}` — 進行中
   - `GET /api/history/process-instances?initiator={userId}&finished=true` — 已結束

### Week 2：審批操作 + 批註 + 通知

5. 審批操作 API
   - `PUT /api/tasks/{id}` body: `{ action: "complete", variables: { approved: true } }` — 同意
   - `PUT /api/tasks/{id}` body: `{ action: "complete", variables: { approved: false } }` — 退件
   - `PUT /api/tasks/{id}` body: `{ action: "complete", variables: { rejected: true, rejectReason: "..." } }` — 拒絕
   - `PUT /api/tasks/{id}` body: `{ action: "delegate", assignee: "userId" }` — 轉發
   - 每個操作後發送 AuditEvent 到 MQ
6. 批註 API
   - `POST /api/tasks/{taskId}/comments` — 新增批註
   - `GET /api/tasks/{taskId}/comments` — 查詢批註
   - `GET /api/history/tasks/{taskId}/comments` — 歷史批註
7. Email 通知（基礎版）
   - Flowable TaskListener：任務指派時發送
   - 硬編碼模板：「你有一筆新待辦：{processName} - {taskName}」
   - 流程退回/拒絕/完成時通知申請人
   - 透過 RabbitMQ 非同步發送（bpm.notify.queue → EmailConsumer）
8. 簡單審核流程 BPMN
   - 請假流程：申請 → 主管審核 → 結束（含退回/拒絕分支）
   - 採購流程：申請 → 主管審核 → 財務審核 → 結束
   - 部署到 Flowable，綁定 formKey

### Week 3-4：前端

9. DynamicForm.vue
   - 依 formKey 向 Form Service 取 Schema
   - 依 processInstanceId 向 BPM Core 取流程變數
   - 欄位 id = 流程變數名稱，自動填入
   - 依 mode（edit/review/readonly）控制可編輯性
   - 提交時寫回流程變數 + 儲存 FormData 快照
10. 待辦儀表板（Dashboard.vue + TaskInbox.vue）
    - Dashboard：統計卡片（待辦數、本週已處理、緊急待辦）
    - TaskInbox：待辦清單表格（流程名稱、任務名稱、發起人、建立時間、狀態）
    - 點擊待辦 → 開啟 DocumentDetail.vue（含 DynamicForm + 操作按鈕）
11. 我的申請頁面（MyApplications.vue）
    - Tab：進行中 / 已完成 / 已拒絕
    - 每筆：流程名稱、發起時間、當前節點、當前審核人、狀態
    - 點擊 → 流程詳情 + 進度圖
12. ActionDialog.vue
    - 同意：確認 + 可選意見
    - 退件：必填退件原因
    - 拒絕：必填拒絕原因
    - 轉發：人員選擇器（OrgSelector）
13. CommentPanel.vue + ApprovalTimeline.vue
    - CommentPanel：批註輸入框 + 批註列表
    - ApprovalTimeline：時間軸顯示流程歷史（誰、何時、做了什麼、意見）

---

## Tasks Breakdown

| # | Task | 服務 | 預估 | 依賴 |
|---|------|------|------|------|
| 2.1 | Form Service 微服務建置 + Entity + API | Form Service | 2d | Phase 1 |
| 2.2 | 請假/採購表單 Schema 建立（含 review 版） | Form Service | 0.5d | 2.1 |
| 2.3 | 待辦清單 API（三種來源合併） | BPM Core | 1.5d | Phase 1 |
| 2.4 | 我發起的流程 API | BPM Core | 0.5d | Phase 1 |
| 2.5 | 審批操作 API（同意/退件/拒絕/轉發） | BPM Core | 2d | Phase 1 |
| 2.6 | 批註 API | BPM Core | 1d | Phase 1 |
| 2.7 | Email 通知（TaskListener + MQ + Consumer） | BPM Core | 1.5d | Phase 1 |
| 2.8 | 請假/採購 BPMN 設計 + 部署 | BPM Core | 1d | 2.5 |
| 2.9 | DynamicForm.vue | Frontend | 3d | 2.1, 2.2 |
| 2.10 | Dashboard.vue + TaskInbox.vue | Frontend | 2d | 2.3 |
| 2.11 | MyApplications.vue | Frontend | 1.5d | 2.4 |
| 2.12 | DocumentDetail.vue + ActionDialog.vue | Frontend | 2d | 2.5, 2.9 |
| 2.13 | CommentPanel.vue + ApprovalTimeline.vue | Frontend | 2d | 2.6 |
| 2.14 | OrgSelector.vue（人員選擇器） | Frontend | 1d | Phase 1 |
| 2.15 | ProcessDiagram.vue（流程圖 + 進度標示） | Frontend | 1d | Phase 1 |
| 2.16 | 端到端測試（發起→審核→通知→完成） | All | 1.5d | All |

---

## Subagent Handoff Prompts

### Agent A：Form Service（Week 1）

```
你負責建立 Form Service 微服務。這是獨立微服務，管理表單定義和表單資料。

請閱讀 docs/bpm-platform-spec.md §八 Form Service。

專案骨架已建好（form-service/），請在此基礎上完成：

1. JPA Entity：
   - FormDefinition：id, name, formKey(unique+version), version, schemaJson(TEXT), status(draft|published), createdBy, createdAt, updatedAt
   - FormData：id, formDefinitionId, processInstanceId, taskId(nullable), dataJson(TEXT), submittedBy, submittedAt

2. REST API：
   - POST /api/forms — 建立表單定義
   - GET /api/forms — 查詢列表（分頁）
   - GET /api/forms/{formKey} — 取得最新 published 版本的 Schema
   - GET /api/forms/{formKey}?version={n} — 取得特定版本
   - PUT /api/forms/{id} — 更新（僅 draft 可更新）
   - POST /api/forms/{id}/publish — 發佈（status → published，version +1）
   - POST /api/form-data — 提交表單資料
   - GET /api/form-data/{processInstanceId} — 取得流程的表單資料
   - PUT /api/form-data/{id} — 更新（退回修改時）

3. 初始資料：手動建立以下表單 Schema（用 data.sql 或 API 呼叫）：
   - formKey="leave-request" (mode=edit)：leaveType(select), dateRange(dateRange), reason(textarea)
   - formKey="leave-review" (mode=review)：同上欄位 readonly + approverComment(textarea)
   - formKey="purchase-request" (mode=edit)：itemName(text), quantity(number), amount(number), reason(textarea)
   - formKey="purchase-review" (mode=review)：同上 readonly + approverComment(textarea)

4. AuditEventPublisher 整合：表單提交/更新時發送 FORM_SUBMIT/FORM_UPDATE 事件到 MQ

技術約束：Spring Data JPA, MSSQL, 資料庫 bpm_form_db
```

### Agent B：BPM Core 業務 API（Week 1-2）

```
你負責實作 BPM Core 的業務 API。Flowable 引擎和基礎封裝已在 Phase 1 完成。

請閱讀 docs/bpm-platform-spec.md §四（4.2-4.5）。

請完成：

1. 待辦清單 API：
   - GET /api/tasks?assignee={userId} — 合併 assignee + candidateUser + candidateGroups
   - 後端合併去重，依 createTime 倒序
   - 回傳格式：{ taskId, taskName, processInstanceId, processDefinitionKey, businessKey, assignee, createTime, dueDate, formKey }

2. 我發起的流程 API：
   - GET /api/process-instances?initiator={userId} — 進行中
   - GET /api/history/process-instances?initiator={userId}&finished=true
   - 回傳格式：{ processInstanceId, processDefinitionKey, businessKey, startTime, endTime, status, currentTask: { taskName, assignee } }

3. 審批操作 API（PUT /api/tasks/{id}）：
   - action=complete + approved=true → 同意
   - action=complete + approved=false → 退件
   - action=complete + rejected=true + rejectReason → 拒絕
   - action=delegate + assignee → 轉發
   - action=claim → 認領
   - action=resolve → 被轉發人回交
   - 每個操作後呼叫 AuditEventPublisher 記錄對應操作類型

4. 批註 API：
   - POST /api/tasks/{taskId}/comments { message, userId }
   - GET /api/tasks/{taskId}/comments
   - GET /api/history/tasks/{taskId}/comments

5. BPMN 流程設計 + 部署：
   - 請假流程：Start → UserTask(主管審核, formKey=leave-review) → ExclusiveGateway(approved/rejected/returned) → End
   - 採購流程：Start → UserTask(主管審核) → UserTask(財務審核) → End
   - 每個 Gateway 需有 rejected 分支導向 End Event
   - 部署到 Flowable

6. Email 通知：
   - TaskListener：任務指派時發送 MQ 訊息到 bpm.notify.queue
   - EmailConsumer：監聽 queue，用 JavaMailSender 發送
   - 硬編碼模板（Phase 3 會改為模板管理）
   - 事件：task_assigned, process_returned, process_rejected, process_completed
```

### Agent C：前端核心頁面（Week 3-4）

```
你負責建立前端核心頁面。

技術棧：Vue 3 + Vite + Pinia + Element Plus (或 Ant Design Vue) + Vue Router

請閱讀 docs/bpm-platform-spec.md §十一 前端架構（11.1, 11.5, 11.6）和 §八（8.4, 8.5）。

請完成：

1. DynamicForm.vue（核心元件）：
   - Props：formKey, processInstanceId, mode(edit|review|readonly)
   - 邏輯：
     a. 用 formKey 呼叫 GET /api/forms/{formKey} 取 Schema
     b. 用 processInstanceId 呼叫 GET /api/tasks/{taskId}/variables 取流程變數（review/readonly 時）
     c. 依 Schema fields 動態渲染表單元件
     d. 欄位 id = 流程變數名稱，自動填入值
     e. mode=review 時資料欄位 readonly，僅 readonly=false 的欄位可編輯
   - 支援元件：text, textarea, number, date, dateRange, select, radio, checkbox, file, link
   - emit: submit(formData)

2. Dashboard.vue：
   - 統計卡片：待辦數量、本週已處理、緊急待辦（超過 3 天未處理）
   - 緊急待辦列表（前 5 筆）

3. TaskInbox.vue：
   - 呼叫 GET /api/tasks?assignee={userId}（合併三種來源）
   - 表格：流程名稱、任務名稱、發起人、建立時間、狀態
   - 點擊行 → router.push 到 DocumentDetail

4. MyApplications.vue：
   - Tab：進行中 / 已完成 / 已拒絕
   - 呼叫 GET /api/process-instances?initiator={userId}
   - 表格：流程名稱、發起時間、當前節點、審核人、狀態
   - 支援催辦按鈕（進行中的流程）

5. DocumentDetail.vue：
   - 上方：DynamicForm（review mode）
   - 下方左：ApprovalTimeline
   - 下方右：CommentPanel
   - 底部：ActionDialog 觸發按鈕（同意/退件/拒絕/轉發）

6. ActionDialog.vue：
   - 同意：確認對話框 + 可選意見輸入
   - 退件：必填退件原因
   - 拒絕：必填拒絕原因 + 確認警告（「流程將終止」）
   - 轉發：OrgSelector 選擇轉發對象
   - 呼叫 PUT /api/tasks/{id} 對應 action

7. CommentPanel.vue：
   - 批註輸入框 + 送出按鈕
   - 批註列表（時間倒序）
   - 呼叫 POST/GET /api/tasks/{taskId}/comments

8. ApprovalTimeline.vue：
   - 時間軸元件，顯示流程歷史
   - 每個節點：操作人、操作時間、操作類型（tag 顏色）、意見
   - 資料來源：GET /api/history/tasks?processInstanceId={id}

9. OrgSelector.vue：
   - 人員搜尋（姓名/工號）
   - 部門樹瀏覽
   - 單選/多選模式
   - 呼叫外圍組織系統 API（透過 BPM Core 轉發）

10. ProcessDiagram.vue：
    - 顯示 BPMN 流程圖（用 bpmn-js viewer mode）
    - 標示當前進度（highlight 當前節點）
    - 呼叫 GET /api/process-instances/{id}/diagram

11. 路由配置：
    - / → Dashboard
    - /tasks → TaskInbox
    - /tasks/:taskId → DocumentDetail
    - /my-applications → MyApplications
    - /audit-log → AuditLog（Phase 1 已完成）

12. services/ API 封裝：
    - flowableApi.js：tasks, process-instances, history
    - formApi.js：forms, form-data
    - orgApi.js：組織查詢（透過 BPM Core）
```
