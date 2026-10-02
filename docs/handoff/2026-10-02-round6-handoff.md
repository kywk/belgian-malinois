# 接手文件 — 2026-10-02 第五輪完成（wave 1＋wave 2）

**寫給下一個接手的 PM Agent。** 撰寫時間 2026-10-02（晚間）。
上一輪交接見 `docs/handoff/2026-10-01-round5-handoff.md`（**仍然有效**）、
`docs/handoff/2026-10-01-round4-handoff.md`（環境陷阱總表、`git commit` 提交整個 index 的坑）、
`docs/handoff/2026-09-30-round3-handoff.md`（授權三組政策、並行 subagent 標準流程）。
本檔只寫 2026-10-02 這一輪的新事實；`docs/handoff/2026-10-02-next-pm-prompt.md`
的內容已被本輪取代。

本輪分兩波，全部經 PM 驗收、合併、跑完整套件與線上實測。
⚠️ **本檔的「完成」都有 merge commit 可查**（round5 的教訓：文件說完成不等於在 main 上）。

---

## 0. 現況

| | |
|---|---|
| 後端 | Spring Boot **3.5.16** + Flowable **7.2.0**（未升級），單一模組 `bpm-core` |
| 前端 | Vue 3.4 + Vite 5 + Element Plus |
| 測試 | 後端 **840**、前端 **165**（僅認 `mvn clean verify` 的 `Tests run: N`） |
| `main` | `0974b8f`（本輪程式全數合併）＋文件收尾 commit；已 push **`nsl`** |
| `github` remote | ⚠️ 仍指向無關公開 repo，**且已有到 `d6c8f53` 的完整歷史**；使用者明示暫不處理。**永遠 `git push nsl main`，不要裸 push** |
| worktree | 全部清除（含 `/tmp` 備份） |
| 容器 | dev 容器運行中（bpm-core 8080、nginx 80、mssql/rabbitmq/redis/mailhog）；`acceptance-test.sh` **PASS 7 / FAIL 0** |

```
cd /Users/kywk/kywk/nanshan/greyhound
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d --build bpm-core mailhog
./scripts/seed-data.sh && ./scripts/acceptance-test.sh   # 重建+seed 後第一次可能全失敗，再跑一次
cd bpm-core && mvn clean verify                          # 829（容器停掉再跑）
```

---

## 1. 本輪完成

### Wave 1（跨層驗證＋前端＋文件）

| 工項 | commit / merge | 內容 |
|---|---|---|
| **#67 前端** | `57d40b6`／`71d61bd` → `b5de2a1` | Process 面板可設 `process.completed`／`complete`／`all`；節點層補 `all`；`saveWebhooks` 存檔契約測試 |
| #67 後續 | `d53d668` → `34c6756` | **移除 `timeout` 選項**（後端保留相容）；非法 event 顯示「（無效，後端不投遞）」；pool 限制註記 |
| **#25 payload** | `fcfb951`／`792e2db` → `4af4e53` | `task.timeout` 非敏感欄位；spec §11.4 以實作為準；P2-1 紅線明文 |
| dev 註解 | `4c802bc` → `8e9c0a7` | `dev-token.sh` 帳號表與 `auth.js` 對齊 `MockPermController` fixture |
| **#68 docs** | `4f3447d` → `9bf795b` | R-20 文件落差 10 處（每處附 file:line） |
| **#95 docs 同步** | `8da65e9` → `b471e69` | CLAUDE.md R-18 解除、plan 索引 49.5→19.5 人日、spec 落差、Phase 4/5 勾選、歷史快照加註 |
| PM 收尾 | `3f8f7a6`／`616ba8a` | backlog 狀態/統計重盤、`MeController` 敘述更正、#95 入列 |

### Wave 2（後端功能與驗證）

