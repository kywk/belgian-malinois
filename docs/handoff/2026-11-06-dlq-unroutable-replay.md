# 停放訊息重放時缺少 binding

在獨立 RabbitMQ Testcontainer 建立 direct exchange、目的 queue 和 parking queue，先省略 binding。測試以 MockMvc 呼叫實際 controller 的 POST /api/admin/dlq/replay?queue=bpm&max=1；稽核 publisher 使用 mock，這輪未載入登入與授權 filter。每次執行使用新容器、新 messageId 與新 body。

| 時點 | HTTP | replayed / failed / remaining | broker 實查 |
|---|---|---|---|
| 舊版，無 binding | 200 | 1 / 0 / 0 | parking 為 0；紅燈斷言記錄了 queue depth 與完整回應 |
| 修正後，無 binding | 200 | 0 / 1 / 1 | parking 為 1，目的 queue 空 |
| 建立 binding，再重放同一筆 | 200 | 1 / 0 / 0 | 目的 queue 取回一筆，逐 byte 和 messageId 均相等，再讀兩個 queue 均為空 |

重放使用專屬 mandatory template，每次發布建立獨立 CorrelationData，等待 broker confirm 並檢查 return，再 ack parking 原件。發布失敗、逾時或路由失敗走原有 nack/requeue 路徑。確認遺失仍可能造成之後重複投遞。

從 BPM repo 根目錄、使用 JDK 21 與本機 Docker 執行。要重現紅燈，可在臨時目錄解開修正前版本，只帶入回歸測試。整合測試不使用共用 localenv。

```sh
red=$(mktemp -d)
git archive f678a25 bpm-core | tar -x -C "$red"
testfile=src/test/java/com/bpm/core/dlq/DlqReplayPublishFailureTest.java
cp "bpm-core/$testfile" "$red/bpm-core/$testfile"
(cd "$red/bpm-core" && mvn -o -B -ntp '-Dtest=DlqReplayPublishFailureTest#missingBindingKeepsParkedMessageUntilRouteReturns' test)
```

修正後定向測試共 10 項通過，含無路由、同步發布例外、連線失敗與目的地解析。

```sh
(cd bpm-core && mvn -o -B -ntp -Dtest=DlqReplayPublishFailureTest,DlqReplayServiceBrokerFailureTest,DlqReplayServiceDestinationTest test)
```
