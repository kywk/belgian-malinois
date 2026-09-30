# 接手文件 — 2026-09-30 第二輪完成（含 #67 節點層）

**寫給下一個接手的實作 agent。** 撰寫時間 2026-09-30。
上一輪的交接見 `docs/handoff/2026-09-29-authorization-hardening-handoff.md`
（**那份仍然有效**，本檔只補充 2026-09-30 這一輪的新事實）與
`docs/handoff/2026-09-29-agent-handoff.md`。

---

## ⚠️ 0.0 上線前必讀：#83 有一個硬性前置條件

**必須在真實權限中心指派權限碼 `bpm:external:revision`，否則外部系統發起的案件
連「退回」都會 500。**

`ApplicantResolver` 的第三段（initiator 是 `system:<id>` 時派給誰）會查這個權限碼，
查不到就拋 `IllegalStateException`（**刻意不回 null** —— 回 null 會建立一個沒有
assignee 也沒有候選人的任務，正是換一種方式製造同一個靜默卡死）。

dev 環境的 `MockPermController` 已有 `dir001` 的 fixture，所以**測試全綠掩蓋了這個
前置條件**。對接真實權限中心時若忘了建立，症狀是「外部系統發起的單退回時直接報錯」，
而且是在**使用者按下退回的當下**才會發生。

---

## 0. 先說本輪最重要的方法論結論

**#86 的觸發條件比任何人一開始描述的都窄，而這正是它躲過「手動試一次」的原因。**

backlog 原本寫「只要該 key 已有任何一筆規格，重複儲存必定失敗」。
實測後修正：**必須是新批次與既有規格有同名變數**才會撞唯一約束。
新舊完全不重疊（整批換新名字）或送空陣列，缺陷期間都是 200。

所以一個人手動測試時很容易剛好試在綠的那一側（剛建好規格 → 第一次存 → 好的），
然後就判斷「這個功能沒問題」。**連續呼叫同一路徑兩次，是抓到這類缺陷的最低門檻。**

負向控制組也獨立佐證了這點：缺陷放回去時 8 條測試紅 4 條，
**紅的全是「有重疊」形狀**，另外 4 條（換新名字、空陣列、非管理員、夾帶 id）
在缺陷期間是綠的——它們防的是「修法把語意改壞」。

**接手後的紀律：驗證任何「整批取代 / 刪除後重建」的端點，都要連續呼叫兩次。**

---

## 1. 系統現況

| | |
|---|---|
| 後端 | Spring Boot **3.5.16** + Flowable **7.2.0**（**未升級**），單一模組 `bpm-core` |
| 前端 | Vue 3.4 + Vite 5 + Element Plus |
| DB | MSSQL 2022，**三個資料庫**：`bpm_core_db`／`bpm_audit_db`／`bpm_form_db` |
| 其他 | RabbitMQ、Redis、MailHog。Docker 是 **OrbStack** |
| 測試 | 後端 **585** 個（Testcontainers：真實 MSSQL／RabbitMQ／Redis），前端 **87** 個（Vitest） |
| 分支 | `feature/round2-hardening`，比 `main` 多 9 個 commit，**未 push** |
| 部署 | **尚未部署，只有本機開發** |

```bash
cd /Users/kywk/kywk/nanshan/greyhound
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d --build bpm-core mailhog
./scripts/seed-data.sh        # 部署 2 支 BPMN + 4 個表單定義
./scripts/acceptance-test.sh  # 應為 PASS 7 / FAIL 0
cd bpm-core && mvn verify      # 422 個
```

⚠️ **啟動必須帶 dev overlay**，少了 mailhog 會讓 health 失敗。
⚠️ 跑 `mvn verify` 前必須 `docker compose stop bpm-core`（理由見第 6.2 節）。

---

## 2. #86 已完成（commit `f6df3fb`）

`POST /api/admin/process-definitions/{key}/variable-spec` 是「整批取代」，
原本先衍生刪除（`em.remove()`，只**排程**不落地）再 `saveAll`，
而 **Hibernate 的 flush 順序固定是 INSERT 在 DELETE 之前** → 撞
`uk_bpm_process_variable_spec_key_name` → 500。

