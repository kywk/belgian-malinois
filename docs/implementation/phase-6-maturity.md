# Phase 6（持續）— 成熟化

## 目標

持續迭代的進階功能。無固定截止日，依優先順序逐步交付。包含多渠道通知、通用 Delegate Bean、稽核匯出/合規報告、KPI 儀表板、AI 語意編輯、行動裝置適配。

## 前置條件

- Phase 1-5 全部完成：完整的 BPM 平台核心功能

---

## Implementation Plan

### 迭代 A（2週）：多渠道通知 + 通知管理 UI

1. Teams 通知渠道
   - TeamsNotifyConsumer：監聽 bpm.notify.queue，channel=teams 時呼叫 Teams Incoming Webhook API
   - 訊息格式：Adaptive Card（標題、流程資訊、操作連結）
2. Line Works 通知渠道
   - LineWorksNotifyConsumer：呼叫 Line Works Bot API
   - 訊息格式：Flex Message
3. 前端通知渠道配置管理 UI
   - NotifyConfigAdmin.vue（路由 /admin/notify-config）
   - 依流程定義分組，每個事件類型可配置渠道 + 模板
   - 模板預覽（替換範例變數後顯示）

### 迭代 B（2週）：通用 Delegate Bean 元件庫

4. Delegate Bean 實作
   - EmailNotifyDelegate：通用 Email 發送（可配置收件人、模板）
   - TeamsNotifyDelegate：通用 Teams 通知
   - ExternalApiDelegate：通用外部 API 呼叫（可配置 URL/method/headers/body template）
   - DataValidationDelegate：資料驗證（可配置規則）
   - DynamicAssigneeDelegate：動態計算審核人（可配置 EL 表達式）
5. Delegate Bean 在 BPMN Editor 中的選擇
   - Service Task Properties Panel：下拉選擇已註冊的 Delegate Bean
   - 依選擇的 Bean 顯示對應配置欄位

### 迭代 C（1週）：稽核匯出 + 合規報告

6. 稽核 Log 匯出
   - GET /api/audit-logs/export?format=csv&startDate=&endDate=
   - GET /api/audit-logs/export?format=excel&startDate=&endDate=
   - 匯出操作本身記錄 EXPORT_DATA 稽核 log
7. ISO 27001 合規報告
   - GET /api/audit-logs/compliance-report?quarter=2026-Q1
   - 報告內容：操作統計、異常操作偵測結果、hash chain 完整性驗證結果、存取控制摘要
   - PDF 輸出

### 迭代 D（2週）：流程 KPI 儀表板

8. KPI 資料 API
   - GET /api/analytics/process-stats — 流程統計（發起數、完成數、平均處理時間）
   - GET /api/analytics/task-stats — 任務統計（各節點平均耗時、超時率）
   - GET /api/analytics/user-stats — 人員統計（處理量、平均回應時間）
   - 資料來源：Flowable history 表
9. 前端 KPI 儀表板
   - AnalyticsDashboard.vue（路由 /analytics）
   - 圖表：流程處理趨勢（折線圖）、各流程完成率（長條圖）、節點瓶頸分析（熱力圖）、人員效率排名

### 迭代 E（3週）：AI 語意編輯

10. AI 側邊欄
    - AiSidebar.vue：在 BpmnEditor.vue 右側可展開的側邊欄
    - 自然語言輸入框 + 對話歷史
11. JSON IR 指令執行器
    - 接收 LLM 回傳的 JSON 操作指令
    - 呼叫 bpmn-js Modeling API 執行原子操作（add_task, add_gateway, add_sequence_flow 等）
    - 操作後即時渲染
12. LLM 整合
    - 後端 Proxy API：POST /api/ai/bpmn-assist
    - System Prompt：注入公司權限群組清單 + 當前流程 JSON 結構
    - 回傳 JSON IR 指令陣列

### 迭代 F（2週）：行動裝置適配

13. 響應式佈局
    - 待辦清單、我的申請、審核頁面的行動裝置適配
    - 表單渲染器行動裝置適配
14. PWA 支援（可選）
    - Service Worker + manifest.json
    - 離線待辦清單快取
    - 推播通知

---

## Tasks Breakdown

