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

### ✅ R-09 外部 API 授權改精確比對（已完成 2026-09-28，未經編譯驗證）

> 已提交於 `feature/tech-debt-remediation`（commit `f12a8c2`）。修補了 7 項，
> 並在審查後追加修掉 5 個新引入的問題。**但審查同時發現 8 個仍存在的漏洞，
> 見上方 R-18 ~ R-25 —— 其中 R-18（admin API 無認證）會讓本項修補完全可繞過。**
>
> 驗收狀態：
> - [x] `allowedActions = ["query_status_extended"]` 呼叫 `query_status` 被拒
> - [x] `systemId="erp"` 無法存取 `initiator="erp-legacy"` 的實例
> - [ ] 外部 API 壓測不再每請求一次 DB write → 移至 R-25
> - [ ] **未經編譯與測試**（本次環境無 javac/maven/docker）

原始問題記錄如下，保留供對照：

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

## P0 追加 — 2026-09-28 安全審查新發現

> 來源：R-09 修補完成後，由兩個獨立 reviewer 對 `external/` 套件做的審查。
> 這些是**修補後仍然存在**的問題，不是 R-09 的遺漏。

### R-18 `/api/admin/**` 完全無認證 → 可繞過所有外部系統授權（0.5 人日止血）

**這是目前最嚴重的單一問題，且它讓 R-09 的所有收緊都變成紙上防線。**

`external/ExternalSystemAdminController.java` 全無認證，任何能連到 nginx :80 的人可以：

1. `GET /api/admin/external-systems` 列舉所有 systemId 與其授權設定
2. `POST /api/admin/external-systems/{id}/rotate-key` → **明文回傳新 API Key**（`:80`），完整接管該外部系統
3. `PUT /api/admin/external-systems/{id}` 把 `allowedProcessKeys`、`ipWhitelist` 設成 null → R-09 的檢查與 IP 白名單一併歸零

**止血選項**：在 `infra/nginx/nginx.conf` 加 `location ^~ /api/admin/ { allow <內網 CIDR>; deny all; }`。
⚠️ **但這會同時擋掉前端管理頁面**（表單編輯器、流程管理、外部系統管理都打 `/api/admin/*`）。所以這不是可直接套用的修補 —— 要嘛限制來源網段後確認管理者都在該網段內，要嘛直接做 R-01。

---

#### ✅ 決策（2026-09-28，Bruce）：**不做 nginx 半套止血；R-18 列為部署前阻斷項，直接排 R-01**

**決策內容**：不在 nginx 施作來源限制，改為把 R-18 標記為**部署前阻斷項**（deployment blocker），資源投入真正的修補 R-01（後端 JWT + 由 token 推導 `operatorId`）。

**理由**：

1. **目前實際暴露面為零**。系統尚未部署、只在本機開發（見 CLAUDE.md「部署狀態」）。R-18 的威脅模型是「任何能連到 nginx :80 的人」，而目前只有開發者自己的機器能連到。
2. **半套止血的代價大於收益**。限制整個 `/api/admin/**` 會立刻擋掉前端所有管理頁面，本機開發與 demo 都會壞掉；只擋高危險端點則需要逐一盤點端點清單，容易漏，而且仍然不是授權（只是網段信任）。用確定的可用性損失，去換一個目前不存在的風險，不划算。
3. **nginx 網段限制本質上不是修補**。它無法區分「誰」在呼叫，只能區分「從哪來」。真正的問題是應用層沒有身分概念 —— 那只有 R-01 能解。

**因此必須遵守的條件**：

- [ ] **R-18 為部署前阻斷項**：在 R-01 完成之前，本系統**不得部署到任何多人可連線的環境**（含 SIT／UAT）。
- [ ] 若因故必須在 R-01 之前部署，則該次部署**必須**同時施作 nginx 網段限制，並接受管理頁面僅限該網段使用。
- [ ] R-01 的範圍必須包含「身分不再由請求參數自報」（`assignee`／`operatorId`／`createdBy`），否則加了 JWT 仍可冒用他人核決。

