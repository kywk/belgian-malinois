# Round handoff — uni-form 表單重構（2026-10-12～13）

> **給下一位接手人**：本輪把整個表單系統（UI 與資料）從自製 flat schema 換成 **uni-form SDK**。
> 權威施工文件：[`docs/plan/2026-10-12-uniform-form-refactor.md`](../plan/2026-10-12-uniform-form-refactor.md)
> （凍結介面 §五、決策 §三、施工紀錄 §四）。下一批工作：[`2026-10-13-next-agent-prompt.md`](2026-10-13-next-agent-prompt.md)。

## 1. 一句話

表單定義改存 **uni-form DSL v1**；審核／補件權限以 BPMN **`uniform:access`** 表達；
前端 **UfRenderer／UfDesigner**；後端 **Envelope＋`validateV1`**；3 個 subagent 分工、PM 整合，
端到端 13/13、`mvn verify` 1677 全綠。

## 2. 交付與證據（全部實跑）

| 驗證 | 結果 |
|---|---|
| `cd bpm-core && mvn -o verify` | **1677 tests、0 failures／0 errors／0 skipped、BUILD SUCCESS**（Testcontainers 真 MSSQL／RabbitMQ／Redis） |
| `cd bpm-frontend && npm run test` / `npm run build` | **25 檔 259 tests 全綠** / build 成功 |
| **E2E（localenv `:18110`）** | **13/13 PASS**：preview → 啟流程 → 審核（view／全唯讀／`dateRange` 物件／version 鎖定）→ 退回 → 補件（fill／`reason` editable／`leaveType` readonly）→ 竄改 readonly 400 → 重送 → FormData 版本化（新列在前、readonly 值保留）→ 核准結案 |
| `scripts/acceptance-test.sh` | **PASS 7 / FAIL 0** |
| **fallback 容器路徑 smoke** | `docker compose build bpm-core`（容器內從 pinned submodule 建 SDK）→ up → HEALTH 200 → seed → **E2E 13/13（:8080）** → `down -v`；零殘留、不需主機 `~/.m2` |

## 3. 主要變更

- **內容**：`form/V7` migration —— `leave-request`／`purchase-request` 改存 DSL v1、`leave-review`／
  `purchase-review` 廢除；三支 BPMN（`leave-approval`／`purchase-approval`／`countersign-review`）
  加 `uniform:access`（repo 與 classpath 副本 byte-identical）。
- **後端**（`com.bpm.core.uniform`）：`FormAccessResolver`（fail-closed）、`FormContextResolver`（版本／
  最新列唯一份）、`FormEnvelopeService`（`projectV1`）、`FormSubmissionService`（`validateV1`）、
  `FormDefinitionValidator`（DSL schema＋compileStatic＋value schema＋`key==formKey`）；
  新端點 `GET /api/tasks/{id}/form`、`GET /api/process-definitions/{key}/start-form`、
  `POST /api/forms/preview`；提交統一走 `formData`（`POST /api/process-instances`、`PUT /api/tasks/{id}`）；
  移除 `FormSchemaValidator` 與 `POST/PUT /api/form-data`。
- **前端**：`src/uniform/`（plugin／Element Plus widgets／api）；`DocumentDetail`（Envelope 驅動、
  載入失敗 fail-closed 不露任務動作）、`StartProcess`（process-definitions 驅動）、`FormEditor`
  （UfDesigner＋preview dialog）；BPMN 面板「表單權限」＋moddle；`DynamicForm` 系全刪。
- **環境**：`scripts/localdev.sh`（**localenv 優先**、偵測不到才 fallback）；`Dockerfile` 建置改從
  `vendor/uni-form`（pinned submodule）建 SDK（快取鍵=submodule commit）；`.gitlab-ci.yml` docker build
  指令對齊；`greyhound.env` JWT dev secret 修正（原 22 bytes 不足 HS256 下限）。

## 4. 本輪修掉的真 bug（都在 E2E／合併時抓到）

