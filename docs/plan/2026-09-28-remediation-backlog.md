# 改進 Backlog（可開工項）

**建立日期**：2026-09-28
**基準**：`docs/backend-development-backlog.md`（65 項功能待辦）之外的**工程品質與安全性**項目
**合計**：約 26 人日（不含升級計畫的 22 人日與整併的 3 人日）

---

## 說明

本文只收錄**工程品質、安全性與正確性**項目 —— 也就是既有 backlog 沒有涵蓋、但會阻斷上線或讓後續開發變貴的東西。功能性待辦仍以 `docs/backend-development-backlog.md` 為準。

每項都寫到「可直接開工」的程度：檔案位置、具體改法、驗收條件。

---

## P0 — 上線阻斷項

### R-01 認證授權：server 端推導身分（8 人日）

**現況**：所有 `/api/**` 完全開放，包含 `/api/admin/**`、`/api/internal/cache-invalidate`、`/api/audit-logs/integrity-check`。無 `spring-boot-starter-security`、無 `SecurityFilterChain`、無 `@PreAuthorize`。

**真正的問題不只是缺 filter**。身分目前由呼叫端自報：`assignee`、`operatorId`、`createdBy`、`submittedBy`、`initiator` 全部從 request param／body 讀取，server 無條件採信。**任何人都能 POST 帶 `mgr001` 去核准自己的請假。** 在簽核系統裡這是最不能有的漏洞。

**不要等 RBAC 專案**。`docs/rbac-enterprise-backlog.md` 是 146 人日且未開工，不能讓 BPM 上線卡在它後面。

做法：

1. 在 bpm-core 加入最小可用 JWT filter（先接受自簽 token，介面預留給未來的權限中心）。
2. **關鍵**：新增 `CurrentUser` 解析器，所有 controller 的操作者欄位改由 token 推導，**移除從 request 讀取身分的路徑**。這一步才是真正的修補 —— 只加 JWT 但 controller 繼續讀 `assignee` param，仍然可以冒用。
3. `/api/admin/**`、`/api/internal/**` 加角色檢查。
4. `/api/external/**` 維持現有 API Key 機制（見 R-06）。

依賴：**R-02（前端 axios interceptor）必須先做**，否則前端無法處理 401。

驗收：
- [ ] 無 token 存取任一 `/api/**`（除 `/api/health`、`/api/external/**`）回 401
- [ ] 帶 `user001` 的 token 呼叫 `PUT /api/tasks/{id}` 且 body 夾帶 `assignee=mgr001` → 稽核記錄的 operator 仍為 `user001`
- [ ] 非 admin token 存取 `/api/admin/**` 回 403
- [ ] 稽核記錄的 `operatorId` 一律來自 token，不受 request 內容影響

### R-02 前端統一 axios instance 與 interceptor（0.5 人日）

**現況**：`bpm-frontend/src/services/` 下 5 個檔（`flowableApi.js`、`formApi.js`、`orgApi.js`、`externalApi.js`、`auditLogApi.js`）各自裸用 axios，無共用 instance、無 baseURL、無錯誤處理、無 401 轉導。

**這是 R-01 的前置作業，先做比後補便宜。**

做法：建立 `src/services/http.js` 匯出設定好的 axios instance（baseURL、request interceptor 掛 token、response interceptor 處理 401/403/5xx），5 個 service 檔改用它。

驗收：
- [ ] 5 個 service 檔皆不再直接 `import axios`
- [ ] 401 自動清除 token 並導向登入
- [ ] 後端 5xx 有統一的使用者可見錯誤提示

### R-03 前端 router guard（0.5 人日）

**現況**：`bpm-frontend/src/router/index.js` 的路由宣告了 `meta: { requiresRole: 'admin' | 'auditor' }`，但整個 `src/` 沒有任何 `router.beforeEach` —— **meta 是死資料，admin 頁面打 URL 就進得去**。

做法：加 `beforeEach` 讀 `meta.requiresRole` 比對 `stores/auth.js`。同時把 `isAdmin` 從 `token?.startsWith('admin')` 改為讀 token claim（與 R-01 同步）。

