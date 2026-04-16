# 企業級 BPM 流程平台 — 技術規格文件

> 本文件為完整技術對話整理，供 Claude Code 或其他 AI Coding Tool 作為開發上下文使用。

---

## 一、專案背景與目標

### 核心目標
建立一套企業內部**低代碼 BPM 流程平台**，以 Flowable 為引擎，整合企業組織架構與權限系統，讓業務人員能直接設計並部署流程，無需每次都由工程師開發。

### 設計原則
- BPMN 2.0 作為企業流程的統一語言
- 業務人員設計 BPMN → 流程管理員審核 → 工程師開發客製整合（只在必要時）
- 流程邏輯與代碼分離：流程變更不需重新部署應用
- 通用節點元件庫由工程師建置，業務人員重複使用

---

## 二、技術棧

### 後端
| 元件 | 技術 |
|------|------|
| 框架 | Spring Boot |
| 流程引擎 | Flowable 6.x |
| ORM | Spring Data JPA |
| 認證授權 | Sa-Token（RBAC + ABAC）— 外圍既有系統 |
| 快取 | Redis（Ehcache 輔助） |
| Message Queue | RabbitMQ |
| 資料庫 | MSSQL（每個微服務獨立 DB） |
| 服務間通訊 | REST（同步）+ RabbitMQ（非同步） |
| API Gateway | Nginx（路由 + JWT 驗證） |

### 前端
| 元件 | 技術 |
|------|------|
| 框架 | Vue 3 |
| BPMN 編輯器 | bpmn-js |
| Properties Panel | bpmn-js-properties-panel |
| 狀態管理 | Pinia |
| UI 元件 | （公司既有 Element Plus 或 Ant Design Vue） |

### 參考專案
- **DragonFlow / gitee.com/lwj/flow**（MIT License）
  - 後端 Flowable Service 層可參考移植
  - 前端 UI 因技術棧不同需重建
  - bpmn-js 整合方式直接參考

### CI/CD
- Git 分支：`dev` → `sit` → `uat` → `main`
- BPMN XML 版控於 Git（`bpmn-definitions/` 目錄）
- Pipeline：GitLab CI / GitHub Actions
- 部署策略：DEV 自動、SIT 自動、UAT/PROD 人工觸發

---

## 三、系統架構

```
┌──────────────────────────────────────────────────────────┐
│              前端 (Vue3 SPA)                               │
│   BPMN Editor / Form Editor / 待辦 / 我的申請 / 稽核 Log   │
└─────────┬──────────────┬──────────────┬──────────────────┘
          │              │              │
    ┌─────▼──────┐ ┌─────▼──────┐ ┌────▼───────────┐
    │  BPM Core  │ │   Form     │ │  Audit Log     │
    │  Service   │ │  Service   │ │  Service       │
    │            │ │            │ │                │
    │ Flowable   │ │ 表單定義   │ │ append-only    │
    │ 流程引擎   │ │ 表單資料   │ │ ISO 27001      │
    │ 任務操作   │ │ 表單渲染   │ │ hash chain     │
    │ 通知觸發   │ │            │ │                │
    │ Webhook    │ │            │ │                │
    │ 外部接入   │ │            │ │                │
    └──┬──┬──┬───┘ └────────────┘ └────────────────┘
       │  │  │          ▲                 ▲
       │  │  │  REST    │                 │ MQ (所有服務寫入)
       │  │  └──────────┘     ┌───────────┘
       │  │                   │
       │  └──► RabbitMQ ──────┘
       │
  ┌────▼──────────────────────────┐
  │       外圍既有系統              │
  │  ┌──────────┐  ┌────────────┐ │
  │  │ 身分認證  │  │ 權限/組織  │ │
  │  │ (SSO/JWT)│  │  (RBAC)    │ │
  │  └──────────┘  └────────────┘ │
  └───────────────────────────────┘
```

### 微服務職責劃分

| 微服務 | 職責 | 資料庫 |
|--------|------|--------|
| **BPM Core Service** | Flowable 引擎、任務操作、通知觸發、Webhook 觸發、外部 API 接入、Callback 接收 | bpm_core_db（Flowable ACT_* 表 + webhook/notify config） |
| **Form Service** | 表單定義管理、表單資料讀寫、表單 Schema 渲染 | bpm_form_db（form_definition + form_data） |
| **Audit Log Service** | 稽核紀錄寫入、查詢、匯出、合規報告 | bpm_audit_db（獨立存取控制，業務 DBA 不可存取） |

### 服務間通訊

```
BPM Core → Form Service:     同步 REST（取表單 schema、讀寫表單資料）
BPM Core → Audit Log:        非同步 MQ（操作紀錄寫入）
Form Service → Audit Log:    非同步 MQ（表單操作紀錄）
BPM Core → 外圍權限系統:      同步 REST（查組織、查權限）
BPM Core → 外圍身分系統:      JWT 驗證（API Gateway 層處理）
外部系統 → BPM Core:          同步 REST（發起流程、Callback）
BPM Core → 外部系統:          非同步 Webhook（節點事件通知）
```

---

## 四、Flowable 整合設計

### 4.1 BPMN XML 中可用的 EL 函數

業務人員在 bpmn-js 設計流程時可使用以下 Spring Bean 表達式：

```
// 組織相關
${orgService.getDirectManager(initiator)}
${orgService.getAuthorizedManager(initiator, amount)}
${orgService.getManagerChain(initiator, 3)}
${orgService.getDeptGroup(initiator)}

// 權限相關
${permService.getUsersByPermission('finance:payment:approve')}
${permService.hasPermission(initiator, 'purchase:self:approve')}
${permService.getUsersByPermissionAndDept('hr:approve', deptId)}

// 組合查詢
${bpmQueryService.getManagerWithPermission(initiator, 'finance:approve')}
${bpmQueryService.getDeptUsersWithPermission(initiator, 'legal:review')}
```

### 4.2 Task 操作 API

> 所有 API 由 BPM Core 封裝，統一使用 `/api/` 前綴。不直接暴露 Flowable 原生 REST API。

```
# 查詢
GET  /api/tasks?assignee={userId}              → 指派給我
GET  /api/tasks?candidateUser={userId}         → 我可認領
POST /api/tasks/query                          → 複合條件查詢

# 操作
PUT  /api/tasks/{id}  { action: claim }        → 認領
PUT  /api/tasks/{id}  { action: complete }     → 完成（同意/退件）
PUT  /api/tasks/{id}  { action: delegate }     → 轉發
PUT  /api/tasks/{id}  { assignee: userId }     → 改派（直接換人）
PUT  /api/tasks/{id}  { action: resolve }      → 被轉發人完成後回交
POST /api/tasks/{id}/comments                  → 加入意見

# 流程
POST /api/process-instances                    → 啟動流程
GET  /api/process-instances/{id}/diagram       → 流程圖
GET  /api/process-instances?initiator={userId} → 我發起的流程
GET  /api/history/tasks                        → 歷史紀錄
POST /api/deployments                          → 部署 BPMN
```

### 4.3 Task 操作完整清單

| 操作 | API | 流程繼續？ | 場景 |
|------|-----|-----------|------|
| 同意 | complete + approved=true | ✅ 往下 | 正常核可 |
| 退件 | complete + approved=false | ✅ 退件分支 | 不同意，退回上一節點 |
| 退回申請人 | complete + returnTo=initiator | ✅ 回起點 | 資料有誤，退回修改 |
| **拒絕（終止）** | complete + rejected=true | ✅ 流程結束 | 直接否決，流程終止 |
| 轉發 | delegate | ❌ 等回覆 | 請人代審 |
| 改派 | PUT assignee | ❌ 換人繼續 | 完全換人 |
| 加簽 | 見 4.4 加簽機制 | ❌ 等加簽 | 額外會簽 |
| 批註 | POST /api/tasks/{id}/comments | ❌ 不影響 | 留言備註 |
| 催辦 | 通知服務 | ❌ 不影響 | 提醒處理 |

