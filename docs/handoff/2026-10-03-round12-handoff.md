# 接手文件 — 2026-10-03 Wave E 完成（#43＋#48＋#49＋#50）

> ⚠️ **同日稍晚另有 Wave F（#22 白名單／#58／#56／#65／#43 補網域），見
> `docs/handoff/2026-10-03-round13-handoff.md`（最新）。** 本檔內容仍然有效。

**寫給下一個接手的 PM Agent。** 撰寫時間 2026-10-03（同日，接續 round11）。
上一輪交接見 `docs/handoff/2026-10-03-round11-handoff.md`（**仍然有效**：Wave D）、
`round10`（Wave C）、`round9`（Wave B）、`round8`（Wave A）、`round7`（#96／#51／#3／#6／taskId）、
`2026-10-02-round6-handoff.md`、`2026-10-01-round4-handoff.md`（環境陷阱總表）、
`2026-09-30-round3-handoff.md`（授權政策、並行流程）。本檔只寫 Wave E 的新事實。

兩個 agent 並行（三個 delegate 共用 `FlowableConfig`／`BpmnFieldSupport`，刻意合成一線），
全部經 PM 驗收、合併、完整套件與線上實測；worktree 與 feature 分支已清除。

---

## 0. 現況

| | |
|---|---|
| 後端 | Spring Boot **3.5.16** + Flowable **7.2.0**（未升級） |
| 前端 | Vue 3.4 + Vite 5 + Element Plus |
| 測試 | 後端 **1100**（`mvn clean verify`）、前端 **187**（Vitest） |
| `main` | merges 至 `894102f`／`6de2f52` ＋ 文件收尾 commit |
| push | ⚠️ `nsl` 主機本日整天不可達（`10.127.42.141`）——本日六輪 commit 都在本機 `main`，網路恢復後 `git push nsl main`（**永遠不要裸 push**） |
| worktree | 全部清除 |
| 容器 | dev 容器運行中；`acceptance-test.sh` **PASS 7 / FAIL 0**；容器 log 已見 `Picked up JAVA_TOOL_OPTIONS` |

---

## 1. 本輪完成

| 工項 | commit / merge | 內容 |
|---|---|---|
| **#43 EmailNotifyDelegate** | `3dfea85` → `894102f` | `flowable:field` `to`／`subject`／`body`；`JavaMailSender` 直寄；fail-open |
| **#48 DataValidationDelegate** | `e24bac9` → `894102f` | `requiredVariables`＋`condition`；失敗 `BpmnError("DATA_VALIDATION_FAILED")` |
| **#49 ExternalApiDelegate** | `bd5478b` → `894102f` | `url`／`method`／`body`／`resultVariable`；URL 過 `WebhookUrlPolicy`；失敗 `BpmnError` |
| **#50 JVM 記憶體** | `67e3d93` → `6de2f52` | `JAVA_TOOL_OPTIONS`（MaxRAMPercentage=75、ExitOnOutOfMemoryError）＋ prod compose limits |
| 共用 | 隨 #43 | `com.bpm.core.engine.BpmnFieldSupport`（三個 delegate 共用欄位讀取） |

---

## 2. 本輪重要技術結論

### 2.1 🔴 `flowable:field` 對 delegateExpression 是 per-execution setter 注入（單例競態）

`javap` 證實：`ProcessEngineConfigurationImpl` 預設 `delegateExpressionFieldInjectionMode = MIXED`；
`DelegateExpressionUtil.resolveDelegateExpression(..., fieldDeclarations)` 在 MIXED 下呼叫
`ClassDelegate.applyFieldDeclaration(fields, bean, false)` —— **每個 execution 對 Spring 單例 bean
做 setter 注入**，並行流程互相覆蓋欄位（無例外、無 log）。

→ 三個 delegate 一律在 `execute` 時從 `execution.getCurrentFlowElement()` 取
`ServiceTask.getFieldExtensions()`，用共用的 `BpmnFieldSupport` 解析（thread-safe）。
`BpmnFieldSupport` 語意：`expression="..."` 走引擎 `ExpressionManager`
（實際 API 是 `CommandContextUtil.getProcessEngineConfiguration()`；`Context.getProcessEngineConfiguration()`
在 7.2.0 不存在）；`stringValue` 做 `${var}` 單輪文字替換，變數缺失/null → 空字串。

### 2.2 三個 delegate 的失敗語意（刻意不同，都有理由）

- **#43 EmailNotify：fail-open**（吞例外只記 log）——通知失敗不得讓流程失敗（與 `NotifyPublisher`
  ／`TimeoutNotifyDelegate` 同一語意）。
- **#48 DataValidation：`BpmnError`**——驗證失敗是業務結果，設計師用 boundary error 走替代路徑；
  沒接住就是明顯失敗。**兩個欄位都沒設定也是 BpmnError**（不讓「什麼都不驗」靜默通過）。
- **#49 ExternalApi：`BpmnError`（BLOCKED／FAILED）**——外部系統失敗是預期內業務情境，可建模；
  訊息不含 response body（可能含對方敏感資料）。
- 🔴 **#49 的 URL 一律先過 `WebhookUrlPolicy`**：BPMN 由業務人員編輯，這個 delegate 等於
  「讓 BPMN 能發任意 HTTP」——`WebhookUrlPolicy` 從此是安全相依（與 #67 同一句話）。

