# ADR-002：BPMN-as-code —— repo `bpmn-definitions/` 單一來源、`POST /api/deployments` 單一部署入口

**日期**：2026-10-05
**狀態**：📝 **提案（設計完成；2026-10-06 已拍板 Q1／Q2，實作進行中）** —— R-08 選項 A（BPMN-as-code）
已由使用者拍板；CI 平台已定為 **GitLab CI**（`docs/handoff/2026-10-05-round22-handoff.md:124`）。
**修訂（2026-10-06）**：T1 實測發現 testResources 映射不可與 jar 內副本並存，映射延後至 T4
（見 §4.3 修訂框）；T1 已完成、Q1／Q2 已拍板（見 §7／§8）。
**決策者**：Bruce
**預估**：設計（本票）0.5 人日；**實作約 2.5 人日**（原 R-08 估 1 人日，差異理由見 §7）
**相依**：R-07（GitLab CI 單軌化、刪 GitHub Actions，2 人日，`docs/plan/2026-09-28-remediation-backlog.md:119`）；
#53（部署期 `${ENV_*}` 替換）與 #61（部署 Git 版控）已完成（`docs/backend-development-backlog.md:131,153`）

---

## 0. 摘要（決策一句話）

1. **單一 writer**：repo 的 `bpmn-definitions/` 是「**應該上線什麼**」的真實來源；`POST /api/deployments`
   是唯一部署入口；runtime 的 `bpm.bpmn-definitions-dir`（#61 Git repo）是**只寫不讀的部署帳本**，
   引擎永遠不從它讀定義。三者的角色分離見 §2.1。
2. **CI**：GitLab CI `bpmn:deploy` 以**服務帳號 JWT**呼叫部署 API，逐檔部署、**失敗大聲紅**；
   **不在 CI 端做 env 替換**（#53 已在部署期實作，見 §3、§2.2 被否決選項 (e)）。
3. **classpath 退場**：main resources 的 3 份 BPMN 移入 `bpmn-definitions/`；測試以 Maven
   `testResources` 映射同一份檔案（零複本）；先以 compose 環境變數關閉
   `flowable.check-process-definitions`，再移除 jar 內副本（順序見 §6）。
4. **運維**：prod 加 `bpmn-definitions` 具名 volume；rollback = 用同一條 pipeline 重部署前一版
   repo 內容（不是改檔案、不是改 DB）。

---

## 1. 背景與現況（已查證）

### 1.1 BPMN 現在住哪裡、怎麼上線

- 三份 BPMN 在 `bpm-core/src/main/resources/processes/`：
  `leave-approval.bpmn20.xml`（65 行）、`purchase-approval.bpmn20.xml`（252 行）、
  `countersign-review.bpmn20.xml`（100 行）；Flowable `process id` 分別為
  `leave-approval`／`purchase-approval`／`countersign-review`（各檔 `process id=` 行）。
- 這三份被打包進 jar，開機由 Flowable Spring Boot 自動部署（PM 已在 dev 容器 log 看到
  `DefaultAutoDeploymentStrategy`）。設定面複核：`flowable-spring-boot-autoconfigure:8.0.0` 的
  `META-INF/spring-configuration-metadata.json` 預設值為
  `flowable.check-process-definitions=true`、`flowable.process-definition-location-prefix=classpath*:/processes/`、
  `flowable.process-definition-location-suffixes=**.bpmn20.xml,**.bpmn`、
  `flowable.deployment-name=SpringBootAutoDeployment`；本 repo 的 `application.yml:151-153`
  只設 `database-schema-update`／`async-executor-activate`，沒有覆蓋上述預設。
- **runtime 目錄與引擎無關**：全 repo 沒有任何程式碼從 `bpm.bpmn-definitions-dir` 讀取定義
  （唯一使用者是 `DeploymentController.java:46,56` 的寫檔與 `DeploymentGitCommitter.java:61`
  的 commit 目標）。Flowable 的真實來源是它的 DB（`ACT_RE_*`）。這點是「單一 writer」設計的基礎。

### 1.2 repo 沒有 `bpmn-definitions/`，CI stage 是空轉

- `git log --all -- bpmn-definitions` 為空 —— 這個目錄**從未存在**。
- GitLab `bpmn:deploy`（`.gitlab-ci.yml:176-209`）以 `changes: [bpmn-definitions/**]`（:209）觸發，
  永遠不成立；內的 glob（:192、:202）也永遠掃不到檔案。手動觸發時：
  - `BPM_URL="${BPM_CORE_URL:-http://bpm-core:8080}"`（:201）—— runner 容器內解不到
    `bpm-core` 這個 hostname（它只存在於 compose 網路）；
  - `curl … || echo "WARN: Failed $bpmn"`（:205-206）把失敗吞掉，job 仍綠色。
- GitHub `bpmn-deploy.yml`（:6 同樣 `paths: ['bpmn-definitions/**']`；:42-59 同樣迴圈與
  `|| echo "Failed…"`）是同一份邏輯的第二份拷貝，R-07 將刪除。
- **沒有認證**：`POST /api/deployments` 在 R-01 後要求 `ROLE_ADMIN`
  （`bpm-core/src/main/java/com/bpm/core/security/SecurityConfig.java:295`），
  而兩個 CI job 的 curl 都沒有 `Authorization` 標頭，手動觸發也只會拿到 401/403 ——
  這在 no-op 的掩蓋下從未被發現（現在是第三層「執行成功但什麼都沒做」）。

### 1.3 部署 API 的既有能力（#53／#61，已完成，直接沿用）

`DeploymentController.deploy()`（`DeploymentController.java:144-256`）的順序與語意是刻意的：

