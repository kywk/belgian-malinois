package com.bpm.core.engine;

import com.bpm.core.support.ExternalApiTestSink;
import com.bpm.core.support.IntegrationTestBase;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.runtime.Execution;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #45 端到端：BPMN 上的 {@code esignDelegate} 真的觸發外部簽核服務，
 * 流程停在 message catch event；回呼（#21 的喚醒動作）之後才續行。
 *
 * <h2>本測試組在證明什麼</h2>
 *
 * <ol>
 *   <li><b>觸發真的發生</b>：sink 收到 POST，body 是替換後的 payload。
 *       這裡刻意用 {@code expression} 形式讓 payload 帶上
 *       {@code ${execution.processInstanceId}} —— 回呼的 correlation 需要
 *       它，而 {@code stringValue} 的 {@code ${processInstanceId}} 拿不到
 *       （它不是流程變數）。這條路只有真實引擎的 ExpressionManager 走得通，
 *       單元測試的 {@code ${var}} 後備子集證明不了。</li>
 *   <li><b>delegate 不等待</b>：start 回來後流程是「等待中」狀態 ——
 *       {@code messageEventSubscriptionName} 查得到，且還沒有 UserTask。
 *       用 {@code runtimeService.messageEventReceived}（#21
 *       {@code CallbackController} 的喚醒動作）才讓流程續行到複核任務。</li>
 *   <li><b>失敗可建模</b>：非 2xx → {@code ESIGN_FAILED}、被政策拒絕 →
 *       {@code ESIGN_BLOCKED}，兩者都被 boundary error 接住走替代路徑；
 *       政策拒絕的那條同時證明請求數為 0。</li>
 * </ol>
 *
 * <p>{@code allowed-hosts} 沿用 #67／#49 的測試例外：{@code application-test.yml}
 * 把 {@code localhost} 列進 {@code bpm.webhook.allowed-hosts}，讓請求真的
 * 打得到 {@link ExternalApiTestSink}（#49 的通用外部 API sink，這裡重用）。
 * 政策預設拒絕 loopback 的行為由 {@code blockedUrl...} 用 {@code 127.0.0.1}
 * （與清單中的 {@code localhost} 是不同的字面 host）釘住。
 */
class ESignDelegateIntegrationTest extends IntegrationTestBase {

    @Autowired
    private RepositoryService repositoryService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private com.bpm.core.lint.BpmnLintService lintService;

    @BeforeEach
    void resetSink() {
        ExternalApiTestSink.reset();
    }

    private String sinkUrl(String name) {
        // ⚠️ 必須是 localhost：allowed-hosts 比對的是 URL 裡的字面 host。
        return "http://localhost:" + SERVLET_PORT + "/mock/test-external-api/" + name;
    }

