# 可重複執行的瀏覽器端到端測試

## 執行方式

需要 JDK 21、Node 24（CI 固定 24.21.0）、Maven、Docker，以及 UniForm `package.json` 指定的 pnpm。先讓目前 shell 的 `JAVA_HOME`、`PATH` 指向這些版本，再從 repo 根目錄執行：

```bash
git submodule update --init --recursive vendor/uni-form
npm install --global "$(node -p "require('./vendor/uni-form/package.json').packageManager")"
bash scripts/browser-e2e.sh
```

預設入口會從 gitlink 固定的 UniForm 原始碼建置 Java SDK 與 npm tarballs，執行 `npm ci`、下載 Chromium，再透過 Maven `browser-e2e` profile 執行 Playwright。SDK 打包完成後統一使用 Node gzip，避免不同 pnpm 原生打包器的壓縮位元差異讓 lockfile 校驗失敗。

相依與瀏覽器已準備好時，可執行 `bash scripts/browser-e2e.sh --prepared`。每次仍會重新編譯後端並建立全新的資料庫。Linux 首次安裝瀏覽器的系統套件可加 `E2E_INSTALL_WITH_DEPS=1`。

## 隔離與驗證範圍

`BrowserE2eIT` 沿用真實 MSSQL、RabbitMQ、Redis 的 Testcontainers 基底，建立三個獨立資料庫與隨機 API port，再啟動隨機 port 的 Vite。另建立固定版本 `mailhog/mailhog:v1.0.1` 的隔離 SMTP sink，通知郵件僅送入該次容器，不依賴本機 SMTP 或外部郵件服務；此套測試不驗證郵件投遞內容。測試結束會關閉瀏覽器、Vite 與測試容器，移除該次 Vite cache。不讀取或清除 localenv 的資料；環境檢查會拒絕 localenv 的 18110／3000 port。

六組案例各自透過 UI 建立唯一 UUID 表單、儲存草稿、發布、預覽、填答及送出。每案都有必填、數字上限、單選與一次送出的驗證，再覆蓋：

| 案例 | 待辦與最終結果 |
| --- | --- |
| 核准 | 批註、申請人催辦、主管同意，已完成 |
| 退件 | 原因必填、申請人僅能修改允許欄位、補件重送、主管核准，已完成 |
| 拒絕 | 原因必填、保存意見、任務消失，已拒絕 |
| 轉發 | 選擇受託人、受託人完成交回、原主管核准，已完成 |
| 加簽 | 兩人加簽、意見保存、部分完成仍禁止父任務結案、全部完成後核准，已完成 |
| 認領 | 候選任務認領前唯讀、認領後其他候選人失去處理資格、核准，已完成 |

表單與待辦的業務寫入皆經瀏覽器 UI；API 只用來部署每案專用 BPMN 與讀取持久化結果。登入使用既有 dev 登入流程。每案要求無瀏覽器例外與 console error，不自動重試失敗案例。

## 報告與 CI

- HTML：`bpm-frontend/playwright-report/index.html`
- JUnit：`bpm-frontend/test-results/browser-e2e.xml`
- Maven 啟動／容器結果：`bpm-core/target/failsafe-reports/`

失敗會保存畫面；不保存 trace、HAR、影片、JWT 或登入狀態檔。測試資料皆為合成資料，報告不納入 git；CI artifact 保留七天。

GitHub Actions 的 `.github/workflows/browser-e2e.yml` 在 main/dev/sit 相關變更與 PR 執行，也可手動啟動；GitLab 的 `browser:e2e` 使用相同入口。兩者再執行前端單元回歸與正式版建置。

UniForm submodule 位於內網 `10.127.42.141`。Runner 必須能連到該 Git server 並具有讀取權限；一般 GitHub hosted runner 無法直接取得它。GitHub 必須設定 repository variable `BROWSER_E2E_RUNNER`，值為 JSON 字串或 label 陣列，例如 `["self-hosted","linux","x64"]`，選擇已配置內網與 Docker 的 runner；未設定會在前置檢查明確失敗。需要專用 SSH 身分時，設定成對的 `UNIFORM_SSH_PRIVATE_KEY`、`UNIFORM_SSH_KNOWN_HOSTS` secrets；known_hosts 必須使用已信任的 server key。也可由 runner 既有 SSH 設定提供身分。

GitLab runner 必須允許 privileged Docker-in-Docker，並在 checkout 階段就具備內網 submodule 的存取權限。CI 遇到缺少 SDK 存取能力時會失敗，不會跳過 E2E 回報成功。

## 2026-10-26 實跑結果

- macOS arm64、JDK 21、Node 24.21.0、pnpm 11.27.1、Playwright 1.64.1。
- 完整入口重新建置 SDK 與 `npm ci` 後，六案全過；相同版本再以 `--prepared` 建立另一組全新資料庫，六案再次全過，沒有重試。
- 每輪各有五筆已完成、一筆已拒絕；通知送至隔離 SMTP，瀏覽器無例外或 console error。
- 既有前端測試 266 項通過，正式版建置成功；兩份 CI YAML、46 段 CI shell 與入口腳本語法檢查通過。
- 測試容器與專用 Vite cache 已回收，原本機 API、前端與前端相依 HTTP 檢查皆為 200。
- GitHub repository 目前無自架 runner、runner variable 或 SSH secrets；遠端 CI 仍待上述內網 runner 設定，不能將本機結果視為遠端 CI 已通過。