拒絕操作的流程設計：BPMN 中每個 UserTask 後的 ExclusiveGateway 需增加 `rejected` 分支，導向 End Event（流程終止），並觸發通知申請人。

```java
// 拒絕操作 API
PUT /api/tasks/{id}
{
    "action": "complete",
    "variables": [
        { "name": "approved", "value": false },
        { "name": "rejected", "value": true },
        { "name": "rejectReason", "value": "不符合規定" }
    ]
}
```

### 4.4 加簽機制

加簽分為兩種模式：

#### 4.4.1 通用加簽（動態子任務）

當前審核人臨時需要其他人提供意見時使用。透過 Flowable 子任務（Sub Task）實現：

```java
// 建立加簽子任務
POST /runtime/tasks
{
    "parentTaskId": "{currentTaskId}",
    "assignee": "{countersignUserId}",
    "name": "加簽審核 - {原任務名稱}",
    "description": "請提供意見",
    "variables": {
        "countersignType": "general",
        "requestedBy": "{currentAssignee}"
    }
}
```

流程邏輯：
1. 原任務暫停（不可 complete），狀態標記為「等待加簽」
2. 加簽人完成子任務後，原任務恢復可操作
3. 加簽人的意見自動附加到原任務 comments
4. 支援多人加簽（建立多個子任務），全部完成後原任務才恢復

#### 4.4.2 特定子流程加簽（Call Activity）

預先定義好的加簽流程，適用於固定的會簽場景（如法務審查、財務覆核）：

```xml
<!-- 在 BPMN 中以 Call Activity 呼叫預定義的加簽子流程 -->
<callActivity id="legalReview" name="法務加簽"
    calledElement="legal-countersign-process"
    flowable:inheritVariables="true">
    <extensionElements>
        <flowable:in source="documentId" target="documentId"/>
        <flowable:out source="legalOpinion" target="legalOpinion"/>
    </extensionElements>
</callActivity>
```

子流程模板由流程管理員預先設計並部署，業務人員在 BPMN Editor 中透過 Call Activity 節點選擇使用。

### 4.5 批註機制

批註（Comment）為任務留言功能，不影響流程走向：

```java
// 新增批註
POST /api/tasks/{taskId}/comments
{
    "message": "請注意此案金額已超過授權額度",
    "userId": "{commentUserId}"
}

// 查詢批註
GET /api/tasks/{taskId}/comments
GET /api/history/tasks/{taskId}/comments
```

前端 UI：在 DocumentDetail.vue 的審核時間軸中顯示批註，每個節點可展開查看所有留言。

---

## 五、OrgService 規格

> OrgService 為外圍組織系統的 REST Client wrapper，本身不持有組織資料，透過 Redis 快取外圍 API 回應以提升效能。

### 5.1 外圍組織系統 API（BPM Core 呼叫端）

```
# BPM Core 呼叫外圍組織系統的 REST API
GET  {ORG_SERVICE_URL}/api/users/{userId}                    → 取得人員資料
GET  {ORG_SERVICE_URL}/api/users/{userId}/manager             → 直屬主管
GET  {ORG_SERVICE_URL}/api/users/{userId}/manager-chain?levels=3  → 主管鏈
GET  {ORG_SERVICE_URL}/api/users/{userId}/department           → 所屬部門
GET  {ORG_SERVICE_URL}/api/users/{userId}/substitute           → 代理人
GET  {ORG_SERVICE_URL}/api/departments/{deptId}/members        → 部門成員
```

### 5.2 BPM Core 內的 Wrapper Bean

```java
@Service("orgService")
public class OrgService {
    @Autowired
    private OrgRestClient orgRestClient;  // REST Client 呼叫外圍系統
    @Autowired
    private RedisTemplate<String, String> redis;

    // 以下方法供 Flowable EL 表達式使用，內部呼叫外圍 API + Redis 快取

    String getDirectManager(String userId)
    String getAuthorizedManager(String userId, BigDecimal amount)
    String resolveEffective(String userId)       // 考慮代理人
    String getDeptGroup(String userId)
    List<String> getManagerChain(String userId, int levels)
    String getDeptId(String userId)
    boolean isUserAvailable(String userId)
}
```

### 5.3 快取策略

所有方法先查 Redis，miss 時呼叫外圍 API 並寫入快取：

| 資料 | Cache Key 格式 | TTL |
|------|---------------|-----|
| 直屬主管 | `org:manager:{userId}` | 60 分鐘 |
| 主管鏈 | `org:manager-chain:{userId}:{levels}` | 60 分鐘 |
| 部門成員 | `org:dept-members:{deptId}` | 30 分鐘 |
| 代理人 | `org:substitute:{userId}` | 1 分鐘 |
| 人員可用狀態 | `org:available:{userId}` | 5 分鐘 |

主動失效：外圍組織系統人員異動時，透過 webhook 通知 BPM Core 清除對應快取。

```
# 外圍組織系統 → BPM Core 的快取失效通知
POST /api/internal/cache-invalidate/org
{
    "type": "manager_change",    // manager_change | dept_change | substitute_change
    "userIds": ["user001"]
}
```

---

## 六、BpmPermissionService 規格

> BpmPermissionService 為外圍權限系統（Sa-Token）的 REST Client wrapper，本身不持有權限資料。

### 6.1 權限碼規範

```
{system}:{module}:{action}

範例：
  finance:payment:approve    → 財務付款核准
  hr:leave:approve           → 人資假單核准
  purchase:order:approve     → 採購單核准
  legal:contract:review      → 法務合約審查
  purchase:self:approve      → 自主核准（免審核）
```

### 6.2 外圍權限系統 API（BPM Core 呼叫端）

```
GET  {PERM_SERVICE_URL}/api/permissions/{permCode}/users           → 有此權限的使用者
GET  {PERM_SERVICE_URL}/api/permissions/{permCode}/users?deptId={} → 有此權限且在特定部門的使用者
GET  {PERM_SERVICE_URL}/api/users/{userId}/permissions              → 使用者的所有權限
GET  {PERM_SERVICE_URL}/api/users/{userId}/has-permission?code={}   → 檢查使用者是否有特定權限
```

### 6.3 BPM Core 內的 Wrapper Bean

```java
@Service("permService")
public class BpmPermissionService {
    @Autowired
    private PermRestClient permRestClient;  // REST Client 呼叫外圍系統
    @Autowired
    private RedisTemplate<String, String> redis;

    // 以下方法供 Flowable EL 表達式使用，內部呼叫外圍 API + Redis 快取

    List<String> getUsersByPermission(String permCode)
    List<String> getUsersByPermissionAndDept(String permCode, String deptId)
    boolean hasPermission(String userId, String permCode)
    List<String> getUsersByPermissionAndCondition(String permCode, Map<String, Object> attrs)
    String getFirstAvailableUser(String permCode)
    void invalidateCache(String userId)
}
```

### 6.4 快取策略

| 資料 | Cache Key 格式 | TTL |
|------|---------------|-----|
| 權限清單 | `perm:users:{permCode}` | 5 分鐘 |
| 部門+權限 | `perm:users:{permCode}:{deptId}` | 10 分鐘 |
| 使用者權限 | `perm:user:{userId}` | 5 分鐘 |

主動失效：外圍權限系統角色/權限異動時，透過 webhook 通知 BPM Core 清除對應快取。

