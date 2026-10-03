# 接手文件 — 2026-10-03 #70 Stage 5 完成（Spring Boot 4.1.1＋Flowable 8.0.0）

**寫給下一個接手的 PM Agent。** 撰寫時間 2026-10-03（同日，接續 round14）。
上一輪交接見 `docs/handoff/2026-10-03-round14-handoff.md`（**仍然有效**：Wave G）、
`round13`（Wave F）、`round12`（Wave E）、`round11`（Wave D）、`round10`（Wave C）、
`round9`（Wave B）、`round8`（Wave A）、`round7`（#96／#51／#3／#6／taskId）、
`2026-10-02-round6-handoff.md`、`2026-10-01-round4-handoff.md`（環境陷阱總表）、
`2026-09-30-round3-handoff.md`。升級計畫本身見 `docs/plan/2026-09-28-springboot4-upgrade.md`
（已補「實施結果」）。

本檔只寫 #70 Stage 5 的新事實。分支 `feature/70-stage5` 已合併（`bccd72c`）並清除。

---

## 0. 現況

| | |
|---|---|
| 後端 | Spring Boot **4.1.1** + Flowable **8.0.0**（Stage 5 完成；Stage 6 Jackson 2→3 待做） |
| 前端 | Vue 3.4 + Vite 5 + Element Plus（未動） |
| 測試 | 後端 **1230**（`mvn clean verify`）、前端 **200**（Vitest） |
| `main` | merge `bccd72c`（樹與已驗證分支 `f71e0b0` 逐位元相同）＋文件收尾 commit |
| push | ⚠️ `nsl` 主機本日整天不可達（`10.127.42.141`）——本日九輪 commit 都在本機 `main`，網路恢復後 `git push nsl main`（**永遠不要裸 push**） |
| worktree | 全部清除 |
| 容器 | dev 容器**跑的是 Boot 4 build**（同 compose project `greyhound`，從分支建置）；health 200；`acceptance-test.sh` PASS 7 / FAIL 0 |
| DB | dev 庫的 Flowable schema 已熱升級至 **8.0.0.0**（不可逆） |

---

## 1. 本輪完成（`feature/70-stage5`，4 commits）

| commit | 內容 |
|---|---|
| `4a7c40b` | S5-0a~0e：Flowable 8.0.0、`variable-json-mapper: jackson2`、Boot 4.1.1、springdoc 3.1.1、`spring-boot-jackson2`、`spring-boot-starter-flyway`、Testcontainers 2.0 座標；yml 屬性遷移（手動核對 OpenRewrite 規則資料） |
| `8fca42b` | Java import 搬遷（7 檔 25 處）＋AMQP retry 語意（`max-retries: 2` 維持總嘗試 3 次）＋`spring-boot-starter-webmvc-test` |
| `7bbddc6` | 🔴 執行期修正：Security 7 閘道 filter 順序、`FACTOR_` 過濾、Flowable 8 `unacquire` 測試、audit 測試隔離 |
| `f71e0b0` | 驗收後移除 `properties-migrator` |

---

## 2. 本輪重要技術結論

### 2.1 🔴 Security 7 的鏈序變動：閘道標頭會蓋過 JWT（安全回歸，實測）

`addFilterBefore(gatewayFilter, UsernamePasswordAuthenticationFilter.class)` 在 Security 6 剛好
排在 Bearer 之後（UPF 在 Bearer 之後）；Security 7 相對順序變了 → 閘道 filter 跑在 Bearer **之前**，
帶閘道密鑰的請求改由 `X-User-Id` 決定身分。修正：`addFilterAfter(gatewayFilter, BearerTokenAuthenticationFilter.class)`
（該類別已搬到 `org.springframework.security.oauth2.server.resource.web.authentication`）。
守門測試：`AuthenticationTest.jwtIdentityWinsOverGatewayHeader`、`MePermissionsEndpointTest.jwtIdentityWinsAndParametersCannotOverrideIt`。

### 2.2 🔴 Security 7 的 `FACTOR_BEARER` authority

bearer 認證會附帶認證因子 authority（`FACTOR_*`），它不是權限碼、也不是候選群組名，
但會出現在 SecurityContext。`AuthorityResolver.isPermissionCode`（排除 `ROLE_`／`FACTOR_`）是唯一過濾點，
`MeController`（permissions 欄位）與 `CandidateGroupMembership`（候選群組線索）都呼叫它。

### 2.3 Flowable 8 的 `unacquire` 語意變更

Flowable 7：只清 job 鎖，exclusive job 的範圍鎖要等原鎖到期；Flowable 8：一併釋放範圍鎖，
其他系統可立刻認領。平台只是包 `ManagementService` API，測試已更新為新語意。

### 2.4 AMQP retry 的語意漂移

`spring.rabbitmq.listener.simple.retry.max-attempts` 移除，改 `max-retries`（總嘗試 = 1 + maxRetries）。
選 `max-retries: 2` 維持「總嘗試 3 次」的既有行為；`WebhookRetryDlqTest` 的斷言同步改寫。

### 2.5 其他實測