| 工項 | commit / merge | 內容 |
|---|---|---|
| **R-19** | `123e494` → `c69ce1a` | `completeTask` 三層檢查：擁有權 403 → `allowedProcessKeys` 403 → **任務必須明確指派給 `system:<id>`** 403 → `_` 變數 400 → 既有 `validateVariables`。全部在 `taskService.complete` 前（零副作用） |
| **#26／#27** | `e3eb4cc` → `6c19e2a` | HMAC 重放標頭、重試／DLQ、SSRF 不重試的**測試證明**（主程式零變更）；`WebhookTestSink` 加失敗注入 |
| **#40** | `990cf1e` → `b3a8dd4` | `GET /api/audit-logs/export` CSV（BOM＋RFC 4180、逐頁串流、匯出以 `EXPORT_DATA` 留痕） |
| webhook HMAC prod 防護 | `d34c3c6` → `0aa4d50` | prod 未設或沿用 `bpm-webhook-secret` → **拒絕啟動** |
| **#52** | `a80d323` → `f04f584` | 多版本並行＋表單版本鎖的測試（真實 DB，含對照組） |
| **#33＋#6** | `6d37d43`／`a6703ee`／`fdab9ce`／`dfd8977` → `26417bb` | 唯一發送端 `NotifyPublisher`（退回／拒絕／結案／認領／加簽／催辦）；催辦端點 `POST /api/tasks/urge?processInstanceId=`＋前端接線 |
| #36 追認 | （無程式改動） | rule h 已是 error（#68d），backlog 列更新 |
| 收尾 | （見 §8 更新） | R-23 `startProcess` `_` 過濾＋TASK_URGE 稽核 |

**PM 補完的驗證**
- 前端 webhook 面板以**真實瀏覽器**（computer-use skill＋獨立 Chrome profile＋CDP）驗證：Process 面板「流程 Webhook」、事件選項 `process.completed／complete／all`；UserTask 面板事件 `create／complete／reject／all`（**無 timeout**）。截圖與 DOM 選項清單雙重佐證。
- 線上實測：催辦 200／429／403／404＋MailHog 四種通知信；匯出 200／403／403／401＋`EXPORT_DATA`；R-19 自我核准 403 且零副作用、未授權 key 403、`_` 變數 400、系統持有任務 200。

---

## 2. 本輪重要技術結論

### 2.1 🔴 Flowable 7.2.0 **不發 timeout task event**（`javap -p -c` 證實）
`BaseTaskListener` 只有 `CREATE／ASSIGNMENT／COMPLETE／DELETE／ALL_EVENTS`；整個 flowable-engine
沒有任何 `"timeout"` 字面值。所以 `event="timeout"` **設定成功但永遠不投遞**；設計器已移除該選項、
後端 `WebhookTaskListener.matches`／`buildPayload` 保留相容、payload 休眠。
⚠️ 舊註解「`event=all` 已讓 timeout 真的能觸發」是**未經實測的假設**，已在 backlog 更正。
替代方向（未決）：由 `delete` 推導／等 Flowable 8／不做。

### 2.2 #40 的 `StreamingResponseBody` **與閘道身分不相容**（實測）
本服務身分由 `GatewayAuthenticationFilter` 逐請求設定。`StreamingResponseBody` 會對同一請求做
**ASYNC dispatch**，該次 dispatch 沒有身分 → `/api/audit-logs/**` 的 `hasAuthority` 被拒 →
回應已 commit → 每次成功匯出噴 `Unable to handle ... response is already committed` ERROR。
**未來任何串流端點都要注意**；本工項改為直接寫 `HttpServletResponse` OutputStream。

### 2.3 加簽的 standalone task **不經過 BPMN taskListener**
`CountersignController` 用 `taskService.newTask()` 建子任務，`${notifyTaskListener}` 掛不到它
—— 這才是先前「加簽沒通知」的真因（不是 assignee 太晚設定）。修法：由建立端呼叫
`NotifyPublisher.taskAssigned(...)`，事件與 payload 規則仍只有一份。

### 2.4 通知的 fail-open 是刻意的（與 webhook listener 對稱）
`NotifyPublisher.publish()` 吞所有例外。呼叫端在簽核交易內；若讓 RabbitMQ 故障往外丟，
**使用者會簽不了核**。反過來說：稽核最後失敗回滾時通知已送出 —— 與既有 webhook listener 同一取捨。

### 2.5 R-19 的授權語意（已定案）
外部系統只能完成「`assignee` 或 `candidateUsers` 明確包含 `system:<id>`」的任務；候選群組不納入。
身分比對沿用 `ExternalActorIdentity`（大小寫不敏感、不 trim、不比對 owner）。
狀態碼：`_`／規格 → 400；未指派給該系統／key 未授權 → 403。所有拒絕零副作用。
⚠️ **Call Activity 子流程的任務取子流程自己的 key** —— 部署時子流程 key 也要在
`allowedProcessKeys` 內。

