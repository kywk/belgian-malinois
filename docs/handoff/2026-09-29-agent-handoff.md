# 接手文件 — feature/tech-debt-remediation

**寫給下一個接手的 agent。** 撰寫時間 2026-09-29，branch `feature/tech-debt-remediation`，
57 commits，未合併，base 是 `main`。

---

## 0. 先讀這段：三件會讓你重工的事

1. **實際的 repo 在 `/Users/kywk/kywk/nanshan/greyhound`**，不是工作目錄設定的
   `/Users/kywk/kywk/nanshan/sandbox/greyhound`（那裡只有三個空目錄、沒有 git repo）。
   每個 Bash 呼叫都要自己 `cd` 過去 —— **shell 的 cwd 在每次呼叫之間會重置**。

2. **不要動使用者的 4 個未追蹤檔案**（他明確交代過）：
   `backend-development-backlog.md`、`docs/backend-development-backlog.md`、
   `docs/backend-completed-items.md`、`docs/rbac-enterprise-backlog.md`。
   `git status` 一直會顯示它們，那是正常的，**不要 `git add -A` 把它們掃進去**。
   本次所有 commit 都用明確路徑 staging。

3. **這個 repo 出現過 3 次 git index 異常**（`0dd8c46`、`64aef4c`，以及 base 上的
   `f12a8c2`）：commit 內容把先前 commit 的檔案「還原」掉了，工作樹始終正確。
   原因未查明。**因此每次 commit 前後都要檢查**：
   ```
   git diff --cached --stat     # 提交前：確認 staged 的正好是你要的
   git show --stat HEAD         # 提交後：確認實際進去的一樣
   ```
   建議合併前把 57 個 commit 用 `git show --stat` 抽查一遍。

---

## 1. 系統是什麼

繁中企業 BPM 平台，**尚未部署，只有本機開發環境**。

| | |
|---|---|
| 前端 | Vue 3 SPA（`bpm-frontend`），Element Plus，bpmn-js 設計器 |
| 後端 | Spring Boot **3.5.16** + Flowable **7.2.0**，單一模組 `bpm-core` |
| DB | MSSQL 2022，**三個資料庫**：`bpm_core_db`（含 Flowable ACT_*）、`bpm_audit_db`、`bpm_form_db` |
| 其他 | RabbitMQ（稽核與通知投遞）、Redis（組織／權限快取）、MailHog（dev 收信） |
| Docker | **OrbStack**，不是 Docker Desktop |

啟動：
```
cd /Users/kywk/kywk/nanshan/greyhound
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d --build bpm-core
./scripts/seed-data.sh        # 部署 2 支 BPMN + 4 個表單定義
./scripts/acceptance-test.sh  # 應為 PASS 7 / FAIL 0
```

測試：
```
cd bpm-core && mvn verify        # 262 個，全綠
cd bpm-frontend && npx vitest run # 59 個，全綠
```

---

## 2. 使用者的硬規則（必須遵守）

| 規則 | 狀態 |
|---|---|
| 不要升到 Spring Boot 4.x，必須先在 3.5.16 上完成 Flowable 6→7 | Flowable 7.2.0 已完成，Boot 仍 3.5.16 |
| `FlowableConfig.setBeans()` 的 map **必須包含 `notifyTaskListener`** | 已遵守，`purchase-approval` 的 delegateExpression 依賴它 |
| 跟稽核有關的交易一律寫 `@Transactional("auditTransactionManager")` | 已遵守 |
| 不要修改 `docs/history/**` | 未動 |
| **R-18 的止血方式、auditor 角色政策要先問，不要自己決定** | auditor 已於 2026-09-29 決策（見下）；R-18 已決策為部署前阻斷項 |
| 分階段 commit，訊息以繁中為主，要寫清楚**為什麼**這樣改 | 已遵守，commit message 都很長且解釋動機 |

### 使用者已做過的決策（不要再問）

