> ⚠️ **本檔已被取代（2026-10-01）。** 最新的是
> **`docs/handoff/2026-10-01-next-pm-prompt.md`**，交接是
> `docs/handoff/2026-10-01-round4-handoff.md`。
>
> 本檔保留作為紀錄。**已知與現況不符的兩處**：
> ① 第 3 節建議的優先序（#90／#93／#91／#92／#67／#82）**全部已完成**；
> ② 後端測試數記 629、前端 93 —— 現況是 **693／144**（前端基線是 96 不是 93）。
> ①「授權類缺陷只有線上實測才抓得到」仍然成立且本輪再次驗證；
> ②「並行 subagent 必須用獨立 worktree」仍然有效，但**新增了「派工前劃檔案邊界」**。

# 開工 prompt — Greyhound BPM 平台，2026-09-30 第二輪收尾後

> 給下一位 PM Agent。撰寫：2026-09-30。
> **你現在讀的這份檔案就是給你的 prompt**（`docs/handoff/2026-09-30-next-pm-prompt.md`），
> 已納入版控。
> 上一輪的完整交接見 `docs/handoff/2026-09-30-round3-handoff.md`
> （**那份仍然有效**）、`docs/handoff/2026-09-29-authorization-hardening-handoff.md`、
> `docs/handoff/2026-09-29-agent-handoff.md`。

你是這個專案的 PM Agent。職責是**排定優先序、拆解工作、逐項推進**，
而且每項工作開工前都要先問使用者是否繼續（使用者的額度有限）。

## 0. 先確認現況（不要假設）

- repo：`/Users/kywk/kywk/nanshan/greyhound`，**每個 Bash 呼叫都要自己 `cd` 過去**
  （shell 的 cwd 會重置 —— 這是最容易漏掉的一條）。
- 開工前先跑 `git status -sb`、`git branch`、`git log --oneline -5`、`git worktree list`
  確認，此後可能已有新變動。
- **2026-09-30 收尾時的狀態**：`main` 已含第二輪全部成果（#86 #79 #81 #87 #80 #83
  #88 #89 #67節點層 #68a/b/d + 四項裁決實作），後端 **629** 測試全綠、前端 **93** 全綠。
  **尚未部署。所有 worktree 與 feature 分支已清除。**
- 分支政策是 `feature/* → main` 直接合併（`dev` 不存在且沒有可部署環境，
  所有 deploy job 還是 `echo` 佔位）。**不要再問這件事。**

## 1. 必讀（依序）

1. `docs/handoff/2026-09-30-round3-handoff.md` — **最新交接**，含踩過的坑、
   授權三組政策、使用者硬規則、並行 subagent 的標準流程
2. `docs/handoff/2026-09-29-authorization-hardening-handoff.md` — 第 4 節環境與
   資料庫陷阱仍然有效、第 7 節硬規則
3. `docs/backend-development-backlog.md` — 92 項工項，**已完成 45 項**，
   剩餘 26 項未開始（見 `main` 上的統計表）
4. 需要時再讀：`docs/plan/2026-09-28-springboot4-upgrade.md`（含前置調查結論）、
   `docs/rbac-enterprise-backlog.md` 第十三節、`docs/bpm-platform-spec.md`

## 2. 這一份 prompt 最重要的兩件事

### 2.1 授權類缺陷的發現方式 —— 讀程式碼和跑測試都不夠

2026-09-30 那一輪，`mvn verify` 450 個測試全綠的時候，
**「整批取代的端點第二次呼叫必定 500」這個缺陷仍然存在**。

那一輪的每一個缺陷都是靠**對執行中的服務用真實 JWT 做 curl 實測**抓到的。
端點授權規則檢查（`AuthenticationTest`）對這類缺陷完全無感 ——
`/api/**` 是 `authenticated()`，權限檢查是健全的，缺陷在**物件層**。

**所以你的排程必須保留「線上實測」這個步驟，不要因為測試全綠就當作做完了。**

另外四件會浪費時間的事（2026-09-30 實測確認）：

- **新守衛會讓既有測試變成「空斷言」** —— fixture 提前被擋下，斷言就變成
  無論缺陷在不在都通過。正確做法是**改 fixture 成真實資料**，不是放寬預期值。
