# 第 6 項整合驗收與切換

範圍為準增員本人聯徵送件、內勤簽核，以及外勤管理人查詢同一筆 submission。基線為 BPM `5295d4b2`、agent `319480e2`、recruitment `3a738c2e`。瀏覽器使用獨立 checkout Flashlight `6b0cb241` 的既有頁面，測試前準備相鄰的三個 repo 及 recruitment/flashlight。

## 環境來源與放行條件

| 邊界 | 本機驗收接線 | 正式放行需具備 |
|---|---|---|
| 準增員與外勤前端 | Chromium 操作 Flashlight API 模式。host shell 注入測試 JWT 與瀏覽器 key。agent 套用真實 API key filter、RS256 middleware、業務 controller 和 owner HTTP adapter。管理人查詢另走 AGENT workflow 摘要與畫面上的遮罩歷程。 | 正式 shell／登入、準增員專用 issuer／audience／RSA 公鑰、外勤入口金鑰。legacy 外勤 JWT 的 Redis 公鑰查詢在本輪是 fixture。 |
| 業務所有權與投遞 | recruitment 真實 HTTP、SQL Server migration、快照／outbox／對帳 worker；TALENT 與 AGENT 即時資格來源為 loopback fixture。 | 權威 issuer/sub→BP、issuer/agentCode→有效管理帳號；正式表單、同意文件、效期政策與 ECM 證據。測試上傳實體 bytes；CMS／ECM 使用 dev/test provider。目前只有 dev/test evidence adapter；正式 CMS／ECM adapter 與表單／政策版本接線待實作，production 會拒絕送件。 |
| BPM 與內勤 | 真實 v1 HTTP、SQL Server、Flowable、Vue 內勤入口、SSO code 交換、驗章、任務持有守衛及核決，測試流程綁定一個已指派任務。SAML、STAFF 名冊與 AuthZ 決策使用 fixture。 | 真實 SAML IDP/callback、Org/主管/代理及 AuthZ、逐環境受理規則、核准的流程和表單版本。 |
| 結案回傳 | Flowable 完成事件經 RabbitMQ、BPM WebhookConsumer 到 agent HMAC 接點。測試轉送器另投一份錯簽副本，再原樣送出 BPM 簽發的 body 和 headers。SQL 輪詢刻意延後，由有效通知喚醒，再查權威狀態。 | 每環境設定業務 BPMN 的 process.completed webhook、允許目的地主機、專用 HMAC、網路 ACL、時鐘與 DLQ 告警。 |

正式切換狀態為 **待環境驗收**。不可用本機 fixture 作為正式服務地址。環境 owner 在 SIT/UAT 以核准的測試帳號、資料與流程重走同一條完整路徑。應記錄 requestId、businessKey、processInstanceId、revision、授權結果與來源；憑證由 secret 管理提供。

第 6 項原驗收操作聯徵（CREDIT_CHECK）；本輪追加面談（INTERVIEW）的專屬流程、送審表單與簽核權限。兩類案件正式切換前仍須各自完成環境驗收。SMTP 使用 MailHog，BPM 的 Redis 使用測試容器；本次驗收的結案通知走 RabbitMQ 與 HTTP webhook。

## 啟用與回退

