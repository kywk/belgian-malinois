# 接手文件 — 2026-10-03 第六輪完成（#96＋#51＋#3＋#6＋currentTask taskId）

**寫給下一個接手的 PM Agent。** 撰寫時間 2026-10-03。
上一輪交接見 `docs/handoff/2026-10-02-round6-handoff.md`（**仍然有效**）、
`docs/handoff/2026-10-01-round5-handoff.md`、`docs/handoff/2026-10-01-round4-handoff.md`
（環境陷阱總表）、`docs/handoff/2026-09-30-round3-handoff.md`（授權三組政策、並行流程）。
本檔只寫 2026-10-03 這一輪的新事實；`docs/handoff/2026-10-02-next-pm-prompt-v2.md`
的內容已被本輪取代。

本輪五個工項各在獨立 worktree 並行（同時 5 個 subagent、上限 5），
全部經 PM 驗收、合併、跑完整套件與線上實測；worktree 與 feature 分支已全部清除。
⚠️ **本檔的「完成」都有 merge commit 可查。**

---

## 0. 現況

| | |
|---|---|
| 後端 | Spring Boot **3.5.16** + Flowable **7.2.0**（未升級），單一模組 `bpm-core` |
| 前端 | Vue 3.4 + Vite 5 + Element Plus |
| 測試 | 後端 **881**（`mvn clean verify`）、前端 **165**（Vitest） |
| `main` | merge `1d9841c` ＋ PM 收尾 `7c7094d`／`c730ec9` ＋ 本文件收尾 commit；已 push **`nsl`** |
| `github` remote | ⚠️ 仍指向無關公開 repo（`kywk/belgian-malinois`），使用者明示暫不處理。**永遠 `git push nsl main`，不要裸 push** |
| worktree | 全部清除（feature 分支亦已刪除） |
| 容器 | dev 容器運行中（bpm-core 8080、nginx 80、mssql/rabbitmq/redis/mailhog）；`acceptance-test.sh` **PASS 7 / FAIL 0** |

```bash
cd /Users/kywk/kywk/nanshan/greyhound
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d --build bpm-core mailhog
./scripts/seed-data.sh && ./scripts/acceptance-test.sh
cd bpm-core && mvn clean verify          # 容器停掉再跑
```

---

## 1. 本輪完成

| 工項 | commit / merge | 內容 |
|---|---|---|
| **#96 完成路徑通知收斂** | `2cf789d` → `7f88ac2` | 新增全域 `CompletionNotifyListener`（`FlowableConfig` 註冊）：HTTP 與外部 API 兩條完成路徑共用同一份通知判定；`TaskController` 移除直接呼叫（防雙發）。`ApplicantIdentityLookup` 抽出「自然人申請人」判定與催辦共用；`NotifyPublisher.isRevisionTask` 補件判定唯一一份 |
| **#51 DLQ 告警＋人工重放** | `7a1dcef` → `1d9841c` | `DLQ_MESSAGE`／`DLQ_REPLAY` 稽核、選配 email（`bpm.dlq.alert-recipients` 預設空）、`POST /api/admin/dlq/replay?queue=bpm\|audit&max=`（ROLE_ADMIN） |
| **#3 催辦開放系統受理人** | `643d37c` → `fd54160` | `ApplicantResolver.resolveApplicant(onBehalfOf, initiator)` 為三段規則唯一入口；`urgeTask` 對系統案件以受理人判定。自然人案件不受影響 |
| **#6 移除 HMAC fallback** | `d2836d9` → `283ca3d` | `WebhookConsumer` 的 `@Value` 無預設；屬性缺席即啟動失敗；dev 預設只在 base yml |
| **currentTask 補 taskId** | `134ab0f` → `0169505` | `ProcessController` 兩處與 `HistoryController` 的 `currentTask` map 補 `taskId`（與單筆詳情一致） |
| PM 收尾 | `c730ec9`／`7c7094d` | 合併後移除死碼 `TaskController.applicantOf` 與其注入；`WebhookHmacSecretValidator` javadoc 對齊；urgeTask javadoc 對齊 taskId 現況 |

### 1.1 本輪的驗證層次（每一項都做齊）

- Agent 自跑相關測試＋負向控制組（每項回報紅／綠各幾條與「綠的證明不了什麼」）。
- PM 讀 diff、合併，主樹 `mvn clean verify` **881 全綠**（兩次：合併 #96/#3/#6 後 862；全數合併後 881）。
- 前端 165 全綠（本輪未動前端，驗證基線不漂移）。
- 容器重建＋seed＋`acceptance-test.sh` PASS 7 / FAIL 0。
- 線上實測（PM 序列）：見 §8。

---

## 2. 本輪重要技術結論

### 2.1 #96：同一 command 的歷史變數查詢**不足**（實測）