### 2.3 EmailNotifyDelegate 的 `to` 是完整 email（與平台慣例刻意不同）

`EmailConsumer` 的慣例是 userId＋`@company.com`；本 delegate 的 `to` **原樣使用（完整地址）**，
javadoc 已明示。PM 線上實測第一次用 `user001`（平台慣例）→ MailHog 收到 `To: user001`
（SMTP 真實環境會不可達）——**設計師須寫完整地址**；若日後要支援 userId，應在 delegate 內
收斂（勿各自實作）。

### 2.4 #50：容器感知與 limits 的位置

- `ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"`；
  不寫死 `-Xmx`（limit 調整時 heap 跟著長）。
- **limits 只放 `docker-compose.prod.yml`**（bpm-core 2G／mssql 2G／rabbitmq 768M／redis 256M／
  nginx 128M）；dev 刻意不受限（base 檔加註）。bpm-core 2G 是未壓測起點值。

---

## 3. 已知殘餘

| 殘餘 | 說明 |
|---|---|
| #43 | 收件人逗號分隔是第三份實作（`DeadLetterConsumer` private 無法共用）；整合測試不證明 SMTP 真投遞（線上已補） |
| #48 | `requiredVariables` 寫成 `${x}` 會被先替換（誤用；註解已說明） |
| #49 | DNS rebinding TOCTOU 沿用 `WebhookUrlPolicy` 既有邊界；未防 |
| #50 | 值未經壓測；`ExitOnOutOfMemoryError` 不保證早於 kernel OOM-kill |
| 沿用 | probe 殘留（`probe-43-49` 2 版等歷輪）、已停用外部系統、R-21／R-24、#70 Stage 5 |

---

## 4. 環境風險（新增，其餘見 round11／round10／round4）

1. **BPMN 的 `flowable:field` 不要依賴 setter 注入**（§2.1）；新 delegate 一律走 `BpmnFieldSupport`。
2. **boundary error 要攔截所有錯誤時不可帶 `errorRef`**（PM 線上踩到：帶了未定義的
   `errorRef="anyError"` → `No catching boundary event found`、流程 500）。
3. **EmailNotifyDelegate 的 `to` 要完整 email**（§2.3）。
4. 沿用：Flyway placeholder、migration 版本、deployment name 後綴、`mvn clean verify`、
   `DOCKER_HOST` exports、外部 API 雙標頭、`git commit` 整 index、單一 servlet port。

---

## 5. 已裁決與待裁決

### 5.1 本輪（無新使用者裁決；內容照 Wave E 提案執行）

### 5.2 待裁決／可開新工項

1. **#22 共享 topic**：per-system topic 白名單？
2. **#58 表單修改版本控制**（語意待確認）。
3. **#56 動態選項**（SSRF 政策面）。
4. **#65 OpenAPI**（prod 曝露與否）。
5. 下一輪主軸（見 §6）。

---

## 6. 建議的下一輪優先序

| 順序 | 工項 | 估時 | 備註 |
|---|---|---|---|
| 1 | **#45 ESignDelegate／#46 ErpSyncDelegate**（需要外部系統，可先用 mock）＋**#44 TeamsNotify／#47 DynamicAssignee** | 3d／2d／2d／2d | Delegate 類別收尾（剩 8d） |
| 2 | **#60 流程啟動完整流程**（form-data 同交易）／**#61 BPMN 部署 Git commit** | 2d／3d | 跨服務整合收斂 |
| 3 | **#65 API 文件**（需定 prod 曝露）／**#63～#64 測試覆蓋** | 2d／5d | 上線準備 |
| 4 | 小項：#22 topic 白名單／#58／#56 | 0.5–2d | 需先裁決 |
| 5 | **#70 Stage 5**（Boot 4＋Flowable 8） | 22d | `ExtensionElementPreservationTest` 先紅 |

**部署前檢查清單（沿用＋新增）**：權限碼指派；prod secrets；`allowedProcessKeys` backfill；
#51 告警收件人；callback secret rotate；worker 系統 `external_worker` 權限＋專屬 topic；
**`JAVA_TOOL_OPTIONS` 與 prod limits 為未壓測起點值，上線前依 GC log 調**。

---

## 7. 統計

- 工項 **96**：✅ **77**、🟡 **9**、⬜ **10**，剩餘估時上限 **~54.5 人天**。
- 後端 **1100**；前端 **187**；`acceptance-test` PASS 7 / FAIL 0。

---

## 8. 線上實測明細（2026-10-03 Wave E，PM 序列）

| 工項 | 實測內容與結果 |
|---|---|
| **#43／#48／#49** | 部署 `probe-43-49`（validate→email→extApi，兩個 boundary error）→ A `{days:1}`：驗證通過 → **MailHog 收到「E2E Delegate 通知」**（完整地址 +1）→ extApi loopback **被拒且零請求** → boundary 接住 → 流程結束；B `{}`：log「DataValidationDelegate 擋下流程：變數 'days' 不存在或為空白」→ 走替代路徑、零寄信。⚠️ 第一次探測因 `errorRef` 未定義導致 500（已修、記入 §4.2） |
| **#50** | 容器重建後 log：`Picked up JAVA_TOOL_OPTIONS: -XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError`；`docker compose config`（base／dev／prod）三種組合通過 |
