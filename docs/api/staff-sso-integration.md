# 內勤 SSO、組織與 AuthZ 整合

本文件記錄 v1 契約的內勤執行路徑。BPM 後台只接受 STAFF；外勤仍由 agent BFF 呼叫外部 API。本次沒有實作 external v1 案件 API，也沒有修改 agent、Staff、SAML、AuthZ 的服務程式。

## 啟用方式

後端啟用 `prod,staff` profiles，設定見 [application-staff.yml](../../bpm-core/src/main/resources/application-staff.yml)。`staff` profile 清除開發 JWT secret；直接設定 `bpm.staff.enabled=true` 時，也必須自行清空 `bpm.security.jwt.dev-secret`。設定不完整、正式服務不是 HTTPS、正式服務認證空白都會拒絕啟動。

前端使用 `VITE_STAFF_SSO=true npm run build`。此旗標與後端需一起部署；未啟用時維持既有開發登入。正式模式不顯示測試帳號或貼 token 入口，JWT 僅保存在目前頁籤的 sessionStorage；JWT roles 不用於登入或權限降級。

| 設定 | 正式環境需提供的值 |
| --- | --- |
| `STAFF_SSO_ISSUER` | SAML 簽發 JWT 的精確 issuer；不要求該 issuer 提供 OIDC discovery |
| `STAFF_SSO_AUDIENCE` | BPM 可接受的 audience；需與 SAML 實際簽發值確認 |
| `STAFF_SSO_KEY_ID` | 與 issuer / audience 配套的受控 kid |
| `STAFF_SSO_PUBLIC_KEY_LOCATION` | 受控 X.509 PEM 公鑰，例如 `file:/run/secrets/staff-public.pem`；不放私鑰 |
| `STAFF_SAML_URL` | SAML 服務根網址，不含尾端斜線 |
| `STAFF_SSO_CALLBACK_URL` | BPM 同源根網址，例如 `https://bpm.example/` |
| `STAFF_IDENTITY_URL` | 下節身分橋接服務的基底網址；目前現有服務尚未提供此 HTTP 契約 |
| `STAFF_IDENTITY_AUTHORIZATION` | BPM → 身分橋接服務的完整 Authorization header 值 |
| `STAFF_AUTHZ_URL` | AuthZ 服務根網址 |
| `STAFF_AUTHZ_AUTHORIZATION` | BPM → AuthZ 的完整 Authorization header 值；與網路閘道／服務認證共同確認 |
| `bpm.staff.approval-permissions` | `processDefinitionKey: canonicalApprovalPermission` 對照；未設定的流程不允許任務異動 |
| `GATEWAY_AUTH_ENABLED`、`GATEWAY_SHARED_SECRET`、`GATEWAY_TRUSTED_PROXIES` | 沿用內部快取失效及 CI 機器呼叫設定 |
| `STAFF_CI_GATEWAY_USER_ID`、`STAFF_CI_AUTHZ_SUBJECT_ID` | CI 受信任閘道身分與 AuthZ 機器 subject；不得取自任意使用者 JWT |

`bpm.staff.allow-human-deploy` 預設 false。若依 ADR-002 啟用 break-glass，仍需 console 與獨立 deploy 權限；config manage 不包含 deploy。CI 沿用 `POST /api/deployments`，帶受信任 gateway headers、不帶 Bearer；精確匹配設定的機器 ID，另查 AuthZ deploy 權限。Bearer 與 gateway 同時出現時，永遠走內勤驗證，不轉成 CI。`/api/internal/**` 保留機器路徑；不接受 STAFF JWT roles 偽造 GATEWAY。

## 登入與 session

1. 前端讀取 `GET /api/auth/staff/config`，取得 `loginUrl`、`callbackUrl`；僅接受同源 callback。
2. 前端在 sessionStorage 保存 32 bytes 隨機 state 與建立時間，把 `bpm_sso_state` 放入 returnUrl，導至 SAML `/saml/v1/get-jwt`。
3. SAML 以 60 秒、一次性 `code` 導回。BPM 前端先清除網址中的 code / token / state，再比對頁籤 state（最長 5 分鐘），不接受 legacy URL token。
4. `POST /api/auth/staff/token` JSON `{"code":"<one-use-code>"}` 由後端以 form-urlencoded 向 SAML `/saml/v1/token` 交換。只接受 `code="0000"`、`data.token`，並先驗證 RS256 JWT，再回 `{"token":"..."}`。不轉送 SAP ticket、sso 或其他額外欄位。
5. 前端帶 Bearer 呼叫 `GET /api/me/session`。只有權威 STAFF ACTIVE 身分與 console grant 同時成立才顯示工作台；回傳 v1 userId / displayName / userType / deptId / permissions / expiresAt / authorizationCheckedAt。

