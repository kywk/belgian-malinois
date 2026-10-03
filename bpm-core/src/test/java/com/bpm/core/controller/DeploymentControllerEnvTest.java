package com.bpm.core.controller;

import com.bpm.core.audit.AuditEventPublisher;
import com.bpm.core.dto.AuditEvent;
import com.bpm.core.lint.BpmnLintService;
import com.bpm.core.service.BpmnEnvSubstitutor;
import com.bpm.core.service.DeploymentGitCommitter;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.repository.Deployment;
import org.flowable.engine.repository.DeploymentBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code DeploymentController} 的環境變數替換接線（backlog #53）—— <b>純單元</b>。
 *
 * <h2>這一組測試釘住的事實</h2>
 *
 * <ol>
 *   <li><b>原始落地、resolved 上線</b>：引擎收到 resolved XML；磁碟與
 *       Git commit 保存原始 XML（含佔位符）。「祕密不進版控」的邊界。</li>
 *   <li><b>fail-closed</b>：值未設定 → 400，且<b>在任何副作用之前</b>
 *       （不 lint、不寫檔、不建 repo、不部署、不稽核）。</li>
 *   <li><b>兩份 lint 的分工</b>：有佔位符時原始與 resolved 各 lint 一次；
 *       任一份失敗都不部署。resolved 失敗會擋在寫檔之前。</li>
 *   <li><b>稽核只記 hash 與變數名</b>：xmlSha256 是原始 XML 的指紋
 *       （與 #61 語意相同）、resolvedSha256 是 resolved 的指紋，
 *       不記任何值。</li>
 *   <li><b>無佔位符＝legacy</b>：只 lint 一次、稽核不加 resolved 欄位。</li>
 * </ol>
 *
 * <p>與 {@code DeploymentControllerGitTest} 同一手法（mock 而非整合測試）：
 * 被測的是 controller 的決策與呼叫順序，不需要真的 DB／引擎。
 */
class DeploymentControllerEnvTest {

    private static final String RAW_XML = """
            <definitions id="env" xmlns:flowable="http://flowable.org/bpmn">
              <userTask id="t" flowable:candidateGroups="${ENV_FINANCE_GROUP}"/>
            </definitions>
            """;
    private static final String RESOLVED_XML =
            RAW_XML.replace("${ENV_FINANCE_GROUP}", "finance_dept_approver");

    @TempDir
    Path tmp;

    private RepositoryService repositoryService;
    private DeploymentBuilder deploymentBuilder;
    private Deployment deployment;
    private BpmnLintService lintService;
    private AuditEventPublisher auditPublisher;

    @BeforeEach
    void setUp() {
        repositoryService = mock(RepositoryService.class);
        deploymentBuilder = mock(DeploymentBuilder.class);
        deployment = mock(Deployment.class);
        when(repositoryService.createDeployment()).thenReturn(deploymentBuilder);
        when(deploymentBuilder.name(any())).thenReturn(deploymentBuilder);
        when(deploymentBuilder.addString(any(), any())).thenReturn(deploymentBuilder);
        when(deploymentBuilder.deploy()).thenReturn(deployment);
        when(deployment.getId()).thenReturn("dep-env-1");
        when(deployment.getName()).thenReturn("env.bpmn20.xml");

        lintService = mock(BpmnLintService.class);
        when(lintService.lint(anyString()))
                .thenReturn(new BpmnLintService.LintResult(true, List.of()));

        auditPublisher = mock(AuditEventPublisher.class);
    }

    /** repo-dir 與 bpmn-dir 同一個（與 GitTest 相同）—— commit 需要檔案在 repo 內。 */
    private DeploymentController controller(Map<String, String> variables, boolean gitEnabled) {
        var environment = new MockEnvironment();
        variables.forEach((name, value) ->
                environment.withProperty("bpmn.variables." + name, value));
        Path dir = tmp.resolve("repo");
        return new DeploymentController(repositoryService, lintService, auditPublisher,
                new DeploymentGitCommitter(gitEnabled, dir.toString(), "test@bpm.local"),
                new BpmnEnvSubstitutor(environment), dir.toString());
    }

    private static MockMultipartFile multipart(String xml) {
        return new MockMultipartFile("file", "env.bpmn20.xml", "text/xml",
                xml.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(String s) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    }

    /** 讀出某個檔案在 HEAD commit 裡的內容（不是工作區）—— 驗「commit 了什麼」。 */
    private static byte[] committedContent(Path repoDir, String fileName) throws Exception {
        try (Git git = Git.open(repoDir.toFile())) {
            RevCommit head = git.log().call().iterator().next();
            try (TreeWalk walk = TreeWalk.forPath(git.getRepository(), fileName, head.getTree())) {
                assertThat(walk).as("HEAD commit 裡找不到 %s", fileName).isNotNull();
                return git.getRepository().open(walk.getObjectId(0)).getBytes();
            }
        }
    }

    // ── 替換：引擎 resolved、檔案與 commit 原始 ─────────────────────

    @Test
    @DisplayName("有佔位符：引擎拿到 resolved、檔案與 commit 是原始 XML、稽核記 hash 與變數名")
    void substitutionAffectsEngineButNotVersionControl() throws Exception {
        var controller = controller(Map.of("ENV_FINANCE_GROUP", "finance_dept_approver"), true);

        controller.deploy(multipart(RAW_XML), "env.bpmn20.xml", "admin001");

        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(deploymentBuilder).addString(anyString(), content.capture());
        assertThat(content.getValue())
                .as("引擎必須拿到 resolved XML")
                .isEqualTo(RESOLVED_XML);

        Path target = tmp.resolve("repo").resolve("env.bpmn20.xml");
        assertThat(Files.readString(target))
                .as("寫檔必須是原始 XML（含佔位符）")
                .isEqualTo(RAW_XML);
        assertThat(new String(committedContent(tmp.resolve("repo"), "env.bpmn20.xml"),
                StandardCharsets.UTF_8))
                .as("#61 的 commit 保存原始 XML —— 環境差異不進版控")
                .isEqualTo(RAW_XML);

        ArgumentCaptor<AuditEvent> audit = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditPublisher).publish(audit.capture());
        assertThat(audit.getValue().detail())
                .containsEntry("xmlSha256", sha256(RAW_XML))
                .containsEntry("resolvedSha256", sha256(RESOLVED_XML))
                .containsEntry("envSubstitutions", List.of("ENV_FINANCE_GROUP"))
                .containsEntry("envSubstitutionCount", 1);
        assertThat(audit.getValue().detail().values())
                .as("稽核只記 hash 與名字，不記值")
                .noneMatch(v -> String.valueOf(v).contains("finance_dept_approver"));

        verify(lintService).lint(RAW_XML);
        verify(lintService).lint(RESOLVED_XML);
    }

