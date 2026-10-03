# 接手文件 — 2026-10-03 Wave C 完成（#55＋#59＋#7 通知＋#21 UI）

> ⚠️ **同日稍晚另有 Wave D（#22／#24／#20／小殘餘），見
> `docs/handoff/2026-10-03-round11-handoff.md`（最新）。** 本檔內容仍然有效。

**寫給下一個接手的 PM Agent。** 撰寫時間 2026-10-03（同日，接續 round9）。
上一輪交接見 `docs/handoff/2026-10-03-round9-handoff.md`（**仍然有效**：Wave B）、
`round8`（Wave A）、`round7`（#96／#51 告警／#3／#6／taskId）、
`2026-10-02-round6-handoff.md`、`2026-10-01-round4-handoff.md`（環境陷阱總表）、
`2026-09-30-round3-handoff.md`（授權政策、並行流程）。本檔只寫 Wave C 的新事實。

四個工項各在獨立 worktree 並行，全部經 PM 驗收、合併、完整套件與線上實測；
worktree 與 feature 分支已全部清除。

---

## 0. 現況

| | |
|---|---|
| 後端 | Spring Boot **3.5.16** + Flowable **7.2.0**（未升級） |
| 前端 | Vue 3.4 + Vite 5 + Element Plus |
| 測試 | 後端 **998**（`mvn clean verify`）、前端 **184**（Vitest，18 檔） |
| `main` | merges 至 `cf2463c`／`0eaa500`／`68d046e`／`a8e9a3a` ＋ 文件收尾 commit |
| push | ⚠️ 收尾時 `nsl` 主機仍不可達（`10.127.42.141`）——本日四輪 commit 都在本機 `main`，網路恢復後 `git push nsl main`（**永遠不要裸 push**） |
| worktree | 全部清除 |
| 容器 | dev 容器運行中；`acceptance-test.sh` **PASS 7 / FAIL 0** |

---

## 1. 本輪完成

| 工項 | commit / merge | 內容 |
|---|---|---|
| **#55 表單 Schema 驗證** | `e543d8e` → `cf2463c` | 新 `FormSchemaValidator`＋`FormDataController.submit` 接線；違規 400 逐欄位指名、零副作用 |
| **#59 封存保護** | `77f71c7` → `0eaa500` | 執行中流程使用的表單不可封存（409 指名案件）；draft 有 FormData 不可刪 |
| **#7 撤回通知** | `6caeb41` → `68d046e` | `process_cancelled` 通知現任受理人（刪除前收集、刪除後發送、每任務一則） |
| **#21 管理頁 UI** | `6f5067d` → `a8e9a3a` | 回呼密鑰狀態欄＋輪換按鈕（明文僅顯示一次、關閉即清） |

---

## 2. 本輪重要技術結論

### 2.1 #55：驗證的三個決策

- **未知欄位拒絕（400）**：渲染器只送 schema 定義過的欄位，未知 key 只可能來自
  呼叫端 bug 或注入；且 spec §8.5「欄位 id == 流程變數名」——未知 key 若被轉寫成
  變數就是繞過受保護變數檢查的注入面。
- **schemaJson 毀損 → 500 fail-closed**：seed 四張表與既有測試資料的 schema 都是合法
  JSON（實查），對既有資料零影響；壞 schema 前端本來也渲染不出來。
- **查不到 formDefinitionId → warn＋略過**（已知缺口）：`bpm_form_data.form_definition_id`
  無 FK，既有測試資料就是對不到定義的 id；硬擋會把完整性問題變成送件中斷。
  已用一條測試釘住現況（未來要求真實定義時會紅、強迫重新裁決）。
- 型別表以 `DynamicForm.vue` 的資料契約為準；`number` 不接受數字字串（外部整合若需要再放寬）。
- `PUT /api/form-data/{id}` **不驗**（#58 範圍）——「新增驗、修改不驗」的落差已寫進註解。

### 2.2 #59：用 FormData 而不是 `_formVersions` 判定「使用中」

- `_formVersions` 是「流程定義會用到哪些表單」的**快照**（BPMN 裡有、沒走到也會鎖），
  拿它判定會把「被引用」誤判成「使用中」，且是 Map 型變數無法反查案件。
- `bpm_form_data.form_definition_id` 精確指向「這一版」＋`processInstanceId` 對 running 查詢。
- 跨交易管理器：archive/delete 在 `formTransactionManager`、runtime 查詢是
  `primaryTransactionManager`——唯讀、不寫 Flowable、不 catch Flowable 例外
  （避免 rollback-only）；同型先例 `FormDataController.submit`。
- ⚠️ `bpm_form_data.form_definition_id` **無 index**（新查法全表掃描）——建議另開小項補 migration。

### 2.3 #7 撤回通知：時機與收件人

- **收件人在刪除前收集、發送在刪除後**：任務隨 `deleteProcessInstance` 消失。
- **每任務一則**（平行關卡各受理人不同），與催辦一致。
- 位置在刪除後、稽核前；稽核仍 fail-closed 為最後一道，通知 fail-open。
  窗口（稽核失敗回滾但通知已送）已記載，屬既有取捨。
