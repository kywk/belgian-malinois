# BPM 外部案件 v1 與第 4 項交接

## 第 3 項交付範圍

BPM 實作四個 server-to-server 入口。POST `/api/external/v1/process-instances` 接受凍結的投遞命令。GET `/api/external/v1/requests/{requestId}` 回 receipt。GET `/api/external/v1/process-instances/{id}` 及同 collection 的 `?businessKey=` 查權威狀態。欄位、四種 receipt 與錯誤處置沿用 [案件契約](../plan/contracts/staff-agent-v1/case-api.md) 和 [錯誤與驗收](../plan/contracts/staff-agent-v1/errors-acceptance.md)。

* 外部認證沿用 API key 輪替、IP、節流與 action 查核。專用 `agent-bff` 只能走 v1 外部案件路由。POST 接受白名單 HTTPS issuer 的 AGENT actor，保留 agentCode 與原 sub。BFF 和 owning service 負責驗證外勤 JWT、即時資格與案件操作權；BPM 不持有外勤 Bearer。BPM 的發起人為 `system:agent-bff`。每次查詢和重放重驗服務權限與流程範圍。
* Flyway core V10 建立 `bpm_case_binding` 與 `bpm_case_request`。binding 指定流程部署 ID 及主表 UUID／版號。驗證和啟動都採這些版本。變數必須登錄，表單須通過鎖版 UniForm DSL。首關現階段支援具名規則映射至一位內勤，目標需通過 STAFF ACTIVE 與 console/read/act/approval 查核，流程初始任務的實際 assignee 必須相符。
* 每個服務各自受 requestId、精確 businessKey 兩組 DB 唯一約束。businessKey 的合法區段不含斜線，故唯一鍵等價於案件四元組，BIN2 定序保留大小寫。以 hash v1 比對解析後的命令，物件排序、數值正規化，保留陣列順序。保存 actor、traceId、hash 及預先配置的引擎 attempt ID，完整表單快照寫入表單 DB，通過驗證的業務欄位亦可作為流程變數。
* 成功掛接流程與 CONFIRMED 位於同一核心交易。獨立表單交易若遇核心回滾會嘗試補償。不能證明結果、或已有同 owner 同鍵但缺 v1 關聯的舊流程時，保留 UNCERTAIN，封鎖兩個鍵。中斷後 PROCESSING 在查詢或重放時檢查，逾五分鐘且無成功交易會轉 UNCERTAIN；人工修復前不會自動再啟動。對帳時以 `attempt_id` 比對核心 Flowable 歷史及表單 DB 的 `process_instance_id`，保留紀錄供稽核。防重列沒有 TTL；封存必須保留唯一鍵。
* 啟動時保存狀態 revision 1，讀取時按案件加鎖，僅在對外狀態改變時增加 revision。該數字表示已保存的狀態快照，可跨服務重啟使用。核准、駁回與退回依綁定時保存的節點 ID 判讀。取消回 UNKNOWN／PROCESS_CANCELLED，無明確終點對照時回 UNKNOWN／RESULT_MAPPING_UNAVAILABLE。

## 2026-11-06 驗證紀錄

| 執行範圍 | 結果 | 日誌（本機） |
|---|---|---|
| 完整後端回歸，最後收尾修改前 | 1,704 項通過，0 失敗／錯誤／跳過 | `/tmp/bpm-case-backend.log` |
| `bash scripts/browser-e2e.sh --prepared` | 7 個 Chromium 案例通過（既有 6、內勤 SSO 1），Failsafe 18 項通過，BUILD SUCCESS | `/tmp/bpm-case-e2e.log` |
| 最後的表單欄位防繞過修改，`StaffIntegrationTest#externalRejectedInputAndUncertainRecovery` | 1 項通過。以 variables 送出 DSL 欄位得到 400，查該 requestId 得到 404 | `/tmp/bpm-case-field-final.log` |

## 可重跑的行為驗證

