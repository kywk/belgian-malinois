package com.bpm.form.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "bpm_form_data", indexes = {
        @Index(name = "idx_form_data_process", columnList = "processInstanceId")
})
public class FormData {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String formDefinitionId;

    @Column(nullable = false)
    private String processInstanceId;

    private String taskId;

    @Column(columnDefinition = "NVARCHAR(MAX)", nullable = false)
    private String dataJson;

    private String submittedBy;

    @Column(updatable = false)
    private Instant submittedAt;

    @PrePersist
    void onCreate() {
        if (submittedAt == null) submittedAt = Instant.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getFormDefinitionId() { return formDefinitionId; }
    public void setFormDefinitionId(String formDefinitionId) { this.formDefinitionId = formDefinitionId; }
    public String getProcessInstanceId() { return processInstanceId; }
    public void setProcessInstanceId(String processInstanceId) { this.processInstanceId = processInstanceId; }
    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    public String getDataJson() { return dataJson; }
    public void setDataJson(String dataJson) { this.dataJson = dataJson; }
    public String getSubmittedBy() { return submittedBy; }
    public void setSubmittedBy(String submittedBy) { this.submittedBy = submittedBy; }
    public Instant getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(Instant submittedAt) { this.submittedAt = submittedAt; }
}
