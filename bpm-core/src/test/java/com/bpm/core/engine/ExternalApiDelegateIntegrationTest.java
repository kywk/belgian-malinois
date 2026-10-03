package com.bpm.core.engine;

import com.bpm.core.support.ExternalApiTestSink;
import com.bpm.core.support.IntegrationTestBase;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #49 端到端：BPMN 上的 {@code externalApiDelegate} 真的發出 HTTP 請求，
 * SSRF 政策在真實路徑上生效，失敗走 boundary error 替代路徑。
 *
 * <h2>allowed-hosts 沿用 #67 的測試例外</h2>
 *
 * <p>{@code application-test.yml} 把 {@code localhost} 列進
 * {@code bpm.webhook.allowed-hosts}（理由見該檔註解）—— 本測試沿用同一條
 * 路徑讓請求真的打得到 {@link ExternalApiTestSink}。政策預設拒絕 loopback
 * 的行為仍在同一組測試裡被釘住：{@code blockedUrl...} 用 {@code 127.0.0.1}
 * （與清單中的 {@code localhost} 是不同的字面 host）驗證被擋且請求數為 0。
 *
 * <h2>為什麼變數在流程結束前讀</h2>
 *
 * <p>{@code resultVariable} 在流程實例結束後從 runtime 消失（歷史才查得到），
 * 所以流程刻意停在 {@code manualReview} 任務上，用
 * {@code runtimeService.getVariable} 讀 —— 這是最直接、不依賴 history level
 * 的斷言。讀完把任務完成，讓案件收尾。
 */
class ExternalApiDelegateIntegrationTest extends IntegrationTestBase {

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
     * start → serviceTask(externalApiDelegate, 可選 boundary error) →
     * manualReview（正常路徑）／altReview（替代路徑）→ end。
     *
     * @param url          目標 URL
     * @param boundaryCode 要接住的 errorCode；{@code null} 表示不掛 boundary
     */
    private String deployApiProcess(String key, String url, String boundaryCode) {
        String boundaryAndAlt = boundaryCode == null ? "" : """
                    <boundaryEvent id="apiFailed" attachedToRef="callApi">
                      <errorEventDefinition errorRef="%s"/>
                    </boundaryEvent>
                    <sequenceFlow id="alt" sourceRef="apiFailed" targetRef="altReview"/>
                    <userTask id="altReview" name="外部系統失敗-人工處理"
                              flowable:assignee="mgr001" flowable:formKey="leave-review"/>
                    <sequenceFlow id="altEnd" sourceRef="altReview" targetRef="end"/>
                """.formatted(boundaryCode);
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:flowable="http://flowable.org/bpmn"
                             targetNamespace="http://bpm.com/external-api-delegate-test">
                  <process id="%s" isExecutable="true">
                    <startEvent id="start"/>
                    <sequenceFlow id="f1" sourceRef="start" targetRef="callApi"/>
                    <serviceTask id="callApi" name="呼叫外部 API"
                                 flowable:delegateExpression="${externalApiDelegate}">
                      <extensionElements>
                        <flowable:field name="url" stringValue="%s"/>
                        <flowable:field name="method" stringValue="GET"/>
                        <flowable:field name="resultVariable" stringValue="apiResult"/>
                      </extensionElements>
                    </serviceTask>
                    <sequenceFlow id="ok" sourceRef="callApi" targetRef="manualReview"/>
                    %s
                    <userTask id="manualReview" name="人工複核"
                              flowable:assignee="mgr001" flowable:formKey="leave-review"/>
                    <sequenceFlow id="okEnd" sourceRef="manualReview" targetRef="end"/>
                    <endEvent id="end"/>
                  </process>
                </definitions>
                """.formatted(key, url, boundaryAndAlt);

        var lint = lintService.lint(xml);
        assertThat(lint.valid())
                .as("測試 BPMN 必須通過 lint，實際錯誤：%s", lint.errors())
                .isTrue();

        repositoryService.createDeployment()
                .name("externalapidelegate-" + key)
                .addString(key + ".bpmn20.xml", xml)
                .deploy();
        return key;
    }

    private void complete(String processInstanceId) {
        Task task = taskService.createTaskQuery().processInstanceId(processInstanceId).singleResult();
        assertThat(task).isNotNull();
        taskService.complete(task.getId());
    }

    @Test
    @DisplayName("#49 2xx：請求真的抵達 sink，回應 body 寫入 resultVariable")
    void happyPathSendsRequestAndStoresResponse() {
        String key = deployApiProcess("api-ok", sinkUrl("echo"), null);

        var instance = runtimeService.startProcessInstanceByKey(key);

        assertThat(ExternalApiTestSink.receivedTo("echo"))
                .as("delegate 的失敗型態是『什麼都沒送』；必須從接收端確認")
                .hasSize(1);
        assertThat(ExternalApiTestSink.receivedTo("echo").get(0).method()).isEqualTo("GET");
        assertThat((String) runtimeService.getVariable(instance.getId(), "apiResult"))
                .contains("\"sink\":\"echo\"");
        assertThat(taskService.createTaskQuery().processInstanceId(instance.getId()).count())
                .as("2xx 走正常路徑到 manualReview")
                .isEqualTo(1);

        complete(instance.getId());
    }

    @Test
    @DisplayName("#49 🔴 SSRF 政策在真實路徑生效：127.0.0.1 被擋、boundary 接住、零請求")
    void blockedUrlTakesBoundaryPathWithoutRequest() {
        String blockedUrl = "http://127.0.0.1:" + SERVLET_PORT + "/mock/test-external-api/blocked";
        String key = deployApiProcess("api-blocked", blockedUrl,
                ExternalApiDelegate.ERROR_CODE_BLOCKED);

        var instance = runtimeService.startProcessInstanceByKey(key);

        Task task = taskService.createTaskQuery().processInstanceId(instance.getId()).singleResult();
        assertThat(task).isNotNull();
        assertThat(task.getTaskDefinitionKey())
                .as("EXTERNAL_API_BLOCKED 必須被 boundary error 接住")
                .isEqualTo("altReview");
        assertThat(ExternalApiTestSink.receivedTo("blocked"))
                .as("政策拒絕時連請求都不能送出")
                .isEmpty();

        complete(instance.getId());
    }

    @Test
    @DisplayName("#49 非 2xx（503）→ EXTERNAL_API_FAILED，boundary 接住走替代路徑")
    void serverErrorTakesBoundaryPath() {
        ExternalApiTestSink.fail("boom", 503);
        String key = deployApiProcess("api-boom", sinkUrl("boom"),
                ExternalApiDelegate.ERROR_CODE_FAILED);

        var instance = runtimeService.startProcessInstanceByKey(key);

        Task task = taskService.createTaskQuery().processInstanceId(instance.getId()).singleResult();
        assertThat(task).isNotNull();
        assertThat(task.getTaskDefinitionKey()).isEqualTo("altReview");
        assertThat(ExternalApiTestSink.receivedTo("boom"))
                .as("非 2xx 是『有送出去但對方失敗』——請求必須存在，才能與政策拒絕區分")
                .hasSize(1);

        complete(instance.getId());
    }
}