- **Testcontainers 2.0 模組更名**（計畫未列）：不改座標連 POM 都讀不進去。
- **springdoc 3.1.1**（計畫未列；2.x 是 Boot 3 線）。
- `spring-boot-jackson2` 讓 5 個 Jackson 2 注入點編譯零錯誤、執行期由全套件綠證明。
- Flowable schema 熱升級 `7.2.0.2 → 8.0.0.0`（common/engine/history/identity/eventregistry）成功。
- 日期格式：Flowable 8 回 **ISO 8601 UTC（`Z`）**；前端 `utils/datetime.js` 集中處理（未做瀏覽器走查）。

---

## 3. 已知殘餘

| 殘餘 | 說明 |
|---|---|
| Stage 6 | Jackson 2→3（1.5–2.5d，不阻斷上線）；`variable-json-mapper: jackson2`／`spring-boot-jackson2` 是遷移期依賴 |
| 前端走查 | 計畫驗收唯一未做項（日期顯示／`Dashboard.vue` 逾期計算） |
| dev audit 鏈 | `integrity-check` broken=19、unverifiable=123 —— **歷史債**（舊 hash bug＋歷輪測試 truncate 的 id 缺口），非本次造成；新寫入完好 |
| 沿用 | probe 殘留、R-21／R-24、各輪小殘餘 |

---

## 4. 環境風險（新增，其餘見 round14／round4）

1. **dev 庫 Flowable schema 已是 8.0.0.0** —— 降回 Boot 3／Flowable 7 的映像會不相容（dev only；測試庫是拋棄式的）。
2. **Security 7**：新增 factor authority（`FACTOR_*`）；自訂 filter 的插入點不要錨在 UPF。
3. **AMQP retry**：`max-attempts` 已不存在，語意是 `1 + max-retries`。
4. 沿用：BPMN 屬性內含 JSON 用單引號、`${processInstanceId}` 在 `stringValue` 是空字串、
   `HttpURLConnection` 不支援 PATCH、Flyway placeholder、migration 版本、`mvn clean verify`、
   `DOCKER_HOST` exports、`git commit` 整 index、單一 servlet port。

---

## 5. 已裁決與待裁決

### 5.1 本輪已裁決（使用者 2026-10-03）

1. 開工 #70；2. Stage 5 驗證後**合併 main**（Stage 6 另議）。

### 5.2 待決策／可開新工項

1. **Stage 6**（Jackson 2→3）何時做。
2. 前端瀏覽器走查（可與其他 wave 並行）。
3. 其餘 backlog：**#28** payloadTemplate／**#32** Teams 通知整合／**#35** EL 白名單逐方法／
   **#41** 異常偵測／**#53** BPMN 環境變數／**#60／#61** 跨服務整合／**#63／#64** 測試覆蓋。

---

## 6. 建議的下一輪優先序

| 順序 | 工項 | 估時 | 備註 |
|---|---|---|---|
| 1 | **#70 Stage 6**（Jackson 2→3） | 1.5–2.5d | 完成後 #70 才 ✅；`JacksonException` 改 RuntimeException、8 檔 |
| 2 | **#60／#61 跨服務整合** | 2d／3d | form-data 同交易、BPMN 部署 Git commit |
| 3 | **#28／#32／#35／#41／#53** | 1–3d 各 | 其餘類別 |
| 4 | **#63／#64 測試覆蓋** | 5d／5d | 上線準備 |

**部署前檢查清單（沿用＋新增）**：權限碼指派；prod secrets；backfill（R-21／R-24）；
callback secret rotate；worker topic 專屬化；#51 告警收件人；JVM／limits 依 GC log 調；
表單 schema 修復；**prod 映像的 `JAVA_TOOL_OPTIONS` 與 Boot 4 建置**。

---

## 7. 統計

- 工項 **97**：✅ **85**、🟡 **8**、⬜ **4**，剩餘估時上限 **~51 人天**
  （#70 依慣例保留原始估時 22d 為上限；**實際剩 Stage 6 約 1.5–2.5d**）。
- 後端 **1230**；前端 **200**；`acceptance-test` PASS 7 / FAIL 0。

---

## 8. 驗證明細（2026-10-03，#70 Stage 5）

| 項目 | 結果 |
|---|---|
| 編譯 | ✅（main 19 處＋test 6 處 import 搬遷；Testcontainers 座標更名） |
| `mvn clean verify` | ✅ **1230 全綠**（冷啟動 Testcontainers；含 5 個 Jackson 注入點的執行期證明） |
| 既有 DB 熱啟動 | ✅ Flowable schema `7202 → 8000`（5 component）、6 秒啟動、health 200 |
| seed＋acceptance | ✅ PASS 7 / FAIL 0 |
| 日期格式 | ✅ `2026-09-29T04:47:50.820Z`（ISO 8601 UTC） |
| 稽核鏈（新寫入） | ✅ 新事件 broken 不變（19→19）；19 條為歷史債 |
| 安全回歸 | ✅ JWT 優先序／`FACTOR_` 過濾皆有測試守門 |
