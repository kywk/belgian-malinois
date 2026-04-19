# Phase 3（4週）— 表單設計器 + 公文系統

## 目標

完成拖拉式表單設計器，讓業務人員自行建立表單。完成公文系統（文號管理、附件）。完成通知模板管理取代硬編碼。Phase 結束時業務人員應能：設計表單 → 綁定流程 → 發起公文 → 審核含加簽 → 收到模板化通知。

## 前置條件

- Phase 2 完成：Form Service API、DynamicForm 渲染器、核心審批流程、待辦/我的申請頁面

---

## Implementation Plan

### Week 1：Form Editor API + 前端骨架

1. Form Service 表單設計器 API 擴充
   - 表單版本管理：建立新版本時複製上一版 Schema
   - 表單預覽 API：POST /api/forms/preview（傳入 Schema，回傳渲染結果）
   - 表單匯出/匯入：GET/POST /api/forms/{id}/export（JSON 格式）
2. 前端 Form Editor 骨架
   - FormEditor.vue：三欄佈局（左：元件面板 / 中：畫布 / 右：屬性設定）
   - FieldPalette.vue：13 種元件的拖拉來源（見 §8.2）
   - FormCanvas.vue：拖放目標區域，支援排序
   - 使用 vuedraggable 或 @vueuse/core 的 useDraggable

### Week 2：Form Editor 完成 + 公文系統

3. 前端 Form Editor 完成
   - FieldConfig.vue：選中元件後顯示屬性設定面板（label, required, options 等）
   - FormPreview.vue：即時預覽（複用 DynamicForm.vue，mode=edit）
   - 儲存/發佈流程：編輯 → 儲存草稿 → 預覽 → 發佈
4. 公文表單設計
   - 公文 Entity：DocumentRequest（id, documentNumber, processInstanceId, title, urgencyLevel, createdBy, createdAt）
   - 文號管理：自動編碼規則（年度-部門-流水號，如 DOC-2026-FIN-001）
   - DocumentController：POST /api/documents（建立公文 + 啟動流程）
5. 附件上傳
   - FileAttachment Entity（id, processInstanceId, taskId, fileName, filePath, fileSize, contentType, uploadedBy, uploadedAt）
   - POST /api/attachments（multipart upload）
   - GET /api/attachments/{id}/download
   - 儲存策略：本地檔案系統或 S3（可配置）

### Week 3：通知模板 + 加簽

6. 通知模板管理
   - NotifyTemplate Entity（id, name, channel, subjectTemplate, bodyTemplate）
   - NotifyConfig Entity（id, processDefinitionKey, eventType, channel, templateId, enabled）
   - API：CRUD /api/admin/notify-templates, /api/admin/notify-configs
   - 模板變數替換引擎：${processName}, ${taskName}, ${assigneeName}, ${initiatorName}, ${dueDate}, ${processUrl}
   - 改造 Phase 2 的 EmailConsumer：從硬編碼改為查詢模板
7. 前端加簽功能
   - ActionDialog 擴充「加簽」按鈕
   - CountersignDialog.vue：選擇加簽人（OrgSelector 多選模式）+ 加簽說明
   - 呼叫 POST /api/tasks（建立子任務）
   - 待辦清單顯示加簽任務（parentTaskId 不為空的標記為「加簽」）

### Week 4：外部表單連結 + 整合測試

8. ExternalFormLink.vue
   - 偵測 formKey 以 `external:` 開頭
   - 替換 URL 變數（processInstanceId, taskId）
   - 「開啟外部表單」按鈕 → window.open
   - 提示文字：「請在外部系統查看資料後，回到此頁面進行審批」
9. 整合測試
   - 表單設計 → 發佈 → 綁定流程 → 發起 → 審核（含動態表單渲染）
   - 公文流程端到端
   - 加簽流程端到端
   - 通知模板替換驗證

---

## Tasks Breakdown

