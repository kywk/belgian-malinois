package com.bpm.core.repository;

import com.bpm.core.model.DocumentRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface DocumentRequestRepository extends JpaRepository<DocumentRequest, String> {
    List<DocumentRequest> findByCreatedByOrderByCreatedAtDesc(String createdBy);

    @Query("SELECT COUNT(d) FROM DocumentRequest d WHERE d.documentNumber LIKE :prefix%")
    int countByPrefix(String prefix);
}