1. **#53 替換**（:153-160）：只認 `${ENV_*}`、值來自 `bpmn.variables.*`（環境變數
   `BPMN_VARIABLES_*` 可由 Spring relaxed binding 覆蓋）；查不到值 **400 且零副作用**。
2. **Lint**（:162-175）：原始 XML 一定驗；有替換時 resolved XML 再驗一次（`:168-175`）
   —— 設計器與部署端吃同一份規則。
3. **寫檔**（:177-190）：寫入 `bpm.bpmn-definitions-dir`，內容是**原始 XML**（含佔位符）。
4. **#61 Git commit**（:208-221）：`bpm.bpmn.git.enabled=true` 時 commit 原始 XML；
   失敗回 503 且**不呼叫引擎部署**（fail-closed）。
5. **Flowable deploy**（:224-227）：送 **resolved XML** 進引擎。
6. **稽核**（:229-252）：`deploymentId`、`xmlSha256`（原始檔指紋）、有替換時
   `resolvedSha256`＋變數**名**（不記值）、有 commit 時 `gitCommit` short id、`operatorId`
   來自已認證身分（`CallerIdArgumentResolver`，不 fallback 標頭）。

兩個後續決策必須知道的實作事實：

- `DeploymentController` **沒有**呼叫 `DeploymentBuilder.enableDuplicateFiltering()`
  （介面存在，controller :224-227 未用）→ 重複部署同一份內容仍會產生**新的**
  deployment／process definition 版本。CI 重跑、rollback 都會留新版本（見 §5、§8-Q4）。
  相對地，**classpath 自動部署有** `enableDuplicateFiltering()`（`flowable-spring:8.0.0`
  的 `DefaultAutoDeploymentStrategy.deployResourcesInternal` bytecode offset 15；
  `AbstractProcessAutoDeploymentStrategy` 無此呼叫）——所以「jar 內容與目前最新部署相同」
  時，重啟不會產生新版本；這也是為什麼舊 jar rollback 只有在**內容已不同**時才會造成
  版本回退（§4.1）。
- runtime repo 由 JGit 自動 `init`（`DeploymentGitCommitter.java:148-157`），**沒有 remote、
  沒有 push**；行程內鎖（:52-57）不跨實例。它是「同一台主機上的歷史帳本」，不是備份。

### 1.4 `cicd/envs/*.yml` 與實際 BPMN 脫節（死設定）

- PM 的 grep 複核成立：3 份 BPMN 的 `${ENV_` 出現次數皆為 **0**；出廠流程的主管／補件關卡用的是
  `${assigneeResolver.resolve(execution)}`／`${applicantResolver.resolve(execution)}`
  （`leave-approval.bpmn20.xml:28,51`），不是 `candidateGroups=${ENV_*}`。
- `cicd/envs/*.yml` 的 `ENV_*` 值因此沒有任何消費者；其餘鍵（如 `bpm.core.url`）因 shell 假 YAML
  parser（`.gitlab-ci.yml:194-197` 的 `while IFS=': '`）與 `grep -v '^bpmn\.'` 過濾，也從未被使用
  （R-08 原始描述，`docs/plan/2026-09-28-remediation-backlog.md:137-139`）。
- compose 三個檔案都沒有注入 `BPMN_VARIABLES_*`（grep 無命中）→ 即使今天 CI 手動送檔，
  只要檔案裡有佔位符就會 400。**這是設計上的正確行為（fail-closed），不是缺口。**

### 1.5 設計器是第二個「呼叫端」

`bpm-frontend/src/views/BpmnEditor.vue:92` 的「部署」按鈕直接 `POST /api/deployments`；
路由掛 `/admin/bpmn-editor` 且 `requiresRole: 'admin'`（`bpm-frontend/src/router/index.js:61`）。
因此「誰可以寫 runtime 目錄」有兩個呼叫端：CI 與管理員。本 ADR 的單一 writer 指的是
**寫入 runtime 目錄的程式只有 DeploymentController**；「誰是 release 的呼叫端」見 §2 與 §8-Q2。

### 1.6 運維缺口

- prod compose 的 bpm-core 只掛 `bpm-uploads:/app/uploads`（`docker-compose.prod.yml:141`），
  **沒有** `/app/bpmn-definitions` volume；`bpm.bpmn-definitions-dir` 預設是相對路徑
  `./bpmn-definitions`（`application.yml:192`），容器內即 `/app/bpmn-definitions`
  （Dockerfile `WORKDIR /app` 與預建目錄 `Dockerfile:27-30`）。容器重建 = 帳本歷史消失。
- `BPM_BPMN_GIT_ENABLED=true`＋持久化目錄＋repo 巡檢已列在部署前檢查清單
  （`docs/handoff/2026-10-04-round21-handoff.md:83`），但「旗標與此設計的關係」此前沒有定義。
- prod 對外只有 nginx 的 80/443（`docker-compose.prod.yml:154-157`），bpm-core 無公開 port；
  nginx 的 `/api/` proxy 會轉發 `Authorization`（`infra/nginx/nginx.conf:20-25`）——
  CI 走 nginx 打 API 在網路層可行。

### 1.7 測試與 seed 對 classpath BPMN 的依賴（本票實測）

- `scripts/seed-data.sh` 走 API 部署三支流程（:64-74），檔案路徑指向
  `bpm-core/src/main/resources/processes/`；`scripts/acceptance-test.sh` 只啟動流程，不部署 BPMN。
- 測試基底的 `IntegrationTestBase` 共用 static Testcontainers，**沒有任何顯式部署出廠流程的程式碼**；
  `application-test.yml:20-27` 沒有覆蓋 `flowable.check-process-definitions`，因此測試沿用預設
  `true`＋classpath `processes/` 自動部署。
