# 下一批工作 — 可直接派工的 prompt（2026-10-13）

> **狀態更新**：**uni-form 表單重構已交付**於分支 `uni-form`（E2E 13/13、`mvn verify` 1677、前端
> 259；見 [`2026-10-13-uniform-form-refactor-handoff.md`](2026-10-13-uniform-form-refactor-handoff.md)）。
> remediation B 批次（R-21～R-25）已於 2026-10-05 完成。**動手改表單前必讀**
> [`docs/plan/2026-10-12-uniform-form-refactor.md`](../plan/2026-10-12-uniform-form-refactor.md)。
> 本檔把 `docs/handoff/README.md` §3 的未完成事項整理成**可直接貼給 subagent 的 prompt**；
> 建議順序：**A（PM 營運）→ B（uni-form 後續 P5）→ C（remediation 殘餘）→ D（功能殘餘）**。

---

## §0 共通規範（每個 subagent prompt 都要含）

```
## 工作環境（每條都必須遵守）
- 工作目錄：<worktree 路徑>（獨立 branch，base <commit>；本專案慣例：~/workspace/.worktrees/greyhound/<task>）。
  每個 Bash 呼叫自己 cd 過去；主樹 /Users/kywk/Dropbox/nanshan/greyhound 是 PM 的，絕對不要碰。
- 不要 push、不要 `git add -A`；commit 前 `git status --short`，只 commit 自己要的檔案。
- `vendor/uni-form` 是 pinned submodule（只讀）。要改 SDK 一律回報 PM —— SDK 的變更在 uni-form repo
  另開 worktree，禁止在 submodule 內直接開發。
- localenv 是本機環境的權威（~/nanshan/scripts/localenv）：起服務用
  `bash ~/nanshan/scripts/localenv.sh greyhound ready`；看狀態 status／日誌 logs。
  不要停別人的服務；不要用 docker-compose 起全組（fallback 僅供無 localenv 的機器）。
- Maven 前先：`export DOCKER_HOST=unix://$HOME/.orbstack/run/docker.sock; export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`。
- docs/backend-development-backlog.md、docs/history/** 不准動；docs/plan/**、docs/bpm-platform-spec.md 只讀（PM 統一改）。
- 註解密度與風格比照 repo（繁中、解釋「為什麼」）。完整套件由 PM 跑；你跑自己受影響的測試類別。
- 每個工項至少 1 條負控（改壞 → 紅 → 還原 → 綠）。
- 回報格式：落地與設計差異／檔案清單／測試指令與結果／負控／行為差異觀察／沒做到與不確定（誠實揭露不足比全綠重要）。
```

**PM 前置**（每批）：從最新 `docs/handoff/README.md` 確認現況 → 建 worktree → 派工 →
收工後跑 `mvn verify`＋前端 `npm test`＋`scripts/localdev.sh seed`＋`acceptance` → 合併 →
更新 `docs/handoff/README.md` 與當輪 handoff → push `nsl uni-form`（或依使用者指示的目標）。

---

## A. PM 營運收尾（不需 agent；PM／人為操作）

1. **uni-form fix 接受與 pin bump**：uni-form repo 分支 `fix/jackson3-fullv1-smoke-v2`
   （`43bf983` 修正＋`9025fd7` 報告、base `89708ef`）已推 origin。接受並合併後：
   `cd vendor/uni-form && git fetch && git checkout <新 main SHA>` → 主 repo `git add vendor/uni-form`
   更新 gitlink → 記錄新 pin → 重跑 `mvn -o verify`＋前端測試＋E2E。
   （若 uni-form 決定不合併，改以本機 pin 為準並在本檔註記。）
2. **CI 取得 submodule**：`.gitmodules` 目前是本機 file URL；指定可取得的 Git URL＋
   `GIT_SUBMODULE_STRATEGY: recursive`（GitLab CI 設定）。容器建置已可從 submodule 建 SDK
   （見 `bpm-core/Dockerfile`），CI 只缺「取得 submodule」這一步；與 R-07 合批處理。
3. **瀏覽器視覺驗收（本輪 NOT_RUN）**：`./scripts/localdev.sh frontend` → http://localhost:3000，
   走：設計器（編輯→儲存→發布→預覽）、填寫（送出／缺必填）、審核（全唯讀）、補件（只有事由可改）、
   退回／拒絕。任何發現以 issue 形式回報（附路徑與最小重現）。
4. **部署前檢查清單**（沿用 `round21 §4`＋`round22 §5`）：prod secrets、Teams webhook、
   `BPM_BPMN_GIT_ENABLED`、`bpm_external_system` 盤點、`API_KEY_HMAC_SECRET` 等。
   **新增**：prod 需要 uni-form jar 的取得方式（建議 CI 從 submodule 建置；`uniform-spring-boot4-starter`
   ＋`uniform-jackson3`＋`uniform-spec`＋`uniform-core` 四顆）。

---

## B. uni-form 重構後續 P5（每項獨立可並行）

| 優先 | 項目 | 估時 | 一句話 |
|---|---|---|---|
| B1 | **動態選項 OptionsProvider** | 1.5d | `optionsSource`＋`host:` provider；`GET /api/forms/options`（optionsUrl 代理）退場或轉為 provider；設計器開放「選項來源」設定 |
| B2 | **file 欄位（FileVault）** | 2d | `file` widget 可填：宿主 upload adapter＋`FileStore.stage/verify/bind`；`validateV1` 的 file 證據；附件生命週期（purge） |
| B3 | **richtext 欄位** | 1.5d | `uniform-tiptap` 接入＋`content:richtext` 固定內容；後端 `RichTextService` 重驗；設計器 palette 開放 |
| B4 | **`bpm:` pack（orgSelector 等）** | 2d | manifest＋Vue widget＋Java hook（org／user 選擇；MockOrg 提供資料）；不需要的型別不進 palette |
| B5 | **BPMN lint＋面板強化** | 1d | lint：`uniform:access` 引用的 field key 必須存在於該 formKey 最新 published 的 DSL（fail 或 warn 需拍板）；面板 field key 改下拉（來源＝表單定義） |

範例 prompt（B1；其餘照同型改寫，範圍以計畫 §八與本表為準）：

```
你是 Greyhound BPM 平台的實作 agent，負責 **uni-form P5-B1：動態選項 OptionsProvider**。
背景：uni-form 重構（docs/plan/2026-10-12-uniform-form-refactor.md）後，設計器 palette 不含
optionsSource；舊功能 optionsUrl（後端代理，GET /api/forms/options，FormOptionsService）
仍在但已無設計器入口。SDK 的正確形狀是節點 optionsSource={provider:"host:...",params,dependsOn}
＋前端 options.load adapter＋後端 OptionsProvider（寫入時 resolve 驗證＋$labels 快照）。
範圍：bpm-core（OptionsProvider 註冊與 resolve 接線、Envelope behaviorRegistry 的 providers 清單、
admin 或設定來源）、bpm-frontend（options.load adapter、設計器開放設定）、必要的 spec 只讀。
驗收：靜態＋remote 選項案例（遠端優先／fallback 語意沿用舊行為或明示改變）；resolve 擋非法值；
負控；E2E 至少一條（選項載入成功與失敗）。
<共通規範 §0>
```

---

## C. Remediation 殘餘（沿用 2026-10-04 prompt，僅註記變化）

| 編號 | 項目 | 人日 | 變化 |
|---|---|---|---|
| R-04 | Secrets 治理（步驟 2–5） | 0.5 | 不變；`greyhound.env` 等 localenv 端已完成對齊 |
| R-07 | CI/CD 收斂成單軌 | 2 | **新增**：CI 取得 submodule（strategy＋URL，見 A2）；其餘同原 prompt |
| R-08 | BPMN-as-code T2–T5 | 2 | 不變（ADR-002）；T2 待 R-07 runner 前提 |
| R-15 | Spotless＋ESLint/Prettier | 1 | 不變（大 diff，獨立 commit） |
| R-16 | 根目錄 README.md | 0.5 | 內容需納入 uni-form 重構後的架構（新增 API 入口段） |

> 原 prompt（R-04／R-07／R-08／R-15／R-16 的完整描述）見
> [`2026-10-04-next-agent-prompt.md`](2026-10-04-next-agent-prompt.md) §C／§E。

---

## D. 功能殘餘（狀態已依 uni-form 重構更新）

| 項目 | 狀態 | 說明 |
|---|---|---|
| #58 PUT 不驗 schemaJson | **已解** | `PUT /api/form-data/{id}` 已移除；`PUT /api/forms/{id}` 有 DSL gate |
| #56 optionsUrl 存檔不驗 | **退場** | 設計器不再提供 optionsUrl；端點暫留（B1 一併處理退場） |
| #60 瀏覽器實測 | **重驗** | StartProcess／DocumentDetail 已重寫；瀏覽器驗收見 A3（NOT_RUN） |
| #47 設計器動態指派運算式 | 開放 | 0.5d；`/bpm-frontend/src/bpmn/` |
| #45 ESign resultVariable 解析 | 開放 | 0.5d |
| #28 webhook headers 自訂 | 開放 | 1d |
| #22 外部 worker 殘餘（UI／topic） | 開放 | 1d |
| #32 通知設定前端管理頁 | 開放 | 2d |
| #97 DNS rebinding TOCTOU | 開放 | 0.5d（需設計） |

---

## 備註

- **表單類變更的紅線**（重構後）：DSL 是唯一格式（舊 flat `fields[]` 不再被接受）；DSL `key` 必須
  等於 `formKey`；`hidden` 欄位的定義與值不下送；後端永遠以 `validateV1` 重驗；`uniform:access`
  缺省 fail-closed（view／readonly）。詳見計畫文件 §三／§五。
- 每批完成後更新 `docs/handoff/README.md` §3 勾消狀態與當輪 handoff，保持「未完成總表」是唯一入口。
