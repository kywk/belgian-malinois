# 接手文件 — 2026-10-04 #60／#61 跨服務整合（Wave I）

> ⚠️ **同日稍晚的 #41／#53（異常偵測、環境變數替換）見
> `docs/handoff/2026-10-04-round19-handoff.md`（最新）。** 本檔內容仍然有效。

**寫給下一個接手的 PM Agent。** 撰寫時間 2026-10-04（接續 round17，同日）。
上一輪交接見 `docs/handoff/2026-10-04-round17-handoff.md`（**仍然有效**：走查＋#28／#32／#35）、
`2026-10-03-round16-handoff.md`（#70 結案）、`round15`（Stage 5）、`round14`（Wave G）、
`round13`（Wave F）、`round12`（Wave E）、`round11`（Wave D）、`round10`（Wave C）、
`round9`（Wave B）、`round8`（Wave A）、`round7`、`2026-10-02-round6-handoff.md`、
`2026-10-01-round4-handoff.md`（環境陷阱總表）、`2026-09-30-round3-handoff.md`。

本檔只寫本輪新事實。兩個分支 `feature/60-atomic-start`／`feature/61-deploy-git` 已合併並清除；
`main` = `6d0866e`（＋文件收尾 commit）。

---

## 0. 現況

| | |
|---|---|
| 後端 | Spring Boot 4.1.1 + Flowable 8.0.0 + Jackson 3 + **JGit 7.8（#61）** |
| 前端 | Vue 3.4 + Vite 5 + Element Plus |
| 測試 | 後端 **1321**（`mvn clean verify`）、前端 **206**（Vitest） |
| `main` | `6d0866e`（本輪 2 merge）＋文件收尾 |
| push | ⚠️ `nsl` 仍不可達；累積約 **105 顆 commit** 在本機 `main`，恢復後 `git push nsl main`（**永遠不要裸 push**） |
| worktree | 全部清除 |
| 容器 | dev 容器跑本輪 build（**Git 版控已還原預設關閉**）；health 200；acceptance 7/0 |
| DB | dev core schema v8；Flowable schema 8.0.0.0（不可逆） |

---

## 1. 本輪完成

### 1a. #60 流程啟動完整流程（merge `6d0866e`）

- `POST /api/process-instances` 新增可選 `formData{formDefinitionId,dataJson}`：
  - **`formDefinitionId` 先當定義 id 查、查不到再當 formKey 查最新 published**
    （前端只知道 BPMN 的 formKey；直接當 id 會讓 validator 依 #55 缺口靜默略過整包驗證）。
  - schema 驗證（`FormSchemaValidator`，與 `/api/form-data` 同一份）→ 400 指名欄位、**零副作用**。
  - `dataJson` 欄位推導成流程變數（spec §8.5）；逐 key 過既有 deny-list；**與顯式 `variables`
    的欄位重疊 → 400**（同一欄位不可兩處都給）。
  - 表單寫入**最後**（form tx 先 commit）；form save 失敗 → 往外丟、主交易回捲（案件不存在）；
    form save 成功但主交易 commit 失敗 → `afterCompletion(STATUS_ROLLED_BACK)` best-effort
    刪除該列（`FormService.deleteDataById`）。**無 XA**，取捨見 `ProcessController.startProcess` javadoc。
  - 回應新增 `formDataId`。
- 前端 `StartProcess.vue`：改送 `formData`（formKey＋dataJson），不再送 variables；dateRange 維持
  `"起~訖"` 字串。
- 舊呼叫端（只帶 variables）**完全不變**（回歸測試釘住）。

### 1b. #61 BPMN 部署 Git commit（merge `c5b5b6d`）

- 新 `DeploymentGitCommitter`（JGit；pom 加 `org.eclipse.jgit:org.eclipse.jgit:7.8.0.202609011348-r`）。
- 設定 `bpm.bpmn.git.enabled`（**預設 false**）／`repo-dir`（預設＝`bpm.bpmn-definitions-dir`）／
  `author-email`。未啟用時**完全不碰 repo 目錄**。
- 啟用時順序：lint → 寫檔 → **Git commit（失敗 → 503、完全不呼叫 Flowable deploy）** →
  Flowable deploy → 稽核（`gitCommit` short id，有真的 commit 才記）。
