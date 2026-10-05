# docs/handoff — 交接文件索引

**最後更新**：2026-10-05（remediation B 批次：R-21／R-22／R-24／R-25 完成）
**最新交接**：`2026-10-05-round22-handoff.md`
**下一批工作 prompt**：`2026-10-04-next-agent-prompt.md`（B 批次已勾消；C 平台決策＝GitLab CI）

---

## 0. 現況快照（給新接手的人）

| | |
|---|---|
| 後端 | Spring Boot **4.1.1** + Flowable **8.0.0** + Jackson **3** + JGit **7.8**（#70 全數完成，EOL 安全債清償） |
| 前端 | Vue 3.4 + Vite 5 + Element Plus |
| 測試 | 後端 **1656**（`mvn clean verify`）、前端 **211**（Vitest）、`acceptance-test.sh` **PASS 7 / FAIL 0** |
| `main` | `64dff59`（本輪 2 merge；`nsl` 主機不可達，約 **140 顆** commit 待推） |
| dev DB | core schema **v9**、Flowable schema **8.0.0.0**（不可逆）；audit 鏈 broken=19／unverifiable=123 為歷史債 |
| dev 容器 | compose project `greyhound`；跑最新 build、預設設定（mock 組織/權限、Git 版控關閉） |
| 功能 backlog | **97 項：✅ 97、🟡 0、⬜ 0** |
| remediation backlog | 開放 **~5 人日**（見 §3.B；R-21／R-22／R-24／R-25 已於 2026-10-05 完成） |

**閱讀順序**：① 最新 round（22）→ ② 本 README 的「未完成總表」→ ③ `2026-10-04-next-agent-prompt.md` →
④ 需要時回讀對應輪次 → ⑤ 環境陷阱總表（`round4`＋`round6/13/14` 增補，動手前必讀）。

---

## 1. 文件索引

| 檔案 | 內容 |
|---|---|
| `2026-09-29-agent-handoff.md` | 重構後 agent 交接（早期） |
| `2026-09-29-authorization-hardening-handoff.md` | 認證授權強化（R-01／R-18 等） |
| `2026-09-29-pm-agent-kickoff.md` | PM agent 啟動 |
| `2026-09-30-next-pm-prompt.md`、`2026-10-01-next-pm-prompt.md`、`2026-10-02-next-pm-prompt.md`、`2026-10-02-next-pm-prompt-v2.md` | 各時期的下一 PM prompt（歷史） |
| `2026-09-30-round3-handoff.md` | R-20 等收尾 |
| `2026-10-01-round4-handoff.md` | **環境陷阱總表**（多輪沿用） |
| `2026-10-01-round5-handoff.md` | 該輪整合 |
| `2026-10-02-round6-handoff.md` | 該輪整合（陷阱增補） |
| `2026-10-03-round7-handoff.md` | #96 完成路徑通知收斂、#51 DLQ 告警＋重放、#3 催辦、#6 HMAC fallback、currentTask taskId |
| `2026-10-03-round8-handoff.md` | Wave A：#23／#1／#7／#51 |
| `2026-10-03-round9-handoff.md` | Wave B：#4／#21／#5 |
| `2026-10-03-round10-handoff.md` | Wave C：#55／#59／#7 通知／#21 UI |
| `2026-10-03-round11-handoff.md` | Wave D：#22／#24／#20 |
| `2026-10-03-round12-handoff.md` | Wave E：delegate 三件組／#50 |
| `2026-10-03-round13-handoff.md` | Wave F：待決策六項（#22 白名單／#58／#56／#65／#43） |
| `2026-10-03-round14-handoff.md` | Wave G：#97／#44／#45／#46／#47 |
| `2026-10-03-round15-handoff.md` | #70 Stage 5（Boot 4.1.1＋Flowable 8.0.0） |
| `2026-10-03-round16-handoff.md` | #70 Stage 6（Jackson 3）——#70 結案 |
| `2026-10-04-round17-handoff.md` | 前端瀏覽器走查＋#28／#32／#35（含 `#56` 表單鏈斷裂修復） |
| `2026-10-04-round18-handoff.md` | #60（formData 原子啟動）／#61（JGit 部署版控） |
| `2026-10-04-round19-handoff.md` | #41（異常偵測）／#53（環境變數替換） |
| `2026-10-04-round20-handoff.md` | #63（+198 單元）／#64（端到端＋flake 修復＋`PROCESS_COMPLETE result` 真 bug 修復） |
| `2026-10-04-round21-handoff.md` | **#8／#9 外圍 client 正式化——97 項全清**；含部署前檢查清單 |
| `2026-10-05-round22-handoff.md` | **remediation B 批次：R-21／R-22／R-24／R-25**；含部署清單增補 |
| `2026-10-04-next-agent-prompt.md` | 未完成事項的派工 prompt（B 批次已完成；C／D／E 仍有效） |

---

## 2. 已完成大事記（2026-10-03～05）

- **remediation B 批次（2026-10-05）**：R-21（授權設定寫入端驗證＋UI 多選）、R-22（反向代理後真實
  client IP）、R-24（擁有權查詢與篩選分離）、R-25（API key v2／寬限期／失敗節流）；後端 1656、前端
  211、acceptance 7/0（round22）。
