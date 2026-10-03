# 接手文件 — 2026-10-04 #41／#53（Wave J；#63／#64 施工中）

> ⚠️ **同日稍晚的 #63／#64（測試覆蓋＋端到端）見
> `docs/handoff/2026-10-04-round20-handoff.md`（最新）。** 本檔內容仍然有效。

**寫給下一個接手的 PM Agent。** 撰寫時間 2026-10-04（接續 round18，同日）。
上一輪交接見 `docs/handoff/2026-10-04-round18-handoff.md`（**仍然有效**：#60／#61）、
`round17`（走查＋#28／#32／#35）、`round16`（#70 結案）、`round15`（Stage 5）、
`round14`（Wave G）、`round13`（Wave F）、`round12`（Wave E）、`round11`（Wave D）、
`round10`（Wave C）、`round9`（Wave B）、`round8`（Wave A）、`round7`、
`2026-10-02-round6-handoff.md`、`2026-10-01-round4-handoff.md`（環境陷阱總表）、
`2026-09-30-round3-handoff.md`。

本檔只寫本輪新事實。兩個分支 `feature/41-anomaly-detection`／`feature/53-env-substitution`
已合併並清除；`main` = `8b0d246`（＋文件收尾 commit）。
**#63（單元測試）／#64（整合測試＋flake 修復）已在兩個 worktree 施工中**（使用者核定的下一批）。

---

## 0. 現況

| | |
|---|---|
| 後端 | Spring Boot 4.1.1 + Flowable 8.0.0 + Jackson 3 + JGit 7.8 |
| 前端 | Vue 3.4 + Vite 5 + Element Plus |
| 測試 | 後端 **1366**（`mvn clean verify`）、前端 **206** |
| `main` | `8b0d246`（本輪 2 merge）＋文件收尾 |
| push | ⚠️ `nsl` 仍不可達；累積約 **110 顆 commit** 在本機 `main`，恢復後 `git push nsl main`（**永遠不要裸 push**） |
| worktree | gh-63／gh-64（施工中） |
| 容器 | dev 容器跑本輪 build、**已還原預設設定**；health 200；acceptance 7/0 |
| DB | dev core schema v8；Flowable schema 8.0.0.0（不可逆） |

---

## 1. 本輪完成

### 1a. #41 異常操作偵測（merge `0807a00`）

- `AnomalyDetector`：`@Scheduled`（fixed delay 60s、首跑延遲 60s）掃稽核 DB 滑動窗口。
  - **大量審批**：同一 operator 10 分鐘內 `TASK_APPROVE`／`TASK_REJECT` ≥ 50（DB 端 `GROUP BY`）。
  - **異常存取**：同一 operator 10 分鐘內 `DATA_ACCESS` `denied:true` ≥ 20（SQL `LIKE` 粗篩＋Java JSON 精判）。
- 告警（沿用 #51 形狀）：`ANOMALY_DETECTED` 稽核（`operator=system`）＋ERROR log＋選配 email
  （`bpm.audit.anomaly.alert-recipients`，預設空＝不寄）；**只放非敏感中介資料**。
- 30 分鐘冷卻（同模式＋operator，記憶體狀態、重啟遺失可接受）。
- 設定 `bpm.audit.anomaly.*`；**test profile 關閉背景掃描**（避免共享稽核 DB 隨機紅燈）；
  `@EnableScheduling` 是本 repo 第一個排程。
- 已知殘餘：跨節點冷卻不共享（多實例可能重複告警）；慢速探測（低於門檻的持續嘗試）不偵測；
  `scanAt` 測試多載是 package-private 生產 API。

### 1b. #53 BPMN 環境變數替換（merge `8b0d246`）

- `BpmnEnvSubstitutor`：只認 `${ENV_[A-Z0-9_]+}`（與 lint 規則 i 的豁免邊界**逐字相同**）；
  值來自 `bpmn.variables.<NAME>`（spec §12.3 樣張；env 覆蓋實測可行：
  `BPMN_VARIABLES_ENV_FINANCE_GROUP=...`）；未設定／只有空白 → **400 fail-closed**；XML 轉義。
- 部署順序：讀原始 → 替換 → **lint 原始＋lint resolved**（有佔位時）→ 寫**原始**檔 →
  Git commit **原始**檔（#61）→ deploy **resolved** → 稽核（`xmlSha256` 原始指紋＋
  `resolvedSha256`＋`envSubstitutions` 名稱清單）。
- **祕密不進 Git**：磁碟與版控永遠是原始 XML；resolved 只在記憶體。
- 已知殘餘：CDATA 內的值不轉義（javadoc 標明）；值不自動 trim；有佔位時 lint 兩次（部署低頻，取正確性）。

---

## 2. 線上實測（dev 容器，2026-10-04）

| 項目 | 結果 |
|---|---|
| 熱啟動＋seed＋acceptance | health 200；PASS 7 / FAIL 0 |
| #53 正向 | `${ENV_FINANCE_GROUP}`（env 注入）部署 200；引擎 XML 為 resolved 值、無佔位；磁碟原始檔仍保留佔位 |
| #53 負向 | `${ENV_NOT_SET_X}` 部署 **400**（訊息指名設定鍵） |
| #41 觸發 | user001 對非參與案件 3 次 `/{id}/variables` → 404＋3 筆 denied DATA_ACCESS；掃描後產生 `ANOMALY_DETECTED`（hitCount=3、threshold=2、window=120s）＋ERROR log |
| 收尾 | dev 容器已重建為**預設設定**（小門檻／ENV 注入都已移除） |

⚠️ 走查備註：`GET /api/process-instances/{id}` **不存在**（404 不是被拒存取）；製造 denied 請用
`/{id}/variables` 或其他有 access guard 的路徑。

---

## 3. 測試可靠性（重要）

`AuditDeliveryTest` 的背景訊息隔離 **flake 已實測 3 次**（PM 完整套件一次：`eventsWithoutIdStillStored`
expected 1 but was 2；agents 各一次）。單跑綠、重跑全綠（1366/1366）。
**已列 #64 必辦**：確定性隔離（stop 後輪詢佇列穩定再 truncate，或整類別保持 listener 停止），
要求連續 3 次＋與其他 audit 類別交錯都綠。

---

## 4. 施工中（#63／#64，使用者核定的下一批）

| 工項 | 內容 | 備註 |
|---|---|---|
| #63 單元測試 | 盤點高風險缺口、8～12 類別、≥150 條單元測試、負控 3 條 | 可選 JaCoCo 佐證 |
| #64 整合測試 | **flake 修復（必辦）**＋流程端到端（formData 啟動→核准→完成、退回、拒絕、採購兩關、撤回）＋外部系統接入缺口 | 共享容器／DEFINED_PORT 限制 |

完成後由 PM 跑完整套件、熱啟動、合併、文件收尾。

---

## 5. 剩餘工項

| 類別 | 工項 | 估時 |
|---|---|---|
| Org/Perm | #8（3d）／#9（2d）——**使用者指示下一批進行**（等真實權限中心／介接決策） | 5d |
| 測試覆蓋 | #63（5d）／#64（5d）——施工中 | 10d |

（#41／#53 完成後：✅ 93、🟡 4、⬜ 0；剩餘上限 ~15 人天。）

---

## 6. 統計

- 工項 **97**：✅ **93**、🟡 **4**（#8／#9／#63／#64）、⬜ **0**。
- 後端 **1366**；前端 **206**；`acceptance-test` PASS 7 / FAIL 0。