- 65 個測試檔引用出廠流程 key；其中只有 11 個提到顯式部署（多數是部署自訂 BPMN 的 listener 測試），
  其餘**沒有任何顯式部署程式碼**。
- 另有 9 處（5 個檔）測試直接以 `getResourceAsStream("processes/…")` 讀 classpath：
  `WebhookDeliveryWiringTest.java:574,607`、`OptionalAssigneeVariableLintTest.java:329,378`、
  `LintRuleCorrectnessTest.java:142,274,335`、`SystemInitiatorRevisionTest.java:382`、
  `CallActivityCountersignTest.java:85`。
- **A/B 實驗（本票執行，單一測試，非完整套件）**：

  ```
  對照組： mvn -f bpm-core/pom.xml test -Dtest=ProcessResultReportingTest
           → Tests run: 5, Failures: 0, Errors: 0 → BUILD SUCCESS
  實驗組： 同上 + -Dflowable.check-process-definitions=false
           → Tests run: 5, Failures: 0, Errors: 3
             FlowableObjectNotFoundException: No process definition found for key 'leave-approval'
             （ProcessResultReportingTest:62/92/99）
  ```

  結論：**測試套件依賴 classpath 自動部署**；把 BPMN 移出 jar 而不處理測試 fixture，
  會讓大量整合測試紅燈（且不是測試邏輯壞，是定義不存在）。

---

## 2. 決策

### 2.1 單一 writer 模型（採納：本 ADR 的核心）

```
業務人員／流程管理員
    │ (1) 編輯 XML，進版控（PR／設計器匯出後提交）        ← 唯一寫入 repo 的方式是 Git
    ▼
repo  bpmn-definitions/*.bpmn20.xml                      ← 真實來源：應該上線什麼
    │ (2) release 分支 push → GitLab CI bpmn:deploy
    ▼
GitLab CI ── (3) POST /api/deployments（Bearer JWT）──▶ DeploymentController
                                                         ├─ #53 替換（fail-closed 400）
                                                         ├─ lint（原始＋resolved）
                                                         ├─ 寫 runtime dir（原始 XML）  ← 唯一 writer
                                                         ├─ #61 git commit（原始 XML）
                                                         ├─ Flowable deploy（resolved）→ ACT_RE_*（真實來源：正在跑什麼）
                                                         └─ 稽核 xmlSha256／gitCommit／operator
```

角色分工（違反即失去單一 writer）：

| 位置 | 角色 | 誰可以寫 |
|---|---|---|
| `bpmn-definitions/`（repo） | **應該上線什麼**的真實來源；含佔位符的原始 XML | 人，且只透過 Git（PR／merge） |
| Flowable DB（`ACT_RE_*`） | **正在跑什麼**的真實來源 | 只有 `POST /api/deployments`（引擎部署） |
| `bpm.bpmn-definitions-dir`（runtime，#61） | **部署帳本**：每次部署的原始 XML＋Git 歷史；只寫不讀 | 只有 `DeploymentController`（透過 API 的任何呼叫端） |
| 稽核 DB | 跨兩者的對帳依據：`xmlSha256`＋`gitCommit` | 只有應用 |

為什麼 runtime 目錄不能當真實來源：它不被任何程式讀取（§1.1），容器重建即遺失（§1.6），
且內容是「上一次被 API 部署的檔案」，無法回答「目前應該是哪一版」——那只有 repo 能回答。

### 2.2 被否決的選項與理由

| 選項 | 評估 |
|---|---|
| **(a) repo 為源頭、CI 經 API 部署、runtime 目錄為帳本** | ✅ **採納**。重用 #53（替換）、lint、#61（版控）、稽核四道既有保證；CI 不需要任何主機權限；rollback 與 audit 走同一條路。 |
| (b) CI 直接寫入掛載的 runtime 目錄並自行 commit | ❌ 需要 runner 有目標主機的檔案系統權限（跨主機時等於 NFS/SSH 才能寫）；繞過 lint 與 #53 替換（CI 得自己實作），繞過稽核；CI 與設計器成為兩個 writer，JGit 鎖不跨行程；`git commit` 進的是 runtime repo（無 remote），不是平台 repo。 |
| (c) 在目標主機以 SSH／`compose exec` 放檔＋觸發 | ❌ CI 取得主機層權限（比 API token 大得多的憑證）；`docker cp`＋重啟不是原子操作且會中斷；同樣繞過 lint／替換／稽核；「誰部署了什麼」只剩 CI log。 |
| (d) 維持 BPMN 隨 jar（R-08 選項 B） | ❌ 與使用者已拍板的選項 A 相反；且 `flowable.check-process-definitions` 一律開啟時，rollback 舊 jar 在**內容已不同**時會把舊版 BPMN **重新部署成最新版**（見 §4.1），是比現在更危險的雙軌。 |
| (e) CI 端以 `yq`／Python 做 env 替換（R-08 原文選項 A 的一部分） | ❌ 與 #53 重複且方向相反。#53 的關鍵設計是「**原始 XML 才進版控**，resolved 只在記憶體」；CI 端替換會把環境值寫進 pipeline 產物，且兩套替換（CI＋部署期）必然漂移。**CI 不替換、不解析 `cicd/envs/*.yml`**。 |
| (f) 設計器直接部署（現況） | ⚠️ **dev 保留；sit/uat/prod 是否保留為 break-glass 待拍板**（§8-Q2）。它不是 writer 層級的問題（仍走 API），而是 release 紀律問題。 |

### 2.3 雙寫衝突如何避免

1. **runtime 目錄永遠不手改**、不 `git pull` 平台 repo 進去；`DeploymentGitCommitter` 的
   `openOrInitRepo` 會在空目錄自動 init，這是首次部署的便利，不是同步機制。