- **認證機制**：使用者直接使用走 **JWT**；Server 之間 API 觸發走**信任閘道**。
  本服務**只驗證 JWT，不簽發、不處理 OIDC 流程** —— 那由另一個服務負責。
- **auditor 政策**：由**權限中心的權限碼 `audit:log:read`** 決定（不是設定檔清單、
  也不是「只有管理員」）。JWT 有 `roles` claim 時優先採用。
- **R-01 範圍**：這一階段只做後端，前端完整登入流程（OIDC 重導、token 更新、
  過期處理）留待後續。

---

## 3. 已完成的工作

全部 57 個 commit 的清單用 `git log --reverse --oneline main..HEAD` 看。分階段摘要：

| 階段 | 內容 | 狀態 |
|---|---|---|
| Stage 1 | 縮小升級面（移除 dead module、bump 3.5.13→3.5.16） | 完成 |
| Stage 2 | Flyway 接手 schema、Testcontainers 整合測試骨架 | 完成 |
| P0 × 6 | 路徑穿越、BPMN EL 範圍、稽核 hash chain、mass assignment、任務變數保護名單、加簽保護 | 完成 |
| NVARCHAR | 全 schema 56 個文字欄位 VARCHAR→NVARCHAR | 完成 |
| P1 × 15 | 稽核操作者、併發任務、三個 fail-open、逾時與快取容錯、公文編號、附件授權、通知模板、DLQ、表單改版 | 完成 |
| Stage 3 | form-service 併入 bpm-core（ADR-001） | 完成 |
| Stage 4 | Flowable 6.8.1 → 7.2.0（Boot 不動） | 完成 |
| 前端測試 | Vitest 框架 + 日期格式化集中（Stage 5 前置防線） | 完成 |
| P2-1／3～8 | webhook、Hikari、稽核缺口、lint 規則、設計器運算式、mock fail-open、快取一致性 | 完成 |
| R-01 | **平台層認證**（Spring Security + JWT + 信任閘道 + 授權矩陣） | 完成 |

**P2-2 不存在**（稽核報告沒有這個編號）。

---

## 4. 本次踩過的坑 —— 不要重犯

### 4.1 環境與工具

| 坑 | 症狀 | 對策 |
|---|---|---|
| `ls` 被 alias（eza 類） | `ls -a` 什麼都不輸出 | 用 `find` / `printf` |
| `cp` / `mv` 被 alias 成互動模式 | `cp -f` **靜默不覆寫**，還原檔案失敗 | 用 `command cp`，或 `git checkout --` |
| `tac` 不存在 | command not found | 用 `git log --reverse` |
| zsh 不做 word splitting | `set -- $spec` 在迴圈裡失效 | 用 `while read` |
| `docker compose exec -T` 會吃掉 heredoc | 迴圈只跑一次 | 內層命令加 `< /dev/null` |
| pyyaml 不在環境裡 | `import yaml` 失敗 | 用 Spring 的 `YamlPropertiesFactoryBean` 在測試裡驗 yml |
| Python 三引號被 JS 字串尾端引號吃掉 | `SyntaxError` 在莫名的行號 | 寫 JS/檔案內容改用 bash heredoc `<<'EOF'` |

### 4.2 測試可信度 —— 本次最重要的發現

**`application-test.yml` 曾寫 `http://localhost:${local.server.port:8080}/mock/org`。**
`webEnvironment = MOCK` 下沒有 `local.server.port`，於是退回預設值 `8080` ——
那是 docker compose 上 bpm-core 容器的 port。**整合測試打的是另外部署的容器，不是自己。**

危害不是慢，而是**測試結果不反映原始碼**：容器裡是舊 build 時，安全性測試會因為
容器已有修正而通過，即使當前原始碼是壞的。我做非空驗證時就被騙過去一次。
停掉容器後 177 個測試有 47 個失敗。

