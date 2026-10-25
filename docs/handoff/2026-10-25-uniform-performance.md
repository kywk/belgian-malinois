# UniForm 升版與效能整理（2026-10-25）

## 範圍與版本

- `vendor/uni-form`：`67f6047` → `36613299f1a98954d813af61001e5645dc8c50af`。已用來源 repo 的 `git ls-remote origin refs/heads/main` 確認當輪遠端主分支。
- 重建 npm tarballs 與 Java SNAPSHOT，更新五個 npm SDK 套件的 lockfile integrity；版本號仍為 npm `0.1.0`／Java `0.1.0-SNAPSHOT`，來源以 gitlink 為準。
- 使用者確認專案仍在開發階段、不需要資料 migration。本次未改資料結構、未新增 migration，也未重置現有資料。
- 未新增測試、未 commit／push。submodule 原有 `scripts/release-check.sh` 檔案模式差異保留。

## 已實作

| 路徑 | 改善與邊界 |
| --- | --- |
| 前端路由／UniForm 接點 | 頁面改為動態載入，renderer／designer 使用 SDK 子入口；保留路由權限政策。 |
| 動態選項 | 每個宿主 context 共用 schema 欄位索引；表單驗證用 id 查找，避免逐 widget 重複展開 schema。 |
| 人員搜尋 | 250ms debounce、取消舊請求、拒絕過期回應；卸載時清理。 |
| 流程圖 | XML 與 viewer 模組並行載入；每輪請求各自管理 viewer，切換案件及卸載時取消並銷毀。 |
| 表單快照 | 最新資料查詢限制取第一筆，不再讀取整份歷史 JSON 才取第一列；完整歷史 API 保留。 |
| 待辦摘要 | 依案件去重、分批讀流程資料與 `_formVersions`；DTO 組裝不再每任務額外查詢兩次，不載入其他填答變數。 |
| 後端選項權限 | 固定 manifest registry 於建構時註冊一次；DSL、權限與答案仍逐請求計算，未加入跨使用者資料快取。 |
| 建置 | 前端與 SDK 統一 Node 24；Docker 使用 repo root context，CI 先打包 pinned SDK 再 `npm ci`；加入共用建置腳本。 |

## 建置量測

同一 checkout、相同 Vite 5.2.11 建置；前後分別使用原有 Node 22 與對齊後 Node 24。數字為輸出檔案大小，非瀏覽器網路／CPU 效能測試。

| 項目 | 調整前 | 調整後 |
| --- | ---: | ---: |
| 主 JavaScript | 2,893.68 kB | 1,326.56 kB（減少約 54%） |
| 主 JavaScript gzip | 885.09 kB | 434.31 kB（減少約 51%） |
| 表單設計器 | 併入主檔 | 140.22 kB、按路由載入 |
| BPMN 設計器 | 併入主檔 | 1,309.11 kB、按路由載入 |

主檔與 BPMN 設計器仍有 Vite 大型 chunk 警告；本次不透過提高警告門檻掩蓋，也未擴張為全站 Element Plus 重構。待辦的序列化版本變數可能仍觸發 Flowable 內部 byte array 讀取，不能宣稱 SQL 次數已固定。

## 實際驗證

- **PASS**：SDK `pnpm install --frozen-lockfile`、`pnpm exec bash scripts/pack-npm.sh`。
- **PASS**：`mvn -B -f vendor/uni-form/java -pl uniform-spec,uniform-core,uniform-jackson3,uniform-spring-boot4-starter -am clean install -DskipTests`。
- **PASS**：宿主後端 clean compile／testCompile；後續下列針對性測試共 40 項通過，包含真實 MSSQL／RabbitMQ／Redis 容器與待辦版本鎖定：

  ```sh
  mvn -B -f bpm-core test '-Dtest=FormEnvelopeServiceTest,FormDefinitionValidatorTest,FormAccessResolverTest,FormEnvelopeControllerTest,MultiVersionProcessTest#runningInstanceKeepsItsLockedFormVersionWhenANewFormVersionIsPublished'
  ```

  最初受限環境下 Mockito attach 失敗；允許 JVM attach 與 Docker 存取後重跑通過。測試未啟動 SMTP，背景通知留下連線拒絕訊息；本輪不涵蓋郵件送達。

- **PASS**：前端 `npm ci --ignore-scripts --no-audit --no-fund`、`npm run build`。
- **PASS**：既有 `setup.spec.js`、`widgets.spec.js`、`FormEditor.spec.js`、`StartProcess.spec.js`、`DocumentDetail.spec.js` 共 43 項；`router/guard.spec.js` 修正 module mock 後另跑 17 項通過，合計 60 項。未削弱權限斷言，並移除測試 helper 吞掉導航例外的行為。
- **PASS**：`git diff --check`、建置腳本 `bash -n`、Docker Compose 合併設定檢查。
- **NOT_RUN**：瀏覽器操作／Core Web Vitals、負載測試、Docker 映像實際建置與遠端 CI；GitLab YAML 未以其官方 lint 執行驗證。
- **DEFERRED_BY_USER**：SDK 完整 conformance／release-check，依 UniForm [AGENTS.md 暫行開發模式](../../vendor/uni-form/AGENTS.md#暫行開發模式2026-10-22-起)；宿主未執行全套測試。

## 同日追加：待辦實機驗證

- 修正缺失的人員搜尋入口：登入者透過 `/api/org/users/search?keyword=...` 查詢；後端以既有組織服務設定呼叫 `/api/users/search`，只回傳 `userId`、`name`、`deptId`，最多 50 筆。正式組織服務需提供相同搜尋契約；本機 mock 已支援工號及姓名。
- 轉發任務以 `PENDING` 狀態呈現「完成轉發並交回」，保留原主管最後核決；直接完成仍在轉發的任務回 409。
- 加簽人取得自身子任務的唯讀表單與專用意見入口。後端先驗持有者，再解析父任務鎖定版本；hidden 欄位仍裁切，沒有授予父案件一般讀寫權限。
- 多人加簽未全數完成時禁止原主管同意、退件或拒絕；意見保存於原任務，完成後恢復核決。對話框鎖定重複送出，部分建立成功後只重試尚未建立的人員。
- 修正拒絕結案誤分類為 completed：歷史清單批次查詢流程層 rejected 結果，排除任務區域同名變數；瀏覽器確認拒絕案只出現在「已拒絕」，轉發及加簽核准案留在「已完成」。`HistoryProcessOutcomeTest` 驗證兩個端點的拒絕、核准、取消、執行中狀態通過。
- 瀏覽器已實際完成轉發、交回、原主管核准，以及兩人加簽、逐人完成、原主管核准；確認表單資料與各自意見保存、待辦移轉正確。使用獨立合成測試案件，保留原有案件。
- 最小驗證：`DocumentDetail.spec.js` 10 項、前端正式建置通過；後端既有待辦／加簽／表單測試 44 項與搜尋／加簽安全回歸 38 項分批通過（兩批含重疊測試，不相加為唯一測試總數）。未執行全套測試或負載測試。

## 後續邊界

- `.gitmodules` 改為來源 repo 的內網 SSH URL；CI runner 需有內網連線及讀取授權，僅修改建置流程不會自動取得這些能力。
- 部分案件／歷史列表仍未分頁；若資料量增長，下一步應同時定義 API 分頁與前端操作方式，再量測查詢時間。
- `CLAUDE.md` 尚有 Boot 3／Flowable 7 的歷史描述；目前技術版本以 `pom.xml` 與根目錄 README 為準。本次未掃描改寫歷史規劃文件。