**關聯**：本決策同時決定了 R-09（commit `f12a8c2`）的實際有效性 —— 在 R-01 完成前，R-09 的授權收緊在可連線環境中仍是可繞過的。

### ✅ R-19 外部系統可自我核准（已完成 2026-10-02）

> **✅ 已完成（`123e494`，merge `c69ce1a`；`startProcess` 的 `_` 殘留由 `c05afae` 補齊）。**
> 現況（2026-10-02 複驗）：
> - `completeTask` 順序：擁有權 403 → `allowedProcessKeys` 403 → 任務必須是 `assignee`／
>   `candidateUsers` 明確包含 `system:<systemId>`（403）→ `_` 前綴 400 → 既有
>   `validateVariables` 400。全部在 `taskService.complete` 之前，被拒零副作用。
> - `startProcess` 也拒絕 `_` 前綴（與 `completeTask` 同一份 helper）。
> - 線上實測：自我核准 403 且任務仍在、`approved` 未寫入；未授權流程 key 403；
>   `_externalSystemId` 400；`system:<id>` 持有的任務可完成（200）。
> - ⚠️ 未收緊「未宣告的非 `_` 變數」（沿用既有 `validateVariables` 語意）；
>   Call Activity 子流程的任務要求子流程 key 也在授權清單內。

`external/ExternalApiController.java` 的 `completeTask` 只檢查「流程實例屬於誰」，**不檢查「這個 task 該不該由外部系統做」**：沒有任何 assignee / candidate / taskDefinitionKey 限制。

後果：系統 X 啟動 `leave-approval` → 該實例擁有者是 X → X 直接以 `PUT /api/external/tasks/{主管簽核的 taskId}` **自行完成人工主管簽核**。人工審批在自己送出的案件上等於不存在。

同一處還有**內部變數注入**：`vars` 未過濾 `_` 前綴、未比對 `ProcessVariableSpec`（`validateVariables` 只在 startProcess 呼叫），因此可任意覆寫 `approved` / `rejected`（退回vs駁回的判定基礎）、`_formVersions`、`initiator`，甚至 `_externalSystemId` 自身。

修法：(i) task 的 assignee/candidate 必須屬於該系統，或維護 `taskDefinitionKey` 白名單；(ii) `vars` 拒絕所有 `_` 前綴並只接受該流程宣告過的變數名；(iii) 補上 allowedProcessKeys 比對。

### ✅ R-20 `initiator` 仍可任意偽造（已完成 2026-09-29；2026-10-02 文件收尾）

> **✅ 已完成（主修 `4ee75d4`／2026-09-29；`#68` a/b/c/d 於 2026-09-30 全部收尾，其中 c 由 `#83` 完成）。**
> 現況（2026-10-02 文件收尾時複驗）：
> - `initiator` 一律由 server 寫成 `system:<systemId>`，body 帶 `initiator` 直接回 **400**（`ExternalApiController.java:89-94`）。
> - 代發改用 `onBehalfOf`：需該系統 `allowOnBehalfOf=true`（否則 403）且該員工必須存在（`ExternalApiController.java:162-173`；欄位見 `ExternalSystem.java:69-70`）。
> - 「外部系統發起必須指定第一關受理人」的 400 現在也接受 `onBehalfOf`（`ExternalApiController.java:246-250`）。
> - `firstTaskAssignee` 必須是組織系統認識的人（`ExternalActorGuard.requireKnownPerson`，#88）；`firstTaskCandidateGroups` 加上 `allowedCandidateGroups` 授權白名單（#88 政策 B）。
> - lint rule h 由 warning 升為 **error**（`BpmnLintService.java:327-333`，#68d）。
> - 「我的申請」查詢改為 `initiator` **或** `onBehalfOf`（`ProcessController.java:262-270`、`HistoryController.java:281-287`），回應以 `onBehalf=true` 標示。
>
> ⚠️ **未完全覆蓋的部分（勿誤讀為全解）**：
> - `firstTaskCandidateGroups` 的**群組存在性**仍未驗證（授權白名單只處理越權，刻意留白；理由見 `ExternalApiController.java:199-236`）。
> - `EmailConsumer` 的 `${initiatorName}` 仍直接渲染 `initiator`（`EmailConsumer.java:135`），外部案件的通知信會顯示 `system:<id>`；代發案件沒有另外的顯示名。
> - **沒有 backfill**：改動前以自訂 `initiator` 啟動的舊實例，在新的查詢邏輯下仍可能查不到；`queryByBusinessKey` 也仍以 `initiator` 篩選（`ExternalApiController.java:388-393`，見 R-24）。

