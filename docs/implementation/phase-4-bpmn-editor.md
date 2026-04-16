# Phase 4（3週）— 流程設計平台

## 目標

完成 BPMN 流程設計器，讓業務人員/流程管理員在瀏覽器中設計流程。包含 Properties Panel 擴充（審核對象選擇器、formKey 綁定、Webhook 設定）、BPMN Lint 驗證、CI/CD pipeline。Phase 結束時應能：設計 BPMN → 設定審核人/表單/Webhook → Lint 驗證通過 → 部署到 DEV。

## 前置條件

- Phase 3 完成：Form Editor、公文系統、通知模板、加簽機制

---

## Implementation Plan

### Week 1：bpmn-js Editor 整合 + Properties Panel 基礎

1. bpmn-js Editor 整合
   - 安裝 bpmn-js, bpmn-js-properties-panel, bpmn-moddle
   - BpmnEditor.vue：初始化 Modeler + Canvas + Properties Panel
   - Flowable moddleExtension 載入（assignee, candidateGroups, formKey 等）
   - 工具列：新建、開啟（匯入 XML）、儲存、匯出 XML、部署、Lint 驗證
   - useBpmnModeler.js composable：封裝 modeler 生命週期
2. Properties Panel 基礎擴充
   - 自訂 PropertiesProvider：擴充 UserTask 的屬性面板
   - Tab 結構：一般 / 審核對象 / 表單 / Webhook / 進階

### Week 2：Properties Panel 三大擴充

3. 審核對象類型選擇器
   - 下拉選擇：特定人員 / 直屬主管（一階）/ 直屬主管（N階）/ 特定權限 / 特定單位 / 特定 Callback
   - 選擇後自動產生對應 EL 表達式寫入 BPMN XML：
     特定人員 → OrgSelector 選人 → `flowable:assignee="${userId}"`
     直屬主管一階 → `flowable:assignee="${orgService.getDirectManager(initiator)}"`
     直屬主管N階 → 數字輸入 → `flowable:assignee="${orgService.getManagerChain(initiator, N)[N-1]}"`
     特定權限 → 權限碼選擇器 → `flowable:candidateUsers="${permService.getUsersByPermission('xxx')}"`
     特定單位 → 部門選擇器 → `flowable:candidateGroups="${deptId}"`
     特定 Callback → 文字輸入 → 搭配 Message Catch Event
4. formKey 綁定選擇器
   - 下拉選擇：內建表單 / 外部連結
   - 內建表單：從 Form Service 載入已發佈的表單列表，選擇後寫入 `flowable:formKey="leave-review"`
   - 外部連結：URL 輸入框，寫入 `flowable:formKey="external:https://..."`
   - Start Event 也支援 formKey 設定（啟動表單）
5. 節點級 Webhook 設定 UI
   - 事件列表：create / complete / timeout / reject
   - 每個事件可配置：URL、Method（POST/PUT）、Headers（key-value 編輯器）、payloadTemplate（可選）
   - 儲存到 BPMN XML extensionElements

### Week 3：BPMN Lint + CI/CD

6. BPMN Lint 自動驗證
   - 前端 Lint（即時，設計時）：
     所有 UserTask 有 Assignee 或 CandidateGroup
     所有 UserTask 有 formKey
     所有 ExclusiveGateway 有 default flow
     節點命名非預設（"Task 1"）
   - 後端 Lint（部署前）：
     formKey 若非 external: 前綴，對應表單必須存在於 Form Service
     使用的 EL 函數在白名單內
     所有 Service Task 有錯誤邊界事件
     若流程允許外部系統發起，第一個 UserTask 不使用 initiator EL
   - Lint 結果顯示：錯誤（紅）/ 警告（黃），點擊可定位到節點
7. BPM Core Webhook 後端
   - WebhookTaskListener：攔截 task create/complete/timeout 事件
   - 從 BPMN XML extensionElements 讀取 webhook 配置
   - 透過 RabbitMQ 非同步發送（bpm.webhook.queue → WebhookConsumer）
   - WebhookConsumer：HTTP 呼叫 + HMAC 簽章 + 重試
   - Payload 組裝：見 §11.4 Webhook Payload 規格
