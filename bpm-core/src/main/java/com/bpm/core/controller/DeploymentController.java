package com.bpm.core.controller;

import org.springframework.transaction.annotation.Transactional;
import com.bpm.core.security.CallerId;
import com.bpm.core.audit.AuditEventPublisher;
import com.bpm.core.dto.AuditEvent;
import com.bpm.core.audit.model.OperationType;
import com.bpm.core.lint.BpmnLintService;
import com.bpm.core.service.BpmnEnvSubstitutionException;
import com.bpm.core.service.BpmnEnvSubstitutor;
import com.bpm.core.service.DeploymentGitCommitter;
import com.bpm.core.service.DeploymentGitException;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.repository.Deployment;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/deployments")
public class DeploymentController {

    /** 檔名允許的字元以外一律換成底線（白名單制）。 */
    private static final java.util.regex.Pattern UNSAFE_CHARS =
            java.util.regex.Pattern.compile("[^A-Za-z0-9._-]");

    private final RepositoryService repositoryService;
    private final BpmnLintService lintService;
    private final AuditEventPublisher auditPublisher;
    private final DeploymentGitCommitter gitCommitter;
    private final BpmnEnvSubstitutor envSubstitutor;
    private final Path bpmnDir;

    public DeploymentController(RepositoryService repositoryService, BpmnLintService lintService,
                                AuditEventPublisher auditPublisher,
                                DeploymentGitCommitter gitCommitter,
                                BpmnEnvSubstitutor envSubstitutor,
                                @Value("${bpm.bpmn-definitions-dir:./bpmn-definitions}") String bpmnDir) {
        this.repositoryService = repositoryService;
        this.lintService = lintService;
        this.auditPublisher = auditPublisher;
        this.gitCommitter = gitCommitter;
        this.envSubstitutor = envSubstitutor;
        // normalize + toAbsolutePath 一次做掉。少了這一步，下面的 startsWith
        // 圍堵檢查會對「合法」名稱誤判：bpmnDir 若是相對路徑 "./bpmn-definitions"，
        // 而 resolve(...).normalize() 會把 "./" 消掉變成 "bpmn-definitions/x"，
        // 兩者前綴比對永遠不相等 → 正常部署被擋成 400。
        this.bpmnDir = Path.of(bpmnDir).toAbsolutePath().normalize();
    }

    /**
     * 只保留檔名的最後一節，並限制為安全字元集。
     *
     * <p>用白名單而非「拒絕 ..」：黑名單必然漏掉編碼變體
     * （{@code %2e%2e}、反斜線、多重編碼）。正常的部署名稱
     * （{@code leave-approval.bpmn20.xml}）完全不受影響。
     */
    static String safeFileName(String raw) {
        String name = (raw == null || raw.isBlank()) ? "process.bpmn20.xml" : raw;
        name = name.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        name = UNSAFE_CHARS.matcher(name).replaceAll("_");
        // 「.」與「..」全由允許字元組成，因此會原樣通過字元白名單，
        // 但它們在檔案系統上是目錄參照而非檔名。單靠後面的 startsWith
        // 圍堵雖然也能攔下，但不該把正確性押在單一層防線上。
        if (name.chars().allMatch(c -> c == '.')) {
            name = name.replace('.', '_');
        }
        return name.isBlank() ? "process.bpmn20.xml" : name;
    }

    /** 部署內容的指紋。讓稽核紀錄能獨立驗證「當時上線的是哪一份」。 */
    private static String sha256(String s) {
        try {
            var md = java.security.MessageDigest.getInstance("SHA-256");
            var d = md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 必須存在", e);
        }
    }

