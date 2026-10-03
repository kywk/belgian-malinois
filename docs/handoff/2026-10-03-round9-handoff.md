# 接手文件 — 2026-10-03 Wave B 完成（#4＋#21＋#5）

**寫給下一個接手的 PM Agent。** 撰寫時間 2026-10-03（同日，接續 round8）。
上一輪交接見 `docs/handoff/2026-10-03-round8-handoff.md`（**仍然有效**：Wave A）、
`docs/handoff/2026-10-03-round7-handoff.md`（#96／#51 告警／#3／#6／taskId）、
`2026-10-02-round6-handoff.md`、`2026-10-01-round4-handoff.md`（環境陷阱總表）、
`2026-09-30-round3-handoff.md`（授權三組政策、並行流程）。本檔只寫 Wave B 的新事實。

三個工項各在獨立 worktree 並行，全部經 PM 驗收、合併、完整套件與線上實測；
worktree 與 feature 分支已全部清除。

---

## 0. 現況

| | |
|---|---|
| 後端 | Spring Boot **3.5.16** + Flowable **7.2.0**（未升級） |
| 前端 | Vue 3.4 + Vite 5 + Element Plus |
| 測試 | 後端 **962**（`mvn clean verify`）、前端 **174**（Vitest，17 檔） |
| `main` | merges 至 `5f4ca50`／`f3aed18`／`c78a275` ＋ PM 收尾（V6 改名、revert、docs） |
| push | ⚠️ 收尾時 `nsl` 主機仍不可達（`10.127.42.141`）——本日三輪的 commit 都在本機 `main`，網路恢復後 `git push nsl main`（**永遠不要裸 push**） |
| worktree | 全部清除 |
| 容器 | dev 容器運行中；`acceptance-test.sh` **PASS 7 / FAIL 0**；Flyway V5／V6 已套用 |

---

## 1. 本輪完成

| 工項 | commit / merge | 內容 |
|---|---|---|
| **#4 Call Activity 加簽** | `2c1e628` → `5f4ca50` | 出廠模板 `countersign-review`＋變數規格 seed（V6）＋`seed-data.sh` 部署；前端 `CallActivityProps.js`（calledElement 下拉＋inheritVariables）＋`flowableModdle` 補型別 |
| **#21 Callback 接收端** | `9032365` → `f3aed18` | per-system callback secret（V5）＋`POST /api/callback/{type}`：HMAC＋時間戳窗、Redis 冪等、message correlation、`ExternalSystemAccessGuard` 抽共用 |
| **#5 代理人機制** | `2e91a1b` → `c78a275` | 首關受理人經 `effectiveAssignee`（含外部 `firstTaskAssignee` 的覆寫修正）；`POST /api/admin/tasks/forward-substitutes` 批次手動轉派 |
| PM 收尾 | `6327c57`／`1098afc`→`c967b3b` | #4 的 seed migration 改名 V5→V6（與 #21 的 V5 版本衝突）；一組 dev mock fixture 因與測試反向而 revert |

---

## 2. 本輪重要技術結論

### 2.1 #4：模板契約的方向與 lint 依賴

- **in/out 方向**：`flowable:in source=父變數 target=子變數`；`flowable:out source=子變數
  target=父變數`。模板註解裡的範例是「父以 `out source="approved"` 取回」——
  **子流程不會產生 `countersignApproved`**，父流程自己決定目標名。
  PM 線上實測第一次寫反（source=countersignApproved）→ 目標變數為 null。
- **lint `undeclared-variable` 是 error**：模板的 `${countersignAssignee}` 沒有規格宣告
  就無法部署 → 出貨模板必須與變數規格 seed 一起（`V6__seed_countersign_review_variable_specs.sql`）。
- **`flowableModdle` 缺 CallActivity/In/Out 型別**：改動前「開啟含 `flowable:in/out` 的
  BPMN 再存檔」會整段靜默刪掉（與 #67 的 TaskListener 同型缺陷）——已補。
