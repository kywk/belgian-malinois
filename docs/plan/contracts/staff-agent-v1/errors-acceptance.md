# 錯誤碼與驗收案例

以下是目標行為，尚未執行服務整合驗收。JSON 正反例只檢查 DTO 形狀；不能證明這裡的身分、防重、隔離與交易案例通過。

## 錯誤格式與判斷

新 BPM v1 及新增 `/api/me/session` 錯誤固定：

```json
{"code":"BPM_IDEMPOTENCY_CONFLICT","message":"相同請求識別已使用不同內容","traceId":"fixture-trace-1","requestId":"10000000-0000-4000-8000-000000000001","retryable":false,"outcome":"NOT_APPLIED"}
```

agent 新入口固定 MASA error envelope，欄位轉 snake_case，data=null；為避免誤重試，增加 retryable/outcome/request_id：

```json
{"code":"BPM_OPERATION_UNCERTAIN","message":"處理結果尚待確認","data":null,"trace_id":"fixture-trace-1","request_id":"10000000-0000-4000-8000-000000000001","retryable":false,"outcome":"UNKNOWN"}
```

message 可本地化，不作程式判斷；requestId/request_id 在未形成投遞命令時為 null。outcome 固定 NOT_APPLIED（能證明本次 attempt 未產生副作用）、UNKNOWN（可能已生效）、NOT_APPLICABLE（唯讀／認證）；**不是同 key 歷史請求的結論**。retryable=true 只允許保留原 requestId/body 並遵守退避重試，不允許產生新 key。UNKNOWN 一律先查詢，不自動重送異動。

舊 BPM API、既有 agent 業務路由、AuthZ 自己的 envelope 不在此輪改格式；adapter 明確轉譯。不只看 HTTP 200，還要驗證必要欄位與上游業務 code。本表的新 code 僅適用新增契約與明確接線處。

## 代碼目錄

| code | HTTP | 使用時機 | 重試／後續動作 |
|---|---|---|---|
| BPM_AUTH_INVALID | 401 | 新人員入口缺 Bearer、簽章／issuer／audience／kid／時效不符 | 重新登入；不可自動重送異動 |
| BPM_STAFF_REQUIRED | 403 | token 有效但不是已確認內勤 | 不可改帶 header 冒充 |
| BPM_ACCOUNT_INACTIVE | 403 | 內勤或 BFF 外勤來源明確停權 | 需來源狀態修復 |
| BPM_PERMISSION_DENIED | 403 | ACTIVE 身分缺操作權限 | 查 grant；不以 admin／roles fallback |
| BPM_SERVICE_AUTH_INVALID | 401 | 私有 agent 或 BPM 服務憑證缺失／錯誤 | 修服務設定，不要求外勤重新登入 |
| BPM_SERVICE_FORBIDDEN | 403 | 已驗證服務停用、IP／domain／action 不允許 | 修明確授權 |
| BPM_WORKFLOW_NOT_ALLOWED | 403 | workflow／process 不在呼叫系統範圍 | 不換任意流程 key 重送 |
| BPM_NOT_FOUND | 404 | 不存在或資源不在呼叫者案件／system 範圍 | 不揭露真實 owner；receipt 404 規則見案件文件 |
| BPM_VALIDATION_ERROR | 400 | 缺欄位、未知欄位、保留變數、型別錯誤、重複 JSON key | 修正輸入；既有 requestId 已受理時不可改內容重送 |
| BPM_PAYLOAD_TOO_LARGE | 413 | body 超過 1 MiB | 附件改用業務文件接口 |
| BPM_CASE_VERSION_CONFLICT | 409 | owning service 快照／expectedVersion 已變更 | 重載案件；不自動套新版本 |
| BPM_CASE_STATE_CONFLICT | 409 | 案件已撤回或當下不允許投遞／補件 | 重載 capabilities |
| BPM_DEFINITION_VERSION_MISMATCH | 409 | binding 停用、不存在或流程版本不相符 | 重新取得經業務確認的綁定；不改成 latest |
| BPM_FORM_VERSION_MISMATCH | 409 | 表單與流程綁定版本不同 | 重新讀取並確認表單 |
| BPM_FORM_VALIDATION_FAILED | 422 | 鎖版 DSL 驗證失敗 | 修正業務表單；不略過驗證 |
| BPM_IDEMPOTENCY_CONFLICT | 409 | 同 requestId 不同語意內容 | 查原請求；禁止覆寫 |
| BPM_CASE_ALREADY_STARTED | 409 | 同 case tuple 已用另一 requestId 建立／保留 | 用原 requestId 或 exact businessKey 對帳 |
| BPM_RATE_LIMITED | 429 | 服務限流 | retryable=true；Retry-After 秒數退避 |
| BPM_IDENTITY_UNAVAILABLE | 503 | 人員 mapping／在職／組織來源不可判定或過期 | retryable=true；來源恢復才重新查 |
| BPM_AUTHZ_UNAVAILABLE | 503 | AuthZ timeout、5xx、非 0000 或不合法 data | retryable=true；不得降級空權限或舊 grant |
| BPM_ROUTING_UNAVAILABLE | 503 | 沒有可用且合格的內勤受理路徑 | 修正組織／流程規則；不能派給外勤或空人員 |
| BPM_INTEGRATION_NOT_CONFIGURED | 503 | workflow/provider／正式環境必填設定未配置 | retryable=false；需完成配置 |
| BPM_UPSTREAM_UNAVAILABLE | 503 | BFF 唯讀下游故障、下游服務憑證失效，或可證明尚未送出的異動故障 | retryable=true；異動僅在 NOT_APPLIED 時重試同 key |
| BPM_OPERATION_UNCERTAIN | 500 | 異動 timeout、斷線、2xx DTO 缺失、跨 DB 補償結果不明 | retryable=false、outcome=UNKNOWN；先查 receipt |
| BPM_INTERNAL_ERROR | 500 | 其他未預期錯誤 | 不回 stack trace；唯讀 NOT_APPLICABLE，異動不能證明未生效時 UNKNOWN |

