# 接手文件 — 2026-10-03 Wave A 完成（#23＋#1＋#7＋#51 parking）

> ⚠️ **同日稍晚另有 Wave B（#4／#21／#5），見
> `docs/handoff/2026-10-03-round9-handoff.md`（最新）。** 本檔內容仍然有效。

**寫給下一個接手的 PM Agent。** 撰寫時間 2026-10-03（同日稍晚，接續 round7）。
上一輪交接見 `docs/handoff/2026-10-03-round7-handoff.md`（**仍然有效**：
#96／#51 告警／#3／#6／taskId 與環境陷阱）、`2026-10-02-round6-handoff.md`、
`2026-10-01-round4-handoff.md`（環境陷阱總表）、`2026-09-30-round3-handoff.md`
（授權三組政策、並行流程）。本檔只寫 Wave A 的新事實。

本輪四個工項各在獨立 worktree 並行（4 個 subagent），全部經 PM 驗收、合併、
完整套件與線上實測；worktree 與 feature 分支已全部清除。

---

## 0. 現況

| | |
|---|---|
| 後端 | Spring Boot **3.5.16** + Flowable **7.2.0**（未升級） |
| 前端 | Vue 3.4 + Vite 5 + Element Plus |
| 測試 | 後端 **927**（`mvn clean verify`）、前端 **165**（Vitest） |
| `main` | merge `dcf29d3`＋PM flake 修正 `ab050b4`＋文件收尾 commit |
| push | ⚠️ **`nsl` 主機在收尾時不可達**（`10.127.42.141` ping 0%）——本輪與 round7 的 commit 都在本機 `main`，網路恢復後 `git push nsl main`（**永遠不要裸 push**；`github` remote 仍指向無關公開 repo，使用者明示暫不處理） |
| worktree | 全部清除（feature 分支亦已刪除） |
| 容器 | dev 容器運行中；`acceptance-test.sh` **PASS 7 / FAIL 0** |

---

## 1. 本輪完成

| 工項 | commit / merge | 內容 |
|---|---|---|
| **#23 逾期提醒** | `2f8756e` → `4d6a6fd` | 非中斷式 boundary timer＋`timeoutNotifyDelegate`（`JavaDelegate`）→ `task_timeout` 通知（`NotifyPublisher`／`EmailConsumer`）。**只通知現任受理人、不自動動作**（使用者裁決） |
| **#1 `returnTo=initiator`** | `f124fb5` → `bb1e286` | complete 接受 `returnTo`（只認小寫）；purchase-approval gw2 新分支 → `revisionFromManager`；接上 `TASK_RETURN_INITIATOR` 稽核；變數每輪重寫防殘留；`returnTo` 列入 `PROTECTED_VARIABLES` |
| **#7 流程撤回** | `f9cf773` → `24a7f87` | `POST /api/process-instances/{id}/cancel`；申請人限定；已有完成任務 409；接上 `PROCESS_CANCEL` 稽核（fail-closed） |
| **#51 parking 留存** | `5c7531e` → `dcf29d3` | 告警後把死信 park 到 `dlq.parking.bpm`／`dlq.parking.audit`；origin 自訂標頭；重放只讀 parking |
| PM 收尾 | `ab050b4` | `DlqAlertReplayTest` 隔離背景通知重試噪音（全套件 flake） |

---

## 2. 本輪重要技術結論

### 2.1 #23：`getCurrentActivityId()` 不是 boundary event（實測推翻）

delegate 掛在 boundary 之後的 serviceTask 時，`currentActivityId` 是 **serviceTask**
（`ContinueProcessOperation` 走 sequence flow 時就換掉 currentFlowElement）。
實作改為：① delegate 直接掛 boundary executionListener 時直接用；② 掛下游節點時
由 **incoming sequence flow 反推**，恰好一個 boundary 才採用，零個／多個都 warn＋no-op。
中斷式（`cancelActivity=true`）**明確擋下**：同 command 任務列可能未 flush，
靠「查不到任務」擋不住。delegate 需 `@Lazy` RepositoryService／TaskService
（`BeanCurrentlyInCreationException`，與 ProcessCompletedListener 同一結構性循環）。

### 2.2 #1：`returnTo` 的變數生命週期

- 伺服器在**每一次** complete 都重寫 `returnTo`（非退回申請人時寫 `""`）：
  只在退回申請人時寫入的話，前一輪的 `initiator` 會殘留，讓下一輪一般退回被 gw2 誤判。
