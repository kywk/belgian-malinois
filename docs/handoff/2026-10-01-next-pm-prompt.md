# 開工 prompt — Greyhound BPM 平台，2026-10-01 第三輪收尾後

> 給下一位 PM Agent。撰寫：2026-10-01。
> **你現在讀的這份檔案就是給你的 prompt**（`docs/handoff/2026-10-01-next-pm-prompt.md`），
> 已納入版控。
> 上一輪的交接見 `docs/handoff/2026-10-01-round4-handoff.md`（**最新**）、
> `docs/handoff/2026-09-30-round3-handoff.md`、`docs/handoff/2026-09-29-authorization-hardening-handoff.md`、
> `docs/handoff/2026-09-29-agent-handoff.md`。

你是這個專案的 PM Agent。職責是**排定優先序、拆解工作、逐項推進**，
而且每項工作開工前都要先問使用者是否繼續（使用者的額度有限）。

## 0. 先確認現況（不要假設）

- repo：`/Users/kywk/kywk/nanshan/greyhound`，**每個 Bash 呼叫都要自己 `cd` 過去**。
  ⚠️ **變數也會重置，不只是 cwd** —— PM 上一輪因此把一個 401 誤讀成授權缺陷。
- 開工前先跑 `git status -sb`、`git branch`、`git log --oneline -5`、`git worktree list`。
- **2026-10-01 收尾時的狀態**：`main` = `316a35f`，已含第三輪全部成果
  （#90 #92 #93 #94 #91 方向A #82 + 兩個收斂後效應修正），
  後端 **693** 測試全綠、前端 **144** 全綠、`acceptance-test` PASS 7 / FAIL 0。
  **尚未部署。`main` 比 `github/main` 多 25 個 commit，未 push。所有 worktree 已清除。**
- 分支政策是 `feature/* → main` 直接合併（`dev` 不存在且沒有可部署環境，
  所有 deploy job 還是 `echo` 佔位）。**不要再問這件事。**

## 1. 必讀（依序）

1. `docs/handoff/2026-10-01-round4-handoff.md` — **最新交接**，含這一輪踩過的坑
2. `docs/handoff/2026-09-30-round3-handoff.md` — 授權三組政策、使用者硬規則、
   並行 subagent 的標準流程（**仍然有效**）
3. `docs/backend-development-backlog.md` — **94 項工項，已完成 50 項**，
   22 項部分完成、22 項未開始（見統計表）
4. 需要時再讀：`docs/plan/2026-09-28-springboot4-upgrade.md`、
   `docs/rbac-enterprise-backlog.md` 第十三節、`docs/bpm-platform-spec.md`

## 2. 這一份 prompt 最重要的兩件事

### 2.1 🔴 授權類缺陷只有「對執行中的服務做真實實測」才抓得到 —— 而 PM 自己也會錯

2026-09-30 那一輪，`mvn verify` 全綠時「整批取代第二次呼叫必定 500」仍然存在。
2026-10-01 這一輪同樣：**每個狀態碼斷言都走真實 HTTP、每個被拒形狀都同時驗資料沒被改。**

但這一輪更重要的是 **PM 下了三個判斷、被 subagent 各自推翻，三次都是 agent 對**：

| PM 的判斷 | 真相 |
|---|---|
| 「P1-4 要求候選人白名單」 | P1-4 原文的修法是「改顯式 switch」，#77 已做；**沒有任何文件要求候選人白名單** |
| 「element-plus 不驗證 `type`」 | 它**自己註冊了 validator**，PM 只查了 Vue 3 核心 |
| 「`required=true` 不會在執行期為空」 | `validateVariables` **只判 `containsKey`**，送空白值就通過 |

**三次有兩次是 PM 基於「自己下給 agent 的指示」**，agent 必須**回去查原文**才能推翻。

**所以你的做法是**：
1. 下指示前**自己去讀一次原文** —— 本輪三次失敗都是「引用二手敘述、沒讀原文」
2. agent 回報時**獨立複驗**，而且要準備好**自己被推翻**
3. **證據優先於推論** —— 本輪有一次分歧最後是部署真實 BPMN + 查資料庫裁決的

⚠️ **反面也要記**：agent 的質疑也可能是錯的（有一次 agent 的位元碼推論被實測推翻）。
**agent 做對的地方是標記「我懷疑、我未修改、我無法複驗」而不是直接去「修正」** ——
那正是你該要的行為。

### 2.2 並行 subagent 必須用獨立 worktree，且派工前就要劃好檔案邊界

2026-09-30 有兩個 agent 共用主樹，**實際發生資料損失**。
流程見 `docs/handoff/2026-09-30-round3-handoff.md` 第 10 節，
2026-10-01 再次驗證有效（5 個 agent 分三批，主樹零污染）。

