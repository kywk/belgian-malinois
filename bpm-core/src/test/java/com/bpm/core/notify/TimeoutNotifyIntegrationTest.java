package com.bpm.core.notify;

import com.bpm.core.support.IntegrationTestBase;
import com.bpm.core.support.NotifyTestSink;
import org.flowable.engine.HistoryService;
import org.flowable.engine.ManagementService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #23 端到端：非中斷式 boundary timer 到期 → 逾期提醒。
 *
 * <h2>本測試組在證明什麼</h2>
 *
 * <p>政策是「只提醒、不動作」，所以每一條正向測試都同時釘兩邊：
 *
 * <ol>
 *   <li>訊息真的出現（{@link NotifyTestSink}）—— 通知的失敗型態是
 *       「什麼都不發生」，只看流程有推進證明不了任何事。</li>
 *   <li>任務還在、流程還在跑 —— 這是<b>非中斷式</b>（
 *       {@code cancelActivity="false"}）的直接證據。中斷式會刪掉任務，
 *       也就違反「不自動動作」的政策。</li>
 * </ol>
 *
 * <h2>為什麼 timer 是手動執行而不是等它自己響</h2>
 *
 * <p>{@code application-test.yml} 刻意關掉 async executor
 * （{@code async-executor-activate: false}），讓測試時序確定。
 * 這裡照 Flowable 的標準作法把 timer job 轉成可執行 job 後同步執行：
 * {@code moveTimerToExecutableJob → executeJob}。發送是同步的
 * （{@code RabbitTemplate.convertAndSend} 送到 broker 才返回），
 * 所以 {@code executeJob} 返回時訊息已在 sink queue 上。
 *
 * <h2>與 webhook timeout 的關係</h2>
 *
 * <p>本測試<b>不</b>碰 webhook 的 {@code event="timeout"}（Flowable 7.2.0
 * 不發那個 task event，見 {@code WebhookTaskListener.matches}）。
 * 這裡測的是替代機制：boundary timer ＋ {@code timeoutNotifyDelegate}
 * ＋ 通知事件 {@code task_timeout}。
 */
class TimeoutNotifyIntegrationTest extends IntegrationTestBase {

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private RepositoryService repositoryService;

    @Autowired
    private ManagementService managementService;

    @Autowired
    private HistoryService historyService;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private AmqpAdmin amqpAdmin;

    @BeforeEach
    void setUp() {
        NotifyTestSink.install(rabbitTemplate, amqpAdmin);
        NotifyTestSink.reset();
    }

    // ── 工具 ────────────────────────────────────────────────────────