- **#70 升級結案**：Boot 4.1.1＋Flowable 8.0.0＋Jackson 3（EOL 安全債清償）；Security 7 鏈序、`FACTOR_BEARER` 兩條安全回歸修復。
- **前端瀏覽器走查**（升級驗收最後一項）：4 身分 × 10 路線；抓到並修復 `#56` 的 `DynamicForm` v-else-if 鏈斷裂回歸。
- **功能補齊**：#28（webhook payloadTemplate）、#32（Teams 通知）、#35（EL 逐方法白名單）、#60（formData 原子啟動）、#61（BPMN Git 版控）、#41（異常偵測）、#53（`${ENV_*}` 替換）、#63（+198 單元測試）、#64（端到端＋真 bug 修復）、#8／#9（外圍 client 正式化）。
- **真 bug 修復**：`PROCESS_COMPLETED` 的 `result` 在最後一關寫入變數時誤判 `unknown`（每張正常核准單都中）——端到端測試發現（round20）。

---

## 3. 未完成事項總表

### A. 營運／部署（需要環境或人為操作）

| 項目 | 說明 |
|---|---|
| **push `nsl`** | `nsl`（10.127.42.141）不可達；恢復後 `git push nsl main`（**唯一允許的 push**，永不裸 push） |
| **部署前檢查清單** | 見 `round21 §4`：prod secrets（含 `ORG_AUTH_TOKEN`／`PERM_AUTH_TOKEN`）、Teams webhook、`BPM_BPMN_GIT_ENABLED`、backfill、表單 schema 修復、權限碼指派等 |
| **部署前 DB／secrets 盤點** | `bpm_external_system` 無 seed SQL：撈 `allowedProcessKeys`（fail-closed 恐中斷）與 `ipWhitelist`（nginx IP 語意翻轉）；prod 必須注入 `API_KEY_HMAC_SECRET`（未設即啟動失敗）。見 round22 §5 |

### B. Remediation backlog 開放項（~5 人日；見 `docs/plan/2026-09-28-remediation-backlog.md`）

| 編號 | 項目 | 人日 | 備註 |
|---|---|---|---|
| R-04 | Secrets 治理（步驟 2–5） | 0.5 | `application.yml`／`docker-compose.yml` 仍有 dev 密碼與 `bpm-webhook-secret` 字面值；dev 預設可接受，但驗收要求 git 追蹤檔零命中＋prod 由 env 注入 |
| R-07 | CI/CD 收斂成單軌 | 2 | 平台已定：**保留 GitLab CI、刪 GitHub Actions**；deploy job 仍是 `echo` |
| R-08 | `bpmn-definitions/` no-op | 1 | CI 掃不存在的目錄；#61 已做「部署時 Git commit」，但 BPMN-as-code pipeline 仍缺（A：移出 jar；B：刪 job） |
| R-15 | Spotless＋ESLint/Prettier＋`.editorconfig` | 1 | 目前皆無；格式化會產生巨大 diff，建議獨立 commit 離峰做 |
| R-16 | 根目錄 `README.md` | 0.5 | 目前不存在 |

> ✅ **2026-10-05 完成**：R-21／R-22（B1）、R-24／R-25（B2）——詳見 `2026-10-05-round22-handoff.md`。

### C. 功能殘餘（非 backlog 編號；來自各輪「已知殘餘」）

| 項目 | 說明 | 出處 |
|---|---|---|
| #47 | 前端設計器不產生 `dynamicAssignee` 運算式（需手改 BPMN） | round14 |
| #45 | ESign `resultVariable` 是原字串（供應商 requestId 解析未做） | round14 |
| #28 | spec §11.4 的 `headers` 自訂未做 | round17 |
| #22 | 外部 worker：UI 無 `external_worker` 勾選；complete／fail／unacquire 不查 topic | round13 |
| #32 | 通知設定沒有前端管理頁（API only） | round17 |
| #60 | 前端 StartProcess 實際瀏覽器提交未測；啟動路徑無 `FORM_SUBMIT` 稽核 | round18 |
| #61 | CI/CD 仍缺（＝R-07／R-08）；本地 repo 無 remote／不 push；多實例共用工作目錄不支援 | round18 |
| #56 | `options` 是 formKey 保留字；`optionsUrl` 存檔不驗 | round13 |
| #58 | `PUT` 不驗 schemaJson；狀態競態無鎖；多列語意待前端配合 | round13 |
| #97 | DNS rebinding TOCTOU 仍在（3xx 重導已修） | round14 |

### D. 環境待實測（需要真實系統或 prod 環境）

| 項目 | 說明 |
|---|---|
| #44 Teams | 未對真實 Teams 頻道實測（incoming webhook 長期將被 Workflows 取代） |
| #46 ERP | 未對真 ERP；`HttpURLConnection` 不支援 PATCH |
| #45 ESign | 未對真供應商 |
| #65 OpenAPI | prod 執行期未實測（設定層＋框架語意） |
| #8／#9 | 真實組織／權限系統上線：換 URL＋token；認證方式若有差異需對齊 |

### E. 開發環境（housekeeping，不影響交付）

- dev DB 的 probe 殘留（`probe-*` 多個 process／版本）與已停用外部系統。
- `AuditDeliveryTest` flake 已修（round20）；若再現請看該測試的隔離註解。
- 已完成的「程序教訓」見 round20 §3（agent 驗證 run 可能在 session 結束後仍在跑；合併前必看 `git status`＋最新 surefire 報告）。