```
POST /api/internal/cache-invalidate/perm
{
    "type": "permission_change",
    "userIds": ["user001"],
    "permCodes": ["finance:payment:approve"]
}
```

---

## 七、BpmQueryService 規格（組合查詢）

```java
@Service("bpmQueryService")
public class BpmQueryService {

    // 找「直屬主管」且「有特定權限」
    String getManagerWithPermission(String userId, String permCode)

    // 找「同部門」且「有特定權限」且「目前可用」
    List<String> getDeptUsersWithPermission(String userId, String permCode)
}
```

---

## 八、Form Service（表單服務）— 獨立微服務

### 8.1 定位

獨立微服務，提供類似 Google Form 的拖拉式表單設計器，讓業務人員建立流程申請表單，無需工程師開發。表單與 BPMN 流程節點透過 `formKey` 綁定。

獨立部署原因：
- 表單定義與資料有獨立生命週期，可脫離流程獨立設計、測試
- 未來可被其他系統複用（不只 BPM 需要動態表單）
- 資料量大（每次流程都產生表單資料），獨立 DB 方便管理
- 獨立資料庫：`bpm_form_db`

### 8.2 表單元件清單

| 元件類型 | 說明 | 屬性 |
|---------|------|------|
| 單行文字 | 文字輸入 | label, placeholder, required, maxLength, pattern |
| 多行文字 | 文字區域 | label, required, maxLength, rows |
| 數字 | 數字輸入 | label, required, min, max, precision |
| 日期 | 日期選擇 | label, required, format, minDate, maxDate |
| 日期區間 | 起迄日期 | label, required |
| 下拉選單 | 單選下拉 | label, required, options（靜態/API 動態載入） |
| 單選 | Radio | label, required, options |
| 多選 | Checkbox | label, required, options, maxSelect |
| 檔案上傳 | 附件 | label, required, accept, maxSize, maxCount |
| 人員選擇 | OrgSelector | label, required, multiple, scope（全公司/同部門） |
| 金額 | 數字 + 幣別 | label, required, currencies |
| 說明文字 | 純顯示 | content（富文本） |
| 連結 | 外部連結按鈕 | label, url（支援變數替換）, openInNewWindow |

### 8.3 表單資料模型

```java
@Entity
@Table(name = "bpm_form_definition")
public class FormDefinition {
    private String id;
    private String name;              // 表單名稱
    private String formKey;           // 對應 BPMN formKey
    private Integer version;
    private String schemaJson;        // 表單結構 JSON Schema
    private String status;            // draft | published
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

@Entity
@Table(name = "bpm_form_data")
public class FormData {
    private String id;
    private String formDefinitionId;
    private String processInstanceId;
    private String taskId;            // nullable，啟動表單時為 null
    private String dataJson;          // 表單填寫資料 JSON
    private String submittedBy;
    private LocalDateTime submittedAt;
}
```

### 8.4 表單 Schema JSON 結構

```json
{
    "formKey": "leave-request",
    "version": 1,
    "mode": "edit",
    "fields": [
        {
            "id": "leaveType",
            "type": "select",
            "label": "假別",
            "required": true,
            "readonly": false,
            "options": [
                { "label": "特休", "value": "annual" },
                { "label": "事假", "value": "personal" },
                { "label": "病假", "value": "sick" }
            ]
        },
        {
            "id": "dateRange",
            "type": "dateRange",
            "label": "請假期間",
            "required": true
        },
        {
            "id": "reason",
            "type": "textarea",
            "label": "事由",
            "required": true,
            "maxLength": 500
        }
    ]
}
```

表單模式（mode）：
- `edit`：申請表單，欄位可編輯
- `review`：審核表單，資料欄位唯讀，僅審核意見欄可編輯
- `readonly`：純檢視，所有欄位唯讀

每個欄位可透過 `readonly` 覆蓋表單級別的 mode 設定。

### 8.5 欄位與流程變數綁定規則

**核心規則：表單欄位 `id` = Flowable 流程變數名稱。**

```
表單欄位 id: "applicantName"  ←→  流程變數: applicantName
表單欄位 id: "amount"         ←→  流程變數: amount
```

#### 資料流與儲存分工

```
申請人提交表單
    │
    ├─ 1. 前端將表單資料 POST 到 Form Service → 儲存為 FormData（快照備份）
    ├─ 2. 同時將表單欄位值作為 variables 傳給 BPM Core → 寫入 Flowable 流程變數（運行時資料）
    │
審核人開啟待辦
    │
    ├─ 3. 前端向 Form Service 取表單 Schema（欄位結構）
    ├─ 4. 前端向 BPM Core 取流程變數（欄位值）← 運行時資料來源
    └─ 5. 渲染表單：Schema 定義結構，流程變數填入值
```

**兩份資料的用途不同：**
- **Flowable 流程變數**：運行時資料來源，供 EL 表達式、Gateway 條件判斷、審核顯示使用
- **FormData（Form Service）**：提交時的快照備份，用於稽核追溯（「當時提交的原始資料是什麼」），流程變數可能在後續節點被修改，但 FormData 不變

前端渲染流程：
1. 依 UserTask 的 `formKey` 向 Form Service 取表單 Schema
2. 依 `processInstanceId` 向 BPM Core 取流程變數
3. 以欄位 `id` 為 key，將流程變數值填入對應欄位
4. 依 `mode` 和欄位 `readonly` 決定可否編輯
5. 提交時，將可編輯欄位的值寫回流程變數 + 同步儲存 FormData 快照

外部系統發起流程時，傳入的 `variables` 會成為流程變數，因此只要表單欄位 `id` 與變數名稱一致，審核時就能自動顯示外部系統傳入的資料。

### 8.6 表單 API

```
# 表單定義管理
POST   /api/forms                    → 建立表單定義
GET    /api/forms                    → 查詢表單列表
GET    /api/forms/{formKey}          → 取得表單 Schema
PUT    /api/forms/{id}               → 更新表單定義
POST   /api/forms/{id}/publish       → 發佈表單

# 表單資料
POST   /api/form-data                → 提交表單資料（啟動流程時）
GET    /api/form-data/{processInstanceId}  → 取得流程的表單資料
PUT    /api/form-data/{id}           → 更新表單資料（退回修改時）
```

### 8.7 與流程節點綁定

**每個 UserTask 必須綁定表單或外部檢視畫面，二者至少擇一。** BPMN Lint 驗證此規則。

| formKey 格式 | 類型 | 說明 |
|-------------|------|------|
| `leave-request` | 內建表單 | 對應 Form Service 中的表單定義，申請人填寫 |
| `leave-review` | 內建審核表單 | mode=review，資料唯讀 + 審核意見欄 |
| `external:https://...` | 外部畫面 | 開新視窗到外部系統，回來審批 |
| `leave-review-hybrid` | 混合模式 | 內建表單顯示摘要 + link 元件連到外部系統 |

綁定方式：
- 啟動表單：Start Event 設定 `flowable:formKey="leave-request"`
- 節點表單：UserTask 設定 `flowable:formKey="leave-review"`
- 外部表單：UserTask 設定 `flowable:formKey="external:https://example.com/form/{processInstanceId}"`

### 8.8 外部表單連結機制

非平台內建的表單（如外部報名系統、對帳系統），透過 formKey 前綴 `external:` 識別：

```
flowable:formKey="external:https://ext-system.com/review/{processInstanceId}"
```

前端處理邏輯：
1. 偵測 `formKey` 以 `external:` 開頭
2. 替換 URL 中的變數（processInstanceId, taskId 等）
3. 以新視窗開啟外部表單頁面
4. 審批頁面顯示「外部表單已開啟」提示，保留同意/退回/拒絕按鈕
5. 審核人查看外部表單後，回到平台進行審批操作

