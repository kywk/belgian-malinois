# 企業版 RBAC 權限系統 — 完整工項與工時估算

> 產出日期：2026-06-08
> 最後更新：2026-09-29（新增第十三節：BPM 平台已定案的介面約定與缺口）
> 含對外 API 服務、Java SDK、屬性型角色綁定 (ABAC hybrid)
>
> **狀態：未開工。**BPM 平台目前以 `MockOrgController`／`MockPermController` 代替本系統。

---

## 架構概覽

```
┌─────────────────────────────────────────────────────┐
│                     管理介面 (Vue 3)                   │
└──────────────────────┬──────────────────────────────┘
                       │
┌──────────────────────▼──────────────────────────────┐
│              權限中心 (Spring Boot)                    │
│  ┌──────────┐ ┌──────────┐ ┌───────────┐           │
│  │ 認證模組  │ │ 授權模組  │ │ 管理模組   │           │
│  └──────────┘ └──────────┘ └───────────┘           │
│  ┌──────────────────────────────────────┐           │
│  │       對外 API Service Layer          │           │
│  │  (供其他系統呼叫查詢權限/角色/組織)     │           │
│  └──────────────────────────────────────┘           │
└──────────────────────┬──────────────────────────────┘
                       │
        ┌──────────────┼──────────────┐
        ▼              ▼              ▼
  ┌──────────┐  ┌──────────┐  ┌──────────┐
  │ 系統 A   │  │ 系統 B   │  │ 系統 C   │
  │ (含 SDK) │  │ (含 SDK) │  │ (REST)   │
  └──────────┘  └──────────┘  └──────────┘
```

**角色綁定方式：**
- 直接綁定：Role ↔ UserId（傳統 RBAC）
- 屬性綁定：Role ↔ Attribute Rule（如 `orgCode=FIN*`、`jobCode=MGR`、`contractTag=VIP`）
- 運行時計算：使用者屬性符合規則 → 自動獲得該角色的所有權限

---

## 一、核心資料模型

| # | 工項 | 說明 | 估時 |
|---|------|------|------|
| 1 | User Entity | id, account, name, email, phone, status, lastLoginAt | 0.5d |
| 2 | UserAttribute Entity | userId, attrKey, attrValue（多值，如 orgCode, jobCode, contractTag） | 1d |
| 3 | Role Entity | id, roleCode, roleName, description, roleType(static/dynamic), enabled | 0.5d |
| 4 | Permission Entity | id, permCode, permName, permGroup, type(menu/button/api), resourceUrl | 0.5d |
| 5 | UserRole 關聯 | userId, roleId, grantedBy, grantedAt, expiresAt（直接綁定） | 0.5d |
| 6 | RolePermission 關聯 | roleId, permissionId | 0.5d |
| 7 | RoleAttributeRule Entity | roleId, attrKey, operator(eq/neq/in/startsWith/regex), attrValue, logic(AND/OR) | 1.5d |
| 8 | Department Entity | id, parentId, deptCode, deptName, sortOrder | 0.5d |
| 9 | UserDepartment 關聯 | userId, deptId, isPrimary | 0.5d |
| 10 | Menu Entity | id, parentId, menuName, path, icon, permCode, sortOrder, menuType | 0.5d |

**小計：6.5d**

---

## 二、認證模組

| # | 工項 | 說明 | 估時 |
|---|------|------|------|
| 11 | 登入 API | 帳密驗證 + JWT 簽發 + 登入失敗計數鎖定 | 1.5d |
| 12 | Token 管理 | Access Token + Refresh Token、Redis session、多裝置管理 | 2d |
| 13 | 登出 API | Token 撤銷（Redis 黑名單） | 0.5d |
| 14 | 密碼安全 | BCrypt、密碼強度規則、密碼歷史（禁止重複）、定期過期 | 1.5d |
| 15 | 密碼重設 | 忘記密碼 Email 連結 + 重設 API | 1.5d |
| 16 | SSO 整合 | OAuth2 / OIDC 對接（Authorization Code Flow） | 3d |
| 17 | 多因子驗證 (MFA) | TOTP (Google Authenticator) 綁定/驗證/重置 | 3d |
| 18 | Service Token 簽發 | 系統間呼叫用的 long-lived service token 管理 | 1.5d |

