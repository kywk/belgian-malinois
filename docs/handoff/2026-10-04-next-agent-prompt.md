# 下一批工作 — 可直接派工的 prompt（2026-10-04）

本檔把 `docs/handoff/README.md` §3 的未完成事項整理成**可直接貼給 subagent 的 prompt**。
建議順序：**A（PM 營運）→ B（remediation P1）→ C（CI/CD，先問平台）→ D（功能殘餘）→ E（工程衛生）**。
每個 prompt 都假設「一工項＝一 worktree／一分支」；共通規範見 §0，派工時**整段貼進 prompt 開頭**。

---

## §0 共通規範（每個 subagent prompt 都要含）

```
## 工作環境（每條都必須遵守）
- 工作目錄：<worktree 路徑>（分支 <branch>，base <main commit>）。每個 Bash 呼叫都要自己 cd 過去。主樹 /Users/kywk/kywk/nanshan/greyhound 是 PM 的，絕對不要碰。
- 不要 push、不要 `git add -A`；commit 前 `git status --short` 檢查，只 commit 自己要的檔案。
- 不要啟動 docker compose（除 prompt 明示）、不要線上實測（PM 做）。可以跑 Maven（含 Testcontainers）。
- Maven 前先：`export DOCKER_HOST=unix://$HOME/.orbstack/run/docker.sock; export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`。
- `docs/backend-development-backlog.md`、`docs/history/**` 不准動；`docs/plan/**`、`docs/bpm-platform-spec.md` 只讀（PM 統一改）。
- 註解密度與風格比照 repo（繁中、解釋「為什麼」）。完整套件由 PM 跑；你跑自己受影響的測試類別。
- 每個工項至少 1 條負控（改壞 → 紅 → 還原 → 綠）。
- 回報格式：落地與設計差異／檔案清單／測試指令與結果／負控／行為差異觀察／沒做到與不確定（誠實揭露不足比全綠重要）。
```

**PM 前置**（每批）：從最新 `docs/handoff/README.md` 確認現況 → 建 worktree → 派工 →
收工後跑 `mvn clean verify`＋前端 `npm test`＋熱啟動（seed＋acceptance）→ 合併 → 更新
`docs/handoff/README.md` 與當輪 handoff → `git push nsl main`（若可達）。

---

## A. PM 營運收尾（不需 agent；PM／人為操作）

1. **push**：確認 `nsl`（10.127.42.141）可達後執行 `git push nsl main`（**永不裸 push**；
   若使用者另有指示再照辦）。目前約 120 顆 commit 待推。
2. **部署前檢查清單**（`round21 §4`）逐項核對，特別是：
   - prod secrets：`OIDC_ISSUER_URI`、DB 密碼、`ORG_AUTH_TOKEN`／`PERM_AUTH_TOKEN`、
     webhook HMAC、callback secret rotate。
   - `BPM_BPMN_GIT_ENABLED=true`＋持久化可寫的 `bpmn-definitions`。
   - Teams webhook URL＋`WebhookUrlPolicy` 白名單。
   - **R-21 的 DB 盤點**：`bpm_external_system.allowed_process_keys` 在正式環境的實際值
     （無 seed SQL；空值＝不限制，fail-closed 可能中斷服務）。
3. **backfill**（R-20／R-24）：`_externalSystemId` 與 businessKey 查詢的一致性資料修補。

---

## B. Remediation P1（建議 2 個 agent 並行）

### B1 — R-21＋R-22：外部系統授權設定寫入端驗證＋proxy 下的 IP 白名單
> 估時 1.5d。相依：無。檔案：`external/ExternalSystemAdminController.java`、
> `external/ExternalApiAuthFilter.java`、`config/`、`application.yml`、
> `bpm-frontend/src/views/ExternalSystemAdmin.vue`。

```
你是 Greyhound BPM 平台的實作 agent，負責 remediation **R-21＋R-22**（見
docs/plan/2026-09-28-remediation-backlog.md）。

## R-21：授權設定的驗證移到寫入端（1d）
- 現況：allowedProcessKeys 空＝不限制，admin UI 自由文字、無必填 → 照 UI 建立的外部系統
  預設可啟動任何流程；allowedActions 由 checkbox 產生 "[]"＝全拒，兩者安全語意相反。
- 做法：ExternalSystemAdminController 的 create/update 對三個欄位（allowedProcessKeys／
  allowedActions／allowedCandidateGroups／allowedWorkerTopics）做格式驗證（JSON 解析失敗 400、
  訊息指名欄位）；allowedProcessKeys 強制非空且每個 key 必須是已部署的流程定義；
  前端 ExternalSystemAdmin.vue 改多選＋required。