**修法**：刪除改成 `@Modifying @Query` 原生 JPQL，呼叫當下就送到資料庫，
完全不進 Hibernate 的動作佇列。

**為什麼不選另外兩條路**（理由已寫在 repository 的 javadoc）：

- **「衍生刪除 + `flush()`」**——也能修好，但規則會散在「刪除」與「記得 flush」
  兩處。**規則分散正是這個缺陷的成因**（#84 的 create/update 對同一個參數
  有兩套規則也是同一個成因）。讓刪除只有一種形狀比較重要。
- **「比對後只刪真正消失的那幾列」**（diff/upsert）——會讓舊列**保留原 id**，
  而 `batchSave` 強制 `setId(null)` 是 **security-audit P0-4 的防護**
  （夾帶 id 會讓 `save()` 走 `merge()` 覆寫任意資料列）。保留 id 等於替
  那條防護開一個繞道。

新增測試：`VariableSpecBatchReplaceTest`（8 條，在 `com.bpm.core.security`）。

---

## 3. ⚠️ 並行開發的檔案衝突（本輪實測）

**`HistoryController` 同時被 #79 與 #80 需要**：

| 工項 | 動到的檔案 |
|---|---|
| #79 簽核意見 | `TaskController.java`、`HistoryController.java` |
| #80 bpmn-xml / documents | `ProcessController.java`、`HistoryController.java` ← **與 #79 重疊** |
| #81 表單 createdBy | `form/` 套件（不衝突） |
| #83 system:<id> | BPMN XML、`FlowableConfig`、新的 resolver bean |
| #87 重複變數名 | `ProcessVariableSpecController.java`、前端 spec admin |

所以 **#79 與 #80 不可在同一 working tree 並行**。
本輪只開 #79 與 #87（檔案不重疊）正是為了避開這件事。
**下一輪若要並行 #79 + #80，必須用獨立 git worktree。**（見第 4.5 節）

---

## 4. 這一輪踩過的坑 —— 不要重犯

### 4.1 測試基線會隨 commit 漂移
backlog 與 handoff 記的測試數一直不準（280 → 402 → 414 → 422）。
**以 `mvn verify` 的實際輸出為準**，不要相信文件裡的數字。

### 4.2 負向控制組要記錄「哪些測試是綠的」
這次的教訓：負向控制組不只證明「測試會紅」，也**揭露了缺陷的實際邊界**
（紅的全是重疊形狀）。這比「測試紅了」更有資訊。
把這個觀察寫進測試類別的 javadoc，下一個人做負向控制組時才有依據。

### 4.3 用 `command cp` 備份，絕對不用 `git checkout -- src/main/java`
前兩輪的 subagent 都踩過 `git checkout`，把自己已修改的 tracked 檔還原掉，
重做了 4 個檔案的全部內容。
```bash
command cp <file> bpm-core/target/<dir>/          # 備份
command cp bpm-core/target/<dir>/<file> <file>   # 還原（cp 被 alias 成互動模式會靜默不覆寫）
```

### 4.4 負向控制組本身可能無效
把缺陷放回去的**位置**會影響結果。有一次把 `initiator` 放回 deny-list 檢查
**之前**，結果 deny-list 反過來擋掉一切、12 個測試全綠——等於沒驗到。
**改用整份還原**才得到有效結果。

### 4.5 🔴 兩個 agent 共用 working tree → **2026-09-30 實測造成實際損失**

上一版 handoff 寫「共用 working tree 會汙染對方的**建置**」。
2026-09-30 實際派兩個 subagent 並行後，後果比「建置被汙染」嚴重得多：

- **#79 的 agent 用 `git add`（非明確路徑）時把 #87 agent 的 4 個檔案捲進自己的
  commit**，造成那些檔案在他的樹裡被刪除。事後用 `git commit --amend` 修正。
- **#87 agent 的 backlog 修改被整份還原掉**，必須重做。
- 兩人都靠 `git add`／`git reset --soft` 收拾殘局，任何一步出錯就是資料遺失。

