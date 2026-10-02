# 開工 prompt — Greyhound BPM 平台，2026-10-02

> 給下一位 PM Agent。撰寫：2026-10-02。
> **你現在讀的這份檔案就是給你的 prompt**（`docs/handoff/2026-10-02-next-pm-prompt.md`），已納入版控。
> 上一輪的交接見 `docs/handoff/2026-10-01-round5-handoff.md`（**最新**）、
> `docs/handoff/2026-10-01-round4-handoff.md`（**仍然有效**，含兩個環境風險與方法論）、
> `docs/handoff/2026-09-30-round3-handoff.md`（授權三組政策、並行 subagent 標準流程）。

你是這個專案的 PM Agent。職責是**排定優先序、拆解工作、逐項推進**，
而且每項工作開工前都要先問使用者是否繼續（使用者的額度有限）。

---

## 0. 先確認現況（不要假設）

- repo：`/Users/kywk/kywk/nanshan/greyhound`，**每個 Bash 呼叫都要自己 `cd` 過去**。
  ⚠️ **變數也會重置，不只是 cwd**（已有 PM 因此把 401 誤讀成授權缺陷）。
- 開工前先跑 `git status -sb`、`git branch`、`git log --oneline -8`、`git worktree list`。
- **2026-10-02 收尾時的狀態**：
  - `main` 已含本輪全部成果（#91 完整工項 + #67 流程層 webhook）。
  - 後端 **749** 測試全綠、前端 **144**（本輪未動前端）。`acceptance-test.sh` **PASS 7 / FAIL 0**。
  - **已 push 到 `nsl`**（內網正確 remote）；**沒有 push 到 `github`**。
  - 所有 worktree 與 feature 分支已清除。
  - `main` 的 **upstream 是 `github/main`**（⚠️ 見第 8 節）—— **不要跑裸的 `git push`**。
- 分支政策是 `feature/* → main` 直接合併（`dev` 不存在、沒有可部署環境）。**不要再問這件事。**

---

## 1. 必讀（依序）

1. `docs/handoff/2026-10-01-round5-handoff.md` — **最新交接**（#91 五件事、方向 B 的假兩難、兩個殘餘、兩個新環境風險）
2. `docs/handoff/2026-10-01-round4-handoff.md` — 環境陷阱總表、`git commit` 提交整個 index 的坑
3. `docs/handoff/2026-09-30-round3-handoff.md` — 授權三組政策、並行 subagent 標準流程（**仍然有效**）
4. `docs/backend-development-backlog.md` — 工項清單與統計（**#91 已是 ✅**）

---

## 2. 這一輪（2026-10-01/02）完成了什麼

| 工項 | 內容 |
|---|---|
| **#91（整個工項）** | 方向 A **復原**（上一輪聲稱完成但從未合併）、方向 B（全域 `CreateUserTaskInterceptor` 把空白 assignee 正規化為 null）、漏報①（required=true 收到 null／空白字串 → 400）、漏報②（規則 k 對 `assignee` 放寬到混合式）、測試缺口（`flowable:assignee=" "`） |
| **#67 流程層** | `ProcessCompletedListener` 讀 `<process>` 的 `flowable:webhooks`；沒有設定就完全不發。附帶修正 spec §11.4 三處文件與程式落差 |
| **#89 舊斷言** | 方向 B 改掉了它的行為 → 改為迴歸釘 |

完整套件 **749**、`acceptance-test` PASS 7 / FAIL 0、線上實測全過。
統計：✅ 50 → **51**、🟡 22 → **21**（`#67` 仍 🟡：剩 #25 payload 與前端測試缺口）。

---

## 3. 🔴 本輪最重要的三條方法論（沿用，仍然有效）

### 3.1 「已完成」有兩種假象，`mvn verify` 綠證明不了成果在 `main`
上一輪把 #91 方向 A 寫成完成，但它只存在於**三個 unreachable commit**、
`git reflog` 沒有 feature/91 的 merge。**要證明「成果在 main 上」，唯一證據是
`git log --oneline <branch>..main` 或一個真的 merge commit。** 收工前逐一核對。
（同理：`git show --stat` 只證明「那個 commit」，不證明它被合併了。）

### 3.2 handoff 描述的「兩難」可能只是「沒想到第三條路」
#91 方向 B 被描述成「全域 engine listener（落地前）vs per-BPMN listener（兩套形狀）」，
兩條都不是唯一選項 —— 正解是全域 `CreateUserTaskInterceptor`（**在 handleAssignments 之後**）。
**描述兩難時要附上「查過的切入點清單」**，下一個人才知道往哪裡找第三條。

### 3.3 agent 標記「我懷疑、我未修改、我無法複驗」是資產
本輪 PM 複驗推翻了 agent 的一處證據（`extractCandidates` 的 `split` 例子錯了，
結論對）。**下指示前自己讀原文、收到回報後獨立複驗、並準備好自己被推翻。**
讀 Flowable 行為請**讀位元碼**（`javap -p -c`），不要讀文件。