1. 部署 recruitment migration，先在所有提供 submit 的實例設 `app.talent.workflow.intake-paused=true`。讀回草稿確認 `can_submit=false`；以新 idempotency key 嘗試送件應回 503，先前成功的相同命令仍能取得原 receipt。保留舊版本與所有不可變 binding。
2. BPM 設 `bpm.staff.enabled=true`、`VITE_STAFF_SSO=true`，配置 SAML、Org、AuthZ 與服務帳號。`bpm.external.mock-enabled=false` 須配套真實 Org/Perm URL。為核准的 TALENT workflow 綁定本人 issuer、鎖版流程／表單、內勤受理者及 approval permission。agent-bff 限 start_process、query_status，配置獨立服務與 webhook 憑證。見[內勤設定](../api/staff-sso-integration.md)。
3. agent 配置 `RECRUITMENT_TALENT_ISSUERS`、`RECRUITMENT_TALENT_AUDIENCE`、`RECRUITMENT_TALENT_PUBLIC_KEY`，再部署新版。TALENT 與 AGENT 用分開的 issuer 與 key。Flashlight 以 `VITE_TALENT_DATA_SOURCE=api`、`VITE_ORGANIZATION_DATA_SOURCE=api` 建置，指向本環境 agent。設定 owner、agent、BPM 三方允許的 domain/workflow 與服務憑證。[agent 接線](../../../agent/docs/features/workflow-cases/README.md)
4. recruitment 設 `app.talent.workflow.enabled=true`，配置 identity bridge、雙向 key、正式 evidence provider 與各類型 bindings。先保留 intake-paused。來源驗證與下列故障矩陣經該環境驗收後，才開放新件（intake-paused=false），觀察該環境首筆 receipt、審核結果、webhook、待對帳/UNCERTAIN 數量。各服務設定需重啟，兩個前端環境開關需重建部署。[owner 開關](../../../recruitment/docs/features/organization-recruitment/workflow-sync.md)
5. 回退先將所有 owner 實例設 intake-paused=true（滾動更新時先於 ingress 暫停 **新送件** 或排空舊實例），保留 v1 worker、owner 接點、原金鑰與 binding。保留舊版相容讀取、草稿、歷程和 receipt。依 submission 對照兩庫，清點待辦、已派未簽、傳輸不明與衝突。UNCERTAIN 僅查 receipt／原 businessKey；由權責人員核對後處理。不得清空 outbox、改 requestId 或將 v1 列改送舊 adapter。若須立即停止傳輸，保持 intake-paused，再關 enabled，保存 DB。復原時以同一 binding／命令重新開 enabled，完成對帳與核對後再開 intake。正式環境維持拒絕策略；若實際降版至第 5 項或更早，舊 binary 不識別 intake-paused，且管理歷程缺新版的資格檢查。須持續在 ingress 封鎖兩個 review-history 入口與新送件（可直接封鎖全部 submit），清點所有對外路徑與實例；可驗證的新版恢復後才解除封鎖。本輪實測為同版切開關及更換 JVM。

在 recruitment DB 以唯讀方式清點 v1 案件。CONFIRMED 表示投遞成功，仍須依 ReviewJson 的案件狀態判定是否結案。

```sql
SELECT w.Phase, o.DeliveryStatus, COUNT_BIG(*) AS Total
FROM TALENT_WORKFLOW_SYNC w JOIN TALENT_BPM_OUTBOX o ON o.SubmissionId=w.SubmissionId
GROUP BY w.Phase, o.DeliveryStatus;

SELECT w.SubmissionId, o.BusinessKey, o.ProcessInstanceId, w.Phase, o.ReviewJson
FROM TALENT_WORKFLOW_SYNC w JOIN TALENT_BPM_OUTBOX o ON o.SubmissionId=w.SubmissionId;
```

## 驗收與故障矩陣

| 場景 | 可觀察的結果 | 證據範圍 |
|---|---|---|
| TALENT 本人送件與鎖版 | Flashlight 建稿、上傳、同意、正式送件；receipt 可重放，Flowable 同鍵歷史唯一，最終 APPROVED@2。 | 第 6 項 Chromium 與 SQL Server |
| 暫停、排空與復原 | 緊急停止 v1 傳輸時 owner 回 401、原列保留。owner 換 JVM 後，同命令回原 submission，新送件回 503 且按鈕停用；既有案件可完成。重新開受理後草稿恢復可送，原件與 outbox 各一。 | 第 6 項 Chromium 與 SQL Server |
| 簽核撤權及恢復 | 已開啟的同一任務先被拒 403，重新授權後原內勤 session 可簽；準增員及外勤管理人以各自頁面查到同一結果。 | 第 6 項 Chromium |
| 兩種身分混用、錯 audience／簽章／過期 | AGENT 進 TALENT 入口遭拒；TALENT 讀 AGENT 摘要遭拒；臨時帳號錯誤的 token 在 controller 之前遭拒；有效 TALENT JWT 僅可進本人路徑。管理人以原 JWT 在停權後重開管理頁，兩個歷程端點均拒絕。 | 第 6 項瀏覽器、agent JWT 測試 |
| Webhook 錯簽與有效送達 | 錯簽 401，有效送達 204；喚醒已刻意延遲的 SQL 列並取得權威 revision。 | 第 6 項實際完成事件 |
| 斷線、重派、重複／亂序、權威來源故障 | 首次 BPM commit 後 SIGKILL、Retry-After、UNCERTAIN 停送、同 sub 移轉、舊件隔離及撤權後拒簽見[第 5 項](2026-11-06-revocation-sync.md)。併發冪等、版本衝突、跨 system、錯 key／未授權、補償失敗見[第 3 項](2026-11-06-external-cases.md)與[第 4 項](../../../agent/docs/features/workflow-cases/README.md)。 | 沿用前項證據；本輪只重跑受影響範圍 |

