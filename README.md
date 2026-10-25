# Greyhound BPM 平台

Greyhound 是以 BPMN 流程、任務與表單為核心的內部流程平台，包含 Spring Boot 後端與 Vue 管理介面。後端使用 Java 21、Spring Boot 4.1.1、Flowable 8.0.0；前端使用 Vue 3、Vite 5 與 Element Plus。表單採用 uni-form，版本固定在 `vendor/uni-form` submodule 的 commit `3661329`。

## 開發環境

本機開發環境由共用 `scripts/localenv` 管理。需要 Java 21、Maven、Docker、Node 24 與已初始化的 uni-form submodule。前端與 SDK 的 Volta 宣告統一為 Node 24.21.0，pnpm 版本依 SDK 的 `packageManager`。環境手冊位於 `$LOCALENV_SH` 同目錄的 `localenv/docs/greyhound.md`。

```bash
git submodule update --init vendor/uni-form

# 指定共用 scripts repo 的入口；也可讓 localdev 自動偵測
export LOCALENV_SH="/path/to/scripts/localenv.sh"
bash scripts/localdev.sh up
bash scripts/localdev.sh frontend
```

`scripts/localdev.sh` 優先委派給 localenv；找不到時改用專案 Docker Compose fallback。fallback API 預設為 `http://127.0.0.1:8080`，localenv API 預設為 `http://127.0.0.1:18110`，前端 Vite 為 `http://127.0.0.1:3000`。啟停、狀態與日誌可用 `scripts/localdev.sh down|status|logs`。

若缺少 uni-form SDK 套件（例如首次建置或 SDK pin 改變），先從根目錄建置本機使用的 npm tarball 與 Java SNAPSHOT：

```bash
cd vendor/uni-form
mise exec node@24.21.0 -- corepack pnpm install --frozen-lockfile
mise exec node@24.21.0 -- corepack pnpm exec bash scripts/pack-npm.sh
cd ../..
mvn -f vendor/uni-form/java -pl uniform-spec,uniform-core,uniform-jackson3,uniform-spring-boot4-starter -am clean install -DskipTests
mvn -f bpm-core clean package -DskipTests
```

只有完成 `pack-npm.sh` 產生 `vendor/uni-form/dist/npm/` tarball 後，才在 `bpm-frontend` 執行 `npm ci`。SDK pin 更新時須同步更新本機 tarball 的 lockfile integrity；Java reactor 與 bpm-core 使用 `clean` 重建，避免殘留已刪除資源或沿用舊 SNAPSHOT 內嵌依賴。`mvn verify` 使用 Testcontainers 啟動 MSSQL、RabbitMQ、Redis；需有可用的 Docker daemon，且目前執行環境可存取 Docker socket。

Node 24 與 SDK 指定的 pnpm 已在 PATH 時，也可從根目錄執行 `bash scripts/build-uniform-frontend.sh`。前端 Docker 與 CI 共用此入口，再執行 `npm ci`；Docker 使用 repository root 作為 context：`docker build -f bpm-frontend/Dockerfile .`。UniForm 的 Git URL 為內網 SSH，CI runner 必須具備網路與讀取權限。

localenv 的 seed／acceptance runner 需要 Bash 4 以上；macOS 內建 Bash 3.2 不支援。若 Homebrew Bash 已安裝，可將它放在 PATH 前端再操作：

```bash
# 若 Bash 4+ 安裝在其他位置，請以該路徑取代 /opt/homebrew/bin/bash
export PATH="/opt/homebrew/bin:$PATH"
/opt/homebrew/bin/bash scripts/localdev.sh seed
/opt/homebrew/bin/bash scripts/localdev.sh acceptance
```

本機常用操作（依目前 `scripts/localdev.sh`）：

```bash
bash scripts/localdev.sh status
bash scripts/localdev.sh seed
bash scripts/localdev.sh acceptance
```

## API 與驗證

常用 API 路徑包含流程啟動 `POST /api/process-instances`、任務表單 `GET /api/tasks/{id}/form`、任務提交 `PUT /api/tasks/{id}`、表單定義 `/api/forms`、流程定義 `/api/process-definitions`、BPMN 檢查 `POST /api/bpmn/lint`、稽核紀錄 `/api/audit-logs`。API 文件可在服務啟動後由 OpenAPI UI 查看。

```bash
cd bpm-core && mvn verify
cd ../bpm-frontend && npm test && npm run build
```

本機測試環境提供 MockOrg／MockPerm 供流程開發使用；真實企業組織與權限中心尚未接入。開發用 mock 資料不代表正式環境身分來源或權限設定。正式部署設定與操作流程需依部署文件確認。

## 專案入口

- [`docs/handoff/2026-10-25-uniform-performance.md`](docs/handoff/2026-10-25-uniform-performance.md)：本次 UniForm 升版、架構／效能調整與驗證範圍
- [`docs/README-testing.md`](docs/README-testing.md)：測試環境與測試方式
- [`docs/handoff/README.md`](docs/handoff/README.md)：現況快照、未完成項目及交接文件索引
- [`docs/handoff/2026-10-13-uniform-form-refactor-handoff.md`](docs/handoff/2026-10-13-uniform-form-refactor-handoff.md)：uni-form 導入、介面與後續工作
- [`bpm-core/pom.xml`](bpm-core/pom.xml)：後端依賴與 Java／Spring Boot／Flowable 版本
- [`bpm-frontend/package.json`](bpm-frontend/package.json)：前端指令與 Node 版本宣告
- [`scripts/localdev.sh`](scripts/localdev.sh)：本機服務、seed 與 acceptance 入口