- 收件人規則抽成 `NotifyPublisher.taskRecipients(TaskService, Task)`；
  ⚠️ **收斂未完成**：`TaskController.taskRecipients` 與 `TimeoutNotifyDelegate.candidateUsers`
  仍是同規則副本（不在本輪檔案邊界），javadoc 已記待收斂項。

### 2.4 #21 UI：一次性明文

- 狀態欄（`***`＝已設定／null＝未設定）＋輪換按鈕；成功才開對話框、`@closed` 清狀態。
- 失敗訊息集中在 `http.js` 攔截器（view 不重複 toast，與 repo 規則一致）。
- ⚠️ **建立外部系統時回傳的 `callbackSecret` 仍被丟掉**（既有缺口，建議另開小項）。

---

## 3. 已知殘餘

| 殘餘 | 說明 |
|---|---|
| #55 | 查不到的 formDefinitionId 略過驗證；PUT 不驗；未涵蓋 maxLength/min/max/pattern/options/日期格式 |
| #59 | `form_definition_id` 無 index；「啟動未送表單」不算使用中（版本鎖定仍可運作） |
| #7 | `reason` 無長度上限；收件人規則三份副本待收斂 |
| #21 | secret 明文存 DB（無 KMS）；建立時明文未顯示；crash 窗口 |
| 沿用 | probe／探測殘留（`probe-4call`／`probe-21cb`／`probe-23timer`／`probe-96ext`）、已停用外部系統、已封存表單 `e2e-archive-probe`、R-21／R-24、#70 Stage 5 |

---

## 4. 環境風險（新增，其餘見 round9／round8／round4）

1. 表單 schema 驗證上線後，**schemaJson 毀損會讓該表單 500 收不了件**（刻意 fail-closed；
   修復方式是新版 draft 發布）。
2. 沿用：Flyway placeholder（SQL 內不可出現錢號加左大括號）、多工項 migration 版本衝突
   （合併後先 `ls db/migration/core/`）、`POST /api/deployments` 的 name 要含 `.bpmn20.xml`、
   `mvn clean verify`、`DOCKER_HOST` exports、外部 API 需 `X-API-Key`＋`X-System-Id`、
   `git commit` 提交整個 index、`IntegrationTestBase.SERVLET_PORT` 單一 port。

---

## 5. 已裁決與待裁決

### 5.1 本輪（無新使用者裁決；沿用既有）

- #7 撤回通知：實作採「通知現任受理人、每任務一則」的合理預設，使用者未反對即沿用。
- #21 UI、#55、#59 均為既有工項描述的直接落實。

### 5.2 待裁決／可開新工項

1. **#55 缺口**：查不到 formDefinitionId 要不要改為拒絕（需先處理既有測試資料）。
2. **#59 index**：`bpm_form_data.form_definition_id` 補 index（0.2d）。
3. **#21 建立時顯示 callbackSecret**（0.2d）。
4. **收件人規則收斂**（`TaskController`／`TimeoutNotifyDelegate` 改呼叫共用 helper，0.3d）。
5. 下一輪主軸（見 §6）。

---

## 6. 建議的下一輪優先序

| 順序 | 工項 | 估時 | 備註 |
|---|---|---|---|
| 1 | **#58 表單資料更新版本控制**（PUT 的本人／退回狀態檢查；可順帶接 #55 驗證） | 1d | 與 #55 同檔，注意 |
| 2 | **#56 動態選項（API 載入）** | 2d | Form Service 剩餘 ⬜ |
| 3 | **#22 External Worker Task**／**#24 Signal Event** | 3d／1d | 非同步主線 |
| 4 | 小項批次（§5.2 的 2～4） | ~0.7d | 可與上面並行 |
| 5 | **#65 API 文件**（OpenAPI）／**#63～#64 測試** | 2d／5d | 上線前；#65 需決定 prod 是否曝露 |
| 6 | **#70 Stage 5**（Boot 4＋Flowable 8） | 22d | `ExtensionElementPreservationTest` 先紅 |

**部署前檢查清單（沿用＋新增）**：權限碼指派；prod secrets；`allowedProcessKeys` backfill；
#51 告警收件人；外部系統 callback secret 逐一 rotate；**表單 schema 若毀損需先修復**。

---

## 7. 統計

- 工項 **96**：✅ **70**、🟡 **10**、⬜ **16**，剩餘估時上限 **~64 人天**。
- 後端 **998**；前端 **184**；`acceptance-test` PASS 7 / FAIL 0。

---

## 8. 線上實測明細（2026-10-03 Wave C，PM 序列）

| 工項 | 實測內容與結果 |
|---|---|
| **#55** | 真實 leave-request 定義（id `2AAB217D-…`）＋執行中案件：缺 required → **400** 指名 `leaveType`／`dateRange`；未知欄位 `hacker` → **400** 指名；合法資料 → **200** 且資料列落地 |
| **#59** | 對使用中（同一案件）的 leave-request archive → **409** 訊息指名案件 id；另建 `e2e-archive-probe` → publish → archive **200**（status=archived） |
| **#7 通知** | 撤回有 mgr001 待辦的案件 → **200**；mgr001 收到「【BPM】案件已被撤回：主管審核」（delta=2＝啟動待辦信＋撤回信）；`PROCESS_CANCEL` 稽核仍在 |
| **#21 UI** | 僅 vitest（26 條）；**未在真實瀏覽器驗證**（列殘餘） |