若外部系統需回傳資料，透過 Callback API（見第十章 10.2）將資料寫入流程變數。

---

## 九、外部系統接入

### 9.1 外部系統管理

#### 9.1.1 資料模型

```java
@Entity
@Table(name = "bpm_external_system")
public class ExternalSystem {
    private String id;
    private String systemId;              // 唯一識別碼，如 "registration-system"
    private String systemName;            // 顯示名稱
    private String apiKey;                // SHA-256 hash 儲存，不可逆
    private String contactEmail;          // 負責人信箱
    private List<String> allowedProcessKeys;  // 允許發起的流程 key
    private List<String> allowedActions;  // start_process | complete_task | query_status | callback
    private String callbackUrl;           // 預設回調 URL（可選）
    private String ipWhitelist;           // IP 白名單（可選，逗號分隔）
    private Boolean enabled;
    private LocalDateTime createdAt;
    private LocalDateTime lastUsedAt;     // 最後呼叫時間
}
```

#### 9.1.2 管理 API（需系統管理員權限）

```
# 外部系統 CRUD
POST   /api/admin/external-systems                → 建立（回傳明文 API Key，僅此一次）
GET    /api/admin/external-systems                 → 列表
GET    /api/admin/external-systems/{systemId}      → 詳情
PUT    /api/admin/external-systems/{systemId}      → 更新（allowedProcessKeys、enabled 等）
DELETE /api/admin/external-systems/{systemId}      → 停用

# API Key 管理
POST   /api/admin/external-systems/{systemId}/rotate-key  → 重新產生 API Key（舊 Key 立即失效）

# 使用紀錄
GET    /api/admin/external-systems/{systemId}/usage-logs   → 呼叫紀錄
```

#### 9.1.3 認證流程

```
外部請求進入
    │
    ├─ 1. Nginx 轉發到 BPM Core
    ├─ 2. 驗證 X-API-Key + X-System-Id
    ├─ 3. 檢查 enabled = true
    ├─ 4. 檢查 IP 白名單（若有設定）
    ├─ 5. 檢查 allowedActions 是否包含此操作
    ├─ 6. 檢查 allowedProcessKeys 是否包含目標流程
    └─ 7. 通過 → 執行操作 + 寫入稽核 log
```

### 9.2 外部系統發起流程

外部系統（報名系統、對帳系統等）可透過 API 直接發起 BPM 流程：

```
POST /api/external/process-instances
Headers:
    X-API-Key: {apiKey}
    X-System-Id: {systemId}
Body:
{
    "processDefinitionKey": "registration-approval",
    "businessKey": "REG-2026-001",
    "initiator": "system:registration",
    "firstTaskAssignee": "manager001",
    "firstTaskCandidateGroups": ["hr_dept"],
    "variables": {
        "applicantName": "...",
        "registrationId": "REG-001",
        "callbackUrl": "https://ext-system.com/api/callback"
    }
}

→ Response:
{
    "processInstanceId": "PRC-550e8400-e29b",
    "businessKey": "REG-2026-001",
    "status": "running",
    "currentTask": {
        "taskId": "TSK-12345",
        "taskName": "主管審核",
        "assignee": "manager001"
    }
}
```

#### 外部 initiator 處理規則

外部系統發起的流程，`initiator` 格式為 `system:{systemId}`，非真實用戶。BPMN 中依賴 `initiator` 的 EL 函數（如 `orgService.getDirectManager(initiator)`）會無法解析。

處理方式：
1. **流程設計時**：供外部系統發起的流程，第一個 UserTask 不可使用 `initiator` 相關 EL 表達式
2. **API 層**：外部系統發起時，必須透過 `firstTaskAssignee` 或 `firstTaskCandidateGroups` 明確指定第一個節點的審核人
3. **後續節點**：可正常使用 EL 表達式，因為後續節點的 `initiator` 可替換為實際經辦人（第一個節點的 assignee）
4. **BPMN Lint 規則**：若流程定義允許外部系統發起（在 ProcessVariableSpec 中標記），驗證第一個 UserTask 不使用 `initiator` EL 函數

```java
// BPM Core 處理外部發起流程
if (initiator.startsWith("system:")) {
    // 將第一個節點的 assignee 設為 firstTaskAssignee
    // 將 firstTaskAssignee 存入流程變數 "effectiveInitiator" 供後續節點使用
    variables.put("effectiveInitiator", firstTaskAssignee);
}
```

### 9.3 節點 API 觸發（自動化審批）

自動化系統可透過 API 代替人工操作節點（同意、退回等），實現流程自動化：

```
PUT /api/external/tasks/{taskId}
Headers:
    X-API-Key: {apiKey}
    X-System-Id: {systemId}
Body:
{
    "action": "complete",
    "variables": {
        "approved": true,
        "autoApprovedBy": "system:auto-approve",
        "autoApproveReason": "金額低於自動核准門檻"
    }
}
```

安全限制：
- 外部 API Key 需明確授權可操作的流程定義與操作類型
- 自動化操作在稽核 log 中標記為 `source: external_api`

### 9.4 外部系統查詢流程狀態

```
# 查詢單一流程狀態
GET /api/external/process-instances/{processInstanceId}/status
Headers:
    X-API-Key: {apiKey}
    X-System-Id: {systemId}

→ Response:
{
    "processInstanceId": "PRC-550e8400-e29b",
    "businessKey": "REG-2026-001",
    "status": "running",           // running | completed | rejected | cancelled
    "startedAt": "2026-04-16T10:00:00Z",
    "currentTasks": [
        {
            "taskId": "TSK-12345",
            "taskName": "主管審核",
            "assignee": "manager001",
            "createdAt": "2026-04-16T10:00:00Z"
        }
    ],
    "result": null                 // 完成後才有值：approved | rejected
}

# 依 businessKey 查詢（外部系統通常用自己的單號查）
GET /api/external/process-instances?businessKey={businessKey}
Headers:
    X-API-Key: {apiKey}
    X-System-Id: {systemId}
```

### 9.5 流程變數清單（對接文件）

每個流程部署時，流程管理員需定義該流程的變數規格，供外部系統對接：

```java
@Entity
@Table(name = "bpm_process_variable_spec")
public class ProcessVariableSpec {
    private String id;
    private String processDefinitionKey;
    private String variableName;       // 變數名稱
    private String variableType;       // string | number | date | boolean
    private Boolean required;          // 是否必填
    private String description;        // 說明
    private String example;            // 範例值
}
```

```
# 查詢流程變數規格（外部系統對接用，需 API Key）
GET /api/external/process-definitions/{processDefinitionKey}/variable-spec
Headers:
    X-API-Key: {apiKey}
    X-System-Id: {systemId}

→ Response:
{
    "processDefinitionKey": "registration-approval",
    "processName": "報名審核流程",
    "variables": [
        { "name": "applicantName",    "type": "string",  "required": true,  "description": "報名人姓名" },
        { "name": "registrationId",   "type": "string",  "required": true,  "description": "報名編號" },
        { "name": "amount",           "type": "number",  "required": false, "description": "報名費用" },
        { "name": "callbackUrl",      "type": "string",  "required": false, "description": "流程結束回調 URL" }
    ]
}

# 管理端：維護流程變數規格（需流程管理員權限）
POST /api/admin/process-definitions/{processDefinitionKey}/variable-spec
PUT  /api/admin/process-definitions/{processDefinitionKey}/variable-spec/{id}
```

---

## 十、非同步流程設計

### 10.1 場景分類