- **設計器 provider 註冊點**是 `FlowablePropertiesProvider.getGroups`（不是 `useBpmnModeler.js`）。
- ⚠️ `seed-data.sh` 既有兩行傳 `deploymentName`（controller 讀 `name`），靠原檔名部署；
  #4 新行傳含 `.bpmn20.xml` 的 name。既有兩行未動（若日後要正名，注意部署名會變）。

### 2.2 #21：callback secret 必須可還原、Flowable 7 沒有 correlation builder

- **secret 存明文（可還原）**：HMAC 驗簽的密鑰就是原始密鑰；雜湊儲存買不到任何東西
  （拿雜湊當 key＝雜湊本身成為可偽造密鑰）。防護改落在 READ_ONLY、回應遮蔽、明文只回一次、
  稽核只記「先雜湊再截」的前綴。無 KMS 時的實務取捨（Stripe／GitHub 同型）。
- **Flowable 7 已移除 `createMessageCorrelationBuilder`**（6.x API）。等價：
  `ExecutionQuery.processInstanceId(pid).messageEventSubscriptionName(type)` ＋
  `runtimeService.messageEventReceived(type, executionId, vars)`；>1 個等待訂閱回 409。
- **冪等 fail-closed 503**；交易未 commit（404／稽核失敗／例外）用 `afterCompletion` 釋放鍵，
  避免毒化。硬殺行程的窗口留 24h TTL（已記載）。
- 共用守衛抽成 `ExternalSystemAccessGuard`（停用／IP／allowedActions），
  `ExternalApiAuthFilter` 行為與訊息不變（回歸測試綠）。

### 2.3 #5：代換的範圍與覆寫陷阱

- **代換範圍**：只代換平台解析出的真人（首關受理人、外部 `firstTaskAssignee`）；
  候選群組不代換（沒有對象）；加簽／reassign／delegate 是明確選擇不代換。
- **覆寫陷阱**：`ExternalApiController` 在啟動後會再 `setAssignee(firstTaskAssignee)`，
  不一起改就會把 BPMN 已代換的代理人**蓋回休假者本人**——已走同一份 `effectiveAssignee`。
- **`resolveEffective` 只解一層**（代理人的代理人不再往下），與既有語意一致。
- 既有任務轉派：批次 100、離職者（org 404）跳過、組織故障 503 整批回滾、
  一筆總結稽核（`TASK_SUBSTITUTE_FORWARD`）、逐任務通知新受理人。

### 2.4 🔴 PM 整合教訓（兩條）

1. **migration 版本衝突**：#4 與 #21 各自從同一 base 建了 `V5`；合併後 Flyway 會直接啟動失敗。
   **並行 wave 派工時，若多個工項都可能新增 migration，必須在 brief 指定版本號或明講「用下一個可用版本」。**
   （本次已改 V6；V5＝callback secret、V6＝加簽規格 seed。）
2. **不要為了線上實測加 dev mock fixture**：PM 在 `MockOrgController` 加 `user004→user005`，
   與 `SubstituteForwardTest` 的 Redis fixture（`user005→user004`）互為反向，全套件紅一條；
   已 revert。線上實測改用 **Redis 直接種事實**（`org:substitute:user004=user005`），
   與測試同一機制、零程式改動。

---

## 3. 已知殘餘

| 殘餘 | 說明 |
|---|---|
| #4 | 前端 hook 接線未以 renderer 測；設計器分頁內新部署不更新快取；`optional-assignee` warning 是刻意取捨 |
| #21 | secret 明文存 DB（無 KMS）；管理頁輪換按鈕未做（後續）；crash 窗口；callback `variables` 不擋 `_` 前綴、不驗擁有權（規格未要求，已記風險） |
| #5 | `resolveEffective` 一層；端點無總量上限；通知與稽核非同交易（既有模式） |
| 沿用 | probe 殘留（`probe-4call`／`probe-21cb`／`probe-23timer`／`probe-96ext`／歷輪）、已停用外部系統（`e2e-5`／`e2e-cb` 等）、R-21／R-24、#70 Stage 5 |

---

## 4. 環境風險（新增，其餘見 round8／round7／round4）

