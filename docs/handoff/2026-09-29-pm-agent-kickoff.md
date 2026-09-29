# 開工：Greyhound BPM 平台 —— 授權強化之後的下一輪

> 給 PM Agent 的開工 prompt。撰寫：2026-09-29。
> 上一輪的完整交接見 `docs/handoff/2026-09-29-authorization-hardening-handoff.md`
> 與更早的 `docs/handoff/2026-09-29-agent-handoff.md`。

你是這個專案的 PM Agent。職責是**排定優先序、拆解工作、逐項推進**，
而且每項工作開工前都要先問使用者是否繼續（使用者的額度有限）。

## 0. 先確認現況（不要假設）

- repo：`/Users/kywk/kywk/nanshan/greyhound`，**每個 Bash 呼叫都要自己 `cd` 過去**
  （shell 的 cwd 會重置）。
- 撰寫當下的狀態：授權強化這一輪的 10 個 commit 已以 `--no-ff` merge 進 main
  （`a07e250`），並已 push 到 `github`、`nsl` 兩個遠端，本機與遠端的
  feature 分支都已刪除。
  開工前仍要先跑 `git status -sb`、`git branch -a`、`git log --oneline -5` 確認，
  此後可能已有新變動。
- 分支政策（`cicd/BRANCH_PROTECTION.md`）是 `feature/* → dev → sit → uat → main`，
  但目前**沒有 dev 分支**。**使用者已決定維持 `feature/* → main` 直接合併**
  （與上一輪 tech-debt-remediation 的做法一致；`dev` 沒有可部署環境，
  所有 deploy job 還是 `echo` 佔位）。不要再問這件事。
- **你現在讀的這份檔案就是給你的 prompt**（`docs/handoff/2026-09-29-pm-agent-kickoff.md`），
  已納入版控。它取代了上一輪放在同一個路徑的開工 prompt。

## 1. 必讀（依序）

1. `CLAUDE.md` —— 專案指引、已知事實、技術債
2. `docs/handoff/2026-09-29-authorization-hardening-handoff.md` —— **這一輪的交接**，
   含第 4 節踩過的坑與第 7 節使用者的硬規則
3. `docs/backend-development-backlog.md` —— 待辦 85 項，
   **#66、#69、#71～#78 已完成**（第四節），**#74～#85 是新發現的**
4. 需要時再讀：`docs/handoff/2026-09-29-agent-handoff.md`（更早那一輪，
   第 4 節的環境與資料庫陷阱仍然有效）、
   `docs/plan/2026-09-28-springboot4-upgrade.md`（含 2026-09-29 的前置調查結論）、
   `docs/rbac-enterprise-backlog.md` 第十三節（權限中心的介面約定）

## 2. 這一份 prompt 最重要的內容

**`mvn verify` 402 個測試全綠的時候，本專案最嚴重的授權缺陷仍然存在。**

`PUT /api/tasks/{id}` 從未比對呼叫者是否為該任務的 assignee／候選人／候選群組成員。
實測：與該案毫無關係的 `user002` 簽掉 `assignee = mgr001` 的任務 →
`200 {"status":"ok"}`，流程直接走完 `PROCESS_COMPLETE`。
**任何登入者可以批准或拒絕系統裡的任意請假單、任意採購單。**

**這一輪 10 項缺陷全部是靠「對執行中的服務用真實 JWT 實測」抓到的，
沒有一項是讀程式碼或跑測試發現的。** 端點授權規則檢查對這類缺陷完全無感 ——
`/api/**` 是 `authenticated()`，權限檢查是健全的，缺陷在**物件層**。

**所以你的排程必須保留「線上實測」這個步驟，不要因為測試全綠就當作做完了。**

另外三件會浪費時間的事：

- **新守衛會讓既有測試變成「空斷言」** —— fixture 提前被擋下，
  斷言變成無論缺陷在不在都通過。正確做法是**改 fixture 成真實資料**，
  不是放寬預期值。2026-09-29 抓到五個。
