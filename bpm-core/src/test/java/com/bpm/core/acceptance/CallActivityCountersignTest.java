package com.bpm.core.acceptance;

import com.bpm.core.lint.BpmnLintService;
import com.bpm.core.support.IntegrationTestBase;
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
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 特定子流程加簽（Call Activity）的驗收迴歸測試（backlog #4、spec §4.4.2）。
 *
 * <p>#3 的通用加簽走 {@code CountersignController} 的動態子任務，本工項補的是
 * 另一種模式：預定義的加簽子流程模板（{@code countersign-review}），父流程以
 * Call Activity 呼叫、用 {@code flowable:in}／{@code flowable:out} 做變數映射。
 *
 * <h2>這一組測試要釘死的事實</h2>
 *
 * <ol>
 *   <li><b>模板能真的部署</b>：{@code processes/countersign-review.bpmn20.xml} 走
 *       {@code BpmnLintService} 是 {@code valid=true}，且走真的
 *       {@code POST /api/deployments}（{@code seed-data.sh} 的那條路）會產生
 *       {@code countersign-review} 的流程定義。名稱刻意帶
 *       {@code .bpmn20.xml}：Flowable 只解析這個後綴的資源，少了它會
 *       「部署成功但不產生定義」（見 seed-data.sh 的同一段註解）。</li>
 *   <li><b>in 映射決定受理人與任務名稱</b>：子任務的 assignee 是父流程傳入的
 *       變數值、name 是 {@code countersignTaskName} 覆寫值。父流程的原始變數名
 *       （{@code legalReviewer}）<b>不會</b>出現在子流程 —— 這是
 *       {@code inheritVariables="false"} 的直接證據，也證明不是「碰巧同名」。</li>
 *   <li><b>out 映射帶回結果且不改名衝突</b>：子流程完成時的
 *       {@code approved}／{@code rejected} 以 out 映射改名成
 *       {@code countersignApproved}／{@code countersignRejected} 寫回父流程，
 *       父流程依此繼續走到 {@code endApproved}。</li>
 *   <li><b>歷史看得到父子實例</b>：子實例的 {@code processDefinitionKey} 是
 *       {@code countersign-review}、{@code superProcessInstanceId} 是父實例。</li>
 *   <li><b>R-19 的直接證據</b>：子流程任務的 processDefinitionKey 是子流程自己
 *       的 key（不是父流程的）。外部系統若要完成這個任務，必須被授權
 *       {@code countersign-review} —— 本測試不改授權程式，只把這個事實釘住
 *       （見 {@code ExternalApiController.completeTask} 的同段說明）。</li>
 * </ol>
 *
 * <h2>為什麼父流程用測試內動態部署</h2>
 *
 * <p>父流程不是出廠資產，只是本測試的載體：用唯一的 key
 * （{@code callactivity-test-<random>}）動態部署，避免與其他測試共用資料庫時
 * 互相污染（{@link IntegrationTestBase} 的容器是 static，所有測試共用 DB）。
 * 動態部署走 {@code RepositoryService}（不經 lint）—— 要驗 lint 的是<b>出廠
 * 模板</b>，不是這個測試載體。
 *
 * <h2>負向控制組（實測記錄）</h2>
 *
 * <p>把父流程的 {@code flowable:in source="legalReviewer" target="countersignAssignee"}
 * 整行移除後，本類別的 e2e 測試轉紅，但紅的位置不是 assignee 斷言 ——
 * 是在 {@code startProcessInstanceByKey} 就拋
 * {@code Unknown property used in expression: ${countersignAssignee}}：
 * 變數完全缺席時，Flowable 對 assignee 的未定義識別字是吵鬧地失敗
 * （啟動回滾），不是靜默卡死。靜默卡死發生在「有映射、但值為 null／空白」
 * 的情形（見模板與 V5 migration 的註解）。
 *
 * <p>把兩條 {@code flowable:out} 移除後，紅在完成子任務的 HTTP 請求上：
 * gateway 條件 {@code ${countersignApproved == true}} 拋
 * {@code Unknown property used in expression}（JUEL 不把未定義識別字當成
 * false，default flow 不會接手）。還原後全綠。
 */
