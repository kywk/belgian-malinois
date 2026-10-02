# 接手文件 — 2026-10-01 第四輪完成（#91 整個工項）

**寫給下一個接手的實作 agent。** 撰寫時間 2026-10-01（晚間）。
上一輪交接見 `docs/handoff/2026-10-01-round4-handoff.md`（**仍然有效**）、
`docs/handoff/2026-09-30-round3-handoff.md`。下一位 PM 的 prompt 見
`docs/handoff/2026-10-01-next-pm-prompt.md`（⚠️ 本輪已使其中「#91」相關項目全部完成，
見下方第 1 節）。

本檔只寫這一輪的新事實。授權三組政策、使用者硬規則、授權元件清單**都沒有變**。

---

## 0. 現況

| | |
|---|---|
| 後端 | Spring Boot **3.5.16** + Flowable **7.2.0**（未升級），單一模組 `bpm-core` |
| 前端 | Vue 3.4 + Vite 5 + Element Plus |
| 測試 | 後端 **727** 個、前端 **144** 個（僅認 `mvn clean verify` 的 `Tests run: N`） |
| `main` | `dd70b34`，比 `github/main` 多 **8** 個 commit，**未 push** |
| worktree | 全部已清除 |
| 容器 | dev 環境（`bpm-core` 8080 + mssql/rabbitmq/redis/mailhog），`acceptance-test.sh` **PASS 7 / FAIL 0** |

---

## 1. 本輪完成：`#91` 整個工項（五件事 + 一條舊測試修正）

`#91`（`flowable:assignee="${var}"` 求值為空白 → 案件沒有人看得到）原本是 🟡，
本輪做成 ✅。

| 子項 | 做法 | commit 型態 |
|---|---|---|
| **方向 A（規則 `optional-assignee`）** | 規則 k：指派欄位用 required=false 的已宣告變數 → warning。**⚠️ 這是「復原」** | 見第 2 節 |
| **方向 B（指派層正規化）** | 新增 `BlankAssigneeNormalizingInterceptor`（全域 `CreateUserTaskInterceptor`），把建立任務時 `isBlank()` 的 assignee 收斂成 `null` | `30bd600` |
| **漏報①（required=true 的空白值）** | `ExternalApiController.validateVariables`：required=true 收到 null／空白字串 → 400（數字 0／false／空集合**不擋**） | `177bfcc` |
| **漏報②（混合式 `${a}-${b}`）** | 規則 k 對 `flowable:assignee` 放寬成「參照到任一 required=false 變數」；候選欄位維持純參照 | `6a4c894`／`c187779` |
| **測試缺口（`flowable:assignee=" "`）** | 由方向 B 的測試 `whitespaceLiteralAssigneeIsNull` 釘住（Flowable 的 `createExpression` 會 trim，本來就是 null，非缺陷路徑） | 隨方向 B |
| **#89 舊斷言修正** | `UnreachableBlankAssigneeAlertTest.blankAssigneeFromExpressionIsAlerted` 釘的是方向 B 改掉的舊行為 → 改為方向 B 的迴歸釘 | `064028a` |

**完整套件 727**＝703（#91 方向 A）＋10（漏報①）＋9（漏報②）＋5（方向 B）。
`acceptance-test.sh` PASS 7 / FAIL 0。

### 1.1 ⚠️ 方向 B 的「兩難」是**假兩難**（本輪最重要的技術結論）

round4 handoff 第 6 節把方向 B 描述成兩難：全域 engine event listener「跑在 task 落地**之前**」
vs per-BPMN task listener（同一條規則兩套形狀）。**兩條都不是唯一選項。**

PM 用 `javap -p -c` 讀 Flowable 7.2.0 `UserTaskActivityBehavior.execute(DelegateExecution)`，
呼叫順序是：

```
TaskService.createTask()
  → CreateUserTaskInterceptor.beforeCreateUserTask(...)
  → TaskHelper.insertTask(...)            ← 任務落地
  → handleAssignments(...)                ← 在此把空白字串寫進 assignee
  → CreateUserTaskInterceptor.afterCreateUserTask(...)  ← 全域、且在寫入之後
```

所以 **`ProcessEngineConfigurationImpl.setCreateUserTaskInterceptor(...)`**（在 `FlowableConfig`
的 `EngineConfigurationConfigurer` 設定）是唯一同時滿足的切入點：

- **全域**：對所有 UserTask 生效，含使用者自行部署的 BPMN → 不是兩套形狀。
- **在 handleAssignments 之後**：是既有 row 的 UPDATE，不是 #86 那種 INSERT/DELETE 排序風險。

為什麼不用其他三個（已寫在 `BlankAssigneeNormalizingInterceptor` 的類別註解）：
engine event listener（落地前、且職責是觀察不是改資料）、per-BPMN task listener（兩套形狀）、
`ActivityBehaviorFactory` 覆寫 `handleAssignments`（要複製 Flowable 的指派邏輯，漂移即新缺陷）。

### 1.2 ⚠️ 方向 A 是「復原」，不是「新做」——而且這是本輪最該記的方法論教訓