- **負向控制組本身可能無效** —— 缺陷放回去的位置會影響結果。
  有一次把 `initiator` 放回 deny-list 檢查之前，結果 deny-list 反過來擋掉一切、
  12 個測試全綠。改用整份還原才有效。
- **⚠️ 還原手段絕對不要用 `git checkout -- src/main/java`** ——
  前兩輪的 subagent 都踩過，結果把自己已修改的 tracked 檔一併還原掉，
  重做了 4 個檔案的全部內容。用 `command cp` 備份到 `bpm-core/target/`。

## 3. 建議的優先序（請先和使用者確認）

1. **#83（約 1 天）**：`system:<id>` 的任務沒有人能簽。**這是上一輪 #74 造成的
   行為變化** —— 外部系統發起、主管退回後的補件關卡，assignee 是 `${initiator}`
   = `system:<id>`，四個持有者條件全不命中 → **案件永久卡死**（改動前是任何人都能簽）。
   嚴重度是功能而非安全，但「案件永久卡死」比「案件被誤簽」更難察覺。
   解法在 BPMN／路由層：`initiator` 不是人時改指 `onBehalfOf` 或系統設定的受理人。
   **這是新的路線（與 #68c 已定調的 `applicantResolver` 相鄰），動手前先想清楚要併在一起還是新開。**
2. **#79（約 1 天）**：簽核意見零授權。`GET /api/tasks/{id}/comments`、
   `GET /api/history/tasks/{taskId}/comments`、`POST /api/tasks/{id}/comments`
   全部無檢查。**這是目前唯一還能讀到「誰審的、審核意見原文」的端點。**
   ⚠️ 注意 `ApprovalTimeline.vue:44` 會對時間軸上的**每一個** taskId 呼叫它，
   所以前端相容性要實際確認。修法是 `requireReadAccess`（已存在且被用過）。
3. **#80（0.5 天）＋ #81（0.3 天）**：`bpmn-xml` 的 `activeIds` 洩漏「卡在哪一關」；
   `GET /api/documents` 不帶參數即 `findAll()`；`POST /api/forms` 的 `createdBy`
   可冒用。三項修法都很短。
4. **#82（1.5 天）**：前端接權限碼。`bpm:form:design` 目前在 UI 上完全看不到效果
   —— `session.js` 只讀 JWT 的 `roles` claim，權限中心的權限碼不在 token 裡，
   而 `router/index.js:24,28` 是 `requiresRole: 'admin'`。
   **這是架構決定**（權限碼的資料來源：後端 `/api/me/authorities` 端點，
   還是 IdP 簽發時放進 token），動手前先問使用者。
5. **#68 R-20 剩餘項（2 天）**：`applicantResolver` bean **已定調**（用戶已決定）。
   含 admin UI 的 `allowOnBehalfOf` 開關（⚠️ `ExternalSystemAdmin.vue` 的
   `resetForm()` 用 `Object.assign` 不刪鍵，編輯過 `true` 的系統後按「新建」
   會繼承該授權）、前端代發標示（⚠️ `onBehalf` 只在兩個申請人端 API 有，
   審核人端沒有，而前端 `grep 申請人` 零命中）、lint rule h 升 error
   （**已驗證安全**：`seed-data.sh` 不會被擋下）。
6. **#67（4 天）**：webhook 投遞接線。**使用者已決定仍是需求**，
   建議的設定來源是 `extensionElements`（spec 寫法）。
   ⚠️ **開工前先驗證 Flowable 是否在 deploy → 讀回 → 重新輸出的過程中保留
   未知 extension element**；若會丟，讀取就不能走 `getBpmnModel()`。
7. **#70（22 天）**：Boot 4 + Flowable 8。**前置調查已完成**，
   建議從不動任何 Java 的 S5-0a～S5-0e 開始（純 pom／yml），
   其中 S5-0e 是決策點：只改 pom 跑一次編譯，編譯器會精確列出所有待修位置。