### 2.6 瀏覽器驗證的方法（可重用）
`orca computer`（computer-use skill）＋**獨立 profile 的 Chrome**（不干擾使用者的分頁）
＋ `--remote-debugging-port=9222` 後改用 **CDP（Node＋`ws`）**：
- 原生 `<select>` 不曝露選項，用 CDP `Runtime.evaluate` 讀 `select.options` 最可靠。
- 用 JS 合成 `MouseEvent` 可選取 bpmn-js 圖形（CDP 點擊中心會打到 canvas 背景）。
- 設計器載入既有流程要用 `?id=<processDefinitionId>`（`route.query.id`），
  只給 processKey 會是空白圖。

### 2.7 測試技巧（#26／#27）
- 重試參數讀**執行期 `RabbitProperties`**，不寫死設定值。
- DLQ 斷言要先**暫停 `dlq.bpm` 的 listener**（`DeadLetterConsumer` 會搶先吃掉），
  用 `x-death` 證明來源是 `bpm.webhook.queue/rejected`。
- 「只有 N 次」要**多等一個完整 backoff** 再斷言，否則 max-attempts 被改大時照樣假綠。

---

## 3. 已知殘餘與限制

| 殘餘 | 說明 |
|---|---|
| timeout 事件 | 上游不存在（§2.1）；設計器不再提供，替代機制未決 |
| external API 完成任務不發通知 | `ExternalApiController.completeTask` 是另一條 complete 路徑，退回／拒絕／結案通知只覆蓋 HTTP `PUT /api/tasks/{id}`。建議改全域 `TASK_COMPLETED`／`PROCESS_COMPLETED` listener（需 FlowableConfig 註冊） |
| 催辦的邊界 | 無自然人申請人（`initiator=system:*` 且無 `onBehalfOf`）的案件沒人能催；30 分鐘／案件 key／fail-open 待追認；成功催辦的 TASK_URGE 稽核收尾中 |
| 匯出邊界 | 無筆數上限；CSV 公式注入（`=`／`+`／`-`／`@`）未防；中斷語意記「命中筆數」；檔名無時間 |
| R-21 | `allowedProcessKeys` 空值＝不限制，寫入端未強制必填（讀取端 R-09 已 fail-closed） |
| R-24 | `queryByBusinessKey` 仍以 `initiator` 篩選（舊資料無 backfill） |
| #70 升級 | Stage 5（Boot 4＋Flowable 8）未開工；⚠️ 升級時 `ExtensionElementPreservationTest` 會第一個紅 |
| dev 探測殘留 | `probe-67fe`、`probe-r19e2e`（流程定義）、已停用外部系統 `probe19e2e`、`t91req`、`probe-67p*`、`probe-91*`、`probe-blank-literal`（都無害、清不掉） |
| github 曝光 | `github` remote 已有完整歷史（含 dev 密碼與內部文件）；使用者明示暫不處理 |

---

## 4. 環境風險（新增，其餘見 round4／round5）

1. 🔴 **prod 啟動新增硬性要求**：`BPM_WEBHOOK_HMAC_SECRET`（未設或等於 `bpm-webhook-secret` → 拒絕啟動）；JWT 與 gateway 的舊要求仍在。
2. **`StreamingResponseBody` × 閘道 ASYNC dispatch 不相容**（§2.2）。
3. **R-19 的 Call Activity 授權模型**：子流程 key 要在 `allowedProcessKeys` 內（部署附屬簽時必查）。
4. **催辦的 Redis key**：`bpm:urge:{processInstanceId}`、TTL 30 分鐘；Redis 故障 fail-open。
5. **外部 API 的必填變數**：leave-approval 有 required spec（例：`days`），外部發起少了就 400（與內部路徑不同，實測踩過）。
6. 沿用：`mvn clean verify`、`DOCKER_HOST` exports、dev overlay、`IntegrationTestBase.SERVLET_PORT` 單一 port、外部 API `ipWhitelist` 要含 `192.168.117.1`、`PUT /api/tasks/{id}` 的 `variables` 是 list、`DELETE /api/admin/external-systems/{id}` 是軟停用、shell 變數每次重置、commit 提交整個 index（每次 commit 前檢查 `git status --short` 第一欄）。

