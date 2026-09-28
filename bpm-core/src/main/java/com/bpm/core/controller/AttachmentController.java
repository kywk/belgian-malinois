package com.bpm.core.controller;

import com.bpm.core.model.FileAttachment;
import com.bpm.core.repository.FileAttachmentRepository;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RuntimeService;
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
    private final Path uploadDir;

    public AttachmentController(FileAttachmentRepository repo,
                                RuntimeService runtimeService,
                                HistoryService historyService,
                                @Value("${bpm.upload.dir:./uploads}") String uploadDir) {
        this.repo = repo;
        this.runtimeService = runtimeService;
        this.historyService = historyService;
        // normalize + toAbsolutePath 一次做掉，後續的 startsWith 圍堵檢查
        // 才有意義（相對路徑之間比 prefix 會有誤判）。
        this.uploadDir = Path.of(uploadDir).toAbsolutePath().normalize();
    }

    @PostMapping
    public FileAttachment upload(@RequestParam("file") MultipartFile file,
                                  @RequestParam String processInstanceId,
                                  @RequestParam(required = false) String taskId,
                                  @RequestParam(required = false) String uploadedBy) throws IOException {
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
        att.setUploadedBy(uploadedBy);
        return repo.save(att);
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
    public List<FileAttachment> list(@RequestParam String processInstanceId) {
        return repo.findByProcessInstanceIdOrderByUploadedAtDesc(processInstanceId);
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<Resource> download(@PathVariable String id) {
        FileAttachment att = repo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        // 同樣圍堵下載路徑。filePath 來自 DB，而在此修復之前寫入的資料列
        // 可能指向 uploadDir 之外的任意路徑 —— 若不檢查，一筆被污染的
        // 資料列就能把這個端點變成任意檔案讀取。
        Path stored = Path.of(att.getFilePath()).toAbsolutePath().normalize();
        if (!stored.startsWith(uploadDir)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }

        Resource resource = new FileSystemResource(stored);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + att.getFileName() + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(resource);
    }
}