| # | Task | 服務 | 預估 | 依賴 |
|---|------|------|------|------|
| 3.1 | Form Service 版本管理 + 預覽 + 匯出匯入 API | Form Service | 1.5d | Phase 2 |
| 3.2 | FormEditor.vue 骨架（三欄佈局 + 拖拉框架） | Frontend | 2d | Phase 2 |
| 3.3 | FieldPalette.vue（13 種元件拖拉來源） | Frontend | 1d | 3.2 |
| 3.4 | FormCanvas.vue（拖放 + 排序） | Frontend | 2d | 3.2 |
| 3.5 | FieldConfig.vue（屬性設定面板） | Frontend | 2d | 3.4 |
| 3.6 | FormPreview.vue（即時預覽） | Frontend | 0.5d | 3.4 |
| 3.7 | 公文 Entity + 文號管理 + DocumentController | BPM Core | 2d | Phase 2 |
| 3.8 | 附件上傳（Entity + API + 儲存） | BPM Core | 1.5d | Phase 2 |
| 3.9 | NotifyTemplate + NotifyConfig Entity + API | BPM Core | 2d | Phase 2 |
| 3.10 | 模板變數替換引擎 + EmailConsumer 改造 | BPM Core | 1d | 3.9 |
| 3.11 | CountersignDialog.vue + ActionDialog 加簽擴充 | Frontend | 1.5d | Phase 2 |
| 3.12 | 加簽 API（子任務建立 + 狀態管理） | BPM Core | 2d | Phase 2 |
| 3.13 | ExternalFormLink.vue | Frontend | 1d | Phase 2 |
| 3.14 | 整合測試 | All | 2d | All |

---

## Subagent Handoff Prompts

### Agent A：Form Editor 前端（Week 1-2）

```
你負責建立拖拉式表單設計器前端。Phase 2 已完成 DynamicForm.vue（表單渲染器），本階段建立設計器。

請閱讀 docs/bpm-platform-spec.md §八（8.1-8.4）。

請完成：

1. FormEditor.vue（主頁面，路由 /admin/form-editor/:id?）：
   - 三欄佈局：左 250px（FieldPalette）/ 中（FormCanvas）/ 右 300px（FieldConfig）
   - 頂部工具列：表單名稱輸入、儲存草稿、預覽、發佈按鈕
   - 狀態管理：useFormEditor composable（當前 Schema、選中欄位、dirty 狀態）

2. FieldPalette.vue（左欄）：
   - 分類顯示 13 種元件（見 §8.2）：
     基礎：單行文字、多行文字、數字、日期、日期區間
     選擇：下拉選單、單選、多選
     進階：檔案上傳、人員選擇、金額
     展示：說明文字、連結
   - 每個元件可拖拉到 FormCanvas
   - 使用 vuedraggable（vue.draggable.next）

3. FormCanvas.vue（中欄）：
   - 拖放目標區域
   - 已放置的欄位可拖拉排序
   - 點擊欄位 → 選中（高亮）→ 右欄顯示屬性
   - 刪除按鈕（hover 時顯示）
   - 空狀態提示：「拖拉左側元件到此處」

4. FieldConfig.vue（右欄）：
   - 依選中欄位類型顯示對應屬性：
     通用：id(自動生成,可改)、label、required、readonly
     text：placeholder、maxLength、pattern
     number：min、max、precision
     select/radio/checkbox：options 編輯器（新增/刪除/排序選項）
     date：format、minDate、maxDate
     file：accept、maxSize、maxCount
     link：url（支援 {processInstanceId} 等變數）、openInNewWindow
   - 屬性變更即時反映到 Canvas

5. FormPreview.vue：
   - 對話框模式，複用 DynamicForm.vue（mode=edit）
   - 傳入當前 Schema，即時預覽

6. services/formApi.js 擴充：
   - POST /api/forms（建立）
   - PUT /api/forms/{id}（更新 Schema）
   - POST /api/forms/{id}/publish（發佈）
   - GET /api/forms/{formKey}（取得 Schema）

技術約束：
- 拖拉用 vuedraggable (vue.draggable.next)
- 不需要條件邏輯（欄位間聯動），只有基本屬性
- 欄位 id 預設自動生成（camelCase），使用者可修改
```

### Agent B：BPM Core 公文 + 通知 + 加簽（Week 2-3）