1. **Flyway placeholder**：SQL 檔內**不可出現 `${`**（連註解也不行）——Flyway 會當 placeholder 求值，
   找不到值就整個 context 起不來（#4 踩到）。
2. **多工項 migration 版本衝突**：見 §2.4.1；合併後先 `ls db/migration/core/` 檢查重號。
3. **`POST /api/deployments` 的 name 要含 `.bpmn20.xml`**（少了會「成功但無流程定義」）；
   `seed-data.sh` 既有兩行靠原檔名，新增行請比照。
4. 沿用：`mvn clean verify`、`DOCKER_HOST` exports、外部 API 需 `X-API-Key`＋`X-System-Id`、
   `git commit` 提交整個 index、`IntegrationTestBase.SERVLET_PORT` 單一 port。

---

## 5. 已裁決與待裁決

### 5.1 本輪已裁決（使用者 2026-10-03）

1. **#21 認證模式**：per-system callback secret（非平台簽發 token、非沿用 API Key）。
2. **#5 轉派語意**：新任務自動＋既有任務管理員手動批次。

### 5.2 待裁決／可開新工項

1. **#21 管理頁**：callback secret 輪換 UI（後續小項）。
2. **#1「退到任意節點」**、**#7 撤回通知／reason 上限**、**#51 parking 清理政策**（round8 §5.2 仍在）。
3. 下一輪主軸（見 §6）。

---

## 6. 建議的下一輪優先序

| 順序 | 工項 | 估時 | 備註 |
|---|---|---|---|
| 1 | **#22 External Worker Task**／**#24 Signal Event** | 3d／1d | 非同步主線收尾 |
| 2 | **#21 管理頁輪換 UI**＋**#7 撤回通知**＋**#1 任意節點** | 0.5–2d 各 | 需先裁決後兩者 |
| 3 | **#63～#65**（測試覆蓋、API 文件） | 5d／2d | 上線前文件 |
| 4 | **#70 Stage 5**（Boot 4＋Flowable 8） | 22d | `ExtensionElementPreservationTest` 先紅 |

**部署前檢查清單（沿用＋新增）**：權限碼指派；prod secrets（`OIDC_ISSUER_URI`、
`GATEWAY_SHARED_SECRET`、`BPM_WEBHOOK_HMAC_SECRET`）；`allowedProcessKeys` backfill；
#51 告警收件人；**外部系統的 callback secret 需逐一 rotate 產生**（V5 之後既有系統不能回呼）。

---

## 7. 統計

- 工項 **96**：✅ **68**、🟡 **11**、⬜ **17**，剩餘估時上限 **~67 人天**。
- 後端 **962**；前端 **174**；`acceptance-test` PASS 7 / FAIL 0。

---

## 8. 線上實測明細（2026-10-03 Wave B，PM 序列）

| 工項 | 實測內容與結果 |
|---|---|
| **#4** | `probe-4call`（父）以 Call Activity 呼叫 `countersign-review`：in 映射帶入動態任務名「E2E 法務加簽」→ 子任務在 mgr001 信箱、子實例 key `countersign-review:2`；完成子任務 → **父流程續行並完成**、out 映射 `legalApproved=true`（`LONG_=1`）。⚠️ 第一次探測把 in/out 方向寫反（得到 null）——契約以模板註解為準 |
| **#21** | 建立 `e2e-cb`（回傳 callbackSecret）→ 部署 `probe-21cb`（message catch）→ 帶正確 HMAC＋時間戳回呼 **200 喚醒**；同 deliveryId **200 duplicate**；壞簽章 **401**；缺 deliveryId **400**；rotate 後舊密鑰 **401**、新密鑰 **200** |
| **#5** | Redis 種 `org:substitute:user004=user005` → 外部 `firstTaskAssignee=user004` 的案件任務在 **user005** 信箱（首關代換）；`reassign` 到 user004 **不代換**；`POST /api/admin/tasks/forward-substitutes` **scanned=245 forwarded=1** → 任務到 user005、通知 +1、`TASK_SUBSTITUTE_FORWARD` 稽核；第二次 **forwarded=0**；Redis 事實已清除 |
