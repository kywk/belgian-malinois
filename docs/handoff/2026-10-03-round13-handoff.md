# 接手文件 — 2026-10-03 Wave F 完成（待決策六項落實）

> ⚠️ **同日稍晚另有 Wave G（#97／#44／#45／#46／#47），見
> `docs/handoff/2026-10-03-round14-handoff.md`（最新）。** 本檔內容仍然有效。

**寫給下一個接手的 PM Agent。** 撰寫時間 2026-10-03（同日，接續 round12）。
上一輪交接見 `docs/handoff/2026-10-03-round12-handoff.md`（**仍然有效**：Wave E）、
`round11`（Wave D）、`round10`（Wave C）、`round9`（Wave B）、`round8`（Wave A）、
`round7`（#96／#51／#3／#6／taskId）、`2026-10-02-round6-handoff.md`、
`2026-10-01-round4-handoff.md`（環境陷阱總表）、`2026-09-30-round3-handoff.md`。
本檔只寫 Wave F 的新事實。

五個工項各在獨立 worktree 並行，全部經 PM 驗收、合併、完整套件與線上實測；
worktree 與 feature 分支已全部清除。

---

## 0. 現況

| | |
|---|---|
| 後端 | Spring Boot **3.5.16** + Flowable **7.2.0**（未升級） |
| 前端 | Vue 3.4 + Vite 5 + Element Plus |
| 測試 | 後端 **1151**（`mvn clean verify`）、前端 **200**（Vitest） |
| `main` | merges 至 `541c468`／`6cdd1a2`／`058aee3`／`43eb5f0`／`f845756` ＋ 文件收尾 commit |
| push | ⚠️ `nsl` 主機本日整天不可達（`10.127.42.141`）——本日七輪 commit 都在本機 `main`，網路恢復後 `git push nsl main`（**永遠不要裸 push**） |
| worktree | 全部清除 |
| 容器 | dev 容器運行中；`acceptance-test.sh` **PASS 7 / FAIL 0**；Flyway V7 已套用 |

---

## 1. 本輪完成（使用者 2026-10-03 核准的六項決策）

| 決策／工項 | commit / merge | 內容 |
|---|---|---|
| **D1 #22 topic 白名單** | `42726c0` → `541c468` | `allowedWorkerTopics`（V7；空＝不限制）＋policy 檢查（acquire／查詢，碰 job 前 403）＋管理頁 |
| **D2 #58 版本化** | `09e5254` → `6cdd1a2` | PUT 三道閘門：送件人（404）→ 退回狀態（409）→ 版本化新增列 |
| **D3 #56 動態選項** | `3f106ad` → `058aee3` | 後端代理＋`WebhookUrlPolicy`（先於快取）＋Redis 快取＋前端遠端優先/靜態 fallback |
| **D4 #65 OpenAPI** | `a7d1310` → `43eb5f0` | springdoc 2.9.1；prod 關閉、dev/test 開放（雙層防護） |
| **D6 #43 to 補網域** | `ec52679` → `f845756` | `to` 無 `@` 自動補 `@company.com` |
| D5／D7 | — | 任意節點暫不做；parking 維持現狀（已定案，無工項） |

---

## 2. 本輪重要技術結論

### 2.1 #22：白名單的拒絕選 403、空＝不限制

- `allowedWorkerTopics` 沿用 `ExternalSystemPolicy.allowed()` 四態（JSON 陣列或逗號分隔；`[]`＝拒絕全部；null／空＝不限制）。
- **403 而非 404**：判定只讀呼叫端自己的設定、在碰任何 job 前完成；別人的 topic 存不存在不在輸入裡 → 不洩漏。
- ⚠️ 既有系統在管理員設定前**不受此檢查約束**（R-21 同型已知狀況）。
- ⚠️ UI 仍無法勾選 `external_worker` 權限（#22 既有前端缺口，未修）。

### 2.2 #58：三道閘門與版本化

- 順序：**送件人**（`existing.getSubmittedBy()`；非送件人 404＋`DATA_ACCESS`）→ **身分**（body 冒用 400）→ **退回狀態**（現任任務 `taskAssignee(caller)` 含 `NotifyPublisher.isRevisionTask`，否則 409；已結束亦 409）。
- 版本化：修改＝**新增一列**（同 pid／formDefinitionId／submittedBy／taskId，新 dataJson），舊列保留；`FORM_UPDATE` 稽核含 `supersededFormDataId`。
- 前端消費已 grep 確認：`getFormData`／`updateFormData` 無任何元件呼叫，多列不影響現有畫面。
- ⚠️ 已知：PUT 仍不驗 schemaJson；狀態與寫入間無鎖；多列語意未來前端須取第一筆；候選群組型補件關卡會被 409（內建 BPMN 都是直接指派）。

### 2.3 #56：代理的三個安全點

- **policy 先於快取**（被拒 URL 不會被快取值供應）；**不跟隨 3xx**（`FormOptionsService` 自建 client 明確關閉）；Redis 讀寫壞值 fail-open、policy 維持 fail-closed（方向相反是刻意的）。
- 錯誤語意：非 2xx／非 JSON／逾時 → **502**；policy 拒絕 → 403；未登入 401。
- ⚠️ `/api/forms/options` 與 `/{formKey}` 並存 → **`options` 是 formKey 保留字**。
- 🔴 **發現既有缺口（新工項 #97）**：`WebhookConsumer` 與 `ExternalApiDelegate` **仍跟隨 3xx**，政策只檢查原始 URL → 可被重導到內網。已記入 backlog（0.5d）。