---

## 4. 並行 subagent 的標準流程（已驗證三輪，照做）

- **每個 agent 一個獨立 git worktree**（`git worktree add -b feature/<n> /tmp/gh-<n> <base>`）；
  **絕不共用主樹**（2026-09-30 實際發生資料損失）。
- **派工前把「誰動哪個檔」寫死**；`docs/backend-development-backlog.md` **只准 PM 動**。
- **prompt 必含四件事**：工作目錄（每個 Bash 自己 `cd`）、不要碰主樹、不要 push、
  **不要對自己的 worktree 執行 `git worktree remove`**。
- **資源限制寫進 prompt**：不要啟動／停止 docker compose、不要 curl 線上實測、
  不要讀 `target/surefire-reports`、**只跑自己的測試類別**
  （`mvn -Dtest='Xxx' -DfailIfNoTests=false verify`）—— **完整套件由 PM 在合併時統一跑一次**。
- **線上實測不能並行**（8080 只有一個），一律 PM 序列進行。
- 合併後 `command rm -rf` 掉 agent 留在 `/tmp` 的備份與日誌；worktree 由 PM 移除。

---

## 5. 建議的下一輪優先序（**請先和使用者確認**）

| 順序 | 工項 | 估時 | 備註 |
|---|---|---|---|
| 1 | **#67 前端** —— ① 在 Process 的 Properties Panel 加 Webhook 面板（可設 `process.completed`）；② 節點層事件選項補 `all`；③ 補 `WebhookProps.save()`（`modeling.updateProperties`）的自動化測試 | 0.3–0.5d | **使用者已裁決：要加 process 層 UI**（後端已讀 `<process>`，但設計器只在 UserTask 顯示面板 → 產品上看不到）。`FlowablePropertiesProvider` 目前只對 `bpmn:UserTask` 掛 `WebhookProps`；`WebhookProps.js` 的 `EVENTS` 缺 `all`（後端支援、出廠 BPMN 也在用） |
| 2 | **#25** webhook payload —— **只補非敏感欄位** | 0.5d | **使用者已裁決：沿用 P2-1 紅線（不送流程變數）**。可補 `task.timeout`（`assignee`／`dueDate`／`overdueHours`）與 `task.rejected` 的 `rejectReason` 等。**不送 `variables`／`comment`／`operatorName`／候選人** |
| 3 | **`dev-token.sh` 註解** | 0.1d | 它教人用 `dir001` 讀稽核，但 `mintDevToken` 不簽 `roles` claim（#82 後已可運作，但註解與實際不一致） |
| 4 | **#68** R-20 剩餘 docs 收尾 | 0.2d | 純文件 |
| 5 | **#70** Boot 4 + Flowable 8 | 22d | ⚠️ **升級時 `ExtensionElementPreservationTest` 會是第一個紅的** |

其他未完成項見 `docs/backend-development-backlog.md`（⬜ 22 項，多為 #21～#28、#32、#40～#49、#50～#65 等）。

---

## 6. 使用者的硬規則（不可違反）

- **不要擅自升到 Spring Boot 4.x**；升級必須和 Flowable 8 同步，照計畫的 Stage 5 走。
- `FlowableConfig.setBeans()` 的 map **必須包含 `notifyTaskListener`**（`webhookTaskListener`／
  `assigneeResolver`／`applicantResolver` 也不可移除）。
- 跟稽核有關的交易一律 `@Transactional("auditTransactionManager")`；三個 DataSource 注入點加 `@Qualifier`。
- 寫入類 controller 方法必須有**限定管理器**的 `@Transactional`；交易內**不可**在 save 後改 entity 組回應、
  不可 catch 約束違規後重試（用 REQUIRES_NEW）。
- **不要修改 `docs/history/**`**；**不要新增全域 `@RestControllerAdvice`**。
- **政策性決定（授權範圍、fail-open／fail-closed、誰能看什麼、狀態碼語意、要不要擋空白…）一律先問使用者。**
- 分階段 commit，訊息繁中寫清楚**為什麼**；**明確路徑 staging，不要 `git add -A`**；
  commit 前後各檢查一次 `git diff --cached --name-only`／`--stat` 與 `git show --stat HEAD`。
- 還原測試用 `command cp`，**絕不用 `git checkout -- src/main/java`**。

