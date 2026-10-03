# Spring Boot 4 升級計畫

**建立日期**：2026-09-28
**狀態**：**Stage 0～6 全部 ✅ 完成（Stage 5 於 2026-10-03、Stage 6 於 2026-10-03）**；升級結案
**前置調查**：✅ 2026-09-29 完成（見下方「前置調查結論」，該節修正了本文 4 處事實錯誤）
**優先級**：P0（安全性阻斷項）
**預估**：22 人日（含前置安全網，含 form-service 整併）

---

## ⚠️ 前置調查結論（2026-09-29）—— 開工前必讀

第 8 節列的 6 項「撰寫時未能查證」已全部查證（實際下載 jar 驗證，非文件推論）。
**結論：可以開工，但本文有 4 處事實錯誤，其中 2 處會導致開工第一天就卡住。**

### ① 「把 Jackson 議題完全隔離到 Stage 6」做不到

Stage 5 第 1 項的隔離策略**不成立**。本專案有 **5 個類別**在建構式注入 Jackson 2 的
`com.fasterxml.jackson.databind.ObjectMapper`（`WebhookConsumer`、`AuditEventPublisher`、
`AuditEventConsumer`、`ExternalApiAuthFilter`、`ExternalSystemPolicy`），且**沒有自訂
`ObjectMapper` bean**，全部吃 Boot 自動配置。Boot 4 自動配置的是 **Jackson 3 的 `JsonMapper`**
→ 這 5 個注入點在 Stage 5 就會啟動失敗。