**同一輪的 handoff 裡已寫過「用獨立 worktree」，但沒有落實 —— 因為 PM 在分派時
沒把「每個 agent 一個 worktree」寫進 prompt。教訓記在這裡，也記在第 10 節。**

另外兩個併行才會遇到的問題：

- **不要讀 `bpm-core/target/surefire-reports` 判斷測試結果**。併行的 `mvn verify`
  會互相覆寫，#87 的 agent 一度讀到 #79 的失敗報告。
  **只信任當次 `mvn verify` 自己印出來的最後一行。**
- **不要用 surefire 報告加總來算測試總數**。報告檔不涵蓋 `@Nested` 內類別
  （PM 實測：逐類加總 416，實際 450，差 34 條全在 `@Nested`）。
  **唯一的真相是 `mvn verify` 輸出的 `Tests run: N`。**

### 4.6 記憶體：併行跑 `mvn verify` 的真實風險
24GB 機器上每份 `mvn verify` 會各起一組 Testcontainers（MSSQL 就要 1.5GB）。
四份同時跑有 OOM 風險 —— 實際上兩位 agent 都選擇**等對方的 maven 結束**才跑完整套件，
等於序列化。**若要真正並行，測試階段必須排程序列化。**

### 4.7 稽核的「多記而非漏記」是刻意取捨，不是缺陷
#86 缺陷期間，失敗的請求仍留下一筆 `replace` 稽核，宣稱「刪掉 reason、
放寬所有必填」，而資料其實完全沒變。
這**不是**缺陷——`AuditEventPublisher` 的類別註解已記載這是 fail-closed
機制**刻意接受**的窗口（「我們選多記而不是漏記：多記可以從業務資料反查出來，
漏記無從發現」）。
修掉 flush 時的約束違規後，這個窗口的**這個實例**自然消失，但機制本身不變。

---

## 5. 使用者的硬規則（不可違反）

延續上一輪 handoff 第 7 節（認證政策、稽核 fail-closed、`setBeans()` 必須含
`notifyTaskListener`、不要用 `git add -A`、分階段 commit 訊息繁中寫清楚為什麼…），
**以下幾條在 2026-09-30 再次確認仍然有效**：

- 授權的三組政策**已定案，不要自創第四組**。未通過時回 **404 而非 403**
  （403 會確認物件存在，對可枚舉的 id 等於留枚舉管道）。
- **規則只能有一份。** 缺陷的成因常常就是同一條規則有兩套形狀
  （#84 create/update、#86 刪除+flush）。新增檢查前先確認既有元件沒有現成規則。
- 稽核 fail-closed；稽核與交易綁定用 `@Transactional` 限定管理器。
- **不要修改 `docs/history/**`**；**不要新增全域 `@RestControllerAdvice`**。
- **政策性決定一律先問使用者**（授權範圍、fail-open 或 fail-closed、誰能看什麼）。
  連「要不要擋空字串」這種小決定也是。
- **不要擅自 push。**

---

## 6. 已知限制（下一輪要處理的）

| # | 事項 | 說明 |
|---|---|---|
| **#83** | `system:<id>` 的任務沒有人能簽 | **#74 造成的行為變化**。外部系統發起 → 主管退回 → 補件關卡 assignee 是 `${initiator}` = `system:<id>` → 四個持有者條件全不命中 → **案件永久卡死**。根本解法在 BPMN／路由層 |
| **#82** | 前端沒有權限碼的概念 | `session.js` 只讀 JWT 的 `roles` claim，權限中心的權限碼不在 token 裡。**架構決定**，要先問使用者 |
| ~~#80~~ | ~~`bpmn-xml` 與 `documents` 零檢查~~ | ✅ **2026-09-30 完成**。`activeIds` 洩漏「卡在哪一關」已關閉；`GET /api/documents` 的 `findAll()` 已收斂成「自己建立的」 |
| #81 | `POST /api/forms` 的 `createdBy` | 與 #66／#72 同型 |
| — | 被委派任務送 `complete` 是裸 500 | **既有引擎行為**，委派能走完的動作是 `resolve` |
| — | `isParticipant` 不涵蓋候選群組 | Flowable 的 `taskInvolvedUser` 只比對 `USER_ID_`。**使用者已明確決定維持現狀** |
| — | `ProcessController` 與 `TaskController` 各有一份 `PROTECTED_VARIABLES` | 兩處必須維持同一份內容。合併屬重構，尚未做 |

