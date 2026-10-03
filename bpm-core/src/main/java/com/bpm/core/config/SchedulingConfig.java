package com.bpm.core.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 啟用 Spring 的 {@code @Scheduled}（#41 異常操作偵測是 repo 第一個排程工作）。
 *
 * <p>獨立成一個 {@code @Configuration} 而不是加在 {@code BpmCoreApplication}：
 * 「這個應用程式有哪些東西會在背景自己跑」值得有一個明確、可搜尋的入口。
 * 加在啟動類別上時，它只會淹沒在自動配置的註解之間。
 *
 * <p>⚠️ 開啟排程是全域效果：日後任何 {@code @Scheduled} 都會生效，
 * 而且預設只有單一執行緒。新增排程工作時請確認它不會阻塞其他排程
 * （本偵測器只做短查詢；若日後出現長工作，應另外設定 TaskScheduler）。
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
