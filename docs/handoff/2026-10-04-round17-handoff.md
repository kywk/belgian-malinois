# 接手文件 — 2026-10-04 走查＋#28／#32／#35（Wave H）

> ⚠️ **同日稍晚的 #60／#61（跨服務整合）見
> `docs/handoff/2026-10-04-round18-handoff.md`（最新）。** 本檔內容仍然有效。

**寫給下一個接手的 PM Agent。** 撰寫時間 2026-10-04（接續 round16，跨午夜）。
上一輪交接見 `docs/handoff/2026-10-03-round16-handoff.md`（**仍然有效**：#70 結案）、
`round15`（Stage 5）、`round14`（Wave G）、`round13`（Wave F）、`round12`（Wave E）、
`round11`（Wave D）、`round10`（Wave C）、`round9`（Wave B）、`round8`（Wave A）、
`round7`、`2026-10-02-round6-handoff.md`、`2026-10-01-round4-handoff.md`（環境陷阱總表）、
`2026-09-30-round3-handoff.md`。升級計畫見 `docs/plan/2026-09-28-springboot4-upgrade.md`（已結案）。

本檔只寫本輪新事實。四個分支 `feature/28-payload-template`／`feature/32-teams-notify`／
`feature/35-el-method-whitelist` 已合併並清除；`main` = `ef765dc`（＋文件收尾 commit）。

---

## 0. 現況

| | |
|---|---|
| 後端 | Spring Boot 4.1.1 + Flowable 8.0.0 + Jackson 3 |
| 前端 | Vue 3.4 + Vite 5 + Element Plus |
| 測試 | 後端 **1297**（`mvn clean verify`）、前端 **204**（Vitest） |
| `main` | `ef765dc`（本輪 3 merge＋2 直接 commit）＋文件收尾 |
| push | ⚠️ `nsl`（10.127.42.141）仍不可達；累積約 **100 顆 commit** 在本機 `main`，恢復後 `git push nsl main`（**永遠不要裸 push**） |
| worktree | 全部清除 |
| 容器 | dev 容器跑本輪 build；health 200；`acceptance-test.sh` PASS 7 / FAIL 0 |
| DB | dev core schema **v8**（V8＝notify_config.webhook_url）；Flowable schema 8.0.0.0（不可逆） |

---

## 1. 本輪完成

### 1a. 前端瀏覽器走查（計畫驗收的最後一項，終於補完）

- 方法：headless Chrome ＋ CDP（Node 內建 WebSocket；`orca computer` runtime 不在，改用程式化路徑）。
  4 身分（mgr001／user001／dir001／admin001）× 10 路線：儀表板、待辦清單、案件詳情、
  我的申請、發起申請、稽核 Log、流程管理、BPMN 設計器。
- 結果：日期顯示全正常（`2026/9/30 下午6:11:47`）、Dashboard 逾期計算正確
  （mgr001 緊急 38／user001 1）、稽核 detail JSON 正常；**零 API 4xx/5xx、零 JS/console error、零 Invalid Date**。

### 1b. 走查抓到並修復 `#56` 回歸（`dd4c12f`）

- 案件詳情「假別」選單下方多一行「不支援的欄位類型: select」。
- 根因：`#56` 把「選項載入中／失敗」兩個提示 `div` 插在 `el-select` 與 `el-radio-group` 之間，
  **v-if/v-else-if 鏈斷成兩條**；所有 text/textarea/number/date/select 欄位都多渲染 fallback。
  原測試只斷言「該有的在」、沒斷言「不該有的不在」。
- 修法：提示 div 移到型別鏈尾；`DynamicForm.spec` 加 2 條回歸（負控：舊碼 1 紅）。

### 1c. #28 webhook payloadTemplate（merge `1cb471c`）

- `WebhookConfig.payloadTemplate`＋`ATTR_PAYLOAD_TEMPLATE`；`{{field}}` 模板（JSON 字串內容轉義、
  null→空、未知 placeholder 原樣保留＋debug）；listener 渲染成 `__webhookBody`，consumer 優先使用、
  **HMAC 對最終 body 計算**；`__webhookUrl/Method` 契約不變。
- **P2-1 紅線**：模板只查 payload map（結構上碰不到流程變數）。
- lint 新增規則 l `webhook-payload-template`（未知欄位 warning；`BpmnLintService` 原本完全沒有 webhook 檢查）。
- 前端：`WebhookProps` 加 textarea、`webhookStorage` 讀寫、**`flowableModdle` 補 `payloadTemplate` 屬性**
  （少了它存檔往返會整個丟掉）；兩支 spec 更新。
- 附帶修正：`ProcessCompletedListener` 每筆設定各自一份 payload map（避免無模板設定繼承他人 body）。

### 1d. #32 通知服務 Teams 整合（merge `2a55384`＋`ef765dc`）

- V8 migration：`bpm_notify_config.webhook_url NVARCHAR(1000) NULL`。
- Admin API：create/update 接受 `webhookUrl`；`channel=teams` 必填＋過 `WebhookUrlPolicy`（create 與 update 同一條）；
  **讀取端（create/list/update 回應）一律 `***` 遮蔽**，update 收到 `"***"` 視為「不改此欄位」；
  稽核記 sha256 前綴＋長度（不記全文）。
