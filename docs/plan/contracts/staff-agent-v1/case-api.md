# agent BFF 與 BPM 案件 API

狀態：v1 固定契約，以下標「新增」的路由尚未存在。目前 ExternalApiAuthFilter 會拒絕未知路徑；實作時須同步加入明確 method/path/action 規則，不能放寬整段 prefix。

## 入口與認證

| 層級 | method 與 path | 認證／授權 | 狀態 |
|---|---|---|---|
| 外勤瀏覽器→agent | GET `/v4/workflow-cases/{domain}/{applicationId}/submissions/{submissionId}/{workflowRole}` | agent 既有 API key＋Bearer；驗 issuer/audience/type=AGENT，owning service 核對案件 scope | 新增，僅讀摘要 |
| 業務服務→agent | POST `/internal/bpm/v1/requests` | 專用 `X-Service-Id`＋`X-Service-Key`，與 service/domain/workflow allowlist 綁定 | 新增，私有網路；不是瀏覽器入口 |
| 業務服務→agent | GET `/internal/bpm/v1/requests/{requestId}` | 同上；查核 request 所屬業務服務，不能只因持有 key 就讀所有 domain | 新增，投遞對帳 |
| 業務服務→agent | GET `/internal/bpm/v1/cases/{domain}/{applicationId}/submissions/{submissionId}/{workflowRole}` | 同上；由 server-owned reference 解析 BPM process ID | 新增，狀態對帳 |
| agent→BPM | POST `/api/external/v1/process-instances` | `X-System-Id: agent-bff`＋`X-API-Key`；start_process、流程 binding 白名單 | 新增，鎖版與防重啟動 |
| agent→BPM | GET `/api/external/v1/requests/{requestId}` | 同上，query_status；request 的 system owner | 新增，取得投遞 receipt |
| agent→BPM | GET `/api/external/v1/process-instances/{id}` | 同上，query_status；process 的 system owner 及 allowedProcessKeys | 新增，權威流程狀態 |
| agent→BPM | GET `/api/external/v1/process-instances?businessKey=...` | 同上；exact businessKey 與 system owner | 新增，回 `{items: ProcessStatus[]}`，v1 唯一鍵保證 0 或 1 筆 |

三種 key 不可互換；API key 不進瀏覽器、日誌或 payload。內部服務憑證由 agent 的 server 配置驗證，不以請求的 service 名稱自證身分。agent 出站固定取 server 配置的 systemId/key，忽略／拒絕客戶端同名標頭，不轉送外勤 Bearer。每層使用 `X-Trace-Id`；無值由服務產生，限制 1..128 個 `[A-Za-z0-9._-]` 字元。

首批 `agent-bff` 明列 allowedActions=`["start_process","query_status"]`、allowedWorkerTopics=`[]`、allowedCandidateGroups=`[]`、allowOnBehalfOf=false；allowedProcessKeys 僅列該環境綁定的流程 key。新啟動入口依 server binding 指派內勤，不套用舊入口要求呼叫端提供 firstTaskAssignee 的模式。沒有 binding／可達的內勤受理規則就拒絕。

## 瀏覽器送件與查詢

**不新增通用 `/workflow-cases/.../submit` 或 `/approve`。** 送件／補件保持 owning 業務 API，由核心保存快照及 outbox 後再投遞。瀏覽器不能給 workflowCode、bindingVersion、processDefinitionId、actor、owner、首關人員、approved/rejected 或任意 variables。

例如既有 recruitment TALENT `POST /v4/recruitment/talent/applications/{id}/submit` 的 body 保持：

```json
{
  "expected_version": 3,
  "idempotency_key": "client-submit-fixture-1",
  "form_version": "fixture-interview-v1",
  "policy_version": "fixture-policy-v1",
  "consent": {"accepted": true, "document_id": "fixture-consent", "version": "1", "content_hash": "fixture-hash"}
}
```

此例只說明已有前端契約；AGENT 不可使用 TALENT route，以上版本／hash 也不是正式送件資料。prepare 仍僅凍結資料，不等於送 BPM。v1 AGENT transport 不承接 TALENT payload；禁止 BFF 為了接線把 actor type 改掉。