2026-11-06 定向驗收共 **51 個不同的 JUnit 案例通過**，失敗、錯誤、略過皆為 0。agent 41 個（JWT 邊界 5、本人與歷程 13、workflow HTTP 23），recruitment 7 個（provider 邊界 3、業務整合 4），BPM 3 個（TALENT 鎖版與單案、worker 強制重啟、跨服務 Chromium 各 1）。Chromium 已計入 BPM。

最後一輪 Chromium 以 `recruitment/40bb2096-c6bc-4a53-825b-2b31b74dec8c/98c5f48a-9ca5-4949-be79-cc5d3cfad20e/review` 串起兩庫。BPM 的 requestId 與 traceId 都是原 submission `98c5f48a-9ca5-4949-be79-cc5d3cfad20e`，服務為 `agent-bff`，actor 為本人 `TALENT`。Flowable 唯一歷史案件 `896fd8f5-81ed-4368-9d25-a10cba1a1991` 已結束。暫停期間核准後，外勤管理人的遮罩歷程和本人頁面都讀到 `APPROVED`；停權後原管理人 JWT 查詢兩個歷程入口均回 403。

本輪另重跑第 5 項的提交後斷線案例。recruitment worker PID 從 `61135` 換成 `61162`，以原 requestId `52800d98-b4ce-4195-8073-15efec4c22e0` 恢復對帳。Flowable 僅有一筆已完成的歷史案件 `1e87fc6f-b1d6-4ffc-bc2d-2cddbd7b4c7a`。

## 定向重跑

準備 Docker、Java 21／25、Node 24、Python 3、BPM 的 UniForm 子模組與專案 Maven 相依。兩個前端須安裝鎖定的 npm 相依，BPM 的 Playwright 須已安裝相符 Chromium。以下從 recruitment 根目錄執行；`JAVA21_HOME` 與 `JAVA25_HOME` 指向對應 JDK，且 `JAVA25_HOME` 須 export。前兩條同時產出 runner 用的 Surefire XML classpath；缺少報告時不能跳過。測試使用各自配置的本機臨時埠和獨立 DB，對正式環境沒有寫入步驟。

```sh
export JAVA25_HOME
(cd ../agent && JAVA_HOME="$JAVA21_HOME" PATH="$JAVA21_HOME/bin:$PATH" mvn -o -ntp -B \
  -Dtest=TalentJwtBoundaryTest,WorkflowCaseHttpTest,TalentApplicationTest test)
(JAVA_HOME="$JAVA25_HOME" PATH="$JAVA25_HOME/bin:$PATH" mvn -o -ntp -B \
  '-Dtest=TalentProviderBoundaryTest,TalentPhase2IntegrationTest#persistenceVersionEvidenceAndLegacyIsolation+concurrentSubmitAndCreateAreIdempotent+missingAndMismatchedConsentOrUploadNeverSucceed+routesRejectSharingAndAgentActorsAndUnknownFields' test)
(cd ../bpm/bpm-core && JAVA_HOME="$JAVA21_HOME" PATH="$JAVA21_HOME/bin:$PATH" mvn -o -ntp -B \
  '-Dtest=TalentCutoverBrowserIT#talentSubmitStaffApproveAndManagerReadDuringDrain+interviewSubmitStaffApproveAndManagerRead' test)
(cd ../bpm/bpm-core && JAVA_HOME="$JAVA21_HOME" PATH="$JAVA21_HOME/bin:$PATH" mvn -o -ntp -B \
  "-Dtalent.restart.script=$PWD/../../recruitment/scripts/validation/talent-workflow-restart.py" \
  '-Dtest=StaffIntegrationTest#talentSubmissionLocksActorAndCreatesExactlyOneStaffCase+restartTalentOwnerAfterBpmCommit' test)
```

瀏覽器以三種身分分開操作。跨服務 IT 的輸出見 `bpm-core/target/interview-cutover.log`（面談）、`bpm-core/target/talent-cutover.log`（聯徵） 及 Surefire 報告。第 5 項重啟案例只因共用啟動程式和驗章路徑有異動而定向重跑，其餘歷史紀錄沿用。