2. **平台 repo 永遠不由應用或 CI 在 runtime 寫入**；只有人經 Git 寫。CI 部署時不 commit
   平台 repo（內容已在 repo 裡）。
3. **引擎永不讀 runtime 目錄**（§1.1 已查證）——所以「jar 舊版 vs 目錄新版」不是引擎層的雙軌，
   真正的雙軌風險來自 classpath 自動部署，§4 處理。
4. **可對帳**：稽核有 `xmlSha256` 與 `gitCommit`（:230-252），任何時候可以回答
   「線上跑的那份對應 repo 哪顆 commit」；若 runtime 帳本與 repo 不一致，**以 repo 為準重部署**。
5. **同內容重部署不產生空 commit**（`DeploymentGitCommitter.java:104-120`），
   但會產生新引擎版本（§1.3）——CI 的冪等性見 §8-Q4。

### 2.4 Rollback 怎麼做

- **唯一做法：用同一條 pipeline 重部署上一個版本的 repo 內容**（GitLab 手動 job 帶
  `BPMN_REF=<tag/commit>` 變數，checkout 那個 ref 後走同一支腳本）。
- 效果：repo → API → 帳本＋稽核都有新紀錄；Flowable 建立新版本，**進行中的舊案不受影響**
  （spec §12.4，`docs/bpm-platform-spec.md:1285-1289`），新案走舊版定義。
- **不要**用「改 runtime 目錄」或「改 DB」rollback —— 兩者都會繞過稽核與 lint，
  而且 runtime 目錄不是引擎來源，改了也沒用。

---

## 3. CI 流程（GitLab）與認證方案

### 3.1 pipeline 形狀

```
push to dev / sit        push to uat / main
        │                        │
        ▼                        ▼
  bpmn:deploy 自動          bpmn:deploy 手動（when: manual）
        └──────────┬─────────────┘
                   ▼
  1. 由分支決定環境（dev→DEV、sit→SIT、uat→UAT、main→PROD）
  2. 前置檢查：BPMN_DEPLOY_URL／BPMN_DEPLOY_TOKEN 未設即 fail；
     bpmn-definitions/ 不存在或 0 個檔即 fail（防「空迴圈成功」）
  3. 逐檔 POST /api/deployments（Authorization: Bearer $BPMN_DEPLOY_TOKEN;
     -F file=@… -F name=$(basename …)）
  4. 每一檔檢查 HTTP 200 且回應含 "deploymentId"；任何一檔失敗 → 整個 job 紅
  5. 不做 env 替換、不呼叫 cicd/envs/*.yml
```

實作要點（對應 R-08 驗收「不存在執行成功但什麼都沒做」）：

- 觸發規則：**只跑 branch pipeline**（push 後），不跑 MR pipeline —— 部署發生在 merge 之後；
  `changes: [bpmn-definitions/**]` 保留（在 branch pipeline 語意正確）。
- `curl --fail-with-body -sS`；**移除**現有的 `|| echo "WARN…"`（`.gitlab-ci.yml:206`）；
  `-sf` 之外顯式驗證回應 JSON（400 lint 失敗、503 git 失敗都會非零）。
- 用 `nullglob` 或顯式 `ls` 檢查目錄非空；今天 job 的形狀是「掃不到就整段跳過、exit 0」，
  新 job 必須讓「沒有檔案」也是失敗。
- GitLab project 變數（masked/protected）放置 `BPMN_DEPLOY_TOKEN` 與 per-env URL；
  repo 內不落任何 secret。現有 `.gitlab-ci.yml` 對 `BPM_CORE_URL` 只有 fallback，
  從未定義實際值（:201）——這也是為什麼它過去不可能成功。

### 3.2 認證：CI 如何取得呼叫 `POST /api/deployments` 的授權

| 方案 | 機制（證據） | 評估 |
|---|---|---|
| **1. 服務帳號 JWT（建議）** | prod：CI 拿 client credentials 向企業 IdP 換 JWT（`OIDC_ISSUER_URI`，`JwtDecoderConfig.java:58-63` 只驗不簽）。`roles` claim 帶 `admin`（或 `*`）→ `ROLE_ADMIN`（`AuthorityResolver.java:155-157`）；或 claim 不帶 roles、由權限中心對 `sub` 回 `*`（`AuthorityResolver.java:179-201`）。dev：`scripts/dev-token.sh` 的 HS256 等價物（CI 變數存 `BPM_DEV_JWT_SECRET`）。 | 憑證可輪替、可撤銷、有到期；稽核 `operatorId` = 服務帳號 `sub`；**不需要新程式碼**；nginx 會轉發 Authorization（§1.6）。未決：IdP 能否發 client-credentials token、scope／audience、權限中心是否已有此帳號（§8-Q1）。 |
| **2. 閘道信任** | CI 帶 `X-Gateway-Secret`＋`X-User-Id`；`GatewayAuthenticationFilter` 驗密鑰後，以該身分查權限中心（:115-138）。**只給 `ROLE_GATEWAY` 不足以部署**（`SecurityConfig.java:217` 只保護 `/api/internal/**`；`/api/deployments` 要 `ROLE_ADMIN`，:295），權限中心必須剛好回 `*`。需 `GATEWAY_AUTH_ENABLED=true`＋`GATEWAY_SHARED_SECRET`（prod 預設關閉，`application.yml:384-385`）。 | 可作過渡／fallback：不需 IdP。缺點：共用密鑰是靜態的、持有者可冒充任意 `X-User-Id`；稽核身分可被偽造；prod 得因此開啟閘道。**不建議當長期方案。** |
| **3. 目標主機 SSH／`compose exec` 放檔＋觸發** | CI 取得主機存取後放檔到 runtime 目錄。 | ❌ 見 §2.2(c)。繞過 lint／替換／稽核、主機憑證過大、非原子。 |
| **4. 新增部署專用 API key／權限碼** | 例如新權限碼 `bpm:bpmn:deploy`，或 `/api/deployments` 接受 API key。 | 不在現有程式碼內，需新授權面與測試；短期內沒有比方案 1 更好的安全性（JWT 已可做到最小權限與稽核）。只有當 IdP 無法發服務帳號 token 時才值得評估；屆時優先方案 2 過渡。 |