8. CI/CD Pipeline
   - BPMN 部署流程：設計完成 → Lint 驗證 → 儲存 XML → 部署到 Flowable
   - Git 自動 commit：BPMN XML 存到 `bpmn-definitions/{processKey}/{version}.bpmn`
   - 微服務獨立部署 pipeline（每個服務獨立 build + deploy）
   - 環境配置：dev.yml / sit.yml / uat.yml / prod.yml

---

## Tasks Breakdown

| # | Task | 服務 | 預估 | 依賴 |
|---|------|------|------|------|
| 4.1 | bpmn-js Modeler 整合 + BpmnEditor.vue | Frontend | 2d | Phase 3 |
| 4.2 | Flowable moddleExtension + 基礎 Properties Panel | Frontend | 1.5d | 4.1 |
| 4.3 | 審核對象類型選擇器（6 種類型 + EL 產生） | Frontend | 3d | 4.2 |
| 4.4 | formKey 綁定選擇器（內建表單列表 + 外部連結） | Frontend | 1.5d | 4.2 |
| 4.5 | 節點級 Webhook 設定 UI | Frontend | 2d | 4.2 |
| 4.6 | 前端 BPMN Lint（即時驗證） | Frontend | 2d | 4.1 |
| 4.7 | 後端 BPMN Lint API（部署前驗證） | BPM Core | 1.5d | Phase 3 |
| 4.8 | WebhookTaskListener + WebhookConsumer | BPM Core | 2d | Phase 3 |
| 4.9 | Webhook Payload 組裝 + HMAC 簽章 | BPM Core | 1d | 4.8 |
| 4.10 | BPMN 部署 API（Lint → 儲存 XML → 部署 Flowable） | BPM Core | 1d | 4.7 |
| 4.11 | Git 自動 commit（bpmn-definitions/） | BPM Core | 0.5d | 4.10 |
| 4.12 | CI/CD pipeline 配置（微服務獨立部署） | Infra | 1.5d | Phase 1 |
| 4.13 | 整合測試（設計→Lint→部署→Webhook 觸發） | All | 1.5d | All |

---

## Subagent Handoff Prompts

### Agent A：bpmn-js Editor + Properties Panel（Week 1-2）

```
你負責建立 BPMN 流程設計器前端，基於 bpmn-js。

請閱讀 docs/bpm-platform-spec.md §十一（11.2-11.4）。

請完成：

1. BpmnEditor.vue（路由 /admin/bpmn-editor/:processKey?）：
   - 初始化 bpmn-js Modeler
   - 載入 Flowable moddleExtension（見 §11.2）
   - 工具列按鈕：新建、開啟（匯入 XML）、儲存草稿、匯出 XML、部署、Lint 驗證
   - 全螢幕佈局，左側 Canvas，右側 Properties Panel

2. useBpmnModeler.js composable：
   - createModeler(container, propertiesPanel)
   - importXml(xml) / exportXml()
   - getElement(id) / getSelectedElement()
   - on(event, callback)
   - destroy()

3. 自訂 PropertiesProvider（擴充 UserTask 屬性面板）：
   - 使用 bpmn-js-properties-panel 的 extension 機制
   - Tab：一般（name, id）/ 審核對象 / 表單 / Webhook

4. 審核對象 Tab：
   - 「審核對象類型」下拉選擇器，選項：
     a. 特定人員 → 顯示 OrgSelector → 選人後寫入 flowable:assignee="${userId}"
     b. 直屬主管（一階）→ 無額外輸入 → flowable:assignee="${orgService.getDirectManager(initiator)}"
     c. 直屬主管（N階）→ 數字輸入(1-10) → flowable:assignee="${orgService.getManagerChain(initiator, N)[N-1]}"
     d. 特定權限 → 權限碼輸入/選擇 → flowable:candidateUsers="${permService.getUsersByPermission('xxx')}"
     e. 特定單位 → 部門選擇器 → flowable:candidateGroups="${deptId}"
     f. 特定 Callback → 文字輸入 Callback 名稱
   - 選擇後自動更新 BPMN XML 中對應屬性

5. 表單 Tab：
   - 「表單類型」下拉：內建表單 / 外部連結
   - 內建表單：下拉列表（呼叫 GET /api/forms 取已發佈表單），選擇後寫入 flowable:formKey="{formKey}"
   - 外部連結：URL 輸入框，寫入 flowable:formKey="external:{url}"
   - Start Event 也支援此設定

6. Webhook Tab：
   - 事件列表（可新增/刪除）：
     每行：事件類型(select: create|complete|timeout|reject) + URL(input) + Method(select: POST|PUT)
   - 展開：Headers（key-value 編輯器）、payloadTemplate（textarea, 可選）
   - 儲存到 BPMN XML extensionElements

依賴套件：
- bpmn-js: ^17.x
- bpmn-js-properties-panel: ^5.x
- bpmn-moddle: ^9.x
- @bpmn-io/properties-panel: ^3.x
```