---

## 5. 待使用者裁決（累積，未定案）

**A. timeout（已決定設計器移除；留下的是）**
1. 替代機制：(a) 維持現狀（payload 休眠）／(b) 由 `delete` 推導（需設計：如何辨識邊界計時器中斷）／(c) 留到 #70。

**B. #6 催辦**
2. 30 分鐘／案件 key／fail-open 是否接受。
3. 無自然人申請人的案件是否開放給 `bpm:external:revision` 受理人催辦。
4. 退件／拒絕原因是否入信（目前保守不放，前端把它寫成 comment）。
5. `process_completed` 只在核准時發（拒絕只發 rejected）的解讀是否正確。
6. 端點形狀維持 `?processInstanceId=` 或改 `/tasks/{taskId}/urge`（後者需先在 `ProcessController` currentTask 補 taskId）。

**C. #40 匯出**
7. 筆數上限（目前無）；CSV 公式注入是否防（會改寫資料，與逐字一致衝突）；中斷語意；檔名是否含時間。

**D. HMAC／DLQ**
8. 是否移除 `WebhookConsumer` 的 `@Value` fallback 與 base `application.yml` 的預設字面值。
9. DLQ 告警與人工重放（`DeadLetterConsumer` 目前只記 log）—— 建議新工項（原 #51）。

**E. R-19／R-23**
10. 未宣告的非 `_` 變數（如 `initiator`）是否也要在外部 API 收緊（目前沿用 `validateVariables` 既有語意放行）。
11. R-23 的 startProcess `_` 過濾（收尾中）。

---

## 6. 建議的下一輪優先序（先和使用者確認）

| 順序 | 工項 | 估時 | 備註 |
|---|---|---|---|
| 1 | 清 §5 的待裁決 | — | 多數是產品參數，一次問完 |
| 2 | **外部 API 完成任務的通知**（全域 listener） | 1–2d | 收斂完成路徑；`FlowableConfig` 註冊 |
| 3 | **#51 DLQ 告警／重放** | 1–2d | 使用者若同意 |
| 4 | **#23 Timer Event 超時處理** | 2d | 需先有 §5-A 的方向；可取代死掉的 timeout |
| 5 | **#4 Call Activity** 或 **#21 Callback 接收端** | 3d | #21 的 token 簽發方式需先設計 |
| 6 | **#70 Stage 5**（Boot 4＋Flowable 8） | 22d | 照升級計畫；`ExtensionElementPreservationTest` 先紅 |

其他 ⬜／🟡 見 `docs/backend-development-backlog.md`。**部署前檢查清單**：
權限碼 `bpm:external:revision`／`bpm:form:design`／`audit:log:read` 必須在真實權限中心指派；
prod secrets（`OIDC_ISSUER_URI`、`GATEWAY_SHARED_SECRET`、`BPM_WEBHOOK_HMAC_SECRET`）；
`allowedProcessKeys` backfill（R-21／R-24）。

---

## 7. 統計

- 工項 **95**：✅ **59**、🟡 **15**、⬜ **21**，剩餘估時上限 **~85 人天**。
- 後端 **840**（`mvn clean verify`）；前端 **165**；`acceptance-test` PASS 7 / FAIL 0。

---

## 8. 收尾更新（2026-10-02，已合併）

`feature/wave2-closeout-gaps`（→ merge `0974b8f`）：
- `c05afae`：`startProcess` 以**同一份** `rejectReservedVariableNames` 拒絕 `_` 前綴
  （R-23 殘留關閉；兩個外部入口一致）。
- `0958d3e`：成功催辦寫 `TASK_URGE` 稽核（operator＝呼叫者；detail 只放冷卻分鐘數／
  任務數／收件人數）；`TASK_URGE` 移出 `NOT_YET_IMPLEMENTED`。
- 新增測試 11 條（7＋4）；負控組 A 3 紅、B 2 紅；相關 138 條綠。
- ⚠️ 仍待裁決：`startProcess` 的保留變數 400 贏過「定義不存在」404（與既有
  `validateVariables` 位置一致）；urge 的「通知＋稽核」非原子（無 outbox，與 `updateTask`
  同型取捨）；`TASK_URGE` 未帶 `processDefinitionKey`。