    /**
     * 部署 BPMN：替換環境變數（#53）→ lint → 寫檔 → Git commit（啟用時）
     * → Flowable deploy（resolved）→ 稽核。
     *
     * <h2>⚠️ 順序是刻意的（fail-closed）</h2>
     *
     * <p>Git commit（{@code bpm.bpmn.git.enabled=true} 時）排在 Flowable deploy
     * <b>之前</b>，且 commit 失敗就回 503、完全不呼叫
     * {@code repositoryService.createDeployment()}。
     *
     * <p>為什麼是這個方向：engine deploy 是對外生效點 —— 一旦成功，之後啟動的
     * 案件就照新版本走。寧可「檔案與版控已更新、但沒有上線」（重送即可；
     * 內容未變時不會多一筆 commit，只會把引擎部署補完成），也不要
     * 「已上線、但版控沒有那一版」（事後無從 diff、無從回復，等於版控說了謊）。
     *
     * <p>啟用時 commit 在 DB 交易之內、但檔案系統不參與交易：若 commit 之後
     * engine deploy 才失敗，DB 交易會回滾，檔案與 git commit 仍留著。
     * 這是刻意選的一邊，理由同上 —— 多一筆未上線的版控紀錄可以靠重送收斂，
     * 少一筆上線紀錄無法補救。
     *
     * <p>{@code bpm.bpmn.git.enabled=false}（預設）時整段跳過，部署行為與加入
     * 版控前相同：不多做任何 I/O，無佔位符時稽核欄位集合也不變（唯一差異是
     * detail 的 key 順序改為固定的插入序；原本的 {@code Map.of} 順序本來就
     * 隨 JVM 執行而異）。
     *
     * <h2>#53 環境變數替換：原始 XML 落地、resolved XML 上線</h2>
     *
     * <p>BPMN 裡的 {@code ${ENV_*}} 佔位符（spec §12.3）在<b>最前面</b>就替換，
     * 且查不到值直接 400 —— 此時還沒有寫檔、commit 或引擎部署，所以失敗是
     * 完全無副作用的（見 {@link BpmnEnvSubstitutor} 的政策）。
     *
     * <p><b>寫檔與 Git commit 用的是原始 XML</b>（含佔位符）：版控保存的是
     * 跨環境共用的那一份定義，正式環境的群組名之類的設定不進版控。
     * resolved XML 只存在於記憶體，用於引擎部署與稽核 hash。
     *
     * <h2>為什麼有佔位符時要 lint 兩份</h2>
     *
     * <p>兩份 lint 各有不可取代的職責：
     * <ul>
     *   <li><b>原始 XML</b>：與 {@code POST /api/bpmn/lint}（設計器按送審時走
     *       的同一個服務）<b>同一份輸入</b>。設計器看到的接受度與部署端一致，
     *       才不會出現「設計器綠燈、部署卻被另一條規則擋下」。</li>
     *   <li><b>resolved XML</b>：值造成的問題只有替換後才看得見 —— 未轉義的
     *       {@code &} 讓 XML 解析失敗、值裡的白名單外 EL（{@code ${evil.foo()}}）
     *       注入流程定義。這些在部署前發現，就不會延後到執行期。</li>
     * </ul>
     *
     * <p>無佔位符時 resolved 與原始是<b>同一個物件</b>（逐位元不變），
     * 只 lint 一次 —— 一般部署的成本與加入本功能前相同。
     */
    @PostMapping
    @Transactional("primaryTransactionManager")
    public Object deploy(@RequestParam("file") MultipartFile file,
                         @RequestParam(defaultValue = "") String name,
                         @CallerId
                         String operatorId) throws IOException {
        String xml = new String(file.getBytes());
        String deployName = name.isEmpty() ? file.getOriginalFilename() : name;

        // 1. 環境變數替換（#53）。fail-closed：查不到值 → 400，
        //    且在任何副作用（寫檔／commit／部署）之前就結束。
        BpmnEnvSubstitutor.Resolution env;
        try {
            env = envSubstitutor.resolve(xml);
        } catch (BpmnEnvSubstitutionException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }

        // 2. Lint validation（有佔位符時連 resolved 一起驗，理由見方法 javadoc）
        var lintResult = lintService.lint(xml);
        if (!lintResult.valid()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "BPMN Lint 驗證失敗: " + lintResult.errors().size() + " 個錯誤");
        }
        if (env.hasSubstitutions()) {
            var resolvedLint = lintService.lint(env.xml());
            if (!resolvedLint.valid()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "BPMN Lint 驗證失敗（環境變數替換後）: "
                                + resolvedLint.errors().size() + " 個錯誤");
            }
        }

        // 3. Save XML to bpmn-definitions/
        //
        // ⚠️ 寫入的是<b>原始</b> XML（含佔位符）：這是版控保存的內容。
        // deployName 來自 @RequestParam 或 client 的原始檔名，兩者皆不可信。
        // 改動前直接 bpmnDir.resolve(deployName) —— 與附件上傳完全相同的
        // path traversal（security-audit P0-1 明確點出這一行）：
        // name=../../../../app/app.jar 即可覆寫執行中的 jar。
        String safeName = safeFileName(deployName);
        Path target = bpmnDir.resolve(safeName).normalize();
        if (!target.startsWith(bpmnDir)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "部署名稱不合法");
        }
        Files.createDirectories(bpmnDir);
        Files.writeString(target, xml);

        // ⚠️ operatorId 原本寫死 null（security-audit P2-4）。
        //
        // 部署 BPMN 是這個平台上最有後果的單一操作：它決定所有後續案件的
        // 簽核路徑要送給誰。而那筆稽核紀錄是<b>匿名</b>的 ——
        // 事後可以看到「某時有人部署了流程定義」，但查不出是誰。
        //
        // 而且部署是覆寫式的：新版本一上線，之後啟動的案件全部照新規則走。
        // 這正是最需要問責的地方。
        String operator = operatorId != null && !operatorId.isBlank() ? operatorId : "unknown";

        // 部署內容的指紋。讓稽核紀錄能獨立驗證「當時上線的是哪一份」，
        // 同時放進 commit message —— 版控與稽核各自可查，兩邊對得起來。
        // ⚠️ 這裡刻意維持「提交的原始 XML」：xmlSha256 的語意從 #61 起就是
        // 版控裡那一份的指紋，加入 #53 後不變。
        String xmlSha256 = sha256(xml);

        // 4. Git commit（#61，僅在 bpm.bpmn.git.enabled=true 時）。
        //    commit 的是原始檔（target 的內容）—— 環境差異不進版控。
        String gitCommitId = null;
        if (gitCommitter.isEnabled()) {
            try {
                gitCommitId = gitCommitter.commitFile(target,
                        "deploy: " + safeName + " by " + operator + "\n\nxmlSha256: " + xmlSha256,
                        operator);
            } catch (DeploymentGitException e) {
                // fail-closed：版控寫不進去就不部署（見方法 javadoc）。
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "Git commit 失敗，部署已中止: " + e.getMessage(), e);
            }
        }

        // 5. Deploy to Flowable —— 送 resolved XML（有佔位符時）。
        Deployment deployment = repositoryService.createDeployment()
                .name(deployName)
                .addString(deployName != null ? deployName : "process.bpmn20.xml", env.xml())
                .deploy();

        // 6. Audit
        var auditDetail = new LinkedHashMap<String, Object>();
        auditDetail.put("deploymentId", deployment.getId());
        auditDetail.put("name", deployment.getName());
        // 部署的內容摘要：事後可據此確認當時上線的是哪一份 XML，
        // 而不必依賴 deploymentId 仍存在。xmlSha256 是提交的原始 XML。
        auditDetail.put("xmlSha256", xmlSha256);
        auditDetail.put("xmlBytes", xml.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        // #53：有替換時加記 resolved 指紋與用到的變數名。<b>只記 hash 與名字，
        // 不記值</b> —— 稽核是長期保存的紀錄，正式環境的群組名等設定
        // 不該因為一次部署而被寫進另一份儲存。名字清單已足夠回答
        // 「這次上線依賴哪些環境設定」。
        if (env.hasSubstitutions()) {
            auditDetail.put("resolvedSha256", sha256(env.xml()));
            auditDetail.put("envSubstitutions", env.usedNames());
            auditDetail.put("envSubstitutionCount", env.usedNames().size());
        }
        // 有 commit 時記 short id，讓「這筆稽核」可以直接對到 Git 歷史。
        // 內容未變（回 null）或未啟用時不記 —— 不製造一個指向不存在 commit 的欄位。
        if (gitCommitId != null) {
            auditDetail.put("gitCommit", gitCommitId.substring(0, 7));
        }
        auditPublisher.publish(new AuditEvent(OperationType.BPMN_DEPLOY.name(),
                operator, null, null, auditDetail));

        return Map.of("deploymentId", deployment.getId(), "name", deployment.getName(),
                "lint", lintResult);
    }
}
