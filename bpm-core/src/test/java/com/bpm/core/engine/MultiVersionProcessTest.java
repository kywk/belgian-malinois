package com.bpm.core.engine;

import com.bpm.core.form.repository.FormDefinitionRepository;
import com.bpm.core.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * #52 多版本流程並行處理的驗收。
 *
 * <h2>要釘死的事實</h2>
 *
 * <p>backlog #52 的現況是「依賴 Flowable 預設行為＋表單版本鎖定；無專門測試」。
 * 「依賴預設行為」不是保證：升級 Flowable（Stage 5 的 7→8，backlog #70）
 * 或有人「順手」把啟動路徑改成明確指定 {@code processDefinitionId}，都可能讓
 * <b>舊案被換到新版流程定義</b>，而症狀是案件走到新版的關卡、舊版關卡消失，
 * 通常要等到有人簽錯關或案件卡死才會發現。所以這裡用測試把它變成事實。
 *
 * <ol>
 *   <li><b>同一 key 的 v1／v2 並存</b>：v2 部署後啟動的新實例走 v2；
 *       v1 已在跑的實例仍鎖在 v1 —— 且不只是 {@code processDefinitionId}
 *       這個欄位，連「完成後往哪裡走」都必須走 v1 的順序流。</li>
 *   <li><b>表單版本鎖</b>：實例啟動時把 {@code _formVersions} 寫進流程變數
 *       （{@code FormVersionLocker}）；之後表單發布 v2，舊實例仍以 v1 繼續，
 *       連後續關卡的待辦 API 都回報 {@code formVersion=1}。</li>
 *   <li><b>版本查詢／歷史查詢</b>：{@code GET /api/process-definitions} 在
 *       新舊並存時的語意（含 {@code latestVersion=true}），以及
 *       {@code /{id}/resourcedata} 讀回的確實是該版本的 XML。</li>
 * </ol>
 *
 * <h2>⚠️ 為什麼每一個「舊案還是舊版」斷言都先有一條「新案確實是新版」</h2>
 *
 * <p>只斷言「舊實例的版本是 1」的話，一個「所有實例都停在 v1」的錯誤實作
 * （例如 v2 根本沒有生效）也會讓測試全綠 —— 那正是版本測試最常見的假綠。
 * 因此：
 * <ul>
 *   <li>測試 1 先斷言 v2 部署後啟動的實例版本是 2（且 {@code pd1.getId() != pd2.getId()}），
 *       再回頭斷言 v1 的實例仍在 1，最後用「完成後走哪條路」證明兩者真的執行不同模型。</li>
 *   <li>測試 2 在表單發布 v2 之後，用<b>同一個流程定義</b>再啟動一個新實例：
 *       新實例鎖 v2、舊實例鎖 v1。同一條程式碼路徑、只有啟動時間不同，
 *       排除了「表單版本根本沒更新」與「版本鎖永遠回舊版」兩種假綠。</li>
 * </ul>
 *
 * <h2>負向控制組（2026-10-02 實測）</h2>
 *
 * <p>不動主程式，只把測試的期待值暫時改成相反，確認斷言咬得住真正的行為差異：
 * 把測試 1 的「舊實例版本＝1」改成 {@code isEqualTo(2)}，失敗訊息是
 * {@code expected: 2 but was: 1}；把測試 2 的「發布 v2 後舊實例仍鎖 v1」
 * 改成 {@code containsEntry(formKey, 2)}，失敗訊息是
 * {@code ["&lt;formKey&gt;"=1 (expected: 2)]}。兩條都轉紅，測試 3 不受影響；
 * 還原後（SHA-256 與備份一致）三條全綠。
 */
class MultiVersionProcessTest extends IntegrationTestBase {

    @Autowired private RepositoryService repositoryService;
    @Autowired private RuntimeService runtimeService;
    @Autowired private TaskService taskService;
    @Autowired private HistoryService historyService;
    @Autowired private FormDefinitionRepository defRepo;

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 表單設計者：MockPermController 的 fixture 中持有 {@code bpm:form:design}。 */
    private static final String DESIGNER = "mgr001";

    /** 流程的啟動者／任務受理人。 */
    private static final String USER = "user001";

    // ── 工具 ───────────────────────────────────────────────────────