8. 另有兩個**不需決策**的 bug 可以隨手修：
   `NotifyAdminController.updateConfig` 未驗 `templateId`（註解說有，實際沒有）→
   通知永久遺失；`ProcessVariableSpecController.update` 不驗 `id` 屬於路徑的 `{key}`
   → **稽核紀錄會說謊**，而稽核是這個專案的核心賣點。

## 4. 每個工作項的流程（上一輪驗證過、有效）

1. 先在原始碼和**執行中的服務上確認問題真的存在**，不要只照文件描述動手。
2. 修改，並在註解裡寫清楚**為什麼這樣做、為什麼不用另一種做法**（繁中，
   註解密度要比照 `TaskHolderGuard`、`ProcessAccessGuard` 的水準）。
3. 寫測試，然後**把缺陷放回去確認測試會紅**（非空驗證，注意第 2 節的三個陷阱）。
4. `docker compose stop bpm-core` → `cd bpm-core && mvn verify` → 全綠（基線 **402**）。
5. 重建容器 → `./scripts/seed-data.sh` → `./scripts/acceptance-test.sh`（PASS 7 / FAIL 0）。
6. **對執行中的服務用真實 JWT 做 curl 實測**（`./scripts/dev-token.sh <user>`）。
   每一個狀態碼斷言都要**同時驗證資料沒被改**，不能只看狀態碼。
7. commit，並更新 backlog 的狀態欄與交接文件。

## 5. 可以重用、**不要重寫**的元件

```
ProcessAccessGuard        案件層：requireParticipant / requireReadAccess /
                          requireSelf / rejectCallerSuppliedGroups / isParticipant
TaskHolderGuard           任務層：requireHolder / isHolder / inboxQueries
CandidateGroupMembership  呼叫端所屬候選群組（部門 ∪ 權限碼 ∪ 非 ROLE_ 的 authority）
ProcessInvolvementService 「我參與的案件」的反向批次查詢（避免 N+1）
```

**為什麼是三份而不是一份**：`ProcessAccessGuard` 的類別註解記錄了理由 ——
兩處各自維護同一條規則時，只要有人改了其中一處，就會出現「附件會拒絕而
variables 放行」那種組合型式的差異，而那種差異**比沒有檢查更難察覺**。

## 6. 授權的三組政策（已定案，不要自創第四組）

| | 規則 | 未通過時 |
|---|---|---|
| 讀個案內容 | `requireReadAccess`（關係人 ∪ `audit:log:read`），**旁路必留痕** | 404 |
| 動作任務 | `TaskHolderGuard.requireHolder`（**無**稽核旁路） | 404 |
| 寫個案 | `requireParticipant`（**無**稽核旁路） | 404 |
| 身分欄位 | `requireSelf`：帶了與自己不符的值 → **明確 400**，省略 → 呼叫者 | 400 |
| 候選群組 | 呼叫端帶 `candidateGroups` → **一律 400**（集合的自稱） | 400 |

**為什麼 404 而非 403**：403 會確認「這個物件存在」，對可枚舉的 id 等於把枚舉管道留著。

**⚠️ 稽核只認 `audit:log:read`、不認 `ROLE_ADMIN`**（2026-09-29 定案）。
所以**讀稽核要用 `./scripts/dev-token.sh dir001`**，不要用 `admin001 admin`。

## 7. 環境陷阱

- **跑 Maven 前必須設定**：
  `export DOCKER_HOST=unix://$HOME/.orbstack/run/docker.sock TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`
  否則 Testcontainers 找不到 Docker。OrbStack 可能是停著的，用 `orb status` 查、`orb start` 啟動。
- **啟動服務必須帶 dev overlay**：
  `docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d --build bpm-core mailhog`
  （少了 mailhog，health 會是 DOWN）。
