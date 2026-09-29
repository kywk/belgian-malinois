package com.bpm.core.repository;

import com.bpm.core.model.NotifyConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface NotifyConfigRepository extends JpaRepository<NotifyConfig, String> {
    List<NotifyConfig> findByProcessDefinitionKeyAndEventTypeAndEnabledTrue(String processDefinitionKey, String eventType);
    List<NotifyConfig> findByProcessDefinitionKeyOrderByEventType(String processDefinitionKey);

    /**
     * 引用指定模板的設定。
     *
     * <p>刪除模板前必須查 —— 否則會留下指向不存在模板的設定，
     * 而那正是 P1-13 的失敗模式（EmailConsumer 取不到模板 → retry 後進 DLQ
     * → 通知永久遺失）。P1-13 只在 create/update 擋住了錯誤的 templateId，
     * 刪除這條路徑會把同一個洞重新打開。
     */
    List<NotifyConfig> findByTemplateId(String templateId);
}