**決策（2026-10-06，使用者拍板）**：**先用方案 2（閘道密鑰）過渡**，並搭配 **CI 專用權限碼**
（§8-Q2：`/api/deployments` 改認該權限碼，CI 服務帳號不具其他管理權；sit/uat/prod 的人為部署
只留 break-glass）。過渡要件與退出條件：

- prod 開 `GATEWAY_AUTH_ENABLED=true`＋`GATEWAY_SHARED_SECRET`（`application.yml:384-385`）；
  權限中心為 CI 服務帳號（`X-User-Id`）回新的部署權限碼（名稱 T2 定，例如 `bpm:bpmn:deploy`），
  **不得**回 `*` 或 admin —— 否則又回到「一把鑰匙全權」。
- 後端需調整 `/api/deployments` 的授權判定（目前只認 `ROLE_ADMIN`，`SecurityConfig.java:295`）
  以接受該權限碼 —— 這是 O2 進入 T2 範圍的原因（T2 估時因此上調）。
- 稽核：operatorId 為 CI 服務帳號；gateway 密鑰持有者可冒充任意 `X-User-Id`，過渡期以網路位置
  與密鑰保管控制風險。**退出條件：IdP 服務帳號 JWT（方案 1）就緒後切回並關閉閘道**（另立收回票）。
- dev：`scripts/dev-token.sh` 的 dev JWT（`ROLE_ADMIN`）繼續用於本機與驗收，不受上述影響。

無論哪個方案，**部署 job 使用的身分不得是一個可以互動登入的真人帳號**，稽核才答得出
「這次部署是 pipeline 做的還是人做的」。

### 3.3 Lint 在 CI 的位置

- CI **不重啟** env 替換（§2.2(e)），也不需要在 CI 內跑 `BpmnLintService`：部署 API 本身
  就是 lint gate —— 原始與 resolved 各驗一次，失敗 400 且零副作用（`DeploymentController.java:162-175`）。
  這是「一次寫、兩處用」：設計器按送審走 `POST /api/bpmn/lint` 的同一份服務。
- 想更早得到回饋（MR 階段）的代價很高：`BpmnLintService` 建構子依賴
  `FormService`／`ProcessVariableSpecRepository`／`ExternalSystemRepository`／`ExternalSystemPolicy`
  （`BpmnLintService.java:209-217`），是 DB-backed 服務，不是可攜 CLI；把它搬進 CI 需要
  額外抽出無 DB 的規則子集（新工程）。**R-08 範圍內不做獨立 lint job**；若未來要做，
  應以「呼叫目標環境的 `/api/bpmn/lint`」為先，而不是在 CI 重造規則。

### 3.4 與 R-07 的邊界與前提

- R-07 負責：刪 GitHub Actions、GitLab pipeline 單軌化、registry 與 prod compose 對齊、
  修 build/test 重複執行。R-08 只動 `bpmn:deploy` 的內容與新增 CI 變數需求。
- **runner 前提**：`bpmn:deploy` 只需要 `curl`，與 image registry 無關；但 runner 必須能
  連到目標環境（經 nginx 或內網 URL）。現有 `bpm-core:test` 用 `eclipse-temurin:21-jdk`
  跑 `mvn verify`（`.gitlab-ci.yml:43-48`），該 image 沒有 Docker socket／dind service，
  Testcontainers 類測試在該 job 內的可執行性存疑 —— 這是 R-07 的範圍，**不阻擋 R-08**，
  但 R-08 的驗收不能倚賴該 job 的綠燈（詳見 §7 估時）。

---

## 4. classpath 退場與測試影響

### 4.1 為什麼「先關設定、再移檔案」

Flowable 的自動部署是**開機行為**（§1.1）。若先移除 jar 內 BPMN、之後才關
`check-process-definitions`，兩次 release 之間會出現「新版 jar 沒有定義可 auto-deploy」的
空窗（引擎其實已有 DB 定義，但任何舊 jar rollback 在**內容已不同**時會把 classpath 舊版
重新部署成最新版 —— 自動部署雖有 `enableDuplicateFiltering()`，但那只擋「與最新部署同內容」
的重複，擋不住真正的版本回退）。反向順序（先關設定、後移檔案）沒有空窗，且對 rollback 安全：

1. **現在就可以做**：在 prod compose 注入 `FLOWABLE_CHECK_PROCESS_DEFINITIONS=false`
   （Spring relaxed binding，舊 jar 也吃這個環境變數）。此時 jar 內檔案還在，但開機不再自動部署；
   定義已存在 DB，服務行為不變。
2. release N+1 才移除 `processes/` 並在 `application.yml` 明確設
   `flowable.check-process-definitions: false`（這是防禦未來有人把檔案放回 jar）。
3. 測試 profile 在 `application-test.yml` 明確設 `true`（或由 Maven `testResources` 餵同一份檔案，
   見 §4.3）—— 測試是最需要開機自動部署的環境。

### 4.2 需要退場的檔案與影響面

