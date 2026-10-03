# 接手文件 — 2026-10-03 #70 結案（Stage 6 Jackson 2→3）

**寫給下一個接手的 PM Agent。** 撰寫時間 2026-10-03（同日，接續 round15）。
上一輪交接見 `docs/handoff/2026-10-03-round15-handoff.md`（**仍然有效**：Stage 5）、
`round14`（Wave G）、`round13`（Wave F）、`round12`（Wave E）、`round11`（Wave D）、
`round10`（Wave C）、`round9`（Wave B）、`round8`（Wave A）、`round7`、
`2026-10-02-round6-handoff.md`、`2026-10-01-round4-handoff.md`（環境陷阱總表）、
`2026-09-30-round3-handoff.md`。升級計畫見 `docs/plan/2026-09-28-springboot4-upgrade.md`
（已補 Stage 6 實施結果，狀態為結案）。

本檔只寫 Stage 6 的新事實。分支 `feature/70-stage6` 已合併（`54d8b52`）並清除。

---

## 0. 現況

| | |
|---|---|
| 後端 | **Spring Boot 4.1.1 + Flowable 8.0.0 + Jackson 3**（#70 全數完成，EOL 安全債清償） |
| 前端 | Vue 3.4 + Vite 5 + Element Plus（未動） |
| 測試 | 後端 **1230**（`mvn clean verify`）、前端 **200**（Vitest） |
| `main` | merge `54d8b52`（樹與已驗證分支逐位元相同）＋文件收尾 commit |
| push | ⚠️ `nsl` 主機本日整天不可達（`10.127.42.141`）——本日十輪 commit 都在本機 `main`，網路恢復後 `git push nsl main`（**永遠不要裸 push**） |
| worktree | 全部清除 |
| 容器 | dev 容器跑的是 Jackson 3 build；health 200；`acceptance-test.sh` PASS 7 / FAIL 0 |
| DB | dev 庫 Flowable schema 8.0.0.0（Stage 5 升級，不可逆） |

---

## 1. 本輪完成（`feature/70-stage6`，2 commits）

| commit | 內容 |
|---|---|
| `da10eb1` | 機械式 import 遷移（**45 檔**：main 14＋test 31）：`databind.*`／`core.type.TypeReference`／`core.JsonProcessingException` → `tools.jackson.*`；`annotation.JsonProperty` 不動；FQN 用法一併處理；兩個 API 更名（`JsonNode.fieldNames()→propertyNames()`、`isContainerNode()→isContainer()`） |
| `cda03b1` | `JacksonAmqpConfig` 換 `JacksonJsonMessageConverter`（no-arg）；移除 `spring-boot-jackson2`；移除 `flowable.variable-json-mapper: jackson2`（Flowable 改預設 Jackson 3） |

---

## 2. 本輪重要技術結論

1. **annotation 套件不動**：Jackson 3 的 `@JsonProperty` 仍在 `com.fasterxml.jackson.annotation`。
2. **`JacksonException` 是 unchecked**（`StreamReadException`／`JsonParseException` 為 subtype）；全 repo 0 個 `catch (IOException)`，3 個 catch 已改抓 `JacksonException`。
3. **日期**：Jackson 3 bare mapper 原生支援 `Instant`（ISO-8601 UTC）；**bare Jackson 2.21 反而會丟 `InvalidDefinitionException`**（jsr310 未註冊）。Boot 4 注入的 JsonMapper 日期格式與舊一致（稽核／webhook／通知斷言全綠）。
4. **AMQP converter**：spring-amqp 4.1.1 的 `JacksonJsonMessageConverter` no-arg 會自建 `JsonMapper`（`findAndAddModules`＋關 `FAIL_ON_UNKNOWN_PROPERTIES`／`DEFAULT_VIEW_INCLUSION`），與舊 `Jackson2JsonMessageConverter()` 同形狀；刻意不注入 Boot 的 JsonMapper。
5. **Jackson 2 jar 仍 transitive 存在**（springdoc `swagger-core-jakarta`、Flowable bpmn-model 的 annotations）——被移除的是 `spring-boot-jackson2` 自動配置，不是硬拔所有 Jackson 2 class。
6. 未知欄位／null 預設與舊一致。