| 場景 | Flowable 節點 | 說明 |
|------|--------------|------|
| 同步呼叫外部 API | Service Task + JavaDelegate | 取得結果繼續 |
| 非同步外部 Worker | External Worker Task | 輪詢認領執行 |
| 等待 Callback | Message Catch Event | 喚醒等待流程 |
| 廣播訊號 | Signal Event | 一對多喚醒 |
| 定時等待 | Timer Event | 超時自動觸發 |
| 流程完成後觸發 | End Event Listener + MQ | 發布到下游系統 |

### 10.2 Callback 機制

```java
// 觸發端：帶入 processInstanceId + callbackToken
// 接收端：
@PostMapping("/api/callback/{type}")
public ResponseEntity<?> handleCallback(@RequestBody CallbackRequest req) {
    // 1. 驗證 HMAC Token（防偽造）
    // 2. 冪等檢查（Redis SetIfAbsent，防重複處理）
    // 3. 喚醒 Flowable Message Event
    runtimeService.createMessageCorrelationBuilder("MessageName")
        .processInstanceId(req.getProcessInstanceId())
        .setVariables(req.getVariables())
        .correlate();
}
```

### 10.3 流程完成後觸發下游

```java
// FlowableEventListener 監聽 PROCESS_COMPLETED
// → 發布 Spring ApplicationEvent
// → RabbitMQ Exchange（routing key = processDefinitionKey）
// → 各消費者：ERP 同步、公文發送、報表產生
```

### 10.4 RabbitMQ 重試策略

- 重試：指數退避，1s → 2s → 4s，最多 3 次
- 失敗後進入 Dead Letter Queue
- DLQ 消費者：發送告警，等待人工介入

---

## 十一、前端架構

### 11.1 目錄結構

```
src/
├── views/
│   ├── Dashboard.vue          ← 儀表板（統計卡片 + 緊急待辦）
│   ├── TaskInbox.vue          ← 待辦清單
│   ├── MyApplications.vue     ← 我發起的流程（申請中 + 歷史）
│   ├── DocumentForm.vue       ← 公文申請表單
│   ├── DocumentDetail.vue     ← 公文詳情 + 審核操作
│   ├── FormEditor.vue         ← 表單設計器（管理員）
│   ├── BpmnEditor.vue         ← 流程設計（管理員）
│   └── AuditLog.vue           ← 稽核 log 查詢
├── components/
│   ├── TaskCard.vue           ← 待辦卡片
│   ├── ApprovalTimeline.vue   ← 流程進度時間軸（含批註顯示）
│   ├── ActionDialog.vue       ← 同意/退件/拒絕/轉發/加簽 Dialog
│   ├── CountersignDialog.vue  ← 加簽人員選擇 Dialog
│   ├── CommentPanel.vue       ← 批註面板
│   ├── OrgSelector.vue        ← 組織人員選擇器（含搜尋）
│   ├── ProcessDiagram.vue     ← 流程圖（含目前進度標示）
│   ├── DynamicForm.vue        ← 動態表單渲染器（依 Schema 渲染）
│   ├── ExternalFormLink.vue   ← 外部表單連結元件
│   └── form-editor/
│       ├── FieldPalette.vue   ← 表單元件面板（拖拉來源）
│       ├── FormCanvas.vue     ← 表單設計畫布
│       ├── FieldConfig.vue    ← 元件屬性設定面板
│       └── FormPreview.vue    ← 表單預覽
├── composables/
│   ├── useMyTasks.js          ← 待辦清單邏輯
│   ├── useMyApplications.js   ← 我發起的流程邏輯
│   ├── useDocument.js         ← 公文操作邏輯
│   ├── useBpmnModeler.js      ← bpmn-js 封裝
│   ├── useFormEditor.js       ← 表單編輯器邏輯
│   ├── useDynamicForm.js      ← 動態表單渲染邏輯
│   └── useOrg.js              ← 組織查詢
└── services/
    ├── flowableApi.js         ← Flowable REST API
    ├── documentApi.js         ← 公文業務 API
    ├── formApi.js             ← 表單定義/資料 API
    ├── externalApi.js         ← 外部系統接入 API
    └── orgApi.js              ← 組織查詢 API
```

### 11.2 bpmn-js 整合

```javascript
// Flowable 擴充屬性（moddleExtensions）
const flowableExtension = {
    name: 'Flowable',
    uri: 'http://flowable.org/bpmn',
    prefix: 'flowable',
    properties: [
        { name: 'assignee',        isAttr: true, type: 'String' },
        { name: 'candidateGroups', isAttr: true, type: 'String' },
        { name: 'candidateUsers',  isAttr: true, type: 'String' },
        { name: 'formKey',         isAttr: true, type: 'String' },
        { name: 'initiator',       isAttr: true, type: 'String' },
        { name: 'skipExpression',  isAttr: true, type: 'String' }
    ]
}
```

### 11.3 審核對象類型選擇器（Properties Panel 擴充）

bpmn-js Properties Panel 中，UserTask 節點提供「審核對象類型」下拉選擇器，根據選擇自動產生對應的 Flowable EL 表達式：

| 審核對象類型 | UI 操作 | 產生的 EL 表達式 |
|-------------|---------|-----------------|
| 特定人員 | 人員選擇器（OrgSelector） | `flowable:assignee="${userId}"` |
| 直屬主管（一階） | 無需額外輸入 | `flowable:assignee="${orgService.getDirectManager(initiator)}"` |
| 直屬主管（二階） | 選擇階數（1-N） | `flowable:assignee="${orgService.getManagerChain(initiator, 2)[1]}"` |
| 特定權限 | 權限碼選擇器 | `flowable:candidateUsers="${permService.getUsersByPermission('xxx')}"` |
| 特定單位 | 部門選擇器 | `flowable:candidateGroups="${deptId}"` |
| 特定 Callback | 輸入 Callback 名稱 | 搭配 Message Catch Event，等待外部指定審核人 |

### 11.4 節點級 Webhook 設定（Properties Panel 擴充）

每個 BPMN 節點可在 Properties Panel 中配置 webhook，支援不同事件觸發不同 URL：

```javascript
// 擴充屬性：節點 webhook 配置
// 儲存於 BPMN XML extensionElements 中
{
    name: 'webhooks',
    type: 'Array',
    items: {
        event: 'String',    // create | complete | timeout | reject
        url: 'String',      // webhook URL
        method: 'String',   // POST | PUT
        headers: 'Object',  // 自訂 headers（含認證）
        payloadTemplate: 'String'  // 可選，自訂 payload 模板
    }
}
```

後端透過 Flowable TaskListener / ExecutionListener 攔截事件，觸發對應 webhook：

```java
@Component
public class WebhookTaskListener implements TaskListener {
    @Override
    public void notify(DelegateTask task) {
        List<WebhookConfig> hooks = getWebhookConfigs(task, event);
        for (WebhookConfig hook : hooks) {
            // 非同步發送，失敗重試（同 RabbitMQ 重試策略）
            webhookService.sendAsync(hook, buildPayload(task));
        }
    }
}
```

#### Webhook Payload 規格

所有事件共用欄位：

| 欄位 | 型別 | 說明 |
|------|------|------|
| event | string | 事件類型 |
| timestamp | string | 事件時間 (UTC, ISO 8601) |
| processInstanceId | string | 流程實例 ID |
| processDefinitionKey | string | 流程定義 key |
| businessKey | string | 業務單號 |
| taskId | string | 任務 ID |
| taskName | string | 任務名稱 |
| hmacSignature | string | HMAC-SHA256 簽章（防偽造） |

各事件額外欄位：