### 2.4 #65：版本證據與雙層防護

- springdoc **2.9.1** 的 parent POM 與本專案同為 Boot 3.5.16（實證；官方矩陣標 2.8.x，若要照矩陣改 property 一行）。
- prod 由 `application.yml` prod 文件 `springdoc.api-docs.enabled=false`／`swagger-ui.enabled=false` **不註冊端點**；`SecurityConfig` 的 permitAll 只服務 dev/test——`OpenApiProdDisabledTest` 是設定層守門人。

### 2.5 #43：`to` 補網域的邊界

- 不含 `@` → 補 `@company.com`；含 `@`（任何位置）→ 原樣；**去重移到補網域之後**（`user001` 與 `user001@company.com` 只寄一次）。
- `a@` 這類壞位址原樣交給 SMTP 拒收（fail-open 只記 log）；網域常數與 `EmailConsumer` 各自維護（已註解警示）。

---

## 3. 已知殘餘

| 殘餘 | 說明 |
|---|---|
| #97（新） | `WebhookConsumer`／`ExternalApiDelegate` 跟隨 3xx 的 SSRF 重導缺口（0.5d，未做） |
| #22 | 空白名單＝不限制（設定前無效）；UI 無 `external_worker` 勾選；complete／fail／unacquire 不查 topic |
| #58 | PUT 不驗 schemaJson；狀態競態無鎖；多列語意待前端配合 |
| #56 | `options` formKey 保留字；`optionsUrl` 存檔不驗；502 會多一次全域 toast（`http.js` 既有行為） |
| #65 | prod 執行期未實測（設定層＋框架語意） |
| 沿用 | probe 殘留（`probe-43-49` 3 版等歷輪）、已停用外部系統、R-21／R-24、#70 Stage 5 |

---

## 4. 環境風險（新增，其餘見 round12／round11／round4）

1. **3xx 重導會繞過 `WebhookUrlPolicy`**（#97；`FormOptionsService` 已關，其他兩個未關）。
2. **`options` 是 formKey 保留字**（#56）。
3. 沿用：`flowable:field` 不依賴 setter 注入、boundary error 不帶未定義 errorRef、EmailNotifyDelegate `to` 補網域、Flyway placeholder、migration 版本、`mvn clean verify`、`DOCKER_HOST` exports、`git commit` 整 index、單一 servlet port。

---

## 5. 已裁決與待裁決

### 5.1 本輪已裁決（使用者 2026-10-03，六項）

1. D1 `allowedWorkerTopics` 白名單；2. D2 送件人＋退回狀態＋版本化；3. D3 後端代理＋政策閘門；
4. D4 prod 關閉 API 文件；5. D5 任意節點暫不做；6. D6 `to` 支援 userId；7. D7 parking 維持現狀。

### 5.2 待裁決／可開新工項

1. **#97** 要不要立即做（0.5d，安全）。
2. **#45 ESignDelegate／#46 ErpSyncDelegate**（需外部系統 mock 設計）。
3. **#60 form-data 同交易／#61 BPMN 部署 Git commit**（跨服務整合）。
4. **#63／#64 測試覆蓋**、**#70 Stage 5**。
5. R-21／R-24 的 backfill（部署前）。

---

## 6. 建議的下一輪優先序

| 順序 | 工項 | 估時 | 備註 |
|---|---|---|---|
| 1 | **#97 重導收斂**＋**#45 ESign**＋**#46 ErpSync**＋**#44 Teams** | 0.5d／3d／2d／2d | Delegate 收尾＋安全小項 |
| 2 | **#60 流程啟動完整流程**／**#61 BPMN 部署 Git commit** | 2d／3d | 跨服務整合 |
| 3 | **#63／#64 測試覆蓋**（各 5d）、**#65 對接文件補強** | 5d／5d | 上線準備 |
| 4 | **#70 Stage 5**（Boot 4＋Flowable 8） | 22d | 建議獨立 session |

**部署前檢查清單（沿用＋新增）**：權限碼指派；prod secrets；`allowedProcessKeys`／
`allowedWorkerTopics` backfill（R-21）；callback secret rotate；worker topic 專屬化；
#51 告警收件人；`JAVA_TOOL_OPTIONS`／prod limits 依 GC log 調；**表單 schema 毀損需先修復**。

---

## 7. 統計

- 工項 **97**：✅ **80**、🟡 **7**、⬜ **8**，剩餘估時上限 **~50 人天**。
- 後端 **1151**；前端 **200**；`acceptance-test` PASS 7 / FAIL 0。

---

## 8. 線上實測明細（2026-10-03 Wave F，PM 序列）

| 決策 | 實測內容與結果 |
|---|---|
| **D1** | `e2e-topic`（白名單 `["e2e-topic"]`）：acquire 白名單內 **200**；未列 topic **403**（訊息指名） |
| **D2** | 審核中 PUT → **409**；非送件人（mgr001）→ **404**；退回後送件人 PUT → **200 新列**；GET 兩列（v2 在前、v1 在後）；`FORM_UPDATE` 稽核含 `supersededFormDataId` |
| **D3** | loopback → **403**（零請求）；公開非 JSON → **502**；未登入 → **401** |
| **D4** | `/v3/api-docs` **200**（title「Greyhound BPM 平台 API」）；`/swagger-ui.html` **302** |
| **D6** | `to=user001` → MailHog 收到 `user001@company.com`（**+1**） |
