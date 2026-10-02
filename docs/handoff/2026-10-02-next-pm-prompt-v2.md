# 開工 prompt — Greyhound BPM 平台（2026-10-02 第二版，給下一位 PM Agent）

> ⚠️ **本檔已於 2026-10-03 執行完畢並被取代** —— 成果見
> `docs/handoff/2026-10-03-round7-handoff.md`（最新交接）。

> 給下一位 PM Agent。撰寫：2026-10-02（本輪收尾時）。
> 上一版 `docs/handoff/2026-10-02-next-pm-prompt.md` 對應的是**已完成的這一輪**；
> 本檔取代它。**你現在讀的這份檔案就是給你的 prompt。**

你是這個專案的 PM Agent。職責是**排定優先序、拆解工作、逐項推進**，
而且每項工作開工前都要先問使用者是否繼續（使用者的額度有限）。

---

## 0. 先確認現況（不要假設）

- repo：`/Users/kywk/kywk/nanshan/greyhound`；**每個 Bash 呼叫都要自己 `cd` 過去**
  （shell 的 cwd 與變數每次呼叫都會重置）。
- 開工前跑：`git status -sb`、`git branch`、`git log --oneline -12`、`git worktree list`。
- **2026-10-02 收尾狀態**：
  - `main` = `82d602d`（第二波）＋本文件所在的文件收尾 commit；**已 push `nsl`**。
  - 後端 **840** 測試全綠（`mvn clean verify`）、前端 **165**、`acceptance-test.sh` **PASS 7 / FAIL 0**。
  - worktree 全部清除；dev 容器（bpm-core／nginx／mssql／rabbitmq／redis／mailhog）運行中。
  - ⚠️ `main` 的 upstream 是 `github/main`，而 `github` remote 指向無關公開 repo 且已有本專案歷史
    （使用者明示暫不處理）—— **永遠 `git push nsl main`，不要裸 push**。
  - 分支政策：`feature/* → main` 直接合併（不要再問）。
  - 統計：96 項｜✅ 59｜🟡 15｜⬜ 22｜剩餘估時上限 ~86.5 人天。

## 1. 必讀（依序）

1. **`docs/handoff/2026-10-02-round6-handoff.md`（最新）** —— 本輪兩波成果（每項都有 merge commit）、
   技術結論、殘餘、環境風險、**已裁決清單**、下一輪建議。
2. `docs/handoff/2026-10-01-round5-handoff.md` —— #91、方向 B 的假兩難、驗證方法論。
3. `docs/handoff/2026-10-01-round4-handoff.md` —— 環境陷阱總表、`git commit` 提交整個 index 的坑。
4. `docs/handoff/2026-09-30-round3-handoff.md` —— 授權三組政策、並行 subagent 標準流程。
5. `docs/backend-development-backlog.md` —— 工項清單與統計。

## 2. 本輪（2026-10-02）完成了什麼（一句話）

**Wave 1**：#67 前端（流程層 webhook 面板＋`all`＋存檔契約測試、後續移除 timeout 選項）、
#25 payload（P2-1 紅線）、dev 註解、#68 docs、#95 docs 同步。
**Wave 2**：R-19（外部完成任務最小授權＋`_` 變數過濾）、#26／#27（HMAC／重試／DLQ 測試驗證）、
#40（稽核 CSV 匯出）、webhook HMAC prod 啟動防護、#52（多版本測試）、#33＋#6（通知觸發完整化＋催辦）、
R-23（`startProcess` `_` 過濾）＋TASK_URGE 稽核。
另以**真實瀏覽器**（computer-use／CDP）完成前端面板視覺驗證。

## 3. 已裁決（2026-10-02 使用者，**不要再問**）

1. **timeout**：併入 **#23** 設計替代機制（boundary timer＋政策）；`timeout` webhook 選項保持移除。
2. **催辦參數**：30 分鐘／案件 key／fail-open，端點 `POST /api/tasks/urge?processInstanceId=` 照現行。
3. **system 案件催辦權**：開放給 `bpm:external:revision` 受理人（**待實作**）。
4. **退件／拒絕原因不入信**：維持 P2-1 紅線。
5. **匯出**：無筆數上限、CSV 逐字一致不中和公式、中斷語意記命中筆數、檔名含日期。
6. **HMAC**：移除 `WebhookConsumer` 的 `@Value` fallback（保留 base dev 預設）（**待實作**）。
7. **DLQ**：以 **#51** 為工項（含人工重放），下一輪做。
8. **R-19 變數**：維持現狀（只擋 `_` 前綴＋必填規格），不收緊到 spec 名單。
9. **下一輪主軸**：**完成路徑收斂（#96）＋ DLQ 告警／重放（#51）**。

## 4. 建議的下一輪 wave（先和使用者確認）

| 順序 | 工項 | 估時 | 備註 |
|---|---|---|---|
| 1 | **#96 完成路徑通知收斂** | 1–2d | 外部 API 完成也發通知；全域 `TASK_COMPLETED`／`PROCESS_COMPLETED` listener（`FlowableConfig` 註冊）；與 `NotifyPublisher` 收斂 |
| 2 | **#51 DLQ 告警＋人工重放** | 1–2d | `DeadLetterConsumer` 目前只記 log |
| 3 | **#23 Timer Event 超時處理** | 2d | timeout 事件的替代設計（政策需先與使用者確認） |
| 4 | 小收尾：**#3 催辦開放受理人**、**#6 移除 HMAC fallback** | 0.5d | 已有明確裁決 |
| 5 | 「我的申請」currentTask 補 `taskId`（可選） | 0.2d | 若產品偏好 `/tasks/{taskId}/urge` |
| 6 | **#4 Call Activity** 或 **#21 Callback 接收端** | 3d | #21 的 token 簽發需先設計 |
| 7 | **#70 Stage 5**（Boot 4＋Flowable 8） | 22d | 照升級計畫；`ExtensionElementPreservationTest` 先紅 |

## 5. 環境風險與硬規則（重點；全文見 round6 §4）

- 🔴 **prod 啟動要求**：`BPM_WEBHOOK_HMAC_SECRET`（未設或預設 → 拒絕啟動）；舊有的
  `OIDC_ISSUER_URI`／`GATEWAY_SHARED_SECRET` 要求仍在。
- 🔴 **`StreamingResponseBody` 與閘道稽核不相容**（ASYNC dispatch 遺失身分）—— 串流端點用直接寫 response。
- 🔴 **R-19**：Call Activity 子流程的任務，子流程 key 也要在 `allowedProcessKeys` 內。
- 沿用：`mvn clean verify`、`DOCKER_HOST=unix://$HOME/.orbstack/run/docker.sock`＋
  `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`、dev overlay、`acceptance` 重建後可能需跑第二次、
  外部 API 的 `ipWhitelist` 要含 `192.168.117.1`、`PUT /api/tasks/{id}` 的 `variables` 是 list、
  `DELETE /api/admin/external-systems/{id}` 是軟停用、**`git commit` 會提交整個 index**（提交前檢查第一欄）。
- 並行 subagent 上限 5、每 agent 一個 worktree、prompt 要含工作目錄／不碰主樹／不 push／不移除自己的 worktree；
  `docs/backend-development-backlog.md` 只准 PM 動；完整套件由 PM 在合併時統一跑；線上實測 PM 序列做。

## 6. 回報格式

每完成一項就向使用者回報：做了什麼、為什麼、驗證結果（測試數、負控組、**線上實測**）、
沒做到的部分，然後問是否繼續下一項。**誠實回報很重要** —— 主動揭露不足的價值高於測試全綠。
