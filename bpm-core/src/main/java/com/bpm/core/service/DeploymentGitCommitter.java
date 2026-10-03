package com.bpm.core.service;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.EmptyCommitException;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.revwalk.RevCommit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 把已寫入的 BPMN 檔 commit 進 Git（backlog #61）。
 *
 * <p>spec §技術棧明示「BPMN XML 版控於 Git（{@code bpmn-definitions/} 目錄）」。
 * 加入本服務前，部署只做「寫檔＋稽核 SHA-256」—— 檔案有了、指紋有了，
 * 但沒有任何一版被保存下來，無從 diff、無從回復。
 *
 * <h2>為什麼用 JGit 而不是 exec {@code git}</h2>
 *
 * <p>prod 映像只保證有 JRE，不保證有 git binary。把部署流程押在
 * 「容器裡剛好裝了 git」上是部署環境的隱性依賴，而且 CLI 的失敗
 * 只能靠 exit code／輸出文字判斷。JGit 讓 commit 失敗是 Java 例外，
 * controller 能明確地 fail-closed（503、不呼叫 Flowable deploy）。
 *
 * <h2>啟用與否</h2>
 *
 * <p>{@code bpm.bpmn.git.enabled} 預設 false：未啟用時 {@link #commitFile}
 * 連 repo 目錄都不碰，部署行為與加入本功能前相同（不多做任何 I/O；稽核欄位
 * 集合不變，見 controller）。啟用是明確的選擇 —— 因為 repo 不存在時本服務
 * 會自動 {@code git init}（見 {@link #openOrInitRepo}），誤設 {@code repo-dir}
 * 會把一個既有目錄變成 repo。
 */
@Service
public class DeploymentGitCommitter {

    private static final Logger log = LoggerFactory.getLogger(DeploymentGitCommitter.class);

    private final boolean enabled;
    private final Path repoDir;
    private final String authorEmail;

    /**
     * 序列化「init → add → commit」。
     *
     * <p>並行部署時同時只有一個 commit 在跑。JGit 自己也會在 index.lock 上擋，
     * 但第二個部署拿到的是例外（{@code JGitInternalException}），不是排隊等待；
     * 這裡讓它排隊，結果是可預期的。只鎖行程內 —— 多實例部署本來就不該共用
     * 同一個 repo 工作目錄，跨行程鎖救不了那種設定錯誤。
     */
    private final ReentrantLock lock = new ReentrantLock();

    public DeploymentGitCommitter(
            @Value("${bpm.bpmn.git.enabled:false}") boolean enabled,
            @Value("${bpm.bpmn.git.repo-dir:${bpm.bpmn-definitions-dir:./bpmn-definitions}}") String repoDir,
            @Value("${bpm.bpmn.git.author-email:bpm-core@localhost}") String authorEmail) {
        this.enabled = enabled;
        // 與 DeploymentController 的 bpmnDir 同樣先 absolute＋normalize：
        // relativize 需要兩邊都是絕對路徑，否則 commit 的檔名會錯。
        this.repoDir = Path.of(repoDir).toAbsolutePath().normalize();
        this.authorEmail = authorEmail;
    }

    /** 部署流程用來決定是否走 commit 分支。 */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * commit 已寫入的 BPMN 檔。
     *
     * @param file       已寫入磁碟的檔案（必須在 {@code repoDir} 之內）
     * @param message    commit message；controller 已把 {@code xmlSha256} 放在第二段
     * @param authorName git author／committer 的名字（操作者 id）
     * @return commit 的完整 object id；內容與上次 commit 相同時回 {@code null}
     * @throws DeploymentGitException repo 無法使用或 commit 失敗
     */
    public String commitFile(Path file, String message, String authorName) {
        if (!enabled) {
            // 未啟用時連 repo 目錄都不碰 —— 這是「不啟用＝逐位元不變」的邊界。
            return null;
        }
        Path absolute = file.toAbsolutePath().normalize();
        // repo-dir 允許獨立設定（預設等於 bpmn-definitions-dir）。一旦被指到
        // 不含部署檔案的目錄，relativize 會生出 "../" 開頭的路徑，JGit 的
        // addFilepattern 不接受這種形狀，錯誤也會長得很難懂。設定錯誤就在
        // 這裡擋下，訊息直接說出兩邊的路徑。
        if (!absolute.startsWith(repoDir)) {
            throw new DeploymentGitException("部署檔不在 Git repo-dir 之內（file=" + absolute
                    + "，repo-dir=" + repoDir + "）");
        }
        lock.lock();
        try (Git git = openOrInitRepo()) {
            // Windows 路徑分隔字元換成 /，git 的 index 只認 /。
            String relative = repoDir.relativize(absolute).toString().replace('\\', '/');
            git.add().addFilepattern(relative).call();
            RevCommit commit;
            try {
                // ⚠️ setAllowEmpty(false) 不可省：JGit 的 CommitCommand 預設
                // 「允許空 commit」（allowEmpty = only.isEmpty() ? TRUE : FALSE），
                // 且 call() 從不回 null。少了它，重複部署同一份 XML 會在歷史
                // 留下一筆 tree 完全沒變的 commit —— 版控訊號被噪音淹掉。
                commit = git.commit()
                        .setAuthor(authorName, authorEmail)
                        .setCommitter(authorName, authorEmail)
                        .setMessage(message)
                        .setAllowEmpty(false)
                        .call();
            } catch (EmptyCommitException noChanges) {
                // index 與 HEAD 無差異（內容未變，或被 .gitignore 排除）：
                // 不是錯誤，只是沒有新版本可記。回 null，稽核不記 gitCommit。
                log.info("BPMN 內容與上次 commit 相同，不產生空 commit: {}", relative);
                return null;
            }
            log.info("BPMN 已 commit: {} ({})", relative, commit.abbreviate(7).name());
            return commit.getName();
        } catch (Exception e) {
            // 全部收斂成一種例外：controller 只需要知道「版控寫不進去」。
            throw new DeploymentGitException(
                    "Git commit 失敗（repo-dir=" + repoDir + "）: " + e.getMessage(), e);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 開 repo；沒有 repo 就自動 init。
     *
     * <p>用 {@code Git.open} 探測而不是自己檢查 {@code .git} 存不存在：
     * linked worktree／submodule 的 {@code .git} 是<b>檔案</b>不是目錄，
     * 「目錄檢查」會把那種 repo 誤判成沒有 repo、再 init 一次。
     *
     * <p>自動 init 是為了可用性：dev 第一次部署、或正式環境首次上線時，
     * 不該要求維運先手動 {@code git init} 才能部署 —— 那會讓「啟用版控」
     * 變成一個必須照順序做對的維運步驟，漏了就是 503。
     * 代價是 {@code repo-dir} 若誤設到既有目錄，這裡會把它變成 repo，
     * 所以預設 {@code enabled=false}，啟用必須是明確的決定。
     *
     * <p>若 {@code repoDir} 存在但不是目錄（例如指向一個檔案），
     * {@code createDirectories} 會直接失敗 —— 呼叫端轉成 503。
     */
    private Git openOrInitRepo() throws IOException, GitAPIException {
        Files.createDirectories(repoDir);
        try {
            return Git.open(repoDir.toFile());
        } catch (RepositoryNotFoundException notARepo) {
            Git git = Git.init().setDirectory(repoDir.toFile()).call();
            log.info("已初始化 BPMN 版控 repo: {}", repoDir);
            return git;
        }
    }
}
