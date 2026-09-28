# CLAUDE.md

本檔提供 Claude Code 在此 repo 工作時所需的專案背景與慣例。
文件語言以**繁體中文**為主（spec、backlog、history 皆為繁中），程式碼註解與 commit 可中英混用。

## 專案定位

企業內部**低程式碼 BPM 流程平台**。核心目標：讓業務人員自行設計、部署、維運 BPMN 流程。

單一事實來源：`docs/bpm-platform-spec.md`（約 1487 行）。任何架構或行為爭議以此為準；§19 決策紀錄是最接近「慣例文件」的部分。

## 架構

```
Vue 3 SPA ──> Nginx (:80) ──> bpm-core :8080   (Flowable 7.2.0 + 表單 + 稽核)
              RabbitMQ (5672/15672) · Redis (6379) · MSSQL 2022 (1433)
```

| 模組 | 說明 | DB |
|---|---|---|
| `bpm-core/` | Spring Boot 3.5.16 ⚠️（3.5 線已 EOL，見下）/ Java 21，內嵌 Flowable **7.2.0**。流程、任務、附屬簽、附件、公文編號、Webhook、通知、BPMN Lint、外部系統 API、**表單 schema 與表單資料**、稽核 | `bpm_core_db` + `bpm_audit_db` + `bpm_form_db`（**三個 DataSource**） |
| `bpm-frontend/` | Vue 3.4 + Vite 5 + Element Plus + Pinia，bpmn-js 17 編輯器、拖拉式表單設計器 | — |

Nginx 路由（`infra/nginx/nginx.conf`）：`/api/` → bpm-core，`/` → SPA。

⚠️ **form-service 已於 2026-09-28 併入 bpm-core**（Stage 3 / ADR-001）。合併的是**部署單元**，不是**資料模型** —— `bpm_form_db` 仍是獨立資料庫，表單程式碼在 `com.bpm.core.form.*`。

⚠️ **三個 DataSource 的注入點一律要寫 `@Qualifier`**。`primaryEntityManagerFactory` 帶 `@Primary`，而依型別注入時 `@Primary` 的優先序**高於「參數名稱剛好相同」**。少寫一個 qualifier，該 persistence unit 的寫入就會**靜默不落地**（沒有例外、沒有錯誤日誌、commit 還「成功」）—— 這正是 commit `cecdbe4` 造成稽核長期一筆都沒寫的原因。`DataSourceBindingTest` 會抓這件事（已驗證移除 qualifier 後它真的會失敗）。

## 待開工計畫

計畫與決策文件集中在 `docs/plan/`（索引見 `docs/plan/README.md`）。**動到框架版本、服務邊界或安全性之前先看該目錄**，不要重新規劃已經決定的事。

- `docs/plan/2026-09-28-handover.md` —— ⚠️ **先讀**：`feature/tech-debt-remediation` 分支的交接，所有程式碼變更皆**未經編譯**
- `docs/plan/2026-09-28-security-audit.md` —— 全系統安全與正確性審查（40+ 項，含一條 RCE 路徑）
- `docs/plan/2026-09-28-springboot4-upgrade.md` —— Boot 4 + Flowable 8 分階段升級（22 人日）
- `docs/plan/2026-09-28-adr-001-form-service-consolidation.md` —— form-service 併入 bpm-core（3 人日，提議中）
- `docs/plan/2026-09-28-remediation-backlog.md` —— 工程品質與安全性改進 R-01 ~ R-25

部署狀態：**尚未部署，只有本機開發**。因此 Stage 2 測試網優先於 R-01 認證授權。

⛔ **部署前阻斷項（2026-09-28 決策）**：R-18（`/api/admin/**` 完全無認證）在 R-01 完成前，本系統**不得部署到任何多人可連線的環境**（含 SIT／UAT）。決策理由與例外條件見 `docs/plan/2026-09-28-remediation-backlog.md` 的 R-18 段落。刻意不做 nginx 半套止血 —— 它會擋掉前端所有管理頁面，卻換不到真正的授權。

## 必讀的既有事實（容易踩雷）