class CallActivityCountersignTest extends IntegrationTestBase {

    private static final String TEMPLATE_RESOURCE = "processes/countersign-review.bpmn20.xml";
    private static final String CHILD_KEY = "countersign-review";
    private static final String ASSIGNEE = "user003";

    @Autowired private RepositoryService repositoryService;
    @Autowired private RuntimeService runtimeService;
    @Autowired private TaskService taskService;
    @Autowired private HistoryService historyService;
    @Autowired private BpmnLintService lintService;

    private static String templateXml() throws Exception {
        return new String(CallActivityCountersignTest.class.getClassLoader()
                .getResourceAsStream(TEMPLATE_RESOURCE).readAllBytes(), StandardCharsets.UTF_8);
    }

    /**
     * 走出廠模板的部署路徑（{@code seed-data.sh} 用的同一個端點）。
     *
     * @return deploymentId，呼叫端負責在測試結束時刪除（見各測試的 finally）
     */
    private String deployTemplateViaEndpoint() throws Exception {
        byte[] xml = templateXml().getBytes(StandardCharsets.UTF_8);
        String body = mockMvc.perform(multipart("/api/deployments")
                        .file(new MockMultipartFile("file", "countersign-review.bpmn20.xml", "text/xml", xml))
                        // ⚠️ 後綴不可省：見類別註解第 1 點。
                        .param("name", "countersign-review.bpmn20.xml")
                        .header("X-User-Id", "admin001"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return body.replaceAll(".*\"deploymentId\":\"([^\"]*)\".*", "$1");
    }

    /**
     * 父流程：start → Call Activity（呼叫 countersign-review）→ gateway → 兩個 end。
     *
     * <p>gateway 條件讀的是 out 映射後的 {@code countersignApproved}。
     * ⚠️ 負向控制組實測：把兩條 out 映射移除後，完成子任務時條件求值會拋
     * {@code Unknown property used in expression: ${countersignApproved == true}}
     * —— 父流程不是「走 default endRejected」而是完成請求整個失敗（JUEL 對
     * 未定義識別字不當成 false）。所以 out 映射的證據同時是「父流程真的依
     * 結果繼續」的證據。
     */
    private static String parentXml(String key) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:flowable="http://flowable.org/bpmn"
                             targetNamespace="http://bpm.com/test">
                  <process id="%s" isExecutable="true">
                    <startEvent id="start"/>
                    <sequenceFlow id="f1" sourceRef="start" targetRef="legalReview"/>
                    <callActivity id="legalReview" name="法務加簽"
                                  calledElement="%s"
                                  flowable:inheritVariables="false">
                      <extensionElements>
                        <flowable:in source="legalReviewer" target="countersignAssignee"/>
                        <flowable:in source="legalTaskName" target="countersignTaskName"/>
                        <flowable:out source="approved" target="countersignApproved"/>
                        <flowable:out source="rejected" target="countersignRejected"/>
                      </extensionElements>
                    </callActivity>
                    <sequenceFlow id="f2" sourceRef="legalReview" targetRef="gw"/>
                    <exclusiveGateway id="gw" name="加簽結果" default="toRejected"/>
                    <sequenceFlow id="toApproved" sourceRef="gw" targetRef="endApproved">
                      <conditionExpression>${countersignApproved == true}</conditionExpression>
                    </sequenceFlow>
                    <sequenceFlow id="toRejected" sourceRef="gw" targetRef="endRejected"/>
                    <endEvent id="endApproved" name="核准"/>
                    <endEvent id="endRejected" name="拒絕"/>
                  </process>
                </definitions>
                """.formatted(key, CHILD_KEY);
    }

    @Test
    @DisplayName("#4：出廠模板通過真的 lint（valid=true，含預期的 optional-assignee warning）且走部署端點產生流程定義")
    void shippedTemplatePassesLintAndDeploys() throws Exception {
        var lint = lintService.lint(templateXml());

        assertThat(lint.errors().stream().filter(e -> "error".equals(e.severity())).toList())
                .as("模板若有 error，seed-data.sh 部署會被 400 擋下。錯誤：%s", lint.errors())
                .isEmpty();
        assertThat(lint.valid()).isTrue();

        // ⚠️ 這條 warning 是刻意的取捨，不是待修的缺陷：countersignAssignee 是
        // 「父流程 in 映射帶入」的變數，規格上 required=false（見 V5 migration
        // 註解），而 lint 規則 k 對 assignee 使用非必填變數一律警告。
        // 釘住它的存在，讓日後若要「消 warning」的人必須先讀到那段理由。
        assertThat(lint.errors())
                .as("預期的 optional-assignee warning 不見了 —— 若規則改了，這裡要跟著更新")
                .anyMatch(e -> "optional-assignee".equals(e.rule()) && "countersignReview".equals(e.elementId()));

        // 最後走真的部署端點：證明「name 帶 .bpmn20.xml」真的產生流程定義，
        // 而不只是檔案上傳成功（ResourceNameUtil 只認 .bpmn20.xml／.bpmn）。
        String deploymentId = deployTemplateViaEndpoint();
        try {
            var definitions = repositoryService.createProcessDefinitionQuery()
                    .deploymentId(deploymentId).list();
            assertThat(definitions).extracting(ProcessDefinition::getKey)
                    .as("部署成功但沒有流程定義 —— 名稱少了 .bpmn20.xml 後綴就是這個症狀")
                    .containsExactly(CHILD_KEY);
        } finally {
            // 不留下新版本：共用 DB 下這會改變其他測試 startProcessInstanceByKey 撿到的版本。
            repositoryService.deleteDeployment(deploymentId, true);
        }
    }

    @Test
    @DisplayName("#4：父流程 Call Activity 呼叫子流程 —— in 映射決定受理人與任務名稱，out 映射帶結果回父流程")
    void callActivityMapsVariablesInAndOut() throws Exception {
        String templateDeploymentId = deployTemplateViaEndpoint();
        String parentKey = "callactivity-test-" + UUID.randomUUID().toString().substring(0, 8);
        var parentDeployment = repositoryService.createDeployment()
                .name(parentKey)
                .addString(parentKey + ".bpmn20.xml", parentXml(parentKey))
                .deploy();
        try {
            String parentId = runtimeService.startProcessInstanceByKey(parentKey,
                    Map.of("legalReviewer", ASSIGNEE, "legalTaskName", "法務複核")).getId();

            // ── 子流程實例：由父流程的 Call Activity 產生 ──────────────────
            ProcessInstance child = runtimeService.createProcessInstanceQuery()
                    .superProcessInstanceId(parentId).singleResult();
            assertThat(child)
                    .as("完成前沒有子流程實例 —— Call Activity 沒有被觸發或 calledElement 解析不到")
                    .isNotNull();
            assertThat(child.getProcessDefinitionKey()).isEqualTo(CHILD_KEY);

            Task childTask = taskService.createTaskQuery().processInstanceId(child.getId()).singleResult();
            assertThat(childTask).as("子流程應建立一個加簽任務").isNotNull();

            // in 映射：受理人與任務名稱都來自父流程傳入的變數。
            assertThat(childTask.getAssignee())
                    .as("assignee 不是父流程傳入的 legalReviewer 值 —— in 映射沒生效或映射到錯的值")
                    .isEqualTo(ASSIGNEE);
            assertThat(childTask.getName())
                    .as("countersignTaskName 沒有覆寫任務名稱（Flowable 對 name 做 EL 求值）")
                    .isEqualTo("法務複核");

            // inheritVariables="false" 的直接證據：父流程的原始變數名不在子流程，
            // 只有 in 映射的目標名在 —— 排除「碰巧同名所以看起來對」。
            String childExecutionId = childTask.getExecutionId();
            assertThat(runtimeService.hasVariable(childExecutionId, "legalReviewer")).isFalse();
            assertThat(runtimeService.hasVariable(childExecutionId, "legalTaskName")).isFalse();
            assertThat(runtimeService.getVariable(childExecutionId, "countersignAssignee"))
                    .isEqualTo(ASSIGNEE);

            // R-19：子流程任務的 process key 是子流程自己的（不是父流程的）。
            // 外部系統若要完成它，allowedProcessKeys 必須含 countersign-review。
            assertThat(repositoryService.getProcessDefinition(childTask.getProcessDefinitionId()).getKey())
                    .as("子流程任務的 processDefinitionKey 必須是 countersign-review（R-19）")
                    .isEqualTo(CHILD_KEY);

            // ── 完成子任務（走真的任務端點，與 ActionDialog 的完成路徑同一條）──
            mockMvc.perform(put("/api/tasks/{id}", childTask.getId())
                            .header("X-User-Id", ASSIGNEE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"action\":\"complete\",\"variables\":["
                                    + "{\"name\":\"approved\",\"value\":true},"
                                    + "{\"name\":\"rejected\",\"value\":false}]}"))
                    .andExpect(status().isOk());

            // out 映射：改名後寫回父流程。
            // ⚠️ 父流程在 gateway 之後立刻結束，runtime 執行已刪除 —— 必須讀
            // 歷史變數，不能在這裡用 runtimeService.getVariable(parentId, ...)。
            assertThat(historyService.createHistoricVariableInstanceQuery()
                    .processInstanceId(parentId).variableName("countersignApproved").singleResult().getValue())
                    .as("out 映射沒有把 approved 帶回父流程")
                    .isEqualTo(true);
            assertThat(historyService.createHistoricVariableInstanceQuery()
                    .processInstanceId(parentId).variableName("countersignRejected").singleResult().getValue())
                    .isEqualTo(false);

            // 父流程繼續：走 countersignApproved 的條件分支到 endApproved。
            HistoricProcessInstance historicParent = historyService.createHistoricProcessInstanceQuery()
                    .processInstanceId(parentId).singleResult();
            assertThat(historicParent).isNotNull();
            assertThat(historicParent.getEndActivityId())
                    .as("父流程沒有走到 endApproved —— out 映射失效時條件求值會失敗或走錯分支")
                    .isEqualTo("endApproved");

            // 歷史查詢看得到父子實例與連結。
            HistoricProcessInstance historicChild = historyService.createHistoricProcessInstanceQuery()
                    .processInstanceId(child.getId()).singleResult();
            assertThat(historicChild).as("子流程實例應在歷史中").isNotNull();
            assertThat(historicChild.getProcessDefinitionKey()).isEqualTo(CHILD_KEY);
            assertThat(historicChild.getSuperProcessInstanceId())
                    .as("子實例的 superProcessInstanceId 必須指向父實例")
                    .isEqualTo(parentId);
            assertThat(historicChild.getEndTime()).isNotNull();
        } finally {
            repositoryService.deleteDeployment(parentDeployment.getId(), true);
            repositoryService.deleteDeployment(templateDeploymentId, true);
        }
    }

    @Test
    @DisplayName("#4：呼叫端未映射 countersignTaskName 時，任務名稱走模板預設值")
    void countersignTaskNameFallsBackToDefault() {
        // 直接啟動子流程（不經父流程），只給 in 契約的必填輸入。
        ProcessInstance child = runtimeService.startProcessInstanceByKey(CHILD_KEY,
                Map.of("countersignAssignee", ASSIGNEE));
        try {
            Task task = taskService.createTaskQuery().processInstanceId(child.getId()).singleResult();
            assertThat(task).isNotNull();
            assertThat(task.getName())
                    .as("countersignTaskName 缺席時，三元式應求值成預設名稱而不是拋錯或留空")
                    .isEqualTo("加簽複核");
        } finally {
            // 收尾：讓子流程跑完，避免留下執行中實例影響其他測試。
            Task task = taskService.createTaskQuery().processInstanceId(child.getId()).singleResult();
            if (task != null) {
                taskService.complete(task.getId(), Map.of("approved", true, "rejected", false));
            }
        }
    }
}
