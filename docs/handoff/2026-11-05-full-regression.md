# Greyhound BPM 完整回歸 2026 11 05

BPM 本機前後端已啟動。修正套件安裝校驗失敗及一項受背景稽核干擾的測試後，後端 1,685 項、前端 266 項與瀏覽器六組流程全部通過。

基準為 `2a11db8`，UniForm gitlink 為 `400697f6ce0a0d1f5acd0adefa4c0ba4acc8338a`，加上本輪工作區修正。環境為 macOS arm64、JDK 21.0.9、Node 24.21.0、pnpm 11.27.1；未變更 SDK pin 或其他套件版本。

## 驗證結果

命令從 repo 根目錄執行，除非另註明工作目錄。Java SDK 的 `-DskipTests` 僅用於依賴準備，不代表 SDK 全套測試通過。

| 範圍 | 命令 | 最終結果 |
| --- | --- | --- |
| 固定來源 Java SDK | `mvn -B -f vendor/uni-form/java/pom.xml -pl uniform-spring-boot4-starter -am -DskipTests clean install` | PASS；四個內嵌 SDK JAR 的 SHA-256 與來源建置產物逐一相同 |
| npm SDK | `bash scripts/build-uniform-frontend.sh` | PASS；再次重建後，五個宿主套件的 SHA-512 均符合修正後的 lockfile |
| 前端乾淨安裝 | 在 `bpm-frontend` 執行 `npm ci --no-audit --no-fund` | PASS |
| 全部前端測試 | 在 `bpm-frontend` 執行 `npm test` | 25 檔、266 項通過 |
| 前端正式建置 | 在 `bpm-frontend` 執行 `npm run build` | PASS；保留既有大型 chunk 警告 |
| 兩個跨資料庫補償案例 | 在 `bpm-core` 執行 `mvn -B -Dtest=StartProcessFormDataCompensationTest test` | 2 項通過，0 失敗／錯誤／跳過 |
| 瀏覽器 E2E 與後端打包 | `bash scripts/browser-e2e.sh --prepared` | 六組 Chromium 流程全部通過、無重試；Failsafe 包裝案例 1 項通過，`verify` 成功 |
| 修正後完整後端重驗 | 在 `bpm-core` 執行 `mvn -B test` | 1,685 項通過，0 失敗／錯誤／跳過；耗時 4 分 23 秒 |
| 差異檢查 | `git diff --check` | PASS |

後端首輪 `mvn -B clean verify` 為 1,685 項中 1 項失敗；修正後完整重跑 `test`。後端打包及 `verify` 已由 E2E profile 完成；最終完整重驗採 `test`，避免覆寫正在執行的 JAR。總數依 Maven 最終摘要及逐套件 console 計數交叉核對。

瀏覽器涵蓋設計／發布／預覽／填答驗證、核准、退件補件、拒絕、轉發交回、兩人加簽及候選任務認領。使用當輪全新 MSSQL／RabbitMQ／Redis／SMTP 容器與合成資料；結束後確認測試容器已回收。

報告入口：`bpm-frontend/playwright-report/index.html`、`bpm-frontend/test-results/browser-e2e.xml`、`bpm-core/target/surefire-reports/`、`bpm-core/target/failsafe-reports/`。這些是未納入版控的本機產物，後續測試可能覆寫。

## 修正原因與範圍

### 套件雜湊與建置入口

首輪 `npm ci` 回 `EINTEGRITY`。lockfile 使用原生 pnpm 打包的雜湊，專案共用入口卻會以 Node gzip 標準化壓縮。以同一 UniForm Vue 套件對照，原生打包雜湊符合舊 lockfile，且與標準化套件解壓後的 tar 完全相同。

更新五個 UniForm 套件的 lockfile integrity，保留共用建置腳本。README 改為使用與 Docker／CI 相同的 `build-uniform-frontend.sh`，避免直接打包再次產生不一致。乾淨安裝、全前端測試、正式建置與再次打包校驗均通過。

### 回滾測試的背景稽核干擾

`StartProcessFormDataCompensationTest.formSaveFailureRollsBackProcessStart` 原本比較整張稽核表的列數。首輪預期 18、實際 19；同一窗口有較早測試的通知進入死信並寫入 `DLQ_MESSAGE`。

在既有案例中注入一筆無關稽核後，單獨執行可確定重現原斷言失敗（預期 0、實際 1）。修正為透過唯一 business key 捕捉 Flowable execution 所屬的流程實例 ID，再檢查該案所有類型的稽核為 0。另斷言無關稽核確實寫入一筆，保留干擾情境的回歸保護。

流程回滾、表單回滾及主交易 commit 失敗後的表單補償斷言均保留。listener 在 `finally` 移除，暫時觀測輸出已清除。沒有變更正式交易、稽核或補償程式。

### 本機啟動設定

localenv 原本指向不存在的 `greyhound/bpm-core`。在機器專屬設定補上 `DIR_greyhound="bpm"`，並加入既定的 SDK module 與 runtime 目錄設定；`validate` 通過。資料根目錄與共用服務定義維持原設定。

既有 RabbitMQ／MailHog 容器引用已消失的 Docker network。確認掛載後，僅重建這兩個容器；保留 RabbitMQ bind mount 與既有 MSSQL。再次執行 `greyhound deps` 成功。

## 服務狀態與邊界

- localenv `greyhound ready --no-build --strict-health` 及 `demo greyhound start` 成功，前後端保持執行。
- 前端 `http://127.0.0.1:3000/` 回 HTTP 200；後端 `http://127.0.0.1:18110/actuator/health` 回 HTTP 200／UP。
- core／audit／form 三個資料庫、Redis、RabbitMQ、MailHog 均為 UP。
- `user001` 開發 JWT 查詢權限、流程定義與待辦均回 HTTP 200；匿名待辦請求回 401。token 未輸出或存檔。
- 本輪為本機開發驗證，組織與權限使用既有 mock；不涵蓋正式 IdP／ODS／外部供應商、遠端 CI、負載測試或 SDK 全套 conformance。
- 本機持續服務仍需 Docker 與 localenv 程序存活；重新登入或重開機後依 localenv 入口啟動。
