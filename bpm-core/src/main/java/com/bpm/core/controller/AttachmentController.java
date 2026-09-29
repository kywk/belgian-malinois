package com.bpm.core.controller;

import org.springframework.transaction.annotation.Transactional;
import com.bpm.core.security.CallerId;
import com.bpm.core.audit.AuditEventPublisher;
import com.bpm.core.audit.model.OperationType;
import com.bpm.core.dto.AuditEvent;
import com.bpm.core.model.FileAttachment;
import com.bpm.core.repository.FileAttachmentRepository;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/attachments")
public class AttachmentController {

    /**
     * 合法的 Flowable 流程實例 id 形狀。
     *
     * <p>Flowable 的 id 是 UUID 或數字序號，兩者都落在此集合內。
     * 以白名單而非黑名單驗證：黑名單（例如「拒絕 ..」）必然漏掉編碼變體，
     * 白名單則連 `/`、`\`、`%2e` 一併排除。
     */
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9-]{1,64}");

    private final FileAttachmentRepository repo;
    private final RuntimeService runtimeService;
    private final HistoryService historyService;
    private final TaskService taskService;
    private final AuditEventPublisher auditPublisher;
    private final Path uploadDir;

    public AttachmentController(FileAttachmentRepository repo,
                                RuntimeService runtimeService,
                                HistoryService historyService,
                                TaskService taskService,
                                AuditEventPublisher auditPublisher,
                                @Value("${bpm.upload.dir:./uploads}") String uploadDir) {
        this.repo = repo;
        this.runtimeService = runtimeService;
        this.historyService = historyService;
        this.taskService = taskService;
        this.auditPublisher = auditPublisher;
        // normalize + toAbsolutePath 一次做掉，後續的 startsWith 圍堵檢查
        // 才有意義（相對路徑之間比 prefix 會有誤判）。
        this.uploadDir = Path.of(uploadDir).toAbsolutePath().normalize();
    }

    @PostMapping
    @Transactional("primaryTransactionManager")
    public Map<String, Object> upload(@RequestParam("file") MultipartFile file,
                                      @RequestParam String processInstanceId,
                                      @RequestParam(required = false) String taskId,
                                      @RequestParam(required = false) String uploadedBy,
                                      @CallerId
                                      String callerId) throws IOException {
        // ── 1. processInstanceId 白名單驗證 ──────────────────────────
        // 改動前這個值零驗證就進 resolve()，processInstanceId=../../../../etc/cron.d
        // 即可在容器內任意位置建立目錄並寫檔。
        if (processInstanceId == null || !SAFE_ID.matcher(processInstanceId).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "processInstanceId 格式不合法");
        }

        // ── 2. 流程實例必須真的存在 ─────────────────────────────────
        // 光是格式合法還不夠：否則任何人都能用合法形狀的亂數 id
        // 在 uploadDir 下堆出無限多個目錄（且無人清理）。
        if (!processInstanceExists(processInstanceId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "流程實例不存在: " + processInstanceId);
        }

        // ── 物件層授權（security-audit P1-6）─────────────────────────
        String operator = firstNonBlank(callerId, uploadedBy);
        requireParticipant(processInstanceId, operator);

        Path dir = uploadDir.resolve(processInstanceId).normalize();

        // ── 3. 儲存檔名一律棄用 client 的值 ─────────────────────────
        // originalFilename 由 client 完全控制，且 Servlet multipart 不剝除路徑
        // （"uuid_" + "/../../../x" 正規化後仍可逐層上跳）。
        // 因此檔案系統上只用 UUID；原始檔名僅存 DB 供顯示與下載時的
        // Content-Disposition 使用。
        String storedName = UUID.randomUUID().toString();
        Path target = dir.resolve(storedName).normalize();

        // ── 4. 落地前的圍堵斷言 ─────────────────────────────────────
        // 前三道都通過了仍要斷言一次：這是唯一與「實際寫入位置」直接相關的
        // 檢查，也是未來有人改動上面任一步時的最後一道防線。
        if (!target.startsWith(uploadDir)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "附件路徑不合法");
        }

        Files.createDirectories(dir);
        file.transferTo(target);

        FileAttachment att = new FileAttachment();
        att.setProcessInstanceId(processInstanceId);
        att.setTaskId(taskId);
        att.setFileName(sanitizeDisplayName(file.getOriginalFilename()));
        att.setFilePath(target.toString());
        att.setFileSize(file.getSize());
        att.setContentType(file.getContentType());
        att.setUploadedBy(firstNonBlank(uploadedBy, operator));
        FileAttachment saved = repo.save(att);

        auditPublisher.publish(new AuditEvent(OperationType.FORM_SUBMIT.name(), operator,
                processInstanceId, taskId,
                Map.of("attachmentId", saved.getId(),
                       "fileName", saved.getFileName(),
                       "fileSize", saved.getFileSize() == null ? 0L : saved.getFileSize())));

        return toResponse(saved);
    }

    /**
     * 要求呼叫者是該案件的關係人，否則 404。
     *
     * <p>審查指出「即使補上認證，程式碼裡也<b>沒有可掛授權判斷的位置</b>」
     * （security-audit P1-6）—— 這個方法就是那個位置。
     *
     * <p>關係人的定義：案件發起人、或在此案件中持有／曾持有任務的人
     * （含候選人）。這涵蓋申請人、各關卡簽核人與加簽人。
     *
     * <p>回 404 而非 403：403 會確認「這個附件／案件存在」，
     * 對可枚舉的 id 來說等於把枚舉管道留著。
     *
     * <p>呼叫者身分來自 {@code @CallerId}，也就是已認證的 principal
     * （JWT 的 sub，或閘道認證過的身分）。R-01 已完成 ——
     * 這個方法先前的註解說「身分仍可自報，因此不是完整的安全邊界」，
     * 那個限制已經不存在了。
     *
     * <p>⚠️ 仍然沒有 admin／auditor 的旁路。現在有伺服器端的角色模型了
     * （{@code AuthorityResolver}），所以要加的話是在此處判斷
     * {@code ROLE_ADMIN}；但「管理員能不能看任何案件的附件」是權責政策，
     * 不是技術缺口，所以留給明確決策。
     */
    private void requireParticipant(String processInstanceId, String userId) {
        if (userId == null || userId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        if (!isParticipant(processInstanceId, userId)) {
            // 稽核拒絕：有人嘗試存取無關案件的附件，這件事本身值得留痕。
            auditPublisher.publishDetached(new AuditEvent(OperationType.DATA_ACCESS.name(), userId,
                    processInstanceId, null,
                    Map.of("denied", true, "reason", "not a participant")));
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }

    private boolean isParticipant(String processInstanceId, String userId) {
        // 1. 發起人（執行中看 runtime 變數，已結案看歷史變數）
        if (userId.equals(initiatorOf(processInstanceId))) return true;

        // 2. 目前持有任務或為候選人
        boolean hasRuntimeTask = taskService.createTaskQuery()
                .processInstanceId(processInstanceId)
                .taskInvolvedUser(userId).count() > 0;
        if (hasRuntimeTask) return true;

        // 3. 曾經處理過此案件的任務（含已完成的關卡、加簽）
        return historyService.createHistoricTaskInstanceQuery()
                .processInstanceId(processInstanceId)
                .taskInvolvedUser(userId).count() > 0;
    }

    private String initiatorOf(String processInstanceId) {
        // 先確認實例仍在執行，而不是呼叫 getVariable 再 catch 例外。
        //
        // upload() 現在有 @Transactional（稽核 fail-closed，P1-14）。Flowable 命令在
        // 外層交易中拋例外時，會把整個外層交易標成 rollback-only —— 就算這裡 catch
        // 住了，commit 時仍會變成 UnexpectedRollbackException。已結案案件的附件上傳
        // 因此會全部失敗。
        if (runtimeService.createProcessInstanceQuery()
                .processInstanceId(processInstanceId).singleResult() != null) {
            Object v = runtimeService.getVariable(processInstanceId, "initiator");
            if (v != null) return v.toString();
        }
        // 已結案的實例在 runtimeService 查不到，改看歷史變數
        var hv = historyService.createHistoricVariableInstanceQuery()
                .processInstanceId(processInstanceId).variableName("initiator").list();
        return hv.isEmpty() || hv.get(0).getValue() == null
                ? null : hv.get(0).getValue().toString();
    }

    /**
     * 對外的附件表示法。
     *
     * <p>刻意<b>不含 filePath</b>：那是容器內的實體路徑，對呼叫端沒有任何用途，
     * 只會洩漏部署結構（security-audit P1-6）。
     */
    private static Map<String, Object> toResponse(FileAttachment att) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id", att.getId());
        m.put("processInstanceId", att.getProcessInstanceId());
        m.put("taskId", att.getTaskId());
        m.put("fileName", att.getFileName());
        m.put("fileSize", att.getFileSize());
        m.put("contentType", att.getContentType());
        m.put("uploadedBy", att.getUploadedBy());
        m.put("uploadedAt", att.getUploadedAt());
        return m;
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        if (b != null && !b.isBlank()) return b;
        return null;
    }

    private boolean processInstanceExists(String processInstanceId) {
        boolean running = runtimeService.createProcessInstanceQuery()
                .processInstanceId(processInstanceId).count() > 0;
        if (running) return true;
        // 已結案的案件仍應可以補附件／查附件，因此也接受歷史實例。
        return historyService.createHistoricProcessInstanceQuery()
                .processInstanceId(processInstanceId).count() > 0;
    }

    /**
     * 原始檔名只用於顯示，但仍須去掉路徑成分 ——
     * 它會出現在下載的 Content-Disposition 標頭裡。
     */
    private static String sanitizeDisplayName(String original) {
        if (original == null || original.isBlank()) return "attachment";
        String name = original.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        name = name.replace("\r", "").replace("\n", "").replace("\"", "");
        return name.isBlank() ? "attachment" : name;
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam String processInstanceId,
                                          @CallerId
                                          String callerId) {
        requireParticipant(processInstanceId, callerId);
        return repo.findByProcessInstanceIdOrderByUploadedAtDesc(processInstanceId)
                .stream().map(AttachmentController::toResponse).toList();
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<Resource> download(@PathVariable String id,
                                             @CallerId
                                             String callerId) {
        FileAttachment att = repo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        // Flowable 的 processInstanceId 可被枚舉，而改動前這個端點只做
        // findById → 任何人都能列舉並下載全公司案件的附件。
        requireParticipant(att.getProcessInstanceId(), callerId);

        // 同樣圍堵下載路徑。filePath 來自 DB，而在此修復之前寫入的資料列
        // 可能指向 uploadDir 之外的任意路徑 —— 若不檢查，一筆被污染的
        // 資料列就能把這個端點變成任意檔案讀取。
        Path stored = Path.of(att.getFilePath()).toAbsolutePath().normalize();
        if (!stored.startsWith(uploadDir)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }

        // 誰下載了薪資單必須留下紀錄（改動前三個端點全無稽核）。
        auditPublisher.publish(new AuditEvent(OperationType.DATA_ACCESS.name(), callerId,
                att.getProcessInstanceId(), att.getTaskId(),
                Map.of("attachmentId", att.getId(), "fileName", att.getFileName())));

        Resource resource = new FileSystemResource(stored);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + att.getFileName() + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(resource);
    }
}
