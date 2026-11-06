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

這次操作的是聯徵（CREDIT_CHECK）。面談（INTERVIEW）使用同一送件與對帳機制，正式切換前仍須以該類型自己的流程、表單、文件與效期規則驗收。SMTP 使用 MailHog，BPM 的 Redis 使用測試容器；本次驗收的結案通知走 RabbitMQ 與 HTTP webhook。

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
  '-Dtest=TalentCutoverBrowserIT#talentSubmitStaffApproveAndManagerReadDuringDrain' test)
(cd ../bpm/bpm-core && JAVA_HOME="$JAVA21_HOME" PATH="$JAVA21_HOME/bin:$PATH" mvn -o -ntp -B \
  "-Dtalent.restart.script=$PWD/../../recruitment/scripts/validation/talent-workflow-restart.py" \
  '-Dtest=StaffIntegrationTest#talentSubmissionLocksActorAndCreatesExactlyOneStaffCase+restartTalentOwnerAfterBpmCommit' test)
```

瀏覽器以三種身分分開操作。跨服務 IT 的輸出見 `bpm-core/target/talent-cutover.log` 及 Surefire 報告。第 5 項重啟案例只因共用啟動程式和驗章路徑有異動而定向重跑，其餘歷史紀錄沿用。