---

## 7. 每一個工作項的流程（已驗證）

1. **先在原始碼與執行中的服務上確認問題真的存在。** 不要只照文件描述動手。
2. 修，**註解寫清楚「為什麼這樣做」與「為什麼不用另一種做法」**（繁中，
   註解密度比照 `TaskHolderGuard`、`ProcessAccessGuard`）。
3. 寫測試，然後**把缺陷放回去確認測試會紅**，並記下**哪些測試是綠的**
   （見 4.2）。
4. `docker compose stop bpm-core` → `cd bpm-core && mvn verify` → 全綠。
5. 重建容器 → `seed-data.sh` → `acceptance-test.sh`（PASS 7 / FAIL 0）。
6. **對執行中的服務用真實 JWT 做 curl 實測**（`./scripts/dev-token.sh <user>`）。
   每個狀態碼斷言都要**同時驗證資料沒被改**。
7. commit，更新 backlog 狀態欄。

### 7.1 線上實測抓到、自動化測試看不到的例子

- **error dispatch**：MockMvc 不做 error dispatch，`ResponseStatusException`
  變成的狀態碼在測試裡與線上不同。**新的狀態碼斷言要走真實 HTTP。**
- **守衛插在流程中段時，狀態碼差異會被既有測試綁死**：守衛必須排在
  action 形狀檢查**之後**。
- **本輪（#86）**：狀態碼斷言全部走真實 HTTP，因為
  `DataIntegrityViolationException` 的 500 與 MockMvc 的行為不一致。

### 7.2 跑 Maven 前必須設定

```bash
export DOCKER_HOST=unix://$HOME/.orbstack/run/docker.sock
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
```

### 7.3 zsh 與 git 的陷阱

| 坑 | 對策 |
|---|---|
| `echo ===` 被當命令展開 | 用 `echo '---'` |
| `--include=*.java` glob 失敗 | 加引號 |
| `ls`／`cp`／`mv` 被 alias | 用 `find`／`command cp` |
| `git merge` 不支援 `-F -` | merge 訊息先寫進檔案 |
| 暫存檔放 `bpm-core/target/` | 已 gitignore |
| 刪稽核紀錄需 `SET QUOTED_IDENTIFIER ON` | 附帶觸發器時 MSSQL 會擋 |

---

## 8. 待使用者裁決（2026-09-30 新增）

| 事項 | 說明 |
|---|---|
| **#79-2** | 對**已完成**的關卡留言仍是裸 500（`AddCommentCmd` 查不到 runtime task）。**改動前完全相同，非本輪引入**。守衛放行之後才 500 對呼叫端是誤導的。修成 404（任務已結束）還是 409（狀態衝突）屬 API 語意政策。已用測試釘住現狀。前端走不到這條路徑 |
| **#87-2** | **空白 `variableName` 該不該擋？** 現況：單一空白名 → 200（已用測試釘住），兩個空白名互相衝突 → 400。**#87 的建議是擋**：`variableName` 就是外部系統要塞進流程的 key（spec §8.5），一個叫 `""` 的變數永遠比對不到任何東西 —— 設定它的人看不到異常，但輸入驗證等同少了一項；`required=true` 的空白名則讓**每一次**外部發起都回 400。擋的話改動很小 |
| **#87-3** | `variableName: null` 仍 500（NOT NULL 約束）。刻意未擋，屬空白名稱政策的一部分，建議與 #87-2 一起裁決 |
| **#80-1** | **`GET /api/documents` 應列出「自己參與的」還是「自己建立的」？**（已實作為**自己建立的**，待追認）。省略 `createdBy` = 呼叫者、帶他人 = 400。**放棄了**「全公司公文清單」與稽核旁路。關鍵前提是**沒有任何前端呼叫這個端點**（`grep` 零命中），所以現在收斂零成本；日後若要「我參與的公文」應**新增** `/api/documents/involved`（走 `ProcessInvolvementService` + `requireReadAccess`），而不是把這個放寬回去。完整理由見 `DocumentController.list` 的 javadoc |
| **#80-2** | **`bpmn-xml` 對「已結案」的案件仍回 200 + 空圖**（`pi == null` 的分支刻意未改）。`ProcessDiagram.vue:23` 依賴它顯示「無流程圖資料」，改成 404 會讓審結的單在畫面上變成錯誤訊息。分開「已結案」與「從未存在」需要改變回應契約（已結案的單突然有流程圖），屬產品決定。**「從未存在」已由守衛擋成 404**，所以不存在枚舉管道 |
| **#80-3** | ~~#80 未做線上實測~~ | ✅ **已由 PM 完成**：`mvn verify` 482 全綠、`acceptance-test` PASS 7/FAIL 0，線上實測通過（見第 9 節） |