round4 handoff 第 4 節與 backlog #91 列都寫「方向 A 完成」，但 **`main` 上沒有規則
`optional-assignee`**，`git reflog` 也**沒有** feature/91 的 merge 記錄。真正的實作只存在於
**三個 unreachable commit**：

```
6682051 refactor(lint): 變數規格整次 lint 只查一次，並攜帶 required
b053b77 feat(lint): 規則 k —— 指派欄位不可直接使用 required=false 的變數（#91 方向 A）
0bd4dac test(lint): 規則 k 的 19 條測試（基底 e8572fd）
```

本輪用 `feature/91a-recover` cherry-pick 復原、零衝突、10/10 綠。

**教訓（給下一位 PM）**：`mvn verify` 綠只證明「這一刻的樹」；
`git show --stat` 只證明「那個 commit」。**要證明「某工項的成果在 main 上」，唯一的證據是
`git log --oneline <branch>..main` 或一個真的 merge commit。** 收工前應逐一核對。
（附帶更正：上一輪記的「693＝661＋#91 的 10＋#94 的 22」把 #91 的 10 算進去了；
693 其實是**不含** #91 的主樹基線。真正的基線加 #91 是 703。）

### 1.3 ⚠️ agent 的「懷疑但無法複驗」被 PM 複驗後**有一處是錯的**（但結論對）

漏報② 的 agent 把「為什麼候選欄位不放寬」建立在「`extractCandidates` 把 `"DEP01,"` 拆成
`["DEP01",""]`」上，並主動標記「我沒有自己反組譯複驗」。

PM 用 `javap` 讀 `extractCandidates`＋`java` 實測 `String.split("[\\s]*,[\\s]*")`，發現
**Java 的 `split` 預設丟掉尾端空字串**：`"DEP01,"` → `["DEP01"]`（不是 `["DEP01",""]`）、
`",hr"` → `["","hr"]`、`","` → `[]`。

→ **結論對**（其餘有效項仍在，放寬候選欄位會製造假警告），**證據錯**。
已修正註解並補記殘餘邊界（`"${a},${b}"` 全可選且全空 → `[]` → 完全沒有候選人；不擴大規則，
因為放寬成「任一可選參照就警告」會對最常見的 `"hr,${dept}"` 大量誤報）。

**這個 pattern 值得記**：agent 把「我懷疑、我未修改、我無法複驗」標記出來是**對的行為**
（它沒有把未證實的推論寫死），而 PM 的獨立複驗把它修正了。**兩邊都做了該做的事。**

---

## 2. 本輪的三個方法論發現

1. **「已完成的工項」可能有兩種假象**：文件說完成（假象一）、`mvn verify` 綠（假象二）。
   兩者都不能證明成果在 `main`。見 1.2。
2. **handoff 描述的「兩難」可能只是「沒想到第三條路」**。描述兩難時要附上「查過的切入點清單」，
   下一個人才知道要往哪裡找第三條。
3. **agent 標記的「無法複驗」是資產**。三次裡有兩次（#92 的 P1-4、#91 的 split）是
   agent 回去查原文推翻 PM；一次是 PM 複驗推翻 agent。**兩邊都要保留「被推翻」的空間。**

---

## 3. 已知殘餘（無害，但要知道）

| 殘餘 | 說明 |
|---|---|
| **process-instance `participant` link（`userId="  "`）** | 方向 B 的 interceptor 只把 **task** 的 assignee 收成 null。原始的 `handleAssignments` 會用空白值在 **process instance** 上建一個 `userId="  "` 的 `participant` identity link，本輪**沒有清它**。**本專案沒有任何程式以 userId 查 process-instance identity link**（`TaskHolderGuard`／`UnreachableTaskListener` 用 `getIdentityLinksForTask`），所以它**不影響任何查詢**。若日後要清，在 interceptor 內用 `RuntimeService.deleteUserIdentityLink(pid, 該空白值, "participant")` 即可。 |
| **`assignee-blocks-candidates` 這個 reason 只剩 API 寫入端可達** | 方向 B 之後，BPMN 運算式路徑不再產生空白 assignee。剩下的是 `TaskService.setAssignee(taskId, "  ")`（`UnreachableBlankAssigneeAlertTest` 的兩條 API 測試就是它的證據）。若日後也把 API 納入正規化，這個 reason 會變成死碼。 |
| **dev 庫探測殘留** | `probe-91b`／`probe-91c`（流程定義＋變數規格），與既有的 `probe-blank-literal` 同類。另有**已停用**的外部系統 `t91req`（`DELETE /api/admin/external-systems/{id}` 是**軟停用**：`ENABLED_=0`）。都無害，清不掉（沒有硬刪端點）。 |

---

## 4. ⚠️ 新環境風險（下一個人一定會踩）

### 4.1 `git status --short` 出現 `MM` —— index 被外部動作改動

本輪真的發生：PM commit 完 backlog（19:27）後，**index 在 19:30 自己被 staged 了一個
「把剛 commit 的註解刪回去」的變更**（`git status --short` = `MM docs/...`）——
**工作樹內容是對的、只有 index 被動**。時間點與 `git worktree add` 重疊，本機有
`fsmonitor` daemon 與 Eclipse 專案檔殘留。

