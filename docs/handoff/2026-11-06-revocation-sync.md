# 第 5 項交接：撤權、準增員本人送件與狀態同步

基線為 BPM `201c2b9`、agent `b4e94141`、recruitment `1162a17`。第 3 項的鎖版與唯一鍵、第 4 項的 AGENT 與 Chromium 簽核回歸沿用各自紀錄。這次擴充 v1 的送件 actor，維持內勤簽核與外勤查詢的獨立入口。

## 實接與替身

| 路徑 | 本機實際驗收 | 正式環境尚需 |
|---|---|---|
| 內勤身分、組織、代理與 AuthZ | BPM 真實 RS256 驗章、directory／delegations 與 AuthZ SDK HTTP adapter，後端為可異動的 loopback fixture。SQL Server／Flowable 實際持有任務。 | SAML 金鑰與環境綁定、ACTIVE 人員與組織橋接、具起訖期間的代理權威來源、正式 AuthZ grant 與各流程 approval mapping、來源容量／新鮮度及稽核。 |
| TALENT 本人與 AGENT 查詢 | recruitment 真實 submit、交易、SQL Server、身分橋接 HTTP 消費端及 BFF。重啟測試以受信任 BFF headers 進本人入口，以同 sub 的兩枚簽章 AGENT JWT 走公開查詢。身分橋接、本人同意、附件證據是 fixture。 | 準增員 issuer/sub 至 BP、外勤 issuer/agentCode 至有效 manager account 的正式服務，現場登入簽發、正式 CMS／ECM、面談與聯徵的表單與政策版本。 |
| 流程、投遞與對帳 | recruitment worker 經 agent 私有 API → BPM external v1 → SQL Server／Flowable。agent 真實驗 raw-body HMAC，owner 重驗資格與 CAS。實際簽核回存原 submission。注入延遲、重複／舊通知、舊版查詢結果及第二筆的 202／UNCERTAIN。 | 每類型核准的 workflow／form 綁定、專用服務憑證、網路白名單、webhook 訂閱與秘密、告警與衝突處置流程。Redis／RabbitMQ／SMTP 測試容器支援 BPM 啟動。本次 webhook 投遞由測試端簽發。 |

## 驗收證據

- 以既有 JWT 驗調職、停權與再次簽核。舊組織待辦消失，已派任務保留；角色仍可讀但失去核決權回 403。代理到期對待辦、表單與操作生效。身分、AuthZ、代理來源錯誤維持 503。
- 真實 BPM 首次啟動後攔住 201，在 owner 收到確認前 SIGKILL recruitment JVM。新 JVM 連回同一獨立、已套 migration 的 SQL Server DB；原受理請求可重放。同一 requestId、businessKey 的 BPM 歷史筆數為 1。
- 同一 sub 的 AG000001 與 AG000002 先後查同一案件。實際更新候選人的 ManagerId 後，原管理人 404、新管理人 200。本人補件後，原 submission 簽核結果提升到 APPROVED@2；再次查到先前的 RETURNED@1 仍保留 APPROVED@2。新 submission 保有自己的投遞與狀態。
- 第二筆的 202 帶 4 秒 Retry-After，提前喚醒仍等到期限後才查。明確 UNCERTAIN 只查詢，無第二次 start。額外覆蓋 GET receipt 帶較長 Retry-After、同版異值、競爭 CAS 與舊資料相容。

2026-11-06 第 5 項共 43 個不同的 JUnit 案例通過：BPM 9（含跨服務重啟）、agent 23、recruitment 11，失敗／錯誤／略過皆為 0。最後單獨重跑的跨服務案例包括不合法 callback 原子拒絕、本人停權與舊件在新草稿期間禁止 claim，亦已通過。契約檢查另計 28 份 schema 與 49 正／32 反例。第 3、4 項其餘回歸沿用交接紀錄。

最後一次 SIGKILL 前後的 recruitment PID 為 `22058 → 22069`；原 requestId 為 `d2ebfd0c-8206-4a11-8ff2-663699633338`，processInstanceId 為 `9973bd33-934e-4a55-abb7-bcfb4e9abaa1`。SQL Server 保存的 businessKey 為 `recruitment/e28db2d8-8945-40a7-b8f8-5ece6ad88416/d2ebfd0c-8206-4a11-8ff2-663699633338/review`。Flowable 完成歷史一筆。

## 重跑範圍

agent 使用 Java 21，先執行 `mvn -o -ntp -B -Dtest=WorkflowCaseHttpTest test`。recruitment 使用 Java 25，執行 `mvn -o -ntp -B -Dtest=TalentProviderBoundaryTest,TalentPhase2IntegrationTest test`。跨服務測試從這兩份 Surefire XML 取得實際 classpath，需三個相鄰 checkout、Docker、Python 3 及環境變數 JAVA25_HOME（或腳本預設安裝位置）。

BPM 在 bpm-core 目錄使用 Java 21。下列命令只選第 5 項新增或受影響案例。腳本使用 Testcontainers 本輪 SQL Server 中的全新獨立 DB，測試結束即清理。

```sh
mvn -o -B -ntp \
  '-Dtest=StaffIntegrationTest#transferredOrInactiveHolderCannotSignWithExistingToken+expiredDelegationFreezesAssignedTaskEvenWithIndependentApprovalGrant+freshReadRejectsExpiredValidityOrStaleObservation+revocationPreventsWriteAndRegrantAllowsActualFlowableCompletion+todoFollowsOrgMoveAndDoesNotTrustOldOfficeClaim+authzDenyAndBrokenSourceRemainDifferent+talentSubmissionLocksActorAndCreatesExactlyOneStaffCase+externalRejectedInputAndUncertainRecovery+restartTalentOwnerAfterBpmCommit' \
  "-Dtalent.restart.script=$PWD/../../recruitment/scripts/validation/talent-workflow-restart.py" test
```

部署順序為 recruitment migration、BPM 與 agent 的 TALENT workflow／issuer 綁定，再開 recruitment worker。各方向的 key 分開。正式憑證、正式人員與真實組織／AuthZ 事件尚未進行環境驗收。參見[內勤整合](../api/staff-sso-integration.md)、[agent 介接](../../../agent/docs/features/workflow-cases/README.md)與[recruitment 接線](../../../recruitment/docs/features/organization-recruitment/workflow-sync.md)。