0. ⚠️ **Spring Boot 3.5 已於 2026-06-30 結束 OSS 支援**。已升到該線最後一個 OSS 版本 **3.5.16**（2026-09-28），此後新 CVE 不會再有 OSS 修補 —— 這是止血，不是解決。升級到 Boot 4 **會強制 Flowable → 8.0.x**（Flowable 7.2.0 在 Boot 4 上無法運作、Flowable 8 不支援 Boot 3 —— 兩者必須同步跳，沒有中繼點）。**不要擅自 bump 到 4.x**，見升級計畫 Stage 5。

   ✅ 中繼點（Flowable 6→7）已於 2026-09-28 完成：目前是 **Boot 3.5.16 + Flowable 7.2.0**，這是合法且可運作的組合。
1. **稽核已併入 bpm-core**（2026-04-24，`docs/history/2026-04-24-architecture-refactor/summary.md`，commit `a955a06`）。稽核 API 在 bpm-core 的 `/api/audit-logs`，資料仍在獨立的 `bpm_audit_db`。原 `audit-log-service/` 模組目錄已於 2026-09-28 刪除。`docs/history/**` 中仍提及 :8082 的內容屬歷史紀錄，**不要修改**。

   **form-service 亦已於 2026-09-28 併入**（同樣的模式：合併部署單元、保留獨立 DB）。`docs/history/**` 與部分計畫文件仍提及 :8081，屬歷史紀錄。
2. **表單欄位 `id` == Flowable 流程變數名**（spec §8.5）。這是貫穿前後端的關鍵約定，改表單 schema 前務必確認。
3. **退回 vs 駁回**靠流程變數區分：`approved=false` 為退回；必須再加 `rejected=true` 才是終止。
4. **表單版本鎖定**：`FormVersionLocker` 在流程啟動時把 `_formVersions`（formKey→version）寫入流程變數，進行中案件不受表單改版影響。`TaskController.toMap()` 會回傳 `formVersion`。
5. **組織／權限仍是 Mock**：`MockOrgController` / `MockPermController` 提供 `orgService`、`permService`、`bpmQueryService` 的資料來源。真正的權限中心是另一個尚未開工的專案（`docs/rbac-enterprise-backlog.md`）。
6. ⚠️ **BPMN EL bean 白名單實際上不成立**（2026-09-28 審查修正 —— 本檔先前宣稱它是「唯一真正落實的授權邊界」，那是錯的）。`lint/BpmnLintService.java` 的白名單只是**部署前的字串檢查**，而且：`config/FlowableConfig.java` 沒有呼叫 `setBeans()`，因此**執行期任何 Spring bean 都能從運算式取用**；白名單的 regex `\$\{(\w+)\.` 對 `${''.getClass()...}` 完全不 match（不 match 就放行）；只掃 UserTask 的 3 個屬性，`conditionExpression` 與 listener 的 `delegateExpression` 都沒檢查；且不遞迴 SubProcess，包一層就全繞過。詳見 `docs/plan/2026-09-28-security-audit.md` P0-2。**修 `setBeans()` 時 map 必須含 `notifyTaskListener`**，否則現有 BPMN 會壞。
7. ⚠️ **稽核的不可篡改性目前無效**（2026-09-28 審查）。設計是 SHA-256 hash chain + `INSTEAD OF UPDATE/DELETE` 觸發器，但實作有四個破口：`integrityCheck()` **從不比對 `log[n].previousHash == log[n-1].hashValue`**，所以刪除中間一筆或篡改後重算都回報 intact；hash 只涵蓋 17 個欄位中的 7 個；hash 在 `createdAt` 賦值**之前**計算，導致部分記錄被永久誤報為篡改；`synchronized` 跨實例無效且無 `@Transactional`。觸發器仍需**手動執行**，且兩個 DataSource 都用 `sa` → 應用自己就能 DROP TRIGGER。詳見 `docs/plan/2026-09-28-security-audit.md` P0-3。修 `append()` 時**必須寫 `@Transactional("auditTransactionManager")`**，未限定的 `@Transactional` 會開在 `bpm_core_db` 上導致稽核寫入毫無原子性。
8. **Flowable 已升至 7.2.0**（2026-09-28，Stage 4，仍在 Boot 3.5.16 上）。`BpmCoreApplication` 的 `@ImportAutoConfiguration({...})` 已移除 —— Flowable 7 改用 Boot 3 的 `AutoConfiguration.imports` 格式。

   ⚠️ **但 `ProcessCompletedListener` 的 `@Lazy RuntimeService` 仍不可移除**。實測移除後啟動直接失敗：這是結構性循環（`processCompletedListener → runtimeService → 引擎設定 → processEngineConfigurer → processCompletedListener`），不是 Flowable 6 的遺留物。確切路徑寫在該類別的註解裡。

   詳見 `docs/history/2026-04-19-test-and-verify/walkthrough.md`（16 項踩雷紀錄，動到底層前先看）。