```
你負責 BPM Core 的公文系統、通知模板管理、加簽機制。

請閱讀 docs/bpm-platform-spec.md：
- §四 4.4（加簽機制）
- §十六（通知服務設計）

請完成：

1. 公文系統：
   - DocumentRequest Entity：id, documentNumber, processInstanceId, title, urgencyLevel(normal|urgent|critical), category, createdBy, createdAt
   - 文號自動編碼：{年度}-{部門代碼}-{流水號}，如 DOC-2026-FIN-001
   - DocumentController：
     POST /api/documents — 建立公文 + 自動啟動對應流程
     GET /api/documents/{id} — 公文詳情
     GET /api/documents?createdBy={userId} — 我的公文

2. 附件上傳：
   - FileAttachment Entity：id, processInstanceId, taskId(nullable), fileName, filePath, fileSize, contentType, uploadedBy, uploadedAt
   - POST /api/attachments（multipart/form-data，關聯 processInstanceId）
   - GET /api/attachments?processInstanceId={id} — 列出附件
   - GET /api/attachments/{id}/download — 下載
   - 儲存路徑：{upload-dir}/{processInstanceId}/{uuid}_{fileName}

3. 通知模板管理：
   - NotifyTemplate Entity：id, name, channel(email|teams|line_works|system_webhook), subjectTemplate, bodyTemplate
   - NotifyConfig Entity：id, processDefinitionKey, eventType(task_assigned|process_returned|process_rejected|process_completed|task_timeout), channel, templateId, enabled
   - Admin API：CRUD /api/admin/notify-templates, /api/admin/notify-configs
   - 模板引擎：用 String.replace 或 Thymeleaf 替換 ${processName}, ${taskName}, ${assigneeName}, ${initiatorName}, ${dueDate}, ${processUrl}
   - 改造 EmailConsumer：查詢 NotifyConfig → 取模板 → 替換變數 → 發送

4. 通用加簽機制：
   - POST /api/tasks（建立子任務）：
     parentTaskId, assignee, name="加簽審核 - {原任務名稱}", description
   - 原任務狀態管理：有未完成子任務時，原任務不可 complete
   - 子任務完成時：意見自動附加到原任務 comments
   - 多人加簽：全部子任務完成後原任務才恢復
   - GET /api/tasks/{taskId}/subtasks — 查詢子任務狀態

技術約束：
- 附件大小限制：50MB（application.yml 可配置）
- 文號需保證唯一（DB unique constraint + 樂觀鎖）
- 加簽用 Flowable 原生 subtask 機制
```

### Agent C：前端加簽 + 外部連結（Week 3-4）

```
你負責前端加簽功能和外部表單連結元件。

請完成：

1. ActionDialog.vue 擴充加簽：
   - 新增「加簽」按鈕（在同意/退件/拒絕/轉發旁邊）
   - 點擊 → 開啟 CountersignDialog

2. CountersignDialog.vue：
   - OrgSelector（多選模式）選擇加簽人
   - 加簽說明輸入框
   - 確認後呼叫 POST /api/tasks 為每個加簽人建立子任務
   - 成功後提示「已發送加簽請求給 N 人」

3. 待辦清單擴充：
   - 加簽任務標記（parentTaskId 不為空時顯示「加簽」tag）
   - 原任務顯示加簽狀態（「等待加簽 2/3 完成」）

4. ExternalFormLink.vue：
   - Props：formKey, processInstanceId, taskId
   - 邏輯：
     a. 偵測 formKey.startsWith('external:')
     b. url = formKey.replace('external:', '')
     c. 替換 {processInstanceId}, {taskId} 變數
     d. 渲染「開啟外部表單」按鈕 + 提示文字
     e. window.open(url, '_blank')
   - DocumentDetail.vue 整合：formKey 為 external: 時用 ExternalFormLink 取代 DynamicForm

5. DocumentDetail.vue 改造：
   - 判斷 formKey 類型：
     普通 formKey → DynamicForm.vue
     external: → ExternalFormLink.vue
     混合（Schema 中有 link 元件）→ DynamicForm.vue（link 元件自動渲染為按鈕）
```
