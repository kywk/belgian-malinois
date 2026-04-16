package com.bpm.core.repository;

import com.bpm.core.model.FileAttachment;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface FileAttachmentRepository extends JpaRepository<FileAttachment, String> {
    List<FileAttachment> findByProcessInstanceIdOrderByUploadedAtDesc(String processInstanceId);
}
