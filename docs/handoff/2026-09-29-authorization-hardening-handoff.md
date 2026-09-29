# 接手文件 — 授權強化（#66、#71～#78）

**寫給下一個接手的實作 agent。** 撰寫時間 2026-09-29。
上一輪（`feature/tech-debt-remediation`）的交接見 `docs/handoff/2026-09-29-agent-handoff.md`；
**那份仍然有效**，本檔只補充 2026-09-29 這一輪的新事實與新教訓。

---

## 0. 先讀這段：這一輪最重要的一個方法論結論

**`mvn verify` 402 個測試全綠的時候，最嚴重的授權缺陷仍然存在。**

`PUT /api/tasks/{id}` 從未比對呼叫者是否為該任務的 assignee／候選人／候選群組成員。
實測：與該案毫無關係的 `user002` 簽掉 `assignee = mgr001` 的任務 → `200 {"status":"ok"}`，
稽核留下 `TASK_APPROVE | operatorId = 'user002'`，流程直接走完 `PROCESS_COMPLETE`。
**任何登入者可以批准或拒絕系統裡的任意請假單、任意採購單。**

而這條路徑正是前端實際在用的表單寫入路徑（`DocumentDetail.vue` → `PUT /api/tasks/{taskId}`
帶 variables）—— 也就是說在那一個時間點，**拿表單資料的權限比簽核的權限還大**。

**這一輪的每一項缺陷都是靠「對執行中的服務用真實 JWT 實測」抓到的，沒有一項是讀程式碼或
跑測試發現的。** 端點授權規則檢查（`AuthenticationTest`）對這類缺陷完全無感 ——
`/api/**` 是 `authenticated()`，權限檢查是健全的，缺陷在**物件層**。

所以：**接手後的紀律是線上實測不可省略。** 見第 6 節。

---

## 1. 系統現況

| | |
|---|---|
| 後端 | Spring Boot **3.5.16** + Flowable **7.2.0**（**未升級**），單一模組 `bpm-core` |
| 前端 | Vue 3.4 + Vite 5 + Element Plus |
| DB | MSSQL 2022，**三個資料庫**：`bpm_core_db`／`bpm_audit_db`／`bpm_form_db` |
| 其他 | RabbitMQ、Redis、MailHog。Docker 是 **OrbStack** |
| 測試 | 後端 **402** 個（Testcontainers：真實 MSSQL／RabbitMQ／Redis），前端 **59** 個（Vitest） |
| 部署 | **尚未部署，只有本機開發**。R-18 的部署阻斷項仍是 R-01 完成後的待確認狀態 |

```bash
cd /Users/kywk/kywk/nanshan/greyhound
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d --build bpm-core mailhog
./scripts/seed-data.sh        # 部署 2 支 BPMN + 4 個表單定義
./scripts/acceptance-test.sh  # 應為 PASS 7 / FAIL 0
cd bpm-core && mvn verify      # 402 個
```

⚠️ **啟動必須帶 dev overlay**，少了 mailhog 會讓 health 失敗。
⚠️ 跑 `mvn verify` 前必須 `docker compose stop bpm-core`（理由見第 6.2 節）。

---

## 2. 這一輪新增的授權元件（下一輪應該重用，不要重寫）

```
com.bpm.core.security
  ProcessAccessGuard   案件層：requireParticipant / requireReadAccess / requireSelf /
                       rejectCallerSuppliedGroups / isParticipant / initiatorOf / stateOf
  TaskHolderGuard      任務層：requireHolder / isHolder / inboxQueries
                       （assignee ∪ owner ∪ candidateUser ∪ 候選群組）
com.bpm.core.service
  CandidateGroupMembership   呼叫端所屬候選群組（部門 ∪ 權限碼 ∪ 非 ROLE_ 的 authority）
  ProcessInvolvementService  「我參與的案件」的反向批次查詢（避免 N+1）
```

**為什麼是三份而不是一份**：`ProcessAccessGuard` 的類別註解記錄了理由 ——
兩處各自維護同一條規則時，只要有人改了其中一處，就會出現「附件會拒絕而
variables 放行」那種組合型式的差異，而那種差異**比沒有檢查更難察覺**，
因為兩邊單獨看起來都合理。

**不要在 controller 內重寫一份判斷。** 下一輪若要加新的物件層檢查，
先看這三個元件有沒有現成的規則可用。

---

## 3. 授權的三組政策（已定案，不要自創第四組）

