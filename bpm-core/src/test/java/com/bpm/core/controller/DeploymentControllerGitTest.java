package com.bpm.core.controller;

import com.bpm.core.audit.AuditEventPublisher;
import com.bpm.core.dto.AuditEvent;
import com.bpm.core.lint.BpmnLintService;
import com.bpm.core.service.DeploymentGitCommitter;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.revwalk.RevCommit;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.repository.Deployment;
import org.flowable.engine.repository.DeploymentBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code DeploymentController} 與 Git commit 的接線（backlog #61）—— <b>純單元</b>。
 *
 * <h2>這一組測試釘住的事實</h2>
 *
 * <ol>
 *   <li><b>順序</b>：Git commit 在 {@code repositoryService.createDeployment()}
 *       <b>之前</b>。用 {@code thenAnswer} 在引擎部署發生的當下檢查 Git log，
 *       而不是只看最後狀態 —— 順序反了但兩者都成功時，只看最後狀態驗不出來。</li>
 *   <li><b>fail-closed</b>：commit 失敗 → 503、引擎<b>完全沒有</b>新 deployment、
 *       稽核也沒有那一筆。這是本工項最關鍵的取捨（見 controller 的 javadoc）。</li>
 *   <li><b>稽核欄位</b>：有 commit 時記 {@code gitCommit} short id；內容未變
 *       （沒有新 commit）時<b>不記</b> —— 不製造指向不存在 commit 的欄位。</li>
 *   <li><b>enabled=false 零行為差異</b>：部署照常、repo 目錄不存在、
 *       稽核沒有 {@code gitCommit}。</li>
 * </ol>
 *
 * <p>用 mock 而非整合測試：本專案的 {@code IntegrationTestBase} 是單一
 * static port 的 DEFINED_PORT 設計，多一個完整 context 會讓既有整合測試
 * 整組紅掉（見 {@code WebhookConsumerHmacSecretRequiredTest} 的註解）。
 * 這裡要驗的 controller 決策不依賴真的 DB／引擎。
 */
class DeploymentControllerGitTest {

    private static final String XML_V1 = "<definitions id=\"v1\"/>";
    private static final String XML_V2 = "<definitions id=\"v2\"/>";

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
        when(deployment.getId()).thenReturn("dep-1");
        when(deployment.getName()).thenReturn("leave-approval.bpmn20.xml");

        lintService = mock(BpmnLintService.class);
        when(lintService.lint(anyString()))
                .thenReturn(new BpmnLintService.LintResult(true, List.of()));