- `EmailConsumer`（名字保留）改為 channel 路由：`teams` → `TeamsWebhookPayload`（與 #44 共用形狀
  `{"text": ...}`，不是 MessageCard）POST 到 `webhookUrl`；`email` 走原模板／fallback。
  - policy 拒絕 → log ERROR、不重試、不 fallback；非 2xx／逾時／例外 → throw（retry→DLQ，與 email 一致）；
    URL 空 → warn 略過該筆。
  - **recipients 只對 email 有意義**：early return 移到 fallback 前，群組任務的 Teams 通知才送得出去。
  - 兩 channel 同時設定 → 兩條都送（email 處理完不再吃掉 teams）。
- 已知取捨：**部分失敗會重複送達**（一則訊息只 ack 一次；email 成功、teams 失敗時重試會重送 email）——
  需要 per-channel 冪等／outbox 才能消除，不在本次範圍，已寫進類別 javadoc。

### 1e. #35 EL 逐方法白名單（merge `c1f52d5`）

- `EL_METHOD_WHITELIST`（六 bean；每方法附用途註解）＋`EL_METHOD_EXCLUDED`（維運 API、static 工具、
  P1-7 兩 stub）；規則 g 擴充：bean 在白名單且出現 `.method` 時方法不在集合 → **error `el-method-whitelist`**
  （訊息含 bean.method 與排序後允許清單）；bean 層檢查、非白名單 bean、裸用 `${bean}` 行為不變。
- `ElMethodWhitelistDriftTest`（純 reflection）：白名單方法必須存在、每個 public 方法必須在白名單或排除清單。
- 已知殘餘（繼承 bean 層，未惡化）：空白 `${ orgService.…}`、巢狀方法呼叫只驗第一節、合法方法後接長鏈。
  逐方法後**新增**的好處：`${orgService.getClass()}` 這類第一節反射會被擋。

---

## 2. 線上實測（dev 容器，2026-10-04）

| 項目 | 結果 |
|---|---|
| 熱啟動（既有 DB） | health 200；core Flyway **v7 → v8** 成功；audit／form DB 無變化 |
| seed＋acceptance | PASS 7 / FAIL 0 |
| #35 負控 | `POST /api/deployments` 帶 `${orgService.nope(execution)}` → **400**（1 lint error） |
| #35 正控 | 同檔改 `${orgService.getDeptId(execution)}` → **200**、lint valid |
| #32 遮蔽 | create／list 回應 `webhookUrl=***`、全文 0 命中；PUT `***` → 200 且回應仍 `***`；fixture 已刪 |
| #28 前端 | BPMN 設計器頁載入正常、`流程 Webhook` 面板渲染、零 console error |

---

## 3. 已知殘餘（本輪新增）

| 殘餘 | 說明 |
|---|---|
| #28 headers 自訂 | spec §11.4 有列 `headers`，本次未做（payloadTemplate 已做） |
| #28 模板引用節點欄位 | 流程層模板引用節點欄位（如 `{{taskId}}`）lint 不警告（聯集制）、執行期原樣保留 |
| #28 自訂 body 不強制合法 JSON | 使用者寫什麼送什麼（Content-Type 仍 application/json） |
| #32 channel 白名單 | 刻意未加（既有測試以 `teams-<uuid>` 自訂 channel 並期待 200）；只有精確 `teams` 會驗證 |
| #32 teams 的 templateId 仍必填 | 與 email 同一條 `requireExistingTemplate`；legacy 無模板列才走事件預設文案 |
| #32 部分失敗重複送達 | 見 1d；需要 per-channel 冪等／outbox |
| #32 admin UI | repo 無 notify-config 前端管理頁（API only） |
| #35 字串層限制 | 見 1e 殘餘 |

---

## 4. 剩餘工項（統計 ~24 人天）

| 類別 | 工項 | 估時 |
|---|---|---|
| Org/Perm | #8（3d）／#9（2d）——**等真實權限中心**，目前無法完成 | 5d |
| 稽核 Log | #41 異常操作偵測 | 3d |
| 基礎設施 | #53 BPMN 環境變數替換（有 secrets 政策面） | 1d |
| 跨服務整合 | #60 form-data 同交易（2d）／#61 BPMN 部署 Git commit（3d）／#63 單元測試（5d）／#64 整合測試（5d） | 15d |

---

## 5. 建議的下一輪優先序

| 順序 | 工項 | 估時 | 備註 |
|---|---|---|---|
| 1 | **#60／#61 跨服務整合** | 2d／3d | 上線前一致性 |
| 2 | **#41／#53** | 3d／1d | #53 需先定 secrets 政策 |
| 3 | **#63／#64 測試覆蓋** | 5d／5d | 上線準備 |
| 4 | #8／#9 | 5d | 阻塞：需真實權限中心 |

**部署前檢查清單（沿用＋新增）**：權限碼指派；prod secrets；backfill（R-21／R-24）；
callback secret rotate；worker topic 專屬化；#51 告警收件人；JVM／limits 依 GC log 調；
表單 schema 修復；**Teams webhook URL 設定（#32；prod 政策白名單）**；
prod 映像已是 Boot 4／Flowable 8／Jackson 3。

---

## 6. 統計

- 工項 **97**：✅ **89**、🟡 **6**、⬜ **2**，剩餘估時上限 **~24 人天**。
- 後端 **1297**；前端 **204**；`acceptance-test` PASS 7 / FAIL 0。