**2026-10-01 新增的關鍵做法 —— 派工前把「誰動哪個檔」寫死**：

| 情況 | 做法 |
|---|---|
| A 要改某個類別、B 需要用同一個 | **指示 B 只許「呼叫」不准「修改」**；若認為必須修改就停下回報 → 合併零衝突 |
| 檔案天然不重疊 | 仍在 prompt 明寫邊界 |
| `docs/backend-development-backlog.md` | **指示所有 agent 都不准動**，PM 統一更新（上一輪唯一的合併衝突就在這裡） |

**prompt 必須包含四件事**：工作目錄（每個 Bash 呼叫自己 `cd`）、不要碰主樹、
不要 push、**不要對你的 worktree 執行 `git worktree remove`**。

**資源限制必須寫進 prompt**：
- **不要啟動 docker compose、不要 `docker compose stop`、不要 curl 線上實測**（PM 統一做）
- **不要讀 `target/surefire-reports`**（只讀 `mvn verify` 的 `Tests run: N`）
- 🔴 **只跑自己的測試類別**（`mvn -Dtest='Xxx' -DfailIfNoTests=false verify`），
  **完整套件由 PM 在合併時統一跑一次** —— 這樣既拿到並行又沒有 OOM 風險，
  而且 PM 拿到的結果比 agent 回報的更有力
- **遇到 `Unresolved compilation problem` 立刻改用 `mvn clean verify`**，
  **不要去改那些測試原始碼 —— 它們沒有錯**（見 round4 handoff 第 1.1 節）

**線上實測不能並行** —— 8080 只有一個，由 PM 序列進行。

## 3. 建議的下一輪優先序（請先和使用者確認）

| 順序 | 工項 | 估時 | 備註 |
|---|---|---|---|
| 1 | **#91 方向 B** 指派層把空白 assignee 視為未指定 | 0.5d | ⚠️ **卡在 flush 順序風險**。`UnreachableTaskListener` 是全域 engine event listener，跑在 task 落地**之前**；task listener 跑在落地**之後**才安全，但改成 per-BPMN listener 會讓規則只對掛了 listener 的 BPMN 生效（= 同一條規則兩套形狀）。**兩難需要先解** |
| 2 | **#91 漏報 ①** `validateVariables` 拒絕 `required=true` 的空白值 | 0.3d | 送 `{"dept":"  "}` + `${dept}` 仍會靜默卡死。**動手前先在線上實測「會不會打破既有整合」** |
| 3 | **#91 漏報 ②** 混合式 `${a}-${b}` | 0.2d | 現行規則只認「整個運算式就是 `${var}`」 |
| 4 | **測試缺口** `flowable:assignee=" "` 沒有測試釘住 | 0.1d | javadoc 聲稱 `""` 和 `" "` 兩者，但測試只覆蓋 `""`。2026-10-01 是手動部署 BPMN 實測才知道現況 |
| 5 | **#67 流程層** `ProcessCompletedListener` 的 webhook | 0.5d | 斷線 A 的第四個實例。需先裁決投遞設定來源 |
| 6 | **#67 前端** `modeling.updateProperties` 的測試缺口 | 0.3d | |
| 7 | **#25** webhook payload 缺口 | 0.5d | `task.timeout` 事件、候選人、`operatorName`、`comment` |
| 8 | **#68** R-20 剩餘 docs 收尾 | 0.2d | |
| 9 | **`dev-token.sh` 註解**與實際權限不一致 | 0.1d | 它教人用 `dir001` 讀稽核，但改動前 dir001 根本進不了 `/audit-log` |
| 10 | **#70** Boot 4 + Flowable 8 | 22d | 前置調查完成。⚠️ **升級時 `ExtensionElementPreservationTest` 會是第一個紅的** |

**為什麼把 #91 的三項排最前**：它們是同一個缺陷的三個出口，而且**方向 B 的兩難
不解決就永遠做不了**。第 4 項（測試缺口）雖然最小，但它是**唯一一个
「文件說了、測試沒證明」的形狀** —— 而 2026-10-01 正好證明這種落差會讓人
（包括 agent）做出錯誤的技術判斷。

## 4. 每個工作項的流程（已驗證兩輪）

1. **先在原始碼與執行中的服務上確認問題真的存在**，不要只照文件描述動手。
2. 修改，並在註解裡寫清楚**為什麼這樣做、為什麼不用另一種做法**（繁中，
   註解密度要比照 `ProcessAccessGuard`、`ExternalActorGuard`、
   `UnreachableTaskListener` 的水準 —— 那些註解密度高是刻意的）。
3. 寫測試，然後**把缺陷放回去確認測試會紅**（非空驗證）。
4. `docker compose stop bpm-core` → `cd bpm-core && mvn clean verify` → 全綠。
   ⚠️ **只信任 `mvn verify` 輸出的 `Tests run: N`**，且**必須用 `clean`**。
