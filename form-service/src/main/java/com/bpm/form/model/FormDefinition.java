package com.bpm.form.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "bpm_form_definition",
        uniqueConstraints = @UniqueConstraint(columnNames = {"formKey", "version"}))
public class FormDefinition {

    // ⚠️ READ_ONLY：id 絕不可由請求 body 指定。
    // Spring Data 的 save() 以 id == null 判斷 isNew —— id 非 null 時會走
    // em.merge() 變成 UPDATE，於是「新增」請求可以覆寫任意資料列
    // （security-audit P0-4）。READ_ONLY 讓 Jackson 在反序列化時忽略此欄位，
    // 但序列化（回應）仍會帶上它。
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    // columnDefinition 不可省略：預設的 String 對映會建成 VARCHAR，
    // 而三個 DB 的定序是 SQL_Latin1_General_CP1_CI_AS，VARCHAR 存不了中文
    // （寫入時靜默變成問號）。見 db/migration/V2__form_name_nvarchar.sql。
    @Column(nullable = false, columnDefinition = "NVARCHAR(255)")
    private String name;

    @Column(nullable = false, length = 100)
    private String formKey;

    @Column(nullable = false)
    private Integer version = 1;

    @Column(columnDefinition = "NVARCHAR(MAX)", nullable = false)
    private String schemaJson;

    @Column(nullable = false, length = 20)
    private String status = "draft"; // draft | published | archived

    private String createdBy;

    @Column(updatable = false)
    private Instant createdAt;

    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getFormKey() { return formKey; }
    public void setFormKey(String formKey) { this.formKey = formKey; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public String getSchemaJson() { return schemaJson; }
    public void setSchemaJson(String schemaJson) { this.schemaJson = schemaJson; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