### 8.1 裁決結果（2026-09-30 使用者已全部裁決）

依使用者硬規則「政策性決定一律先問使用者」，累積的 9 項已取得裁決。
**其中 4 項需要實作**，其餘是確認現狀或純文件。

| 項目 | 裁決 | 狀態 |
|---|---|---|
| **#83 政策** | 維持權限碼 `bpm:external:revision` 方案 | ✅ **接受為上線檢查清單**。⚠️ 見第 0.0 節：真實權限中心必須指派此權限碼 |
| **#88 政策 A** | **組織系統故障時回 503**（不是 400） | 🔧 **待實作**。502/503 明確表示暫時性問題；守衛在啟動流程之前，**重試是安全的**（不會產生重複流程） |
| **#88 政策 B** | **加 `allowedCandidateGroups` 白名單** | 🔧 **待實作**。授權維度、零外部系統依賴、不需改權限中心 |
| **#87-2** | **擋空白 `variableName`** | 🔧 **待實作**。前端 `addRow()` 的空名稱行為一併要改 |
| **#87-3** | `variableName: null` **改成 400** | 🔧 **待實作**。與 #87-2 同一批 |
| **#79-2** | 對已完成關卡留言 **回 404** | 🔧 **待實作**。與其他「找不到東西」的回應一致，呼叫端不會再重試 |
| **#80-1** | 維持「自己建立的」 | ✅ 確認現狀。日後若要「我參與的」應**新增** `/api/documents/involved`，**不要把這個放寬回去** |
| **#68 政策** | 維持不顯示 `initiator`（`system:<id>`） | ✅ 確認現狀 |
| **#83 附帶** | 68c 標為**已完成** | ✅ 純文件（`ApplicantResolver` 第一段就是 68c 已定調的規則） |

**注意裁決 #88-A 與 #88-B 有耦合**：#88-A 改 503 會同時改變 `onBehalfOf` 的既有行為
（今天未知員工回 400）。兩者應在**同一個工項**裡實作並分開測試 ——
「查無此人」要回 400（呼叫端該改 payload），「組織系統故障」才回 503。

**實作 #88-A 的關鍵分辨**：`MockOrgController` 對不認識的使用者回 **404**。
那屬於「查無此人」還是「系統故障」是**判斷題**，不是讀程式碼就能得出來的 ——
404 在真實組織系統上也可能是「端點不存在」。這一點要在實作時想清楚，
否則會把「查無此人」誤判成 503（呼叫端不該重試的情況被當成該重試）。

**實作 #87-2/#87-3 的關鍵**：空白檢查必須**重用 `dbComparisonKey` 的正規化邏輯**，
不可另寫一份 `isBlank()` —— #87 實測發現 `"amount"` 與 `"amount "`（尾端空白）
在 MSSQL 裡**就是衝突**，而另寫 `isBlank()` 會漏掉這個形狀，
它正是要擋的。

---

## 9. 2026-09-30 已完成

