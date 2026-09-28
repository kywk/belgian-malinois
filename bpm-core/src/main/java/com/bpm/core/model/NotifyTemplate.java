package com.bpm.core.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;

@Entity
@Table(name = "bpm_notify_template")
public class NotifyTemplate {

    // ⚠️ READ_ONLY：id 絕不可由請求 body 指定。
    // Spring Data 的 save() 以 id == null 判斷 isNew —— id 非 null 時會走
    // em.merge() 變成 UPDATE，於是「新增」請求可以覆寫任意資料列
    // （security-audit P0-4）。READ_ONLY 讓 Jackson 在反序列化時忽略此欄位，
    // 但序列化（回應）仍會帶上它。
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, length = 30)
    private String channel; // email | teams | line_works | system_webhook

    private String subjectTemplate;

    @Column(columnDefinition = "NVARCHAR(MAX)")
    private String bodyTemplate;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
    public String getSubjectTemplate() { return subjectTemplate; }
    public void setSubjectTemplate(String subjectTemplate) { this.subjectTemplate = subjectTemplate; }
    public String getBodyTemplate() { return bodyTemplate; }
    public void setBodyTemplate(String bodyTemplate) { this.bodyTemplate = bodyTemplate; }
}
