# 接手文件 — 2026-10-01 第三輪完成（#90 #92 #93 #94 #91A #82）

**寫給下一個接手的實作 agent。** 撰寫時間 2026-10-01。
上一輪的交接見 `docs/handoff/2026-09-30-round3-handoff.md`（**仍然有效**）、
`docs/handoff/2026-09-29-authorization-hardening-handoff.md`、
`docs/handoff/2026-09-29-agent-handoff.md`。
下一位 PM 的 prompt 見 `docs/handoff/2026-10-01-next-pm-prompt.md`。

本檔**只寫 2026-10-01 這一輪的新事實**。授權三組政策、使用者硬規則、
授權元件清單都沒有變，見上一輪第 5、7 節，不要重新推導。

---

## 0. 現況

| | |
|---|---|
| 後端 | Spring Boot **3.5.16** + Flowable **7.2.0**（**未升級**），單一模組 `bpm-core` |
| 前端 | Vue 3.4 + Vite 5 + Element Plus |
| DB | MSSQL 2022，三個資料庫（`bpm_core_db`／`bpm_audit_db`／`bpm_form_db`） |
| 測試 | 後端 **693** 個、前端 **144** 個（Testcontainers：真實 MSSQL／RabbitMQ／Redis） |
| 分支 | `main` = `316a35f`，比 `github/main` 多 **25** 個 commit，**未 push** |
| 部署 | **尚未部署，只有本機開發** |
| worktree | **全部已清除**（`git worktree list` 只有主樹） |

```bash
cd /Users/kywk/kywk/nanshan/greyhound
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d --build bpm-core mailhog
./scripts/seed-data.sh        # 部署 2 支 BPMN + 4 個表單定義
./scripts/acceptance-test.sh  # 應為 PASS 7 / FAIL 0
docker compose stop bpm-core  # ⚠️ 跑 mvn verify 前必須
cd bpm-core && mvn clean verify   # 693 個
```

⚠️ **必須用 `mvn clean verify`，不是 `mvn verify`** —— 理由見第 1.1 節。
⚠️ 啟動必須帶 dev overlay，少了 mailhog 會讓 health 失敗。

---

## 1. 這一輪踩過的坑 —— 不要重犯

### 1.1 🔴 `mvn verify` 失敗但 `mvn test-compile` 成功

**症狀**：`mvn verify` 報 **53 errors**，訊息是

```
Unresolved compilation problem: TestGatewayMockMvcCustomizer cannot be resolved to a variable
```

但 `mvn -q test-compile` **回 exit 0、零錯誤**。

**根因**：`bpm-core/` 底下有**未納入版控**的 Eclipse 專案檔
（`.classpath`／`.project`／`.settings/`），而 `target/test-classes` 裡躺著
**Eclipse JDT（ECJ）編譯的 class 檔**（帶著上述未解析標記）。
`.classpath` **不含任何 junit／mockito 的測試相依**，所以那些 class 編譯時就是壞的。
Maven 的增量判斷**只看時間戳**，`.class` 比 `.java` 新 → 直接沿用壞產物。

**對策**：`mvn clean verify`。**不要照著錯誤訊息去改測試原始碼 —— 那些原始碼沒有錯。**
（PM 第一次就是走錯這條路，去找測試原始碼的問題。）

⚠️ 這些 Eclipse 檔是**本機有人用 Eclipse 開過這個專案**留下的。
`git status --ignored` 可以看到它們（`!!` 開頭）。刪掉它們可以避免復發，
但**任何人用 Eclipse 開一次就會重生**，所以真正的保險是「記得用 `clean`」。

### 1.2 前端基線是 **96**，不是 93

`docs/handoff/2026-09-30-round3-handoff.md` 與 `CLAUDE.md` 記的 93 / 87 都不準。
**只有實際跑出來的數字為準。** 這一輪結束時是 **144**（96 + #82 的 48）。

### 1.3 ⚠️ shell 的變數也會重置，不只是 cwd