    /** 每個測試用自己的 key，避免與其他測試（共用同一組容器）互相污染。 */
    private static String uniqueKey(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private ProcessDefinition deploy(String key, String xml) {
        var deployment = repositoryService.createDeployment()
                .name(key + "-" + System.nanoTime())
                .addString(key + ".bpmn20.xml", xml)
                .deploy();
        return repositoryService.createProcessDefinitionQuery()
                .deploymentId(deployment.getId()).singleResult();
    }

    /** 走正式的內部啟動端點（ProcessController 會在這裡呼叫 FormVersionLocker）。 */
    private String startInstance(String key) throws Exception {
        String body = mockMvc.perform(post("/api/process-instances")
                        .header("X-User-Id", USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"processDefinitionKey\":\"" + key + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return JSON.readTree(body).get("processInstanceId").asText();
    }

    private void complete(String taskId, String userId) throws Exception {
        mockMvc.perform(put("/api/tasks/{id}", taskId)
                        .header("X-User-Id", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"complete\",\"variables\":[]}"))
                .andExpect(status().isOk());
    }

    private ProcessInstance instance(String processInstanceId) {
        ProcessInstance pi = runtimeService.createProcessInstanceQuery()
                .processInstanceId(processInstanceId).singleResult();
        assertThat(pi).as("流程實例必須仍在執行中: " + processInstanceId).isNotNull();
        return pi;
    }

    private Task singleTask(String processInstanceId) {
        List<Task> tasks = taskService.createTaskQuery()
                .processInstanceId(processInstanceId).list();
        assertThat(tasks).as("實例 " + processInstanceId + " 應只有一個待辦任務").hasSize(1);
        return tasks.get(0);
    }

    private HistoricProcessInstance historic(String processInstanceId) {
        HistoricProcessInstance hpi = historyService.createHistoricProcessInstanceQuery()
                .processInstanceId(processInstanceId).singleResult();
        assertThat(hpi).as("歷史查詢必須看得到已結束的實例: " + processInstanceId).isNotNull();
        return hpi;
    }

    /** 從待辦清單 API 取出某個任務的表示法（含它回報的 formVersion）。 */
    private JsonNode taskInInbox(String taskId, String assignee) throws Exception {
        String body = mockMvc.perform(get("/api/tasks").param("assignee", assignee)
                        .header("X-User-Id", assignee))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        for (JsonNode t : JSON.readTree(body)) {
            if (taskId.equals(t.path("taskId").asText())) return t;
        }
        throw new AssertionError("待辦清單找不到任務 " + taskId + "，實際回應: " + body);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Integer> lockedFormVersions(String processInstanceId) {
        Object v = runtimeService.getVariable(processInstanceId, "_formVersions");
        assertThat(v)
                .as("實例啟動時必須由 FormVersionLocker 寫入 _formVersions")
                .isInstanceOf(Map.class);
        return (Map<String, Integer>) v;
    }

    // ── BPMN ───────────────────────────────────────────────────────

    /** v1：單一關卡，完成後直接結束。 */
    private static String processV1(String key) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:flowable="http://flowable.org/bpmn"
                             targetNamespace="http://bpm.com/multiversion">
                  <process id="%s" name="多版本流程 v1" isExecutable="true">
                    <startEvent id="start"/>
                    <sequenceFlow id="f1" sourceRef="start" targetRef="stepV1"/>
                    <userTask id="stepV1" name="v1 關卡" flowable:assignee="%s"/>
                    <sequenceFlow id="f2" sourceRef="stepV1" targetRef="endV1"/>
                    <endEvent id="endV1"/>
                  </process>
                </definitions>
                """.formatted(key, USER);
    }

    /**
     * v2：第一個關卡的 id 就換了，而且後面多一關。
     * 「完成第一關之後會多出第二關」是 v1 絕對不會發生的事 ——
     * 用它來分辨實例執行的是哪一個模型，而不是只比對 id 字串。
     */
    private static String processV2(String key) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:flowable="http://flowable.org/bpmn"
                             targetNamespace="http://bpm.com/multiversion">
                  <process id="%s" name="多版本流程 v2" isExecutable="true">
                    <startEvent id="startV2"/>
                    <sequenceFlow id="f1" sourceRef="startV2" targetRef="stepV2"/>
                    <userTask id="stepV2" name="v2 關卡" flowable:assignee="%s"/>
                    <sequenceFlow id="f2" sourceRef="stepV2" targetRef="extraV2"/>
                    <userTask id="extraV2" name="v2 第二關" flowable:assignee="%s"/>
                    <sequenceFlow id="f3" sourceRef="extraV2" targetRef="endV2"/>
                    <endEvent id="endV2"/>
                  </process>
                </definitions>
                """.formatted(key, USER, USER);
    }

    /** 兩個關卡共用同一個 formKey：用來驗證表單鎖會跟著實例走到後續任務。 */
    private static String formProcess(String key, String formKey) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:flowable="http://flowable.org/bpmn"
                             targetNamespace="http://bpm.com/multiversion">
                  <process id="%s" name="表單版本鎖流程" isExecutable="true">
                    <startEvent id="start"/>
                    <sequenceFlow id="f1" sourceRef="start" targetRef="first"/>
                    <userTask id="first" name="填表第一關" flowable:assignee="%s"
                              flowable:formKey="%s"/>
                    <sequenceFlow id="f2" sourceRef="first" targetRef="second"/>
                    <userTask id="second" name="填表第二關" flowable:assignee="%s"
                              flowable:formKey="%s"/>
                    <sequenceFlow id="f3" sourceRef="second" targetRef="end"/>
                    <endEvent id="end"/>
                  </process>
                </definitions>
                """.formatted(key, USER, formKey, USER, formKey);
    }

    // ── 測試 1：流程定義版本 ───────────────────────────────────────

    @Test
    @DisplayName("#52：v2 部署後新案走 v2，v1 已在跑的實例連路由都留在 v1")
    void runningInstanceStaysOnItsDefinitionVersionWhileNewInstancesUseTheLatest()
            throws Exception {
        String key = uniqueKey("mv52-proc");
        ProcessDefinition pd1 = deploy(key, processV1(key));
        assertThat(pd1.getVersion()).as("第一次部署必須是 v1").isEqualTo(1);

        String oldPid = startInstance(key);
        ProcessInstance oldBefore = instance(oldPid);
        assertThat(oldBefore.getProcessDefinitionId()).isEqualTo(pd1.getId());
        assertThat(oldBefore.getProcessDefinitionVersion()).isEqualTo(1);
        Task oldFirst = singleTask(oldPid);
        assertThat(oldFirst.getTaskDefinitionKey())
                .as("v1 實例的第一關必須是 v1 的 stepV1").isEqualTo("stepV1");

        // 部署 v2 → 新啟動的實例走 v2
        ProcessDefinition pd2 = deploy(key, processV2(key));
        assertThat(pd2.getVersion()).as("第二次部署必須拿到版號 2").isEqualTo(2);
        assertThat(pd2.getId())
                .as("v1 與 v2 必須是兩個不同的流程定義 —— 否則後面的對照毫無意義")
                .isNotEqualTo(pd1.getId());

        String newPid = startInstance(key);
        ProcessInstance newInstance = instance(newPid);
        assertThat(newInstance.getProcessDefinitionId())
                .as("v2 部署後，新實例必須綁到 v2 的定義").isEqualTo(pd2.getId());
        assertThat(newInstance.getProcessDefinitionVersion()).isEqualTo(2);
        Task newFirst = singleTask(newPid);
        assertThat(newFirst.getTaskDefinitionKey())
                .as("新實例的第一關必須是 v2 的 stepV2").isEqualTo("stepV2");

        // v2 部署之後，「已在跑」的 v1 實例仍必須留在 v1
        ProcessInstance oldAfter = instance(oldPid);
        assertThat(oldAfter.getProcessDefinitionId())
                .as("v2 部署不得把已在跑的實例搬到新定義").isEqualTo(pd1.getId());
        assertThat(oldAfter.getProcessDefinitionVersion())
                .as("已在跑的實例必須繼續是 v1").isEqualTo(1);
        assertThat(singleTask(oldPid).getTaskDefinitionKey()).isEqualTo("stepV1");

        // 最強的事實：完成第一關之後走的是哪一條路。
        // v1 完成後直接結束；v2 完成後會多一關。若引擎誤用 v2，
        // 完成 oldPid 的第一關就會多出 extraV2（或直接炸掉）。
        complete(oldFirst.getId(), USER);
        assertThat(runtimeService.createProcessInstanceQuery()
                .processInstanceId(oldPid).singleResult())
                .as("v1 實例完成唯一關卡後必須結束（v1 沒有任何後續關卡）")
                .isNull();
        assertThat(taskService.createTaskQuery().processInstanceId(oldPid).count())
                .as("v1 實例結束後不得留下任何任務，尤其不得出現 v2 的 extraV2")
                .isZero();

        // 反向控制：v2 的實例完成第一關後「必須」多出一關。
        // 少了這條，一個「兩邊都走 v1」的錯誤實作也會讓上面全綠。
        complete(newFirst.getId(), USER);
        List<Task> newTasks = taskService.createTaskQuery()
                .processInstanceId(newPid).list();
        assertThat(newTasks).as("v2 的路徑在完成第一關後應產生第二關").hasSize(1);
        assertThat(newTasks.get(0).getTaskDefinitionKey())
                .as("第二關必須是 v2 才有的 extraV2").isEqualTo("extraV2");
        complete(newTasks.get(0).getId(), USER);
        assertThat(runtimeService.createProcessInstanceQuery()
                .processInstanceId(newPid).singleResult()).isNull();

        // 歷史查詢：已結束的兩案各自綁在自己的版本上
        HistoricProcessInstance hOld = historic(oldPid);
        assertThat(hOld.getProcessDefinitionId()).isEqualTo(pd1.getId());
        assertThat(hOld.getProcessDefinitionVersion()).isEqualTo(1);
        assertThat(hOld.getEndTime()).as("v1 實例必須真的已結束").isNotNull();

        HistoricProcessInstance hNew = historic(newPid);
        assertThat(hNew.getProcessDefinitionId()).isEqualTo(pd2.getId());
        assertThat(hNew.getProcessDefinitionVersion()).isEqualTo(2);
        assertThat(hNew.getEndTime()).isNotNull();
    }

    // ── 測試 2：表單版本鎖 ─────────────────────────────────────────

    @Test
    @DisplayName("#52：表單發布 v2 後，舊實例仍以鎖定的 v1 繼續（新實例同路徑鎖 v2）")
    void runningInstanceKeepsItsLockedFormVersionWhenANewFormVersionIsPublished()
            throws Exception {
        String formKey = uniqueKey("mv52-form");
        publishForm(formKey, 1);

        String key = uniqueKey("mv52-formproc");
        ProcessDefinition pd = deploy(key, formProcess(key, formKey));
        assertThat(pd.getVersion()).isEqualTo(1);

        String oldPid = startInstance(key);
        Task oldFirst = singleTask(oldPid);
        assertThat(oldFirst.getTaskDefinitionKey()).isEqualTo("first");
        assertThat(oldFirst.getFormKey()).isEqualTo(formKey);

        // 啟動時就鎖定：流程變數裡記的是 v1
        assertThat(lockedFormVersions(oldPid))
                .as("實例啟動時必須鎖定表單版本，否則後續會跟著最新版漂移")
                .hasSize(1)
                .containsEntry(formKey, 1);
        assertThat(taskInInbox(oldFirst.getId(), USER).path("formVersion").asInt())
                .as("待辦 API 必須回報鎖定的 v1").isEqualTo(1);

        // 發布表單 v2 —— 這是「已發布表單的改版路徑」，不是直接塞 DB
        publishForm(formKey, 2);

        // 先斷言「新版本真的存在且查得到」——否則下面的「舊實例還是 1」
        // 可能只是因為 v2 根本沒發布成功。
        assertThat(defRepo.findLatestPublished(formKey).orElseThrow().getVersion())
                .as("v2 必須是現在的最新已發布版本").isEqualTo(2);
        assertThat(apiFormVersion(formKey, null))
                .as("GET /api/forms/{formKey} 必須看到 v2").isEqualTo(2);
        assertThat(apiFormVersion(formKey, 1))
                .as("v1 必須仍可查（進行中案件的資料來源）").isEqualTo(1);

        // 舊實例的鎖不得被新版本換掉
        assertThat(lockedFormVersions(oldPid))
                .as("表單發布 v2 後，舊實例的 _formVersions 必須仍是 v1")
                .hasSize(1)
                .containsEntry(formKey, 1);
        assertThat(taskInInbox(oldFirst.getId(), USER).path("formVersion").asInt())
                .as("舊實例的待辦仍必須回報 v1").isEqualTo(1);

        // 同一條程式碼路徑的對照組：同一個流程定義、v2 之後才啟動的新實例
        // 必須鎖到 v2。沒有這一條，「鎖永遠回 v1」的錯誤實作也會全綠。
        String newPid = startInstance(key);
        Task newFirst = singleTask(newPid);
        assertThat(lockedFormVersions(newPid))
                .as("v2 發布後啟動的新實例必須鎖定 v2")
                .hasSize(1)
                .containsEntry(formKey, 2);
        assertThat(taskInInbox(newFirst.getId(), USER).path("formVersion").asInt())
                .as("新實例的待辦必須回報 v2").isEqualTo(2);

        // 鎖必須跟著實例走到後續關卡，而不是只在第一關生效
        complete(oldFirst.getId(), USER);
        Task oldSecond = singleTask(oldPid);
        assertThat(oldSecond.getTaskDefinitionKey()).isEqualTo("second");
        assertThat(lockedFormVersions(oldPid))
                .as("後續關卡仍必須使用同一個鎖")
                .containsEntry(formKey, 1);
        assertThat(taskInInbox(oldSecond.getId(), USER).path("formVersion").asInt())
                .as("後續關卡的待辦也必須回報 v1").isEqualTo(1);

        // 收尾（共用容器，不要把執行中的案件留得到處都是）
        complete(oldSecond.getId(), USER);
        complete(newFirst.getId(), USER);
    }

    private void publishForm(String formKey, int expectedVersion) throws Exception {
        if (expectedVersion == 1) {
            createForm(formKey);
            return;
        }
        String draftBody = mockMvc.perform(post("/api/forms/{formKey}/revisions", formKey)
                        .header("X-User-Id", DESIGNER))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode draft = JSON.readTree(draftBody);
        assertThat(draft.get("version").asInt()).isEqualTo(expectedVersion);
        assertThat(draft.get("status").asText()).isEqualTo("draft");

        String draftId = draft.get("id").asText();
        mockMvc.perform(put("/api/forms/{id}", draftId)
                        .header("X-User-Id", DESIGNER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"改版後的審核表\","
                                + "\"schemaJson\":\"{\\\"marker\\\":\\\"form-v" + expectedVersion + "\\\"}\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/forms/{id}/publish", draftId)
                        .header("X-User-Id", DESIGNER))
                .andExpect(status().isOk());
    }

    private void createForm(String formKey) throws Exception {
        String body = mockMvc.perform(post("/api/forms")
                        .header("X-User-Id", DESIGNER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"formKey\":\"" + formKey + "\",\"name\":\"版本鎖測試表\","
                                + "\"schemaJson\":\"{\\\"marker\\\":\\\"form-v1\\\"}\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(JSON.readTree(body).get("version").asInt()).isEqualTo(1);
        mockMvc.perform(post("/api/forms/{id}/publish", JSON.readTree(body).get("id").asText())
                        .header("X-User-Id", DESIGNER))
                .andExpect(status().isOk());
    }

    /** GET /api/forms/{formKey}（version 省略＝最新已發布）回傳的版本號。 */
    private int apiFormVersion(String formKey, Integer version) throws Exception {
        var request = get("/api/forms/{formKey}", formKey);
        if (version != null) request = request.param("version", String.valueOf(version));
        String body = mockMvc.perform(request)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return JSON.readTree(body).get("version").asInt();
    }

    // ── 測試 3：版本查詢與資源讀取 ─────────────────────────────────

    @Test
    @DisplayName("#52：新舊版本並存時，流程定義清單／latest 篩選／XML 讀取各自正確")
    void versionQueriesStayConsistentWhenOldAndNewCoexist() throws Exception {
        String key = uniqueKey("mv52-query");
        ProcessDefinition pd1 = deploy(key, processV1(key));
        ProcessDefinition pd2 = deploy(key, processV2(key));
        assertThat(pd1.getVersion()).isEqualTo(1);
        assertThat(pd2.getVersion()).isEqualTo(2);

        // 不過濾：同一 key 的兩個版本都要在
        List<Integer> all = definitionVersions(key, false);
        assertThat(all).as("兩個版本並存時清單必須同時回報 v1 與 v2")
                .containsExactly(2, 1);

        // latestVersion=true：只回最新版
        assertThat(definitionVersions(key, true))
                .as("latestVersion=true 只能回 v2；回兩筆會讓挑版本的前端邏輯壞掉")
                .containsExactly(2);

        // resourcedata 讀回的必須是該版本自己的 XML
        String v1Xml = mockMvc.perform(get("/api/process-definitions/{id}/resourcedata", pd1.getId()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(v1Xml).as("v1 的資源必須含 v1 的關卡").contains("stepV1");
        assertThat(v1Xml).as("v1 的資源不得混入 v2 的關卡").doesNotContain("stepV2");

        String v2Xml = mockMvc.perform(get("/api/process-definitions/{id}/resourcedata", pd2.getId()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(v2Xml).as("v2 的資源必須含 v2 的關卡").contains("stepV2");
        assertThat(v2Xml).as("v2 的資源不得退回 v1 的關卡").doesNotContain("stepV1");
    }

    /** GET /api/process-definitions 中，屬於指定 key 的版本號（清單本身已按版本 desc）。 */
    private List<Integer> definitionVersions(String key, boolean latestOnly) throws Exception {
        var request = get("/api/process-definitions");
        if (latestOnly) request = request.param("latestVersion", "true");
        String body = mockMvc.perform(request)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        List<Integer> versions = new ArrayList<>();
        for (JsonNode pd : JSON.readTree(body)) {
            if (key.equals(pd.path("key").asText())) {
                versions.add(pd.path("version").asInt());
            }
        }
        return versions;
    }
}