- repo 不存在 → `Git.open` 失敗才 `Git.init`（linked worktree 的 `.git` 是檔案，不能只看目錄）；
  `setAllowEmpty(false)`＋catch `EmptyCommitException`（**JGit 預設允許空 commit 且 call() 不回 null**
  —— 這是前一版實作的真 bug，已修）；同內容重送不新增 commit。
- 已知殘餘：**本地 repo、無 remote、不 push**（CI/CD 仍缺，R-07／R-08）；controller 的寫檔在
  committer 鎖之外（同檔名並行部署時 message 的 sha 可能與 tree 內容不一致——既有「磁碟 vs 稽核
  sha」競態的延伸）；多實例共用工作目錄不支援（鎖只鎖行程內）。

---

## 2. 線上實測（dev 容器，2026-10-04）

| 項目 | 結果 |
|---|---|
| 熱啟動（Git 開啟） | health 200；seed 部署 3 支 BPMN → `bpmn-definitions/.git` 自動 init＋**3 筆 commit**（message 含 `xmlSha256`、reflog 鏈完整） |
| acceptance（Git 開啟） | PASS 7 / FAIL 0 |
| #60 正向 | `formData` 啟動 leave-approval → 200、`formDataId`、`currentTask=主管審核`；`GET /api/form-data/{pid}` 一列、`formDefinitionId` 為解析後 UUID |
| #60 負向 | 缺必填 → 400；formData 與 variables 重疊 → 400 |
| #60 legacy | 只帶 variables → 200 |
| 收尾 | dev 容器已重建為**預設（Git 關閉）** |

---

## 3. 已知殘餘（本輪新增）

| 殘餘 | 說明 |
|---|---|
| #60 前端瀏覽器實送 | 未做（payload 有 2 條單元測試、後端線上實測完成）；StartProcess 仍是硬編表單，未改 DynamicForm |
| #60 無 `FORM_SUBMIT` 稽核 | 啟動路徑只發 `PROCESS_START`；表單列的 `submittedBy` 可追。若要比照 `/api/form-data` 記稽核，屬後續工項 |
| #60 `STATUS_UNKNOWN` 窗口 | commit 階段不可翻譯的例外不補償（無法分辨 commit 是否成功），已寫進 javadoc |
| #60 draft-only formKey | 只有 draft、無 published 時沿用原值並略過驗證（#55 既有缺口） |
| #60 併發 | 未測同時啟動／同時改版表單 |
| #61 CI/CD | Git commit 有了；pipeline 仍缺（R-07／R-08） |
| #61 無 remote／不 push | 版控在本地工作目錄（prod 需持久化且可寫） |
| #61 `.gitignore` 排除部署檔 | JGit 會略過 add、log 顯示「內容相同」不 commit——不壞但版控靜默失效，巡檢項 |

---

## 4. 剩餘工項（統計 ~19 人天）

| 類別 | 工項 | 估時 |
|---|---|---|
| Org/Perm | #8（3d）／#9（2d）——**等真實權限中心**，目前無法完成 | 5d |
| 稽核 Log | #41 異常操作偵測 | 3d |
| 基礎設施 | #53 BPMN 環境變數替換（有 secrets 政策面） | 1d |
| 測試覆蓋 | #63 單元測試（5d）／#64 整合測試（5d） | 10d |

---

## 5. 建議的下一輪優先序

| 順序 | 工項 | 估時 | 備註 |
|---|---|---|---|
| 1 | **#41／#53** | 3d／1d | #53 需先定 secrets 政策 |
| 2 | **#63／#64 測試覆蓋** | 5d／5d | 上線準備 |
| 3 | #8／#9 | 5d | 阻塞：需真實權限中心 |

**部署前檢查清單（沿用＋新增）**：權限碼指派；prod secrets；backfill（R-21／R-24）；
callback secret rotate；worker topic 專屬化；#51 告警收件人；JVM／limits 依 GC log 調；
表單 schema 修復；Teams webhook URL 設定（#32）；**BPMN Git 版控（#61）：prod 開
`BPM_BPMN_GIT_ENABLED=true`＋持久化可寫的 `bpmn-definitions`＋repo 巡檢**；
prod 映像已是 Boot 4／Flowable 8／Jackson 3。

---

## 6. 統計

- 工項 **97**：✅ **91**、🟡 **4**、⬜ **2**，剩餘估時上限 **~19 人天**。
- 後端 **1321**；前端 **206**；`acceptance-test` PASS 7 / FAIL 0。