| # | commit | 摘要 |
|---|---|---|
| #79 | `235ab30` | 簽核意見三端點接 `ProcessAccessGuard.requireTaskReadAccess`／`requireTaskParticipant`。**連 taskId→pid 的查詢都收進守衛**（授權規則只能有一份）。`TaskHolderGuard` 完全沒動 —— 留言不改變任務狀態 |
| #87 | `bdddb9d`／`c1bb853`／`7c13a4b` | 同批重複變數名回 **400 並指名衝突**。⚠️ **「重複」不能用 `Set<String>` 判斷** —— 定序是 `SQL_Latin1_General_CP1_CI_AS`，PM 獨立實查確認 `Amount`/`amount`、`amount`/`amount `、全形`Ａ`/半形`A` 在 DB 層面就是衝突（`SELECT CASE WHEN N'Amount'=N'amount'` 回 1，INSERT 真的撞約束）。只做 `Set<String>` 的話**使用者最常見的失敗形狀擋不住** |
| #81 | `ec4f3a5`（已合併 `4112e99`） | `POST /api/forms` 的 `createdBy` 改由登入身分決定。⚠️ **後果比 backlog 描述嚴重**：實測**省略** `createdBy` 時原本存的是 `null` —— 不只是「可冒用」，而是**正常呼叫下這欄根本是空的，每一張經 `POST /api/forms` 建立的審核表都沒有作者**（`FormDefinition.createdBy` 無預設值、無 `nullable=false`，`FormService.create()` 完全不碰它）。與 `createNextDraft`（v2 有設）的不一致方向是「其中一條壞掉」。**但稽核從未被污染** —— `audit("FORM_UPDATE", userId, …)` 傳的一直是 `@CallerId`，與 #66／#72 的「operatorId 一起被冒用並被 hash chain 永久固定」性質不同，**不要用 #66 的嚴重性去描述它** |
| #80 | `28e44ef`（已合併 `29f9605`） | 三個端點接既有守衛（`bpmn-xml` 與 `documents/{id}` → `requireReadAccess`，`documents` 列表 → `requireSelf`）。**連帶修掉兩項原描述未提到的**：`GET /api/documents/{id}` 同樣零檢查（只修列表等於沒修）、`ExternalApiController` 啟動不存在 key 的裸 500（#69 條目自己指名留給本工項） |
| #83 | `b97e10a`／`e49ec46`（已合併 `29a7824`） | 新增 `ApplicantResolver` bean，3 個補件 UserTask 的 assignee 改用它。三段：`onBehalfOf` → `initiator`（是人的話）→ 權限碼 `bpm:external:revision` 指定的受理人。**找不到受理人時拋例外而非回 null**（回 null 是換一種方式製造同一個靜默卡死）。另修掉 `UnreachableTaskListener` 的告警繞過 |

**#83 的兩個關鍵設計**：

- **`TaskHolderGuard` 經 `git diff` 確認只有註解變更、零邏輯改動。** 這個工項最誘人的錯誤修法是「放寬持有者條件讓它能簽」——
  agent 沒有走，並在註解裡說明理由：根因能從路由層徹底修掉，就不必用授權放寬來換。
  PM 驗證過這個 diff，這是本輪最重要的一個「該拒絕的誘惑」。
- **候選人救不了這種任務**：`Flowable` 的 `taskCandidateUser` 帶 `ASSIGNEE_ IS NULL`，
  assignee 一旦非 null 候選人就看不到 —— **兩個條件互斥而非互補**。
  想靠加候選人來補救會完全無效。
- **`system:` 前綴的比對不區分大小寫**：`firstTaskAssignee` 是 body 裡自由指定的
  字串且無任何驗證，可以送 `SYSTEM:x` 繞過只比小寫的檢查。

**#83 的線上實測（決定性）**：
```
外部系統發起 → mgr001 退回 → 補件關卡 assignee = dir001（不再是 system:<id>）
→ 無關的 user002 簽 → 404（守衛未鬆）
→ dir001 簽 → 200，流程回到「主管審核」給 mgr001
```
**整條回路打通 = 案件不再靜默卡死的實證。**

