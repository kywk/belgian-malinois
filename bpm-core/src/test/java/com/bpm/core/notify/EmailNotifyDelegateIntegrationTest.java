package com.bpm.core.notify;

import com.bpm.core.support.IntegrationTestBase;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #43 端到端：BPMN 上的 {@code emailNotifyDelegate} 真的被引擎解析、執行，
 * 而且寄信失敗時流程照樣走完（fail-open）。
 *
 * <h2>這個測試能證明什麼、不能證明什麼</h2>
 *
 * <ul>
 *   <li><b>能</b>：{@code FlowableConfig.setBeans()} 的
 *       {@code emailNotifyDelegate} 註冊正確（漏掉的話，
 *       {@code startProcessInstanceByKey} 會拋「無法解析 delegateExpression」）；
 *       delegate 真的被執行（歷史活動有該節點）；SMTP 壞掉時 execute 吞例外、
 *       流程完成。</li>
 *   <li><b>不能</b>：信真的寄到了。{@code application-test.yml} 的 SMTP 指到
 *       一個不存在的 port（65000），本測試刻意<b>不</b>另外架 mail sink ——
 *       收件人／主旨／內文的正確性由 {@link EmailNotifyDelegateTest} 用
 *       capture 的 {@code SimpleMailMessage} 釘住。兩邊分工：這裡證明接上了
 *       引擎，那裡證明寄對了內容。</li>
 * </ul>
 *
 * <p>SMTP 是壞的這件事本身就是本測試的 fail-open 證據：若 execute 把例外
 * 往外丟，流程會在 notifyEmail 節點失敗、不會結束。
 */
class EmailNotifyDelegateIntegrationTest extends IntegrationTestBase {

    @Autowired
    private RepositoryService repositoryService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private HistoryService historyService;

    @Autowired
    private com.bpm.core.lint.BpmnLintService lintService;

    /**
     * start → serviceTask(emailNotifyDelegate) → end。
     *
     * @param toField {@code to} 欄位的 stringValue；{@code null} 表示不放
     */
    private String deployEmailProcess(String key, String toField) {
        String toXml = toField == null ? ""
                : "<flowable:field name=\"to\" stringValue=\"" + toField + "\"/>";
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:flowable="http://flowable.org/bpmn"
                             targetNamespace="http://bpm.com/email-delegate-test">
                  <process id="%s" isExecutable="true">
                    <startEvent id="start"/>
                    <sequenceFlow id="f1" sourceRef="start" targetRef="notifyEmail"/>
                    <serviceTask id="notifyEmail" name="寄送通知"
                                 flowable:delegateExpression="${emailNotifyDelegate}">
                      <extensionElements>
                        %s
                        <flowable:field name="subject" stringValue="案件 ${processInstanceId} 通知"/>
                        <flowable:field name="body" stringValue="您好，流程已受理。"/>
                      </extensionElements>
                    </serviceTask>
                    <sequenceFlow id="f2" sourceRef="notifyEmail" targetRef="end"/>
                    <endEvent id="end"/>
                  </process>
                </definitions>
                """.formatted(key, toXml);

        // 「lint 過」是本工項的交付條件之一：同一份 XML 要能部署，也要能過設計器 lint。
        var lint = lintService.lint(xml);
        assertThat(lint.valid())
                .as("測試 BPMN 必須通過 lint，實際錯誤：%s", lint.errors())
                .isTrue();

        repositoryService.createDeployment()
                .name("emaildelegate-" + key)
                .addString(key + ".bpmn20.xml", xml)
                .deploy();
        return key;
    }

    @Test
    @DisplayName("#43 delegateExpression 可解析、節點被執行；SMTP 壞掉時流程仍走完（fail-open）")
    void delegateRunsAndMailFailureDoesNotBlockProcess() {
        String key = deployEmailProcess("email-ok",
                "mgr001@company.com,${applicantEmail}");

        var instance = runtimeService.startProcessInstanceByKey(key,
                Map.of("applicantEmail", "user001@company.com"));

        // SMTP 在測試 profile 指到不存在的 port 65000 → mailSender.send 必失敗。
        // 流程能結束，就是 fail-open 的直接證據。
        assertThat(runtimeService.createProcessInstanceQuery()
                .processInstanceId(instance.getId()).count())
                .as("寄信失敗不得讓流程卡住")
                .isZero();
        assertThat(historyService.createHistoricActivityInstanceQuery()
                .processInstanceId(instance.getId())
                .activityId("notifyEmail")
                .count())
                .as("serviceTask 的歷史活動存在 = delegate 真的被執行，不是被靜默略過")
                .isGreaterThan(0);
    }

    @Test
    @DisplayName("#43 to 欄位缺失 → delegate no-op，流程照常完成")
    void missingToStillCompletesProcess() {
        String key = deployEmailProcess("email-no-to", null);

        var instance = runtimeService.startProcessInstanceByKey(key);

        assertThat(runtimeService.createProcessInstanceQuery()
                .processInstanceId(instance.getId()).count())
                .isZero();
        assertThat(historyService.createHistoricActivityInstanceQuery()
                .processInstanceId(instance.getId())
                .activityId("notifyEmail")
                .count())
                .as("no-op 是 delegate 內部的決定，節點本身仍必須被執行過")
                .isGreaterThan(0);
    }
}