| # | Task | 服務 | 預估 | 迭代 |
|---|------|------|------|------|
| 6.1 | Teams 通知渠道（Adaptive Card） | BPM Core | 2d | A |
| 6.2 | Line Works 通知渠道（Flex Message） | BPM Core | 2d | A |
| 6.3 | NotifyConfigAdmin.vue（通知配置管理 UI） | Frontend | 3d | A |
| 6.4 | 5 個通用 Delegate Bean 實作 | BPM Core | 4d | B |
| 6.5 | Service Task Properties Panel Delegate 選擇器 | Frontend | 2d | B |
| 6.6 | 稽核 Log CSV/Excel 匯出 | Audit Log | 2d | C |
| 6.7 | ISO 27001 合規報告（PDF） | Audit Log | 3d | C |
| 6.8 | KPI 資料 API（流程/任務/人員統計） | BPM Core | 3d | D |
| 6.9 | AnalyticsDashboard.vue（圖表） | Frontend | 4d | D |
| 6.10 | AiSidebar.vue + 對話 UI | Frontend | 2d | E |
| 6.11 | JSON IR 指令執行器 | Frontend | 3d | E |
| 6.12 | LLM Proxy API + System Prompt | BPM Core | 3d | E |
| 6.13 | 行動裝置響應式適配 | Frontend | 4d | F |
| 6.14 | PWA 支援（可選） | Frontend | 2d | F |

---

## Subagent Handoff Prompts

### Agent A：多渠道通知（迭代 A）

```
你負責實作 Teams 和 Line Works 通知渠道。

Phase 2 已完成 Email 通知（EmailConsumer 監聽 bpm.notify.queue）。
Phase 3 已完成通知模板管理（NotifyTemplate + NotifyConfig）。

請完成：

1. TeamsNotifyConsumer：
   - 監聯 bpm.notify.queue，當 NotifyConfig.channel = "teams" 時處理
   - 呼叫 Microsoft Teams Incoming Webhook URL（從 application.yml 配置）
   - 訊息格式：Adaptive Card JSON
     { type: "AdaptiveCard", body: [{ type: "TextBlock", text: subject, weight: "Bolder" }, { type: "TextBlock", text: body }], actions: [{ type: "Action.OpenUrl", title: "前往處理", url: processUrl }] }
   - 模板變數替換同 EmailConsumer

2. LineWorksNotifyConsumer：
   - 呼叫 Line Works Bot Messaging API
   - OAuth2 認證（client_credentials）
   - 訊息格式：Flex Message 或純文字
   - 收件人：從 OrgService 取使用者的 Line Works ID（需外圍組織系統提供）

3. NotifyDispatcher 重構：
   - 統一入口：依 NotifyConfig.channel 分發到對應 Consumer
   - 支援一個事件多渠道（如同時發 Email + Teams）
```

### Agent B：通用 Delegate Bean（迭代 B）

```
你負責實作通用 Delegate Bean 元件庫，供業務人員在 BPMN Service Task 中使用。

請閱讀 docs/bpm-platform-spec.md §十三（13.3 通用 Delegate Bean 清單）。

請完成：

1. ExternalApiDelegate（最重要）：
   - 實作 JavaDelegate
   - 可配置屬性（從 BPMN XML extensionElements 讀取）：
     url, method(GET|POST|PUT), headers(JSON), bodyTemplate(支援 ${variable} 替換), responseVariable(結果存入的流程變數名)
   - 執行：用 RestTemplate 呼叫外部 API，結果存入流程變數
   - 錯誤處理：搭配 Error Boundary Event

2. DataValidationDelegate：
   - 可配置驗證規則（JSON）：
     [{ field: "amount", rule: "max", value: 100000, message: "金額超過上限" }]
   - 驗證失敗：拋出 BpmnError，觸發 Error Boundary Event

3. DynamicAssigneeDelegate：
   - 可配置 EL 表達式，動態計算下一個節點的審核人
   - 結果存入指定流程變數

4. EmailNotifyDelegate / TeamsNotifyDelegate：
   - Service Task 版本的通知（非 TaskListener 觸發，而是流程中主動發送）
   - 可配置收件人（EL 表達式）、模板

5. Delegate Bean 註冊機制：
   - DelegateBeanRegistry：掃描所有 @DelegateBean 註解的 Bean
   - GET /api/admin/delegate-beans — 回傳可用 Bean 列表 + 配置 Schema
   - 供前端 Properties Panel 使用
```