PM 自己踩過：在一次呼叫裡定義 `TM=$(./scripts/dev-token.sh mgr001)`，
下一次呼叫直接用 `"$TM"` → **空字串** → 請求沒帶 token → 回 **401**。
一度以為是授權缺陷。**每個 shell 呼叫都是全新的環境**，`cd` 和變數都要重新給。

### 1.4 dev 庫的資料清不掉，不要設計需要清理的流程

2026-10-01 為了裁決一個引擎行為（`flowable:assignee="   "` 到底產生 null 還是
空白字串），用 `POST /api/deployments` 部署了一個探測用 BPMN
`probe-blank-literal`。**它刪不掉**：

- **沒有部署刪除端點**（`DeploymentController` 只有 `@PostMapping`）
- 手工刪 Flowable 內部表被外鍵擋住（`ACT_FK_EXE_PROCDEF`、
  `ACT_FK_BYTEARR_DEPL`）。試過四種刪除次序都失敗，最後放棄 ——
  半套的 DELETE 本身就有把 dev 庫弄不一致的風險。

**它留在那裡，是無害的殘留**（`acceptance-test.sh` 仍 PASS 7 / FAIL 0）。
要清掉只能重建 dev volume。

**教訓**：需要「部署一個臨時 BPMN 來實測引擎行為」時，**先查有沒有刪除路徑**。
沒有的話就在文件名稱裡加上 `probe-` 前綴並記錄下來，讓後來的人知道它是什麼。

### 1.5 `getIdentityLinksForTask` 的讀回順序不穩定

底層 SQL **沒有 `ORDER BY`**，同一組輸入在不同 run 會回兩種順序。
`isEqualTo(List)` 的測試會**時好時壞**（#93 的 agent 前兩次綠、後面連三次紅）。

**對策**：用不論順序的比對。連續重跑三次確認穩定才提交。
**副作用**：候選群組「保留書寫順序」這個性質從 identity link 讀不回來，
只能在守衛層（`ExternalActorGuard`）驗。

⚠️ **這種測試比沒有測試更糟** —— 會訓練人忽略紅燈。

### 1.6 🔴 一個非法的 Vue prop 會**掛住整個 vitest worker**

`MyApplications.vue` 的 `statusType('running')` 回傳 `''`，而 element-plus 2.7.3
的 `ElTag` 對 `type` 宣告的允許值是
`['primary','success','info','warning','danger']`、`default: 'primary'` ——
`''` 是**允許值以外**的 prop。

後果不只是 dev 模式噴警告：該警告在 jsdom 下把 Node 的 `util.inspect`
帶進無窮遞迴（`formatProperty → formatValue → formatRaw → formatValue`），
`Maximum call stack size exceeded`，接著**整個 vitest run 沒有終止**
（不是測試失敗，是卡死）。

**實測對照**：非法 prop → 120 秒無輸出；`type="primary"` → 26ms 正常。
PM 獨立重現過。

⚠️ **更一般的教訓**：jsdom + Node `util.inspect` + Vue 警告這個組合會讓
「一個或多餘的警告」變成「測試永久掛住」。**遇到測試無終止，先懷疑 prop 驗證。**

**為什麼改成 `'primary'` 而不是「不傳 type」**：2.7.3 的 `ElTag`
**沒有「無色」這個合法值**（`default` 本身就是 `primary`），所以 `''`
**從來沒有生效過** —— 它產生的是 `el-tag--`（沒有任何 CSS 變數匹配）。
**「維持原外觀」這個選項不可達。** 這是個容易誤判的例子：
看起來是外觀問題，實際上是「這個值從來就非法」。

### 1.7 zsh 與 git 的陷阱（沿用，複習）