---

## 3. 已知殘餘

| 殘餘 | 說明 |
|---|---|
| 前端走查 | 計畫驗收唯一未做項（日期顯示／`Dashboard.vue` 逾期計算）——兩輪升級皆未做 |
| dev audit 鏈 | broken=19／unverifiable=123 為歷史債，非升級造成 |
| 其餘 backlog | 見 §5 |

---

## 4. 環境風險（新增，其餘見 round15／round4）

1. **dev 庫 Flowable schema 8.0.0.0 不可逆**；降版映像不相容（dev only）。
2. **Jackson 3**：例外 unchecked；annotation 套件與 2 相同名（不要誤改）；bare mapper 對 `java.time` 的行為與 2.21 不同。
3. 沿用：Security 7 factor authority（`FACTOR_*`）、AMQP `1 + max-retries`、BPMN 屬性 JSON 單引號、`${processInstanceId}` 陷阱、`mvn clean verify`、`DOCKER_HOST` exports、`git commit` 整 index、單一 servlet port。

---

## 5. 剩餘工項（統計 ~29 人天）

| 類別 | 工項 | 估時 |
|---|---|---|
| Org/Perm | #8（3d）／#9（2d）——**等真實權限中心**，目前無法完成 | 5d |
| Webhook | #28 payloadTemplate 自訂 payload | 2d |
| 通知服務 | #32 Teams 通知整合（delegate 已有，此為通知服務層） | 2d |
| BPMN Lint | #35 EL 白名單逐方法檢查 | 1d |
| 稽核 Log | #41 異常操作偵測 | 3d |
| 基礎設施 | #53 BPMN 環境變數替換（有 secrets 政策面） | 1d |
| 跨服務整合 | #60 form-data 同交易（2d）／#61 BPMN 部署 Git commit（3d）／#63 單元測試（5d）／#64 整合測試（5d） | 15d |

---

## 6. 建議的下一輪優先序

| 順序 | 工項 | 估時 | 備註 |
|---|---|---|---|
| 1 | **#60／#61 跨服務整合** | 2d／3d | 上線前一致性 |
| 2 | **#28／#32／#35** | 1–2d 各 | 功能補齊 |
| 3 | **#41／#53** | 3d／1d | #53 需先定 secrets 政策 |
| 4 | **#63／#64 測試覆蓋** | 5d／5d | 上線準備 |

**部署前檢查清單（沿用＋新增）**：權限碼指派；prod secrets；backfill（R-21／R-24）；
callback secret rotate；worker topic 專屬化；#51 告警收件人；JVM／limits 依 GC log 調；
表單 schema 修復；**prod 映像已是 Boot 4／Flowable 8／Jackson 3**。

---

## 7. 統計

- 工項 **97**：✅ **86**、🟡 **7**、⬜ **4**，剩餘估時上限 **~29 人天**。
- 後端 **1230**；前端 **200**；`acceptance-test` PASS 7 / FAIL 0。

---

## 8. 驗證明細（2026-10-03，#70 Stage 6）

| 項目 | 結果 |
|---|---|
| `mvn -q clean test-compile` | ✅ |
| 受影響測試（agent 批一～批三） | ✅ 526 條 0 紅（真 Testcontainers） |
| **`mvn clean verify`（PM）** | ✅ **1230 全綠** |
| 既有 DB 熱啟動 | ✅ health 200、無錯誤 log（唯一命中是 `ExitOnOutOfMemoryError` 字串） |
| seed＋acceptance | ✅ PASS 7 / FAIL 0 |
| 變數（Jackson 3 variable mapper） | ✅ `_formVersions` 等鍵正確讀回 |
| 通知路徑 | ✅ MailHog 正常（total 255） |