- `returnTo` 列入 `PROTECTED_VARIABLES`：否則可用 `variables` 繞過語意檢查與稽核判定。
- **ExternalApiController 不寫 `returnTo`**：若整條流程的中間關卡都由外部系統完成，
  殘留值理論上會影響 gw2；實務極窄（HTTP 路徑每次重寫）。BPMN 註解已標明責任在 TaskController。
- **「退到任意節點」未做**：`ChangeActivityStateBuilder` 會繞過閘道且不觸發
  `TASK_COMPLETED`（#96 的通知不會發），需另立設計工項。

### 2.3 #7：撤回的邊界

- 「第一節點尚未處理」＝**沒有任何已完成的歷史任務**；claimed 未完成仍可撤回（刻意）。
- 已結束／不存在／重複撤回一律 404；參與者非申請人 403（留 DATA_ACCESS）；非參與者 404。
- 系統案件（`applicantOf=null`）**不開放**——沒有 #3 對催辦那樣的裁決。
- ⚠️ TOCTOU：檢查與刪除之間有窗口（極端下可能刪到剛完成的案件），已記載未加鎖。
- ⚠️ 撤回**不通知**現任受理人（殘餘）。
- ⚠️ `reason` 無長度上限（超長 → DB 錯誤 → 500 且零副作用）。

### 2.4 #51 parking：修正前一輪的前提

- ⚠️ **RabbitMQ 3.13.7 不會剝掉客戶端重發布的 x-death**（3.13 起不再維護／更新，
  4.x 才不再解讀）。前一輪「會被剝掉」的記載已全面更正。
- origin 自訂標頭 `x-bpm-origin-exchange`／`-routing-key`／`-queue` 是**主要契約**
  （跨版本、明確），x-death 降為備援，queue 對照表最後。
- 重放來源＝`dlq.parking.*`；端點回應／稽核的 `queue` 值現在是 `dlq.parking.*`
  （欄位契約不變，若有外部 dashboard 以字串比對需同步）。
- parking 重發布失敗：只記 ERROR 後照常返回（訊息被 ack＝遺失）——「絕不熱迴圈」
  優先於「絕不遺失」；log 與 `DLQ_MESSAGE` 稽核都在。
- parking queue 無 TTL／無上限（會長大，靠重放或人工清理）；consumer 若永久停用，
  舊 `dlq.*` 訊息不會被告警也不會被 parking。

### 2.5 🔴 測試衛生：通知測試會製造背景 DLQ 流量（本輪踩到）

`NotifyTestSink` 是 topic **副本**，真實 `EmailConsumer` 仍會收到通知；測試環境 SMTP
不通 → 重試（1s／2s）→ 進 `dlq.bpm` → consumer parking。任何「精確計數」的 DLQ 測試
都會被這股遲到的背景流量污染（本輪 `DlqAlertReplayTest.remaining` 全套件下間歇紅、
單獨跑綠）。修法（已做）：該類別 `@BeforeEach` 停 `bpm.notify.queue`＋清 notify／
`dlq.bpm`／`dlq.parking.bpm`，`@AfterEach` 先清 notify 再恢復。
**下一個寫 DLQ／佇列精確斷言測試的人請照做。**

---

## 3. 已知殘餘

| 殘餘 | 說明 |
|---|---|
| #1 任意節點 | 未做；需 `ChangeActivityStateBuilder`＋通知語意設計（新工項） |
| #7 撤回通知 | 受理人不會收到「已撤回」信；`reason` 無長度上限 |
| #51 parking | 無 TTL／上限；parking 失敗＝訊息遺失（log＋稽核可補救）；consumer 永久停用時舊 DLQ 不 parking |
| #23 | 夾閘道的 boundary→delegate 反推不到（warn＋no-op）；`timeCycle` 重複 timer 每輪都提醒（無去重，政策未要求）；executionListener 掛法只有單元測試 |
| 沿用 | probe 殘留（`probe-23timer`、`probe-96ext`、歷輪 `probe-*`）、已停用外部系統、R-21／R-24、#70 Stage 5 |

---

## 4. 環境風險（新增，其餘見 round7／round4）