5. 重建容器 → `./scripts/seed-data.sh` → `./scripts/acceptance-test.sh`
   （必須 PASS 7 / FAIL 0）。
6. **對執行中的服務用真實 JWT 做 curl 實測**（`./scripts/dev-token.sh <user>`）。
   每個狀態碼斷言都要**同時驗資料沒被改**。
7. commit，訊息繁中寫清楚**為什麼**。**不要 `git add -A`**。
8. 更新 backlog 狀態欄。
9. 合併後 `command rm -rf` 掉 agent 留在 `/tmp` 的備份與日誌。

### 4.1 跑 Maven 前必須設定

```bash
export DOCKER_HOST=unix://$HOME/.orbstack/run/docker.sock
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
```

### 4.2 環境陷阱（完整版見 round4 handoff 第 1 節）

- 🔴 **`mvn verify` 失敗但 `mvn test-compile` 成功** → `mvn clean verify`。
  根因是 `bpm-core/` 有未納入版控的 Eclipse 專案檔，`target/test-classes` 裡是
  Eclipse 編譯的壞 class 檔。**照錯誤訊息去改測試原始碼會完全走錯方向。**
- ⚠️ **前端基線是 144**（不是 93、不是 87）。數字只有實際跑出來的為準。
- ⚠️ **shell 變數也會重置**（不只是 cwd）
- ⚠️ **啟動服務必須帶 dev overlay**，少了 mailhog 會讓 health 失敗
- ⚠️ 容器重建 + seed 完**立刻**跑 `acceptance-test.sh` 會 7 個全失敗，**再跑一次就過**
- ⚠️ **外部 API 實測的 `ipWhitelist` 要含 `192.168.117.1`**（docker bridge），
  `allowedActions` 要含 `start_process`，否則會先撞 403 而誤判
- ⚠️ **dev 庫的資料清不掉**（沒有部署刪除端點、外鍵擋手工刪）。要部署臨時 BPMN
  實測引擎行為時，檔名加 `probe-` 前綴並記錄下來
- zsh：`echo ===` 會被展開改用 `echo '---'`；`--include=*.java` 加引號；
  `cp` 被 alias 成互動模式用 `command cp`；`timeout` 指令不存在
- 刪稽核紀錄需先 `SET QUOTED_IDENTIFIER ON`
- `IntegrationTestBase.SERVLET_PORT` 是 `static final` 的**單一 port**
  → 整個測試套件只容得下一個 Spring context。任何測試類別加
  `@TestPropertySource`／`@Import`／`@DynamicPropertySource` 都會讓
  **其他**測試整組紅掉。**這是既有測試基礎設施的限制，下一個人一定會再踩。**

## 5. 可以重用、**不要重寫**的元件

```
ExternalActorGuard         「指派給誰必須是組織系統認識的人」的唯一一份實作
  requireKnownPerson(field, userId[, action])   五個呼叫點共用
  requireAllowedCandidateGroups(sys, List)      候選群組白名單 + trim/去重/去空白
MeController                GET /api/me/permissions（只回呼叫者自己的，**無 ?userId=**）
ProcessAccessGuard          案件層：requireReadAccess / requireParticipant /
                            requireSelf / requireTaskReadAccess / processInstanceIdOfTask
TaskHolderGuard             任務層：requireHolder / isHolder
CandidateGroupMembership    呼叫端所屬候選群組（部門 ∪ 權限碼 ∪ 非 ROLE_ 的 authority）
ProcessInvolvementService   「我參與的案件」的反向批次查詢（避免 N+1）
OnBehalfOfLookup            代發標示（審核人端字串、申請人端布林值兩種形狀）
ExternalSystemPolicy        白名單四態分類（Kind.UNRESTRICTED = 留空＝不限制）
WebhookConfigResolver       從 BPMN 的 extensionElements 讀 webhook 設定
```

**為什麼是這麼多份而不是一份**：授權類元件的類別註解記錄了理由 ——
兩處各自維護同一條規則時，只要有人改了其中一處，就會出現「附件會拒絕而
variables 放行」那種組合型式的差異，而那種差異**比沒有檢查更難察覺**。

⚠️ **2026-10-01 新增的教訓**：把規則收斂成一份之後，**其他呼叫點的落差才浮現**。
本輪收斂 `requireKnownPerson` 時因此發現兩個後效應（訊息把外部 API 語境寫死、
加簽的 `trim()` 讓同一規則有兩種答案）—— **兩個都是 PM 造成的**。
**收斂之前先問「其他呼叫點有沒有形狀／語境的落差」。**

## 6. 授權的三組政策（已定案，不要自創第四組）