**小計：15d**

---

## 三、授權模組（含屬性型角色計算）

| # | 工項 | 說明 | 估時 |
|---|------|------|------|
| 19 | 權限攔截器 / Filter | 請求驗 Token + 查權限（支援 API path matching） | 2d |
| 20 | 註解式權限控制（本系統用） | `@RequirePermission("user:create")` AOP | 1d |
| 21 | 屬性型角色計算引擎 | 依 UserAttribute 匹配 RoleAttributeRule，動態計算有效角色 | 3d |
| 22 | 有效權限合併 | 靜態角色權限 ∪ 動態角色權限 → 使用者最終權限集 | 1d |
| 23 | 權限快取（Redis） | 使用者有效權限集快取、角色/屬性變動時精確失效 | 2d |
| 24 | 資料範圍控制 | 全部/本部門/本部門及下級/僅本人/自訂部門 | 2d |
| 25 | 選單權限過濾 | 依最終權限集回傳可見選單樹 | 1d |
| 26 | API 白名單管理 | 無需認證的 URL pattern 管理 | 0.5d |

**小計：12.5d**

---

## 四、使用者管理 API + UI

| # | 工項 | 說明 | 估時 |
|---|------|------|------|
| 27 | 使用者 CRUD API | 建立/查詢/更新/停用，分頁 + 多條件搜尋 | 2d |
| 28 | 使用者屬性管理 API | CRUD UserAttribute（支援批次更新） | 1.5d |
| 29 | 使用者角色指派 API | 指派/移除靜態角色（含有效期設定） | 1d |
| 30 | 使用者有效權限查詢 API | 合併靜態+動態角色後的完整權限清單 | 1d |
| 31 | 使用者管理頁面 | 列表（搜尋/篩選/分頁）+ 新增/編輯表單 | 3d |
| 32 | 使用者屬性管理 UI | 屬性 key-value 編輯器（可動態新增屬性列） | 1.5d |
| 33 | 使用者角色指派 UI | 角色多選 + 有效期設定 + 顯示動態角色（唯讀標註） | 2d |
| 34 | 使用者匯入/匯出 | Excel/CSV 批次匯入（含屬性）、匯出清單 | 2d |
| 35 | 帳號啟停用 + 強制登出 | 單筆/批次操作 | 1d |

**小計：15d**

---

## 五、角色管理 API + UI

| # | 工項 | 說明 | 估時 |
|---|------|------|------|
| 36 | 角色 CRUD API | 建立/查詢/更新/刪除（區分 static/dynamic 類型） | 1.5d |
| 37 | 角色權限指派 API | 批次設定角色擁有的權限 | 1d |
| 38 | 角色屬性規則管理 API | CRUD RoleAttributeRule（AND/OR 組合條件） | 2d |
| 39 | 角色成員預覽 API | 靜態成員 + 符合屬性規則的動態成員預覽 | 1.5d |
| 40 | 角色管理頁面 | 列表 + 新增/編輯表單（含 roleType 切換） | 2d |
| 41 | 角色權限指派 UI | 權限樹勾選（依 permGroup 分組展示） | 2d |
| 42 | 角色屬性規則 UI | 規則編輯器（attrKey 下拉 + operator 選擇 + value 輸入 + AND/OR 邏輯） | 3d |
| 43 | 角色成員檢視 UI | 靜態/動態成員分 Tab 顯示 | 1d |
| 44 | 角色複製 | 複製權限配置 + 屬性規則 | 0.5d |
| 45 | 角色繼承 | 父子角色階層，子角色繼承父角色所有權限 | 2d |

**小計：16.5d**

---

## 六、權限定義與選單管理 API + UI

| # | 工項 | 說明 | 估時 |
|---|------|------|------|
| 46 | 權限 CRUD API | 建立/查詢/更新/刪除 + 分組管理 | 1d |
| 47 | 權限管理頁面 | 分組樹 + 表格展示 + 新增/編輯 | 2d |
| 48 | 選單 CRUD API | 樹狀結構 CRUD + 排序 | 1.5d |
| 49 | 選單管理頁面 | 樹狀表格 + 新增/編輯（含圖示選擇、權限綁定） | 2d |
| 50 | 動態選單渲染 | 前端依 API 回傳動態生成 sidebar + 路由 | 2d |