新增摘要查詢的五個 path 欄位皆必填，大小寫精確；domain 只接受註冊值，applicationId/submissionId 由 owning service 保存。核心先確認「該使用者可看此案件」及 submission 屬於 application，失敗或跨人一律 404。權限來源故障回 503，不能當成案件不存在。BFF 再讀對應的 process，不能將 path 當作任意 BPM 查詢。

成功 200，MASA envelope：

```json
{
  "code": "0000", "message": "success", "trace_id": "fixture-trace-1",
  "data": {
    "application_id": "app-a", "submission_id": "sub-a1", "workflow_role": "review",
    "delivery_status": "CONFIRMED", "review_status": "RUNNING", "outcome": "UNKNOWN",
    "source_revision": 1, "updated_at": "2026-11-05T02:00:00Z",
    "capabilities": {"can_view": true, "can_supplement": false}
  }
}
```

不回內勤 assignee、候選名單、全流程 variables、原始表單、API key 或 process ID。尚未取得 BPM 結果時 delivery_status 可為 PENDING／DISPATCHING／UNCERTAIN／FAILED，review_status=UNKNOWN、outcome=UNKNOWN、source_revision=null；只有 owning service 能證明的既有狀態可回傳，來源讀取失敗不能用假空值包成功。capabilities 每次由業務規則計算，僅供 UI 提示，異動還要重驗。

## 私有投遞與 BPM 啟動欄位

私有 agent body 使用 snake_case，BPM body 使用下列 camelCase；schema 同時提供 AgentStartRequest／BpmStartRequest，欄位一對一映射。**只轉 DTO 欄位，variables 內的業務 key 和 dataJson 內容不轉名。** 私有成功回應使用 MASA envelope 與 snake_case data；BPM 成功直接回 DTO。

| BPM 欄位 | 必填／型別／限制 | 來源與規則 |
|---|---|---|
| requestId | 是，UUID 小寫字串 | outbox 建立時產生並持久保存；任何重試不變 |
| workflowCode | 是，1..64 `[a-z0-9._-]` | server 註冊的業務流程代碼，非任意 BPMN key |
| bindingVersion | 是，正整數 | 案件送件時凍結的 workflow binding 版本 |
| caseRef | 是，CaseRef | 核心已保存的案件／submission，不從瀏覽器自由構造 |
| actor | 是，AgentActor | 核心先驗證來源、目前外勤資格與案件操作權；BFF 僅接受授權服務的投遞意圖 |
| processDefinitionId | 是，1..128 string | 精確部署版本；與 bindingVersion、allowedProcessKeys 一致 |
| formData | 是，FormData 或 null | 有主表必填 object；無主表必須 null，不省略 |
| variables | 是，object | 技術流程的已登錄業務變數；主表欄位由 dataJson 提供，不得重複 |

CaseRef：`domain`（1..32，小寫英數及 `_`／`-`）、`applicationId`／`submissionId`（各 1..64 英數及 `_`／`-`）、`workflowRole`（1..32，小寫英數及 `_`／`-`）、`submissionRevision`（正整數）。五欄均必填。ID 不自動大小寫轉換。

AgentActor：`type="AGENT"`、`agentCode`（1..64，權威來源原樣值）、`issuer`（受允許的 HTTPS issuer）、`subject`（1..256，原驗證 JWT sub），四欄必填。以 canonical agentCode 區分人；NASA sub 可為固定值，只保留來源脈絡，不能單獨作個人 key。不接受 account、email、BP 替代；不攜帶 token、密碼或 SAP ticket。

FormData：`formDefinitionId`（UUID）、`formVersion`（正整數）、`dataJson`（JSON object 的 UTF-8 字串），三欄必填。BPM 須 parse 成物件並用鎖定的 UniForm DSL 重驗；不能將字串通過 schema 視為表單已驗證。完整 request body 上限 1 MiB；附件 bytes 不在此 API 上傳，僅接受經業務服務核實的引用。