## 面談追加驗收

本輪自第 6 項 BPM `4d553f21`、agent `76b13983`、recruitment `e0a3e5a3` 開始。面談與聯徵分開執行，每個情境建立獨立 recruitment DB。agent 測試 servlet 只開放該情境的一個 workflow。

- **本機實接**　Chromium 操作 Flashlight 的面談表單，填寫基本資料、10 題自評與 12 筆面試回答，保存 PNG 照片、接受面談同意書後送件。recruitment 以 SQL Server 保存送件快照、outbox 和對帳結果；agent 經 BPM v1 啟動 Flowable。BPM 內勤頁使用 SSO code 交換及 RS256 驗章。內勤核決後，由 RabbitMQ 送出實際 HMAC webhook；準增員本人與有效管理人分別讀回結果。
- **測試來源**　兩個入口使用合成帳號及簽章金鑰；SAML、Org、AuthZ、本人／外勤資格對照、agent 的 Redis 公鑰讀取均為 fixture。面談快照使用 dev 表單、同意書及附件 provider。BPM 部署專屬驗收流程 `recruitment-interview-review`、面談送審單 v1 與 `bpm:approval:recruitment_interview_review:approve`。BPM 表單只帶 submissionId，完整問答和照片歸 recruitment 保存。
- **正式環境缺口**　仍需權責單位提供核准的面談 BPMN／表單及權限映射、身分與組織來源、CMS 同意文件、ECM 儲存、面談效期政策與環境憑證。正式 UAT 與效期計算待這些來源就緒。

本輪面談的 requestId／traceId 為 `1ff515d2-b8b9-4a52-85fc-aa14faa8c2c4`，businessKey 為 `recruitment/36a9c43a-8ee2-49a0-bbbb-8eb0c106340e/1ff515d2-b8b9-4a52-85fc-aa14faa8c2c4/review`，唯一已完成的 processInstanceId 為 `6ad790fd-a3ed-4dce-972f-43bd3d69ed5d`。內勤頁取得的 task/form 是面談 schema v1，顯示「面談送件編號」，回應內的 submissionId 與本人提交相同。只撤面談核決權時簽核回 403，恢復該權限後核准，兩端查到同一結果。

**修正前的通知欄位（修正結果見下節）。** 面談 definitionId 是 UUID。結案 log 記錄的流程鍵為 `1df5c0dd-d9bc-11f1-a9bf-a2c37304dde7`。[ProcessCompletedListener](../../bpm-core/src/main/java/com/bpm/core/webhook/ProcessCompletedListener.java) 對不含冒號的 definitionId 直接回傳原值，並將其寫入 payload.processDefinitionKey 及 Rabbit routing key。本輪的 `bpm.webhook.#` 能收到通知，agent 也以 businessKey 與 processInstanceId 完成權威對帳。只訂閱 `bpm.webhook.recruitment-interview-review` 的消費端有漏收風險，應在正式切換前修正並驗證。

本輪只執行 3 個相關 JUnit 案例，全部通過，失敗、錯誤、略過均為 0。新增面談 Chromium 1 個，共用 runner 的聯徵 Chromium 1 個，共用 owner 啟動設定及撤權 fixture 的 worker 強制重啟案例 1 個。聯徵 processInstanceId 為 `b37e667c-872c-4642-ab90-0b5aa53b1031`；重啟案例原 requestId 為 `387ce75f-904e-4a96-8891-9999acc16476`。沿用第 6 項其餘測試結果與兩份 Surefire classpath；本輪僅以 `mvn -o test-compile` 重編 agent 測試橋接程式。重跑這一輪時，使用前述雙 Chromium 選擇器，worker 部分只選 `StaffIntegrationTest#restartTalentOwnerAfterBpmCommit` 並傳入相同的 `talent.restart.script`。

## 結案通知流程鍵修正

本輪基線為 BPM `github/main 718fcb0c`、agent `origin/codex/recruitment 2bc05df6`、recruitment `origin/codex/recruitment 447ff75c`。在 BPM 基線用 UUID 執行 listener 的 `sendsContractFields`，攔到的 Rabbit routing key 是 `bpm.webhook.12345678-1234-1234-1234-123456789abc`，payload 的 `processDefinitionKey` 也是該 UUID。修正後從結案 execution 取得該部署定義的流程鍵，用於結案稽核、payload 與路由。依實例的 definitionId 載入 BPMN，再按該鍵選取對應流程的 webhook 設定。