**小計：8.5d**

---

## 七、組織部門管理 API + UI

| # | 工項 | 說明 | 估時 |
|---|------|------|------|
| 51 | 部門樹 CRUD API | 建立/查詢/更新/刪除/排序 | 2d |
| 52 | 部門成員管理 API | 指派/移除/查詢部門成員 | 1d |
| 53 | 部門管理頁面 | 左側部門樹 + 右側成員列表 | 2.5d |
| 54 | 部門人員調動 + 歷史 | 批次調動、記錄異動歷史 | 1.5d |

**小計：7d**

---

## 八、操作日誌與安全

| # | 工項 | 說明 | 估時 |
|---|------|------|------|
| 55 | 操作日誌記錄（AOP） | 攔截權限變更/角色指派/屬性修改等關鍵操作 | 2d |
| 56 | 操作日誌查詢 API + UI | 多條件搜尋 + 分頁 + 匯出 | 2d |
| 57 | 登入日誌 | 記錄登入/登出/失敗/IP/UA/地理位置 | 1d |
| 58 | 線上使用者管理 | 查看目前登入使用者 + 強制踢出 | 1.5d |
| 59 | 登入失敗鎖定 | N 次失敗鎖定帳號/IP，自動/手動解鎖 | 1d |
| 60 | IP 黑白名單 | 系統級 + 帳號級 IP 限制 | 1d |

**小計：8.5d**

---

## 九、進階企業功能

| # | 工項 | 說明 | 估時 |
|---|------|------|------|
| 61 | 時效性授權 | 角色指派設有效期、排程掃描到期自動收回 + 到期前通知 | 2d |
| 62 | 職責分離 (SoD) | 互斥角色規則定義 + 指派時衝突檢查 + 管理 UI | 2.5d |
| 63 | 委託/代理 | 使用者委託他人暫代權限（含時間範圍）、代理人權限合併 | 2.5d |
| 64 | 權限申請與審批 | 使用者自助申請角色 → 主管審批 → 自動生效 + 前端 UI | 3.5d |
| 65 | 權限定期覆核 | 排程產生覆核任務、主管確認/收回、覆核報表 | 2.5d |
| 66 | 屬性同步 Webhook | 外部 HR 系統屬性異動時推送 → 更新 UserAttribute → 觸發角色重算 | 2d |

**小計：15d**

---

## 十、對外 API 服務層（供其他系統呼叫）

| # | 工項 | 說明 | 估時 |
|---|------|------|------|
| 67 | API 認證機制 | Service Token 驗證（X-Service-Token header）、呼叫端註冊管理 | 2d |
| 68 | 權限查詢 API | `GET /api/v1/permissions/check?userId={}&permCode={}` → boolean | 1d |
| 69 | 批次權限查詢 API | `POST /api/v1/permissions/batch-check` → { permCode: boolean } map | 1d |
| 70 | 使用者權限清單 API | `GET /api/v1/users/{userId}/permissions` → 完整權限碼列表 | 0.5d |
| 71 | 使用者角色清單 API | `GET /api/v1/users/{userId}/roles` → 含靜態+動態角色 | 0.5d |
| 72 | 使用者屬性查詢 API | `GET /api/v1/users/{userId}/attributes` | 0.5d |
| 73 | 依權限查使用者 API | `GET /api/v1/permissions/{permCode}/users` → 擁有此權限的所有人 | 1d |
| 74 | 依角色查使用者 API | `GET /api/v1/roles/{roleCode}/users` → 含靜態+動態成員 | 0.5d |
| 75 | 組織查詢 API | `GET /api/v1/users/{userId}/department`、`/manager`、`/subordinates` | 1.5d |
| 76 | Token 驗證 API | `POST /api/v1/token/validate` → 驗證 JWT 有效性 + 回傳使用者資訊 | 1d |
| 77 | 快取失效 Webhook API | `POST /api/v1/cache/invalidate` → 外部系統通知權限異動 | 0.5d |
| 78 | API 限流 (Rate Limiting) | 依呼叫端 Token 限制 QPS、超限回 429 | 1.5d |
| 79 | API 版本管理 | `/api/v1/` 路徑版本、向後相容策略 | 0.5d |
| 80 | OpenAPI 文件產生 | Swagger UI + OpenAPI 3.0 spec 自動產生 | 1d |