**#80 的一個設計值得學**：它的 404 預先檢查**刻意排在 403 之後**。
順序顛倒的話，一個只被授權 `leave-approval` 的系統能用「403 變 404」
**枚舉伺服器上部署了哪些流程定義** —— 等於把授權檢查變成 discovery 工具。
PM 線上實測確認這個順序真的成立：`purchase-approval`（未授權）回 403，
且**不因流程存不存在而改變**；只有已授權但未部署的 key 才回 404。

**#80 的 (b) 政策決定（已實作，待追認）**：`/api/documents` 選**自己建立的**
而非「自己參與的」。放棄「全公司公文清單」與稽核旁路。關鍵前提是**沒有任何前端
呼叫這個端點**。日後若需要「我參與的公文」應**新增** `/api/documents/involved`，
**不要把這個放寬回去** —— 放寬回去看起來很合理，但安全上是退步。

**#81 的兩個值得記錄的細節**：

- **前端零風險**（與 #79 的 `CommentRequest.userId` 不同）：`grep -rn 'createdBy' bpm-frontend/src` 零命中，`FormEditor.vue:42` 送的是明確三欄物件。所以「server 決定」不會破壞任何現有呼叫。#79 當時刻意不改 `userId`，是因為前端**真的**在送 `'current_user'` —— 兩者的差異在於前端有沒有送那個欄位。
- **守衛必須排在 `formService.create()` 之前**：反過來的話「冒用 + formKey 已存在」會先回「已存在」，呼叫端以為換個 formKey 就送得出去。`PUT /api/forms/{id}` 刻意不加守衛 —— `FormService.update` 只搬 `name` 與 `schemaJson`，`createdBy` 本來就沒有可冒用的欄位。

**PM 的獨立驗證**（不依賴 subagent 報告）：
- `mvn verify` **450 全綠**（#79 的 17 + #87 的 11），容器停止狀態下跑
- 前端 **66 全綠**（基線 59 + 7）
- `acceptance-test.sh` PASS 7 / FAIL 0（⚠️ 容器剛重建、seed 完**立刻**跑會全失敗，
  再跑一次就過 —— 這是 seed 的時序問題，**不是改動引入**，但下一個人會踩到）
- #79：`user002`／`mgr002` → 404，**404 回應不含意見內容**；關係人與稽核職能 → 200；
  被拒的 POST 零痕跡
- #87：9 種形狀全部符合預期；#86 的行為無回歸
- #81（合併後主樹）：`mvn verify` **459 全綠**；線上實測省略 `createdBy` → `createdBy=mgr001`、
  冒用他人 → **400 且訊息指名該怎麼改**、送自己 → 200、無權限 → 403；
  **被拒的請求在資料庫查無、稽核也無 `FORM_UPDATE`**（不宣稱沒發生的變更）

---

## 10. 並行 subagent 的標準流程（2026-09-30 第二次並行，已落實）

上一輪的教訓（4.5）已轉成本輪的實際做法。**這一套流程可用，照它做。**

### 10.1 PM 在分派前

```bash
cd /Users/kywk/kywk/nanshan/greyhound
git status --short          # 必須乾淨，否則 worktree 的 base 是髒的
BASE=$(git rev-parse --short HEAD)
for n in 83 80 81; do git worktree add /tmp/gh-$n $BASE; done
git worktree list            # 確認每個 agent 一個
```

### 10.2 給 agent 的 prompt 必須包含的四件事

1. **工作目錄是 `/tmp/gh-<工項號>`，每個 Bash 呼叫都要自己 `cd` 過去**
   （shell 的 cwd 會重置 —— 這是最容易漏掉的一條）
2. **絕對不要碰主樹 `/Users/kywk/kywk/nanshan/greyhound`**
3. **不要 push**；不要 `git add -A`
4. **不要對你的 worktree 執行 `git worktree remove`**（PM 上一輪就是把
   別人的測試殺掉、拿到假結果）

### 10.3 🚦 資源限制必須寫進 prompt

8080 只有一個、Testcontainers 記憶體有限。**prompt 要明講**：

