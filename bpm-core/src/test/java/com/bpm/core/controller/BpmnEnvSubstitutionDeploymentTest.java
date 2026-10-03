package com.bpm.core.controller;

import com.bpm.core.support.IntegrationTestBase;
import org.flowable.bpmn.model.UserTask;
import org.flowable.engine.RepositoryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 環境變數替換的部署端整合測試（backlog #53，spec §12.3）。
 *
 * <h2>為什麼單元測試不夠</h2>
 *
 * <p>{@code DeploymentControllerEnvTest} 用 mock 釘住 controller 的決策，
 * 但它證明不了「引擎真的解析得到替換後的值」：XML 轉義若少一個字元，
 * mock 的 {@code addString} 照樣收下字串，只有真的 {@code BpmnXMLConverter}
 * 解析時才會壞。這裡走 {@code POST /api/deployments} → 真實引擎，再用
 * {@code RepositoryService.getBpmnModel} 讀回引擎眼中的定義。
 *
 * <p>三條路徑：替換成功、值含 {@code &} 的轉義、未設定 fail-closed。
 * 測試值來自 {@code application-test.yml} 的 {@code bpmn.variables.*}。
 *
 * <p>清理：每個測試建立的 deployment 與落地檔案在 {@link #cleanup()}
 * 刪除。process key 帶亂數 —— {@link IntegrationTestBase} 的容器是 static、
 * 所有整合測試共用資料庫，寫死 key 會與其他測試類別的規則 h 授權狀態互相污染。
 */
class BpmnEnvSubstitutionDeploymentTest extends IntegrationTestBase {

    @Autowired
    private RepositoryService repositoryService;

    @Value("${bpm.bpmn-definitions-dir:./bpmn-definitions}")
    private String bpmnDir;

    private final List<String> createdDeployments = new ArrayList<>();
    private final List<Path> createdFiles = new ArrayList<>();

    @AfterEach
    void cleanup() {
        createdDeployments.forEach(id -> repositoryService.deleteDeployment(id, true));
        createdDeployments.clear();
        createdFiles.forEach(path -> {
            try {
                Files.deleteIfExists(path);
            } catch (Exception ignored) {
                // 測試收尾失敗不該讓結果變紅；殘檔在 target/ 下。
            }
        });
        createdFiles.clear();
    }

    private static String processXml(String processKey, String candidateGroups) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:flowable="http://flowable.org/bpmn" targetNamespace="t">
                  <process id="%s" isExecutable="true">
                    <startEvent id="s" name="開始"/>
                    <sequenceFlow id="f1" sourceRef="s" targetRef="t"/>
                    <userTask id="t" name="審核關卡" flowable:formKey="external:none"
                              flowable:candidateGroups="%s"/>
                    <sequenceFlow id="f2" sourceRef="t" targetRef="e"/>
                    <endEvent id="e" name="結束"/>
                  </process>
                </definitions>
                """.formatted(processKey, candidateGroups);
    }

    private MvcResult postDeployment(String name, String xml, int expectedStatus) throws Exception {
        return mockMvc.perform(multipart("/api/deployments")
                        .file(new MockMultipartFile("file", name, "text/xml",
                                xml.getBytes(StandardCharsets.UTF_8)))
                        .param("name", name)
                        .header("X-User-Id", "admin001"))
                .andExpect(status().is(expectedStatus))
                .andReturn();
    }

    /** 部署成功並登記清理；回傳 deploymentId。 */
    private String deployAndTrack(String name, String xml) throws Exception {
        var result = postDeployment(name, xml, 200);
        String body = result.getResponse().getContentAsString();
        String deploymentId = body.replaceAll(".*\"deploymentId\":\"([^\"]*)\".*", "$1");
        assertThat(deploymentId)
                .as("回應沒有可解析的 deploymentId: %s", body)
                .doesNotContain("\"").isNotBlank();
        createdDeployments.add(deploymentId);
        createdFiles.add(Path.of(bpmnDir).resolve(name));
        return deploymentId;
    }

    /** 用引擎自己的轉換器讀回這次部署的 UserTask —— 這才是「引擎看到什麼」。 */
    private UserTask deployedUserTask(String deploymentId) {
        var processDefinition = repositoryService.createProcessDefinitionQuery()
                .deploymentId(deploymentId).singleResult();
        assertThat(processDefinition).as("找不到這次部署的流程定義").isNotNull();
        var model = repositoryService.getBpmnModel(processDefinition.getId());
        return (UserTask) model.getMainProcess().getFlowElement("t");
    }

    @Test
    @DisplayName("部署 ${ENV_FINANCE_GROUP}：引擎定義是 resolved 值、磁碟保留原始 XML")
    void deploymentResolvesPlaceholderForEngine() throws Exception {
        String key = "envsub-" + UUID.randomUUID().toString().substring(0, 8);
        String name = key + ".bpmn20.xml";
        String xml = processXml(key, "${ENV_FINANCE_GROUP}");

        String deploymentId = deployAndTrack(name, xml);

        assertThat(deployedUserTask(deploymentId).getCandidateGroups())
                .as("引擎必須看到替換後的值，而不是 ${ENV_FINANCE_GROUP}")
                .containsExactly("finance_dept_approver");
        assertThat(Files.readString(Path.of(bpmnDir).resolve(name)))
                .as("落地的是含佔位符的原始 XML（環境差異不進檔案）")
                .isEqualTo(xml);
    }

    @Test
    @DisplayName("部署 ${ENV_XML_ESCAPE}（值含 &）：引擎解析得到原值")
    void escapedValueSurvivesEngineParsing() throws Exception {
        String key = "envesc-" + UUID.randomUUID().toString().substring(0, 8);
        String name = key + ".bpmn20.xml";
        String xml = processXml(key, "${ENV_XML_ESCAPE}");

        String deploymentId = deployAndTrack(name, xml);

        assertThat(deployedUserTask(deploymentId).getCandidateGroups())
                .as("值含 & 若沒有轉義，引擎解析這份 XML 時就會壞掉")
                .containsExactly("A&B");
    }

    @Test
    @DisplayName("未設定的佔位符：400、引擎零新 deployment、檔案不落地")
    void unsetPlaceholderIsRejectedWithoutSideEffects() throws Exception {
        String key = "envmissing-" + UUID.randomUUID().toString().substring(0, 8);
        String name = key + ".bpmn20.xml";
        String xml = processXml(key, "${ENV_DOES_NOT_EXIST}");

        long before = repositoryService.createDeploymentQuery().count();

        postDeployment(name, xml, 400);

        assertThat(repositoryService.createDeploymentQuery().count())
                .as("fail-closed：400 之後不得有任何新 deployment")
                .isEqualTo(before);
        assertThat(Files.exists(Path.of(bpmnDir).resolve(name)))
                .as("fail-closed：400 之後不得落地任何檔案")
                .isFalse();
    }

    @Test
    @DisplayName("設計器 lint 端點（原始 XML）對 ${ENV_*} 放行 —— 與部署端同一份接受度")
    void designerLintEndpointAcceptsPlaceholder() throws Exception {
        String key = "envlint-" + UUID.randomUUID().toString().substring(0, 8);

        mockMvc.perform(post("/api/bpmn/lint")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(processXml(key, "${ENV_FINANCE_GROUP}")))
                .andExpect(status().isOk());
    }
}
