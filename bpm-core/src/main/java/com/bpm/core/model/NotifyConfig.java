package com.bpm.core.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;

@Entity
@Table(name = "bpm_notify_config", uniqueConstraints =
    @UniqueConstraint(columnNames = {"processDefinitionKey", "eventType", "channel"}))
public class NotifyConfig {

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
    private String processDefinitionKey;

    @Column(nullable = false, length = 30)
    private String eventType; // task_assigned | task_claimed | task_urged | task_timeout | process_returned | process_rejected | process_completed

    @Column(nullable = false, length = 30)
    private String channel;

    private String templateId;

    @Column(nullable = false)
    private Boolean enabled = true;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getProcessDefinitionKey() { return processDefinitionKey; }
    public void setProcessDefinitionKey(String processDefinitionKey) { this.processDefinitionKey = processDefinitionKey; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
    public String getTemplateId() { return templateId; }
    public void setTemplateId(String templateId) { this.templateId = templateId; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
}