| 事件 | 額外欄位 | 說明 |
|------|---------|------|
| `task.created` | assignee, candidateUsers, candidateGroups, dueDate | 任務建立 |
| `task.completed` (approved) | operatorId, operatorName, action="approved", comment, variables | 同意 |
| `task.completed` (returned) | operatorId, operatorName, action="returned", returnTo, comment | 退回 |
| `task.rejected` | operatorId, operatorName, action="rejected", rejectReason | 拒絕（流程終止） |
| `task.timeout` | assignee, createdAt, dueDate, overdueHours | 超時未處理 |
| `process.completed` | result ("approved"/"rejected"), allVariables | 流程結案 |

Payload 範例（同意）：

```json
{
    "event": "task.completed",
    "timestamp": "2026-04-16T11:30:00Z",
    "processInstanceId": "PRC-550e8400-e29b",
    "processDefinitionKey": "registration-approval",
    "businessKey": "REG-2026-0416-001",
    "taskId": "TSK-12345",
    "taskName": "主管審核",
    "operatorId": "manager001",
    "operatorName": "李主管",
    "action": "approved",
    "comment": "同意報名",
    "variables": {
        "approved": true,
        "approverComment": "同意報名"
    },
    "hmacSignature": "sha256=xxxxxxxx"
}
```

`variables` 範圍規則：
- 節點事件（task.*）：**僅送該節點寫入/修改的變數**，避免暴露流程內部資訊
- 流程結案事件（process.completed）：送完整流程變數
- 若外部系統需要特定變數，透過 `payloadTemplate` 自訂 payload 結構

### 11.5 待辦清單查詢（三種來源合併）

```javascript
// 合併查詢：assignee + candidateUser + candidateGroups
const getAllMyPendingTasks = async (userId, userGroups) => {
    const [assigned, candidate, groupTasks] = await Promise.all([
        fetch(`/api/tasks?assignee=${userId}`),
        fetch(`/api/tasks?candidateUser=${userId}`),
        fetch(`/api/tasks?candidateGroups=${userGroups.join(',')}`)
    ])
    // 合併去重，依建立時間排序
}
```

### 11.6 我發起的流程查詢

```javascript
// 查詢當前用戶發起的所有流程（進行中 + 已結束）
const getMyApplications = async (userId, status) => {
    // 進行中
    const active = await fetch(
        `/api/process-instances?initiator=${userId}`
    )
    // 已結束（歷史）
    const finished = await fetch(
        `/api/history/process-instances?initiator=${userId}&finished=true`
    )
    // 合併，依發起時間倒序
}
```

MyApplications.vue 頁面功能：
- Tab 切換：「進行中」/「已完成」/「已拒絕」
- 每筆顯示：流程名稱、發起時間、當前節點、當前審核人、狀態
- 點擊可查看流程詳情與進度圖
- 支援催辦操作（對進行中的流程）

---

## 十二、CI/CD 設計

### 12.1 分支策略

```
main  → PROD（人工觸發）
uat   → UAT（人工觸發）
sit   → SIT（merge 自動）
dev   → DEV（commit 自動）
```

### 12.2 BPMN 部署流程

1. 業務人員在 DEV bpmn-js 設計完成 → 點「送審」
2. 系統執行 BPMN Lint 驗證
3. 自動 commit BPMN XML 到 `bpmn-definitions/{processKey}/{version}.bpmn`
4. 自動部署到 DEV Flowable
5. 建立 PR（dev → sit），通知流程管理員
6. 管理員 Merge → 自動部署 SIT
7. 業務主管 SIT 測試通過 → 人工觸發 UAT 部署
8. UAT 驗收通過 → 人工觸發 PROD 部署

### 12.3 環境差異處理

BPMN XML 中使用佔位符，部署時替換：

```xml
flowable:candidateGroups="${ENV_FINANCE_GROUP}"
```

```yaml
# config/prod.yml
bpmn.variables:
    ENV_FINANCE_GROUP: finance_dept_approver
    ENV_HR_GROUP: hr_approver
```

### 12.4 多版本並行

Flowable 天生支援多版本：
- 新案自動使用最新版
- 進行中的舊案繼續跑舊版，不受影響

---

## 十三、流程治理機制

### 13.1 角色分工

| 角色 | 職責 |
|------|------|
| 業務人員 | 設計 BPMN，送審 |
| 流程管理員 | 審核 BPMN，設定權限，部署各環境 |
| 開發人員 | 實作通用 Delegate Bean、客製 Form、Web Hook |

### 13.2 BPMN Lint 自動驗證規則

- 所有 UserTask 都有 Assignee 或 CandidateGroup
- **所有 UserTask 都有 formKey（內建表單或 external: 外部畫面）**
- 所有 ExclusiveGateway 都有預設路徑（default flow）
- 所有 Service Task 都有錯誤邊界事件
- 節點命名符合規範（非預設 "Task 1"）
- 使用的 EL 函數名稱在白名單內
- formKey 若非 `external:` 前綴，對應的表單定義必須存在於 Form Service

### 13.3 通用 Delegate Bean 清單（待開發）

```
EmailNotifyDelegate       → 發送 Email
TeamsNotifyDelegate       → 發送 Microsoft Teams
ESignDelegate             → 觸發電子簽章（等待 Callback）
ErpSyncDelegate           → 同步到 ERP
DynamicAssigneeDelegate   → 動態計算審核人
DataValidationDelegate    → 資料驗證
ExternalApiDelegate       → 通用外部 API 呼叫（可設定 URL/method）
```

---

## 十四、AI 整合（進階功能）

### 14.1 定位

AI 不取代業務人員的 BPMN 設計能力，而是提升效率：
- 語意指令加速繁瑣操作：「在主管審核後加入法務會辦」
- 流程分析建議：「這個流程缺少錯誤處理路徑」
- 白話解釋：「解釋這個 Gateway 條件的意思」
- 合規審查：「這個流程符合採購授權規定嗎」

### 14.2 架構：JSON IR 方案（非直接編輯 XML）

```
使用者自然語言輸入
        ↓
LLM（Claude / GPT）
  → 產生 JSON 操作指令（非直接生成 XML）
        ↓
指令執行器
  → 呼叫 bpmn-js Modeling API
  → 原子操作，保持 XML 一致性
        ↓
bpmn-js 即時渲染
```

### 14.3 JSON IR 操作集

```json
[
    { "op": "add_task", "name": "法務會辦", "type": "userTask",
      "assigneeGroup": "legal_dept", "after": "task_manager" },
    { "op": "add_gateway", "name": "金額判斷", "type": "exclusiveGateway" },
    { "op": "add_sequence_flow", "from": "gw1", "to": "task1",
      "condition": "${amount < 100000}", "label": "金額 < 10萬" },
    { "op": "set_timer", "taskId": "task1", "duration": "PT48H" },
    { "op": "update_element", "id": "task1", "properties": { ... } },
    { "op": "remove_element", "id": "task1" }
]
```

### 14.4 System Prompt 關鍵設計

- 注入公司權限群組清單（限制 candidateGroup 只能用合法值）
- 注入目前流程的 JSON 結構（上下文感知編輯）
- 要求只輸出 JSON，不輸出解釋文字

---

## 十五、Cache 策略彙總

> 以下快取皆為 BPM Core 對外圍系統（組織/權限）REST API 回應的 Redis 快取。BPM Core 本身不持有組織與權限資料。

| 資料類型 | 來源 | Cache Key 格式 | TTL | 主動失效 |
|---------|------|---------------|-----|---------|
| 直屬主管 | 外圍組織系統 | `org:manager:{userId}` | 60 分鐘 | 組織系統 webhook → `/api/internal/cache-invalidate/org` |
| 主管鏈 | 外圍組織系統 | `org:manager-chain:{userId}:{levels}` | 60 分鐘 | 同上 |
| 部門成員 | 外圍組織系統 | `org:dept-members:{deptId}` | 30 分鐘 | 同上 |
| 代理人設定 | 外圍組織系統 | `org:substitute:{userId}` | 1 分鐘 | 同上 |
| 權限清單 | 外圍權限系統 | `perm:users:{permCode}` | 5 分鐘 | 權限系統 webhook → `/api/internal/cache-invalidate/perm` |
| 部門+權限 | 外圍權限系統 | `perm:users:{permCode}:{deptId}` | 10 分鐘 | 同上 |
| 使用者權限 | 外圍權限系統 | `perm:user:{userId}` | 5 分鐘 | 同上 |

