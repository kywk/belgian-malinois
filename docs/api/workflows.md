# 申請、任務與外部工作操作流程

搭配 [API 入口](README.md) 閱讀。以下 ID、流程 key、topic 與欄位均為示意假資料，執行前替換為已部署流程和可用測試身分；不能將範例視為預建 fixture。所有 JSON 使用 `application/json`。一般 API 使用 Bearer JWT；直接回傳物件，沒有 MASA `code/data` 信封。

## 取得版本後送件

1. `GET /api/process-definitions` 選擇流程，再 `GET /api/process-definitions/{key}/start-form` 取得該次表單的確切流程定義 ID、表單 UUID、版本與 DSL。
2. 依 DSL 產生資料；`POST /api/process-instances` 傳同一組版本，不自行拼湊 ID：

<!-- example: POST /api/process-instances request -->
```json
{"processDefinitionKey":"demoApproval","processDefinitionId":"demoApproval:1:demo-definition","businessKey":"DOC-DEMO-001","formData":{"formDefinitionId":"00000000-0000-4000-8000-000000000001","formVersion":1,"dataJson":"{\"reason\":\"文件流程測試\"}"}}
```

`dataJson` 是 JSON 物件的字串；`reason` 僅示意，必須符合實際 DSL。不要傳 initiator；由登入身分決定。`variables` 若使用為物件 map，不得偽造受保護名稱或 `_` 開頭變數，也不可與 formData 重複定義同一值。未綁主表的技術流程可不帶 formData，不能自行加上未綁表單。

成功 HTTP 200 回 processInstanceId、businessKey、status=running；有待辦才加 currentTask、currentTaskCount，有表單才加 formDataId。currentTask 是排序第一筆，不代表沒有平行任務：

<!-- example: POST /api/process-instances response 200 -->
```json
{"processInstanceId":"demo-process-001","businessKey":"DOC-DEMO-001","status":"running","currentTask":{"taskId":"demo-task-001","taskName":"主管審核","assignee":"demo-reviewer"},"currentTaskCount":1,"formDataId":"00000000-0000-4000-8000-000000000002"}
```

版本已更動或流程已暫停回 409；缺版本、錯誤 formData 或非法變數回 400，流程 key 不存在回 404。重新取得 start-form，讓使用者核對新版本與資料後再送，不能只替換版本號以跳過檢查。

流程與表單分屬交易：表單寫入安排在最後，主交易失敗有補償刪除，但仍存在補償失敗窗口。`businessKey` 不等於公開的冪等鍵保證。逾時後先以目前使用者可見案件／歷史與 businessKey 對帳，必要時由管理員查流程及表單資料；未確認第一次未建立前不再次 POST。

依據：[ProcessController](../../bpm-core/src/main/java/com/bpm/core/controller/ProcessController.java)、[StartProcessRequest](../../bpm-core/src/main/java/com/bpm/core/dto/StartProcessRequest.java)。

## 認領、簽核與退回

以 `GET /api/tasks` 取得自己的任務匣，不傳他人 assignee／candidateUser，也不能自行宣告 candidateGroups。先以 `GET /api/tasks/{taskId}/form` 取得流程鎖定版本，再進行 `PUT /api/tasks/{id}`：

<!-- example: PUT /api/tasks/{id} request -->
```json
{"action":"claim"}
```

claim 的持有人為呼叫者。完成可附表單或變數；此處示意不含表單的核准任務，`approved` 是否被流程消費仍取決於 BPMN：

<!-- example: PUT /api/tasks/{id} request -->
```json
{"action":"complete","variables":[{"name":"approved","value":true}]}
```

表單任務可加 `formData: {"dataJson":"{\"reason\":\"測試\"}"}`，不能指定另一個表單 ID／版本。variables 是 name/value 陣列，與啟動 map 不同。退回申請人用 `action=complete`、`returnTo=initiator`，不可同時 approved=true 或 rejected=true；它不是任意節點跳轉。delegate 使用 delegateUser，resolve 回交委派任務，reassign 使用 assignee；只送 assignee 不會改派。