| 坑 | 對策 |
|---|---|
| `echo ===` 被當命令展開 | 用 `echo '---'` |
| `--include=*.java` glob 失敗 | 加引號 |
| `ls`／`cp`／`mv` 被 alias | 用 `find`／`command cp` |
| `timeout` 指令不存在（macOS） | 用工具自己的 timeout 參數 |
| `git merge` 不支援 `-F -` | merge 訊息先寫進檔案 |
| 暫存檔放 `bpm-core/target/` | 已 gitignore |
| 刪稽核紀錄需 `SET QUOTED_IDENTIFIER ON` | 附帶觸發器時 MSSQL 會擋 |

---

## 2. 🔴 這一輪最重要的方法論結論：**PM 錯了三次，三次都是 agent 對的**

這一輪 PM 下了三個判斷，被 subagent 各自推翻，而且**三次都是 agent 對**：

| PM 的判斷 | agent 的反駁 | 結果 |
|---|---|---|
| 「P1-4 若是要求候選人白名單，那是另一個工項」 | 回去查 `security-audit.md:144` —— P1-4 指定的修法是「改顯式 switch，未知 action 回 400」（#77 已做）。「不檢查新 assignee 是否為候選人」只是**描述嚴重程度**的子句。**沒有任何文件要求候選人白名單** | **agent 對** |
| 「element-plus 沒有驗證 `type`，所以那個掛住 worker 的診斷抓錯根因了」（我只查了 Vue 3 核心不用 `values` 驗證） | 實測抓到警告內容：`Invalid prop: validation failed for prop "type" ... got value ""`。element-plus **自己註冊了 validator**，我漏了那一層 | **agent 對**（PM 後來用部署真實 BPMN 獨立複現） |
| 「`required=true` 的變數被擋在 `validateVariables`，所以不會在執行期為空」 | `validateVariables`（`:697`）**只判 `!variables.containsKey(...)`** —— 送 `{"dept":"  "}` 就通過 | **agent 對**，PM 讀碼確認 |

**為什麼值得記**：三次裡有兩次（第一、三次）PM 是**基於自己下給 agent 的指示**，
agent 必須**回去查原文**才能推翻它 —— 也就是說 agent 的獨立性是這條品質的關鍵。
如果 agent 只是照做，這三個錯誤都會合進主干。

**給下一位 PM 的具體做法**：
1. 下指示前**自己去讀一次原文**（本輪三次失敗都是「引用了二手敘述、沒讀原文」）
2. agent 回報時**獨立複驗**，而且要準備好**自己被推翻**
3. **證據優先於推論**：第 2 次的分歧最後是 PM 用「部署真實 BPMN + 查資料庫」
   裁決的，不是靠讀碼爭論

⚠️ 也要記錄反面：**agent 的質疑也可能是錯的**。第 2 次裡 agent 用位元碼推論
`flowable:assignee="   "` 會被寫入，質疑 `UnreachableTaskListener` 的 javadoc；
PM 實測後**既有 javadoc 對、agent 推論錯**。
**agent 做對的地方是標記「我懷疑、我未修改該檔、我無法複驗」而不是直接去「修正」** ——
如果它順手改了，PM 會拿掉一個正確的文件。

---

## 3. 並行 subagent 的標準流程（第二次驗證，可照做）

上一輪第 10 節的流程**再次有效**。這一輪派了 5 個 subagent（#90、#92、#93、
#91、#82）分三批，主樹全程零污染。

### 3.1 分派前的檔案邊界劃法（這一輪新增的關鍵）

派工前**先列出每個 agent 會動到哪些檔案，並把「不准動」寫進 prompt**。
本輪實際發生的重疊與處理：

| 情況 | 處理 |
|---|---|
| #93 要改 `ExternalActorGuard` 的簽章，#92 需要用同一個 guard | **指示 #92 只許「呼叫」不准「修改」**；若認為必須修改就停下回報。**合併零衝突** |
| #91 要改 `BpmnLintService`，#82 要動前端 | 檔案天然不重疊，但仍在 prompt 明寫邊界 |
| `docs/backend-development-backlog.md` | **指示所有 agent 都不准動**，PM 統一更新 |

