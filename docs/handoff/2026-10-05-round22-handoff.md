# 接手文件 — 2026-10-05 Remediation B 批次（R-21／R-22／R-24／R-25）

> 📌 交接入口：`docs/handoff/README.md`（索引＋未完成總表）。
> 派工 prompt（B 批次已勾消）見 `docs/handoff/2026-10-04-next-agent-prompt.md`。

**寫給下一個接手的 PM Agent。** 撰寫時間 2026-10-05（接續 round21）。
上一輪交接見 `2026-10-04-round21-handoff.md`（**部署前檢查清單仍有效**，本輪 §5 增補）。

本輪兩個工項各在獨立 worktree 並行（2 個 subagent），經 PM 驗收、合併、完整套件與線上實測；
worktree 與 feature 分支已全部清除。`main` = `04f70c5`（B1）＋`64dff59`（B2）＋文件收尾。

---

## 0. 現況

| | |
|---|---|
| 後端 | Spring Boot 4.1.1 + Flowable 8.0.0 + Jackson 3 + JGit 7.8 |
| 前端 | Vue 3.4 + Vite 5 + Element Plus |
| 測試 | 後端 **1656**（`mvn clean verify`；本輪 +39）、前端 **211**（Vitest；+5） |
| `main` | `64dff59`（本輪 2 merge）＋文件收尾 |
| push | ⚠️ `nsl` 仍不可達；累積待推 commit 見 §6，恢復後 `git push nsl main`（**永不裸 push**） |
| worktree | 全部清除（feature 分支亦已刪除） |
| 容器 | dev 跑本輪 build；core schema **v9**（V9 已對「既有資料 DB」實測通過）；health 200；acceptance 7/0 |
| remediation backlog | 開放 **~5 人日**（R-04／R-07／R-08／R-15／R-16） |

---

## 1. B1 — R-21＋R-22（merge `04f70c5`）

### R-21 授權設定的驗證移到寫入端
- 新增 `ExternalSystemAuthorizationValidator`；`ExternalSystemAdminController` 的 create／update
  在**任何 mutation 之前**驗證四個欄位：JSON 陣列格式（失敗 400、訊息指名欄位）、
  `allowedProcessKeys` 必填且每個 key 必須是目前已部署的流程定義。
- `"[]"`（拒絕全部）與缺席（不限制）在另外三欄維持兩種語意、互不轉換（有測試釘住）。
- 寫入端只接受 JSON 陣列；讀取端保留逗號分隔容忍給舊資料列 —— 兩種解析器分工是刻意的。
- 前端 `ExternalSystemAdmin.vue`：`allowedProcessKeys` 改多選＋required（選項來自
  `GET /api/process-definitions`），未選直接擋下。
- ⚠️ **契約變更**：POST／PUT 一律必須帶 `allowedProcessKeys`（partial PUT 會 400）；
  既有資料列讀取不受影響，但**任何一次編輯都要重新指定**（fail-closed）。

### R-22 反向代理後的真實 client IP
- `server.forward-headers-strategy: native`（Tomcat `RemoteIpValve`），**不用 `framework`**
  （`ForwardedHeaderFilter` 不看來電位址、無條件信任 XFF）。
- 信任邊界：只有 TCP peer 位於 `RemoteIpValve` 預設 internalProxies（私有／loopback 網段）
  時才採用 XFF。prod 的 bpm-core 無公開 port、只在私有 Docker 網路上（`docker-compose.prod.yml`
  僅暴露 nginx 80/443），公網無法用偽造 XFF 冒充白名單來源。dev 直連不帶 XFF，行為不變。
- 附帶：`CallbackAuthFilter` 的白名單／log 與 `GatewayAuthenticationFilter` 的 trusted-proxies
  讀同一個 `remoteAddr`，一併修正；403 訊息顯示真實 client IP、不再回顯 proxy 位址。