**本機實接。** 每案在送件前宣告獨立 RabbitMQ queue，只綁定表列 routing key。Chromium 核准後，由該 queue 取出 `process.completed`，逐欄核對 payload 的流程鍵、原 submission 的 businessKey、以及 Flowable 唯一已結案的 processInstanceId。另一份訊息由既有 WebhookConsumer 簽發，agent 用收到的原始 HTTP body 重算 HMAC-SHA256。兩案錯簽均回 401，原簽均回 204。在 SQL 輪詢已延後的條件下，原 submission 經通知喚醒對帳，管理人查得 `APPROVED@2`。

| 案型 | 實收 routing key | 部署 definitionId | businessKey | processInstanceId |
|---|---|---|---|---|
| 面談 | `bpm.webhook.recruitment-interview-review` | `2ebdd70a-d9bf-11f1-8019-8e18c697c7b0` | `recruitment/3655ed2b-f903-406f-9274-331ab4870ecc/076d11ce-1030-406c-a67f-6ed9af36280d/review` | `62bd336a-a5e8-4d19-a527-331f4a549fba` |
| 聯徵 | `bpm.webhook.bff-contract-review` | `bff-contract-review:1:5ffbe293-d9bf-11f1-8019-8e18c697c7b0` | `recruitment/1b8909e2-e94b-4e85-a929-2ec74ee1d0ea/dda0162b-9a65-44f6-b5e8-9d93f169f63f/review` | `3d1023f3-82a1-4462-bff4-a3fba083a4ef` |

面談 payload 的流程鍵為 `recruitment-interview-review`，聯徵為 `bff-contract-review`。聯徵的 definitionId 仍是含版本的冒號格式，原有訂閱與 HMAC 接點均驗收通過。

**Fixture 與適用範圍。** queue 綁定與部署的兩份 BPMN 為驗收設定，BPM 與 agent 使用測試 HMAC 金鑰，HTTP relay 額外送出錯簽副本。本人／管理人資格、SAML、Org、AuthZ、agent 的 Redis 公鑰讀取、表單與附件來源沿用上節 fixture。SQL Server、Flowable、RabbitMQ、agent 驗章與 recruitment 對帳均在本機實跑。正式切換仍待 SIT/UAT 的流程、身分來源與憑證驗收。

**定向重跑。** 使用上節的雙 JDK、Node 與既有 Surefire classpath。在 BPM 的 `bpm-core` 執行以下選擇器。本輪 19 例通過，失敗、錯誤、略過皆為 0，涵蓋 listener 投遞 6 例、流程層設定單元測試 5 例、已部署設定讀取 6 例、跨服務 Chromium 2 例。瀏覽器案例的 Surefire 輸出含兩案識別碼及實收 routing key。

```sh
mvn -o -ntp -B \
  '-Dtest=ProcessCompletedListenerTest$Wiring,WebhookConfigResolverTest$ProcessLevel,ProcessWebhookWiringTest$Resolution,TalentCutoverBrowserIT#interviewSubmitStaffApproveAndManagerRead+talentSubmitStaffApproveAndManagerReadDuringDrain' test
```

## 面談與聯徵 SIT/UAT 驗收清單

**本機基線。** BPM `6898cf97` 已用 Chromium、SQL Server、Flowable、RabbitMQ、agent HMAC 驗章與 recruitment worker 跑通兩案。各案的精確 queue 都收到結案事件，原 submission 均對帳至 `APPROVED@2`。登入帳號與金鑰、權威資格與授權來源、CMS／ECM provider、驗收 BPMN 與 queue 綁定均使用測試配置。前節的鍵值與案件識別碼只供重現本機結果。

**填寫方式。** 共用接線每環境驗一次；兩份 binding 分別核定，再逐案執行端到端驗收。負責單位按系統職責列示，由環境 owner 指定實際窗口。在 SIT、UAT 空格分別填入核定值或設定單連結、證據連結、執行人及結果；憑證僅填 secret 參照。未確認的值與尚未執行的結果留白。

### 共用接線