| 檔案／位置 | 處理 |
|---|---|
| `bpm-core/src/main/resources/processes/{leave,purchase,countersign}*.xml` | 移入 repo `bpmn-definitions/`（byte-identical）。**不再打包進 jar。** |
| `application.yml:151-153`（Flowable 區塊） | 加 `check-process-definitions: false`。 |
| `application-test.yml:20-27` | 明確 `check-process-definitions: true`；保留 classpath `processes/` 來源（用 §4.3 的方式）。 |
| `scripts/seed-data.sh:64-74` | 路徑改指 `bpmn-definitions/`。順帶修一個既存小缺陷：腳本送 `-F "deploymentName=…"`，而 controller 讀的是 `@RequestParam(defaultValue = "") String name`（`DeploymentController.java:147`）——目前是靠「name 空的就 fallback 原始檔名」意外正確，應改成 `name`。 |
| `cicd/envs/*.yml` | **不在本票刪**（此檔唯讀）。BPMN 沒有佔位符、CI 不再替換，這些值已無消費者 → 交由 R-07 或後續清理票處置（§8-Q3）。 |

### 4.3 測試 fixture：用 Maven testResources 映射，零複本（**映射延後至 T4，2026-10-06 修訂**）

> ⚠️ **修訂（T1 實測）**：映射**不可**與 `src/main/resources/processes/` 並存。
> `classpath*:/processes/` 會回兩個 root 的同名檔（`target/classes`＋`target/test-classes`）；
> `DefaultAutoDeploymentStrategy` 把兩份放進**同一 deployment**，`verifyProcessDefinitionsDoNotShareKeys`
> 直接拋 `same key 'leave-approval'` → Spring context 起不來（T1 全量：921 errors）。
> 因此本節映射**與 §6 PR-4（移除 main 副本）同一批落地**；T4 啟用映射時，測試前必須
> `clean`（殘留的 `target/test-classes/processes/` 會造成假性雙 root，T1 已踩過）。

不要複製三份 BPMN 到 `src/test/resources/processes/`（複本就等於第二個真實來源）。
在 `bpm-core/pom.xml` 把 `../bpmn-definitions` 加成 test resource，並映射到 classpath 的
`processes/` 目錄：

```xml
<build>
  <testResources>
    <testResource><directory>src/test/resources</directory></testResource>
    <testResource>
      <directory>${project.basedir}/../bpmn-definitions</directory>
      <targetPath>processes</targetPath>
    </testResource>
  </testResources>
</build>
```

效果：

- Flowable 開機自動部署照舊從 `classpath*:/processes/` 找到三份檔案（`application-test.yml`
  的 test profile 不變）；
- §1.7 那 9 處 `getResourceAsStream("processes/…")` 零修改；
- 檔案實體只有一份（`bpmn-definitions/`），不可能漂移。
- 若 `../bpmn-definitions` 在 IDE 或其他建置工具下不支援，退路才是「test resources 複本＋
  一個比對 byte-equal 的 drift guard 測試」；以 Maven 為準（CI 與本機驗證指令都用 Maven）。

### 4.4 dev／test 過渡後的行為差異（必須記錄）

- **dev 容器**：新 DB 從「開機就有三支流程」變成「跑完 `seed-data.sh` 才有」
  （seed 仍然走 API，只是路徑改指 `bpmn-definitions/`）。dev bring-up 文件要更新為
  `docker compose up` → `seed-data.sh`；acceptance-test 本來就依賴 seed，流程不變。
- **測試**：不變（test profile 仍自動部署，來源是同一份檔案）。
- **SIT/UAT/prod**：首啟不再有任何出廠流程，**必須先由 CI（或管理員）部署定義才有流程可跑**。
  這是刻意的：環境的「應該上線什麼」由 repo 決定，不由 jar 決定。

---

## 5. 運維

### 5.1 prod runtime 目錄持久化

在 `docker-compose.prod.yml` 的 bpm-core 加：

```yaml
    volumes:
      - bpm-uploads:/app/uploads
      - bpm-bpmn-definitions:/app/bpmn-definitions
# 檔案末端
volumes:
  bpm-bpmn-definitions:
```

`Dockerfile:27-30` 已預建 `/app/bpmn-definitions` 並 chown `appuser`，第一次掛 named volume
時 Docker 會沿用 image 目錄的 ownership，非 root 寫入沒問題。沒有這個 volume，
`BPM_BPMN_GIT_ENABLED=true` 在容器重建後就從零開始（原本的部署歷史消失）。

### 5.2 `BPM_BPMN_GIT_ENABLED` 與本設計的關係

- 它控制的是**帳本**（§2.1），不是部署來源：`enabled=false` 時部署照常，只是沒有 Git 歷史
  （稽核仍有 `xmlSha256`）。
- 本設計下建議 prod **開啟**（round21 §4 已列），因為 rollback 對帳與「誰在何時改了哪一版」
  需要歷史；但必須與 5.1 一起，否則重建即遺失。
- **它不是備份**：runtime repo 沒有 remote（`DeploymentGitCommitter` 只 commit）、沒有 push。
  真正的 durable 來源是平台 repo（nsl remote）＋ MSSQL（含 `ACT_RE_*` 與稽核 DB）。

### 5.3 備份

| 資料 | 角色 | 備份策略 |
|---|---|---|
| 平台 repo `bpmn-definitions/` | 真實來源 | 既有的 Git remote（nsl）即備份；CI 部署的檔案指紋（audit `xmlSha256`）與 commit 對得上 |
| Flowable DB（`ACT_RE_*`） | 運行中定義 | 併入 MSSQL 既有備份策略（這是「正在跑什麼」） |
| runtime 目錄＋其中的 `.git` | 帳本 | 建議納入主機備份；即使遺失，可從 repo 重部署收斂，稽核也留有指紋 |

### 5.4 多實例警告