`PROCESS_COMPLETED` 當下查歷史變數看不到同一 command 剛寫入的 `approved`／`rejected`
（還在 DbSqlSession 未 flush；歷史任務的 `end_time` 同理），照原設計會漏發通知。
解法：`TASK_COMPLETED` 當下把「完成關卡＋vars」存進 **`CommandContext` attribute**
（key 含 processInstanceId，隨 command 消亡），`PROCESS_COMPLETED` 取回同一份；
歷史＋execution 只作 fallback。**不用 ThreadLocal**（執行緒重用需清理、且掩蓋「兩事件同 command」）。
⚠️ `CommandContext` 是 Flowable 內部 API，升版是編譯期中斷、不是靜默失效。

### 2.2 #96：恰好一則的切分

- `TASK_COMPLETED` → `applicantEventFor(vars,false)`（只會得到 returned／rejected）。
- `PROCESS_COMPLETED` → **只有** `applicantEventFor(vars,true)=="process_completed"` 才發。
  寫成「非 null 就發」會讓 rejected 的單在結案時再收一則重複通知。
- 補件（`NotifyPublisher.isRevisionTask`）與 standalone 加簽（無 processInstanceId）略過。

### 2.3 #51：x-death 起源與「告警 vs 留存」的互斥

- 重放目的地取 `x-death` **最舊**一筆的 `exchange`＋`routing-keys[0]`（含 `dlx.exchange` 防呆）；
  缺 x-death 走 queue 對照 fallback（`dlq.bpm` 以 payload 有無 `__webhookUrl` 分辨 webhook／notify）。
- **RabbitMQ 3.13 會剝掉客戶端發布的 x-death** → 整合測試必須走真實死信路徑才能取得它。
- 重放是 **at-least-once**（重投成功→ack 之間斷線會再送一次），已在 javadoc 寫明。
- 🔴 **待裁決的取捨**：`DeadLetterConsumer` 正常返回即 ack，訊息離開佇列 ——
  「進 DLQ 即時告警」與「留存待人工重放」**互斥**。目前重放涵蓋
  「consumer 停用／服務中斷期間累積」的訊息（線上實測即用此方式，見 §8）。
  若維運政策是「死信必須留存」，需改 parking queue 或停用 dlq listener（新工項）。
- DLQ 佇列**沒有 DLX**：任何在 consumer 內拋出的例外＝無限 requeue 熱迴圈；
  告警三段（組 detail／稽核／寄信）全部 try/catch。

### 2.4 #3：三段規則共用、短路讀取必須保留

`resolve(execution)` 既有測試釘住「onBehalfOf 命中時連 initiator 都不讀」，
因此抽出 `applyRule(onBehalfOf, Supplier<String> initiator)`；`resolveApplicant` 是直接值入口。
催辦端：查無受理人 → 沿用 403／404 分流；權限中心故障 → 503；兩者都在取得冷卻許可與發送通知之前。

### 2.5 #6：`@Value` 無預設的契約測試

`ApplicationContextRunner` **不會把啟動失敗外拋**（要查 `context.getStartupFailure()`）；
測試含「不提供屬性→啟動失敗」「提供→啟動」「反思 annotation 不含 `:` 預設」三條。

---

## 3. 已知殘餘

| 殘餘 | 說明 |
|---|---|
| **#51 留存政策** | 見 §2.3；待使用者裁決（parking queue 或維運程序） |
| DLQ 告警 email | 只以 mock 單元測；測試 profile SMTP 指向死 port。`spring.mail` 未設 connection／read timeout，SMTP 掛住會占住 DLQ listener 執行緒（訊息堆積、不會熱迴圈） |
| 重放目的地 | 重投沒有開 mandatory／publisher confirms：原 binding 已刪除時會被靜默丟棄（javadoc 已記） |
| `remaining` | 是 basicGet 快照，有並行消費者時只是近似值 |
| probe 殘留 | `probe-96ext`（v1 卡在 `system:erp` 的單、v2 已完成）、已停用外部系統 `e2e-urge3`／`e2e-96`；與歷輪 `probe-*` 同類、無害、清不掉 |
| 沿用 | R-21（白名單空＝不限制）、R-24、timeout 替代（#23）、#70 Stage 5（`ExtensionElementPreservationTest` 先紅）、dev 庫歷輪 probe 殘留 |

---

## 4. 環境風險（新增，其餘見 round4／round6）

1. 🔴 **DLQ consumer 內不得拋例外**：`dlq.bpm`／`dlq.audit` 沒有 DLX，拋出＝無限 requeue。
2. **RabbitMQ 3.13.7 管理 API 沒有 queue pause**（`PUT/POST /api/queues/.../pause` 回 405）。
   要讓訊息留在 DLQ，做法是停掉 bpm-core 後再以
   `SPRING_RABBITMQ_LISTENER_SIMPLE_AUTO_STARTUP=false` 起探測容器（見 §8）。