1. **RabbitMQ 3.13.7 管理 API 沒有 queue pause**（PUT／POST 回 405）——要讓訊息留在
   DLQ，用「停 bpm-core → 注入 → 以 `SPRING_RABBITMQ_LISTENER_SIMPLE_AUTO_STARTUP=false`
   起探測容器」；parking 上線後，正常路徑不必再這樣做。
2. **管理 API 的 `messages` 是統計值**（約 5s 更新），剛發布後查會是舊值。
3. 測試 BPMN 的 UserTask 需 `flowable:formKey`（lint `formkey-required`）；
   `serviceTask` 無 error boundary 只是 warning。
4. 沿用：`mvn clean verify`、`DOCKER_HOST` exports、外部 API 需 **`X-API-Key`＋`X-System-Id`**、
   `DELETE /api/admin/external-systems/{systemId}` 吃 **systemId**（不是 UUID）、
   `git commit` 提交整個 index、`IntegrationTestBase.SERVLET_PORT` 單一 port。

---

## 5. 已裁決與待裁決

### 5.1 本輪已裁決

1. **#23 政策**：逾期只通知現任受理人、不自動動作（使用者 2026-10-03 裁決）。
2. **#51 留存**：使用者選定 parking queue 方案。

### 5.2 待裁決／可開新工項

1. **#1「退到任意節點」**要不要做、怎麼處理通知語意。
2. **#7 撤回通知**（受理人收「已撤回」信）與 `reason` 長度上限。
3. **#51 parking 清理政策**（無 TTL；是否需要 purge 端點或上限）。
4. 下一輪主軸（見 §6）。

---

## 6. 建議的下一輪優先序

| 順序 | 工項 | 估時 | 備註 |
|---|---|---|---|
| 1 | **#4 Call Activity** 或 **#21 Callback 接收端** | 3d | #21 的 token 簽發需先設計 |
| 2 | **#5 代理人機制**（一般指派與既有任務自動轉派） | 2d | 目前只有 `resolveEffective` 與改派代理人 |
| 3 | **#22 External Worker Task**／**#24 Signal Event** | 3d／1d | 非同步主線 |
| 4 | #1 任意節點／#7 撤回通知等小項 | 1–2d | 需先裁決 |
| 5 | **#70 Stage 5**（Boot 4＋Flowable 8） | 22d | `ExtensionElementPreservationTest` 先紅 |

**部署前檢查清單（沿用）**：權限碼指派（`bpm:external:revision`／`bpm:form:design`／
`audit:log:read`）；prod secrets（`OIDC_ISSUER_URI`、`GATEWAY_SHARED_SECRET`、
`BPM_WEBHOOK_HMAC_SECRET`）；`allowedProcessKeys` backfill；#51 告警收件人（選配）。

---

## 7. 統計

- 工項 **96**：✅ **65**、🟡 **12**、⬜ **19**，剩餘估時上限 **~75 人天**。
- 後端 **927**；前端 **165**；`acceptance-test` PASS 7 / FAIL 0。

---

## 8. 線上實測明細（2026-10-03 Wave A，PM 序列）

| 工項 | 實測內容與結果 |
|---|---|
| **#7** | 申請人撤回剛啟動的 leave 案 → **200**、runtime 列表消失、`PROCESS_CANCEL` 稽核（reason 入 detail）；參與者（mgr001）**403**、無關者（user002）**404**；採購案主管核准後（有完成任務、仍在跑）→ **409** |
| **#1** | 採購案財務關卡 `returnTo=initiator`＋`approved=true` → **400 零副作用**；單帶 `returnTo=initiator` → **200**；任務落「**申請者補件（主管退回）**」給 user001；稽核 **`TASK_RETURN_INITIATOR`**（operator=mgr001） |
| **#23** | 部署 `probe-23timer`（5 秒非中斷 boundary timer）→ 啟動 → **恰 1 封「任務已逾時：逾時探測關卡」**給 mgr001；任務仍在、流程仍在跑（非中斷式的直接證據） |
| **#51** | 注入失敗 webhook（TEST-NET）→ 重試後 **`dlq.parking.bpm`=1、`dlq.bpm`=0**、`DLQ_MESSAGE` 稽核（真實 x-death）；加一筆合成通知訊息 → replay `queue=bpm` **`replayed:2, fallbackUsed:1`** → 通知成功送達（MailHog +1）、webhook 再失敗自動 re-park（parking 回到 1）；`DLQ_REPLAY` 稽核齊全 |