驗收：
- [ ] 非 admin 身分直接輸入 `/admin/external-systems` 被導離
- [ ] 前端守衛**不作為唯一防線** —— 後端 403 仍須獨立生效

### R-04 Secrets 治理（0.5 人日剩餘，原 1 人日）

> **✅ 步驟 1 已完成（2026-09-28）**：`infra/mssql/entrypoint.sh` 改讀 `$MSSQL_SA_PASSWORD`，未設即以明確錯誤退出；初始化失敗或逾時不再靜默跳過。這解掉了 prod 首次部署必然失敗的問題。剩餘步驟 2–5 未動。

**現況**：

| 位置 | 內容 | 狀態 |
|---|---|---|
| `bpm-core/src/main/resources/application.yml`、`form-service/.../application.yml` | `BpmDev@2026!`、`bpm`/`bpm_dev` | ❌ 未處理 |
| `docker-compose.yml` | 同上（含 healthcheck 內的明文密碼） | ❌ 未處理 |
| `application.yml` + `webhook/WebhookConsumer` `@Value` fallback | `bpm.webhook.hmac-secret` 預設字面值 `bpm-webhook-secret` | ❌ 未處理 |
| ~~`infra/mssql/entrypoint.sh:10,13`~~ | ~~`BpmDev@2026!` 寫死~~ | ✅ 已修 |

**曾經最嚴重的不是密碼外洩，是 prod 會壞**：`entrypoint.sh` 用寫死的 `BpmDev@2026!` 執行初始化，但 `docker-compose.prod.yml`（同樣掛載該 entrypoint）用 `${MSSQL_SA_PASSWORD}` 啟動 MSSQL —— 兩者不一致時 60 次重試全數失敗、**靜默跳過初始化**，三個資料庫從未建立，後續服務以難以理解的錯誤崩潰。已修復。

做法（依序）：
1. ~~先修 `entrypoint.sh` 改讀 `$MSSQL_SA_PASSWORD` 環境變數。~~ ✅ **已完成**
2. 所有 `application.yml` 的密碼改為 `${ENV_VAR}` 形式，無預設值（缺少即啟動失敗，優於靜默使用弱密碼）。
3. `bpm.webhook.hmac-secret` 移除程式碼與設定檔中的 fallback 字面值。
4. 輪替已外洩的開發密碼。
5. prod secrets 移至環境變數注入或 Vault，`cicd/.env.example` 同步更新。

驗收：
- [ ] `grep -r "BpmDev@2026\|bpm_dev\|bpm-webhook-secret"` 在 git 追蹤檔中零命中
- [ ] 未設環境變數時服務啟動失敗並明確報錯
- [x] `infra/` 與 `scripts/` 已無硬編碼密碼（2026-09-28 驗證）
- [ ] `docker-compose.prod.yml` 用自訂 `MSSQL_SA_PASSWORD` 冷啟動，DB 初始化成功 —— **entrypoint 已修，但需實際跑一次確認**

---

## P1 — 品質地基

> R-05、R-06 已納入升級計畫的 Stage 2，此處僅列索引。

### R-05 Flyway 接手 schema（2 人日）

見 `2026-09-28-springboot4-upgrade.md` Stage 2。

`ddl-auto: update` + `flowable.database-schema-update: true` 跑企業生產系統是資料損毀的等候室。順帶解決兩件事：
- `infra/mssql/audit-log-triggers.sql` 從「手動執行」變成 migration（現在沒人會記得執行，而它是 ISO 27001 的 compensating control）
- `form-service/src/main/resources/data.sql` 的 snake_case 欄位名 vs `FormDefinition` `@UniqueConstraint(columnNames = {"formKey","version"})` camelCase 的不確定性

### R-06 測試安全網（5 人日）

見 `2026-09-28-springboot4-upgrade.md` Stage 2。

**現況**：三個模組皆無 `src/test` 目錄（但 pom 都已宣告 `spring-boot-starter-test`），前端無測試框架。**CI 的 `mvn verify` 與分支保護的 status check 實質為空門。**