| | 規則 | 未通過時 |
|---|---|---|
| 讀個案內容 | `requireReadAccess`（關係人 ∪ `audit:log:read`），**旁路必留痕** | 404 |
| 動作任務 | `TaskHolderGuard.requireHolder`（**無**稽核旁路） | 404 |
| 寫個案 | `requireParticipant`（**無**稽核旁路） | 404 |
| 身分欄位 | `requireSelf`：帶了與自己不符的值 → **明確 400**，省略 → 呼叫者 | 400 |
| 候選群組 | 呼叫端帶 `candidateGroups` → **一律 400**（集合的自稱） | 400 |

**為什麼是 404 而非 403**：403 會確認「這個物件存在」，對可枚舉的 id 等於把枚舉管道留著。

**為什麼稽核旁路只認 `audit:log:read`、不認 `ROLE_ADMIN`**：稽核紀錄含全公司薪資與簽核意見，
而 `AuditEvent.detail` 會帶整包流程變數。`ProcessAccessGuard` 早就拒絕 ADMIN 讀同一批資料，
URL 層若放行就是側門 —— 2026-09-29 已把 `SecurityConfig` 的 ADMIN 旁路移除讓兩處一致。

**⚠️ 授權之後讀稽核要用 `./scripts/dev-token.sh dir001`**，不要用 `admin001 admin`，
也不要用 `dir001 admin`（`roles` claim 會完全取代權限中心查詢）。

---

## 4. 這一輪踩過的坑 —— 不要重犯

### 4.1 「測試在測缺陷行為」反覆出現

**三支 countersign 測試原本在測缺陷。** `CountersignTcA01Test`、`CountersignTamperTest`、
`TaskActionHardeningTest` 全部沿用 `TestGatewayMockMvcCustomizer` 的預設身分 `user001`，
而 `user001` 是**申請人、不是持有者**（該測試的 assignee 是 `mgr001`）。
它們綠是因為缺陷讓任何登入者都能加簽。

### 4.2 新守衛會讓既有測試變成「空斷言」

新增的守衛讓原本用「不存在的 pid」或「未宣告身分」的 fixture **提前被擋下**，
於是「資料沒有被改動」這個斷言**在缺陷完全存在時也成立** —— 測試對缺陷變成無感。

這一輪抓到五個（`FormMassAssignmentTest`、`FormRevisionTest`、`AuditFailClosedTest` ×2、
countersign 三支）。**正確做法是改 fixture 成真實資料，不是放寬預期值。**
更進階的做法是加「非空斷言」證明請求真的送到了 controller。

### 4.3 負向控制組本身可能無效

把缺陷放回去時，**控制組的形狀會影響結果**。這一輪有一次：把 `initiator` 放回
deny-list 檢查**之前**，結果 deny-list 反過來擋掉一切、12 個測試全綠 —— 等於沒驗到。
改用**整份還原**才得到有效結果。

### 4.4 ⚠️ 還原手段：**絕對不要用 `git checkout -- src/main/java`**

前兩輪的 subagent 都踩過：用 git checkout 當負向控制的還原手段，結果把自己**已修改的
tracked 檔**一併還原掉，重做了 4 個檔案的全部內容（最終 `diff -r` 確認一致、測試全綠，
但那是僥倖）。

**用 `command cp` 備份到 `bpm-core/target/`，驗完再 `command cp` 還原。**
（`cp -f` 被 alias 成互動模式會**靜默不覆寫**。）

### 4.5 兩個 subagent 同時工作 → 用隔離 worktree 做負向控制

兩個 agent 共用 working tree 時，一方在 `mvn verify` 期間改檔會汙染對方的建置。
解法：從 HEAD 建一個獨立 worktree 做負向控制，**主樹全程不動**。

### 4.6 前端的「沒有任何權限」偵測不到後端改了什麼

`bpm-frontend/src/services/http.js:65-66` 有 `detail` fallback，但 **#73 之前
`server.error.include-message` 是 `never`**，所以 `ResponseStatusException` 精心寫的
理由只存在於伺服器端日誌，呼叫端只看到 `"error":"Bad Request"`。已修
（`DeliberateErrorMessageAttributes`）。

---

## 5. 已知限制（**下一輪要處理的**）

