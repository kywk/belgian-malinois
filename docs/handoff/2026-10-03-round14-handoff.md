# 接手文件 — 2026-10-03 Wave G 完成（Delegate 收尾＋#97 安全小項）

> ⚠️ **同日稍晚另有 #70 Stage 5（Boot 4.1.1＋Flowable 8.0.0 已上 main），見
> `docs/handoff/2026-10-03-round15-handoff.md`（最新）。** 本檔內容仍然有效。

**寫給下一個接手的 PM Agent。** 撰寫時間 2026-10-03（同日，接續 round13）。
上一輪交接見 `docs/handoff/2026-10-03-round13-handoff.md`（**仍然有效**：Wave F）、
`round12`（Wave E）、`round11`（Wave D）、`round10`（Wave C）、`round9`（Wave B）、
`round8`（Wave A）、`round7`（#96／#51／#3／#6／taskId）、`2026-10-02-round6-handoff.md`、
`2026-10-01-round4-handoff.md`（環境陷阱總表）、`2026-09-30-round3-handoff.md`。
本檔只寫 Wave G 的新事實。

兩階段並行（#97 的 `SafeRestClients` 是三個 HTTP delegate 的共同基礎），
全部經 PM 驗收、合併、完整套件與線上實測；worktree 與 feature 分支已清除。

---

## 0. 現況

| | |
|---|---|
| 後端 | Spring Boot **3.5.16** + Flowable **7.2.0**（未升級） |
| 前端 | Vue 3.4 + Vite 5 + Element Plus |
| 測試 | 後端 **1230**（`mvn clean verify`）、前端 **200**（Vitest） |
| `main` | merges 至 `08c0d7b`／`f974c10`／`52a8795`／`395f470` 等 ＋ 文件收尾 commit |
| push | ⚠️ `nsl` 主機本日整天不可達（`10.127.42.141`）——本日八輪 commit 都在本機 `main`，網路恢復後 `git push nsl main`（**永遠不要裸 push**） |
| worktree | 全部清除 |
| 容器 | dev 容器運行中；`acceptance-test.sh` **PASS 7 / FAIL 0** |

---

## 1. 本輪完成

| 工項 | commit / merge | 內容 |
|---|---|---|
| **#97 不跟隨重導** | `7ead930` → `08c0d7b` | `SafeRestClients.create()`（不跟隨 3xx＋timeout）收斂 webhook／delegate／options 三客戶端；webhook 非 2xx 改視為失敗 |
| **#44 TeamsNotifyDelegate** | `395f470` | `webhookUrl`／`title`／`message`；payload `{"text":…}`；policy＋fail-open |
| **#45 ESignDelegate** | `19c91c6` → `f974c10` | POST 觸發電子簽章；`ESIGN_BLOCKED`／`ESIGN_FAILED`；等待由 BPMN message catch＋#21 callback 承擔 |
| **#46 ErpSyncDelegate** | `53380c7`／`8be43b4` → `52a8795` | `url`／`method`（POST/PUT）／`payload`／`resultVariable`；`ERP_SYNC_*` |
| **#47 dynamicAssignee** | `5a0514b` | EL bean 三方法（`managerAtLevel`／`firstAvailable`／`managerWithPermission`）都套代理人、找不到人拋例外 |

---

## 2. 本輪重要技術結論

### 2.1 #97：Spring 只對 GET 跟隨重導（實測修正工項前提）

- `SafeRestClients` 一律在 `prepareConnection()` 設 `setInstanceFollowRedirects(false)`，讓規則與 method／Spring 版本無關。
- ⚠️ **前提修正**：Spring 的 `SimpleClientHttpRequestFactory` 只對 GET 設 `follow=true`（6.2.x 原始碼）；POST/PUT 本來就不跟隨。真正缺口是 **GET**（`ExternalApiDelegate` 預設 method、`FormOptionsService`）；webhook（POST/PUT）「剛好安全」。
- ⚠️ 順帶修正 **webhook false-success**：302 原本被記成 delivered（`RestClient` 只對 4xx/5xx 拋例外）；現在非 2xx 即失敗 → 重試 → DLQ。這是行為變更，方向正確（接收端確實沒收到）。

### 2.2 #45：ESign 的觸發／等待分工與兩個陷阱

- delegate **只觸發**：BPMN 設計為 `serviceTask(esignDelegate) → message catch event → 後續`；外部簽核完成後走 **#21 `POST /api/callback/{type}`** 喚醒（HMAC＋冪等＋correlation 已具備）。javadoc＋spec 有完整 BPMN 片段。
- `resultVariable`＝**回應 body 原字串**（不解析供應商特定的 requestId；與 #49 同一條規則）。
- ⚠️ **`${processInstanceId}` 陷阱**：`stringValue` 的文字替換把 `processInstanceId` 換成**空字串**（它不是流程變數）；要帶 correlation id 必須用 `expression` 的 `${execution.processInstanceId}`（整合測試釘住）。

### 2.3 #46：獨立實作而非薄包裝

- 與 #49 契約差異大（預設 POST vs GET、payload 必填＋JSON 驗證、method 集合、錯誤碼），抽 helper 會變成帶旗標的淺模組；共用的只有三個既有安全縫（`BpmnFieldSupport`／`WebhookUrlPolicy`／`SafeRestClients`）。
- ⚠️ **`HttpURLConnection` 不支援 PATCH**（`Invalid HTTP method: PATCH`，wire 實測）——已在驗證層擋掉（method 只允許 POST/PUT）。

### 2.4 #47：選 EL bean（不是 delegate）