**為什麼值得做**：上一輪記錄的唯一合併衝突就在 backlog 檔。
把「誰動哪個檔」在派工時就寫死，衝突會在**派工前**消失，而不是在合併時才發現。

### 3.2 資源限制：讓 agent 只跑自己的測試類別

本機 24GB，四份完整 `mvn verify` 有 OOM 風險（上一輪第 4.6 節）。
這一輪改成：**agent 只跑 `-Dtest='自己的類別'`，完整套件由 PM 在合併時統一跑一次**。

**結果**：既拿到並行（5 個 agent），又沒有記憶體風險，而且 PM 拿到的是
**完整套件**的結果（比 agent 回報的更有力）。

### 3.3 PM 收回成果時必做（本輪全部執行）

- `git show --stat` 逐個 commit 看，**確認沒有互相捲入檔案**
- 讀實際 diff（不只是讀報告）
- **獨立複驗 agent 的關鍵技術主張**（本輪：`javap` 位元碼、元素庫的 prop 宣告、
  部署真實 BPMN 查資料庫）
- 主樹跑完整套件 → 重建容器 → seed → `acceptance-test` → **線上實測**

⚠️ **線上實測不能並行**（8080 只有一個），一律由 PM 序列進行。
⚠️ **不要對 agent 正在用的 worktree 做 `git worktree remove`**。
⚠️ 合併後記得 `command rm -rf` 掉 agent 留在 `/tmp` 的備份與日誌
（本輪清掉 9 個，約 1.8MB：`gh-*-backup`、`gh91-probe`、`gh-93a-*.log`）。

---

## 4. 這一輪完成的六項

| # | 摘要 |
|---|---|
| **#90** | 申請人端 `MyApplications.vue` 渲染 `onBehalf` 標籤。**後端零改動**（值早就在 `ProcessController:278`／`HistoryController:296`）。三個 tab 結構性共用同一張 `<el-table>`，所以是結構性保證而非逐一檢查 |
| **#92** | `TaskController` 的 `reassign` 接上 `ExternalActorGuard.requireKnownPerson`（重用 #88 的規則，**不是新寫一份**） |
| **#93** | `firstTaskCandidateGroups` 送 JSON array 不再 500。**陣列為 canonical、逗號分隔字串保留相容**。`requireAllowedCandidateGroups` 簽章由 `String raw` 改為 `List<String>` |
| **#94** | `CountersignController` 的第二份實作刪除、`delegate` 分支接上同一規則 → **「指派給誰必須是組織系統認識的人」收斂成唯一一份** |
| **#91**（方向 A） | `BpmnLintService` 新增規則 `optional-assignee`（severity `warning`）。**方向 B 未做**，見第 6 節 |
| **#82** | 新增 `GET /api/me/permissions`，前端接權限碼。修掉三個現成破口（見下） |

### 4.1 #93 的 spec 全面盤點找出兩處矛盾（值得學）

agent 在改 spec 時發現 `docs/bpm-platform-spec.md` 的 §9.2 **請求範例含
`"initiator": "system:registration"`**，而 `ExternalApiController:89`
**拒絕任何帶 `initiator` 的 body**（回 400）—— **整合方照 spec 抄會拿到 400**。

PM 依「全面盤點」的要求機械式比對 spec 內所有 body 範例的欄位與程式碼實際接受的欄位，
找到**兩處**：

1. 上面的 `initiator`（R-20 落地後文件沒跟上，先於 #93 存在）
2. §9.2 規則第 3 點「後續節點的 `initiator` 可替換為實際經辦人」——
   **程式碼沒有替換 `initiator`**（全程維持 `system:<id>`，另外寫入**獨立的**
   `effectiveInitiator`）。**而這一點寫錯過，它正是 #83 的成因**：
   設計師照著在補件關卡寫 `${initiator}` → `system:erp` → 永久卡死無告警

**教訓**：**文件與程式碼的落差本身就是缺陷**，而且落差往往指向更早的決策失誤
（第 2 處直接是 #83 的根因）。「只改被指出的那一行」會讓人以為文件整體可信了。

