-- 預定義加簽子流程模板（processes/countersign-review.bpmn20.xml，backlog #4）的
-- 變數契約宣告。
--
-- ## 為什麼模板出貨要帶這份 seed
--
-- 模板的 UserTask 受理人是 countersignAssignee 這個流程變數的 EL 參照，而
-- BpmnLintService 的 undeclared-variable 規則是 error：沒有這份宣告，
-- seed-data.sh 部署模板會被 POST /api/deployments 以 400 擋下。變數規格是
-- 平台宣告流程變數的唯一正式管道（lint 訊息自己就這樣要求），所以模板與規格
-- 必須一起出貨，而不是叫部署者自己補。
--
-- ⚠️ 本檔不可出現 Flyway 的 placeholder 語法（錢號加左大括號）：Flyway 預設
-- 會把 SQL 裡的它當成 placeholder 求值，找不到值就讓整個 context 起不來。
-- 所以上面刻意用文字描述運算式而不寫出字面。
--
-- ## 為什麼 required 全部是 0
--
-- 這四個變數不是「呼叫端直接提供」的啟動變數：輸入由父流程的 flowable:in
-- 映射帶入，輸出由子流程任務完成時寫入。required=true 在
-- ExternalApiController.validateVariables 的語意是「呼叫這個 API 的 payload
-- 必須帶這個鍵」——把 countersignAssignee 標成 required 會變成「外部系統
-- 完成子任務時必須回送受理人」，那是錯的契約（R-19 允許外部完成子任務，
-- 但完成時該帶的是審核結果，不是受理人）。
--
-- 代價是 lint 規則 k 對 countersignAssignee 發 optional-assignee warning
-- （required=false 的變數用在 assignee）。刻意接受：warning 不擋部署，而且
-- 它描述的風險是真的 —— 呼叫端若映射了變數但值為 null／空白，子流程任務
-- 會建立成功卻沒有有效受理人（靜默卡死）。變數完全缺席時反而會吵鬧地失敗：
-- Flowable 對 assignee 的未定義識別字拋 Unknown property used in expression。
-- 測試（CallActivityCountersignTest）把 valid=true 與這條 warning 的存在
-- 一起釘住，讓日後想「消 warning」的人必須先讀到這段取捨。
--
-- N'' 前綴不可省略（見 V2／V4 的註解）：沒有它，中文會在寫入前就變成問號。
MERGE INTO bpm_process_variable_spec AS target
USING (VALUES
  ('countersign-review', 'countersignAssignee', 'string', 0,
   N'子流程 UserTask 的受理人。父流程以 flowable:in 映射帶入；未映射時任務沒有受理人。', 'user003'),
  ('countersign-review', 'countersignTaskName', 'string', 0,
   N'選填：覆寫子流程任務名稱；未提供時為「加簽複核」。', N'法務複核'),
  ('countersign-review', 'countersignApproved', 'boolean', 0,
   N'輸出：子流程任務完成時 approved 的結果，由 out 映射改名帶回父流程。', 'true'),
  ('countersign-review', 'countersignRejected', 'boolean', 0,
   N'輸出：子流程任務完成時 rejected 的結果，由 out 映射改名帶回父流程。', 'false')
) AS source (processDefinitionKey, variableName, variableType, required, description, example)
ON target.process_definition_key = source.processDefinitionKey
   AND target.variable_name = source.variableName
WHEN NOT MATCHED THEN
  INSERT (id, process_definition_key, variable_name, variable_type, required, description, example)
  VALUES (NEWID(), source.processDefinitionKey, source.variableName, source.variableType,
          source.required, source.description, source.example);
