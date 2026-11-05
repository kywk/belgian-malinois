# 內勤身分與 AuthZ 契約

狀態：v1 固定契約，待實作。所有新增檢查套用於正式內勤入口，不改用外勤 Token 或 API key 作後台身分。

## 身分解析

順序固定為「驗 token → 解析 canonical 內勤 → 查資格新鮮度 → 查 AuthZ → 查案件／任務權限」。任一步失敗均不得使用後續來源的資料補造身分。

| 值 | 來源與規則 |
|---|---|
| `iss`、`aud`、`kid`、`alg` | 允許組合由部署設定固定。僅 RS256；aud 必須包含設定的 BPM 可接受 audience；kid 必須屬於該 issuer/audience 的受控 key set。不能依 token URL／任意 kid 取金鑰 |
| `sub` | 必填、不含前後空白；保留簽發者原值。以 `(issuer, sub)` 查身分 mapping，不從 nameid/account/email/office_code 猜 USERID |
| `exp`、`iat` | 必填 numeric date 秒；exp > iat；iat 不可超過伺服器時間 60 秒；exp 過期拒絕。若有 nbf 亦驗證，允許時鐘誤差上限 60 秒 |
| `userId` | 權威 mapping 回傳、存在且 ACTIVE 的內勤 `StaffEmployee.UserId` 原樣值。不得把整個 ODS ID 小寫；別名只由權威 mapping 接受 |
| `deptId` | ODS 組織 Object ID 的字串，對應 ParentOrgCode；不是顯示名稱或外勤 A02 office code |
| `orgObjectIds` | 權威組織資料解析出的有效內勤授權層級，去重。與 Staff 的 Z05（缺省 Z06）／Z07／Z09 Object ID 規則對齊；不自行加入其他祖先層級 |
| AuthZ subjects | `staff-<lowercase(userId)>` 加每個 `org-<lowercase(orgObjectId)>`。排序去重；保留原始 userId 給 Flowable。缺組織 mapping 是資料缺口，不能宣稱沒有組織權限 |
| JWT `roles`、`office_code` | roles 不授權；office_code 只可比對／診斷，不能覆蓋目前的權威組織。SAML 實作目前在 office_code 放組織 Object IDs，不代表任何來源的同名欄位都可信 |

`StaffIdentitySnapshot` 是 BPM 的 **outbound port DTO**，不宣告新增企業人員 HTTP API；provider 可介接既有來源。固定欄位：

| 欄位 | 型別與限制 | 語意 |
|---|---|---|
| userId | string，1..100 | canonical USERID |
| displayName | string，1..200 | 顯示，不參與授權 |
| userType | `STAFF` | 不接受 AGENT／TALENT／SERVICE |
| status | `ACTIVE`／`INACTIVE` | 找不到不是 INACTIVE，須以 NOT_FOUND 結果回傳 |
| deptId | string，1..64 | 有效內勤組織 Object ID |
| orgObjectIds | 非空 string[]，每項 1..64 | 已確認的授權組織層級 |
| sourceRevision | string，1..128 | 來源版本；不要求可轉整數 |
| observedAt、validUntil | UTC RFC3339 string | 來源有效性，不可每次讀 cache 都往後延 |

來源必須能證明 ACTIVE 與有效期限。ODS 僅有人員列但無在職狀態時，不能直接產生 ACTIVE snapshot。BPM cache 最長 60 秒且不得超過 validUntil；過期／不可判定回 `BPM_IDENTITY_UNAVAILABLE`。這是目標 SLA，現有 ODS 同步尚未證明符合。離職／停權事件須清 cache；已有直接 assignee 也必須重新通過資格查核。

port 輸入固定 `(issuer, subject)`，輸出分為 FOUND(snapshot)、NOT_FOUND、UNAVAILABLE；不得用空 snapshot 表示故障。snapshot 必須滿足 validUntil > observedAt，且不能以未來時間偽造新鮮度。來源負責 canonical mapping 與資格真實性，BPM 在每次使用時檢查時效。

## Session API

新增 `GET /api/me/session`，只接受內勤 Bearer；沒有 query/body userId。成功 200、直接 JSON、`Cache-Control: no-store`：

```json
{
  "userId": "W9000001",
  "displayName": "契約測試內勤甲",
  "userType": "STAFF",
  "deptId": "50310001",
  "permissions": ["bpm:console:access:use", "bpm:task:work:read"],
  "expiresAt": "2026-11-05T02:30:00Z",
  "authorizationCheckedAt": "2026-11-05T02:00:00Z"
}
```

permissions 僅回 catalog 的已核准四段碼，去重排序；不回 `ROLE_`、`FACTOR_`、`*` 或 AuthZ 管理碼。expiresAt 為 JWT 有效期限，不表示權限可快取到該時間。session 不回原始 token／SAP ticket／AuthZ credential。

所有內勤業務 API 需 `bpm:console:access:use`。此碼不足時 session 回 403，不以成功但空權限混淆資格。內勤 API 不接受 agent API key 或 gateway headers 代登入；CI 部署與 cache invalidate 保留獨立機器入口／政策，避免把既有自動化一併套成人員登入。

舊 `/api/me/permissions` 仍回 `{userId, permissions, admin}`：permissions 在轉換邊界回 catalog 的 legacyAuthority（若沒有則回 canonical code）；admin 只表示具有 `bpm:system:config:manage`。**admin 是 UI 相容值，不可用於替代其他 endpoint 的細權限檢查。** 新 session 與新 UI 使用 canonical code。