⚠️ 保留未動：第 4 點的 lint 規則與 Java 草圖**是準確的**，不要順手改。

### 4.2 #82 修掉的三個破口（都是程式碼裡現成的，不是推測）

| 身分 | 後端 | 改動前前端 | 症狀 |
|---|---|---|---|
| `mgr001`（`bpm:form:design`） | `POST /api/forms` 放行 | `requiresRole: 'admin'` → 擋掉 | 業務人員**看不到**表單設計功能 |
| `dir001`（`audit:log:read`） | `/api/audit-logs/**` 放行 | `requiresRole: 'auditor'` → 擋掉 | 稽核職能**進不了頁面**（而 `dev-token.sh` 的註解正是教人用 `dir001` 讀稽核的） |
| `admin001`（`*` → `ROLE_ADMIN`） | `/api/audit-logs` **刻意不接受** ROLE_ADMIN | 看得見所有管理頁 | 點下去**全部 403** |

**線上實測**：`admin001` 拿到 `admin=true` 但 `permissions=[]` —— 所以前端會對
admin 隱藏稽核選單。**這是「admin 不是萬能」在前端的落實。**

⚠️ **「哪條規則接受 `ROLE_ADMIN`」是每一條規則自己的政策決定**，
`SecurityConfig` 類別註解有對照表。前端**必須複製這個差異**，
不能簡化成「admin 萬能」。

### 4.3 🔴 收斂工作會產生「後效應」，而本輪兩個後效應都是 PM 造成的

把一條規則收斂成一份實作之後，**其他呼叫點的落差才浮現**：

1. **訊息把外部 API 的語境寫死了。** `requireKnownPerson` 的 400 說
   「請改用 `onBehalfOf` 代員工發起」、503 說「未發起流程…未建立任何流程實例」。
   而五個呼叫點裡**只有外部 API 那兩個真的會發起流程**；
   `reassign`／`delegate`／`countersign` 的 body 裡**根本沒有 `onBehalfOf`**。
   **診斷訊息指向錯誤的欄位，比沒有訊息更糟。**
   → 新增 `action` 參數（三個呼叫點分別傳「改派任務」／「委派任務」／
   「建立加簽子任務」），後果句改為動作中立的「本次請求未做任何變更」。
   **刻意不做 per-call-site 客製化** —— 那會讓「規則只有一份」連訊息層都失守。

2. **加簽的 `assignee.trim()` 讓同一條規則有兩種答案。** `" mgr002 "` 在加簽
   回 200、在改派／委派回 400。repo 早已有答案（**不靜默 trim**，對組織系統而言
   那是另一個人），拿掉後**行為變更**（200 → 400），經 PM 裁決。

⚠️ **教訓**：收斂之前先問「**其他呼叫點有沒有形狀／語境的落差**」。
本輪那個 `trim()` 正是**收斂工作自己留下的**同型缺陷 —— 諷刺但真實。

**連帶的測試教訓**：有兩處測試斷言舊訊息的措辭
（`ExternalActorGuardTest`、`ExternalOrgSystemFailureTest`）。
**舊斷言其實是個陷阱** —— 它會把訊息鎖死在「對五個呼叫點裡四個是假的」那個版本上。
更新時**斷言的意圖要一字不減**（那兩條斷言的意圖是「讓只看狀態碼的呼叫端
知道可以安全重試」）。

---

## 5. 2026-10-01 已完成的線上實測（每一項都同時驗了資料沒被改）