9. **MSSQL 資料初始化**：`spring.sql.init.separator: "@@"`（MERGE 語法需要），不可改回預設 `;`。

## 開發與測試流程

```bash
# 啟動（必須帶 dev overlay，純 docker-compose up 會讓 bpm-core mail health 失敗）
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d

./scripts/seed-data.sh        # 部署兩支 BPMN + 驗證 4 個 form key
./scripts/acceptance-test.sh  # curl 黑箱驗收（需要 python3）

# 自動化測試（Testcontainers：真實 MSSQL / RabbitMQ / Redis，不用 H2）
cd bpm-core && mvn verify
```

兩支腳本的 `FORM_URL` 預設已與 `BPM_URL` 相同（form-service 併入後表單 API 在同一個服務），不帶參數直接跑即可。

前端開發：`cd bpm-frontend && npm run dev`（:3000，proxy `/api` → :80）。

測試帳號：`user001`–`user005`、`mgr001`/`mgr002`、`dir001`、`admin001`。詳見 `docs/README-testing.md`。

## 慣例

- **資料庫欄位一律 NVARCHAR，不要用 VARCHAR**。三個 DB 的定序是 `SQL_Latin1_General_CP1_CI_AS`，VARCHAR 存不了中文且寫入時**靜默換成問號**（無例外、無警告）。兩個模組都已設 `hibernate.use_nationalized_character_data: true`，因此 entity 加新的 `String` 欄位會自動是 NVARCHAR；但**手寫 migration 時必須自己寫 NVARCHAR**。`SchemaEncodingGuardTest` 會掃出任何殘留的 VARCHAR 欄位並讓 build 失敗。
- API 一律以 `/api/` 為前綴，**絕不對外暴露 Flowable 原生 REST**。
- 前端所有 API 呼叫一律走 `src/services/http.js` 的共用 axios instance（統一附身分 header、集中錯誤處理）。**不要在 view/component 直接 `import axios`**。
- 身分的單一來源是 `src/services/session.js`；`decodeToken()` 是接上真實 JWT 的唯一縫線，mock 角色對照表 `MOCK_ROLE_MAP` 也在這裡。取使用者 id 用 `auth.userId`，不要用 `auth.token`。
- Pinia 僅有 `stores/auth.js` 一個 store，其餘為元件本地狀態。
- CI/CD 環境變數替換用 `cicd/envs/{dev,sit,uat,prod}.yml`（BPMN EL 群組佔位符）。
- 分支流程：`feature/* → dev → sit → uat → main`（`cicd/BRANCH_PROTECTION.md`）。
- MR/PR 模板單一來源為 `cicd/templates/merge_request.md`。

## 進度與 backlog

- 已完成：`docs/backend-completed-items.md`（Phase 1–5，87 項 / ~107.5 人日）。
- 待辦：`docs/backend-development-backlog.md`（65 項 / ~125.5 人日）。
  - P0：退回／駁回機制、Org/Perm 去 mock（真實 RestClient + Redis 快取 + 失效 webhook）、表單版控、端到端啟流程、認證授權整合。
- 獨立專案 backlog：`docs/rbac-enterprise-backlog.md`（企業權限中心，100 項 / ~146 人日，未開工）。
- 驗收案例 11 項中 4 項未通過：**TC-A01 附屬簽、TC-A02 多方意見、TC-A04 外部系統 API**（`docs/history/2026-04-19-test-and-verify/tasks.md`）。