- **負向控制組要揭露「修法的形狀」，不只是「測試會紅」**。實例：把「任務必須在
  runtime」塞進 `ProcessAccessGuard.processInstanceIdOfTask` 這個**錯誤**修法
  **也會讓 404 的測試變綠**，只有「讀取端仍然工作」那條分辨得出來。
  **「綠了哪幾條」比「紅了哪幾條」更有資訊。**
- **「整批取代／刪除後重建」型端點要連續呼叫兩次**。#86 的缺陷只有在新舊資料
  **有同名項**時才觸發 —— 手動試一次很容易剛好試在綠的那一側。
- **還原手段絕對不要用 `git checkout -- src/main/java`** —— handoff 明文禁止。
  前兩輪的 subagent 都踩過，丟掉自己已修改的 tracked 檔。
  2026-09-30 又有一個 subagent 踩了同樣的坑並誠實回報。用 `command cp` 備份。

### 2.2 並行 subagent 必須用獨立 worktree

2026-09-30 有一次兩個 agent 共用主樹，**實際發生檔案被捲進對方 commit、
另一個人的修改被整份還原掉的資料損失**。

**流程見 `docs/handoff/2026-09-30-round3-handoff.md` 第 10 節**，其中：

- PM 在分派前 `git worktree add /tmp/gh-<工項號> <base>`，每個 agent 一個
- prompt 必須包含四件事：工作目錄、**不要碰主樹**、不要 push、**不要對你的
  worktree 執行 `git worktree remove`**
- **資源限制必須寫進 prompt**：不要啟動 docker compose、不要 `docker compose stop`、
  不要讀 `target/surefire-reports`（只讀 `mvn verify` 的 `Tests run: N`）
- **線上實測不能並行** —— 8080 埠只有一個，由 PM 序列進行

## 3. 建議的下一輪優先序（請先和使用者確認）

2026-09-30 收尾時的剩餘項目依建議順序：

| 順序 | 工項 | 估時 | 備註 |
|---|---|---|---|
| 1 | **#90** 申請人端沒有代發標示 | 0.2d | 純前端。`MyApplications.vue` 從未渲染後端早就回傳的 `onBehalf` 布林值 —— 員工看到自己沒送過的單，畫面一樣不解釋 |
| 2 | **#93** `firstTaskCandidateGroups` 送 array 會 500 | 0.3d | **需先裁決哪一邊是 canonical**：spec §9.1.3 示範的是 JSON array，但後端現在只接受字串（逗號分隔）。2026-09-30 刻意未修 |
| 3 | **#91** `flowable:assignee="${var}"` 求值為空白 | 0.5d | **管理員部署即可觸發，不經任何 API**。#89 讓它看得見但沒擋。兩個方向：① lint 部署前警告 ② 指派層視為未指定（② 會碰到「誰來決定」的政策問題） |
| 4 | **#92** 未知的人 assignee 不告警 | 0.5d | listener 無法不查組織系統就判斷字串是不是人，而那會**在簽核交易內打 HTTP** —— 「寫入端必須驗證」的又一個實例 |
| 5 | **#67 流程層** `ProcessCompletedListener` 的 webhook | 0.5d | 斷線 (A) 的第四個實例。接上只需在同一個 resolver 加「讀 `<process>` 上的 `flowable:webhooks`」 |
| 6 | **#82** 前端接權限碼 | 1.5d | **架構決定，要先問使用者**（權限碼的資料來源） |
| 7 | **#68** R-20 剩餘 docs 收尾 | 0.2d | |
| 8 | **#70** Boot 4 + Flowable 8 | 22d | 前置調查完成。⚠️ **升級時 `ExtensionElementPreservationTest` 會是第一個紅的**（它釘住 Flowable 保留未知 extension element 的行為，Flowable 8 可能改） |

## 4. 每個工作項的流程（已驗證 2026-09-30）

1. 先在原始碼和**執行中的服務上確認問題真的存在**，不要只照文件描述動手。
2. 修改，並在註解裡寫清楚**為什麼這樣做、為什麼不用另一種做法**（繁中，
   註解密度要比照 `ProcessAccessGuard`、`ExternalActorGuard`、
   `UnreachableTaskListener` 的水準 —— 那些註解密度高是刻意的）。