已修（`4ca666d`）：測試自己挑空閒 port、用 `DEFINED_PORT` 起真 Tomcat，並移除 `:8080` 預設值。

> **接手後的紀律：跑完整測試套件時先 `docker compose stop bpm-core`。**
> 套件現在是封閉的，容器開著也能過，但停掉才能證明它是封閉的。

### 4.3 「非空驗證」是本次的核心工作方法

每個守衛測試都要**把它針對的缺陷放回去，確認測試會紅**。本次靠這個方法抓到：

- 上述的測試打到容器問題
- `ProcessResultReportingTest` 原本**重新實作了它聲稱要驗證的邏輯**（自我參照，什麼都沒驗）
- `WebhookUrlPolicyTest` 依賴外部 DNS，偶發失敗
- router 測試把「已在當前路由」的 push 當成導航，守衛從未被執行，斷言「通過」卻沒被驗到

做法：改原始碼放回缺陷 → 跑測試 → 確認紅 → `command cp` 還原。
**不要用 `cp -f`**（見 4.1）。

### 4.4 各領域的具體陷阱

**MSSQL**
- collation 是 `SQL_Latin1_General_CP1_CI_AS`，**VARCHAR 會靜默吃掉非 ASCII**。
  文字欄位一律 NVARCHAR + `use_nationalized_character_data: true`。
- `datetimeoffset` 只有 100ns 精度。Linux 的 `Instant.now()` 有奈秒 →
  稽核 hash 每筆都會回報被竄改。已用 `truncatedTo(MICROS)` 修掉。
  **macOS 上的測試抓不到這個**，只有 docker 測出來。
- 同一批次的 DDL 看不到前面新增的欄位 → `CREATE INDEX` 要包在 `sp_executesql` 裡。
- Hibernate 的 `@Enumerated(STRING)` CHECK 約束只存在於 ddl-auto 建出來的 dev DB，
  migration 在 Testcontainers 會過但 dev 啟動會壞。

**Spring**
- **`@Primary` 的優先序高於「參數名稱剛好相同」**。本專案最嚴重的缺陷
  （`cecdbe4`）就是這樣：`auditTransactionManager` 以型別注入
  `LocalContainerEntityManagerFactoryBean`，拿到的是帶 `@Primary` 的 primary EMF
  → 交易開在 primary、repository 用 audit → `persist()` 永不 flush、commit「成功」、
  **稽核一筆都寫不進去，完全靜默**。所有多 DataSource 的注入點都必須加 `@Qualifier`。
  守衛測試在 `DataSourceBindingTest`（另開 JDBC 連線直接查，繞過一級快取）。
- `FlywayAutoConfiguration` 是 `@ConditionalOnMissingBean(Flyway.class)` ——
  **自己定義 `Flyway` 型別的 bean 會整個關掉自動設定**。要多套 migration 就用
  自訂型別（`AuditFlywayMigrator` 等），不要用 `Flyway` 型別。
- `initializeDataSourceBuilder().build()` **只綁 url/username/password/driver**，
  `hikari.*` 完全不生效。要在 bean 方法上標
  `@ConfigurationProperties("spring.datasource[.x].hikari")`。
- `@Value("${...:}")` 綁到 `List<String>` 時，未設定會變成 `[""]` 而非空清單。
- `@SpringBootTest(MOCK)` **不會建立 MockMvc bean**，要加 `@AutoConfigureMockMvc`。
- `local.server.port` 在 singleton 建構時還不存在（它在 `WebServerInitializedEvent`
  才寫進 Environment）。要在 context 啟動前就選好 port。

**Flowable / JUEL**
- **對 List 越界索引不拋例外，而是回 `null`。** 設計器原本產生
  `${orgService.getManagerChain(initiator, 2)[1]}` → 鏈比要求短時 assignee 為 null
  → 任務建立成功但**對所有人不可見**，永遠卡在引擎裡。已改為 `getManagerAtLevel`
  （永遠不回 null）。