- 驗收：400 零副作用；既有外部系統測試不回歸；前端 Vitest 綠；負控（拿掉驗證 → 測試紅）。

## R-22：IP 白名單在容器／nginx 後失效（0.5d）
- 現況：ExternalApiAuthFilter 用 request.getRemoteAddr()，nginx 後取到 nginx 容器位址；
  填真實 IP 全擋、填 nginx IP 全放行；稽核 ip 也失去鑑識價值。
- 做法：設定 server.forward-headers-strategy（先讀 infra/nginx/nginx.conf 的 X-Real-IP／
  X-Forwarded-For 行為再定案），讓 getRemoteAddr 取得真實 client IP；確認信任邊界
  （不可無條件信任任意來源的 XFF）；錯誤訊息不再回顯內部 proxy 位址。
- 驗收：整合測試用帶 X-Forwarded-For 的請求驗證白名單判定與稽核 ip；負控。

<共通規範 §0>
```

### B2 — R-24＋R-25：擁有權查詢一致＋API Key 強化
> 估時 1.5d。相依：無（backfill 與 PM 確認後才做）。檔案：`external/ExternalApiController.java`、
> `external/ApiKeyUtil.java`、`external/ExternalSystem.java`、migration、`external/ExternalSystemPolicy.java`。

```
你是 Greyhound BPM 平台的實作 agent，負責 remediation **R-24＋R-25**。

## R-24：queryByBusinessKey 與擁有權模型一致（0.5d）
- 現況：GET /api/external/process-instances 仍以 variableValueEquals("initiator", "system:"+id)
  篩選（漏查舊實例；且篩選同時兼任授權，拿掉即洩漏）。
- 做法：改用 _externalSystemId 篩選，並抽一個明確的擁有權檢查函式（授權與篩選分離）；
  backfill 由 PM／維運決定（寫進回報，不在本項自動跑）。
- 驗收：自訂 initiator 的舊實例語意明確（回報決策）；惡意 system:victim 注入已不成立（R-20 已擋）
  仍要有測試釘住；負控。

## R-25：API Key 機制強化（1d）
- 現況：無 salt 單輪 SHA-256；無有效期／多金鑰並存（rotate 即中斷）；無速率限制。
- 做法（保守版，避免改壞既有 key）：HMAC-SHA256(server secret, key)＋可查詢性；
  rotate 支援 grace period（新舊並存到舊 key 到期）；驗證失敗的節流（既有稽核之上）。
  ⚠️ 既有 DB 內是舊格式 hash：需設計可辨識前綴（如 v2:）與一次性遷移策略，先讀
  ApiKeyUtil 與所有使用點再定案。
- 驗收：舊 key 驗證路徑不回歸（必要時雙讀）；新格式測試；速率限制測試；負控。

<共通規範 §0>
```

---

## C. CI/CD（R-07＋R-08）— **先問平台再開工**

> 決策點：保留 GitHub Actions 還是 GitLab CI？（目前兩套並存、deploy job 都是 `echo`。）
> 問到答案後，把下面的 `<平台>` 換掉再派工。估時 3d。

```
你是 Greyhound BPM 平台的實作 agent，負責 remediation **R-07＋R-08**（CI/CD 收斂）。

## R-07：CI/CD 收斂成單軌（2d）
- 保留 <平台>，刪除另一套；補上真實 deploy 步驟（非 echo）；registry 命名與
  docker-compose.prod.yml 一致；修掉 GitLab 若保留時 build/test 重複跑 mvn 的問題。