<!-- example: PUT /api/tasks/{id} response 200 -->
```json
{"taskId":"demo-task-001","status":"ok"}
```

成功可能另含 formDataId。錯誤範例為框架 HTTP 400（message 是否提供依錯誤來源與設定而定）：

<!-- example: PUT /api/tasks/{id} response 400 -->
```json
{"timestamp":"2030-01-02T00:00:00.000Z","status":400,"error":"Bad Request","path":"/api/tasks/demo-task-001"}
```

400 修 payload；403 核對持有／候選人權限；404 表示任務不存在或已不在可操作狀態；409 重新取得任務與版本。完成逾時後先查任務匣與案件進度，勿將任務消失直接判定為失敗並重新啟動案件。流程交易與表單／外部副作用不能視為一個跨系統原子交易。

依據：[TaskController](../../bpm-core/src/main/java/com/bpm/core/controller/TaskController.java)、[TaskActionRequest](../../bpm-core/src/main/java/com/bpm/core/dto/TaskActionRequest.java)。

## 外部 worker：先取得鎖，再回報

外部入口同時帶 `X-System-Id` 與 `X-API-Key`，不使用一般使用者 JWT 代替。系統啟用、IP 白名單、allowedActions（external_worker）、topic 授權及限流仍適用。workerId 由伺服器固定為 `system:<systemId>`，不能借用其他系統的值。

`POST /api/external/worker/tasks/acquire` 每次最多取得一筆：

<!-- example: POST /api/external/worker/tasks/acquire request -->
```json
{"topic":"demo-delivery","lockDurationSeconds":300}
```

無工作成功回空 tasks：

<!-- example: POST /api/external/worker/tasks/acquire response 200 -->
```json
{"tasks":[]}
```

鎖秒數需整數 1..86400。以回傳 jobId 執行外部工作，取得方自行用穩定業務鍵防重；worker 鎖不會替外部付款／寄信提供 exactly-once。成功後 `POST /api/external/worker/tasks/{jobId}/complete`：

<!-- example: POST /api/external/worker/tasks/{jobId}/complete request -->
```json
{"variables":{"deliveryReference":"DOC-DEMO-001"}}
```

<!-- example: POST /api/external/worker/tasks/{jobId}/complete response 200 -->
```json
{"jobId":"demo-job-001","status":"completed"}
```

variables 為 map，禁止 `_` 保留前綴。確定執行失敗時以 `/fail` 回報 errorCode／errorMessage，伺服器遞減 retries，回 retriesLeft；只是要釋放鎖用 `/unacquire`，不減 retries。這些動作都要求仍由本系統持鎖；查無或非本系統持鎖回 404，不藉此透露他人工作。

`GET /api/external/worker/tasks?topic=demo-delivery` 可核對未鎖定與本系統持有工作；未鎖定時 lockOwner／lockExpirationTime 可為 null。acquire／complete 逾時後先核對工作及外部業務結果；不能重新 acquire 後無條件重做副作用，也不可將 complete 的 404 直接認定第一次失敗。429 等候限流恢復、503 查依賴；401／403 修憑證或授權。

依據：[ExternalWorkerController](../../bpm-core/src/main/java/com/bpm/core/external/ExternalWorkerController.java)、[ExternalApiAuthFilter](../../bpm-core/src/main/java/com/bpm/core/external/ExternalApiAuthFilter.java)。

## HMAC callback：保留 deliveryId 重試

BPMN 必須正在等待 `{type}` 對應訊息。`POST /api/callback/esign-completed`：

<!-- example: POST /api/callback/{type} request -->
```json
{"processInstanceId":"demo-process-001","deliveryId":"demo-delivery-001","variables":{"signed":true}}
```

同時提供 `X-System-Id`、`X-Callback-Timestamp`、`X-Callback-Signature`。時間戳需在伺服器 ±5 分鐘內，可用 ISO-8601 Instant；簽章為 per-system callback secret 對**原始 body bytes** 做 HMAC-SHA256，格式 `sha256=<hex>`。簽章不包含時間戳；不要自行加入時間戳串接。簽完後不可再格式化 JSON。body 上限 1 MiB，超過回 413。認證後仍檢查系統啟用、IP 與 callback action 權限。