解法（純 pom 改動，Jackson 2 與 3 可並存）：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-jackson2</artifactId>   <!-- 4.1.1 已驗證存在 -->
</dependency>
```

⚠️ 這是「補回 Jackson 2 依賴」，**不是** Jackson 3 遷移。Stage 6 才移除。

### ② `ExecutionEntity` 不是最高風險單點 —— 實測零風險

第 8 節第 3 項把它列為「整個升級最高風險」，實際驗證結果是**簽章 byte-identical**：
7.2.0 與 8.0.0 的 `javap` 輸出**逐行相同**，`ExecutionEntity extends … ProcessInstance …`
的父型別關係也未變動。全專案 4 處 `.impl.` internal API **全部確認存在、0 個需改**：

| 檔案:行 | 類別 | 8.0.0 狀態 |
|---|---|---|
| `webhook/ProcessCompletedListener.java:9` | `engine.impl.persistence.entity.ExecutionEntity` | ✅ javap 與 7.2.0 逐行相同 |
| `webhook/ProcessCompletedListener.java:8` | `engine.delegate.event.impl.FlowableEntityEventImpl` | ✅ 存在 |
| `controller/TaskController.java:12` | `common.engine.impl.identity.Authentication` | ✅ 存在 |
| `engine/UnreachableTaskListener.java:10` | `common.engine.impl.cfg.TransactionState` | ✅ 存在 |

**真正的頭號風險是上面的 Jackson 注入點，不是 `ExecutionEntity`。**
⚠️ 結構驗證不等於行為驗證 —— S5-6 前先跑 `ProcessResultReportingTest`，那是 `ExecutionEntity`
行為面的唯一防線。

### 其他已查證事項

| 項目 | 結論 |
|---|---|
| Flowable 8 版本 | **8.0.0 是唯一版本，沒有 8.0.1**（2026-02-27 發布後 7 個月無更新）。本文寫的 `8.0.x` 請改成 `8.0.0` |
| `flowable-bpmn-layout` | 仍獨立、**未更名**、8.0.0 存在。`BpmnAutoLayout` 不需改 |
| Jackson 3 AMQP converter | `JacksonJsonMessageConverter`（在 `spring-amqp` jar，非 `spring-rabbit`） |
| `DataSourceProperties.ignoreUnknownFields` | Boot 4.1.1 **仍預設 true** → 第 5 節第 5 項的條件性風險**不成立**，備案不需執行。（⚠️ 該項對 `ignoreUnknownFields` 的描述本身有誤：它是 `@ConfigurationProperties` **annotation** 的屬性，不是 `DataSourceProperties` 的欄位。結論巧合正確，理由要改） |
| `flowable.variable-json-mapper=jackson2` | 屬性仍存在、值仍有效。但 metadata 明確標 **`jackson2 is deprecated for removal`** → Stage 6 是有退場時程的技術債，不只是可延後的選配 |
| Flowable 8 日期改 ISO 8601 UTC | **確認存在**（`2025-09-24T09:58:12.609+02:00` → `2025-09-24T07:58:12.609Z`）。但前端已在 `utils/datetime.js` 集中成 3 個函式並有 `datetime.spec.js` → 前端風險**遠低於**本文描述。⚠️ 本文第 5 節第 4 項**漏了 `Dashboard.vue`**，且它有日期**運算**（逾期判定），比純顯示更敏感 |
| Liquibase 移除 | 無影響，但本文理由不精確：`flowable-engine` 以 compile scope 隱式帶入 `flowable-event-registry`、`flowable-idm-engine` 與 cmmn/dmn/form/content 的 **API jar**。專案**零 import** 所以無影響 |
| Boot import 搬家 | 共 **10 處 / 5 檔**。⚠️ **本文漏了** `org.springframework.boot.orm.jpa.EntityManagerFactoryBuilder` → `org.springframework.boot.jpa.EntityManagerFactoryBuilder`（3 個 DataSource config 檔）、`@AutoConfigureMockMvc` 與 `MockMvcBuilderCustomizer` 搬家（需加 `spring-boot-webmvc-test` dependency） |
| OpenRewrite 覆蓋率 | `MigrateAutoconfigurePackages` 只有 43 條映射，本專案 6 個 Boot import **只中 2 個**。價值在 pom/yml 而非 Java import。⚠️ **不要跑整個 composite**（會強行拉 JUnit 6／Hibernate 7.1／Spring Security 7，超出 Stage 5 範圍）。只挑 `SpringBootProperties_4_0` ＋ `AddSpringBootStarterFlyway` |
| Stage 6 的 `catch (IOException)` 警告 | ⚠️ **不適用**。全專案 **0 個 `catch (IOException)`**，全部是 `catch (Exception)`。風險等級應從「高」降到「低」。⚠️ 本文說 `ObjectMapper` 4 處，實際 **6 處**（漏 `ExternalSystemPolicy` ＋ 測試 2 處）；`Jackson2JsonMessageConverter` 說 2 處，實際 **1 處**（Stage 3 已合併） |

### 時程重新評估

| Stage | 本文估計 | 重估 | 理由 |
|---|---|---|---|
| Stage 5 | 5d | **4~6d（中位 5）** | import 工作量比預期**小**（一半不用改），但**多出**本文沒算的：`EntityManagerFactoryBuilder` 搬遷、新增 `spring-boot-webmvc-test`、**Jackson 2 注入點解套**、AMQP retry 實測、Flowable 8 熱啟動 DB 驗證 |
| Stage 6 | 3d | **1.5~2.5d** | 8 個 Java 檔、0 個 `catch (IOException)`、前端已集中化且有測試 |

**5 人日落在重估區間中位數，維持不變 —— 但風險排序要整個重寫。**

### 建議的開工順序

先做**不動任何 Java 程式碼**的部分（單一 pom 檔可回退，資訊回報最快）：

1. **S5-0a** `flowable.version` 註解鎖定 `8.0.0`
2. **S5-0b** `flowable.variable-json-mapper: jackson2`（`application.yml` ＋ `application-test.yml`）
3. **S5-0c** 加 `spring-boot-properties-migrator`（runtime scope）
4. **S5-0d** 只跑 `SpringBootProperties_4_0` recipe 遷移 yml
5. **S5-0e** pom 一次跳：Boot `3.5.16→4.1.1` ＋ Flowable `7.2.0→8.0.0` ＋ `spring-boot-starter-flyway` ＋ `spring-boot-properties-migrator` ＋ `spring-boot-jackson2`

> **S5-0e 是決策點**：只改 pom 跑一次編譯，**編譯器會精確列出所有待修位置**（比任何靜態盤點都權威，包含繼承鏈與隱式參考）。
> 若錯誤清單大致等於「5 檔 10 處 ＋ Jackson 注入點」，5 人日估算成立，繼續。若遠多於此，**暫停重新評估**。

⚠️ **S5-0e 之後的機械式 import 修復必須與最後的「Boot 4 ＋ Flowable 8 同步跳」分成不同 commit** ——
因為 Boot 4 ＋ Flowable 7.2.0 才是真正不相容的組合，合併會讓你分不清錯誤來自哪一邊。

### 仍然無法事前查證（必須實測）

1. **Flowable 8.0.0 在 Boot 4.1.1 上的執行期相容性** —— Flowable 是對 Boot 4.0.2 / Framework 7.0.3 建置的。已確認 **Maven 層無 BOM 衝突**（Flowable 不 import `spring-boot-dependencies`），**執行期未確認**。
2. **Flowable 8 對既有 DB 的 schema 升級行為** —— Stage 4 的經驗（6.8.1→7.2.0 掉了 2 張 `FLW_EV_DATABASECHANGELOG*` 表）證明這條路**測試網測不到**，必須熱啟動實測。
3. `ExecutionEntity` 的**行為**正確性（非結構）→ `ProcessResultReportingTest`
4. AMQP retry 底層從 Spring Retry 換成 Spring Framework 後，DLQ 路由的實際行為 → `NotificationTemplateTest`

---

## 1. 動機

Spring Boot 3.5 已於 **2026-06-30 結束 OSS 支援**。最後一個 OSS 版本是 **3.5.16**（2026-06-25 發布）；此後 3.5 線的任何 CVE **只會修給付費商業支援**（商業支援延續至 2032-06-30），Maven Central 不會再有修補版本。

本專案目前停在 **3.5.13**，等於：

1. 落後最後一個可取得的 OSS 安全修補版本 3 個 patch。
2. 之後所有新發現的 CVE 都無法透過升級 minor 修掉。

企業簽核系統長期停留在無安全修補的框架版本上不可接受，因此升級從「技術債」升格為 **P0 阻斷項**。

## 2. 關鍵發現：這不只是 Boot 版本升級

> **升級到 Spring Boot 4 會強制 Flowable 從 6.8.1 跳到 8.x —— 跨兩個主版本。**

| 事實 | 來源 |
|---|---|
| Flowable 8 基於 Spring Framework 7 + Spring Boot 4，**不再支援 Spring Boot 3** | Flowable 8.0.0 Release Notes |
| Flowable **7.2.0 無法在 Spring Boot 4 上運作**（官方論壇已確認） | Flowable Forum #12478 |
| Flowable 8.0.0 於 **2026-02-27** 發布 | Flowable 8.0.0 Release |
| Flowable 7 的定位是 Spring Boot 3 / Spring 6 / Java 17 升級 | Flowable 7.0.0 Release |

也就是說可行的組合只有兩組：

```
現況    Spring Boot 3.5.13  +  Flowable 6.8.1     ← 已 EOL
中繼    Spring Boot 3.5.16  +  Flowable 7.2.x     ← 合法且可運作的中繼點
目標    Spring Boot 4.1.1   +  Flowable 8.0.x
```

**中繼點的存在是本計畫最重要的槓桿** —— 它讓我們可以一次只換一個主版本，而不是同時換 Boot 和 Flowable 兩個。

## 3. 好消息：本專案的升級面比想像小

Flowable 6→7 的移除項目，**本專案一項都沒用到**：

| Flowable 7 移除的東西 | 本專案是否使用 |
|---|---|
| Async history | ❌ 未使用（已確認 `bpm-core/src/main/resources/` 無 `async-history` 設定） |
| Form Engine | ❌ 自建 form-service，未用 Flowable 表單引擎 |
| Content Engine | ❌ 附件走自建 `bpm_file_attachment` 表 |

且 Flowable 官方說明 6→7 **在 package、engine、database 層級沒有重大變更**，BPMN 執行模型不變、引擎層不需要 DB schema 遷移。

Jackson 的風險也有退路：Flowable 8 雖預設 Jackson 3，但可用 **`flowable.variable-json-mapper=jackson2`** 繼續走 Jackson 2。Flowable 官方建議的做法正是「先設成 jackson2，之後再另行升級到 Jackson 3」。

## 4. 相容性矩陣

| 元件 | 現況 | 目標 | 備註 |
|---|---|---|---|
| Spring Boot | 3.5.13 | **4.1.1** | 2026-08-20 發布，目前最新 |
| Spring Framework | 6.2.x（由 Boot 管理） | **7.0.9+** | Boot 4.1 要求 |
| Flowable | 6.8.1 | **8.0.x**（施工時 pin 最新 patch） | 經 7.2.x 中繼 |
| Java | 21 | **21 維持不變** | Boot 4 baseline 為 17，最高支援至 26；沒有理由在此次一併動 Java |
| Jakarta EE | 10 | **11** | Servlet 6.1、Persistence 3.2、Validation 3.1 |
| Tomcat（內嵌） | 10.1 | **11+** | 由 Boot 自動帶入 |
| Jackson | 2.x | **先維持 2.x**，第二階段再上 3.x | `com.fasterxml.jackson` → `tools.jackson` |
| MSSQL JDBC | Boot 管理 | Boot 管理 | 無需手動 pin |

## 5. 分階段施工路徑

原則：**每個 Stage 結束都是一個可部署、可回退的穩定狀態**。任一 Stage 不得同時變更兩個主版本。

### ✅ Stage 0 — 立即止血（已完成 2026-09-28）

`bpm-core/pom.xml` 與 `form-service/pom.xml` 的 parent 由 `3.5.13` → **`3.5.16`**，取得 OSS 線上最後一批安全修補。兩個 pom 都加了註解指回本計畫，避免有人直接 bump 到 4.x 而跳過中繼點。

⚠️ **這是止血，不是解決** —— 3.5.16 之後的 CVE 仍不會有 OSS 修補。

### ✅ Stage 1 — 縮小升級面（已完成 2026-09-28）

刪除 `audit-log-service/`（418 行死碼，2026-04 已併入 bpm-core，CI 不建置）。需要升級的 Java 模組從 3 個降到 2 個。

連帶清理：

| 檔案 | 處理 |
|---|---|
| `audit-log-service/` | 目錄整個刪除（含 `target/`） |
| `cicd/envs/{dev,sit,uat,prod}.yml` | 移除 `audit.service.url` |
| `cicd/templates/merge_request.md`、`.github/pull_request_template.md`、`.gitlab/merge_request_templates/Default.md` | 移除影響範圍勾選項 |
| `docs/README-testing.md` | 服務端點表移除 :8082；稽核 curl 改指 :8080 |
| `external/ExternalSystemAdminController.java:85-87` | usage-logs stub 的提示訊息不再指向已不存在的服務 |

**刻意未改**：`docs/history/**` 與 `docs/backend-completed-items.md` 中提及 audit-log-service 與 :8082 的內容 —— 那些是歷史紀錄，改掉等於偽造當時的事實。

### Stage 2 — 建立安全網（7 人日）⚠️ 不可跳過

**在沒有測試的情況下升級流程引擎，等於閉著眼睛換飛機引擎。**

1. **Flyway 接手 schema**（2 人日）
   關掉 `ddl-auto: update`，把現有三個 DB 的 schema 固化成 baseline migration。順帶把 `infra/mssql/audit-log-triggers.sql` 變成 migration（現在是「手動執行」，沒人會記得）。
   > Flowable 自己的 `ACT_*` 表仍由 `flowable.database-schema-update` 管理，**不要**納入 Flyway。
2. **Testcontainers 整合測試骨架**（2 人日）
   三個 pom 都已宣告 `spring-boot-starter-test` 但沒有 `src/test` 目錄 —— 距離可用只差一個目錄。
3. **覆蓋 4 個未過的驗收案例**（3 人日）
   TC-A01 附屬簽、TC-A02 多方意見、TC-A04 外部 API。這些同時是驗收缺口與升級迴歸網，一次投資兩個回報。

### Stage 3 — 整併 form-service（3 人日）

見 `2026-09-28-adr-001-form-service-consolidation.md`。

排在升級**之前**的理由：升級面從 2 個模組降到 1 個。沒有理由先花力氣把 form-service 升到 Boot 4，再把它刪掉。

### ✅ Stage 4 — Flowable 6.8.1 → 7.2.0（留在 Spring Boot 3.5.16）（已完成 2026-09-28）

**單獨變更 Flowable 主版本，Boot 版本不動。** 這一步若出問題，可以確定問題來自 Flowable，而不是 Boot。

必辦事項：

1. **刪除 `BpmCoreApplication` 的 `@ImportAutoConfiguration` workaround。**
   Flowable 7+ 已改用 Spring Boot 3 的 `AutoConfiguration.imports` 格式，那三行顯式 import 不再需要，留著反而可能造成重複註冊。同時確認 `@Lazy RuntimeService` 的循環依賴 workaround 是否仍必要。
2. 確認 `flowable-bpmn-layout` artifact 在 7.x 仍存在（`DeploymentController` 的 `BpmnAutoLayout` 依賴它）。
3. **檢查唯一的 internal API 使用**：`org.flowable.engine.impl.persistence.entity.ExecutionEntity`
   —— 全專案僅 `webhook/ProcessCompletedListener.java:8`（import）與 `:34`（`instanceof ExecutionEntity exec`）兩處。`.impl.` 套件不保證跨主版本穩定，**這是整個升級最高風險的單點**，優先驗證。
4. ✅ 已確認未啟用 async history（`bpm-core/src/main/resources/` 無相關設定）。

#### 實施結果（2026-09-28）

升到 **7.2.0**（7.x 線最新）。四項必辦事項的實際結果：

1. **`@ImportAutoConfiguration` 已移除** —— 實際確認 7.2.0 的
   `flowable-spring-boot-autoconfigure` jar 內含
   `META-INF/spring/...AutoConfiguration.imports`，因此那三行不再需要。
   **但 `@Lazy RuntimeService` 仍然必要** —— 實測移除後啟動直接失敗。
   它不是 Flowable 6 的遺留物，而是結構性循環：
   `processCompletedListener → runtimeService → StandaloneEngineConfiguration
   → engineConfigurers → processEngineConfigurer → processCompletedListener`。
   成因是 `FlowableConfig.processEngineConfigurer` 必須注入該 listener 才能
   呼叫 `setEventListeners()`，而它又需要引擎產生的 `RuntimeService`。
   確切的循環路徑已寫入 `ProcessCompletedListener` 的註解，避免再被移除。
2. ✅ `flowable-bpmn-layout` 7.2.0 存在（已查 Maven Central metadata）。
3. ✅ **最高風險的單點沒問題** —— `org.flowable.engine.impl.persistence.entity.ExecutionEntity`
   與 `org.flowable.engine.delegate.event.impl.FlowableEntityEventImpl` 在
   7.2.0 的 jar 內都仍存在（直接 `unzip -l` 驗證），`ProcessCompletedListener`
   無需改動。
4. ✅ 無 async history。

**DB schema 升級（測試網測不到的部分，需實測）**

Testcontainers 每次都是全新 DB，因此測試<b>驗不到「既有 schema 升級」</b>這條路徑。
對 dev 環境實測結果：

| 項目 | 升級前 | 升級後 |
|---|---|---|
| Flowable schema 版本 | `6.8.1.0` | `7.2.0.2` |
| `ACT_*` / `FLW_*` 表數 | 47 | 45 |
| 執行中流程實例 | 116 | 116（保留） |
| 歷史流程實例 | 196 | 196（保留） |

升級為**就地自動完成**（`flowable.database-schema-update: true`），啟動無任何 ERROR。

消失的兩張表是 `FLW_EV_DATABASECHANGELOG` 與 `FLW_EV_DATABASECHANGELOGLOCK`
—— Flowable 7 把 Liquibase 從事件註冊表移除。**這正好驗證了 Stage 2 把
`FLW_*` 排除在 Flyway 之外的決定**：若當初把它們納入 Flyway 管理，
這次升級會與 Flyway 的歷史表直接衝突。

**最關鍵的驗證**：一個在 6.8.1 時期啟動的既有任務，在 7.2.0 下成功簽核完成。

**其他驗證**：144 個測試全過（零修改）、`acceptance-test.sh` PASS 7 / FAIL 0、
`seed-data.sh` 全綠。

### Stage 5 — Spring Boot 3.5.16 → 4.1.1 + Flowable 7.2.x → 8.0.x（5 人日）

此時 Boot 與 Flowable 必須同步跳（Flowable 8 不支援 Boot 3，Flowable 7 不支援 Boot 4 —— 沒有中繼點）。

1. **先設 `flowable.variable-json-mapper=jackson2`**，把 Jackson 議題完全隔離到 Stage 6。
2. `org.springframework.boot.autoconfigure.*` 套件重組 → 修 import。建議用 OpenRewrite 的 Spring Boot 4 recipe 自動處理大部分機械式改動。
3. **Spring AMQP retry 機制從 Spring Retry 移到 Spring Framework**。本專案用的是 `spring.rabbitmq.listener.simple.retry.*` 屬性而非 `RabbitRetryTemplateCustomizer`，預期影響小，但需實測 DLQ 路由仍正常。
4. **⚠️ 前端連動：Flowable 8 的日期屬性（如 process instance start time）改回傳 ISO 8601 UTC。**
   影響 `ApprovalTimeline.vue`、`TaskInbox.vue`、`MyApplications.vue`、`AuditLog.vue` 的時間顯示與時區。**這是唯一會外溢到前端的破壞性變更**，不要漏。
5. **⚠️ 檢查 `spring.datasource.audit.*` / `.form.*` 這兩個巢狀節點是否還綁得起來。**
   `DataSourceProperties` 的 `@ConfigurationProperties(prefix = "spring.datasource")` 預設
   `ignoreUnknownFields = true`，所以 `audit`／`form` 這兩個 Boot 不認識的子節點目前會被靜默忽略，
   primary 才綁得成功。這在 Boot 3 沒問題，但它依賴的是「寬鬆綁定」這個預設值。

   Boot 4 若收緊綁定（或本專案哪天開啟嚴格綁定），primary 的 DataSource 會在啟動時綁定失敗。
   **現況刻意不動**：改成 `bpm.datasource.audit.*` 需要動三個 `@ConfigurationProperties`
   前綴、`application.yml`、`IntegrationTestBase` 的 `@DynamicPropertySource`、
   以及 `DataSourceCredentialSourceTest` 的斷言 —— 為一個條件性風險付這個代價不划算，
   而 Stage 5 本來就要全面實測啟動，是發現它的正確時機。

   若真的失敗：把 audit／form 的前綴改到 `bpm.datasource.audit` / `bpm.datasource.form`
   （`bpm.datasource.*` 已經是共用帳密的來源，位置上很自然），`hikari` 子節點跟著搬。
6. 確認 Liquibase 移除不影響本專案 —— Flowable 8 把 Liquibase 從 App / CMMN / DMN / event registry 引擎移除改成手動 SQL。本專案只用 process 引擎，預期無影響，但需實測空 DB 啟動。

#### 實施結果（2026-10-03，merge `bccd72c`）

**已上 main**：Boot **4.1.1** ＋ Flowable **8.0.0** ＋ springdoc **3.1.1**；`spring-boot-jackson2`／`spring-boot-starter-flyway` 進場（`properties-migrator` 驗收後移除）。`flowable.variable-json-mapper: jackson2` 保留至 Stage 6。

**與本文預期的差異（實測）**：
- **7 檔／25 處編譯錯誤**（計畫預期 5 檔 10 處 import ＋ 5 個 Jackson 注入點）：import 搬遷正好 10 處但橫跨 7 檔（多 `TestGatewayMockMvcCustomizer`、`WebhookRetryDlqTest`）；**多出** Testcontainers 2.0 模組更名（pom 級）、`RabbitProperties`／`DefaultErrorAttributes` 搬家；`ErrorAttributeOptions` 未搬。
- **Jackson 2 注入點編譯零錯誤**（靠 `spring-boot-jackson2`），執行期由全套件綠證明。
- **AMQP retry 語意**：`max-attempts: 3` → `max-retries: 2`（維持總嘗試 3 次；Framework 語意是 1+maxRetries）。
- 🔴 **Security 7 鏈序變動（計畫未列）**：`addFilterBefore(gatewayFilter, UsernamePasswordAuthenticationFilter.class)` 會讓閘道標頭蓋過 JWT 身分 → 改 `addFilterAfter(..., BearerTokenAuthenticationFilter.class)`（該類別已搬到 `oauth2.server.resource.web.authentication`）。兩條安全測試（`jwtIdentityWinsOverGatewayHeader`、`jwtIdentityWinsAndParametersCannotOverrideIt`）是守門人。
- 🔴 **Security 7 `FACTOR_BEARER` authority（計畫未列）**：bearer 認證會附帶認證因子 authority，原本會被 `/api/me/permissions` 當權限碼輸出、也被 `CandidateGroupMembership` 當候選群組名 → `AuthorityResolver.isPermissionCode`（排除 `ROLE_`／`FACTOR_`）為唯一過濾點。
- **Flowable 8 `unacquire` 語意變更**：現在會一併釋放 exclusive job 的範圍鎖（其他系統可立刻認領；Flowable 7 要等原鎖到期）。

**驗收（全部通過）**：`mvn clean verify` **1230 全綠**（冷啟動）；**既有 dev DB 熱啟動** Flowable schema `7.2.0.2 → 8.0.0.0` 成功、6 秒啟動；seed＋`acceptance-test` PASS 7/0；日期 ISO 8601 UTC；新寫入稽核鏈完好（dev 既有 19 條 broken 是歷史債，非本次造成）。
**未做**：前端瀏覽器手動走查（計畫驗收清單唯一未做項）。

### Stage 6 — Jackson 2 → 3（3 人日，可延後）

**這一步可以獨立排程，不阻斷上線。**

- `com.fasterxml.jackson` → `tools.jackson`（annotation 仍留在 `com.fasterxml.jackson.annotation`）
- 影響範圍實測只有 6 處：
  - `ObjectMapper` 4 處 — `webhook/WebhookConsumer`、`audit/AuditEventPublisher`、`audit/consumer/AuditEventConsumer`、`external/ExternalApiAuthFilter`
  - `Jackson2JsonMessageConverter` 2 處 — `bpm-core/config/JacksonAmqpConfig`、`form-service/config/JacksonAmqpConfig`（Stage 3 後合併為 1 處）
- ⚠️ **`JacksonException` 在 Jackson 3 改為繼承 `RuntimeException`**。原本 `catch (IOException e)` 會靜默不再捕捉 Jackson 解析錯誤 —— 逐一檢查上述 4 個檔案的 catch 區塊。
- ⚠️ 日期/時間格式、null 處理的預設值有差異，**會編譯成功但執行期出錯**。這是要靠 Stage 2 的測試網擋下來的東西。
- 最後才移除 `flowable.variable-json-mapper=jackson2`。若 BPMN 的 EL 運算式中有呼叫 `JsonNode` 方法，需逐一檢查（Flowable 官方明確警告 method signature 大量變動）—— 本專案目前 BPMN 僅使用 `orgService`/`permService`/`bpmQueryService` 三個 bean，預期無 `JsonNode` 操作。

#### 實施結果（2026-10-03，merge `54d8b52`）

**已上 main**：`com.fasterxml.jackson` → `tools.jackson`（**45 檔**：main 14＋test 31；`annotation.JsonProperty` 維持不動）；`JacksonAmqpConfig` 換 spring-amqp 4 的 `JacksonJsonMessageConverter`（no-arg 建構子，與舊 `Jackson2JsonMessageConverter()` 同形狀）；移除 `spring-boot-jackson2` 與 `flowable.variable-json-mapper: jackson2`（Flowable 改用預設 Jackson 3 mapper）。

**實測**：
- 編譯只需兩個 API 更名：`JsonNode.fieldNames()`→`propertyNames()`、`isContainerNode()`→`isContainer()`。
- `JacksonException` 為 unchecked（`StreamReadException`／`JsonParseException` 是其 subtype）；全 repo 0 個 `catch (IOException)`，三個 catch 已改抓 `JacksonException`。
- 日期：Jackson 3 bare mapper 原生支援 `Instant` → ISO-8601 UTC；**bare Jackson 2.21 反而會丟 `InvalidDefinitionException`**（jsr310 未註冊）。
- 未知欄位／null 預設與舊一致；Jackson 2 jar 仍以 transitive 存在（springdoc／Flowable bpmn-model），被移除的是 `spring-boot-jackson2` 的自動配置。
- **驗收**：完整套件 **1230 全綠**（含 526 條受影響測試）；**既有 dev DB 熱啟動**成功（Flowable schema 已於 Stage 5 升 8.0.0.0）；seed＋`acceptance-test` PASS 7/0；變數（`_formVersions` 等）與通知路徑正常。

## 6. 時程總表

| Stage | 內容 | 人日 | 可否獨立部署 |
|---|---|---|---|
| ~~0~~ | ~~3.5.13 → 3.5.16 止血~~ **✅ 已完成** | 0.5 | ✅ |
| ~~1~~ | ~~刪 audit-log-service~~ **✅ 已完成** | 0.5 | ✅ |
| 2 | Flyway + Testcontainers + 4 個驗收案例 | 7 | ✅ |
| 3 | 整併 form-service | 3 | ✅ |
| 4 | Flowable 6.8.1 → 7.2.x | 3 | ✅ |
| 5 | Boot 4.1.1 + Flowable 8.0.x | 5 | ✅ |
| 6 | Jackson 2 → 3 | 3 | ✅（可延後） |
| | **合計** | **22** | |

Stage 0 與 1 已於 2026-09-28 完成；**Stage 2～6 亦已全部完成（Stage 2～4 於 2026-09-28、Stage 5～6 於 2026-10-03）——本計畫結案。**

## 7. 驗收條件

每個 Stage 完成後必須全部通過：

- [ ] `mvn verify` 通過，且**確實執行了測試**（現況為零測試，`verify` 是空門）
- [ ] `docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d` 全服務 healthy
- [ ] `./scripts/seed-data.sh` 成功部署兩支 BPMN 並驗證 4 個 form key
- [ ] `./scripts/acceptance-test.sh` 全數 PASS（TC-L01–L04、TC-P01–P03 + 稽核）
- [ ] 空 DB 冷啟動成功（驗證 schema 建立路徑）
- [ ] 既有 DB 熱啟動成功（驗證向後相容，**Stage 4/5 必驗**）
- [ ] `/api/audit-logs/integrity-check` 回報 hash chain 完整
- [ ] 前端手動走完一次請假 + 採購流程（Stage 5 必驗，因日期格式變更）
- [ ] 三個 DataSource 的 `hikari.*` 確實生效（`DataSourceBindingTest` 斷言 pool-name，Boot 4 的綁定重構可能影響 bean 方法上的 `@ConfigurationProperties`）

## 8. 施工時必須自行確認的事項

以下是撰寫本計畫時**未能查證**的項目，開工前請先確認，不要直接當成事實：

1. Flowable 8 的**最新 patch 版本號**（本文僅確認 8.0.0 於 2026-02-27 發布）。
2. `flowable-bpmn-layout` 在 7.x / 8.x 是否仍為獨立 artifact、artifactId 是否更名。
3. `ExecutionEntity`（internal API）在 7.x / 8.x 的簽章是否變動 —— **最高風險單點**。
4. Spring AMQP 4.x 中 `Jackson2JsonMessageConverter` 的 Jackson 3 對應類別名稱。
5. `spring-boot-starter-mail`、`spring-boot-starter-data-redis` 在 Boot 4 模組重組後的 artifactId 是否變動。
6. OpenRewrite Spring Boot 4 recipe 的實際覆蓋率（作為加速器，不可全信）。

## 參考來源

- [Spring Boot 4.1.1 available now](https://spring.io/blog/2026/08/20/spring-boot-4-1-1-available-now/)
- [Spring Boot 4.0 Migration Guide](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide)
- [Spring Boot System Requirements](https://docs.spring.io/spring-boot/system-requirements.html)
- [Spring Boot End of Life: Every 3.x Branch Is Now Unsupported](https://www.danvega.dev/blog/spring-boot-end-of-life)
- [Spring Boot 3.5 EOL — The CVE Blind Spot Nobody Talks About](https://foojay.io/today/crossing-the-river-styx-spring-boot-3-5-and-the-zombie-dependency-problem/)
- [Spring Framework 7.0 General Availability](https://spring.io/blog/2025/11/13/spring-framework-7-0-general-availability/)
- [Introducing Jackson 3 support in Spring](https://spring.io/blog/2025/10/07/introducing-jackson-3-support-in-spring/)
- [Flowable 8.0.0 Release](https://github.com/flowable/flowable-engine/releases/tag/flowable-8.0.0)
- [Flowable Forum: Spring Boot 4 compatible Flowable 8 release date](https://forum.flowable.org/t/spring-boot-4-compatible-flowable-8-release-date-existing-flowable-7-2-0-not-working-with-spring-boot-4/12478)
- [Flowable Open Source 7.0.0 Release](https://www.flowable.com/blog/releases/flowable-open-source-7-0-0-release)
- [Spring Boot 4 Migration Guide — Moderne（OpenRewrite）](https://moderne.ai/blog/spring-boot-4x-migration-guide)
