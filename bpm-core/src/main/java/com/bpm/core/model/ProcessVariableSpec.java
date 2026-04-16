package com.bpm.core.model;

import jakarta.persistence.*;

@Entity
@Table(name = "bpm_process_variable_spec",
        uniqueConstraints = @UniqueConstraint(columnNames = {"processDefinitionKey", "variableName"}))
public class ProcessVariableSpec {

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