3. 寫測試，然後**把缺陷放回去確認測試會紅**（非空驗證，注意第 2.1 節的陷阱）。
4. `docker compose stop bpm-core` → `cd bpm-core && mvn verify` → 全綠。
   ⚠️ **只信任 `mvn verify` 輸出的 `Tests run: N`**。
5. 重建容器 → `./scripts/seed-data.sh` → `./scripts/acceptance-test.sh`
   （必須 PASS 7 / FAIL 0）。
6. **對執行中的服務用真實 JWT 做 curl 實測**（`./scripts/dev-token.sh <user>`）。
   每個狀態碼斷言都要**同時驗資料沒被改**。
7. commit，訊息繁中寫清楚**為什麼**。**不要 `git add -A`**。
8. 更新 backlog 狀態欄。

### 4.1 跑 Maven 前必須設定

```bash
export DOCKER_HOST=unix://$HOME/.orbstack/run/docker.sock
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
```

### 4.2 環境陷阱

- **啟動服務必須帶 dev overlay**，少了 mailhog 會讓 health 失敗
- 容器重建 + seed 完**立刻**跑 `acceptance-test.sh` 會 7 個全失敗，**再跑一次就過**
  —— 這是 seed 的時序問題，不是改動引入，但每個人都會踩到
- zsh：`echo ===` 會被展開改用 `echo '---'`；`--include=*.java` 加引號；
  `cp` 被 alias 成互動模式用 `command cp`
- 刪稽核紀錄需先 `SET QUOTED_IDENTIFIER ON`，否則觸發器會擋
- `IntegrationTestBase.SERVLET_PORT` 是 `static final` 的**單一 port**
  → 整個測試套件只容得下一個 Spring context。任何測試類別加
  `@TestPropertySource`／`@Import`／`@DynamicPropertySource` 都會讓
  **其他**測試整組紅掉。**這是既有測試基礎設施的限制，下一個人一定會再踩。**

## 5. 可以重用、**不要重寫**的元件

```
ProcessAccessGuard        案件層：requireReadAccess / requireParticipant /
                          requireSelf / requireTaskReadAccess /
                          requireTaskParticipant / processInstanceIdOfTask
TaskHolderGuard           任務層：requireHolder / isHolder
CandidateGroupMembership  呼叫端所屬候選群組（部門 ∪ 權限碼 ∪ 非 ROLE_ 的 authority）
ProcessInvolvementService 「我參與的案件」的反向批次查詢（避免 N+1）
ExternalActorGuard        外部 API 的身分形狀驗證（空白／system: 前綴／組織查詢）
ExternalSystemPolicy      白名單四態分類（Kind.UNRESTRICTED = 留空＝不限制）
WebhookConfigResolver     從 BPMN 的 extensionElements 讀 webhook 設定
```

**為什麼是這麼多份而不是一份**：授權類元件的類別註解記錄了理由 ——
兩處各自維護同一條規則時，只要有人改了其中一處，就會出現「附件會拒絕而
variables 放行」那種組合型式的差異，而那種差異**比沒有檢查更難察覺**。

## 6. 授權的三組政策（已定案，不要自創第四組）

| 規則 | 未通過時 |
|---|---|
| 讀個案內容 | `requireReadAccess`（關係人 ∪ `audit:log:read`），**旁路必留痕** → 404 |
| 動作任務 | `TaskHolderGuard.requireHolder`（**無**稽核旁路）→ 404 |
| 寫個案 | `requireParticipant`（**無**稽核旁路）→ 404 |
| 身分欄位 | `requireSelf`：帶了與自己不符的值 → 明確 400，省略 → 呼叫者 |
| 候選群組 | 呼叫端帶 `candidateGroups` → 一律 400（集合的自稱） |

**為什麼 404 而非 403**：403 會確認「這個物件存在」，對可枚舉的 id 等於把
枚舉管道留著。

**為什麼稽核只認 `audit:log:read`、不認 `ROLE_ADMIN`**：稽核紀錄含全公司薪資
與簽核意見。**讀稽核要用 `./scripts/dev-token.sh dir001`**，不要用 `admin001 admin`。