- **未定義的變數名稱會拋 `Unknown property used in expression`**，不會當成 null。
  所以「要求每個呼叫端先塞哨兵變數」的設計會炸 —— 我第一版這樣寫，40 個測試壞掉。
  正解是讓運算式接 `execution`，由 Java 端讀變數（缺少即 null）。
- `beans` 未設定時 Flowable 把**整個 ApplicationContext 當成 EL 命名空間**。
- `FlowableTaskAlreadyClaimedException` 在 `org.flowable.common.engine.api`。
- 結案後 `runtimeService.getVariables()` 取不到變數（執行期資料已清除），
  要查 `historyService`。

**Redis / 快取**
- 同一個事實不可以有兩份快取。`resolveEffective`（1 分 TTL）與 `isUserAvailable`
  （5 分 TTL）都由 `getSubstitute` 推導，造成「同時有空又已委派」的 4 分鐘窗口。
- **權限碼含冒號**（`hr:leave:approve`），而 Redis glob 是整個 key 比對 ——
  `keys("perm:users:hr:leave:*")` 會命中 `perm:users:hr:leave:approve`，
  失效一個權限碼會波及以它為前綴的另一個。key 家族要分 namespace。
- `KEYS` 阻塞單執行緒的 Redis，已改 `SCAN`。

**其他**
- `"".split(",")` 回傳 `[""]`（長度 1），空快取直接 split 會變成「有一個空字串的成員」。
- `safeFileName("..")` 曾回傳 `".."`（點在允許字元集裡）→ 要額外處理全是點的情況。
- 相對路徑讓 `startsWith` 的圍堵永遠為 false → 一律 `toAbsolutePath().normalize()`。
- `bpm_process_variable_spec` 目前是**空表**，所以「用它當白名單」等於擋掉一切。

---

## 5. R-01（認證）接手要點

這是最後完成的一項，也是其餘所有授權工作的前提。

### 兩條路徑

```
使用者   → Authorization: Bearer <JWT>   → 只驗證（不簽發、不做 OIDC 流程）
Server   → X-Gateway-Secret + X-User-Id  → GatewayAuthenticationFilter
外部系統 → X-API-Key（既有 ExternalApiAuthFilter，與閘道並存）
```

### 設定

| 屬性 | dev | prod |
|---|---|---|
| `bpm.security.jwt.issuer-uri` | 空 | `${OIDC_ISSUER_URI}` **必填，否則啟動失敗** |
| `bpm.security.jwt.dev-secret` | 有值 | **必須為空，否則啟動失敗** |
| `bpm.security.gateway.enabled` | true | `${GATEWAY_AUTH_ENABLED:false}` |
| `bpm.security.gateway.shared-secret` | 有值 | 啟用閘道時必填，否則啟動失敗 |

### 取得 dev token

```
./scripts/dev-token.sh admin001          # 走權限中心查權限
./scripts/dev-token.sh user001 admin     # 帶 roles claim（優先於權限中心）
curl -H "Authorization: Bearer $(./scripts/dev-token.sh dir001)" ...
```

### 授權矩陣（`SecurityConfig`）

最後一條是 **`anyRequest().denyAll()`**，不是 `authenticated()`。
**新增端點時一定要同時加規則**，否則會是 403 而不是「預設可用」。這是刻意的。

### ⚠️ 通配權限的陷阱

權限中心給 `admin001` 的權限是 `["*"]`。照字面當 authority 會產生一個名叫 `*` 的
authority，`hasRole('ADMIN')` 不會命中 → **擁有全部權限的管理員被拒絕存取**。
已在 `AuthorityResolver` 明確轉成 `ROLE_ADMIN`。這種 bug 只影響通配持有者，
而開發時通常就是用管理員在測，特別容易漏到上線。

### 測試如何帶身分