| 工項 | 實測內容 |
|---|---|
| **#92** | 6 種拒絕形狀全部 400（`nobody-xyz`／`system:evil`／`SYSTEM:x`／全形`ｓｙｓｔｅｍ：x`／前後空白／空白），**且每一筆的 assignee 都仍是 `mgr001`**。**非持有者送合法 id → 404**、**送未知 id → 也 404**（證明新檢查沒被插到 `requireHolder` 之前）。持有者改派給**不在候選清單裡的真人** → 200。空白 → 400 且訊息**沒有**提到候選群組 |
| **#93** | 陣列與字串都 200；`["hr:leave:approve",123]` → 400 指名「第 2 個元素…數字」；`null` 元素 → 400；整欄型別錯 → 400；未授權群組 → 403；空陣列 → 既有規則的 400。**未授權的流程 key + 未授權群組 → 403**（授權先於形狀，防枚舉順序成立）。**直接查資料庫**：identity link 陣列 1 條／字串 2 條／空白被丟棄／重複去重；流程變數 `TYPE_=string` 且是逗號分隔（不是序列化清單）。**被拒的請求在 `ACT_HI_PROCINST` 零紀錄** |
| **#94** | 委派 5 種拒絕形狀全部 400，**assignee 與 owner 都未變**；加簽 3 種拒絕形狀**零子任務**；鏈頂 `dir001`（`getDirectManager` 回 null）兩種都 200。**前後空白 `" mgr002 "` 三個入口一致 400** |
| **#82** | **14 個參數名 × 3 個他人 = 42 種組合，全部與乾淨請求逐字元相同，零洩漏**。`admin001` → `admin=true` 但 `permissions=[]`。非回歸：`/api/audit-logs` 對 admin001 → **403**、dir001 → 200；`POST /api/forms` 對 mgr001 與 admin001 放行、user001 → 403；未登入 → 401 |
| **#90** | user002 的 159 筆申請裡**恰好一筆** `onBehalf=True`（代發案），其餘 158 筆全是 `False` |

⚠️ **IP 白名單的實測教訓**：外部 API 的請求來源是 docker bridge
（`192.168.117.1`），不是 `127.0.0.1`。建立測試用外部系統時
`ipWhitelist` 要兩者都填，否則會先撞上 403 而以為是別的問題。
（`allowedActions` 也要含 `start_process`。）

---

## 6. 尚未處理的（下一輪要處理）

| 項目 | 說明 |
|---|---|
| **#91 方向 B** | 指派層把求值為空白的 assignee 視為「未指定」，讓候選人接手。**卡在 flush 順序風險**：`UnreachableTaskListener` 是 `FlowableConfig:76` 用 `setEventListeners(...)` 註冊的**全域 engine event listener**，而 engine event listener 跑在 task 落地**之前** —— #86 的缺陷正是「Hibernate flush 順序固定是 INSERT 在 DELETE 之前」。task listener（`event="create"`）跑在落地**之後**才安全。**但改成 per-BPMN task listener 會讓規則只對掛了 listener 的 BPMN 生效**（出廠 BPMN 有、使用者部署的沒有）= 同一條規則兩套形狀。**兩難未解** |
| **#91 漏報 ①** | `validateVariables` 只擋「缺值」不擋「送空白值」 → `{"dept":"  "}` + `${dept}` 仍會靜默卡死。修法是讓 `required=true` 也拒絕空白值（→ 400），**但要先實測會不會打破既有整合** |
| **#91 漏報 ②** | 混合式 `${a}-${b}`（b 為 optional）會靜默卡死成 `"alice-"`。現行規則只認**整個運算式就是 `${var}`** |
| **測試缺口** | `bpmnLiteralIsNotTheDefect` 只覆蓋 `flowable:assignee=""`（空字串），而 `UnreachableTaskListener` 的 javadoc 聲稱 `""` **和** `" "` 兩者。**那個空格形狀沒有任何測試釘住**（2026-10-01 是 PM 手動部署 BPMN 實測才知道現況：`assignee` 為 NULL，javadoc 對） |
| **`dev-token.sh` 註解** | 它教人用 `dir001` 讀稽核，但 `mintDevToken` 刻意不簽 `roles` claim → **改動前 dir001 在 dev 根本進不了 `/audit-log`**。#82 修好了，但註解該不該更新是 PM 的判斷 |
| **#67 流程層** | `ProcessCompletedListener` 的流程級 webhook 仍無投遞設定來源（斷線 A 的**第四個實例**）。spec §11.4 只定義節點層 → 需裁決 |
| **#67 前端** | `modeling.updateProperties(element, { extensionElements })` 那一步沒有自動化測試覆蓋 |
| **#25** | webhook payload 缺口：`task.timeout` 事件、候選人、`operatorName`、`comment` |
| **#68** | R-20 剩餘 docs 收尾 |
| **#70** | Boot 4 + Flowable 8，22 人日。前置調查完成。⚠️ **升級時 `ExtensionElementPreservationTest` 會是第一個紅的** |

