package com.bpm.core.repository;

import com.bpm.core.model.NotifyConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface NotifyConfigRepository extends JpaRepository<NotifyConfig, String> {
    List<NotifyConfig> findByProcessDefinitionKeyAndEventTypeAndEnabledTrue(String processDefinitionKey, String eventType);
    List<NotifyConfig> findByProcessDefinitionKeyOrderByEventType(String processDefinitionKey);
}