## 已知技術債（勿當作 bug 重複回報，修改前先確認範圍）

1. **無應用層認證授權**。所有 `/api/**` 全開放，含 `/api/admin/**`、`/api/internal/cache-invalidate`、`/api/audit-logs/integrity-check`；身分靠請求參數自報（`assignee`、`operatorId`、`createdBy`…）。前端 `Bearer {userId}` 為假 token。router 已有 `beforeEach` 角色守衛（2026-09-28），但那只是 UX 層防線，後端仍全開放。對應 backlog #62 / R-01。
2. ~~**零單元測試**~~ —— **已於 2026-09-28 建立測試網**（Stage 2）。bpm-core 有 `src/test`，以 Testcontainers 起真實 MSSQL／RabbitMQ／Redis（**刻意不用 H2**：`DATETIMEOFFSET`、`NVARCHAR(MAX)`、`IDENTITY`、`MERGE`、`INSTEAD OF` 觸發器都無法在 H2 重現，而稽核 hash chain 與表單版本鎖定正好踩在這些行為上）。`mvn verify` 會實際執行測試，CI 的 status check 不再是空門。**前端仍無測試框架**（對應 backlog #64）。
3. **CI/CD 雙軌並行**：GitHub Actions 與 GitLab CI 同時維護，registry 不一致（GHCR vs `$CI_REGISTRY`），而 `docker-compose.prod.yml` 只認 GHCR 命名。所有 deploy job 仍是 `echo` 佔位。
4. **`bpmn-definitions/` 目錄不存在**，兩邊的 BPMN deploy job 實質 no-op；env 替換用 shell 假 YAML parser，遇到 `http://` 的冒號會解析錯誤。
5. **密碼治理（部分已修）**：`infra/mssql/entrypoint.sh` 的密碼不一致已於 2026-09-28 修復（改讀 `MSSQL_SA_PASSWORD`，未設即啟動失敗）。**尚未處理**：開發密碼仍散落於兩個 `application.yml` 與 `docker-compose.yml`；`bpm.webhook.hmac-secret` 預設字面值 `bpm-webhook-secret`。見 backlog R-04。
6. **外部 API 授權**：字串子串比對已於 2026-09-28 改為精確比對（R-09，commit `f12a8c2`，**未經編譯驗證**），授權判定集中在 `external/ExternalSystemPolicy.java`，擁有權改用 server 寫入的 `_externalSystemId`。仍存在的問題見 R-18 ~ R-25，其中 R-18（`/api/admin/**` 無認證）會讓 R-09 完全可被繞過。`lastUsedAt` 仍每請求寫一次 DB。
7. **Redis 快取失效用 `KEYS` 掃描**（`perm:users:{code}:*`），production 隱憂。
8. 文件／版控雜項（見 backlog R-11 ~ R-17）：根目錄 `backend-development-backlog.md` 與 `docs/` 那份位元完全相同（重複，易漂移）；`bpm-frontend/dist/` 被 commit 進版控；無根 README、無 ESLint/Prettier/Checkstyle/Spotless 設定；`docker-compose.prod.yml` 仍有已淘汰的 `version: '3.8'` 且未設 JVM heap 上限（backlog #50）。
9. ~~**潛在 bug**：`data.sql` 的 snake_case 與 `@UniqueConstraint` 的 camelCase~~ —— **已於 2026-09-28 在乾淨 DB 上驗證為非問題**，Hibernate 正確解析成 `(form_key, version)`。

   但同一次驗證發現了真正的問題並已修復：**全 schema 的文字欄位都是 VARCHAR 而定序是 Latin1**，中文寫入時被靜默換成問號。已造成表單名稱全毀、通知信主旨全毀，以及稽核 `operator_name` 損壞後使 `integrityCheck` 全面誤報。56 個欄位已轉為 NVARCHAR（migration `nvarchar_all_text_columns`），並加上 `hibernate.use_nationalized_character_data` 與 `SchemaEncodingGuardTest` 防止復發。