### Agent B：BPMN Lint + Webhook 後端（Week 2-3）

```
你負責 BPMN Lint 驗證和節點級 Webhook 後端。

請閱讀 docs/bpm-platform-spec.md §十一（11.4 Webhook Payload）和 §十三（13.2 Lint 規則）。

請完成：

1. 後端 BPMN Lint API：
   - POST /api/bpmn/lint（接收 BPMN XML，回傳驗證結果）
   - 驗證規則：
     a. 所有 UserTask 有 assignee 或 candidateGroups/candidateUsers
     b. 所有 UserTask 有 formKey
     c. formKey 非 external: 時，呼叫 Form Service 確認表單存在
     d. 所有 ExclusiveGateway 有 default flow
     e. 所有 Service Task 有錯誤邊界事件
     f. 節點 name 非空且非預設值
     g. EL 函數名稱在白名單內（orgService, permService, bpmQueryService）
     h. 若流程標記為允許外部發起，第一個 UserTask 不使用 initiator EL
   - 回傳格式：{ valid: boolean, errors: [{ elementId, elementName, rule, message, severity }] }

2. BPMN 部署 API：
   - POST /api/deployments（接收 BPMN XML）
   - 流程：Lint 驗證 → 通過 → 儲存 XML 到 bpmn-definitions/ → 部署到 Flowable
   - 失敗回傳 Lint 錯誤

3. WebhookTaskListener：
   - 實作 TaskListener，註冊到 Flowable
   - 攔截事件：create, complete, delete（timeout 用 Timer Boundary Event）
   - 從 task 的 BPMN XML extensionElements 讀取 webhook 配置
   - 組裝 Payload（見 §11.4）：
     通用欄位：event, timestamp(UTC), processInstanceId, processDefinitionKey, businessKey, taskId, taskName
     complete 額外：operatorId, operatorName, action(approved/returned/rejected), comment, variables
     reject 額外：operatorId, operatorName, rejectReason
   - 發送到 RabbitMQ bpm.webhook.queue

4. WebhookConsumer：
   - 監聽 bpm.webhook.queue
   - HTTP 呼叫：RestTemplate/WebClient POST/PUT 到配置的 URL
   - HMAC-SHA256 簽章：hmacSignature = HMAC-SHA256(secret, payload JSON)
   - secret 從 webhook 配置或全域設定取得
   - 重試：指數退避 1s→2s→4s，最多 3 次，失敗進 DLQ

5. process.completed 事件：
   - FlowableEventListener 監聽 PROCESS_COMPLETED
   - 組裝 Payload：result(approved/rejected), allVariables
   - 發送到 RabbitMQ（routing key = processDefinitionKey）
```

### Agent C：CI/CD Pipeline（Week 3）

```
你負責建立 CI/CD pipeline。

請閱讀 docs/bpm-platform-spec.md §十二 CI/CD 設計。

請完成：

1. Git 分支策略配置：
   - main → PROD, uat → UAT, sit → SIT, dev → DEV
   - PR template

2. 微服務獨立部署 pipeline（GitLab CI 或 GitHub Actions）：
   - bpm-core/：build → test → docker build → deploy
   - form-service/：同上
   - audit-log-service/：同上
   - bpm-frontend/：build → test → docker build → deploy
   - 觸發條件：只有對應目錄有變更時才觸發
   - DEV：commit 自動部署
   - SIT：merge 自動部署
   - UAT/PROD：人工觸發

3. BPMN 部署流程自動化：
   - bpmn-definitions/ 目錄變更時觸發
   - 自動部署到對應環境的 Flowable

4. 環境配置管理：
   - config/dev.yml, sit.yml, uat.yml, prod.yml
   - BPMN EL 佔位符替換（ENV_FINANCE_GROUP 等）

5. Docker Compose（生產版）：
   - 各服務 Dockerfile（multi-stage build）
   - docker-compose.prod.yml
```