認證、schema 與授權檢查先於異動。私有／BPM 的錯誤 HTTP 不直接全部透傳給外勤：下游 401 或服務級 403 → public 503 BPM_UPSTREAM_UNAVAILABLE；不能把 agent 的 key 過期顯示為外勤帳號失效。案件越權 → 404；使用者有案件但沒有該業務動作權 → 403。原始下游 body 與金鑰不傳給瀏覽器。

## 合成驗收資料

| fixture | 語意 |
|---|---|
| W9000001／W9000002 | ACTIVE 內勤，分別屬 50310001／50310002；部門碼為假 Object ID |
| W9000003 | INACTIVE 內勤，即使 token 未過期亦拒絕 |
| staff-w9000001 + org-50310001 | 同時可驗證個人 grant 與組織繼承；使用 catalog 合成 review 權限 |
| AG000001／AG000002 | 兩位 ACTIVE 外勤，issuer 相同，sub 都是 fixture-shared-sub，必須仍能區分兩人 |
| app-a/sub-a1、app-b/sub-b1 | 兩位外勤各自可見的案件；同一 systemId=agent-bff |
| contract-fixture-service | 僅授權 domain=contract-fixture、workflowCode=contract.staff-review 的私有呼叫者 |
| bff-contract-review:1:fixture | 合成流程版本；測試進行中另部署 v2，不應改變 v1 請求 |

fixture 是測試資料，不能當正式種子帳號。以下是行為驗收清單，實作時逐項落成測試並連結結果。

## 身分與授權驗收

| ID | Given／When | Then |
|---|---|---|
| ID-01 | 允許 issuer/aud/key 的 ACTIVE 內勤，具有 console grant，GET session | 200 canonical USERID；無 token／SAP ticket；expiresAt 正確 |
| ID-02 | 有效外勤 JWT 或 TALENT JWT，GET session／tasks | 403 BPM_STAFF_REQUIRED；無 Flowable 查詢／異動 |
| ID-03 | API key／X-User-Id／X-Gateway-Secret 想當後台使用者 | 不取得內勤 principal；缺合法 Bearer 回 401 |
| ID-04 | 錯簽章、錯 audience、未知 kid、錯 issuer、過期，各別送入 | 各 401；不能因另一來源 key 有效而放行 |
| ID-05 | sub 空白／缺失／企圖用 account/nameid fallback | 401；不建 userId |
| ID-06 | token 有效，但人員查無或非 STAFF | 403 STAFF_REQUIRED；來源 timeout 則 503 IDENTITY_UNAVAILABLE，兩者不可混淆 |
| ID-07 | 已有 assignee 的內勤停權，token 仍有效 | 最遲資格 cache 期限後拒絕，不能因是 assignee 放行；異動不生效 |
| ID-08 | JWT office_code 是舊部門，目前權威資料為新部門 | 只以新組織主體 check；舊部門 grant 不生效 |
| AZ-01 | 個人無 grant，但 org-50310001 有 grant | 以 subject_ids 聯集通過；使用 Object ID，不以部門顯示名比對 |
| AZ-02 | JWT roles=[admin,*]、AuthZ 無 grant | 403；session 無管理／稽核／部署能力 |
| AZ-03 | AuthZ HTTP 200，code=0000，allowed=false | 403 PERMISSION_DENIED；不當成 503 |
| AZ-04 | AuthZ HTTP 200 但非 0000／缺 allowed；或 timeout/5xx | 503 AUTHZ_UNAVAILABLE；不回成功空清單、不用 stale roles |
| AZ-05 | 只有 system:config:manage，沒有 audit/design/deploy | 相應操作全拒；admin UI 相容旗標不升權 |
| AZ-06 | 有 approval grant，但不是該任務持有／合法候選人 | 資源不可見時 404；已依法可見但不具動作資格時 403；兩者皆不得完成任務 |
| AZ-07 | 已派任務的使用者缺 task:work:act 或流程 approval grant | complete 403；在 BPM 交易前重新 check |
| AZ-08 | 上次 check 允許，隨後撤權，再提交修改 | 當次遠端 check 拒絕；舊讀取 cache 不放行 |
| AZ-09 | 外部輸入試圖送 subject_attributes 覆蓋 DEPT | 400 或在 feature DTO 明確拒絕；出站首批 attrs={} |

