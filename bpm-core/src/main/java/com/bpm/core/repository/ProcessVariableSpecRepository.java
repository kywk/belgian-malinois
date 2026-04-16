package com.bpm.core.repository;

import com.bpm.core.model.ProcessVariableSpec;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ProcessVariableSpecRepository extends JpaRepository<ProcessVariableSpec, String> {
    List<ProcessVariableSpec> findByProcessDefinitionKeyOrderByVariableName(String processDefinitionKey);
    void deleteByProcessDefinitionKey(String processDefinitionKey);
}