        auditPublisher = mock(AuditEventPublisher.class);
    }

    private DeploymentController controller(boolean gitEnabled, Path bpmnDir, Path repoDir) {
        return new DeploymentController(repositoryService, lintService, auditPublisher,
                new DeploymentGitCommitter(gitEnabled, repoDir.toString(), "test@bpm.local"),
                bpmnDir.toString());
    }

    private static MockMultipartFile multipart(String xml, String filename) {
        return new MockMultipartFile("file", filename, "text/xml",
                xml.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(String s) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    }

    private static List<RevCommit> commits(Path repoDir) throws Exception {
        try (Git git = Git.open(repoDir.toFile())) {
            return StreamSupport.stream(git.log().call().spliterator(), false).toList();
        }
    }

    // ── enabled=true ────────────────────────────────────────────────

    @Test
    @DisplayName("enabled=true：commit 先於引擎部署，稽核帶 gitCommit short id 與 sha")
    void commitBeforeEngineDeployAndAuditHasShortId() throws Exception {
        Path repoDir = tmp.resolve("repo");
        var controller = controller(true, repoDir, repoDir);
        String xml = "<definitions id=\"leave\"/>";
        // 在引擎部署的當下檢查 Git log：commit 必須已經存在。
        // 只驗最後狀態的話，「先 deploy 再 commit」也會綠 —— 那正是要防的順序。
        when(deploymentBuilder.deploy()).thenAnswer(invocation -> {
            assertThat(commits(repoDir))
                    .as("Flowable deploy 執行時 Git commit 還不存在 —— 順序反了")
                    .hasSize(1);
            return deployment;
        });

        controller.deploy(multipart(xml, "leave-approval.bpmn20.xml"),
                "leave-approval.bpmn20.xml", "admin001");

        var log = commits(repoDir);
        assertThat(log).hasSize(1);
        RevCommit commit = log.get(0);
        assertThat(commit.getFullMessage()).isEqualTo(
                "deploy: leave-approval.bpmn20.xml by admin001\n\nxmlSha256: " + sha256(xml));
        assertThat(commit.getAuthorIdent().getName()).isEqualTo("admin001");

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditPublisher).publish(captor.capture());
        assertThat(captor.getValue().detail())
                .containsEntry("deploymentId", "dep-1")
                .containsEntry("xmlSha256", sha256(xml))
                .containsEntry("gitCommit", commit.getName().substring(0, 7));
        verify(deploymentBuilder).deploy();
    }

    @Test
    @DisplayName("enabled=true 且內容未變：第二次部署不新增 commit，稽核不記 gitCommit")
    void unchangedContentAuditOmitsGitCommit() throws Exception {
        Path repoDir = tmp.resolve("repo");
        var controller = controller(true, repoDir, repoDir);

        controller.deploy(multipart(XML_V1, "leave-approval.bpmn20.xml"),
                "leave-approval.bpmn20.xml", "admin001");
        controller.deploy(multipart(XML_V1, "leave-approval.bpmn20.xml"),
                "leave-approval.bpmn20.xml", "admin001");

        assertThat(commits(repoDir)).hasSize(1);
        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditPublisher, times(2)).publish(captor.capture());
        assertThat(captor.getAllValues().get(0).detail())
                .as("第一次部署有真的 commit，必須記 gitCommit")
                .containsKey("gitCommit");
        assertThat(captor.getAllValues().get(1).detail())
                .as("第二次沒有新 commit，不得記一個指向舊 commit 的 gitCommit")
                .doesNotContainKey("gitCommit");
        verify(deploymentBuilder, times(2)).deploy();
    }

    // ── fail-closed：commit 失敗就不部署 ────────────────────────────

    @Test
    @DisplayName("commit 失敗（index.lock 被佔）→ 503、引擎零新部署、稽核零筆")
    void commitFailureIsFailClosed() throws Exception {
        Path repoDir = tmp.resolve("repo");
        var controller = controller(true, repoDir, repoDir);
        // 先成功部署一次把 repo 建起來（含 .git）。
        controller.deploy(multipart(XML_V1, "leave-approval.bpmn20.xml"),
                "leave-approval.bpmn20.xml", "admin001");

        // 模擬另一個行程持有 index 鎖（stale lock 也一樣）：JGit 的 add 會失敗。
        Files.writeString(repoDir.resolve(".git/index.lock"), "");
        try {
            assertThatThrownBy(() -> controller.deploy(multipart(XML_V2, "leave-approval.bpmn20.xml"),
                    "leave-approval.bpmn20.xml", "admin002"))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value())
                            .as("commit 失敗必須是 503（服務暫時不可用），不是 500")
                            .isEqualTo(503));
        } finally {
            Files.deleteIfExists(repoDir.resolve(".git/index.lock"));
        }

        // 第一次的部署不算；第二次必須完全沒有發生。
        verify(deploymentBuilder, times(1)).deploy();
        verify(auditPublisher, times(1)).publish(any());
        assertThat(commits(repoDir)).hasSize(1);
        // 已知且刻意的取捨：檔案已寫入（XML_V2），但沒有上線。
        // 重送即可收斂；反過來「上線了但版控沒有」無法追溯（見 controller javadoc）。
        assertThat(Files.readString(repoDir.resolve("leave-approval.bpmn20.xml"))).isEqualTo(XML_V2);
    }

    // ── enabled=false：與加入版控前相同 ─────────────────────────────

    @Test
    @DisplayName("enabled=false：部署照常、repo 目錄不存在、稽核沒有 gitCommit")
    void disabledKeepsExistingBehavior() throws Exception {
        Path bpmnDir = tmp.resolve("bpmn");
        Path repoDir = tmp.resolve("never-created");
        var controller = controller(false, bpmnDir, repoDir);

        controller.deploy(multipart(XML_V1, "leave-approval.bpmn20.xml"),
                "leave-approval.bpmn20.xml", "admin001");

        verify(deploymentBuilder).deploy();
        assertThat(Files.exists(repoDir))
                .as("enabled=false 卻建了 repo 目錄 —— 未啟用必須零副作用")
                .isFalse();
        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditPublisher).publish(captor.capture());
        assertThat(captor.getValue().detail()).doesNotContainKey("gitCommit");
    }
}