### 6.1 ⚠️ 上線前必讀（沿用，仍然有效）

**必須在真實權限中心指派權限碼 `bpm:external:revision`**，否則外部系統發起的案件
連「退回」都會 500（`ApplicantResolver` 第三段查不到就拋例外，刻意不回 null）。
dev 環境的 `MockPermController` 已有 `dir001` 的 fixture，所以**測試全綠掩蓋了這個前置條件**。

**另外三個**（`#82` 帶出來的）：
- `bpm:form:design` 與 `audit:log:read` 必須在真實權限中心指派給正確的人員
- `/api/me/permissions` 回的是 **SecurityContext 的 authorities**，所以
  真實權限中心的授權結果會直接反映在這裡

---

## 7. 授權元件（新增兩個，其餘沿用）

```
ExternalActorGuard         唯一一份「指派給誰必須是組織系統認識的人」
  requireKnownPerson(field, userId)          → 預設 action「發起流程」（外部 API）
  requireKnownPerson(field, userId, action)  → action 決定訊息裡的語境
  requireAllowedCandidateGroups(sys, List)   → #93 簽章改收已切開的清單
MeController                GET /api/me/permissions（只回呼叫者自己的，無 ?userId=）
OnBehalfOfLookup            「我參與的案件」與代發標示的反向批次查詢
ProcessAccessGuard          案件層：requireReadAccess / requireParticipant / requireSelf …
TaskHolderGuard             任務層：requireHolder / isHolder
CandidateGroupMembership    呼叫端所屬候選群組（部門 ∪ 權限碼 ∪ 非 ROLE_ 的 authority）
ProcessInvolvementService   「我參與的案件」的反向批次查詢（避免 N+1）
ExternalSystemPolicy        白名單四態分類
WebhookConfigResolver       從 BPMN 的 extensionElements 讀 webhook 設定
```

**為什麼 `ExternalActorGuard`  現在有五個呼叫點**：`firstTaskAssignee`／
`onBehalfOf`（外部 API）、`assignee`（reassign）、`delegateUser`（delegate）、
`countersignUserId`（加簽）。**五個都是同一條規則** —— 這就是「規則只能有一份」
的實際意義。

---

## 8. 2026-10-01 使用者已做的裁決（**不要再問**）

- **#93 請求契約**：`firstTaskCandidateGroups` **陣列為 canonical**，
  逗號分隔字串保留為**相容形狀**。型別錯誤回 **400**（沿用同一方法既有規則）。
- **#91 方向**：**只做方向 A**（lint 規則），方向 B 留待下一輪。
  severity 鎖 **warning**（`error` 會擋掉部署）。規則條件鎖
  「`${var}` 且已宣告但 `required=false`」—— **刻意不用「`${var}` + 無候選人」**，
  那會對本平台最常見的「審核人由執行期變數決定」設計噴警告。
- **#92／#94 驗什麼**：驗「**是不是人**」而不是「是不是候選人」。
  候選清單是啟動時的**建議**不是改派白名單，拿它當白名單唯一合法結果是把改派功能打死。
- **#94 加簽的 `trim()`**：拿掉（行為變更 200 → 400）。三個入口一致拒絕前後空白。
- **#82 修法**：新增 `GET /api/me/permissions`，**只能回呼叫者自己的權限碼、
  絕對不能有 `?userId=` 參數**（那是組織結構的枚舉通道）。
  `/admin/forms` 的寬嚴不一致**維持現狀不放寬**。