## R-08：BPMN 部署 stage 的 no-op（1d）
- 現況：CI 掃 bpmn-definitions/**（目錄不存在，BPMN 在 jar 內）；cicd/envs/*.yml 的 shell
  假 YAML parser 對含冒號的值解析錯誤、只過濾 ^bpmn\.。
- 選 A（推薦）：建立 bpmn-definitions/ 把 BPMN 移出 jar（BPMN-as-code），env 替換改用
  yq 或 Python；或選 B：刪掉兩個 deploy job 與 BPMN 佔位，承認 BPMN 隨 jar 部署。
  ⚠️ 選 A 會連動 #61 的 Git 版控與部署來源，先讀 DeploymentController 與 .github/.gitlab 檔。
- 驗收：不存在「執行成功但什麼都沒做」的 stage；選 A 時 CI 真的能部署 BPMN 檔。

<共通規範 §0>
```

---

## D. 功能殘餘（依優先序挑；每項可獨立派工）

| 優先 | 項目 | 估時 | 一句話 |
|---|---|---|---|
| D1 | #47 設計器產生 `dynamicAssignee` 運算式 | 0.5d | `FormProps`/`BpmnEditor` 產生 `${dynamicAssignee.managerAtLevel(execution, N)}` 等，取代手改 BPMN |
| D2 | #45 ESign `resultVariable` 解析 | 0.5d | 從供應商回應抽出 requestId（目前存原字串） |
| D3 | #28 `headers` 自訂（spec §11.4） | 1d | webhook 設定加 headers map＋安全政策（哪些 header 允許） |
| D4 | #22 外部 worker 殘餘 | 1d | UI `external_worker` 勾選；complete／fail／unacquire 查 topic 白名單 |
| D5 | #32 通知設定前端管理頁 | 2d | notify-configs CRUD UI（含 teams webhookUrl 遮蔽語意） |
| D6 | #60 前端 E2E＋`FORM_SUBMIT` 稽核 | 0.5d | StartProcess 瀏覽器實送；啟動路徑補稽核（與 /api/form-data 對齊） |
| D7 | #56／#58 殘餘 | 1d | `options` 保留字；`optionsUrl` 存檔驗證；PUT schemaJson 驗證 |
| D8 | #97 DNS rebinding TOCTOU | 0.5d | 解析後鎖 IP／連線層防護（需設計） |

範例 prompt（其餘照同型改寫）：

```
你是 Greyhound BPM 平台的實作 agent，負責 **#47：設計器產生 dynamicAssignee 運算式**。
背景：#47 的 dynamicAssignee bean 已存在（managerAtLevel／firstAvailable／managerWithPermission，
見 docs/handoff/2026-10-03-round14-handoff.md §3），但前端設計器不會產生運算式，使用者需手改 BPMN。
範圍：bpm-frontend/src/bpmn/（Properties Panel 的指派設定）＋必要時 spec 只讀；
運算式形狀以 DynamicAssigneeResolver 的實際方法簽章為準；EL 方法白名單（#35）已擋非法方法。
驗收：前端 Vitest（含新測試：三種模式各產生正確運算式）；產生的 BPMN 能過 lint；
不破壞既有指派選項；負控（產生器改壞 → 測試紅）。
<共通規範 §0>
```

---

## E. 工程衛生（可合批，1～2 個 agent）

| 項目 | 估時 | 內容 |
|---|---|---|
| R-04 步驟 2–5 | 0.5d | `application.yml`／`docker-compose.yml` 的 dev 密碼與 `bpm-webhook-secret` 字面值改 env（無預設、缺即失敗）；`cicd/.env.example` 同步；驗收 `grep` 零命中 |
| R-15 | 1d | Spotless（Java）＋ESLint/Prettier（前端）＋`.editorconfig`，納入 CI；**先格式化一次會產生巨大 diff，建議獨立 commit 並在離峰做** |
| R-16 | 0.5d | 根目錄 `README.md`（動機、架構、啟動、測試、文件索引） |

```
你是 Greyhound BPM 平台的實作 agent，負責 **R-04 步驟 2–5（Secrets 治理）**。
現況與驗收見 docs/plan/2026-09-28-remediation-backlog.md R-04：
- bpm-core/src/main/resources/application.yml、docker-compose.yml（含 healthcheck）仍有
  dev 密碼與 bpm-webhook-secret 字面值；infra/ 與 scripts/ 已乾淨。
- 做法：所有密碼改 ${ENV_VAR} 無預設（缺少即啟動失敗）；hmac-secret 移除 fallback；
  ⚠️ 不動 dev 便利性到無法開工的程度——dev 的 env 由 docker-compose.dev.yml 注入；
  cicd/.env.example 同步；驗收：git 追蹤檔 grep 零命中＋未設時明確失敗的測試。
<共通規範 §0>
```

---

## 備註

- **#8／#9 真實系統上線**、**#44/#46/#45 真實系統實測**、**#65 prod 執行期**屬環境相依項，
  由 PM 在對應環境驗收，不需要 coding agent（見 `docs/handoff/README.md` §3.D）。
- 每批完成後，請更新 `docs/handoff/README.md` §3 的勾消狀態與當輪 handoff，
  保持「未完成總表」是唯一入口。