- ⚠️ **部署意涵**：既有環境若把 `ipWhitelist` 填成 nginx 容器 IP，改動前是「全放行」、
  上線後會變「全擋」——見 §5。

---

## 2. B2 — R-24＋R-25（merge `64dff59`）

### R-24 `queryByBusinessKey` 與擁有權模型一致
- `GET /api/external/process-instances` 由 `initiator = system:<id>` 改為 `_externalSystemId = <id>`，
  並逐筆呼叫既有 `verifyOwnership`（**授權與篩選分離**；拿掉篩選仍 fail-closed）。
- **backfill 前**：自訂 initiator 啟動、或 R-20 前寫入的舊實例**不出現在列表**；
  `/status` 對 `initiator=system:<id>` 的舊列仍有向後相容讀法 → 兩端點在 backfill 前不一致
  （已在程式註解與本檔記錄）。backfill 建議以稽核庫 `EXTERNAL_API_CALL` 的系統歸屬回填，
  **不要用 initiator 推斷**。
- 惡意 `system:victim` 注入已有測試釘住（body 400＋variables 覆寫＋列表不可見）。

### R-25 API key 強化
- 落庫格式 `v2:` ＋ HMAC-SHA256(server secret, key)；驗證端**雙讀**（無前綴＝舊 SHA-256），
  舊 key 第一次成功驗證後透明升級為 v2。
- rotate 支援寬限期：舊 key 進 `previousApiKey`，預設 24h（`BPM_API_KEY_GRACE_PERIOD` 可覆蓋，
  0＝立即失效）；連續輪替只保留一個 previous slot。
- 認證失敗節流：同一（systemId＋來源 IP）1 分鐘內 10 次達標 → 429＋`Retry-After`；
  記憶體、單實例、重啟歸零；429 仍寫 rejected 稽核。
- migration `V9`：`api_key` 加寬 `NVARCHAR(128)`、新增 `previous_api_key`／到期欄位、
  不改寫既有資料（雙讀＋透明升級才是安全遷移）。
- 連帶效能：`lastUsedAt` 改分鐘級共用 tracker；`ExternalSystemPolicy.parse` 加有界快取。
- ⚠️ **啟動硬需求**：`BPM_API_KEY_HMAC_SECRET`（≥32 bytes）未設即啟動失敗。
  注入來源：dev＝`docker-compose.dev.yml`、test＝`application-test.yml`、
  prod＝`API_KEY_HMAC_SECRET`（`cicd/.env.example` 已同步）。

---

## 3. PM 驗收證據（2026-10-05）

| 項目 | 結果 |
|---|---|
| 合併衝突 | 僅 `ExternalSystemAdminController` 建構子（兩側注入合併）；其餘檔案自動作業 |
| `mvn clean verify` | **1656 綠**（Failures 0／Errors 0；baseline 1617＋39） |
| `npm test` | **211 綠**（21 檔） |
| 熱啟動（dev overlay） | health 200；Flyway **V9 對既有 dev DB 套用成功**（schema v9；補上「只測過全新 DB」的缺口） |
| seed＋acceptance | PASS **7**／FAIL **0** |
| 線上 smoke | ① 建立外部系統 → 200，DB `api_key` 前綴 `v2:`；② 缺 `allowedProcessKeys` → 400 且**零副作用**（DB 無該列）；③ 新 key 啟動 leave-approval → 列表查得到 → status 200；④ 事後停用 smoke 系統（dev DB 留下該停用列＋1 筆 running 實例，屬既有 probe 殘留模式） |

---

## 4. 已知殘餘（本輪新增／延續）

