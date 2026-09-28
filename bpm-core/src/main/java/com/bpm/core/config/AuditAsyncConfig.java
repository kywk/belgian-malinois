package com.bpm.core.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 稽核寫入專用的執行器。
 *
 * <h2>為什麼不能用 Boot 的預設執行器</h2>
 *
 * <p>{@code @EnableAsync} 未指定 executor 時，{@code @Async} 會用 Boot 預設的
 * {@code applicationTaskExecutor}，而它的<b>佇列容量是
 * {@code Integer.MAX_VALUE}</b>（security-audit P1-14）。
 *
 * <p>後果：稽核 DB 變慢時，事件會無上限地堆在 heap 裡。
 * 沒有任何背壓、沒有任何可觀測指標，而且一旦重啟或 OOM，
 * <b>堆積的稽核事件全部消失</b> —— 而它們代表的業務操作早已回 200 完成。
 * 對簽核系統來說，這是最糟的失效方式：資料不見了，而且沒人知道。
 *
 * <h2>選 CallerRunsPolicy 的理由</h2>
 *
 * <p>佇列滿了之後，{@code CallerRunsPolicy} 讓<b>呼叫端執行緒自己去做</b>
 * 這次寫入 —— 也就是稽核寫入退化成同步，簽核請求會變慢。
 *
 * <p>這是刻意的取捨：對稽核而言「慢」遠優於「靜默遺失」。
 * 變慢會被使用者與監控看見；遺失不會。
 * 其他策略都更糟：{@code AbortPolicy} 會拋例外但被
 * {@code AuditEventPublisher} 的 catch 吞掉（等於遺失）、
 * {@code DiscardPolicy} 直接丟棄（明確的遺失）。
 */
@Configuration
public class AuditAsyncConfig {

    /** 稽核執行器的 bean 名稱。{@code @Async} 以此名稱指定。 */
    public static final String AUDIT_EXECUTOR = "auditTaskExecutor";

    @Bean(AUDIT_EXECUTOR)
    public ThreadPoolTaskExecutor auditTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("audit-");
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        // 有界佇列是本設定的重點。500 是一個刻意偏小的值：
        // 正常情況下稽核寫入是毫秒級，佇列不該累積；
        // 一旦累積到 500，代表 DB 已經有問題，此時我們要的是背壓而不是緩衝。
        executor.setQueueCapacity(500);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // 關機時把佇列中的稽核事件寫完再退出，避免正常重啟造成遺失。
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