**小計：12.5d**

---

## 十一、Java SDK 開發

| # | 工項 | 說明 | 估時 |
|---|------|------|------|
| 81 | SDK 專案骨架 | Maven artifact、spring-boot-starter 自動配置 | 1d |
| 82 | SDK 配置 | `application.yml` 配置項（server url, service token, cache ttl） | 0.5d |
| 83 | SDK REST Client | 封裝對權限中心 API 的 HTTP 呼叫（含重試/超時/錯誤處理） | 2d |
| 84 | SDK 本地快取 | Caffeine 本地快取 + TTL，減少遠端呼叫 | 1.5d |
| 85 | `@RequirePermission` 註解 | 方法/類級別、支援 AND/OR 邏輯 | 2d |
| 86 | `@RequireRole` 註解 | 角色級別權限檢查 | 1d |
| 87 | `@RequireAttribute` 註解 | 檢查當前使用者是否具備某屬性值 | 1d |
| 88 | AOP 切面實作 | 攔截註解、從 SecurityContext 取使用者、呼叫權限檢查 | 2d |
| 89 | SecurityContext 整合 | 提供 `AuthContext.getCurrentUser()`、`AuthContext.hasPermission()` | 1.5d |
| 90 | Token 解析 Filter | SDK 內建 Filter，自動解析 JWT / 呼叫 Token 驗證 API | 1.5d |
| 91 | Spring Security 整合（可選） | 適配 Spring Security 的 AuthenticationProvider | 2d |
| 92 | SDK 使用文件 | README + Javadoc + 快速開始指南 + 範例專案 | 2d |
| 93 | SDK 單元測試 | Mock server 測試、註解測試、快取測試 | 2d |
| 94 | SDK 發佈 | Maven Central / 私有 Nexus 發佈 + 版本管理 | 1d |

**小計：18d**

---

## 十二、屬性同步與跨系統整合

| # | 工項 | 說明 | 估時 |
|---|------|------|------|
| 95 | 屬性定義管理 | 定義可用的 attrKey（orgCode, jobCode, contractTag...）+ 資料型別 + 來源標記 | 1.5d |
| 96 | 屬性定義管理 UI | 屬性 key 的 CRUD + 設定同步來源 | 1.5d |
| 97 | HR 系統屬性同步（Pull） | 排程從 HR 系統拉取最新組織/職務資料 → 更新 UserAttribute | 2.5d |
| 98 | 屬性異動事件（Push） | 接收外部系統 Webhook → 更新屬性 → 觸發角色重算 → 失效快取 | 2d |
| 99 | 屬性異動歷史 | 記錄每次屬性變更（who/when/old/new），供稽核 | 1d |
| 100 | 角色重算引擎 | 屬性變動後即時重算受影響使用者的動態角色 + 精確快取失效 | 2d |

**小計：10.5d**

## 十三、與 BPM 平台的介面約定（2026-09-29 現況）

BPM 平台（Greyhound）是本系統的第一個呼叫端。它在 `feature/tech-debt-remediation`
分支完成了認證整合（R-01），因此下列介面**已由 BPM 端的程式碼定案**。本系統實作時
要嘛照這份約定提供，要嘛同步修改 BPM 端的 client —— 兩邊不一致時，BPM 會在啟動後
第一次查組織／權限時失敗。

### 13.1 認證：BPM 只驗證 JWT，不簽發

- BPM **只驗證** JWT（`bpm.security.jwt.issuer-uri`），不簽發、不處理 OIDC 流程 ——
  那由本系統或企業 IdP 負責（對應 #11、#12、#16）。
- 身分取自 `sub` claim。
- 有 `roles` claim 時**優先採用**（例如 `["admin"]` → `ROLE_ADMIN`）；沒有時才向本系統
  查詢使用者的權限碼。