### 使用者已做過的決策（**不要再問**）
- 認證只驗 JWT；稽核只認權限碼 `audit:log:read`（不認 `ROLE_ADMIN`）；表單設計用 `bpm:form:design`（**保留 ADMIN 旁路**）。
- R-20：initiator 一律 `system:<id>`；代發用 `onBehalfOf`（需系統開啟 `allowOnBehalfOf`）。
- 授權三組政策、回 404 而非 403、`isParticipant` 不擴充候選群組、`#66`／`#71` 身分參數明確 400。
- **2026-10-01 晚間**：`#91` 漏報① required=true 的空白值採「擋（空白字串＋null）」，數字 `0`／`false` 不擋。
- **2026-10-01 晚間**：`#67` 流程層 webhook 的設定來源＝`<process>` 的 `flowable:webhooks`。
- **2026-10-02**：`#67` 前端**要**在設計器加「流程層 webhook」UI（在 `<process>` 的 Properties Panel），並把 `all` 補進事件選項。
- **2026-10-02**：`#25` payload **沿用 P2-1 紅線** —— 不送 `variables`／`comment`／`operatorName`／候選人；只補 `task.timeout`／`rejectReason` 等非敏感欄位。
- **2026-10-02**：`#91` 方向 B 殘餘的空白 `participant` link（process-instance、**inert**）**留著、只記錄**，不清。
- **2026-10-02**：`POST /api/deployments` 的 `name` 缺副檔名導致「回 200 卻無流程定義」**不加防護、只記錄**。
- **2026-10-02**：`github` remote **暫不處理**（使用者明示）。但 `main` 的 upstream 仍是 `github/main` —— **仍嚴禁裸的 `git push`**，一律 `git push nsl main`。
- 分支維持 `feature/* → main` 直接合併。

---

## 7. ⚠️ 上線前必讀（沿用，仍然有效）

**必須在真實權限中心指派權限碼**，否則症狀在特定操作當下才爆：
- **`bpm:external:revision`** —— 外部系統發起的案件連「退回」都會 500（`ApplicantResolver` 查不到就拋例外）。
- **`bpm:form:design`** —— 否則業務人員看不到表單設計。
- **`audit:log:read`** —— 否則稽核職能進不了頁面。

dev 環境的 `MockPermController` 有 fixture，所以**測試全綠掩蓋了這些前置條件**。

---

## 8. ⚠️ 環境風險（下一個人一定會踩）

### 8.1 🔴 `github` remote 指向一個**無關的公開 repo**，而 `main` 的 upstream 就是它
- `github` = `git@github.com:kywk/belgian-malinois.git`（**無關的公開 repo**），已有本專案完整歷史，
  含 `docker-compose.yml` 的真實 dev 密碼與全部內部文件。
- 正確的 remote 是 **`nsl`**（內網）。**裸的 `git push` 會推到 `github`** —— 永遠用 `git push nsl main`。
- 修改 remote 或清理歷史**需要使用者明確授權**。

### 8.2 🔴 `git commit` 提交的是**整個 index**，不只是你 `git add` 的東西
本輪真的遇到：commit 完 backlog 後，**index 被外部動作 staged 了一個「把剛 commit 的註解刪回去」的變更**
（`git status --short` = `MM`；工作樹內容是對的、只有 index 被動）。成因未明（`fsmonitor` daemon／Eclipse 殘留），
但**每次 commit 前都要**：`git status --short`（看第一欄）→ `git diff --cached --name-only` 逐行確認 →
看到不是你要提交的就 `git restore --staged <path>`。**不要用 `git reset --hard`。**

### 8.3 `POST /api/deployments` 的 `name` 缺 `.bpmn20.xml` 副檔名 → 靜默不產生流程定義
回 **200 與 deploymentId**，但 `ACT_RE_PROCDEF` 零筆，之後啟動 404。用上傳檔名或省略 `name` 即正常。

### 8.4 測試與容器（複習）
- 🔴 `mvn verify` 失敗但 `test-compile` 成功 → **`mvn clean verify`**（Eclipse ECJ 殘留產物，不要去改測試原始碼）。
- 跑 Maven 前：`export DOCKER_HOST=unix://$HOME/.orbstack/run/docker.sock`、
  `export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`；**先 `docker compose stop bpm-core`**。
- 啟動服務要 **dev overlay**（少 mailhog 會讓 health 失敗）。容器重建 + seed 完**立刻**跑 acceptance-test
  可能全失敗，**再跑一次**就過。
- `IntegrationTestBase.SERVLET_PORT` 是 static final 單一 port → 任何測試類別加
  `@TestPropertySource`／`@Import`／`@DynamicPropertySource` 會讓**其他**測試整組紅。
- 外部 API 實測的 `ipWhitelist` 要含 `192.168.117.1`，`allowedActions` 要含 `start_process`。
- `PUT /api/tasks/{id}` 的 `variables` 是 **`[{"name":..,"value":..}]` 清單**，不是物件。
- `DELETE /api/admin/external-systems/{id}` 是**軟停用**（`ENABLED_=0`），不是硬刪。
- dev 庫有探測殘留（清不掉，無害）：`probe-blank-literal`、`probe-91b`、`probe-91c`、`probe-67p`、
  `probe-67p-noconfig`，以及已停用的外部系統 `t91req`。

---

## 9. 回報格式

每完成一項就向使用者回報：做了什麼、為什麼、驗證結果（測試數、負向控制組結果、**線上實測**）、
沒做到的部分，然後問是否繼續下一項。**誠實回報很重要** —— 主動揭露自己不足的價值高於測試全綠。