| 驗收範圍 | 證據 |
|---|---|
| WF-01／02／04～06／12／13 | `StaffIntegrationTest.simultaneousExternalDeliveryAndReconciliation` 透過 HTTP 同時送 20 次，驗一個 201、一筆引擎流程、同內容重放、衝突、跨 system 三種查詢隔離、停用後原 receipt 可讀。送件前已有新版流程與表單，以實際定義 ID、`_formVersions` 核對。 |
| WF-03／08 | `CaseCommandTest` 比對 dataJson key 次序、陣列順序、高精度小數尾數、數值等價和 actor 改變。外層與 dataJson 重複鍵、保留欄位、TALENT、非整數版號和 NaN 均拒絕。 |
| WF-07／10 | `externalRejectedInputAndUncertainRecovery` 涵蓋錯 issuer、混用憑證、超過 1 MiB、缺主表、未知 DSL 欄位，以持久化的 PROCESSING 紀錄模擬中斷並驗 202、Retry-After 及逾時轉 UNCERTAIN。此案例重建中斷後的資料狀態，沒有實際殺掉 JVM。 |
| WF-11／EV-01 | `compensationFailureRetainsAttemptForOperatorReconciliation` 以兩個 SQL trigger 注入核心確認及表單補償失敗，查兩個 DB 證實零流程、一筆待修表單、兩組鍵繼續防重。`technicalWorkflowRequiresExplicitOutcomeAndDetectsUnlinkedLegacyProcesses` 驗無主表、完成結果無 mapping 與兩筆同業務鍵的舊流程。 |
| EV-04 的 BPM 段 | `StaffBrowserE2eIT.browserLoginTodoApproveAndRevoke` 以機器 HTTP 建案，Chromium 走內勤 SSO／待辦／簽核，再以服務身分查同一流程的 APPROVED 與遞增 revision；另一筆待辦撤權後維持未完成。完整業務 outbox → agent → BPM → 外勤摘要仍由第 4 項驗收。 |

## 正式環境需提供

1. 以環境設定指定 `bpm.external.cases.actor-issuers` 清單及 `bpm.external.cases.staff-routes` 的規則 ID → canonical USERID。內勤橋接、AuthZ 憑證及 `bpm.staff.approval-permissions` 必須按[內勤指南](../api/staff-sso-integration.md)配置。
2. 註冊專用系統與金鑰，指定 IP、`allowedActions=["start_process","query_status"]`、實際流程 key 清單、空 worker topics／candidate groups、`allowOnBehalfOf=false`。只在 BFF server 的 secret 管理保存明文。
3. 由環境維運在 core DB 的 `bpm_case_binding` 寫入核准的 workflow_code、binding_version、caller_system、domain、workflow_role、definition_key、definition_id、form_id／form_version、routing_rule、approval_permission、enabled。依流程語意填 approved_end／rejected_end／returned_node。變更語意要新增版本。enabled 可關閉舊版的新送件。流程首關可引用 `${firstTaskAssignee}`，值由 BPM 提供。正式部署前仍需以實際內勤來源與授權驗收。
4. 本次測試使用隔離的資料庫、HTTP 身分／AuthZ 測試來源與合成流程。agent、業務 outbox、webhook、正式憑證的端對端驗收屬下階段。

## 可直接交給下一個 agent 的 prompt

> 進行第 4 項，實作 agent BFF 的外勤案件查詢與私有投遞／對帳。先讀 BPM 的 `docs/plan/contracts/staff-agent-v1/{case-api,errors-acceptance}.md`、`schemas.json` 和本交接。先在 BPM 工作目錄用 git status／log 確認第 3 項程式可用。主要修改 agent；若 owning service 需要補接點，先確認其案件、submission、角色及授權來源。
>
> 公開 GET `/v4/workflow-cases/{domain}/{applicationId}/submissions/{submissionId}/{workflowRole}` 使用已驗 issuer、audience、type=AGENT 的使用者身分。由 owning service 以 canonical agentCode 審核案件與 submission scope。只回摘要。私有投遞及兩個對帳入口依契約驗服務 key、caller/domain/workflow allowlist 和資源歸屬。BPM 出站憑證取自 server。固定 DTO 做 snake_case ↔ camelCase 轉譯，保持 variables 和 dataJson 原意。重送前重驗外勤資格、案件狀態與操作權，使用已保存的 submission、requestId 及原始語意內容。收到 202 遵守 Retry-After；timeout／畸形成功回應先查 receipt，再依規則查案件。UNCERTAIN 停止自動啟動。以 sourceRevision 更新該 submission 歷程。外勤簽核、TALENT 代換為 AGENT 和降級舊啟動端點均禁止。
>
> 覆蓋 BF-01～08、WF-09／14、EV-02～04。使用兩位相同 sub 而不同 agentCode 的外勤驗證案件隔離，涵蓋服務撤權、交叉 submission、關聯缺失、重送及 webhook 重複／亂序。跑 agent 後端及跨服務 E2E，以已保存 submission 經 BFF 送 BPM、內勤 SSO 簽核、外勤再查摘要。交付測試命令、通過數、正式設定缺口與仍未驗收的項目。若某業務只有 TALENT 送件，沿用 AGENT 合成驗收流程並明列阻礙。沿用上表已通過的 BPM 後端與瀏覽器驗證，針對第 4 項新增或受影響的路徑測試即可。保留現有工作區變更，暫不 push。