| # | 事項 | 說明 |
|---|---|---|
| **#83** | `system:<id>` 的任務沒有人能簽 | **#74 造成的行為變化**。外部系統發起 → 主管退回 → 補件關卡 assignee 是 `${initiator}` = `system:<id>` → 四個持有者條件全不命中 → **案件永久卡死**。改動前是「任何人都能簽」。根本解法在 BPMN／路由層：`initiator` 不是人時改指 `onBehalfOf` 或系統設定的受理人 |
| **#82** | 前端沒有權限碼的概念 | `session.js` 只讀 JWT 的 `roles` claim，權限中心的權限碼**不在 token 裡**。`router/index.js:24,28` 是 `requiresRole: 'admin'` → 只持有 `bpm:form:design` 的業務人員後端放行但前端擋掉。**架構決定** |
| **#79** | 簽核意見零授權 | `GET /api/tasks/{id}/comments`、`GET /api/history/tasks/{taskId}/comments`、`POST /api/tasks/{id}/comments` 全無檢查。**目前唯一還能讀到「誰審的、審核意見原文」的端點** |
| #80 | `bpmn-xml` 與 `documents` 零檢查 | `bpmn-xml` 的 `activeIds` 洩漏「這張單卡在哪一關」；`GET /api/documents` 不帶參數即 `findAll()` |
| #81 | `POST /api/forms` 的 `createdBy` | `FormService.create()` 完全不碰它（只用 `@CallerId` 餵稽核），對照 `createNextDraft` 有 `setCreatedBy` —— 兩端不一致 |
| — | 被委派任務送 `complete` 是裸 500 | `TaskHelper.completeTask` 對 `delegationState = PENDING` 直接拋 `FlowableException`。**既有引擎行為**，委派能走完的動作是 `resolve`（守衛已放行 owner 與 assignee 兩者） |
| — | `isParticipant` 不涵蓋候選群組 | Flowable 的 `taskInvolvedUser` 只比對 `USER_ID_`。**使用者已明確決定維持現狀**。影響：群組審核人看不到案件層檢視（`/involved`、`/variables`、`/form-data`、`/history`） |
| — | `ProcessController` 與 `TaskController` 各有一份 `PROTECTED_VARIABLES` | 兩處必須維持同一份內容。合併屬重構，尚未做 |

---

## 6. 每一個工作項的流程（這一輪驗證過）

1. **先在原始碼與執行中的服務上確認問題真的存在。** 不要只照文件描述動手。
2. 修，**在註解裡寫清楚「為什麼這樣做」與「為什麼不用另一種做法」**。
   這個 repo 的註解密度很高（見 `TaskHolderGuard`、`ProcessAccessGuard`、
   `CandidateGroupMembership`、`ProcessController` 的 `singleResult()` 註解），
   請比照那個水準，**繁中**書寫。
3. 寫測試，然後**把缺陷放回去確認測試會紅**（見 4.3、4.4 的陷阱）。
4. `docker compose stop bpm-core` → `cd bpm-core && mvn verify` → 全綠。
5. 重建容器 → `seed-data.sh` → `acceptance-test.sh`（PASS 7 / FAIL 0）。
6. **對執行中的服務用真實 JWT 做 curl 實測**（`./scripts/dev-token.sh <user>`）。
7. commit，更新 backlog 狀態欄。

### 6.1 線上實測抓到、自動化測試看不到的例子

- **error dispatch**：MockMvc 不做 error dispatch，`ResponseStatusException` 變成的狀態碼在
  測試裡與線上不同。`ErrorDispatchTest:22-23` 的註解記錄過：曾有 265 個測試就這樣
  全綠地放過一個「所有錯誤在線上都變 403」的缺陷。**新的狀態碼斷言要走真實 HTTP。**
- **交易內 entity 被 flush**：`GET /api/process-instances` 曾回 107 件，
  而測試裡的 mock 回空。
- **「回 404 但資料已被改掉」**：每個狀態碼斷言都必須**同時驗證資料沒變**，
  不能只看狀態碼。
- **守衛插在流程中段時，狀態碼差異會被既有測試綁死**：
  `TaskActionHardeningTest.cannotHijackByBareAssignee` 斷言 400，
  所以守衛必須排在 action 形狀檢查**之後**。

### 6.2 跑 Maven 前必須設定

```bash
export DOCKER_HOST=unix://$HOME/.orbstack/run/docker.sock
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
```

否則 Testcontainers 找不到 Docker。OrbStack 可能是停著的：`orb status` 查、`orb start` 啟動。

**跑完整測試套件前先 `docker compose stop bpm-core`** ——
2026-08-28 曾因 `application-test.yml` 寫了 `localhost:${local.server.port:8080}`
而讓整合測試**打到 docker 容器而不是自己**；停掉容器才能證明測試套件是封閉的。
（已修，但紀律保留。）

### 6.3 zsh 與 git 的陷阱

| 坑 | 對策 |
|---|---|
| `echo ===` 被當命令展開 | 用 `echo '---'` |
| `--include=*.java` glob 失敗 | 加引號 |
| `ls`／`cp`／`mv` 被 alias | 用 `find`／`command cp` |
| `git merge` 不支援 `-F -` | merge 訊息先寫進檔案 |
| 暫存檔放 `bpm-core/target/` | 已 gitignore |
| ⚠️ **subagent 的「不准 push」不是 100% 可靠的** | 2026-09-29 發現 nsl 遠端已有我不知情時推上去的 commit。若某個工作項不該中途出現在遠端，push 前先 `git ls-remote` 核對 |