- Server 之間的呼叫（例如下方的快取失效）走**信任閘道**：`X-Gateway-Secret` + `X-User-Id`，
  而不是 #18 的 service token。兩者要擇一統一，或讓閘道負責轉換。

### 13.2 權限碼格式

- 形如 `domain:resource:action`，**含冒號**（例如 `hr:leave:approve`、`audit:log:read`）。
  BPM 的 Redis key 已按此分 namespace；請不要改成其他分隔符。
- 通配 `*` 代表全部權限。BPM 會把它轉成 `ROLE_ADMIN`，但**不會**自動取得具名權限碼
  （例如附件的稽核旁路只認 `audit:log:read`）。
- BPM 目前使用的權限碼：
  - `audit:log:read` —— 查稽核、唯讀調閱附件／流程變數／表單資料。
    **不接受 `ROLE_ADMIN`**（2026-09-29 決策）：稽核紀錄的 `detail` 會帶整包
    流程變數，也就是全公司薪資與簽核意見；`ProcessAccessGuard` 早已拒絕
    `ROLE_ADMIN` 讀案件流程變數，若 URL 層放行就等於開側門。
  - `bpm:form:design` —— 建立／改 schemaJson／發布／封存／刪除表單定義
    （`GET` 維持登入即可）。**接受 `ROLE_ADMIN`**：表單 schema 是設定資產而非
    個人資料，且通配持有者本來就是超級使用者，剝奪這項權換不到安全收益
    （2026-09-29 決策）。`bpm` 是本系統自己的命名空間，不是可依部門指派的
    業務網域。
  - 另有 BPMN 運算式中流程自訂的簽核權限碼（`hr:leave:approve` 等）。

  ⚠️ 「哪些規則接受 `ROLE_ADMIN`」是**每一條規則自己的政策決定**，不是全域設定 ——
  見 `SecurityConfig` 類別註解的對照表。理由是「管理員可不可以看全部資料」與
  「管理員可不可以改系統設定」是兩個問題，合併成「管理員萬能」會讓下一位
  新增端點的人繼承預設值而不去重新回答。

### 13.3 BPM 實際呼叫的查詢 API

BPM 的 `OrgRestClient`／`PermRestClient` 目前呼叫下列路徑（**沒有 `/v1` 前綴**，
回應欄位名稱如右欄）。與第十節規劃的路徑不同，實作時請擇一對齊：

| BPM 呼叫 | 回應 | 對應本文件 |
|---|---|---|
| `GET /api/users/{userId}` | 使用者資料 | #75 |
| `GET /api/users/{userId}/manager` | `{ managerId }` | #75 |
| `GET /api/users/{userId}/manager-chain?levels=N` | 主管 id 陣列（由近到遠） | **缺**（見 #101） |
| `GET /api/users/{userId}/department` | `{ deptId }` | #75 |
| `GET /api/users/{userId}/substitute` | `{ substituteId }`，無代理人為 null | **缺**（見 #102） |
| `GET /api/departments/{deptId}/members` | 使用者 id 陣列 | **缺**（見 #103） |
| `GET /api/permissions/{permCode}/users[?deptId=]` | 使用者 id 陣列 | #73（規劃為 `/api/v1/...`） |
| `GET /api/users/{userId}/permissions` | 權限碼陣列 | #70 |
| `GET /api/users/{userId}/has-permission?code=` | `{ hasPermission }` | #68（規劃為 `permissions/check?userId=&permCode=`） |

語意上的要求：
- **代理人**決定 BPM 的「是否在職」：有代理人 = 不在。BPM 據此改派給代理人，而且
  只解一層代理（代理鏈可能成環）。
- 查無此人時必須回**錯誤**（4xx），不可回預設值 —— mock 曾對未知 userId 一律回
  `mgr001`，造成偽造身分的案件「看起來正常」地派給真實主管。

### 13.4 快取失效：由本系統**推給** BPM

方向與 #77 相反：#77 是「外部系統通知本系統」，而 BPM 需要的是本系統在組織／權限異動時
**主動通知 BPM**（BPM 快取 TTL 1～30 分鐘）。