`DeploymentController` 寫本機檔案、`DeploymentGitCommitter` 只有行程內鎖
（`DeploymentGitCommitter.java:52-57`）。目前 prod compose 是單一 bpm-core 實例，成立；
若未來水平擴充，必須共享 volume 或把部署固定路由到單一實例，否則帳本會分叉。
本 ADR 不解決這個問題，但把它記在設計前提下。

---

## 6. 遷移步驟（可執行的順序）

> 目標：BPMN-as-code 上線且**零中斷**。順序刻意「先組態、後內容」，每一步都可獨立 rollback。

1. **前置（R-07）**：GitLab CI 單軌化、runner 可連目標環境；建立 GitLab masked/protected 變數
   （`BPMN_DEPLOY_TOKEN`、per-env `BPMN_DEPLOY_URL`）。取得服務帳號 JWT 的來源（IdP 或 dev 密鑰）。
2. **PR-1（repo 內容搬家，不動行為；✅ T1 已完成 2026-10-06，`d921742`）**：新增 `bpmn-definitions/`
   三檔（byte-identical）；`application-test.yml` 明確 `check-process-definitions: true`；修
   `seed-data.sh` 路徑與 `name` 參數。**不含 `pom.xml` testResources 映射**（見 §4.3 修訂：必須與
   PR-4 同批）。此時 jar 仍自動部署、CI 仍未啟用 —— 跑 `mvn -f bpm-core/pom.xml clean verify` 與
   dev `seed-data.sh`＋`acceptance-test.sh` 全綠再進下一步。
3. **PR-2（CI 真的會部署）**：改寫 `.gitlab-ci.yml` 的 `bpmn:deploy`（§3.1）；
   先手動在 dev 驗證一檔（可先用小改動觸發），確認 200＋`deploymentId`、稽核有記錄、
   runtime 目錄有檔案；再開 SIT。**負控**：把 glob 改錯／把 URL 指到不可達，job 必須紅。
4. **PR-3（運維設定，無應用程式碼）**：prod compose 加 `bpmn-bpmn-definitions` volume、
   `FLOWABLE_CHECK_PROCESS_DEFINITIONS=false`、`BPM_BPMN_GIT_ENABLED=true`；部署後由 CI
   手動對 prod 部署一次（此時仍是舊 jar，但定義已進 DB），確認稽核與帳本。
5. **PR-4（classpath 退場；含 testResources 映射）**：刪 `src/main/resources/processes/`；
   `pom.xml` 加 `testResources` 映射（§4.3，零複本）；`application.yml` 設
   `check-process-definitions: false`。測試前 `clean`。release 後驗證：重啟後三支流程仍在
   （`GET /api/process-definitions`）、`seed`＋acceptance 全綠、`mvn verify` 全綠。
   Rollback 若發生：回到舊 image，但 compose 的環境變數已關閉自動部署 → 不會把舊 BPMN
   重新部署成最新版。
6. **收尾**：R-07 刪 `.github/workflows/bpmn-deploy.yml`（與其他 GitHub workflows）；
   `cicd/envs/*.yml` 的 BPMN 佔位內容依 §8-Q3 處置；文件（spec §12.2 流程圖、README-testing、
   handoff）更新為「CI 經 API 部署」。

---

## 7. 實作票清單（R-08 實作批次）

> ⚠️ **原 R-08 估 1 人日，實際約 2.5 人日。** 原因：原估時把 R-08 當成「搬檔案＋修 CI 腳本」，
> 沒有計入（i）CI 認證與 GitLab 變數、（ii）測試對 classpath 的依賴需要 fixture 策略、
> （iii）prod volume／rollback 設定與負控驗證。三項都是設計票才查清的事實。

| 票 | 一句話 | 相依 | 估時 |
|---|---|---|---|
| **T1** ✅ | 建立 repo `bpmn-definitions/`（3 檔 byte-identical）、`application-test.yml` 明確 true、修 `seed-data.sh` 路徑與 `name` 參數（**testResources 映射移 T4**）；完成 `d921742` | 無 | 0.5d ✅ |
| **T2** | GitLab `bpmn:deploy` 改真實部署：branch 規則、**閘道密鑰過渡＋CI 專用權限碼（O2 後端授權調整）**、逐檔部署、非空／HTTP／`deploymentId` 檢查、失敗大聲紅；取得 GitLab 變數與身分來源 | T1、R-07 runner 前提、§8-Q1／Q2 已拍板 | 1d |
| **T3** | prod compose：`bpmn-bpmn-definitions` volume＋`FLOWABLE_CHECK_PROCESS_DEFINITIONS=false`＋`BPM_BPMN_GIT_ENABLED=true`；文件化 rollback 程序 | T1 | 0.25d |
| **T4** | classpath 退場：移 `src/main/resources/processes/`、`application.yml` 設 false、**加 Maven testResources 映射（§4.3；測試前 clean）**、重啟驗證、`seed`＋acceptance | T2、T3 上線驗證後 | 0.5d |
| **T5** | 驗收：負控（目錄空／URL 錯／token 錯 → job 紅）、正向（dev 部署成功、稽核有 `gitCommit`）、`mvn verify` 全綠、handoff 記錄 | T2、T4 | 0.25d |
| | **小計** | | **2.5d**（T1 ✅；剩 T2–T5 約 **2d**） |
| **備用** | IdP client-credentials 未能及時提供 → 方案 2 閘道過渡（含 prod 開啟閘道與權限中心設定）＋後續收回票 | T2 | 0.25d～0.5d |

**選配（另立票，不含在上表）**：

- O1：IdP 服務帳號開設與權限中心帳號（維運工時，非程式）。
- O2：`/api/deployments` 改用專用權限碼（例如 `bpm:bpmn:deploy`）而不是 `ROLE_ADMIN`，
  讓 CI 服務帳號不具備其他管理權（§8-Q2 若拍板「CI 專用」就需要）。