所有固定 DTO 拒絕未知欄位。variables 名稱不可含 `_` 前綴或 `initiator`、`effectiveInitiator`、`onBehalfOf`、`approved`、`rejected`；BPM 同時用流程變數規格拒絕未登錄欄位。formData 的欄位也不能覆蓋保留變數。不接受 callbackUrl、firstTaskAssignee、candidateGroups 等額外控制欄位。

```json
{
  "requestId": "10000000-0000-4000-8000-000000000001",
  "workflowCode": "contract.staff-review", "bindingVersion": 1,
  "caseRef": {"domain":"contract-fixture","applicationId":"app-a","submissionId":"sub-a1","workflowRole":"review","submissionRevision":1},
  "actor": {"type":"AGENT","agentCode":"AG000001","issuer":"https://idp.example.invalid","subject":"fixture-shared-sub"},
  "processDefinitionId": "bff-contract-review:1:fixture",
  "formData": {"formDefinitionId":"20000000-0000-4000-8000-000000000001","formVersion":1,"dataJson":"{\"requestReason\":\"合成契約驗收\"}"},
  "variables": {}
}
```

### Workflow binding

binding 是 server 配置／儲存資料，不是瀏覽器 API。固定記錄 `workflowCode`、`bindingVersion`、`domain`、`workflowRole`、`allowedCallerServices`、`allowedActorTypes=["AGENT"]`、`processDefinitionId`、`processDefinitionKey`、`formDefinitionId`／`formVersion`（同時 null 或有值）、`initialRoutingRule`、`approvalPermission`、`enabled`。initialRoutingRule 是受控規則 ID，不能是呼叫者提交的 EL。

首次執行時 binding 必須 enabled；binding 存在但已停用、版本與請求不一致，或定義已刪除，回 `BPM_DEFINITION_VERSION_MISMATCH`。新部署不會改寫既有 binding，也不能把 request 裡的舊 ID 靜默替換為 latest。已成功的相同 requestId 重放仍回原 receipt，不受後來停用影響，但服務／案件查詢授權必須仍有效。

## 防重與結果不明

businessKey 由 BPM 固定計算為 `domain/applicationId/submissionId/workflowRole`，不收 input businessKey；最大長度 195。唯一約束有兩組：`(systemId,requestId)` 與 `(systemId,domain,applicationId,submissionId,workflowRole)`。

相同 requestId 的語意比較涵蓋所有 StartRequest 欄位（含 actor、版本與資料）。固定 DTO 及 variables 的物件順序忽略；dataJson 必須先 parse 再比較，亦忽略 object key 順序；array 順序保留。JSON 數字依任意精度十進位值比較，1 與 1.0 相同；不 trim 字串、不變更 Unicode。拒絕 duplicate JSON keys、NaN／Infinity；hash 的 canonicalization 版本須存入紀錄，**不能直接 hash 原始 HTTP bytes**。traceId／服務憑證不參與 request hash。

| 條件 | 回應／處置 |
|---|---|
| 首次成功 | 201 CONFIRMED receipt；建立 request 紀錄與流程連結 |
| 同 key、同內容、已成功 | 200 原 receipt，`Idempotency-Replayed: true`；不建立第二案 |
| 同 key、同內容、仍處理中 | 202 PROCESSING receipt，Retry-After: 2；先 GET receipt |
| 同 key、同內容、已確定拒絕 | 重放原錯誤 HTTP/code，Idempotency-Replayed: true；GET receipt 則為 200 REJECTED，不重新執行 |
| 同 key、不同內容 | 409 BPM_IDEMPOTENCY_CONFLICT；不覆寫原紀錄 |
| 同 case tuple、不同 requestId | 409 BPM_CASE_ALREADY_STARTED；先用已保存的原 requestId／businessKey 對帳，不自動改號再送 |
| timeout／連線中斷／2xx 缺欄位 | agent 視為 BPM_OPERATION_UNCERTAIN；outbox 保持 UNCERTAIN，GET receipt／案件對帳 |
| receipt 404 | 不能證明原請求未建立流程。僅在 v1 防重已啟用時，重新確認資格／案件仍可送後，用完全相同 requestId/body 重送；禁止 fallback 舊 API |
| receipt UNCERTAIN 或 legacy 多筆結果 | 停止自動啟動，持續查詢／人工修復；不選第一筆當成功 |