| 項目 | 負責單位 | 要取得的證據 | 通過條件 | SIT | UAT |
|---|---|---|---|---|---|
| 本人與外勤登入 | 登入平台、Flashlight、agent | 兩種身分的 issuer、audience、公鑰來源、瀏覽器 key 參照、API 模式建置與登入紀錄 | 本人與管理人能從環境入口登入；agent 以各自設定驗章，外勤 legacy 路徑可讀環境 Redis 公鑰；混用身分或錯 audience 遭拒。 | | |
| 本人資格與管理歸屬 | IDM、外勤資格來源、recruitment | 含觀測時間與有效期限的資格回應、候選人當前管理歸屬及查詢結果 | TALENT 的 issuer/sub 對到本人 BP；AGENT 的 issuer/agentCode 對到有效管理帳號。停權或移轉後舊管理人無法讀取，來源中斷時回 503。 | | |
| 同意文件與附件 | CMS、ECM、recruitment | 環境 adapter 部署紀錄、CMS 文件版本、ECM 上傳與讀回紀錄、submission 證據引用 | 本人看到並同意的版本可追溯；附件可依 BP、案件、用途、版本與雜湊驗回。缺少必要證據時送件遭拒。目前正式 adapter 與版本接線待實作。 | | |
| 內勤登入 | SAML、STAFF 身分橋接、BPM | IdP issuer／audience／kid／公鑰參照、callback 白名單、一次性 code 交換及 STAFF 身分查詢紀錄 | 由 SSO 登入工作台，BPM 驗章並確認有效在職身分與 console 權限；停權回 403，權威來源不可用回 503。 | | |
| 待辦與核決授權 | Org、AuthZ、BPM | 實際個人／組織 grants、受理人與任務持有人紀錄；流程使用主管或代理時，附主管鏈或有效代理紀錄 | 關閉 mock 後仍能按核准路由派件，寫入前重查任務持有及該流程核決權。依賴主管／代理的流程須能查到權威來源，或取得業務核准的明確受理人／群組。 | | |
| 服務間呼叫 | recruitment、agent、BPM、網路維運 | 三方 domain／workflow 白名單、HTTPS 端點、分方向憑證參照、允許與拒絕的呼叫紀錄 | recruitment ↔ agent 可按方向驗證，agent-bff 對 BPM 僅能啟動與查詢核准流程；錯 key 或越權呼叫遭拒。 | | |
| 結案通知通道 | BPM、RabbitMQ 維運、agent | webhook 目的地主機白名單、ACL、HMAC secret 參照、時鐘與 DLQ 告警演練紀錄 | BPM 可送達環境 agent，來源受 ACL 限制；agent 能對原始 body 驗章，測試死信可觸發維運告警。逐案 routing key 另於下表核對。 | | |

### 面談 binding（INTERVIEW）

本機驗證的 workflowCode 為 `recruitment.interview`。

| 待核定項目 | 負責單位 | 要取得的證據 | 通過條件 | SIT 核定值／證據 | UAT 核定值／證據 |
|---|---|---|---|---|---|
| Workflow 與部署版本 | 面談業務、BPM、recruitment、agent | 核准 BPMN、workflowCode、bindingVersion、processDefinitionId、processDefinitionKey、本人 issuer | 三方允許面談 workflow；owner 凍結的啟動命令與 BPM 部署版本一致。 | | |
| 面談表單、題庫與照片 | 面談業務、recruitment、BPM、ECM | 本人表單與題庫版本、照片用途、BPM 送審表單 ID／版本及欄位對照 | 面談快照可還原核准題目、回答與照片引用；內勤開到鎖版表單，submissionId 與本人送件相同。 | | |
| 同意與效期 | 面談業務、CMS、recruitment | 面談同意文件 ID／版本、效期政策版本、起算點、到期邊界及核准範例 | 送件保存同意版本；以核准範例驗算面談有效性及到期日，結果吻合。 | | |
| 受理與核決 | 面談業務、Org、AuthZ、BPM | 面談受理人／群組、流程鍵對應的 approval permission、核准結果映射 | 待辦送達核准對象；撤除面談核決權即拒簽，恢復後可完成同一任務。 | | |
| 結案訂閱 | BPM、RabbitMQ 維運、agent | 部署版本的 process.completed webhook、目的地、queue 名稱及精確 routing key | queue 只綁 `bpm.webhook.<核准面談流程鍵>`，實收事件與該面談案件一致。 | | |

### 聯徵 binding（CREDIT_CHECK）

本機驗證的 workflowCode 為 `recruitment.credit`。

