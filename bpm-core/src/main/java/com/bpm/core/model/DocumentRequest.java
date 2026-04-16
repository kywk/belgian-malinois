package com.bpm.core.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "bpm_document_request")
public class DocumentRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false, unique = true, length = 30)
    private String documentNumber;

    private String processInstanceId;

    @Column(nullable = false)
    private String title;

    @Column(length = 20)
    private String urgencyLevel = "normal"; // normal | urgent | critical

    private String category;
    private String createdBy;

    @Column(updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() { if (createdAt == null) createdAt = Instant.now(); }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getDocumentNumber() { return documentNumber; }
    public void setDocumentNumber(String documentNumber) { this.documentNumber = documentNumber; }
    public String getProcessInstanceId() { return processInstanceId; }
    public void setProcessInstanceId(String processInstanceId) { this.processInstanceId = processInstanceId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getUrgencyLevel() { return urgencyLevel; }
    public void setUrgencyLevel(String urgencyLevel) { this.urgencyLevel = urgencyLevel; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