1. **`FormDefinitionValidator` 未注入 value schema** → 含 `dateRange` 的表單無法存檔／發布
   （`b21c138`，BE 首輪測試只用 textarea 沒踩到）。
2. **`uniform:field` 空列**（BPMN 面板新增未填的列）會被整份 fail-closed → 改為略過該列；
   有 key 但屬性非法仍 fail-closed（`01afee4`）。
3. **DocumentDetail 任務動作 fail-open**：Envelope 未載入時會露出審核按鈕 → fail-closed（`9b79c5c`）。
4. **FormList→FormEditor 載入路徑**：原本帶定義 UUID（`GET /api/forms/{formKey}` 查不到）→ 改帶
   `formKey+version`（`9b79c5c`）。
5. **uni-form main 迴歸（非本案）**：`uniform-jackson3` 的 `FullV1FixtureSmokeTest` 遞迴掃描誤收
   opt-in 的 `core/v2/richtext.manifest.json` → `mvn install` 失敗。已在 uni-form repo 修
   **分支 `fix/jackson3-fullv1-smoke-v2`**（`43bf983` 修正＋`9025fd7` 報告），全 reactor verify PASS。

## 5. Commits

- greyhound 分支 **`uni-form`**（base `5b81ffb`＝nsl/main）：**22 commits、98 檔、+7352/−3215**。
  重點：`0d07772`（submodule pin＋seeds＋BPMN）→ 三分支合併（`b2fe722`／`051062a`／`98f0ec2`）→
  `9b79c5c`／`01afee4`／`b21c138`（PM 收斂與修正）→ `3e2c9c9`／`14875e9`／`523ad3e`（localenv／
  容器建置／文件）。
- 完整清單：`git log --oneline 5b81ffb..uni-form`。
- 三分支 worktree 仍在（`~/workspace/.worktrees/greyhound/uniform-{be,fe,bpmn}`，已全數合併進
  `uni-form`）；合併後是否清理由 PM 決定。

## 6. 未完成與風險

| 項目 | 說明 |
|---|---|
| **瀏覽器視覺驗收** | **NOT_RUN**（本 session 無瀏覽器工具）。跑 `./scripts/localdev.sh frontend` → http://localhost:3000（proxy 已指 18110）手動走一遍：設計器（儲存／發布／預覽）、填寫、審核、補件 |
| **uni-form pin bump** | 待 uni-form 接受 `fix/jackson3-fullv1-smoke-v2` 後：更新 `vendor/uni-form` gitlink → 重跑兩端 |
| **CI 取得 submodule** | `.gitmodules` 仍是本機 file URL；CI 要能 `GIT_SUBMODULE_STRATEGY: recursive` 並指定可取得的 Git URL（R-07 一起處理） |
| **P5 殘餘** | 動態選項（OptionsProvider，`GET /api/forms/options` 退場）、`file`／FileVault、richtext、`bpm:` pack、BPMN lint 檢查 access 引用的 field key（見下一批 prompt） |
| **舊殘餘狀態更新** | #58（PUT 不驗）已隨端點移除解決；#56（optionsUrl）已從設計器退場（端點暫留）；#60 以新流程重驗（瀏覽器 NOT_RUN）；其餘（#47／#45／#28／#22／#32／#97）不變 |
| **環境記錄** | 本輪遇過 localenv `secrets.env` 於 10-12 16:38 被重生造成 infra 密碼不一致：已還原 mssql／redis、並把 4 個 pg roles 對齊現行 secrets（cms／authz 重啟），現行 `secrets.env` 為唯一真相 |

## 7. 給下一位的閱讀順序

1. 本檔 → 2. `docs/handoff/README.md` §0 快照與 §3 未完成總表 → 3. `2026-10-13-next-agent-prompt.md`
→ 4. `docs/plan/2026-10-12-uniform-form-refactor.md`（動手改表單前**必讀**）
→ 5. `vendor/uni-form/agent-kit/llms.txt`（SDK 入口總表）。