3. **管理 API 的 `messages` 是統計值**（預設 5s 更新），剛發布後查會是舊值，等幾秒再查。
4. 沿用：`mvn clean verify`、`DOCKER_HOST` exports、dev overlay、seed 後第一次 acceptance 可能需跑第二次、
   外部 API 需 **同時帶 `X-API-Key` 與 `X-System-Id`**、`ipWhitelist` 要含 `192.168.117.1`、
   `PUT /api/tasks/{id}` 的 `variables` 是 list、`DELETE /api/admin/external-systems/{systemId}` 是軟停用（吃 **systemId** 不是 UUID）、
   `git commit` 會提交整個 index（提交前檢查 `git status --short` 第一欄）、
   `IntegrationTestBase.SERVLET_PORT` 單一 port（測試類別不可加 `@TestPropertySource`／`@Import`／`@DynamicPropertySource`）、
   探測 BPMN 的 UserTask 必須有 `flowable:formKey`（lint `formkey-required`），否則部署 400。

---

## 5. 已裁決與待裁決

### 5.1 本輪完成時採用的政策（使用者當時未及回覆，**若否決可調整**）

1. **#51 告警**＝ERROR log＋`DLQ_MESSAGE` 稽核＋**可選** email（預設不收件人＝不寄）。
2. **#51 重放權限**＝`/api/admin/**` 既有 **ROLE_ADMIN**（未動 SecurityConfig）。
3. **currentTask 補 taskId** 已做（只加欄位、未換催辦端點）。

### 5.2 待裁決

1. **#51 留存政策**（§2.3）：維持「告警即 ack」＋維運程序，或改 parking queue（新增小工項）。
2. **#23 Timer 政策**（沿用 2026-10-02 裁決）：boundary timer＋政策（通知誰、是否自動動作）需先確認才開工。

---

## 6. 建議的下一輪優先序

| 順序 | 工項 | 估時 | 備註 |
|---|---|---|---|
| 1 | **#23 Timer Event 超時處理** | 2d | ⚠️ 政策需先與使用者確認（boundary timer、通知誰、是否自動動作） |
| 2 | **#4 Call Activity** 或 **#21 Callback 接收端** | 3d | #21 的 token 簽發方式需先設計 |
| 3 | **#51 留存 follow-up**（若使用者要 parking queue） | 0.5–1d | 見 §2.3 |
| 4 | **#70 Stage 5**（Boot 4＋Flowable 8） | 22d | 照升級計畫；`ExtensionElementPreservationTest` 先紅 |
| 5 | 其他 ⬜／🟡 | — | 見 `docs/backend-development-backlog.md` |

**部署前檢查清單（沿用）**：權限碼 `bpm:external:revision`／`bpm:form:design`／`audit:log:read`
必須在真實權限中心指派；prod secrets（`OIDC_ISSUER_URI`、`GATEWAY_SHARED_SECRET`、`BPM_WEBHOOK_HMAC_SECRET`）；
`allowedProcessKeys` backfill（R-21／R-24）；#51 告警收件人（`BPM_DLQ_ALERT_RECIPIENTS`，選配）。

---

## 7. 統計

- 工項 **96**：✅ **61**、🟡 **14**、⬜ **21**，剩餘估時上限 **~84 人天**。
- 後端 **881**（`mvn clean verify`）；前端 **165**；`acceptance-test` PASS 7 / FAIL 0。

---

## 8. 線上實測明細（2026-10-03，PM 序列）

| 工項 | 實測內容與結果 |
|---|---|
| **#3** | 外部系統 `e2e-urge3` 發起 leave-approval（無 onBehalfOf）→ dir001（`bpm:external:revision` 持有人）催辦 **200** `{recipients:[mgr001]}`；mgr001（參與者）**403**；user002（無關）**404**；dir001 重複 **429**；MailHog 收到催辦信；`TASK_URGE` 稽核 operator=dir001。自然人案件＋dir001 → **404 且零 TASK_URGE**（不放寬） |
| **#96** | 部署探測 BPMN `probe-96ext`（單一 UserTask 指派 `system:e2e-96`）→ 外部 API 以 onBehalfOf=user001 發起 → 外部 API `PUT /api/external/tasks/{id}` 完成 → **user001 恰好 +1 封「您的申請已核准」**（改動前為 0）；HTTP 路徑完成 leave 案 → user002 **恰好 +1 封**（無雙發） |
| **#51 告警** | 對 `bpm.exchange` 注入投遞失敗的 webhook（TEST-NET 位址）→ 3 次重試後進 DLQ → `DLQ_MESSAGE` 稽核（真實 x-death：`bpm.webhook.queue/rejected`、exchange／routing key 齊全；**不含 payload**） |
| **#51 重放** | 停 bpm-core → 手動注入 2 筆至 `dlq.bpm` → 以 `SPRING_RABBITMQ_LISTENER_SIMPLE_AUTO_STARTUP=false` 起探測容器（0 consumers、佇列 2 筆）→ `POST /api/admin/dlq/replay?queue=bpm` **200 `{replayed:2, failed:0}`** → `bpm.notify.queue` 2 筆（fallback 路徑）→ 恢復正常服務 → 下游消費：**user001 +2 封**、dlq 清空、`DLQ_REPLAY` 稽核 `{replayed:2, fallbackUsed:2}`。授權：未登入 **401**／user001 **403**／queue=bad **400**／空佇列 **200 replayed:0** |
| **taskId** | `GET /api/process-instances` 的 `currentTask.taskId` 與 DB `ACT_RU_TASK.ID_` **逐字一致** |