| 殘餘 | 說明 |
|---|---|
| backfill（R-20／R-24） | 舊實例在 backfill 前列表查不到；屬資料修補，由 PM／維運執行 |
| R-21 部署盤點 | `bpm_external_system` 仍無 seed SQL；部署前撈 `allowedProcessKeys`＋`ipWhitelist` |
| 節流 | 記憶體、單實例、重啟歸零；**callback 路徑無節流**；429 仍逐筆寫稽核 |
| 前端 | 管理頁未顯示寬限期到期／previous key（API 已有欄位，Vue 未用） |
| V9 | 已對既有 dev DB 實測；prod 首次套用仍建議備份後執行 |
| `verifyOwnership` initiator 相容讀法 | R-20 遺留；backfill 後可另行裁決是否移除 |

---

## 5. 部署前檢查清單增補（接 round21 §4）

1. **prod `API_KEY_HMAC_SECRET` 必填**：未設即啟動失敗；不得使用範例值。
2. **`ipWhitelist` 盤點**：改動前填 nginx IP＝全放行；上線後 nginx IP 不再是 client IP → 會變全擋。
   只有填真實 client IP 的條目才會放行。
3. **`allowedProcessKeys` 盤點**（原 R-21 警告）：空／非 JSON 的舊列在新驗證下，下一次編輯必須重填；
   含未部署 key 會被 400（fail-closed），可能中斷既有系統的啟動呼叫。
4. 其餘同 round21 §4：JWT／DB secrets、`ORG_AUTH_TOKEN`／`PERM_AUTH_TOKEN`、Teams webhook、
   `BPM_BPMN_GIT_ENABLED`、backfill、表單 schema 修復。

---

## 6. 統計

- remediation：本輪完成 **R-21／R-22／R-24／R-25（3 人日）**；
  剩 **R-04（0.5）／R-07（2）／R-08（1）／R-15（1）／R-16（0.5）＝ ~5 人日**。
- CI/CD 平台決策：**保留 GitLab CI**（R-07／R-08 派工時套用；GitHub Actions 屆時刪除）。
- 後端 **1656**；前端 **211**；`acceptance-test` PASS 7 / FAIL 0。
- 本輪 merge：`04f70c5`、`64dff59`（2 個 feature 分支各 1 commit、已清除）。
- 待推：`nsl` 恢復後 `git push nsl main`；本輪結束時距 `nsl/main` 約 **140 顆**（含文件收尾）。

---

## 7. 追加（同日晚）：R-08 設計票 — ADR-002（選項 A 拍板）

- 使用者拍板 R-08 走**選項 A（BPMN-as-code）**，設計票產出
  `docs/plan/2026-10-05-adr-002-bpmn-as-code.md`（merge `c6789c3`；設計 0.5 人日）。
- 核心決策：repo `bpmn-definitions/` 是「應該上線什麼」的真實來源；`POST /api/deployments` 是唯一
  部署入口；runtime 目錄（#61）是只寫不讀的部署帳本；CI 用服務帳號 JWT、失敗大聲紅、不做 env 替換。
- 關鍵查證：測試套件依賴 classpath 自動部署（A/B 實驗）；classpath 退場順序「先關設定、再移檔案」；
  prod 缺 `/app/bpmn-definitions` volume；設計器是第二個部署呼叫端。
- 實作 T1–T5 約 **2.5 人日**（原估 1 上修）；未決 7 條（IdP、權限邊界、冪等、bootstrap…）見 ADR-002 §8。
- **2026-10-06 續**：**T1 ✅（`d921742`）**——repo `bpmn-definitions/` 就位、seed 路徑與 `name` 修正；
  testResources 映射因雙 root same-key 失敗**延後至 T4**。**Q1／Q2 拍板**：CI 先用閘道密鑰過渡
  （IdP JWT 為退出條件）、prod 走 CI 專用權限碼（O2 併入 T2，T2 估時 0.75→1d）。ADR-002 已修訂。
- **PM 驗收（2026-10-06）**：merged main `mvn clean verify` **1656 綠**（0 失敗）；
  dev `seed-data.sh`（新路徑＋`name`）＋`acceptance-test.sh` **PASS 7 / FAIL 0**。