- 理由：EL 在任務建立同一個 command 求值（失敗整個交易回滾）；assignee 欄位受 lint 規則 g／i／k 涵蓋，而 delegate 的 `flowable:field` **不在任何 lint 規則內**（打錯 bean 名要等送出才爆）；與 `assigneeResolver`／`applicantResolver` 同形。
- 三方法都**套代理人**（補 `orgService.getManagerAtLevel`／`bpmQueryService.getManagerWithPermission` 不套代理人的缺口）、找不到人拋 `IllegalStateException`（不靜默卡死）、拒收 `system:*`。
- 註冊 `FlowableConfig.setBeans` ＋ `BpmnLintService.EL_WHITELIST` **兩份同步**（`BpmnExpressionBeanScopeTest` 自動涵蓋）。
- ⚠️ **前端設計器仍產生舊運算式**（`${orgService.getManagerAtLevel(...)}`），新 bean 需手改 BPMN；設計器選單支援列為後續。

### 2.5 統計更正（PM 收尾時發現）

- 「2026-09-29 新增」類別剩餘誤記 12.5d（漏計 #70 的 22d）；合計 🟡／⬜ 各少 1。
- 已修正：合計 **97 項｜✅85｜🟡7｜⬜5｜~51 人天**。

---

## 3. 已知殘餘

| 殘餘 | 說明 |
|---|---|
| #47 | 前端設計器不產生新運算式（需手改 BPMN）；`managerAtLevel` 鏈較短時回最高階（繼承既有行為） |
| #44 | 未對真實 Teams 頻道實測（只證明 HTTP 合約）；incoming webhook 長期將被 Workflows 取代 |
| #45 | `resultVariable` 是原字串（供應商 requestId 解析留給後續）；payload 只驗「可解析 JSON」 |
| #46 | 未對真 ERP；PATCH 需換 HTTP client 才能支援 |
| #97 | webhook 302 行為變更（false-success → 失敗重試）已記；DNS rebinding TOCTOU 仍在 |
| 沿用 | probe 殘留（`probe-waveG` 4 process、`probe-43-49` 3 版等）、已停用外部系統、R-21／R-24 |

---

## 4. 環境風險（新增，其餘見 round13／round12／round4）

1. **BPMN 屬性內含 JSON 時用單引號屬性**（`stringValue='{"k":"${v}"}'`）——反斜線跳脫 `\"` 不是合法 XML，會 parse error（PM 線上踩到）。
2. **`${processInstanceId}` 在 `stringValue` 是空字串**；要帶案件 id 用 `expression` 的 `${execution.processInstanceId}`。
3. **`HttpURLConnection` 不支援 PATCH**。
4. 沿用：Flyway placeholder、migration 版本、`flowable:field` 不依賴 setter 注入、boundary error 不帶未定義 errorRef、`mvn clean verify`、`DOCKER_HOST` exports、`git commit` 整 index、單一 servlet port。

---

## 5. 已裁決與待裁決

### 5.1 本輪（無新使用者裁決；照 Wave G 提案執行）

### 5.2 待決策／可開新工項

1. **#47 前端設計器**：讓設計器產生 `dynamicAssignee` 運算式（0.5–1d）。
2. **#45 ESign 結果解析**：要不要支援供應商 requestId 解析（需定義契約）。
3. **#63／#64 測試覆蓋**、**#60／#61 跨服務整合**、**#70 Stage 5**。
4. R-21／R-24 backfill（部署前）。

---

## 6. 建議的下一輪優先序

| 順序 | 工項 | 估時 | 備註 |
|---|---|---|---|
| 1 | **#60 流程啟動完整流程**（form-data 同交易）／**#61 BPMN 部署 Git commit** | 2d／3d | 跨服務整合收斂 |
| 2 | **#47 設計器支援**＋**#45 結果解析**（若裁決要） | 0.5–1d | 小項 |
| 3 | **#63／#64 測試覆蓋**（各 5d）、**#28 payloadTemplate**／**#32 Teams 通知整合**（2d 各） | 5d／5d／2d／2d | 上線準備 |
| 4 | **#35 EL 白名單逐方法**（1d）／**#41 異常偵測**（3d）／**#53 BPMN 環境變數**（1d） | 1d／3d／1d | 視需要 |
| 5 | **#70 Stage 5**（Boot 4＋Flowable 8） | 22d | 建議獨立 session |

**部署前檢查清單（沿用＋新增）**：權限碼指派；prod secrets；`allowedProcessKeys`／
`allowedWorkerTopics` backfill（R-21）；callback secret rotate；worker topic 專屬化；
#51 告警收件人；`JAVA_TOOL_OPTIONS`／prod limits 依 GC log 調；表單 schema 修復；
**Teams incoming webhook 若被 Microsoft 淘汰需改 Workflows**。

---

## 7. 統計

- 工項 **97**：✅ **85**、🟡 **7**、⬜ **5**，剩餘估時上限 **~51 人天**（含本次統計更正）。
- 後端 **1230**；前端 **200**；`acceptance-test` PASS 7 / FAIL 0。

---

## 8. 線上實測明細（2026-10-03 Wave G，PM 序列）

| 工項 | 實測內容與結果 |
|---|---|
| **#44** | `probe-44teams`（webhookUrl=loopback）：policy 拒絕（log 指名、**零請求**）→ fail-open → 流程完成 |
| **#45** | `probe-45esign`：policy 拒絕 → `ESIGN_BLOCKED` → boundary 走替代路徑完成 |
| **#46** | `probe-46erp`：policy 拒絕 → `ERP_SYNC_BLOCKED` → boundary 完成 |
| **#47** | `probe-47assign`（`${dynamicAssignee.managerAtLevel(initiator,1)}`）→ 任務落 **mgr001** |
| **#97** | 由單元／整合測試覆蓋（`SafeRestClientsTest`／`WebhookConsumerRedirectTest`／delegate 302）；線上以既有 webhook 路徑回歸（完整套件綠） |