---

## 十六、通知服務設計

### 16.1 通知觸發時機

| 事件 | 通知對象 | 說明 |
|------|---------|------|
| 任務指派 | 被指派人 | 新待辦事項通知 |
| 任務認領 | 候選人群組 | 通知已被認領（可選） |
| 加簽 | 被加簽人 | 請求提供意見 |
| 催辦 | 當前審核人 | 提醒處理 |
| 流程退回 | 申請人 | 需修改重送 |
| 流程拒絕 | 申請人 | 流程已終止 |
| 流程完成 | 申請人 | 流程已結案 |
| 超時預警 | 當前審核人 + 管理員 | 即將超時 |

### 16.2 通知渠道配置

每個流程定義可配置通知渠道偏好：

```java
@Entity
@Table(name = "bpm_notify_config")
public class NotifyConfig {
    private String id;
    private String processDefinitionKey;  // 流程定義 key
    private String eventType;             // task_assigned | task_timeout | ...
    private String channel;               // email | teams | line_works | system_webhook
    private String templateId;            // 通知模板 ID
    private Boolean enabled;
}
```

### 16.3 通知模板

```java
@Entity
@Table(name = "bpm_notify_template")
public class NotifyTemplate {
    private String id;
    private String name;
    private String channel;          // email | teams | line_works
    private String subjectTemplate;  // 主旨模板（支援變數替換）
    private String bodyTemplate;     // 內容模板（支援變數替換）
}
```

模板變數：`${processName}`, `${taskName}`, `${assigneeName}`, `${initiatorName}`, `${dueDate}`, `${processUrl}`

---

## 十七、Audit Log Service（稽核服務）— 獨立微服務

### 17.1 設計原則

獨立微服務，所有流程相關操作皆記錄稽核 log，不可刪除、不可修改，供合規審查與問題追蹤使用。

獨立部署原因：
- ISO 27001 要求稽核 log 不可被業務系統管理員竄改
- 獨立服務 + 獨立 DB + 獨立存取控制是合規基本要求
- append-only 設計，與業務邏輯完全解耦
- 所有服務透過 MQ 非同步寫入，不影響業務效能
- 獨立資料庫：`bpm_audit_db`（業務 DBA 不可存取）

### 17.2 ISO 27001 合規要求

| ISO 27001 控制項 | 對應設計 |
|-----------------|---------|
| A.12.4.1 事件記錄 | 所有流程操作自動記錄，含操作人、時間、IP、操作內容 |
| A.12.4.2 保護 log 資訊 | append-only DB、獨立存取控制、hash chain 防竄改 |
| A.12.4.3 管理員與操作員 log | 系統管理操作（部署、設定變更）獨立記錄 |
| A.12.4.4 時鐘同步 | 所有微服務使用 NTP 同步，log 時間戳統一為 UTC |
| A.9.4.2 安全登入程序 | 登入失敗、異常存取記錄（由外圍身分系統負責，Audit Log Service 接收） |
| A.16.1.2 資安事件報告 | 異常操作模式偵測 + 告警（如短時間大量審批） |

### 17.3 資料模型

```java
@Entity
@Table(name = "bpm_audit_log")
public class AuditLog {
    private Long id;                    // 自增 ID
    private String traceId;            // 請求追蹤 ID
    private String operationType;      // 操作類型（見 17.4）
    private String operatorId;         // 操作人 ID
    private String operatorName;       // 操作人姓名
    private String operatorSource;     // user | external_api | system
    private String processDefinitionKey;
    private String processInstanceId;
    private String taskId;             // nullable
    private String businessKey;        // 業務單號
    private String detail;             // 操作詳情 JSON
    private String previousState;      // 操作前狀態（資料變更追蹤）
    private String newState;           // 操作後狀態
    private String ipAddress;          // 來源 IP
    private String userAgent;          // 瀏覽器/客戶端資訊
    private String hashValue;          // 本筆紀錄的 SHA-256 hash
    private String previousHash;       // 前一筆紀錄的 hash（hash chain）
    private LocalDateTime createdAt;   // 操作時間 UTC（不可修改）
}
```

### 17.4 操作類型

| 操作類型 | 說明 |
|---------|------|
| PROCESS_START | 發起流程 |
| PROCESS_CANCEL | 撤銷流程 |
| PROCESS_COMPLETE | 流程結案 |
| TASK_APPROVE | 同意 |
| TASK_REJECT | 拒絕（終止） |
| TASK_RETURN | 退件 |
| TASK_RETURN_INITIATOR | 退回申請人 |
| TASK_DELEGATE | 轉發 |
| TASK_REASSIGN | 改派 |
| TASK_COUNTERSIGN | 加簽 |
| TASK_COMMENT | 批註 |
| TASK_CLAIM | 認領 |
| TASK_URGE | 催辦 |
| FORM_SUBMIT | 表單提交 |
| FORM_UPDATE | 表單修改 |
| BPMN_DEPLOY | 流程部署 |
| EXTERNAL_API_CALL | 外部 API 呼叫 |
| DATA_ACCESS | 資料存取（查看敏感資料） |
| CONFIG_CHANGE | 系統設定變更 |
| EXPORT_DATA | 資料匯出 |

### 17.5 稽核 Log API

```
# 查詢（需稽核員權限）
GET  /api/audit-logs?processInstanceId={id}     → 查詢特定流程的操作紀錄
GET  /api/audit-logs?operatorId={userId}         → 查詢特定人員的操作紀錄
GET  /api/audit-logs?operationType={type}        → 依操作類型查詢
GET  /api/audit-logs?startDate={}&endDate={}     → 依時間區間查詢
GET  /api/audit-logs/export?...                  → 匯出 CSV/Excel（匯出操作本身也記錄 log）

# 完整性驗證（需管理員權限）
GET  /api/audit-logs/integrity-check?startDate={}&endDate={}  → 驗證 hash chain 完整性
```

### 17.6 實作方式

各微服務透過 MQ 非同步寫入，Audit Log Service 消費訊息並寫入：

```java
// 各微服務端：發送稽核事件到 MQ
@Component
public class AuditEventPublisher {
    @Autowired
    private RabbitTemplate rabbitTemplate;

    public void publish(AuditEvent event) {
        rabbitTemplate.convertAndSend("audit.exchange", "audit.log", event);
    }
}

// Audit Log Service 端：消費並寫入
@RabbitListener(queues = "audit.log.queue")
public void handleAuditEvent(AuditEvent event) {
    // 1. 取得前一筆 hash
    // 2. 計算本筆 hash = SHA-256(前一筆hash + 本筆內容)
    // 3. append-only 寫入 DB
    // 4. 異常操作模式偵測
}
```

### 17.7 Hash Chain 防竄改

```
Record N:   hash = SHA-256(record_N_content + hash_of_record_N-1)
Record N+1: hash = SHA-256(record_N+1_content + hash_of_record_N)
```

定期（每日）自動執行完整性驗證，若 hash chain 斷裂則觸發告警。

### 17.8 保留策略

