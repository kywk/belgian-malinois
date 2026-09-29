package com.bpm.core.controller;

import com.bpm.core.security.CallerId;
import com.bpm.core.audit.AuditEventPublisher;
import com.bpm.core.dto.AuditEvent;
import com.bpm.core.audit.model.OperationType;
import com.bpm.core.lint.BpmnLintService;
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
    private final Path bpmnDir;

    public DeploymentController(RepositoryService repositoryService, BpmnLintService lintService,
                                AuditEventPublisher auditPublisher,
                                @Value("${bpm.bpmn-definitions-dir:./bpmn-definitions}") String bpmnDir) {
        this.repositoryService = repositoryService;
        this.lintService = lintService;
        this.auditPublisher = auditPublisher;
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

    @PostMapping
    public Object deploy(@RequestParam("file") MultipartFile file,
                         @RequestParam(defaultValue = "") String name,
                         @CallerId
                         String operatorId) throws IOException {
        String xml = new String(file.getBytes());
        String deployName = name.isEmpty() ? file.getOriginalFilename() : name;

        // 1. Lint validation
        var lintResult = lintService.lint(xml);
        if (!lintResult.valid()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "BPMN Lint 驗證失敗: " + lintResult.errors().size() + " 個錯誤");
        }

        // 2. Save XML to bpmn-definitions/
        //
        // ⚠️ deployName 來自 @RequestParam 或 client 的原始檔名，兩者皆不可信。
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

        // 3. Deploy to Flowable
        Deployment deployment = repositoryService.createDeployment()
                .name(deployName)
                .addString(deployName != null ? deployName : "process.bpmn20.xml", xml)
                .deploy();

        // ⚠️ operatorId 原本寫死 null（security-audit P2-4）。
        //
        // 部署 BPMN 是這個平台上最有後果的單一操作：它決定所有後續案件的
        // 簽核路徑要送給誰。而那筆稽核紀錄是<b>匿名</b>的 ——
        // 事後可以看到「某時有人部署了流程定義」，但查不出是誰。
        //
        // 而且部署是覆寫式的：新版本一上線，之後啟動的案件全部照新規則走。
        // 這正是最需要問責的地方。
        auditPublisher.publish(new AuditEvent(OperationType.BPMN_DEPLOY.name(),
                operatorId != null && !operatorId.isBlank() ? operatorId : "unknown",
                null, null,
                Map.of("deploymentId", deployment.getId(),
                        "name", deployment.getName(),
                        // 部署的內容摘要：事後可據此確認當時上線的是哪一份 XML，
                        // 而不必依賴 deploymentId 仍存在。
                        "xmlSha256", sha256(xml),
                        "xmlBytes", xml.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)));

        return Map.of("deploymentId", deployment.getId(), "name", deployment.getName(),
                "lint", lintResult);
    }
}