測試**扮演信任閘道**（不是為測試開後門）：`TestGatewayMockMvcCustomizer` 以
`defaultRequest` 附上閘道密鑰與預設身分 `user001`。
- 測試自己寫的 `.header("X-User-Id", "xxx")` 會覆蓋預設（MockMvc 對 header 是
  「不存在才補」）。
- 預設身分刻意選**沒有任何權限的 user001**。設成 admin001 會讓所有授權規則在
  測試裡形同虛設。
- 要測「未認證」必須明確把兩個標頭設成空字串（見 `AuthenticationTest.anonymous()`），
  否則測試會在有身分的情況下通過，斷言是空的。

### 前端

- `session.js` 的 `decodeToken()` 現在解 JWT payload（不驗簽章 —— 驗證是後端的事）。
  前端的 `MOCK_ROLE_MAP` 已整張刪除。
- `devToken.js` 的簽發以 `import.meta.env.DEV` 閘住，Vite 在 production build
  把整段連同密鑰移除（**已驗證 `dist/` 不含密鑰**）。
  所以 `docker-compose.yml`（nginx 服 `dist/`）沒有簽發能力，登入畫面改為貼上 token；
  `docker-compose.dev.yml`（Vite dev server）選單登入照常可用。
- `http.js` **不再送 `X-User-Id`**。

---

## 6. 待辦 —— 依建議順序

### 6.1 等使用者決定（不要自己決定）

1. ~~**P1-14 fail-open**：稽核寫入失敗是否該阻擋業務操作？~~ —— **2026-09-29 決策：fail-closed，已完成**。
   稽核掛在業務交易的 `beforeCommit`，失敗即回滾並回 503。寫入類 controller 方法**必須**有限定的
   `@Transactional`（見 `AuditEventPublisher` 類別註解）；新增端點時別漏。
2. **「任務對所有人不可見」的通用防線**：`TASK_CREATED` 監聽器要硬擋還是只記錄告警？
   硬擋會弄壞外部 API 的「先啟動再補候選群組」模式（`ExternalApiController`
   在 `startProcessInstanceByKey` 之後才 `addCandidateGroup`）。
3. **`getFirstAvailableUser` 全部不在時的行為**：目前退化為指派第一位並記 warn。
4. **`AttachmentController` 要不要開 admin／auditor 旁路**：現在有伺服器端角色模型了，
   但「管理員能不能看任何案件的附件」是權責政策。

### 6.2 建議的下一個工作項：Stage 5

**Spring Boot 3.5.16 → 4.1.1 + Flowable 7.2.x → 8.0.x（必須同步跳）**

Boot 3.5 已於 2026-06-30 EOL，之後沒有 OSS CVE 修補。計畫在
`docs/plan/2026-09-28-springboot4-upgrade.md`，已含本次補上的檢查項：

- 先設 `flowable.variable-json-mapper=jackson2`，把 Jackson 議題隔離到 Stage 6
- `org.springframework.boot.autoconfigure.*` 套件重組 → 建議用 OpenRewrite recipe
- Spring AMQP retry 從 Spring Retry 移到 Spring Framework
- **⚠️ 唯一外溢到前端的破壞性變更**：Flowable 8 的日期屬性改回傳 ISO 8601 UTC。
  影響 `ApprovalTimeline.vue`、`TaskInbox.vue`、`MyApplications.vue`、`AuditLog.vue`。
  **已由 `bpm-frontend/src/utils/datetime.spec.js` 守住**（10 個測試）。
- **檢查 `spring.datasource.audit.*` / `.form.*` 這兩個巢狀節點還綁不綁得起來**：
  它們目前靠 `DataSourceProperties` 的 `ignoreUnknownFields = true` 被忽略才
  綁得成功。Boot 4 若收緊綁定，primary 的 DataSource 會在啟動時失敗。
  失敗時的改法已寫在計畫裡。
- 驗收條件已補「三個 DataSource 的 `hikari.*` 確實生效」