### Agent C：稽核匯出 + 合規報告（迭代 C）

```
你負責 Audit Log Service 的匯出和 ISO 27001 合規報告功能。

請閱讀 docs/bpm-platform-spec.md §十七（17.5, 17.8）。

請完成：

1. CSV/Excel 匯出：
   - GET /api/audit-logs/export?format=csv&startDate=&endDate=&operationType=&processInstanceId=
   - GET /api/audit-logs/export?format=excel（同上參數）
   - CSV：用 OpenCSV
   - Excel：用 Apache POI
   - 串流輸出（StreamingResponseBody），避免大量資料 OOM
   - 匯出操作本身記錄 EXPORT_DATA 稽核 log

2. ISO 27001 合規報告：
   - GET /api/audit-logs/compliance-report?quarter=2026-Q1
   - 報告內容：
     a. 期間操作統計（各操作類型數量）
     b. 異常操作偵測（短時間大量審批、非上班時間操作、同一人連續操作同一流程）
     c. Hash chain 完整性驗證結果
     d. 外部 API 存取統計
     e. 資料匯出紀錄
   - 輸出格式：JSON（前端渲染）+ PDF（用 iText 或 JasperReports）
```

### Agent D：KPI 儀表板（迭代 D）

```
你負責流程 KPI 分析功能。

請完成：

1. 後端 API（BPM Core）：
   - GET /api/analytics/process-stats?startDate=&endDate=
     回傳：各流程定義的 { processDefinitionKey, processName, startedCount, completedCount, rejectedCount, avgDurationHours }
   - GET /api/analytics/task-stats?processDefinitionKey=&startDate=&endDate=
     回傳：各節點的 { taskDefinitionKey, taskName, avgDurationHours, maxDurationHours, timeoutCount, timeoutRate }
   - GET /api/analytics/user-stats?startDate=&endDate=
     回傳：各人員的 { userId, userName, completedCount, avgResponseHours }
   - 資料來源：Flowable ACT_HI_PROCINST, ACT_HI_TASKINST 表

2. 前端 AnalyticsDashboard.vue（路由 /analytics）：
   - 時間區間選擇器（預設本月）
   - 圖表（用 ECharts 或 Chart.js）：
     a. 流程處理趨勢（折線圖：每日發起數 vs 完成數）
     b. 各流程完成率（長條圖）
     c. 節點瓶頸分析（水平長條圖：各節點平均耗時排名）
     d. 人員效率排名（表格：處理量、平均回應時間）
   - 可依流程定義篩選
```

### Agent E：AI 語意編輯（迭代 E）

```
你負責在 BPMN Editor 中整合 AI 語意編輯功能。

請閱讀 docs/bpm-platform-spec.md §十四 AI 整合。

請完成：

1. AiSidebar.vue：
   - 在 BpmnEditor.vue 右側可展開/收合的側邊欄
   - 自然語言輸入框 + 送出按鈕
   - 對話歷史（使用者輸入 + AI 回應 + 執行結果）
   - 「撤銷上一步」按鈕

2. JSON IR 指令執行器（useAiBpmnAssist.js composable）：
   - 接收 JSON 操作指令陣列
   - 支援操作：
     add_task：呼叫 modeling.createShape + modeling.connect
     add_gateway：同上
     add_sequence_flow：modeling.connect + 設定 condition
     set_timer：設定 Timer Boundary Event
     update_element：modeling.updateProperties
     remove_element：modeling.removeShape
   - 每個操作記錄 undo stack
   - 執行後高亮受影響的元素

3. 後端 LLM Proxy API：
   - POST /api/ai/bpmn-assist
   - 接收：{ message: "在主管審核後加入法務會辦", currentProcessJson: {...} }
   - System Prompt 組裝：
     a. 角色定義：你是 BPMN 流程設計助手
     b. 注入公司權限群組清單（從 DB 查詢）
     c. 注入當前流程的簡化 JSON 結構
     d. 要求只輸出 JSON IR 指令陣列
   - 呼叫 LLM API（Claude / GPT，從 application.yml 配置）
   - 回傳 JSON IR 指令

4. 錯誤處理：
   - LLM 回傳非法 JSON → 提示使用者重試
   - 指令執行失敗 → 自動 undo + 顯示錯誤
```