---

## 7. 使用者的硬規則（不可違反）

- **不要擅自升到 Spring Boot 4.x**；升級必須和 Flowable 8 同步，照計畫的 Stage 5 走。
  **前置調查已完成**（見 `docs/plan/2026-09-28-springboot4-upgrade.md` 的
  「前置調查結論」節），有兩個被推翻的判斷與 15 處事實修正。
- `FlowableConfig.setBeans()` 的 map **必須包含 `notifyTaskListener`**。
- 跟稽核有關的交易一律 `@Transactional("auditTransactionManager")`；
  **三個 DataSource 的注入點一律加 `@Qualifier`**。
- 寫入類 controller 方法必須有**限定管理器**的 `@Transactional`（稽核 fail-closed 的前提）。
  ⚠️ 交易內**不可**在 save 之後為了組回應而修改 entity；
  ⚠️ 交易內**不可** catch 約束違規後重試（要用 REQUIRES_NEW）。
- **不要修改 `docs/history/**`**。
- **不要新增全域 `@RestControllerAdvice`**（#69 已決定不用）。
- **政策性決定（授權範圍、fail-open 或 fail-closed、誰能看什麼）一律先問使用者**。
- 分階段 commit，訊息以繁中為主，寫清楚**為什麼**；用明確路徑 staging，
  不要 `git add -A`；commit 前後各檢查一次 `git diff --cached --stat` 和 `git show --stat HEAD`。

### 使用者已做過的決策（不要再問）

- 認證：只驗 JWT（`sub`；有 `roles` claim 時優先），不簽發、不做 OIDC。
  前端的完整登入流程延後處理（→ #82 就是這件事的後續）。
- auditor 由權限碼 `audit:log:read` 決定；**不認 `ROLE_ADMIN`**（2026-09-29 定案）。
- 稽核 fail-closed；沒人看得到的任務在 commit 後只告警、不硬擋；
  權限持有人全部不在時派給第一位的代理人。
- R-20：initiator 一律 `system:<id>`；代員工發起用 `onBehalfOf`，
  需要外部系統開啟 `allowOnBehalfOf`（預設關閉）。
- 表單設計用**權限碼 `bpm:form:design`** 而非 `ROLE_ADMIN`，
  因為直接綁 ADMIN 會擋掉「業務人員自行設計流程」的產品定位。
  **這裡保留 ADMIN 旁路、稽核那裡移除** —— 兩者刻意不同，理由寫在 `SecurityConfig` 類別註解。
- `isParticipant` 不擴充候選群組（維持現狀）。
- 分支：維持 `feature/* → main` 直接合併（`dev` 不存在且沒有可部署環境）。

---

## 8. 剩餘待辦（依建議順序）

| 順序 | 工項 | 估時 | 備註 |
|---|---|---|---|
| 1 | **#83** `system:<id>` 任務沒有人能簽 | 1d | #74 造成的行為變化。解法在 BPMN／路由層 |
| 2 | **#79** 簽核意見零授權（三個端點） | 1d | 目前唯一還能讀簽核意見原文的端點 |
| 3 | **#82** 前端接權限碼 | 1.5d | 架構決定，要先問使用者 |
| 4 | #80 `bpmn-xml` 與 `documents` | 0.5d | 修法只是 `requireReadAccess`／`requireSelf` |
| 5 | #81 `POST /api/forms` 的 `createdBy` | 0.3d | 與 #66／#72 同型 |
| 6 | #68 R-20 剩餘項 | 2d | `applicantResolver` bean **已定調**；admin UI 開關有個 `resetForm()` 繼承授權的 bug |
| 7 | #67 webhook 投遞接線 | 4d | 設定來源建議 `extensionElements`（**開工前先驗證 Flowable 是否保留未知 extension element**） |
| 8 | #70 Boot 4 + Flowable 8 | 22d | 前置調查完成，從純 pom 的 S5-0a～S5-0e 開始 |

另有兩個盤點時確認、**不需決策**的 bug：
`NotifyAdminController.updateConfig` 未驗 `templateId`（註解說有，實際沒有）→
通知永久遺失；`ProcessVariableSpecController.update` 不驗 `id` 屬於路徑的 `{key}` →
**稽核紀錄會說謊**。

---

## 9. 現況一句話

`main` = `a07e250`，已 push 到 `github` 與 `nsl`，feature 分支已刪。
後端 402 測試全綠（**在容器停止狀態下跑的**）、前端 59 全綠、
`acceptance-test.sh` PASS 7 / FAIL 0。工作樹乾淨。
**尚未部署。**