    /**
     * start → serviceTask(esignDelegate) → message catch event
     * （{@code esign-completed}）→ manualReview → end；
     * 掛 boundary error 時失敗改走 altReview。
     *
     * @param url          簽核服務觸發端點
     * @param withBoundary 是否掛 boundary error（接住 ESIGN_FAILED／ESIGN_BLOCKED）
     */
    private String deployEsignProcess(String key, String url, boolean withBoundary) {
        String boundaryAndAlt = withBoundary ? """
                    <boundaryEvent id="esignFailed" attachedToRef="startEsign">
                      <errorEventDefinition errorRef="ESIGN_FAILED"/>
                    </boundaryEvent>
                    <boundaryEvent id="esignBlocked" attachedToRef="startEsign">
                      <errorEventDefinition errorRef="ESIGN_BLOCKED"/>
                    </boundaryEvent>
                    <sequenceFlow id="alt1" sourceRef="esignFailed" targetRef="altReview"/>
                    <sequenceFlow id="alt2" sourceRef="esignBlocked" targetRef="altReview"/>
                    <userTask id="altReview" name="電子簽章失敗-人工處理"
                              flowable:assignee="mgr001" flowable:formKey="leave-review"/>
                    <sequenceFlow id="altEnd" sourceRef="altReview" targetRef="end"/>
                """ : "";
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:flowable="http://flowable.org/bpmn"
                             targetNamespace="http://bpm.com/esign-delegate-test">
                  <message id="esignCompletedMsg" name="esign-completed"/>
                  <process id="%s" isExecutable="true">
                    <startEvent id="start"/>
                    <sequenceFlow id="f1" sourceRef="start" targetRef="startEsign"/>
                    <serviceTask id="startEsign" name="觸發電子簽章"
                                 flowable:delegateExpression="${esignDelegate}">
                      <extensionElements>
                        <flowable:field name="url" stringValue="%s"/>
                        <flowable:field name="payload">
                          <flowable:expression><![CDATA[{"processInstanceId":"${execution.processInstanceId}","docId":"${docId}","signer":"${signer}"}]]></flowable:expression>
                        </flowable:field>
                        <flowable:field name="resultVariable" stringValue="esignResponse"/>
                      </extensionElements>
                    </serviceTask>
                    <sequenceFlow id="wait" sourceRef="startEsign" targetRef="waitEsign"/>
                    <intermediateCatchEvent id="waitEsign" name="等待電子簽章回呼">
                      <messageEventDefinition messageRef="esignCompletedMsg"/>
                    </intermediateCatchEvent>
                    <sequenceFlow id="afterEsign" sourceRef="waitEsign" targetRef="manualReview"/>
                    <userTask id="manualReview" name="簽核結果複核"
                              flowable:assignee="mgr001" flowable:formKey="leave-review"/>
                    <sequenceFlow id="okEnd" sourceRef="manualReview" targetRef="end"/>
                    %s
                    <endEvent id="end"/>
                  </process>
                </definitions>
                """.formatted(key, url, boundaryAndAlt);

        var lint = lintService.lint(xml);
        assertThat(lint.valid())
                .as("測試 BPMN 必須通過 lint，實際錯誤：%s", lint.errors())
                .isTrue();

        repositoryService.createDeployment()
                .name("esigndelegate-" + key)
                .addString(key + ".bpmn20.xml", xml)
                .deploy();
        return key;
    }

    private Task singleTask(String processInstanceId) {
        Task task = taskService.createTaskQuery().processInstanceId(processInstanceId).singleResult();
        assertThat(task).isNotNull();
        return task;
    }

    @Test
    @DisplayName("#45 觸發成功 → 停在 message catch event → 模擬 #21 喚醒 → 續行到複核")
    void triggerThenCallbackResumesProcess() {
        ExternalApiTestSink.respondWith("esign", "{\"requestId\":\"REQ-9\"}");
        String key = deployEsignProcess("esign-ok", sinkUrl("esign"), true);

        var instance = runtimeService.startProcessInstanceByKey(key,
                Map.of("docId", "DOC-1", "signer", "alice"));

        // ① 請求真的抵達 sink；payload 帶上真實 processInstanceId（回呼的 correlation 依據）
        List<ExternalApiTestSink.Received> received = ExternalApiTestSink.receivedTo("esign");
        assertThat(received)
                .as("delegate 的失敗型態是『什麼都沒送』；必須從接收端確認")
                .hasSize(1);
        assertThat(received.get(0).method()).isEqualTo("POST");
        assertThat(received.get(0).body())
                .contains("\"processInstanceId\":\"" + instance.getId() + "\"")
                .contains("\"docId\":\"DOC-1\"")
                .contains("\"signer\":\"alice\"");
        assertThat((String) runtimeService.getVariable(instance.getId(), "esignResponse"))
                .isEqualTo("{\"requestId\":\"REQ-9\"}");

        // ② delegate 不等待：流程已停在 message catch event，還沒有任何 UserTask
        List<Execution> waiting = runtimeService.createExecutionQuery()
                .processInstanceId(instance.getId())
                .messageEventSubscriptionName("esign-completed")
                .list();
        assertThat(waiting)
                .as("觸發後應停在 message catch event 等回呼")
                .hasSize(1);
        assertThat(taskService.createTaskQuery().processInstanceId(instance.getId()).count())
                .as("回呼之前不該有任務 —— delegate 若自己往下走，這裡會先出現 manualReview")
                .isZero();

        // ③ 模擬 #21 CallbackController 的喚醒動作（同一組 runtimeService API）
        runtimeService.messageEventReceived("esign-completed", waiting.get(0).getId(),
                Map.of("esignStatus", "approved"));

        Task task = singleTask(instance.getId());
        assertThat(task.getTaskDefinitionKey())
                .as("喚醒後續行到複核任務")
                .isEqualTo("manualReview");
        assertThat(runtimeService.getVariable(instance.getId(), "esignStatus")).isEqualTo("approved");

        taskService.complete(task.getId());
        assertThat(runtimeService.createProcessInstanceQuery()
                .processInstanceId(instance.getId()).count()).isZero();
    }

    @Test
    @DisplayName("#45 非 2xx（503）→ ESIGN_FAILED，boundary 接住走替代路徑")
    void serverErrorTakesBoundaryPath() {
        ExternalApiTestSink.fail("esignfail", 503);
        String key = deployEsignProcess("esign-fail", sinkUrl("esignfail"), true);

        var instance = runtimeService.startProcessInstanceByKey(key,
                Map.of("docId", "DOC-1", "signer", "alice"));

        Task task = singleTask(instance.getId());
        assertThat(task.getTaskDefinitionKey())
                .as("ESIGN_FAILED 必須被 boundary error 接住")
                .isEqualTo("altReview");
        assertThat(ExternalApiTestSink.receivedTo("esignfail"))
                .as("非 2xx 是『有送出去但對方失敗』——請求必須存在，才能與政策拒絕區分")
                .hasSize(1);

        taskService.complete(task.getId());
    }

    @Test
    @DisplayName("#45 🔴 SSRF 政策在真實路徑生效：127.0.0.1 被擋、boundary 接住、零請求")
    void blockedUrlTakesBoundaryPathWithoutRequest() {
        String blockedUrl = "http://127.0.0.1:" + SERVLET_PORT + "/mock/test-external-api/esignblocked";
        String key = deployEsignProcess("esign-blocked", blockedUrl, true);

        var instance = runtimeService.startProcessInstanceByKey(key,
                Map.of("docId", "DOC-1", "signer", "alice"));

        Task task = singleTask(instance.getId());
        assertThat(task.getTaskDefinitionKey())
                .as("ESIGN_BLOCKED 必須被 boundary error 接住")
                .isEqualTo("altReview");
        assertThat(ExternalApiTestSink.receivedTo("esignblocked"))
                .as("政策拒絕時連請求都不能送出")
                .isEmpty();

        taskService.complete(task.getId());
    }
}