三個回應均 `Cache-Control: no-store`。交換失敗不自動重送一次性 code；重新登入取得新 code。SAML 必須設定 BPM 的 returnUrl allowlist 與 `HANDOFF_CODE_ORIGINS`，否則可能仍走舊 URL token 流程，BPM 會拒絕。登出目前清除本頁籤 BPM token；不宣稱已撤銷 IdP session 或所有已簽發 JWT。

## 身分橋接的消費端契約

**這是本次新增的 BPM adapter HTTP 契約，不是宣稱現有 Staff API 已有這些路由。** v1 原先只固定 `StaffIdentitySnapshot` port。部署前須由具權威來源的橋接服務實作以下兩個讀取操作；不能拿 ODS 查到一列就補 `status=ACTIVE`。

- `POST {STAFF_IDENTITY_URL}/resolve`：`{"issuer":"https://issuer.example","subject":"sso-subject"}`，以組合鍵映射正式 USERID。
- `POST {STAFF_IDENTITY_URL}/users/resolve`：`{"userId":"W9000001"}`，驗證指派目標；回應 userId 必須精確等於請求值。
- 回應 `{"result":"FOUND","snapshot":{...}}`，snapshot 欄位遵循 [v1 身分契約](../plan/contracts/staff-agent-v1/identity-authz.md)；`NOT_FOUND` 無 snapshot，`UNAVAILABLE` 表示來源不可用。
- snapshot 需有 userId、displayName、userType、status、deptId、orgObjectIds、sourceRevision、observedAt、validUntil。時間為 UTC；觀測不得晚於現在或超過 60 秒；validUntil 必須未過期且晚於 observedAt。
- userType 非 STAFF／查無映射 → `403 BPM_STAFF_REQUIRED`；INACTIVE → `403 BPM_ACCOUNT_INACTIVE`；不明狀態、過期、錯誤格式、來源不可用 → `503 BPM_IDENTITY_UNAVAILABLE`。
- `deptId` 為正式 ParentOrgCode 的組織 object ID；`orgObjectIds` 只含經確認的 Z05（fallback Z06）／Z07／Z09 授權層級。BPM 不相信 JWT office_code，也不自行擴大為所有祖先節點。

橋接成功回應範例（合成資料；實際時間必須更新）：

```json
{
  "result": "FOUND",
  "snapshot": {
    "userId": "W9000001",
    "displayName": "合成測試內勤",
    "userType": "STAFF",
    "status": "ACTIVE",
    "deptId": "50310001",
    "orgObjectIds": ["50310001"],
    "sourceRevision": "fixture-r1",
    "observedAt": "2026-11-05T12:00:00Z",
    "validUntil": "2026-11-05T12:01:00Z"
  }
}
```

本實作沒有身分／grant 快取：每個請求重新查詢，舊 Redis mock 或舊五分鐘 grant 快取不參與正式判斷。因此人員異動不需等快取失效 webhook。正式上線前需量測來源容量；未來若加快取仍受 v1 最長 60 秒、寫入強制新查與撤銷失效規則限制。

## 授權與待辦、簽核

AuthZ 使用既有 `POST /api/v1/sdk/permissions/check`，固定 `system_code=bpm`、`subject_ids=[staff-<lowercase USERID>, org-<object ID>...]`、`mode=ALL`、`subject_attributes={}`。每個候選 permission 個別查詢，避免把一個 ALL 結果誤當完整權限清單。不需要假設管理端 profile API 已授予 BPM 管理角色。

- 有效回應且 allowed=false → 403；非字串 `0000`、缺少 boolean allowed、非 2xx、逾時／斷線 → 503，不回退 JWT roles、mock 或舊 cache。
- 所有內勤 API 先檢查 console。待辦讀取需要 task read；任務寫入需要 task act；表單寫入、管理、稽核、部署分別使用獨立 grant。
- 待辦與持有人檢查共用正式 dept / org / grant 群組，保留原始大小寫 USERID 給 Flowable。正式模式不查舊 mock 群組。
- 任務異動先執行既有 holder guard，再查流程權限對照，並在寫入前重新查 task act 與該流程 approval permission。非持有人仍 404；grant 撤銷回 403；無 mapping 回 `503 BPM_INTEGRATION_NOT_CONFIGURED`，未改動任務。
- 加簽採相同核決檢查；子任務循父任務取得流程。改派、轉發與加簽對象都要通過權威 STAFF ACTIVE 查核。
- 舊 `/api/me/permissions` 保留 DTO，回相容 alias。admin 布林只表示 config manage，不產生後端 ROLE_ADMIN，也不使表單、稽核或簽核自動獲權。

