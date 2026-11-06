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

## 已送達、publisher confirm 在回程遺失

獨立 RabbitMQ 先停放兩筆新訊息 A、B，各自有 messageId 與可逐 byte 比對的 body。只有重放發布端的 AMQP 連線經過 loopback 代理器；代理器丟棄 broker 回傳的第一個 basic.ack frame，再透傳後續 frame。停放訊息的讀取、ack、nack，以及目的 queue 的查驗均直連 broker。測試呼叫實際重放 controller，使用 MockMvc 與模擬稽核 publisher。

| 操作 | HTTP | replayed / failed / remaining | 目的 queue 實讀 |
|---|---|---|---|
| max=2，A 的 confirm 遺失 | 200 | 0 / 1 / 2 | A 一份；parking 深度實查為 2，這一批停在 A |
| max=1，重放 A，confirm 正常 | 200 | 1 / 0 / 1 | A 第二份，與首份相同 messageId、相同 bytes |
| max=1，重放 B | 200 | 1 / 0 / 0 | B 一份；讀完後 parking 與目的 queue 皆空 |

代理器共見三次 publisher confirm。去重責任延伸到下游；這次直接從目的 queue 取出三份訊息，業務端有無重複執行須另查接收方。重跑使用新容器與新識別碼。

```sh
(cd bpm-core && mvn -o -B -ntp -Dtest=DlqReplayLostConfirmTest,DlqReplayPublishFailureTest,DlqReplayServiceBrokerFailureTest,DlqReplayServiceDestinationTest test)
(cd bpm-core && mvn -o -B -ntp -Dtest=DlqAlertReplayTest test)
```

本輪定向測試 11/11 通過，完整 DlqAlertReplayTest 在獨立 MSSQL、Redis、RabbitMQ 上 7/7 通過，涵蓋管理授權、原生死信與稽核、origin 路由、fallback、批量上限。完整整合輪觀察到原生 webhook 的 AMQP messageId 可以為 null；此時須以業務鍵關聯。定向測試完成三次投遞斷言後，關閉連線時 Spring deferred channel pool 曾記錄 clean connection shutdown 引發的背景例外；該輪 Surefire 無失敗、錯誤或跳過。