優先順序（不追覆蓋率數字）：
1. 4 個未過的驗收案例 → Testcontainers 整合測試：TC-A01 附屬簽、TC-A02 多方意見、TC-A04 外部 API
2. 有真實邏輯的單元測試：`FormService` 三態生命週期、`AuditLogService` hash chain、`ExternalApiAuthFilter`、退回／駁回的變數判定

這一項不做，升級與整併都在流沙上蓋。

### R-07 CI/CD 收斂成單軌（2 人日）

**現況**：GitHub Actions 與 GitLab CI 同時維護同一個 repo，推到不同 registry（GHCR `ghcr.io/${{ github.repository }}` vs `$CI_REGISTRY`），而 `docker-compose.prod.yml` 只認 GHCR 命名 —— **GitLab 建出來的 image 無法被 prod compose 部署**。所有 deploy job 都是 `echo` 佔位。

做法：
1. 決定保留哪一套（依實際使用的平台），刪除另一套。
2. 補上真實的 deploy 步驟。
3. GitLab 若保留，修掉 `build` 做 `mvn compile`、`test` 再做 `mvn verify` 的重複工作。

驗收：
- [ ] repo 中只有一套 CI 設定
- [ ] deploy job 實際部署到目標環境，非 `echo`
- [ ] registry 命名與 `docker-compose.prod.yml` 一致

### R-08 處理 `bpmn-definitions/` no-op（1 人日）

**現況**：`.github/workflows/bpmn-deploy.yml` 與 `.gitlab-ci.yml` 的 `bpmn:deploy` 都掃 `bpmn-definitions/**/*.bpmn*`，但**這個目錄在 repo 中不存在** —— 兩個 job 都是空轉。真正的 BPMN 在 `bpm-core/src/main/resources/processes/`（打包進 jar）。

另外 `cicd/envs/*.yml` 的替換用 shell 假 YAML parser（`while IFS=': ' read -r key value`），遇到 `bpm.core.url: http://...` 的冒號會解析錯誤；且只過濾 `^bpmn\.` 前綴，`bpm.`／`form.`／`audit.` 三行實際上從未被使用。

**靜默什麼都沒做的 pipeline stage，比沒有這個 stage 更危險。** 兩條路選一條：

- **A（推薦）**：建立 `bpmn-definitions/`，把 BPMN 從 jar 移出來做成 BPMN-as-code，env 替換改用 `yq` 或 Python。
- **B**：刪掉兩個 deploy job 與 `cicd/envs/*.yml` 的 BPMN 佔位符，承認 BPMN 隨 jar 部署。

驗收：
- [ ] BPMN 部署 job 要嘛實際部署了檔案，要嘛不存在
- [ ] 無「執行成功但什麼都沒做」的 stage

---

## P2 — 正確性

### R-09 外部 API 授權改精確比對（1 人日）

**現況**：三處**靜默放寬權限**的字串子串比對（已逐一定位）：

| 位置 | 程式碼 | 問題 |
|---|---|---|
| `external/ExternalApiAuthFilter.java:77` | `sys.getAllowedActions().contains(action)` | `allowedActions` 是存成 JSON array 的**字串**（見 `model/ExternalSystem.java:29` 註解）。要求 `query_status` 時，若設定內任一值**包含**該子串即通過 —— 例如 `["query_status_extended"]` 會誤放 `query_status`。 |
| `external/ExternalApiController.java:214-218` | `initiator.toString().contains(systemId)` | 非 `equals` → `systemId = "erp"` 可存取 `initiator` 為 `erp-legacy` 的流程實例。 |
| `external/ExternalApiAuthFilter.java:96-99` | `uri.contains("/process-instances")` 等 | action 推導同樣走 URI 子串比對，路徑新增時容易誤判。 |

另外 `lastUsedAt` 每次請求都寫一次 DB（每個 API call 一次 write）。

做法：
- `allowedActions` / `allowedProcessKeys` 用 Jackson 解析成 `Set<String>` 後 `contains()`（集合的精確比對，非字串子串）
- `verifyOwnership()` 改 `equals`
- `resolveAction()` 改用精確路徑比對（`AntPathMatcher` 或 enum 對照表）
- `lastUsedAt` 改非同步或以分鐘為粒度節流