<!-- example: POST /api/callback/{type} response 200 -->
```json
{"status":"ok","processInstanceId":"demo-process-001"}
```

同 systemId + deliveryId 的 24 小時 Redis 鍵存在時：

<!-- example: POST /api/callback/{type} response 200 -->
```json
{"status":"duplicate"}
```

duplicate 可能是正在處理或尚未 commit 的佔位，**不代表流程必已喚醒**。正常回滾會刪鍵；刪除失敗或 setIfAbsent 後行程硬殺，鍵可能留到 TTL，期間回 duplicate。必須對帳流程訂閱／案件進度，不能換 deliveryId 繞過未知結果。

| 結果 | 處理 |
|---|---|
| 400 | processInstanceId／deliveryId 必須非空字串，variables 若提供必須是物件；修正 payload |
| 401 | 修正三標頭、secret、時間窗、原始 bytes 簽章 |
| 403 | 修正系統／IP／action 授權 |
| 404 | 查無等待中的訂閱；核對 type、案件進度與是否尚未抵達等待點 |
| 409 | 同案件有多筆相同訊息訂閱；先處理 BPMN 關聯歧義 |
| 503 | Redis 冪等許可不可用，fail-closed；恢復後以相同 deliveryId 重試 |
| timeout | 保留相同 deliveryId 與內容，更新有效時間戳；先對帳，重送仍可能回佔位 duplicate |

認證 filter 的失敗 body 與框架錯誤不同，例如 HTTP 401：

<!-- example: POST /api/callback/{type} response 401 -->
```json
{"error":"Authentication failed","status":401}
```

依據：[CallbackController](../../bpm-core/src/main/java/com/bpm/core/external/CallbackController.java)、[CallbackAuthFilter](../../bpm-core/src/main/java/com/bpm/core/external/CallbackAuthFilter.java)、[CallbackSignatureUtil](../../bpm-core/src/main/java/com/bpm/core/external/CallbackSignatureUtil.java)。

## 管理員 DLQ 重放

先處理消費者錯誤、確認目的 exchange／binding 存在、確認接收端去重，再以 ADMIN 身分呼叫 `POST /api/admin/dlq/replay?queue=bpm&max=1`。**沒有 dry-run；呼叫就重放**。queue 只接受 bpm／audit，對應 dlq.parking.bpm／dlq.parking.audit；max 預設 100，合法 1..1000。先少量觀察消費結果再擴大。

<!-- example: POST /api/admin/dlq/replay response 200 -->
```json
{"queue":"dlq.parking.bpm","replayed":1,"failed":0,"remaining":0}
```

目的地先取 origin 標頭，再取最舊 x-death，最後由 queue 對照 fallback，且避免投回 DLX 迴圈。重投後才 ack 原訊息；send／ack 之間斷線可能重送。沒有 publisher confirms／mandatory 保證，replayed 計數不等於消費端已成功，更不能保證失去 binding 時未丟訊息。

部分失敗可回 HTTP 200 的 failed 計數；remaining 是快照，取得不到可為 null。失敗會停止本輪，不要只看 HTTP 200 就整批再次 replay。400 修 queue/max；503 可表示開始連線失敗。發生 timeout 或非預期錯誤時先核對 parking queue、原目的 queue、消費者稽核及業務去重記錄，確定未完成範圍再重放；不要把 dead-letter 解讀為業務一定沒執行。

依據：[DlqAdminController](../../bpm-core/src/main/java/com/bpm/core/dlq/DlqAdminController.java)、[DlqReplayService](../../bpm-core/src/main/java/com/bpm/core/dlq/DlqReplayService.java)。

本文範例用於契約與操作順序說明，未宣稱正式 DB、RabbitMQ、Redis 或外部 provider 已通過驗收。環境驗證請使用 [測試指南](../README-testing.md)。