- `POST {bpm}/api/internal/cache-invalidate/org`、`/perm`，需 `ROLE_GATEWAY`（經信任閘道）
- body：`{ "type": "MANAGER|SUBSTITUTE|DEPARTMENT|ALL", "userIds": [], "permCodes": [], "deptIds": [] }`

### 13.5 因此新增的工項

| # | 工項 | 說明 | 估時 |
|---|------|------|------|
| 101 | 主管鏈查詢 API | 依層級回傳主管 id 陣列，供 BPM 多層簽核路由 | 0.5d |
| 102 | 代理人設定與查詢 API | 代理人 CRUD（含期間）＋查詢；BPM 以此判斷是否在職 | 1d |
| 103 | 部門成員查詢 API | 回傳部門的使用者 id 陣列 | 0.5d |
| 104 | 異動主動通知下游 | 組織／權限異動時呼叫訂閱系統的快取失效端點（先支援 BPM 的格式） | 1d |

**小計：3d**

---

## 總結

| 模組 | 工項數 | 估計人天 |
|------|--------|---------|
| 核心資料模型 | 10 | 6.5d |
| 認證模組 | 8 | 15d |
| 授權模組（含屬性角色計算） | 8 | 12.5d |
| 使用者管理 | 9 | 15d |
| 角色管理 | 10 | 16.5d |
| 權限與選單管理 | 5 | 8.5d |
| 組織部門管理 | 4 | 7d |
| 操作日誌與安全 | 6 | 8.5d |
| 進階企業功能 | 6 | 15d |
| 對外 API 服務層 | 14 | 12.5d |
| Java SDK | 14 | 18d |
| 屬性同步與跨系統整合 | 6 | 10.5d |
| 與 BPM 平台的介面缺口（2026-09-29 新增） | 4 | 3d |
| **合計** | **104** | **~149d** |

---

## SDK 使用範例

```java
// 1. 引入依賴
// <dependency>
//   <groupId>com.company</groupId>
//   <artifactId>auth-sdk-spring-boot-starter</artifactId>
//   <version>1.0.0</version>
// </dependency>

// 2. application.yml 配置
// auth:
//   server-url: https://auth.company.com
//   service-token: ${AUTH_SERVICE_TOKEN}
//   cache-ttl: 300s

// 3. 註解使用
@RestController
public class OrderController {

    // 檢查單一權限
    @RequirePermission("order:create")
    @PostMapping("/orders")
    public Order createOrder(@RequestBody OrderRequest req) { ... }

    // 檢查多個權限（AND）
    @RequirePermission(value = {"order:approve", "finance:view"}, logic = Logic.AND)
    @PutMapping("/orders/{id}/approve")
    public Order approveOrder(@PathVariable String id) { ... }

    // 檢查角色
    @RequireRole("finance_manager")
    @GetMapping("/reports/financial")
    public Report getFinancialReport() { ... }

    // 檢查使用者屬性
    @RequireAttribute(key = "orgCode", value = "FIN*", operator = Operator.STARTS_WITH)
    @GetMapping("/finance/internal")
    public Data getInternalData() { ... }

    // 程式化呼叫
    @Autowired
    private AuthContext authContext;

    @GetMapping("/orders")
    public List<Order> listOrders() {
        String userId = authContext.getCurrentUserId();
        if (authContext.hasPermission("order:view_all")) {
            return orderRepo.findAll();
        }
        return orderRepo.findByCreatedBy(userId);
    }
}
```

---

## 屬性型角色綁定範例

```
角色：財務部審核者 (finance_reviewer)
  └─ 綁定規則（AND）：
       ├─ orgCode STARTS_WITH "FIN"
       └─ jobCode IN ["MGR", "SR_STAFF"]

角色：VIP 客戶經理 (vip_account_manager)
  └─ 綁定規則（AND）：
       ├─ contractTag EQUALS "VIP"
       └─ jobCode EQUALS "AM"

角色：全公司主管 (all_managers)
  └─ 綁定規則：
       └─ jobCode STARTS_WITH "MGR"
```

使用者屬性異動（如 HR 系統調動部門）→ 自動觸發角色重算 → 權限即時生效/收回，無需人工操作。
