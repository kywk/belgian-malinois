package com.bpm.core.service;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link DeploymentGitCommitter} 的單元測試（backlog #61）。
 *
 * <h2>為什麼不進整合測試</h2>
 *
 * <p>這支服務的契約是「檔案系統上的 Git 行為」，與 Flowable／DB 無關 ——
 * 用 temp dir＋真的 JGit 就能完整驗證。整合層（controller 的 503、
 * 稽核欄位）另外由 {@code DeploymentControllerGitTest} 用 mock 驗，
 * 兩者都不需要 Testcontainers。
 *
 * <h2>⚠️ 這裡釘住的 JGit 陷阱</h2>
 *
 * <p>JGit 的 {@code CommitCommand} <b>預設允許空 commit</b>
 * （原始碼：{@code allowEmpty = only.isEmpty() ? TRUE : FALSE}），
 * 而且 {@code call()} <b>從不回 null</b>。如果照「沒異動時 commit() 會回 null」
 * 的直覺寫，重複部署同一份 XML 會在歷史留下一筆 tree 完全沒變的 commit。
 * 所以本服務顯式 {@code setAllowEmpty(false)}，並在
 * {@link org.eclipse.jgit.api.errors.EmptyCommitException} 時回 null。
 * {@link #unchangedContentDoesNotCreateEmptyCommit()} 就是釘這件事。
 */
class DeploymentGitCommitterTest {

    private static final String AUTHOR_EMAIL = "test@bpm.local";
    private static final String XML_V1 = "<definitions id=\"v1\"/>";
    private static final String XML_V2 = "<definitions id=\"v2\"/>";

    @TempDir
    Path tmp;

    private static DeploymentGitCommitter committer(boolean enabled, Path repoDir) {
        return new DeploymentGitCommitter(enabled, repoDir.toString(), AUTHOR_EMAIL);
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

    /** commit 後 tree 裡的檔案內容（不是工作目錄的內容）。 */
    private static String committedContent(Path repoDir, String relative) throws Exception {
        try (Git git = Git.open(repoDir.toFile())) {
            ObjectId head = git.getRepository().resolve(Constants.HEAD);
            try (RevWalk walk = new RevWalk(git.getRepository())) {
                RevCommit commit = walk.parseCommit(head);
                try (TreeWalk tw = TreeWalk.forPath(git.getRepository(), relative, commit.getTree())) {
                    assertThat(tw).as("commit 的 tree 裡找不到 %s", relative).isNotNull();
                    return new String(git.getRepository().open(tw.getObjectId(0)).getBytes(),
                            StandardCharsets.UTF_8);
                }
            }
        }
    }

    // ── enabled=false：零行為差異的邊界 ──────────────────────────────

    @Test
    @DisplayName("enabled=false：連 repo 目錄都不碰（檔案系統零副作用）")
    void disabledDoesNotTouchRepo() {
        Path repoDir = tmp.resolve("never-created");
        var committer = committer(false, repoDir);

        assertThat(committer.commitFile(repoDir.resolve("leave-approval.bpmn20.xml"), "m", "admin001"))
                .isNull();
        assertThat(Files.exists(repoDir))
                .as("enabled=false 卻建了 repo 目錄 —— 未啟用必須完全不碰檔案系統")
                .isFalse();
    }

    // ── 正常路徑：init、commit、內容未變 ─────────────────────────────

    @Test
    @DisplayName("repo 未 init → 自動 init；commit 真的產生，message／author／內容都對")
    void autoInitAndCommit() throws Exception {
        Path repoDir = tmp.resolve("bpmn-definitions");
        Files.createDirectories(repoDir);
        Path file = repoDir.resolve("leave-approval.bpmn20.xml");
        Files.writeString(file, XML_V1);
        String message = "deploy: leave-approval.bpmn20.xml by admin001\n\nxmlSha256: " + sha256(XML_V1);

        String commitId = committer(true, repoDir).commitFile(file, message, "admin001");

        assertThat(commitId).as("自動 init 後必須產生 commit").isNotNull();
        assertThat(Files.isDirectory(repoDir.resolve(".git")))
                .as("沒有 .git —— 自動 init 沒發生")
                .isTrue();

        var log = commits(repoDir);
        assertThat(log).hasSize(1);
        RevCommit commit = log.get(0);
        assertThat(commit.getName()).isEqualTo(commitId);
        assertThat(commit.getFullMessage()).isEqualTo(message);
        assertThat(commit.getAuthorIdent().getName()).isEqualTo("admin001");
        assertThat(commit.getAuthorIdent().getEmailAddress()).isEqualTo(AUTHOR_EMAIL);
        assertThat(commit.getCommitterIdent().getName()).isEqualTo("admin001");
        assertThat(committedContent(repoDir, "leave-approval.bpmn20.xml")).isEqualTo(XML_V1);
    }

    @Test
    @DisplayName("內容未變 → 不產生空 commit（回 null，歷史仍一筆）")
    void unchangedContentDoesNotCreateEmptyCommit() throws Exception {
        Path repoDir = tmp.resolve("repo");
        Files.createDirectories(repoDir);
        Path file = repoDir.resolve("leave-approval.bpmn20.xml");
        Files.writeString(file, XML_V1);
        var committer = committer(true, repoDir);
        String first = committer.commitFile(file, "deploy: 第一次", "admin001");
        assertThat(first).isNotNull();

        // 同一份內容再部署一次（message 不同也一樣）：不得產生第二筆。
        String second = committer.commitFile(file, "deploy: 第二次（內容相同）", "admin002");

        assertThat(second)
                .as("內容未變必須回 null，而不是一筆空 commit 的 id")
                .isNull();
        var log = commits(repoDir);
        assertThat(log)
                .as("重複部署同一份 XML 不得在歷史留下噪音")
                .hasSize(1);
        assertThat(log.get(0).getFullMessage()).isEqualTo("deploy: 第一次");
    }

    @Test
    @DisplayName("內容有變 → 第二筆 commit（沒有把『未變』誤判成『已變』）")
    void changedContentCreatesSecondCommit() throws Exception {
        Path repoDir = tmp.resolve("repo");
        Files.createDirectories(repoDir);
        Path file = repoDir.resolve("leave-approval.bpmn20.xml");
        Files.writeString(file, XML_V1);
        var committer = committer(true, repoDir);
        assertThat(committer.commitFile(file, "deploy: v1", "admin001")).isNotNull();

        Files.writeString(file, XML_V2);
        String second = committer.commitFile(file, "deploy: v2", "admin001");

        assertThat(second).isNotNull();
        var log = commits(repoDir);
        assertThat(log).hasSize(2);
        assertThat(log.get(0).getFullMessage()).isEqualTo("deploy: v2");
        assertThat(committedContent(repoDir, "leave-approval.bpmn20.xml")).isEqualTo(XML_V2);
    }

    // ── 失敗路徑 ────────────────────────────────────────────────────

    @Test
    @DisplayName("repo-dir 是檔案 → DeploymentGitException（不是半套狀態）")
    void repoDirIsAFileFails() throws Exception {
        Path repoDir = Files.createFile(tmp.resolve("repo-is-a-file"));
        // 詞法上位於 repoDir 之下，讓失敗發生在 createDirectories 而不是圍堵檢查。
        Path file = repoDir.resolve("leave-approval.bpmn20.xml");

        assertThatThrownBy(() -> committer(true, repoDir).commitFile(file, "m", "admin001"))
                .isInstanceOf(DeploymentGitException.class)
                .hasMessageContaining("Git commit 失敗");
    }

    @Test
    @DisplayName("部署檔不在 repo-dir 之內 → 明確拒絕（設定錯誤不變成 JGit 的謎題）")
    void fileOutsideRepoDirRejected() {
        Path repoDir = tmp.resolve("repo");

        assertThatThrownBy(() -> committer(true, repoDir)
                .commitFile(tmp.resolve("elsewhere.bpmn20.xml"), "m", "admin001"))
                .isInstanceOf(DeploymentGitException.class)
                .hasMessageContaining("不在 Git repo-dir 之內");
    }

    @Test
    @DisplayName("並行部署被同一把鎖序列化：N 筆 commit 全部落地、無例外")
    void concurrentCommitsAreSerialized() throws Exception {
        Path repoDir = tmp.resolve("repo");
        Files.createDirectories(repoDir);
        int n = 6;
        for (int i = 0; i < n; i++) {
            Files.writeString(repoDir.resolve("p" + i + ".bpmn20.xml"), XML_V1 + i);
        }
        var committer = committer(true, repoDir);

        var pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int idx = i;
                futures.add(pool.submit(() -> committer.commitFile(
                        repoDir.resolve("p" + idx + ".bpmn20.xml"), "deploy: p" + idx, "admin001")));
            }
            for (Future<String> f : futures) {
                // 沒有鎖的話，JGit 在 index.lock 上互撞，這裡會拿到例外。
                assertThat(f.get(30, TimeUnit.SECONDS)).isNotNull();
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(commits(repoDir)).hasSize(n);
    }

    // ── Spring 設定繫結 ─────────────────────────────────────────────

    @Test
    @DisplayName("application.yml 預設值：enabled=false、repo-dir 跟隨 bpmn-definitions-dir")
    void applicationYmlDefaults() {
        var factory = new YamlPropertiesFactoryBean();
        factory.setResources(new ClassPathResource("application.yml"));
        Properties props = factory.getObject();
        assertThat(props).isNotNull();

        assertThat(props.getProperty("bpm.bpmn.git.enabled"))
                .as("版控預設必須關閉 —— 啟用是明確的決定")
                .isEqualTo("false");
        assertThat(props.getProperty("bpm.bpmn.git.repo-dir"))
                .as("repo-dir 預設必須與被部署的目錄同一個，否則預設值下 commit 一定找不到檔案")
                .isEqualTo("${bpm.bpmn-definitions-dir}");
        assertThat(props.getProperty("bpm.bpmn.git.author-email"))
                .isEqualTo("bpm-core@localhost");
    }

    @Test
    @DisplayName("@Value 繫結：屬性全缺 → enabled=false")
    void wiringDefaultsToDisabled() {
        runner().run(ctx -> {
            assertThat(ctx).hasSingleBean(DeploymentGitCommitter.class);
            assertThat(ctx.getBean(DeploymentGitCommitter.class).isEnabled()).isFalse();
        });
    }

    @Test
    @DisplayName("@Value 繫結：git.repo-dir 缺席時 fallback 到 bpm.bpmn-definitions-dir")
    void wiringRepoDirFallsBackToDefinitionsDir() throws Exception {
        Path defsDir = tmp.resolve("wiring-defs");
        runner().withPropertyValues(
                        "bpm.bpmn.git.enabled=true",
                        "bpm.bpmn-definitions-dir=" + defsDir)
                .run(ctx -> {
                    var committer = ctx.getBean(DeploymentGitCommitter.class);
                    assertThat(committer.isEnabled()).isTrue();
                    Files.createDirectories(defsDir);
                    Files.writeString(defsDir.resolve("a.bpmn20.xml"), XML_V1);
                    assertThat(committer.commitFile(defsDir.resolve("a.bpmn20.xml"), "m", "admin001"))
                            .isNotNull();
                    assertThat(Files.isDirectory(defsDir.resolve(".git")))
                            .as("repo 不在 bpmn-definitions-dir —— fallback placeholder 沒生效")
                            .isTrue();
                });
    }

    @Test
    @DisplayName("@Value 繫結：明確的 git.repo-dir 優先於 bpmn-definitions-dir")
    void wiringExplicitRepoDirWins() throws Exception {
        Path defsDir = tmp.resolve("wiring-defs");
        Path repoDir = tmp.resolve("wiring-repo");
        runner().withPropertyValues(
                        "bpm.bpmn.git.enabled=true",
                        "bpm.bpmn-definitions-dir=" + defsDir,
                        "bpm.bpmn.git.repo-dir=" + repoDir)
                .run(ctx -> {
                    var committer = ctx.getBean(DeploymentGitCommitter.class);
                    Files.createDirectories(repoDir);
                    Files.writeString(repoDir.resolve("a.bpmn20.xml"), XML_V1);
                    assertThat(committer.commitFile(repoDir.resolve("a.bpmn20.xml"), "m", "admin001"))
                            .isNotNull();
                    assertThat(Files.isDirectory(repoDir.resolve(".git")))
                            .as("repo 不在明確設定的 repo-dir —— @Value 的屬性名可能拼錯")
                            .isTrue();
                });
    }

    /**
     * 只註冊 placeholder 解析與被測 bean 的窄切片 context。
     *
     * <p>不用 {@code @SpringBootTest}：本專案的 {@code IntegrationTestBase}
     * 是單一 static port 的 DEFINED_PORT 設計，多一個完整 context 會讓
     * 既有整合測試整組紅掉（同 {@code WebhookConsumerHmacSecretRequiredTest}
     * 的註解）。這裡要驗的只是 {@code @Value} 的 placeholder 解析。
     */
    private static ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
                .withBean(DeploymentGitCommitter.class);
    }
}
