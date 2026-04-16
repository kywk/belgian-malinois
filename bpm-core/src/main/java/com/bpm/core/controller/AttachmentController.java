package com.bpm.core.controller;

import com.bpm.core.model.FileAttachment;
import com.bpm.core.repository.FileAttachmentRepository;
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

@RestController
@RequestMapping("/api/attachments")
public class AttachmentController {

    private final FileAttachmentRepository repo;
    private final Path uploadDir;

    public AttachmentController(FileAttachmentRepository repo,
                                @Value("${bpm.upload.dir:./uploads}") String uploadDir) {
        this.repo = repo;
        this.uploadDir = Path.of(uploadDir);
    }

    @PostMapping
    public FileAttachment upload(@RequestParam("file") MultipartFile file,
                                  @RequestParam String processInstanceId,
                                  @RequestParam(required = false) String taskId,
                                  @RequestParam(required = false) String uploadedBy) throws IOException {
        Path dir = uploadDir.resolve(processInstanceId);
        Files.createDirectories(dir);
        String storedName = UUID.randomUUID() + "_" + file.getOriginalFilename();
        Path target = dir.resolve(storedName);
        file.transferTo(target);

        FileAttachment att = new FileAttachment();
        att.setProcessInstanceId(processInstanceId);
        att.setTaskId(taskId);
        att.setFileName(file.getOriginalFilename());
        att.setFilePath(target.toString());
        att.setFileSize(file.getSize());
        att.setContentType(file.getContentType());
        att.setUploadedBy(uploadedBy);
        return repo.save(att);
    }

    @GetMapping
    public List<FileAttachment> list(@RequestParam String processInstanceId) {
        return repo.findByProcessInstanceIdOrderByUploadedAtDesc(processInstanceId);
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<Resource> download(@PathVariable String id) {
        FileAttachment att = repo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        Resource resource = new FileSystemResource(att.getFilePath());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + att.getFileName() + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(resource);
    }
}