`ExternalApiController.java` 的 `startProcess` 仍允許呼叫端在 body 指定任意 `initiator`。擁有權判定已不依賴它，但 `initiator` 被下游廣泛信任：

- **BPMN EL 路由**：`leave-approval.bpmn20.xml` 的 `${orgService.getDirectManager(initiator)}` → 外部系統可**偽造一張看似由特定員工提出的請假單**，路由到該員工的主管
- **內部查詢**：`ProcessController` / `HistoryController` 以 `variableValueEquals("initiator", ...)` 篩選 → 偽造案件出現在受害者的「我的申請」
- **通知信**：`EmailConsumer` 直接把 initiator 當申請人渲染
- **Lint 防線失效**：`BpmnLintService` rule h（外部可發起流程的第一個 UserTask 不可用 initiator EL）severity 是 `warning`，而只有 `error` 會阻擋部署

另有**驗證繞過**：「外部系統發起必須指定 firstTaskAssignee」只在 `initiator.startsWith("system:")` 時生效，傳不帶前綴的 initiator 即整條跳過。

連帶項：`firstTaskAssignee` / `firstTaskCandidateGroups` 完全無驗證，可把審核任務指派給任意員工或丟進任意特權群組的待辦池；且 `setAssignee` 發生在流程啟動後，會**覆寫 BPMN EL 算出的合法簽核人**。

修法：`initiator` 一律由 server 寫成 `system:<systemId>`；body 的欄位改名 `onBehalfOf` 並以 `orgService` 驗證帳號存在；`firstTaskAssignee` / `candidateGroups` 同樣驗證；`BpmnLintService` rule h 升為 `error`。

### R-21 授權設定的驗證在讀取端而非寫入端（1 人日）

`allowedProcessKeys` 空值 = 不限制，而 admin UI 該欄位是自由文字、無必填驗證、`resetForm()` 預設空字串 → **照 UI 正常流程建立的外部系統預設可啟動任何流程**，R-09 的檢查在預設路徑上是 no-op。

反過來，若既有資料是 `[]` 或非 JSON，該系統所有 `start_process` 立即全滅，線索只有一行 WARN。

同時 UI 兩個欄位的安全語意相反：`allowedActions` 由 checkbox 產生 `"[]"` → 全拒；`allowedProcessKeys` 清空 → `""` → 全開。管理畫面上兩者都是「沒填」。

修法：`ExternalSystemAdminController` 的 create/update 對三個欄位做格式驗證（解析失敗直接 400）、強制 `allowedProcessKeys` 非空並比對已部署流程 key；前端改多選 + required。

⚠️ **上線前必辦**：`bpm_external_system` 表沒有任何 seed SQL，資料只能來自 admin UI，因此正式/SIT 環境的實際內容無法從 repo 判定 —— **部署前必須撈一次 DB 盤點**，否則 R-09 的 fail-closed 可能造成服務中斷。

### R-22 IP 白名單在容器部署下失效（0.5 人日）

`ExternalApiAuthFilter` 用 `request.getRemoteAddr()`，在 nginx 後方取到的是 **nginx 容器位址**。`infra/nginx/nginx.conf` 有設 `X-Real-IP` / `X-Forwarded-For`，但 `application.yml` 沒有 `server.forward-headers-strategy`。兩種下場都不好：填真實 IP → 全擋；填 nginx IP → **對所有系統一律放行**。稽核記錄的 `ip` 同樣是 nginx IP，鑑識價值為零。

R-09 修掉了白名單的兩個實作 bug（重複值 500、元素未 trim），但沒動到「比對來源不可信」這個根因。