    /**
     * 部署一支「UserTask ＋ 非中斷式 boundary timer ＋ 提醒 serviceTask」的流程。
     *
     * <p>{@code flowable:formKey} 是設計器 lint（{@code formkey-required}）的
     * 既有慣例；{@code repositoryService} 部署雖然繞過 lint，測試 BPMN
     * 仍與正式流程保持同一個形狀。
     */
    private String deployTimerProcess(String key, String userTaskAttrs) {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:flowable="http://flowable.org/bpmn"
                             targetNamespace="http://bpm.com/notify-test">
                  <process id="%s" isExecutable="true">
                    <startEvent id="start"/>
                    <sequenceFlow id="f1" sourceRef="start" targetRef="approve"/>
                    <userTask id="approve" name="審核關卡" flowable:formKey="leave-review"%s/>
                    <boundaryEvent id="timeoutBoundary" attachedToRef="approve" cancelActivity="false">
                      <timerEventDefinition>
                        <timeDuration>PT1S</timeDuration>
                      </timerEventDefinition>
                    </boundaryEvent>
                    <sequenceFlow id="f2" sourceRef="timeoutBoundary" targetRef="notifyTimeout"/>
                    <serviceTask id="notifyTimeout" name="逾時提醒"
                                 flowable:delegateExpression="${timeoutNotifyDelegate}"/>
                    <sequenceFlow id="f3" sourceRef="notifyTimeout" targetRef="end"/>
                    <sequenceFlow id="f4" sourceRef="approve" targetRef="end"/>
                    <endEvent id="end"/>
                  </process>
                </definitions>
                """.formatted(key, userTaskAttrs);
        repositoryService.createDeployment()
                .name("tnt-" + key)
                .addString(key + ".bpmn20.xml", xml)
                .deploy();
        return key;
    }

    private String start(String key) {
        var pi = runtimeService.startProcessInstanceByKey(key, Map.of("initiator", "user001"));
        return pi.getId();
    }

    /** 手動觸發 boundary timer（async executor 在測試中是關的，見類別註解）。 */
    private void fireBoundaryTimer(String pid) {
        var timer = managementService.createTimerJobQuery()
                .processInstanceId(pid).singleResult();
        assertThat(timer)
                .as("前置條件：非中斷式 boundary timer 必須在任務建立時就掛上 timer job")
                .isNotNull();
        managementService.moveTimerToExecutableJob(timer.getId());
        managementService.executeJob(timer.getId());
    }

    private static Map<String, Object> only(List<Map<String, Object>> messages, String event) {
        List<Map<String, Object>> matches = NotifyTestSink.events(messages, event);
        assertThat(matches)
                .as("事件 %s 應該恰好一則，實際收到的所有事件：%s", event,
                        messages.stream().map(m -> m.get("event")).toList())
                .hasSize(1);
        return matches.get(0);
    }

    // ── 正向 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("#23 boundary timer 到期 → 通知受理人 task_timeout，且任務與流程原樣保留")
    void timerNotifiesAssigneeAndKeepsTask() {
        String key = deployTimerProcess("tnt-assignee", " flowable:assignee=\"mgr001\"");
        String pid = start(key);
        Task task = taskService.createTaskQuery().processInstanceId(pid).singleResult();
        assertThat(task.getAssignee()).isEqualTo("mgr001");
        NotifyTestSink.reset();

        fireBoundaryTimer(pid);

        Map<String, Object> msg = only(
                NotifyTestSink.awaitEvent("task_timeout", Duration.ofSeconds(10)), "task_timeout");
        assertThat(msg)
                .containsEntry("event", "task_timeout")
                .containsEntry("assignee", "mgr001")
                .containsEntry("taskId", task.getId())
                .containsEntry("taskName", "審核關卡")
                .containsEntry("processInstanceId", pid)
                .containsEntry("processDefinitionKey", key);
        // P2-1：payload 只有非敏感欄位 —— 沒有流程變數、沒有簽核意見。
        assertThat(msg)
                .doesNotContainKeys("variables", "comment", "initiator", "leaveType", "days");

        // 非中斷式的證據：任務還在、流程還在跑（政策＝只提醒、不動作）。
        assertThat(taskService.createTaskQuery().taskId(task.getId()).count())
                .as("中斷式 boundary 會取消任務；任務必須原樣保留")
                .isEqualTo(1);
        assertThat(runtimeService.createProcessInstanceQuery().processInstanceId(pid).count())
                .as("流程也必須繼續")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("#23 候選任務（無 assignee）→ 通知候選『人』，任務保留")
    void timerNotifiesCandidateUsers() {
        String key = deployTimerProcess("tnt-candidates",
                " flowable:candidateUsers=\"mgr001,mgr002\"");
        String pid = start(key);
        Task task = taskService.createTaskQuery().processInstanceId(pid).singleResult();
        assertThat(task.getAssignee()).as("前置條件：候選任務沒有 assignee").isNull();
        NotifyTestSink.reset();

        fireBoundaryTimer(pid);

        Map<String, Object> msg = only(
                NotifyTestSink.awaitEvent("task_timeout", Duration.ofSeconds(10)), "task_timeout");
        @SuppressWarnings("unchecked")
        List<String> candidates = (List<String>) msg.get("candidateUsers");
        assertThat(candidates).containsExactlyInAnyOrder("mgr001", "mgr002");
        assertThat(msg)
                .as("assignee 是 EmailConsumer 的收件人欄位；沒有受理人就不該放")
                .doesNotContainKey("assignee");
        assertThat(taskService.createTaskQuery().taskId(task.getId()).count()).isEqualTo(1);
        assertThat(runtimeService.createProcessInstanceQuery().processInstanceId(pid).count())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("#23 沒有受理人也沒有候選人 → 零通知（timer 有響，只是沒人可提醒）")
    void timerWithoutAnyHandlerSendsNothing() {
        String key = deployTimerProcess("tnt-nohandler", "");
        String pid = start(key);
        Task task = taskService.createTaskQuery().processInstanceId(pid).singleResult();
        assertThat(task.getAssignee()).isNull();
        NotifyTestSink.reset();

        fireBoundaryTimer(pid);

        // 非空斷言：證明 timer 真的被執行過，否則「沒有通知」可能只是
        // timer 根本沒響（假空）。executeJob 沒拋例外已代表 delegate 被呼叫，
        // 這裡再從歷史活動與 job 佇列兩邊確認。
        assertThat(managementService.createTimerJobQuery().processInstanceId(pid).count())
                .as("timer job 應已被消耗")
                .isZero();
        assertThat(historyService.createHistoricActivityInstanceQuery()
                .processInstanceId(pid).activityId("timeoutBoundary").count())
                .as("boundary event 的歷史活動應存在（timer 確實響過）")
                .isGreaterThan(0);

        assertThat(NotifyTestSink.drain())
                .as("沒有可送對象時不得發空訊息")
                .isEmpty();
        assertThat(taskService.createTaskQuery().taskId(task.getId()).count()).isEqualTo(1);
        assertThat(runtimeService.createProcessInstanceQuery().processInstanceId(pid).count())
                .isEqualTo(1);
    }
}