## 仍需正式來源／設定的缺口

1. **身分橋接服務尚待提供**：issuer/sub → canonical USERID、內勤類型、在職狀態與組織層級證據。現有 ODS model 沒有在職狀態，這是服務能力缺口，不只是補 URL。
2. 正式 SAML issuer / audience / kid / 公鑰及輪替程序、BPM callback allowlist / handoff origins。此版固定一個受控 kid+key；多把公鑰輪替重疊窗口尚未實作。
3. AuthZ 網路、服務認證、bpm permission catalog、個人／組織實際 grants、正式流程 approval mapping。沒有替真實人員建測試 reviewer grant。
4. 主管鏈、代理人、部門成員、搜尋及依權限反查人員尚無正式橋接契約，正式模式明確回 `BPM_ROUTING_UNAVAILABLE`；依賴這些 EL 的既有流程需先補來源或改成已確認的明確受理人／群組。禁止切回 mock 當作上線完成。
5. IdP logout / token 即時撤銷事件尚無整合來源。ACTIVE 與 grant 每請求查核可阻止停用／撤權，但不等於單一 token 撤銷。
6. CI 的 gateway 身分、AuthZ service subject、deploy grant 與來源網路允許清單需由環境提供。外勤 agent BFF 與 external v1 案件 API 留待下一階段。

測試採本機 HTTP 合約 provider 與真實 MSSQL / Redis / RabbitMQ / Flowable；通過代表 BPM 消費端及瀏覽器流程可運作，不代表已連正式 IdP、組織或 AuthZ 環境。

## 第 5 項撤權與已派任務

內勤每個請求重新解析 issuer／sub，採用當次組織與 grant。來源的有效截止為 observedAt 加 60 秒與 validUntil 兩者中的較早值，到期或格式不合法回 503。建立當次認證及執行簽核守衛時均檢查 snapshot 的有效期限。簽核與加簽在異動前再查身分、console、task act 與該流程核決權。中途調職或資格失效會拒絕本次異動。選用的政策是凍結原任務，保留 assignment 與歷程，讓操作人在資格恢復後以新請求處理。

| 異動 | 真正受到影響的資料 |
|---|---|
| 調職 | directory 的 deptId／orgObjectIds 改變，下一請求以新 org subjects 查 AuthZ。候選群組來自本次 grant、alias 及組織。已指派的 assignee 仍須持有當次核決 grant。 |
| 撤權／停權 | AuthZ SDK 對個人與組織主體聯集查直接權限、有效期內的直接角色、群組角色與 ABAC。合法 deny 回 403，錯誤／中斷回 503。INACTIVE 的有效 JWT 回 403。 |
| Redis 與失效事件 | 正式模式的 OrgService／BpmPermissionService 略過 Redis 讀寫。舊 org:manager、manager-chain、dept、dept-members、substitute，以及個人權限、全域與部門持有人查詢仍屬舊模式。閘道的 org／perm invalidation 只處理那些 key。BPM 正式判斷不依賴 AuthZ outbox 通知抵達。 |
| 指名代理 | `delegate` 在交付前、受派人在讀取待辦／任務表單與異動時，按 Flowable 保存的 owner、delegate 查有效期間。到期從代理人待辦移除，直接操作回 403。來源無法確認則 503。原 owner 具備當次權限時可 resolve 收回。已完成歷史案件沿既有參與者／稽核政策讀取。 |

新增身分橋接消費端 `POST {STAFF_IDENTITY_URL}/delegations/check`，請求為 `{owner, delegate}`。回應須回顯兩個 ID、status、observedAt、validUntil，ACTIVE 另需 startsAt、endsAt。使用 UTC、起點含、終點不含。具時效的 INACTIVE 回 403；逾時、歧義或來源故障回 `503 BPM_ROUTING_UNAVAILABLE`。這個查核只驗證指定的一對人。自動尋找替代人、主管鏈與批次重派，仍需另外的權威來源。
