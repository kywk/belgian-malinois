package com.bpm.core.notify;

import com.bpm.core.support.ExternalApiTestSink;
import com.bpm.core.support.IntegrationTestBase;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #44 端到端：BPMN 上的 {@code teamsNotifyDelegate} 真的被引擎解析、執行，
 * 而且送得出去時 sink 收得到、送不出去時流程照樣走完（fail-open）。
 *
 * <h2>這個測試能證明什麼、不能證明什麼</h2>
 *
 * <ul>
 *   <li><b>能</b>：{@code FlowableConfig.setBeans()} 的
 *       {@code teamsNotifyDelegate} 註冊正確（漏掉的話，
 *       {@code startProcessInstanceByKey} 會拋「無法解析 delegateExpression」）；
 *       delegate 真的被執行（歷史活動有該節點）；請求真的以 POST 抵達
 *       HTTP 端點、body 是 Teams 的 {@code {"text": ...}} 形狀；SSRF 政策
 *       在真實路徑上零請求；非 2xx 時 execute 吞例外、流程完成。</li>
 *   <li><b>不能</b>：訊息真的出現在 Teams 頻道裡。本測試的接收端是
 *       {@link ExternalApiTestSink}（同一台 Tomcat 的 {@code /mock/**}），
 *       驗的是 HTTP 合約；Teams 端的渲染不在自動化範圍。</li>
 * </ul>
 *
 * <h2>allowed-hosts 沿用 #67／#49 的測試例外</h2>
 *
 * <p>{@code application-test.yml} 把 {@code localhost} 列進
 * {@code bpm.webhook.allowed-hosts}（理由見該檔註解）—— 本測試沿用同一條
 * 路徑讓請求真的打得到 sink。政策預設拒絕 loopback 的行為仍在同一組測試
 * 裡被釘住：{@code blockedUrl...} 用 {@code 127.0.0.1}（與清單中的
 * {@code localhost} 是不同的字面 host）驗證被擋且請求數為 0。
 */
class TeamsNotifyDelegateIntegrationTest extends IntegrationTestBase {

    @Autowired
    private RepositoryService repositoryService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private HistoryService historyService;

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
     * start → serviceTask(teamsNotifyDelegate) → end。
     *
     * @param webhookUrl {@code webhookUrl} 欄位的 stringValue
     */
    private String deployTeamsProcess(String key, String webhookUrl) {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:flowable="http://flowable.org/bpmn"
                             targetNamespace="http://bpm.com/teams-delegate-test">
                  <process id="%s" isExecutable="true">
                    <startEvent id="start"/>
                    <sequenceFlow id="f1" sourceRef="start" targetRef="notifyTeams"/>
                    <serviceTask id="notifyTeams" name="Teams 通知"
                                 flowable:delegateExpression="${teamsNotifyDelegate}">
                      <extensionElements>
                        <flowable:field name="webhookUrl" stringValue="%s"/>
                        <flowable:field name="title" stringValue="案件 ${caseNo}"/>
                        <flowable:field name="message" stringValue="流程已受理，請盡快處理。"/>
                      </extensionElements>
                    </serviceTask>
                    <sequenceFlow id="f2" sourceRef="notifyTeams" targetRef="end"/>
                    <endEvent id="end"/>
                  </process>
                </definitions>
                """.formatted(key, webhookUrl);

        // 「lint 過」是本工項的交付條件之一：同一份 XML 要能部署，也要能過設計器 lint。
        var lint = lintService.lint(xml);
        assertThat(lint.valid())
                .as("測試 BPMN 必須通過 lint，實際錯誤：%s", lint.errors())
                .isTrue();

        repositoryService.createDeployment()
                .name("teamsdelegate-" + key)
                .addString(key + ".bpmn20.xml", xml)
                .deploy();
        return key;
    }

    private void assertCompleted(String processInstanceId) {
        assertThat(runtimeService.createProcessInstanceQuery()
                .processInstanceId(processInstanceId).count())
                .as("通知失敗不得讓流程卡住")
                .isZero();
        assertThat(historyService.createHistoricActivityInstanceQuery()
                .processInstanceId(processInstanceId)
                .activityId("notifyTeams")
                .count())
                .as("serviceTask 的歷史活動存在 = delegate 真的被執行，不是被靜默略過")
                .isGreaterThan(0);
    }

    @Test
    @DisplayName("#44 2xx：POST 真的抵達 sink，body 是 Teams 的 {\"text\": ...} 形狀；流程走完")
    void happyPathSendsTeamsPayloadAndCompletes() {
        String key = deployTeamsProcess("teams-ok", sinkUrl("teams-ok"));

        var instance = runtimeService.startProcessInstanceByKey(key,
                Map.of("caseNo", "C-2026-001"));

        assertThat(ExternalApiTestSink.receivedTo("teams-ok"))
                .as("delegate 的失敗型態是『什麼都沒送』；必須從接收端確認")
                .hasSize(1);
        assertThat(ExternalApiTestSink.receivedTo("teams-ok").get(0).method()).isEqualTo("POST");
        assertThat(ExternalApiTestSink.receivedTo("teams-ok").get(0).body())
                .as("title 以換行接在 message 前，JSON 由 ObjectMapper 產生")
                .isEqualTo("{\"text\":\"案件 C-2026-001\\n流程已受理，請盡快處理。\"}");
        assertCompleted(instance.getId());
    }

    @Test
    @DisplayName("#44 🔴 SSRF 政策在真實路徑生效：127.0.0.1 被擋、零請求、流程照常完成")
    void blockedUrlIsNoOpAndProcessCompletes() {
        String key = deployTeamsProcess("teams-blocked",
                "http://127.0.0.1:" + SERVLET_PORT + "/mock/test-external-api/teams-blocked");

        var instance = runtimeService.startProcessInstanceByKey(key, Map.of("caseNo", "C-1"));

        assertThat(ExternalApiTestSink.receivedTo("teams-blocked"))
                .as("政策拒絕時連請求都不能送出")
                .isEmpty();
        assertCompleted(instance.getId());
    }

    @Test
    @DisplayName("#44 非 2xx（503）→ execute 吞掉只記 log：請求有出去，流程照常完成")
    void serverErrorIsFailOpen() {
        ExternalApiTestSink.fail("teams-boom", 503);
        String key = deployTeamsProcess("teams-boom", sinkUrl("teams-boom"));

        var instance = runtimeService.startProcessInstanceByKey(key, Map.of("caseNo", "C-1"));

        assertThat(ExternalApiTestSink.receivedTo("teams-boom"))
                .as("非 2xx 是『有送出去但對方失敗』—— 與政策拒絕（零請求）不同")
                .hasSize(1);
        assertCompleted(instance.getId());
    }
}