次要：error message 回顯 client IP，對外洩漏內部 proxy 位址。

### ✅ R-23 `_externalSystemId` 可被任意寫入（已完成 2026-10-02）

> **✅ 已完成（`c05afae`，merge `0974b8f`）。** 兩個外部入口（`startProcess`／`completeTask`）
> 一律拒絕 `_` 前綴變數名（400 並指名，同一份 `rejectReservedVariableNames`）；
> `_externalSystemId` 另由 server 最後覆寫。內部 `TaskController` 的 `_` 前綴保護為更早既有。
> ⚠️ 掃描範圍是「所有」variable 寫入路徑的抽樣（兩個外部入口＋內部完成路徑）；
> form／countersign 等其他寫入端若日後新增 `_` 變數再一併檢查。

擁有權標記是普通 Flowable 變數，在無認證的內部 API 下不是 server-only：`controller/TaskController.java` 的 `PUT /api/tasks/{id}`（action=complete）把 `req.variables()` 逐筆寫入後丟給 `taskService.complete` → 任何未認證呼叫者都能覆寫 `_externalSystemId`，把任一流程實例「過戶」給指定的外部系統。

`_externalSystemId` 比 `initiator` 好，但要真正可信需寫在外部系統無法觸及的地方（獨立資料表），或在所有 variable 寫入路徑把 `_` 前綴列為保留字拒絕。

### R-24 `queryByBusinessKey` 與新擁有權模型不一致（0.5 人日）

`ExternalApiController.java` 的 `GET /api/external/process-instances` 仍以 `variableValueEquals("initiator", "system:" + systemId)` 篩選，未改用 `_externalSystemId`：

- **漏查**：以自訂 initiator 啟動的案件，`/status` 查得到但列表查不到 —— 同一資源兩個端點兩種答案
- **可注入他人列表**：惡意系統以 `initiator: "system:victim"` 啟動流程，該紀錄會出現在 victim 的 businessKey 查詢結果中

刻意未在 R-09 一併修改：改為 `_externalSystemId` 篩選會讓既有實例查不到，需搭配 R-20 的 backfill 一起做。另外此端點目前是靠「篩選剛好也起到授權作用」撐住，沒有經過擁有權檢查函式 —— 若日後有人拿掉篩選條件會直接變成資料洩漏。

> **2026-10-02 加註（R-20 收尾時複驗；本項本身仍待開工）**：R-20 已於 2026-09-29 完成，但**沒有做 backfill**；本端點也仍以 `variableValueEquals("initiator", ExternalActorIdentity.of(systemId))` 篩選（`ExternalApiController.java:388-393`）。因此：
> - **「以自訂 initiator 啟動的舊實例查不到」仍在**（改動前的舊資料）。
> - **「可注入他人列表」已不成立**：body 的 `initiator` 直接 400，且 `variables` 裡的同名值會被 server 在啟動前覆寫（R-20），外部系統無法再讓案件掛上 `system:victim`。

### R-25 API Key 機制強化（1 人日）

`external/ApiKeyUtil.java` 為無 salt 單輪 SHA-256。客觀評估：`UUID.randomUUID()` 走 `SecureRandom`（122 bits 熵），離線暴力破解不構成實際風險。真正的問題是：

1. **無 salt → 相同金鑰產生相同 hash**，DB 外洩時可跨系統/跨環境比對出重用的金鑰。應改 HMAC-SHA256(server secret, key)，保留可查詢性同時讓 DB 外洩不足以偽造。
2. **無金鑰有效期、無多金鑰並存**：`rotateKey` 一寫入舊金鑰立即失效，沒有 grace period → 每次輪替都是計畫性中斷，實務上導致「不敢輪替」。
3. **無速率限制、無失敗鎖定**：驗證失敗只寫一筆稽核，無任何節流。

連帶效能項（R-09 已留 TODO 但未解）：`lastUsedAt` 仍每請求一次 DB write；`ExternalSystemPolicy.parse()` 每請求做 3 次 Jackson 解析，無快取。

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