## BFF 與案件隔離驗收

| ID | Given／When | Then |
|---|---|---|
| BF-01 | AG000001 讀 app-a/sub-a1 | 200 摘要，不含內勤人名／process ID／variables |
| BF-02 | AG000002 與前者相同 JWT sub，讀 app-a | 404；按 agentCode＋業務 scope 隔離，不按 sub 混人 |
| BF-03 | app-a 搭配 sub-b1，或任意 workflowRole | 404；不能拼接跨案件關聯 |
| BF-04 | 瀏覽器加 actor/owner/systemId 或帶服務 header | 不覆蓋已驗證 context；私有入口不接受瀏覽器憑證 |
| BF-05 | 私有合法 service 呼叫別的 domain/workflow | 403 SERVICE_FORBIDDEN／WORKFLOW_NOT_ALLOWED；未呼叫 BPM |
| BF-06 | agent 專用 key 請求內勤任務 complete、管理或部署 | 拒絕；既有內勤任務狀態完全不變 |
| BF-07 | 下游 API key 過期 | public 503 UPSTREAM_UNAVAILABLE；不要求使用者重新登入 |
| BF-08 | AGENT 使用既有 TALENT submit，或 TALENT 送僅允許 AGENT 的 workflow | 拒絕；不新增 outbox、不改 actor type |

## 鎖版與可靠投遞驗收

| ID | Given／When | Then |
|---|---|---|
| WF-01 | 已保存 AGENT submission、合法 binding、正確版本首次 POST | 201 CONFIRMED，一筆流程；initiator=system:agent-bff；actor 與 caseRef 可稽核 |
| WF-02 | 相同 requestId/body 並發 20 次 | 至多一個 process；其餘 200 replay 或 202；DB 唯一約束可證明 |
| WF-03 | 同 requestId 只改 JSON key 次序、dataJson key 次序、1→1.0 | 同語意 replay；array 改序或字串改值則 409 |
| WF-04 | 同 requestId 改 actor、版本或資料 | 409 IDEMPOTENCY_CONFLICT；原結果不變 |
| WF-05 | 同 case tuple 換 requestId | 409 CASE_ALREADY_STARTED；不得第二案 |
| WF-06 | 首次 POST 前部署新 v2，但請求固定 v1 | 啟動 v1；請求與 binding 不符才 409，不默默取 v2 |
| WF-07 | 表單 ID／版本錯；或有表單流程給 null | 409 FORM_VERSION_MISMATCH；合法版本但填寫不合法則 422 |
| WF-08 | variables/formData 夾保留變數或未知固定 DTO 欄位 | 400 VALIDATION_ERROR；不建流程 |
| WF-09 | 成功 commit 後網路中斷／回應被截斷 | BFF 標 UNCERTAIN，GET receipt 找到同一 process；不得用新 key 重送 |
| WF-10 | request 處理中重啟服務／並發 receipt 404 | 透過 durable request 與唯一約束恢復；同 key 重試不產生第二案 |
| WF-11 | 表單補償失敗／不明多筆舊流程 | 保留 UNCERTAIN，停止自動新建並告警；不回假 CONFIRMED |
| WF-12 | 其他 system 查 requestId、businessKey 或 process ID | 404；沒有原 owner、caseRef 或存在性資訊 |
| WF-13 | 原請求成功後 binding 停用，合法呼叫者重送原內容 | 200 原 receipt；新 request 不得用停用 binding 建案 |
| WF-14 | 已建 outbox，但派送前案件撤回／actor 停權 | owning service 阻止派送；已成功紀錄仍保留，不能改為從未送出 |

## 狀態與端到端驗收

| ID | Given／When | Then |
|---|---|---|
| EV-01 | 流程完成但沒有明確核准／駁回 mapping | outcome=UNKNOWN；取消不得顯示 APPROVED |
| EV-02 | webhook 重複／亂序／同 revision 不同內容 | 驗簽後授權查詢；舊 revision 不覆寫；同版異值告警 |
| EV-03 | 舊 sub-a1 完成時案件已有 sub-a2 | 只更新 a1 history，不覆蓋目前 a2 |
| EV-04 | 外勤送件→業務 outbox→agent→BPM→內勤 SSO 簽核→外勤讀摘要 | 兩端 actor／case／request／process／trace 可串接；外勤不能改內勤結果 |

## 完成證據要求

schema/examples 驗證與 git diff --check 是本次契約檢查。後續實作需另外提供：上述案例的測試對應、實際 HTTP 狀態／body、兩位外勤與兩位內勤的隔離證據、唯一鍵並發測試、跨 DB 失敗／重啟恢復測試、真實 SSO/AuthZ/組織來源驗收。只跑 mocked JWT 或 schema 不得勾選 EV-04 完成。
