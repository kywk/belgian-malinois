package com.bpm.core.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;

@Entity
@Table(name = "bpm_process_variable_spec",
        uniqueConstraints = @UniqueConstraint(columnNames = {"processDefinitionKey", "variableName"}))
public class ProcessVariableSpec {

    // ⚠️ READ_ONLY：id 絕不可由請求 body 指定。
    // Spring Data 的 save() 以 id == null 判斷 isNew —— id 非 null 時會走
    // em.merge() 變成 UPDATE，於是「新增」請求可以覆寫任意資料列
    // （security-audit P0-4）。READ_ONLY 讓 Jackson 在反序列化時忽略此欄位，
    // 但序列化（回應）仍會帶上它。
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false, length = 100)
    private String processDefinitionKey;

    @Column(nullable = false, length = 100)
    private String variableName;

    @Column(nullable = false, length = 20)
    private String variableType; // string | number | date | boolean

    @Column(nullable = false)
    private Boolean required = false;

    private String description;
    private String example;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getProcessDefinitionKey() { return processDefinitionKey; }
    public void setProcessDefinitionKey(String processDefinitionKey) { this.processDefinitionKey = processDefinitionKey; }
    public String getVariableName() { return variableName; }
    public void setVariableName(String variableName) { this.variableName = variableName; }
    public String getVariableType() { return variableType; }
    public void setVariableType(String variableType) { this.variableType = variableType; }
    public Boolean getRequired() { return required; }
    public void setRequired(Boolean required) { this.required = required; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getExample() { return example; }
    public void setExample(String example) { this.example = example; }
}