### 6.3 其他已知但未處理

| 項目 | 說明 |
|---|---|
| 前端完整登入流程 | OIDC 重導、token refresh、過期處理。依 2026-09-29 決策不在 R-01 範圍 |
| 個案層級授權 | 「誰能簽這張單」仍由 controller 內的 `requireParticipant` 負責。那是資料層授權，無法只靠 URL 表達 |
| `actuator` 只開 health/info | 池飽和無法觀測。開放 metrics 有安全面，建議限內網或加認證 |
| lint 白名單 vs `setBeans()` | 兩份清單。已加測試守「白名單有、執行期沒有」這個危險方向；反方向（`notifyTaskListener` 在 `setBeans` 卻不在白名單）未動 —— 那會讓部署被自己的 lint 擋下，是安全的那邊 |
| 手動瀏覽器走查 | 一直沒做。browser 工具不可用（使用者婉拒擴充套件）。`http.spec.js` 與 `guard.spec.js` 已涵蓋大部分它會驗的東西 |
| R-20 本體 | 外部 API 的 initiator 仍由呼叫端指定，可冒用**真實存在**的員工編號。本次只拿掉「用不存在的身分繞過必填檢查」。徹底修法是讓 initiator 由 server 依 API key 決定 |

---

## 7. 稽核報告在哪

- **`docs/plan/2026-09-28-security-audit.md`** —— 完整系統安全稽核，**P0／P1／P2 的編號來源**
- `docs/plan/2026-09-28-remediation-backlog.md` —— 修復 backlog，**R 編號**在此（R-01、R-18、R-20…）
- `docs/plan/2026-09-28-springboot4-upgrade.md` —— 升級計畫 Stage 1～6，驗收條件在末段
- `docs/plan/2026-09-28-adr-001-form-service-consolidation.md` —— form-service 併入的決策紀錄
- `docs/plan/2026-09-28-handover.md` —— 上一次的接手文件（本次之前的狀態）
- `docs/README-testing.md` —— dev 環境各服務的位址與手動驗證指令
- `CLAUDE.md` —— 專案指引與已知技術債清單

⚠️ `docs/backend-*.md` 與 `docs/rbac-enterprise-backlog.md` 是**使用者的未追蹤檔案**，
可以讀但不要改（見第 0 節）。

---

## 8. 每個工作項的建議流程

本次一直照這個節奏走，效果不錯：

1. **先在原始碼與執行中的服務上確認問題真的存在** —— 不要照稽核報告的描述就動手。
   本次有數次實測結果與報告描述不同，而且**通常比報告更嚴重**
   （JUEL 越界回 null 而非拋錯；外部系統的 mass assignment 可接管授權）。
2. 修，並在註解裡寫清楚**為什麼**、以及**為什麼不用另一種做法**。
3. 寫測試，然後**把缺陷放回去確認測試會紅**。
4. `docker compose stop bpm-core` → `mvn verify` → 全綠。
5. 重建容器 → `seed-data.sh` → `acceptance-test.sh` → PASS 7 / FAIL 0。
6. 對執行中的服務做一次實測（curl／sqlcmd），確認行為與測試一致。
   **本次多次在這一步抓到 Testcontainers 結構上抓不到的問題**
   （空 DB 建立 vs 既有 schema 升級；macOS vs Linux 時鐘精度；相對路徑圍堵）。
7. `git diff --cached --stat` → commit → `git show --stat HEAD`。

---

## 9. 現況一句話

branch 有 57 commits，後端 262 + 前端 59 測試全綠（且後端是在 docker 容器
**停止**的狀態下跑的），`acceptance-test.sh` PASS 7 / FAIL 0，
stack 是 Boot 3.5.16 + Flowable 7.2.0 單一後端模組，平台層認證已啟用。
工作樹只有使用者的 4 個未追蹤文件。**尚未合併到 main。**
