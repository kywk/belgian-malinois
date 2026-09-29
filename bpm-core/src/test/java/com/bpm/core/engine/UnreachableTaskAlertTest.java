package com.bpm.core.engine;

import com.bpm.core.support.IntegrationTestBase;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.PreparedStatement;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「沒有人看得到的任務」告警（2026-09-29 決策：只告警，不硬擋）。
 *
 * <p>最重要的是第二個測試：外部 API 的「先啟動、同一交易內再補候選群組」
 * 必須<b>不</b>觸發。若 listener 在 TASK_CREATED 當下就檢查，這條合法路徑
 * 每次都會誤報 —— 誤報會訓練大家忽略告警。
 */
class UnreachableTaskAlertTest extends IntegrationTestBase {

    @Autowired private RepositoryService repositoryService;
    @Autowired private RuntimeService runtimeService;
    @Autowired private TaskService taskService;
    @Autowired @Qualifier("primaryTransactionManager") private PlatformTransactionManager primaryTx;

    private static final String NOBODY = "unreachable-nobody";
    private static final String GROUP = "unreachable-group";

    private static String bpmn(String key, String taskAttrs) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:flowable="http://flowable.org/bpmn" targetNamespace="test">
                  <process id="%s" isExecutable="true">
                    <startEvent id="start"/>
                    <sequenceFlow id="f0" sourceRef="start" targetRef="task"/>
                    <userTask id="task" name="無人關卡" %s/>
                    <sequenceFlow id="f1" sourceRef="task" targetRef="end"/>
                    <endEvent id="end"/>
                  </process>
                </definitions>
                """.formatted(key, taskAttrs);
    }

    @BeforeEach
    void deploy() {
        if (repositoryService.createProcessDefinitionQuery().processDefinitionKey(NOBODY).count() == 0) {
            repositoryService.createDeployment().addString(NOBODY + ".bpmn20.xml", bpmn(NOBODY, "")).deploy();
            repositoryService.createDeployment().addString(GROUP + ".bpmn20.xml",
                    bpmn(GROUP, "flowable:candidateGroups=\"dept-hr\"")).deploy();
        }
    }

    private static int alertsFor(String processInstanceId) {
        int[] n = {-1};
        withAuditConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM bpm_audit_log "
                    + "WHERE operation_type = 'TASK_UNREACHABLE' AND process_instance_id = ?")) {
                ps.setString(1, processInstanceId);
                var rs = ps.executeQuery();
                rs.next();
                n[0] = rs.getInt(1);
            }
        });
        return n[0];
    }

    @Test
    @DisplayName("無 assignee、無候選人的任務 → 告警（稽核 TASK_UNREACHABLE）")
    void taskWithNobodyIsAlerted() {
        String pid = runtimeService.startProcessInstanceByKey(NOBODY, Map.of("initiator", "user001")).getId();
        assertThat(alertsFor(pid)).as("沒有人看得到的任務必須被看見").isEqualTo(1);
        assertThat(taskService.createTaskQuery().processInstanceId(pid).count())
                .as("只告警、不硬擋：任務仍照常建立").isEqualTo(1);
    }

    @Test
    @DisplayName("同一交易內補上候選群組（外部 API 的模式）→ 不告警")
    void candidateAddedInSameTransactionIsNotAlerted() {
        String pid = new TransactionTemplate(primaryTx).execute(status -> {
            String id = runtimeService.startProcessInstanceByKey(NOBODY, Map.of("initiator", "user001")).getId();
            String taskId = taskService.createTaskQuery().processInstanceId(id).singleResult().getId();
            taskService.addCandidateGroup(taskId, "dept-hr");
            return id;
        });
        assertThat(alertsFor(pid))
                .as("建立當下沒有候選人，但 commit 時有 —— 這是合法模式，告警就是誤報")
                .isZero();
    }

    @Test
    @DisplayName("BPMN 設了候選群組 → 不告警")
    void taskWithCandidateGroupIsNotAlerted() {
        String pid = runtimeService.startProcessInstanceByKey(GROUP, Map.of("initiator", "user001")).getId();
        assertThat(alertsFor(pid)).isZero();
    }
}