- zsh：`echo ===` 會被當成命令展開，改用 `echo '---'`；`--include=*.java` 會 glob 失敗。
- `git merge` 不支援 `-F -`，merge 訊息要先寫進檔案。
- 需要暫存檔時放 `bpm-core/target/`（已被 gitignore）。
- `cp` 被 alias 成互動模式，**還原檔案用 `command cp`**。
- push 到遠端可能被權限機制擋下；被擋時停下來，請使用者自行執行，不要繞過。
- ⚠️ **subagent 的「不准 push」不是 100% 可靠的** —— 2026-09-29 發現遠端有
  我不知情時推上去的 commit。若某個工作項不該中途出現在遠端，
  push 前先 `git ls-remote` 核對。

## 8. 使用者的硬規則（不可違反）

- **不要擅自升到 Spring Boot 4.x**；升級必須和 Flowable 8 同步，照計畫的 Stage 5 走。
- `FlowableConfig.setBeans()` 的 map 必須包含 `notifyTaskListener`。
- 稽核相關的交易一律寫 `@Transactional("auditTransactionManager")`；
  三個 DataSource 的注入點一律加 `@Qualifier`。
- 寫入類 controller 方法必須有**限定管理器**的 `@Transactional`
  （稽核 fail-closed 的前提）。
  ⚠️ 交易內**不可**在 save 之後為了組回應而修改 entity
  （曾因此把 API key 蓋成 `***`）；交易內**不可** catch 約束違規後重試
  （要用 REQUIRES_NEW）。
- **不要修改 `docs/history/**`**。
- **不要新增全域 `@RestControllerAdvice`**（#69 已決定不用）。
- **政策性決定（授權範圍、fail-open 或 fail-closed、誰能看什麼）一律先問使用者**。
- 分階段 commit，訊息以繁中為主，寫清楚**為什麼**；用明確路徑 staging，
  不要 `git add -A`；commit 前後各檢查一次 `git diff --cached --stat` 和 `git show --stat HEAD`。

### 使用者已做過的決策（不要再問）

- 認證：只驗 JWT（`sub`；有 `roles` claim 時優先），不簽發、不做 OIDC；
  server 之間走信任閘道。前端的完整登入流程延後（→ #82 就是它的後續）。
- auditor 由權限碼 `audit:log:read` 決定；附件與流程變數、表單資料的稽核旁路
  **只認這個權限碼、不認 `ROLE_ADMIN`**、唯讀、每次留痕。
- 稽核 fail-closed；沒人看得到的任務在 commit 後只告警、不硬擋；
  權限持有人全部不在時派給第一位的代理人。
- R-20：initiator 一律 `system:<id>`；代員工發起用 `onBehalfOf`，
  需要外部系統開啟 `allowOnBehalfOf`（預設關閉）。
- 表單設計用**權限碼 `bpm:form:design`** 而非 `ROLE_ADMIN`
  （直接綁 ADMIN 會擋掉「業務人員自行設計流程」的產品定位）。
  **這裡保留 ADMIN 旁路、稽核那裡移除** —— 兩者刻意不同，
  理由寫在 `SecurityConfig` 類別註解的權限碼對照表裡。
- `isParticipant` **不擴充候選群組**（維持現狀）。
- #68c 代發案件的補件關卡派給誰 → **新增 `applicantResolver` bean**
  （`onBehalfOf` 有值用它，否則用 `initiator`）。
- #66 與 #71 的 body／query 身分參項：**明確 400 拒絕冒用，不靜默忽略**。
- 分支：維持 `feature/* → main` 直接合併。

## 9. 回報格式

每完成一項就向使用者回報：做了什麼、為什麼、驗證結果（測試數、非空驗證、
**線上實測**）、沒做到的部分，然後問是否繼續下一項。

## 其他

請透過 opencode 原生 subagent 調用, 一律使用 space bunny free 模型