    // ── fail-closed ────────────────────────────────────────────────

    @Test
    @DisplayName("值未設定：400、不 lint、不寫檔、不建 repo、不部署、無稽核")
    void missingValueIsRejectedBeforeAnySideEffect() {
        var controller = controller(Map.of(), true);

        assertThatThrownBy(() -> controller.deploy(multipart(RAW_XML), "env.bpmn20.xml", "admin001"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> {
                    var rse = (ResponseStatusException) e;
                    assertThat(rse.getStatusCode().value())
                            .as("fail-closed 是 400（使用者可修正的設定問題），不是 500")
                            .isEqualTo(400);
                    assertThat(rse.getReason())
                            .contains("ENV_FINANCE_GROUP")
                            .contains("bpmn.variables.ENV_FINANCE_GROUP");
                });

        verify(lintService, never()).lint(anyString());
        verify(deploymentBuilder, never()).deploy();
        verify(auditPublisher, never()).publish(any());
        assertThat(Files.exists(tmp.resolve("repo").resolve("env.bpmn20.xml"))).isFalse();
        assertThat(Files.exists(tmp.resolve("repo").resolve(".git"))).isFalse();
    }

    @Test
    @DisplayName("resolved lint 失敗：400、不寫檔、不部署（值造成的問題在部署前擋下）")
    void resolvedLintFailureIsFailClosed() {
        when(lintService.lint(RAW_XML))
                .thenReturn(new BpmnLintService.LintResult(true, List.of()));
        when(lintService.lint(RESOLVED_XML))
                .thenReturn(new BpmnLintService.LintResult(false, List.of(
                        new BpmnLintService.LintError("t", "審核", "el-whitelist", "注入", "error"))));
        var controller = controller(Map.of("ENV_FINANCE_GROUP", "finance_dept_approver"), true);

        assertThatThrownBy(() -> controller.deploy(multipart(RAW_XML), "env.bpmn20.xml", "admin001"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getReason())
                        .contains("替換後"));

        verify(deploymentBuilder, never()).deploy();
        verify(auditPublisher, never()).publish(any());
        assertThat(Files.exists(tmp.resolve("repo").resolve("env.bpmn20.xml"))).isFalse();
    }

    @Test
    @DisplayName("原始 XML lint 失敗：即使 resolved 可過也 400（設計器與部署端接受度一致）")
    void rawLintFailureStopsDeployment() {
        when(lintService.lint(RAW_XML))
                .thenReturn(new BpmnLintService.LintResult(false, List.of(
                        new BpmnLintService.LintError("t", "審核", "assignee-required", "缺指派", "error"))));
        when(lintService.lint(RESOLVED_XML))
                .thenReturn(new BpmnLintService.LintResult(true, List.of()));
        var controller = controller(Map.of("ENV_FINANCE_GROUP", "finance_dept_approver"), false);

        assertThatThrownBy(() -> controller.deploy(multipart(RAW_XML), "env.bpmn20.xml", "admin001"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getReason())
                        .contains("BPMN Lint"));

        verify(lintService, never()).lint(RESOLVED_XML);
        verify(deploymentBuilder, never()).deploy();
    }

    // ── 無佔位符：與加入本功能前相同 ───────────────────────────────

    @Test
    @DisplayName("無佔位符：只 lint 一次、引擎與檔案同一份、稽核不加 resolved 欄位")
    void noPlaceholderKeepsLegacyBehavior() throws Exception {
        String plain = "<definitions id=\"plain\"/>";
        var controller = controller(Map.of(), true);

        controller.deploy(multipart(plain), "env.bpmn20.xml", "admin001");

        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(deploymentBuilder).addString(anyString(), content.capture());
        assertThat(content.getValue()).isEqualTo(plain);
        assertThat(Files.readString(tmp.resolve("repo").resolve("env.bpmn20.xml"))).isEqualTo(plain);
        verify(lintService, times(1)).lint(plain);

        ArgumentCaptor<AuditEvent> audit = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditPublisher).publish(audit.capture());
        assertThat(audit.getValue().detail())
                .containsEntry("xmlSha256", sha256(plain))
                .doesNotContainKeys("resolvedSha256", "envSubstitutions", "envSubstitutionCount");
    }
}
