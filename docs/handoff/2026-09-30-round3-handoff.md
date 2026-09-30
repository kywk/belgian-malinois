# 接手文件 — #86 完成後、#79／#87 進行中

**寫給下一個接手的實作 agent。** 撰寫時間 2026-09-30。
上一輪的交接見 `docs/handoff/2026-09-29-authorization-hardening-handoff.md`
（**那份仍然有效**，本檔只補充 2026-09-30 這一輪的新事實）與
`docs/handoff/2026-09-29-agent-handoff.md`。

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
| 測試 | 後端 **422** 個（Testcontainers：真實 MSSQL／RabbitMQ／Redis），前端 **59** 個（Vitest） |
| 分支 | `feature/round2-hardening`，比 `main` 多 4 個 commit，**未 push** |
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

### 4.5 兩個 agent 同時工作 → 用隔離 worktree
共用 working tree 時，一方在 `mvn verify` 期間改檔會汙染對方的建置。
解法：從 HEAD 建獨立 worktree 做負向控制，**主樹全程不動**。
（⚠️ 注意：併行跑多份 `mvn verify` 會各自起一組 Testcontainers，
24GB 機器上四份同時跑有記憶體風險。）

### 4.6 稽核的「多記而非漏記」是刻意取捨，不是缺陷
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
| #80 | `bpmn-xml` 與 `documents` 零檢查 | `bpmn-xml` 的 `activeIds` 洩漏「卡在哪一關」；`GET /api/documents` 不帶參數即 `findAll()` |
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

## 8. 現況一句話

`feature/round2-hardening` = `68de3ff`，比 `main` 多 4 個 commit，**未 push**。
#86 完成（`f6df3fb`）。後端 422 測試全綠（**容器停止狀態下跑的**）、
前端 59 全綠、`acceptance-test.sh` PASS 7 / FAIL 0。工作樹乾淨。
**#79 與 #87 進行中（兩個 subagent 並行）。尚未部署。**