- **#82 產品可見變動已追認**：`App.vue` 把「表單編輯器」**移出** `v-if="isAdmin"`
  的管理子選單 —— 留著會讓整個工項白做（mgr001 路由放行但選單看不到）。
- **`ExternalActorGuard` 訊息**：加 `action` 參數，**不做 per-call-site 客製化**。

**沿用（2026-09-30 之前，見 round3 handoff 第 8.1 節）**：授權三組政策、
回 404 而非 403、稽核只認 `audit:log:read`、R-20 的 `system:<id>` 與 `onBehalfOf`、
表單設計用 `bpm:form:design` 且保留 ADMIN 旁路、`isParticipant` 不擴充候選群組、
分支維持 `feature/* → main` 直接合併。

---

## 9. 本輪的誠實回報（值得當作下次的招募標準）

四個 agent 全部產出了「主動揭露自己不足」的段落，其中三項直接改變了結果：

1. **#92 的 agent 回去查 `security-audit.md` 原文，推翻了 PM 的指示**，
   並說明「沒有任何文件要求候選人白名單」。
2. **#91 的 agent 指出 PM 裁決的前提有破口**（`required=true` 不擋空白值），
   並**主動說明負向控制組的限制**：綠的 7 條證明不了新規則有接上，
   而且 `severityIsWarningAndDoesNotBlockDeployment` 卡在第一個斷言，
   所以 severity 與 `valid()` **未被獨立驗證**。
3. **#94 的 agent 指出加簽那 3 條紅的測試「不是新缺陷」** ——
   舊實作在 fail-closed 的 `MockOrgController` 下本來就擋得住。
   所以收斂帶來的實際改變只有兩件（`system:` 前綴不再依賴 fail-closed、訊息統一）。
   它也**推翻 PM 上一輪的辯護**：「可以 resolve 收回來不是緩解理由 ——
   owner 必須自己察覺到不對，而沒有任何人收到告警」。
4. **#82 的 agent 做了四次獨立的負向控制組驗證**，其中最有價值的是
   「路由改回 `requiresRole`（原始缺陷）→ 紅 6 條」證明測試真會抓到；
   而前兩次各只影響一層，它明說「紅了 5 條反而代表測試間有隱性耦合」。

**另外兩件它們做對的事**：
- **#93 的 agent 修正了 PM 兩處錯誤**（章節號 §9.1.3 → **§9.2**；
  負向控制組抓到是**它自己測試寫錯** —— 記錯了 `initiator` 檢查的順序）。
- **#91 的 agent 對既有 javadoc 的質疑標記為「我懷疑、我未修改、我無法複驗」**，
  讓 PM 用實測裁決 —— **沒有把一個未證實的推論寫進程式碼**。

---

## 10. 現況一句話

**2026-10-01 收尾（第三輪）**：六項工項（#90 #92 #93 #94 #91A #82）完成，
加上 PM 收尾時修的兩個收斂後效應（訊息的 `action` 參數、加簽的 `trim()`）。
後端 **693** 測試全綠（**容器停止狀態、`mvn clean verify`**）、前端 **144** 全綠、
`acceptance-test.sh` PASS 7 / FAIL 0。工作樹乾淨、**所有 worktree 與 feature 分支已清除**、
`/tmp` 下 agent 的備份已清空。**尚未部署。** `main` 比 `github/main` 多 25 個 commit，**未 push**。

⚠️ **dev 庫有一個殘留的流程定義 `probe-blank-literal`**（第 1.4 節），
無害，要清只能重建 volume。

⚠️ **四個測試類別斷言 `ExternalActorGuard` 的中文訊息片段**（#92 的 4 條、
#93 的若干、#94 的 4 條、#90 的 1 條）。**若日後改寫那些訊息，這些會紅** ——
這是刻意的取捨（與 repo 既有先例一致），但要改訊息時**預期會有多個類別轉紅**，
那是訊息改動的成本，不是測試壞掉。