## 7. 使用者的硬規則（不可違反）

- **不要擅自升到 Spring Boot 4.x**；升級必須和 Flowable 8 同步，照計畫的 Stage 5 走。
- `FlowableConfig.setBeans()` 的 map **必須包含 `notifyTaskListener`**。
- 跟稽核有關的交易一律 `@Transactional("auditTransactionManager")`；
  **三個 DataSource 的注入點一律加 `@Qualifier`**。
- 寫入類 controller 方法必須有**限定管理器**的 `@Transactional**。
  ⚠️ 交易內**不可**在 save 之後為了組回應而修改 entity；
  ⚠️ 交易內**不可** catch 約束違規後重試（要用 REQUIRES_NEW）。
- **不要修改 `docs/history/**`**。
- **不要新增全域 `@RestControllerAdvice`**（#69 已決定不用）。
- **政策性決定（授權範圍、fail-open 或 fail-closed、誰能看什麼、狀態碼語意）
  一律先問使用者。**
- 分階段 commit，訊息以繁中為主，寫清楚**為什麼**；用明確路徑 staging，
  **不要 `git add -A`**；commit 前後各檢查一次 `git diff --cached --stat`
  和 `git show --stat HEAD`。
- 還原測試用 `command cp` 備份／還原，**絕不用 `git checkout -- src/main/java`**。

### 使用者已做過的決策（不要再問）

- 認證：只驗 JWT（`sub`；有 `roles` claim 時優先），不簽發、不做 OIDC；
  server 之間走信任閘道。
- auditor 由權限碼 `audit:log:read` 決定；附件與流程變數、表單資料的稽核旁路
  **只認這個權限碼、不認 `ROLE_ADMIN`**、唯讀、每次留痕。
- 稽核 fail-closed；沒人看得到的任務在 commit 後只告警、不硬擋；
  權限持有人全部不在時派給第一位的代理人。
- R-20：initiator 一律 `system:<id>`；代員工發起用 `onBehalfOf`，
  需要外部系統開啟 `allowOnBehalfOf`（預設關閉）。
- 表單設計用**權限碼 `bpm:form:design`**；**這裡保留 ADMIN 旁路、稽核那裡移除**。
- `isParticipant` **不擴充候選群組**（維持現狀）。
- #68c 代發案件的補件關卡派給誰 → **新增 `applicantResolver` bean**。
- #66 與 #71 的 body／query 身分參數：**明確 400 拒絕冒用，不靜默忽略**。
- 2026-09-30 的九項裁決：見 `docs/handoff/2026-09-30-round3-handoff.md` 第 8.1 節。
- 分支：維持 `feature/* → main` 直接合併。

## 8. 回報格式

每完成一項就向使用者回報：做了什麼、為什麼、驗證結果（測試數、負向控制組結果、
**線上實測**）、沒做到的部分，然後問是否繼續下一項。

**誠實回報很重要。** 2026-09-30 有 subagent 主動揭露三件事：
自己測試的無效之處（#68d 的 severity 測試根本證明不了它要證明的事）、
上一輪 agent 修漏的地方（#88 指出 #89 的 listener 漏了空字串形狀）、
以及自己違反了禁用指令（用 `git checkout` 丟掉 4 個自己改過的檔）。
**這些回報的價值高於測試全綠。**

## 9. ⚠️ 上線前必讀

`docs/handoff/2026-09-30-round3-handoff.md` 第 0.0 節：

> **必須在真實權限中心指派權限碼 `bpm:external:revision`**，否則外部系統發起的
> 案件連「退回」都會 500。

`ApplicantResolver` 的第三段（initiator 是 `system:<id>` 時派給誰）查這個權限碼，
查不到就**拋例外**（刻意不回 null —— 回 null 會建立一個沒有 assignee 也沒有候選人的
任務，正是換一種方式製造同一個靜默卡死）。

dev 環境的 `MockPermController` 已有 `dir001` 的 fixture，所以**測試全綠掩蓋了這個
前置條件**。症狀是「外部系統發起的單在**使用者按下退回的當下**報錯」。