驗收：
- [ ] `allowedActions = ["query_status_extended"]` 的系統呼叫 `query_status` 端點被拒
- [ ] `systemId = "erp"` 無法存取 `initiator` 為 `erp-legacy` 的流程實例
- [ ] 外部 API 壓測不再每請求一次 DB write

### R-10 Redis `KEYS` 改 `SCAN`（0.5 人日）

**現況**：`bpm-core/src/main/java/com/bpm/core/service/BpmPermissionService.java:79` —— `redis.keys("perm:users:" + code + ":*")`。`KEYS` 會阻塞 Redis 單執行緒事件迴圈，資料量成長後是 production 隱憂。

做法：改用 `SCAN`（`RedisTemplate.scan()`），或改資料結構讓失效變成 O(1)（以 code 為 key 的 Hash，field 為 dept）。

驗收：
- [ ] 程式碼中無 `keys(` 呼叫
- [ ] 10 萬筆 key 下的快取失效不造成 Redis 延遲尖峰

---

## P3 — 工程衛生（可併入其他 PR 順手做）

| 編號 | 項目 | 人日 |
|---|---|---|
| R-11 | 刪除根目錄 `backend-development-backlog.md`（與 `docs/` 那份位元完全相同，漂移風險） | 0.1 |
| R-12 | `bpm-frontend/dist/` 移出版控，加入 `.gitignore` | 0.1 |
| R-13 | `docs/README-testing.md` 移除 audit-log-service :8082 的錯誤記載，integrity-check 改指 bpm-core | 0.2 |
| R-14 | `docker-compose.prod.yml` 移除已淘汰的 `version: '3.8'`；為 JVM 服務加 heap 上限（既有 backlog #50） | 0.5 |
| R-15 | 加入 Spotless（Java）+ ESLint/Prettier（前端）與 `.editorconfig`，納入 CI | 1 |
| R-16 | 補根目錄 `README.md`（現在只有 `docs/README-testing.md` 與 `cicd/README.md`） | 0.5 |
| R-17 | 補 Swagger/OpenAPI（既有 backlog #65）—— 對外部系統整合尤其重要，目前外部廠商只能讀原始碼 | 2 |

---

## 施工順序（含相依）

```
✅ 已完成（2026-09-28，1 人日）
  ├─ 升級 Stage 0：3.5.13 → 3.5.16 止血
  ├─ 升級 Stage 1：刪 audit-log-service + 清理殘留引用
  └─ R-04 步驟 1：修 entrypoint.sh 密碼不一致（prod 阻斷項）

第 1–2 週（7 人日）← 下一個開工點
  ├─ R-05 Flyway
  └─ R-06 測試安全網          ← 後續所有變更的前提

第 3 週（3.5 人日）
  ├─ R-02 axios interceptor    ← R-01 前置
  ├─ R-03 router guard
  └─ ADR-001 form-service 整併（升級 Stage 3）

第 4 週（3 人日）
  └─ 升級 Stage 4：Flowable 6.8.1 → 7.2.x（Boot 不動）

第 5–6 週（5 人日）
  └─ 升級 Stage 5：Boot 4.1.1 + Flowable 8.0.x  ⚠️ 含前端日期格式連動

第 7–8 週（9 人日）
  ├─ R-01 認證授權（8 人日）
  └─ R-04 剩餘步驟

之後（可獨立排程）
  ├─ 升級 Stage 6：Jackson 2 → 3
  ├─ R-07 CI/CD 收斂
  ├─ R-08 bpmn-definitions
  ├─ R-09 外部 API 授權
  ├─ R-10 Redis SCAN
  └─ R-11 ~ R-17 工程衛生
```

**這個順序的設計原則是「每一步都降低下一步的成本」**：先刪死碼縮小面積 → 再建測試網 → 再整併減少模組數 → 才動主版本升級 → 認證只做一次。

## 相關文件

- `2026-09-28-springboot4-upgrade.md` —— Boot 4 + Flowable 8 升級計畫
- `2026-09-28-adr-001-form-service-consolidation.md` —— form-service 整併決策
- `docs/backend-development-backlog.md` —— 功能性待辦（65 項）
- `docs/rbac-enterprise-backlog.md` —— 企業權限中心（獨立專案，未開工）