**這正是 round4 handoff 第 11 節那個坑的同型現象**（`git commit` 提交整個 index）。

**正確做法**：
```bash
git status --short          # 第一欄是 index 相對 HEAD；看到 M／MM 就先查
git diff --cached --name-only   # 逐行確認清單
# 若不是你要提交的 → git restore --staged <path> 還原 index（不要 commit）
```
PM 本輪用 `git restore --staged` 還原、沒有 commit 那個刪除。**每次 commit 前都做一次。**

### 4.2 `POST /api/deployments` 的 `name` 缺副檔名 → 靜默不產生流程定義

`name=probe-91b`（沒有 `.bpmn20.xml`）→ Flowable 不把它當 BPMN 解析 →
回 **200 與 deploymentId**，但 `ACT_RE_PROCDEF` **零筆**，之後啟動會 404「流程定義不存在」。
用**上傳檔名**（含副檔名）或**省略 `name`** 就正常。`seed-data.sh` 走的是正常路徑。
（非本輪工項，未修；值得考慮是否要一個「部署後沒有產生任何流程定義就報錯」的防護。）

### 4.3 沿用（複習）

- `mvn verify` 失敗但 `test-compile` 成功 → `mvn clean verify`（Eclipse ECJ 殘留）。
- 跑 Maven 前：`export DOCKER_HOST=unix://$HOME/.orbstack/run/docker.sock`、
  `export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`。
- 啟動服務要 dev overlay。容器重建 + seed 完**立刻**跑 acceptance-test 可能全失敗，**再跑一次**就過。
- `IntegrationTestBase.SERVLET_PORT` 是 static final 單一 port → 一個測試類別加
  `@TestPropertySource`／`@Import`／`@DynamicPropertySource` 會讓**其他**測試整組紅。
- 並行 subagent 必須各自獨立 worktree，`docs/backend-development-backlog.md` 只准 PM 動。
- 外部 API 實測的 `ipWhitelist` 要含 `192.168.117.1`，`allowedActions` 要含 `start_process`。

---

## 5. 現況一句話

**2026-10-01 第四輪**：`#91` 從 🟡 做成 ✅ —— 方向 A **復原**（它上一輪從未被合併）、
方向 B（`CreateUserTaskInterceptor` 解掉假兩難）、漏報①②、測試缺口，另修一條 #89 的舊斷言。
後端 **727** 全綠、前端 **144**、`acceptance-test` PASS 7 / FAIL 0、線上實測全過
（DB 的 `ASSIGNEE_` 真的是 NULL、外部 API 空白必填值真的 400 且零副作用）。
**未 push。** worktree 已清除。統計：✅ 50 → **51**、🟡 22 → **21**。

> **後續補充（同日）：** #67 流程層 webhook 亦已完成（`<process>` 的 `flowable:webhooks`），
> 後端 **749** 全綠、`acceptance-test` PASS 7 / FAIL 0。`#67` 仍 🟡（剩 #25 payload 與前端測試缺口）。
> dev 庫另增探測殘留 `probe-67p`／`probe-67p-noconfig`。

---

## 6. 建議的下一輪優先序（先和使用者確認）

> ⚠️ **最新的優先序與「已裁決事項」以 `docs/handoff/2026-10-02-next-pm-prompt.md` 為準**（本表只是本輪快照）。
> 2026-10-02 已補的裁決：`#67` 前端**要**加 process 層 UI；`#25` payload **只補非敏感欄位**（不送 variables/comment/operatorName）；
> `#91` 殘餘空白 participant link **留著**；`deployments name` 缺副檔名**不加防護**。

| 順序 | 工項 | 估時 | 備註 |
|---|---|---|---|
| ~~1~~ | ✅ **#67 流程層**（2026-10-01 同日補完） | 0.5d | 設定來源＝`<process>` 的 `flowable:webhooks`（與節點層共用同一份解析）。沒有設定就不發訊息；事件對應 `all`／`process.completed`／`complete`／省略。線上實測通過（listener 排入佇列、consumer 收到 `__webhookUrl`、loopback 被拒）。見 backlog #67 列 |
| 2 | **#67 前端** `modeling.updateProperties` 測試缺口 | 0.3d | |
| 3 | **#25** webhook payload 缺口 | 0.5d | `task.timeout` 事件、候選人、`operatorName`、`comment` |
| 4 | **`dev-token.sh` 註解** | 0.1d | 它教人用 `dir001` 讀稽核，但 `mintDevToken` 不簽 `roles` claim |
| 5 | **#68** R-20 剩餘 docs 收尾 | 0.2d | |
| 6 | **#70** Boot 4 + Flowable 8 | 22d | ⚠️ 升級時 `ExtensionElementPreservationTest` 會第一個紅 |

⚠️ **上線前必讀**（round4 handoff 第 6.1 節，仍然有效）：真實權限中心必須指派
`bpm:external:revision`、`bpm:form:design`、`audit:log:read`。
