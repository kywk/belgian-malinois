# Greyhound 完整前後端回歸（2026-10-26）

起始版本為 `35a3efb`，UniForm pin 為 `8b7c657`。本輪依使用者要求執行 Greyhound 全部既有自動化測試，並修正測試失敗後重跑。

## 結果

| 範圍 | 命令 | 結果 |
| --- | --- | --- |
| 後端乾淨建置、單元與整合測試 | `cd bpm-core && mvn -B clean verify` | 1,685 項通過，失敗 0、錯誤 0、跳過 0；JAR 打包成功 |
| 前端乾淨安裝 | `cd bpm-frontend && npm ci --no-audit --no-fund` | 314 個套件安裝成功，lockfile integrity 正常 |
| 前端全部測試 | `cd bpm-frontend && npm test` | 25 個測試檔、263 項通過，失敗 0、跳過 0 |
| 前端正式建置 | `cd bpm-frontend && npm run build` | 2,376 個模組建置成功 |
| 本機 API 黑箱驗收 | `bash scripts/acceptance-test.sh http://127.0.0.1:18110 http://127.0.0.1:18110` | 請假／採購共 7 條案例全部通過 |

環境為 JDK 21、Node 24.21.0、Bash 4 以上；後端透過 Testcontainers 啟動隔離的真實 MSSQL、RabbitMQ、Redis。黑箱案例使用既有 localenv 開發服務與合成測試資料。最後檢查前端及後端健康端點皆回 HTTP 200。

## 本次修正

前端首次全量執行為 262 項成功、1 項失敗。`router/guard.spec.js` 原本用 5ms 與 30ms 計時器競速，判斷權限回應前導航是否仍在等待；高負載下兩個計時器都可能在斷言前完成，造成誤判。

改以受控 Promise 暫停權限回應，透過 `flushPromises` 處理待執行工作，再確認導航尚未完成；釋放回應後仍驗證正確導航到稽核頁。保留權限等待與最終路由斷言，未修改正式程式。修正後重跑全部 263 項通過。

## 驗證邊界

- 本輪完整執行 Greyhound Maven／Vitest 測試與 API 驗收；沒有重跑 UniForm 本身的完整 conformance／release-check，也未執行負載測試、Docker 映像建置或遠端 CI。
- 前端仍有既有大型 chunk 警告，以及 App 測試替身的 router injection 警告；皆未使測試或建置失敗。
- 首次乾淨安裝受網路沙箱 DNS 限制，允許網路存取後安裝成功；未變更依賴或 lockfile。