## 權限目錄與動作

AuthZ system_code=`bpm`。四段碼的結構為 system:function:resource:action；以下為將來配置的權限，不代表現有 AuthZ 已建立。

| canonical permission | 適用面 | 額外必要條件 |
|---|---|---|
| bpm:console:access:use | 登入後台與使用內勤 API | ACTIVE 內勤 |
| bpm:task:work:read | 我的待辦、可見任務表單 | 現有案件／持有／候選人守衛 |
| bpm:task:work:act | claim、complete、delegate、resolve、reassign | 現有動作守衛；complete 核決另需 workflow binding 的 approvalPermission |
| bpm:form:definition:design | 表單新增、修改、發布、封存、刪除 | 不授予案件內容讀取 |
| bpm:bpmn:definition:deploy | 流程部署 | 沿用 ADR-002：sit/uat/prod 人為部署僅 break-glass；CI 另認機器身分，不給 console 權限 |
| bpm:system:config:manage | 平台管理設定 | 不隱含表單、部署、稽核或簽核權 |
| bpm:audit:log:read | 全域稽核及既有跨案稽核讀取 | 維持獨立授權；不授予修改／簽核 |
| bpm:approval:bff_contract_review:approve | 合成驗收流程核決 | 仍須 task:work:act 與持有任務；正式環境不建立此測試 grant |

舊碼映射固定：`bpm:form:design` → `bpm:form:definition:design`；`audit:log:read` → `bpm:audit:log:read`。部署用內部 alias `bpm:bpmn:deploy` → `bpm:bpmn:definition:deploy`（前者是 ADR 的待實作建議名，非宣稱現有程式已使用）。`ROLE_ADMIN`、`*` 沒有權限碼映射，不由它們推導上述 grant。

catalog 同時固定角色樣板：staff_user（console＋task read）、form_designer（console＋design）、operator（console＋config manage）、auditor（console＋audit）、CI deployer（只有 deploy）；測試 reviewer 再加 task act 與合成 approval 權限。角色名稱不從 JWT roles 直接採信，AuthZ 可把樣板指派給個人或經確認的組織；本次不自動指派任何真實人員，測試 reviewer 不建立正式 grant。

既有 `hr:*`／`finance:*` BPMN 權限需逐流程新增 catalog mapping；未註冊者在 binding 驗證時回配置錯誤，不猜四段碼。需要查其他 system 的業務權限時依映射分組呼叫 AuthZ，不能用 system_code=bpm 查出跨系統 grant。

### AuthZ 實際 wire

既有 `POST /api/v1/sdk/permissions/check` 的 **JSON 是 subject_ids，不是 effective_subject_ids**。後者只是 Java method 名稱，舊 API markdown 不準確。BPM 固定送：

```json
{
  "subject_ids": ["staff-w9000001", "org-50310001"],
  "system_code": "bpm",
  "permissions": ["bpm:console:access:use", "bpm:task:work:act", "bpm:approval:bff_contract_review:approve"],
  "mode": "ALL",
  "subject_attributes": {}
}
```

成功範例：`{"code":"0000","message":"success","data":{"allowed":true,"reason":"ALLOW"},"trace_id":"fixture-trace-1"}`。allowed=false 是合法拒絕，回 BPM 403；非 0000、缺 data/allowed、逾時、5xx 是授權來源故障，回 BPM 503，不轉成無權限或放行。reason 只作診斷，不以文字判斷 allowed。

subject_attributes 首批固定空物件。後續 ABAC 值只能由伺服器權威資料產生；AuthZ 會讓 request attributes 覆蓋持久屬性，因此不得透傳表單／瀏覽器 attrs。profile 僅用於取得候選權限；要產生可用 permissions 清單須逐項 check，不能用一次 ANY/ALL 結果推論每一項，也不能以 profile 聯集跳過 ABAC。

讀取權限 cache 最長 60 秒、到期故障不得用舊值。所有異動與敏感稽核讀取在當次操作重新 check，不使用舊的正向 cache；檢查後仍以 BPM 交易內的任務狀態／版本防並發。撤權通知清除使用者與組織衍生 cache，未知成員影響範圍時清該 system 的權限 cache，不只清單一 org 主體。

批次 profile 使用既有 `POST /api/v1/grants/subjects/profiles/query`，body=`{"subject_ids":[...],"system_code":"bpm"}`；服務帳號透過 `X-Subject-Id`，需 authz_admin 前綴角色及 `authz:subject:read`。反向資源查詢另需 `authz:resource:read`；這些管理碼不授予 BPM 人員。SDK check 的網路／服務認證由部署綁定，不因免 admin interceptor 而公開給瀏覽器。

原始碼依據：[PermissionCheckRequest](../../../../../authz/src/main/java/com/company/authz/modules/sdk_gateway/api/request/PermissionCheckRequest.java)、[ApiResponseFactory](../../../../../authz/src/main/java/com/company/authz/shared/web/ApiResponseFactory.java)、[StaffAuthzIdentity](../../../../../staff/src/main/java/com/nanshan/core/middleware/authz/StaffAuthzIdentity.java)、[SAML office_code](../../../../../saml/src/main/java/com/nanshan/saml/application/v1/SamlAuthUseCase.java)、[既有部署決策](../../2026-10-05-adr-002-bpmn-as-code.md)。