- 線上保留：3 年（ISO 27001 建議至少保留 3 年）
- 歸檔：超過 3 年的 log 自動歸檔至冷儲存（保持 hash chain 完整）
- 不可刪除：即使歸檔也不可刪除，僅稽核員可查詢歸檔資料
- 定期審查：每季產出稽核報告，供 ISO 27001 內部稽核使用

---

## 十八、建置優先順序

### Phase 1（3週）— 微服務基礎建設
- [ ] 微服務專案骨架建立（BPM Core / Form Service / Audit Log Service）
- [ ] API Gateway 設定（Nginx 路由 + JWT 驗證轉發）
- [ ] RabbitMQ 基礎設定（exchange / queue / binding）
- [ ] Redis 建置（外圍 API 回應快取基礎）
- [ ] **BPM Core**：Spring Boot + Flowable 環境建置（MSSQL + JPA）
- [ ] **BPM Core**：外圍權限/組織系統 REST Client 整合（OrgService / PermService wrapper）
- [ ] **BPM Core**：快取失效 webhook 端點（`/api/internal/cache-invalidate/*`）
- [ ] **BPM Core**：基本 Flowable REST API 封裝（`/api/` 統一前綴）
- [ ] **Audit Log Service**：append-only DB + hash chain + MQ 消費者
- [ ] **Audit Log Service**：基礎稽核 API（查詢 + 完整性驗證）
- [ ] **Audit Log Service**：前端稽核 Log 查詢頁面（基礎版，支援條件查詢）

### Phase 2（4週）— 核心待辦 + 基礎表單
- [ ] **Form Service**：微服務建置 + 獨立 DB（提前，Phase 2 流程上線需要表單）
- [ ] **Form Service**：動態表單 Schema 儲存/查詢 API
- [ ] **BPM Core**：待辦清單 API（三種來源合併）
- [ ] **BPM Core**：我發起的流程清單 API
- [ ] **BPM Core**：審批操作（同意/退件/拒絕/轉發）
- [ ] **BPM Core**：批註 API（新增/查詢）
- [ ] **BPM Core**：Email 通知（基礎模板，硬編碼）
- [ ] **BPM Core**：簡單審核流程上線（請假/採購，手動建立表單 Schema）
- [ ] **前端**：DynamicForm.vue（動態表單渲染器）
- [ ] **前端**：Vue3 待辦儀表板 + 我的申請頁面
- [ ] **前端**：ActionDialog（同意/退件/拒絕/轉發）
- [ ] **前端**：CommentPanel.vue（批註面板）
- [ ] **前端**：ApprovalTimeline.vue（審核時間軸，含批註顯示）

### Phase 3（4週）— 表單設計器 + 公文系統
- [ ] **Form Service**：Form Editor 表單設計器 API（CRUD + 發佈）
- [ ] **前端**：FormEditor.vue（拖拉式表單設計器：FieldPalette / FormCanvas / FieldConfig / FormPreview）
- [ ] **BPM Core**：公文表單設計 + 文號管理
- [ ] **BPM Core**：附件上傳
- [ ] **BPM Core**：通知模板管理（NotifyTemplate CRUD，取代 Phase 2 硬編碼模板）
- [ ] **BPM Core**：通知渠道配置 API（NotifyConfig，流程級別配置）
- [ ] **前端**：ActionDialog 擴充加簽功能 + CountersignDialog.vue
- [ ] **前端**：ExternalFormLink.vue（外部表單連結元件）

### Phase 4（3週）— 流程設計平台
- [ ] **前端**：bpmn-js Editor 整合
- [ ] **前端**：Properties Panel 擴充 — 審核對象類型選擇器
- [ ] **前端**：Properties Panel 擴充 — formKey 綁定選擇器（內建表單/外部連結）
- [ ] **前端**：Properties Panel 擴充 — 節點級 Webhook 設定 UI
- [ ] **BPM Core**：節點級 Webhook 後端 Listener + 非同步發送
- [ ] **BPM Core**：BPMN Lint 自動驗證（含 formKey 必填、外部流程 initiator 檢查）
- [ ] Git 自動 commit + CI/CD pipeline（含微服務獨立部署）

### Phase 5（3週）— 加簽與外部整合
- [ ] **BPM Core**：通用加簽機制（動態子任務）
- [ ] **BPM Core**：特定子流程加簽（Call Activity）
- [ ] **BPM Core**：外部系統管理 API（§9.1 CRUD + API Key 輪換 + IP 白名單）
- [ ] **BPM Core**：外部系統發起流程 API（含 effectiveInitiator 處理）
- [ ] **BPM Core**：外部系統查詢流程狀態 API（§9.4）
- [ ] **BPM Core**：節點 API 觸發（自動化審批）
- [ ] **BPM Core**：流程變數規格管理 API（§9.5 ProcessVariableSpec）
- [ ] **BPM Core**：外部表單連結機制（external: formKey 處理）
- [ ] **前端**：外部系統管理後台頁面
- [ ] **前端**：流程變數規格管理 UI

### Phase 6（持續）— 成熟化
- [ ] **BPM Core**：Teams / Line Works 通知渠道
- [ ] **BPM Core**：通用 Delegate Bean 元件庫
- [ ] **Audit Log Service**：稽核 Log 匯出（CSV/Excel）+ ISO 27001 合規報告
- [ ] **前端**：通知渠道配置管理 UI
- [ ] **前端**：流程 KPI 儀表板
- [ ] **前端**：AI 語意編輯側邊欄
- [ ] **前端**：行動裝置適配

---

## 十九、關鍵設計決策記錄

| 決策 | 選擇 | 理由 |
|------|------|------|
| BPM 引擎 | Flowable | Spring Boot 整合佳，輕量 |
| BPMN Editor | bpmn-js（自行封裝） | 最活躍社群，Vue3 相容，可客製 |
| 參考專案 | DragonFlow 後端參考，前端重建 | UI 框架不同（Ant Design vs 公司技術棧） |
| 組織/權限 | 呼叫外圍既有系統 | 微服務架構，不重複建置 |
| 非同步流程 | Message Event + RabbitMQ | 解耦，可重試，有 DLQ |
| AI 整合 | JSON IR + LLM（非直接 XML） | 減少 75% token，降低語法錯誤率 |
| BPMN 版控 | Git（bpmn-definitions/ 目錄） | 與代碼同一套工具，有 diff 可審查 |
| 多環境部署 | EL 佔位符 + 環境 yml | 一份 BPMN，多環境自動替換 |
| Form Editor | JSON Schema + 動態渲染 | 低代碼，業務人員可自行設計表單 |
| 加簽機制 | 子任務（通用）+ Call Activity（特定） | 兼顧靈活性與標準化 |
| 外部系統接入 | API Key + System ID 認證 | 獨立於用戶認證，可細粒度控制 |
| 節點 Webhook | TaskListener + 非同步發送 | 不阻塞流程，失敗可重試 |
| 拒絕操作 | complete + rejected 變數 + End Event | 與退件區分，明確終止語意 |
| **微服務拆分** | 3 服務：BPM Core / Form Service / Audit Log | Flowable 有獨立 DB schema；表單可複用；稽核需獨立存取控制 |
| **服務間通訊** | REST（同步）+ MQ（非同步） | 表單查詢需即時回應用 REST；稽核寫入不阻塞用 MQ |
| **資料庫拆分** | 每服務獨立 DB | 微服務資料自治，獨立擴展，稽核 DB 獨立存取控制 |
| **稽核合規** | ISO 27001 + hash chain + append-only | 防竄改、可驗證完整性、滿足 3 年保留要求 |
| **API Gateway** | Nginx | 統一入口、JWT 驗證、路由轉發、限流 |
| **通知不獨立拆服務** | 留在 BPM Core 內 | 觸發點在 Flowable Listener，拆出去只多一層 MQ 轉發，收益不大 |