- O3：是否啟用 `enableDuplicateFiltering()`（重部署冪等化；會改變版本號行為，§8-Q4）。
- O4：清掉 `cicd/envs/*.yml` 的 BPMN 佔位內容（建議隨 R-07）。
- O5：設計器在 sit/uat/prod 的部署按鈕去留（§8-Q2）。
- O6：runtime 帳本 remote push／多實例共享 volume（若拍板要）。

---

## 8. 未決問題（需 PM／維運拍板）

1. ✅ **已拍板（2026-10-06）**：先用**閘道密鑰過渡**（方案 2）；IdP 服務帳號 JWT（方案 1）列為
   退出條件，就緒後收回並關閉閘道（另立收回票）。仍待維運確認：權限中心可建立 CI 服務帳號、
   `GATEWAY_SHARED_SECRET` 保管與網路限制。
2. ✅ **已拍板（2026-10-06）**：**CI 專用權限碼**（O2 進入 T2 範圍；名稱 T2 定，例如
   `bpm:bpmn:deploy`）；sit/uat/prod 的人為部署只留 break-glass。設計器在 dev 保留（O5 於 T2
   一併處置 sit/uat/prod）。
3. **`cicd/envs/*.yml` 處置**：BPMN 沒有佔位符、CI 不再替換——刪除、或保留為未來
   「把候選群組改成 `${ENV_*}`」的參考？若要真的啟用環境差異替換，那是 BPMN 內容工作
   （把 resolver 群組改成佔位符）＋各環境注入 `BPMN_VARIABLES_*`，需要另外排。
4. **重部署冪等性**：要不要開 `enableDuplicateFiltering()`？不開：CI 重跑與 rollback 都會多一版
   （功能正確，但版本號膨脹、稽核 `deploymentId` 每次都不同）；開了：同內容重跑成為 no-op，
   但「rollback 到同內容」也會被視為已部署而不產生新紀錄（需確認這是不是想要的語意）。
5. **runtime 帳本的可攜性**：是否要 push 到 remote（多一台主機的災難回復）？多實例時
   volume 共享策略？（§5.4 目前是單實例前提。）
6. **全新環境 bootstrap**：`check-process-definitions=false` 後，新環境首啟沒有任何流程，
   「先起服務、再讓 CI 部署定義」的順序是否接受？誰負責（CI 手動 job／維運腳本）？
7. **SIT/UAT 的 token 路徑**：它們跑 prod profile（`issuer-uri` 必需），IdP 在這些環境是否同樣可用？

---

## 9. ⚠️ 不要承諾的事

- **這不是 GitOps 自動同步**：設計器在 dev 的直接部署**不會**自動回流平台 repo。
  「dev 設計 → PR → sit/uat/prod」仍需人以 PR 把 XML 帶進 repo（設計器已有匯出）。
- **runtime 帳本不是備份**：沒有 remote、沒有 push；容器重建即遺失（除非 §5.1 volume）。
- **rollback 不是 DB 還原**：是「重部署舊版 repo 內容成為最新版」；已完成的案件不會被改寫。
- **不會復活 CI 端 env 替換**：#53 是唯一替換點；`cicd/envs/*.yml` 不會被 CI 讀取。
- **不會自動產生環境差異**：目前 3 份 BPMN 沒有 `${ENV_*}`，這條 pipeline 上線後
  「多環境差異」仍是空集合 —— 要啟用得先改 BPMN 內容（§8-Q3）。
- **估時不含外部等待**：IdP 開帳、GitLab runner 網路／變數開通、目標環境的部署權限，
  都是維運前置，不是 2.5 人日的一部分。

---

## 10. 驗收條件（實作批次）

- [ ] `bpmn-definitions/` 存在且為三份 BPMN 的唯一實體副本（`src/main/resources/processes/` 已不存在）
- [ ] CI 在 `bpmn-definitions/**` 變更時**真的**呼叫 API 並成功部署；回應含 `deploymentId`
- [ ] **負控**：目錄為空、目錄不存在、URL 不可達、token 無效 —— 四者任一都讓 job **紅**
- [ ] 不存在任何 `|| echo WARN` 類的吞錯；job 的綠燈等價於「檔案已部署」
- [ ] CI 不做 env 替換（程式碼掃描：job 不讀 `cicd/envs/`）
- [ ] dev：`seed-data.sh`（新路徑）＋`acceptance-test.sh` PASS 不減
- [ ] `mvn -f bpm-core/pom.xml verify` 全綠（測試仍靠 testResources 映射自動部署出廠流程）
- [ ] prod：volume 存在、容器重建後 `bpmn-definitions` 歷史仍在（若 `BPM_BPMN_GIT_ENABLED=true`）
- [ ] rollback 演練：用 `BPMN_REF` 重部署前一版，新案走舊版、舊案不受影響、稽核有記錄
- [ ] 舊 jar rollback 情境：回到前一版 image，開機**不**自動部署 classpath 舊 BPMN

---

## 附錄：本票查證方式

- 靜態：全 repo grep（`bpmn-definitions`、`bpm.bpmn`、`ENV_`、`check-process-definitions`）、
  Flowable 8.0.0 套件 metadata／`javap`（自動部署預設值、`DeploymentBuilder` 介面）、
  `git log --all -- bpmn-definitions`。
- 動態（設計票允許範圍內）：單一測試 A/B 實驗（§1.7），使用 Testcontainers 的真實 MSSQL／
  RabbitMQ／Redis；**未**啟動 docker compose、未線上實測、未跑完整套件。
- 未執行完整 `mvn clean verify`（設計票不需要）；測試現況引用 round22 handoff 的 1656 綠
  （`docs/handoff/2026-10-05-round22-handoff.md`）。
