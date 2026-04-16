package com.bpm.core.model;

import jakarta.persistence.*;

@Entity
@Table(name = "bpm_notify_config", uniqueConstraints =
    @UniqueConstraint(columnNames = {"processDefinitionKey", "eventType", "channel"}))
public class NotifyConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String processDefinitionKey;

    @Column(nullable = false, length = 30)
    private String eventType; // task_assigned | process_returned | process_rejected | process_completed | task_timeout

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