- **不要啟動 docker compose、不要做線上實測** → PM 統一做
- **不要 `docker compose stop`**（會影響其他 agent）
- **不要讀 `target/surefire-reports`** → 只讀 `mvn verify` 的 `Tests run: N`

即使 worktree 隔離了，**線上實測仍然不能並行** —— 8080 埠只有一個。
這是 worktree 解決不了的資源衝突，只能由 PM 序列進行。

### 10.4 PM 收回 agent 的成果時

- **不要**對 agent 正在用的 worktree 做 `git worktree remove`（見 10.2 第 4 點）
- 先在**主樹**驗證：`mvn verify` → 重建容器 → seed → acceptance-test → 線上實測
- 確認各 agent 的 commit 沒有互相捲入檔案：
  `git show --stat <commit>` 逐個看
- 確認沒有測試被刪掉（用乾淨 base 的 worktree 跑一次基線比對）
- 全部通過後才合併，然後 `git worktree remove`

### 10.5 驗證階段的兩個陷阱（PM 本輪實際踩到）

- **不要用 surefire 報告加總算測試總數**。報告檔不涵蓋 `@Nested` 內類別。
  PM 實測：逐類加總 416，實際 450，差 34 條。**只有 mvn verify 的輸出是真的。**
- **要確認「測試沒有被刪掉」**，做法是從乾淨 base 建一個 worktree 跑基線，
  與本輪 HEAD 的逐類測試數比對。差異只該出現在新增的類別上。

### 10.6 三輪並行的實際結果（2026-09-30）

共派了 7 個 subagent（#79 #87 #83 #80 #81 #88 #68），分三輪。**worktree 隔離有效**：
主樹全程零污染，線上實測由 PM 統一做也無衝突。唯一衝突出現在
`docs/backend-development-backlog.md`（兩邊各自更新相鄰的列），程式碼零衝突。

**agent 的品質特徵（值得作為下次的招募標準）**：

- **會主動揭露自己測試的無效之處**。#68 把 severity 改回 warning 發現只有 1 條紅，
  那條「部署 200」的測試**證明不了升級安全**（它證明的是「出廠 BPMN 不觸發規則」，
  改動前後都成立）—— 然後補了第二條測試把原因釘死。**這比測試全綠更有價值。**
- **會指出前一輪的漏洞**。#88 明確報告 #83 的 `UnreachableTaskListener` 修漏了
  「assignee 是空字串」這個同型實例（→ #89），而且**刻意沒有順手修**。
- **會拒絕看起來更省事的修法並寫明理由**。#88 保留了看起來多餘的前綴比對；
  #68 選「刪鍵再賦值」而非「換新物件」，理由是後者未來有人加欄位時同樣可能忘記。
- **會修正 backlog 裡既有的錯誤結論**。#68d 發現原本記的安全理由（「無 seed SQL」）
  只在乾淨庫成立，真正原因是另一回事。**結論不變、理由變了。**

**PM 端必做、agent 報告裡不能省的驗證**：

- 每個 agent 的 `git show --stat` 逐個看，確認**沒有互相捲入檔案**
- `git diff` 驗證**沒有改動前幾輪的成果**。實例：#88 動了 `ApplicantResolver`（#83 的），
  查證後確認只有註解更新、零邏輯變動；`TaskHolderGuard`（#74 的核心防線）也只有註解變更。
- **線上實測要包含「非空斷言」**：不只看狀態碼，要驗資料庫實際狀態。
  實例：#88 的六種被拒形狀全部在 `ACT_HI_PROCINST` 查無流程實例（無副作用）。

---

## 11. 現況一句話

`feature/round2-hardening` = `29a7824`（#86 #79 #81 #87 #80 #83 全部合併），**未 push**。
已完成 **#86 #79 #87 #81 #80 #83 #88 #89 #68(a/b/d)**、**#67 部分**。後端 **585** 測試全綠（**容器停止狀態下跑的**）、
前端 **87** 全綠、`acceptance-test.sh` PASS 7 / FAIL 0。工作樹乾淨。
**待裁決：#79-2、#87-2、#87-3（見第 8 節）。尚未部署。**