| 待核定項目 | 負責單位 | 要取得的證據 | 通過條件 | SIT 核定值／證據 | UAT 核定值／證據 |
|---|---|---|---|---|---|
| Workflow 與部署版本 | 聯徵業務、BPM、recruitment、agent | 核准 BPMN、workflowCode、bindingVersion、processDefinitionId、processDefinitionKey、本人 issuer | 三方允許聯徵 workflow；owner 凍結的啟動命令與該環境部署版本一致。 | | |
| 聯徵表單與報告 | 聯徵業務、recruitment、BPM、ECM | 正式申請欄位與表單版本、聯徵報告附件要求、BPM 送審表單 ID／版本 | 本人端依核准欄位收件；報告可從原 submission 核對；內勤開到鎖版聯徵表單並讀到同一 submissionId。 | | |
| 同意與效期 | 聯徵業務、CMS、recruitment | 聯徵同意文件 ID／版本、聯徵效期政策版本、起算點、到期邊界及核准範例 | 送件保存聯徵同意版本；有效性及到期日符合該案核准範例。政策核定前保留未知效期。 | | |
| 受理與核決 | 聯徵業務、Org、AuthZ、BPM | 聯徵受理人／群組、流程鍵對應的 approval permission、核准結果映射 | 待辦送達核准對象；撤除聯徵核決權即拒簽，恢復後可完成同一任務。 | | |
| 結案訂閱 | BPM、RabbitMQ 維運、agent | 部署版本的 process.completed webhook、目的地、既有消費端的 queue 名稱及精確 routing key | 消費端以 `bpm.webhook.<核准聯徵流程鍵>` 收到該案通知，原 HMAC 接點驗章成功。 | | |

### 下一輪執行與放行

按順序執行。開始步驟 2 前先建好該案的精確 queue，步驟 3 核准前先延後測試案件的 SQL 輪詢。四個結果欄各自留存對應案件的證據。SIT 通過後，UAT 以該環境的核准帳號、資料與 binding 重走兩案。

| 步驟 | 負責單位 | 要取得的證據 | 通過條件 | SIT 面談 | SIT 聯徵 | UAT 面談 | UAT 聯徵 |
|---|---|---|---|---|---|---|---|
| 1. 部署並暫停一般新件 | 環境維運、recruitment、BPM | 部署版本、migration 紀錄、各實例開關、草稿與送件回應、已受理案件的 receipt | 所有實例開啟 worker 並設 intake-paused；草稿可讀、can_submit=false、新命令回 503，重放已受理命令可取回原 receipt。 | | | | |
| 2. 本人送件與重放 | 業務驗收、Flashlight、recruitment | 在受控入口或驗收時段建立的 submissionId、requestId、traceId、businessKey、快照版本、outbox、BPM receipt | 同一命令重放得到原 submission；依完整 businessKey 只查到一筆 Flowable 實例，receipt 與該案鎖版資料一致。 | | | | |
| 3. 內勤撤權與恢復 | 業務驗收、AuthZ、BPM | task／form、持有人、撤權與恢復決策、同一任務兩次簽核回應 | 已開啟任務撤權後簽核回 403，原任務保留；恢復後由持有人在同一任務核准。 | | | | |
| 4. 精確收件與驗章 | BPM、RabbitMQ 維運、agent | 送件前建立的精確 binding、實收 routing key 與 payload、Flowable 歷史實例、錯簽及原簽 HTTP 狀態 | process.completed 的流程鍵等於該案核准鍵，businessKey 與 processInstanceId 均對到原案；錯簽回 401、原簽回 204。 | | | | |
| 5. 原件對帳與查閱 | recruitment、agent、業務驗收 | 延後輪詢及 webhook 喚醒紀錄、權威查詢回應、兩庫同案識別碼與 revision、本人及管理人頁面 | 核准案例由通知喚醒後重查 BPM，原 submission 更新為 APPROVED@2；本人與當前有權管理人讀回同一結果，跨人或已停權者無法讀取。 | | | | |
| 6. 排空、復原與放行 | 環境維運、recruitment、BPM、兩案業務窗口 | 暫停與 worker 重啟紀錄、重複／亂序通知、UNCERTAIN 清點、兩庫複核清冊與逐案簽認 | 暫停期間舊件可結案；重啟沿用原鍵，舊事件不覆寫新狀態；UNCERTAIN 停止自動派送並按原鍵查證。兩案均簽認後才開放一般新件，觀察首件 receipt、結案與待對帳數量。 | | | | |