有效資格與授權在每次呼叫重新檢查，重放不繞過停權。duplicate lookup 先於流程版本有效性檢查，以便已有成功結果可對帳。併發唯一約束必須在資料庫落實，不能只靠 Redis lock。流程與 request 關聯須同一核心 DB 交易；表單跨 DB 補償失敗時保留 UNCERTAIN 與同一 key，不能移除紀錄後重新建立。request 防重保留期不得短於案件／流程保存期；封存後留防重 tombstone，不能讓 TTL 到期變成新請求。

schema／認證／授權等保留 request 紀錄前的錯誤，不建立 durable REJECTED；若已保留並確定拒絕，該 requestId 與 case tuple 不再用於修改後的命令。需修正版本／內容時，由 owning service 建立新的 submission，保留原拒絕歷史。修正前不得自動換 requestId 重送同一 submission。

## Receipt 與狀態

Receipt（BPM 直接 JSON；agent private data 轉 snake_case）：

```json
{"requestId":"10000000-0000-4000-8000-000000000001","state":"CONFIRMED","caseRef":{"domain":"contract-fixture","applicationId":"app-a","submissionId":"sub-a1","workflowRole":"review","submissionRevision":1},"businessKey":"contract-fixture/app-a/sub-a1/review","processInstanceId":"process-fixture-1","errorCode":null,"retryAfterSeconds":null}
```

state 固定 PROCESSING／CONFIRMED／REJECTED／UNCERTAIN。CONFIRMED 必須有 processInstanceId、errorCode=null；PROCESSING 的 processInstanceId=null、errorCode=null、retryAfterSeconds=2；REJECTED 無 process ID 且有固定錯誤碼（只用於已確定未建案的 durable rejection）；UNCERTAIN 可帶已知 process ID，errorCode=BPM_OPERATION_UNCERTAIN、retryAfterSeconds=null。GET receipt 為 200，未知／非所屬 system 為 404。

ProcessStatus 必填：`processInstanceId`、`processDefinitionId`、`processDefinitionKey`、`workflowCode`、`bindingVersion`、`caseRef`、`businessKey`、`formDefinitionId`／`formVersion`、`engineState`、`reviewStatus`、`outcome`、`reasonCode`、`sourceRevision`、`updatedAt`。無主表的兩欄明確 null。

| 狀態 | engineState | reviewStatus | outcome |
|---|---|---|---|
| 內勤審核中 | RUNNING | RUNNING | UNKNOWN |
| 待外部補件 | RUNNING 或 COMPLETED（由已登錄流程語意決定） | RETURNED | UNKNOWN |
| 最終核准／駁回 | COMPLETED | COMPLETED | APPROVED／REJECTED，必須有明確流程結論 |
| 取消／刪除 | CANCELLED | COMPLETED | UNKNOWN，reasonCode=PROCESS_CANCELLED |
| 不能可靠映射 | 實際 engineState | UNKNOWN | UNKNOWN，reasonCode=RESULT_MAPPING_UNAVAILABLE |

不得以「已結案且 rejected 不為 true」推導核准。sourceRevision 為同一流程從 1 起嚴格遞增的整數；同 revision 的業務結果必須一致。範圍上限 2^53−1。webhook 只喚醒對帳；較舊 revision 忽略，相同 revision 異值告警並保留既有值；只更新該 submission 的歷程，不覆蓋另一筆較新的 submission。

來源：[現有 ExternalApiController](../../../../bpm-core/src/main/java/com/bpm/core/external/ExternalApiController.java)、[現有外部 filter](../../../../bpm-core/src/main/java/com/bpm/core/external/ExternalApiAuthFilter.java)、[recruitment 本人契約](../../../../../recruitment/docs/features/organization-recruitment/phase2-api.md)。