| 規則 | 未通過時 |
|---|---|
| 讀個案內容 | `requireReadAccess`（關係人 ∪ `audit:log:read`），**旁路必留痕** → 404 |
| 動作任務 | `TaskHolderGuard.requireHolder`（**無**稽核旁路）→ 404 |
| 寫個案 | `requireParticipant`（**無**稽核旁路）→ 404 |
| 身分欄位 | `requireSelf`：帶了與自己不符的值 → 明確 400，省略 → 呼叫者 |
| 候選群組 | 呼叫端帶 `candidateGroups` → 一律 400（集合的自稱） |
| 指派對象 | `requireKnownPerson`：空白／`system:` 前綴 → 400（不打網路）；其餘問組織系統，「查無此人」→ 400、「故障」→ **503** |

**為什麼 404 而非 403**：403 會確認「這個物件存在」，對可枚舉的 id 等於把
枚舉管道留著。

**為什麼稽核只認 `audit:log:read`、不認 `ROLE_ADMIN`**：稽核紀錄含全公司薪資
與簽核意見。**讀稽核要用 `./scripts/dev-token.sh dir001`**，不要用 `admin001 admin`。

⚠️ **「哪條規則接受 `ROLE_ADMIN`」是每一條規則自己的政策決定**，
`SecurityConfig` 類別註解有對照表（稽核**不**接受、表單設計**接受**）。
**前端也必須複製這個差異** —— `admin001` 在 `/api/me/permissions` 拿到
`admin=true` 但 `permissions=[]`，前端據此隱藏稽核選單。

## 7. 使用者的硬規則（不可違反）

- **不要擅自升到 Spring Boot 4.x**；升級必須和 Flowable 8 同步，照計畫的 Stage 5 走。
- `FlowableConfig.setBeans()` 的 map **必須包含 `notifyTaskListener`**。
- 跟稽核有關的交易一律 `@Transactional("auditTransactionManager")`；
  **三個 DataSource 的注入點一律加 `@Qualifier`**。
- 寫入類 controller 方法必須有**限定管理器**的 `@Transactional`。
  ⚠️ 交易內**不可**在 save 之後為了組回應而修改 entity；
  ⚠️ 交易內**不可** catch 約束違規後重試（要用 REQUIRES_NEW）。
- **不要修改 `docs/history/**`**。
- **不要新增全域 `@RestControllerAdvice`**（#69 已決定不用）。
- **政策性決定（授權範圍、fail-open 或 fail-closed、誰能看什麼、狀態碼語意）
  一律先問使用者。**
- 分階段 commit，訊息以繁中為主，寫清楚**為什麼**；用明確路徑 staging，
  **不要 `git add -A`**；commit 前後各檢查一次 `git diff --cached --stat`
  和 `git show --stat HEAD`。
- 還原測試用 `command cp` 備份／還原，**絕不用 `git checkout -- src/main/java`**
  （已有**三個** agent 因此丟掉自己改過的 tracked 檔）。

### 使用者已做過的決策（**不要再問**）

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
- #66 與 #71 的 body／query 身分參數：**明確 400 拒絕冒用，不靜默忽略**。
- **2026-09-30 的九項裁決**：見 `docs/handoff/2026-09-30-round3-handoff.md` 第 8.1 節。
- **2026-10-01 的七項裁決**：見 `docs/handoff/2026-10-01-round4-handoff.md` 第 8 節。
- 分支：維持 `feature/* → main` 直接合併。

## 8. 回報格式

每完成一項就向使用者回報：做了什麼、為什麼、驗證結果（測試數、負向控制組結果、
**線上實測**）、沒做到的部分，然後問是否繼續下一項。

**誠實回報很重要。** 2026-10-01 有四個 subagent 主動揭露自己不足，其中三項
直接改變了結果：推翻 PM 對 P1-4 的理解、指出 PM 裁決的前提有破口、
指出「加簽那 3 條紅的測試不是新缺陷」。**這些回報的價值高於測試全綠。**

## 9. ⚠️ 上線前必讀

`docs/handoff/2026-10-01-round4-handoff.md` 第 6.1 節：

> **必須在真實權限中心指派權限碼 `bpm:external:revision`**，否則外部系統發起的
> 案件連「退回」都會 500。

`ApplicantResolver` 的第三段（initiator 是 `system:<id>` 時派給誰）查這個權限碼，
查不到就**拋例外**（刻意不回 null —— 回 null 會建立一個沒有 assignee 也沒有候選人的
任務，正是換一種方式製造同一個靜默卡死）。

dev 環境的 `MockPermController` 已有 `dir001` 的 fixture，所以**測試全綠掩蓋了這個
前置條件**。症狀是「外部系統發起的單在**使用者按下退回的當下**報錯」。

另外 `bpm:form:design` 與 `audit:log:read` 也必須在真實權限中心指派給正確的人員，
否則 #82 修好的前端會忠實地顯示「你沒有這個功能」。
