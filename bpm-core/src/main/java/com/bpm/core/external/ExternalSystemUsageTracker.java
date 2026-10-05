package com.bpm.core.external;

import com.bpm.core.model.ExternalSystem;
import com.bpm.core.repository.ExternalSystemRepository;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * {@code lastUsedAt} 的寫入節流（R-09 遺留的效能債，R-25 順手收）。
 *
 * <h2>改動前：每個請求一筆 DB write</h2>
 *
 * <p>{@code ExternalApiAuthFilter} 與 {@code CallbackAuthFilter} 在每次
 * 通過認證的請求都 {@code sys.setLastUsedAt(Instant.now()); repo.save(sys);}。
 * 外部系統的輪詢與批次呼叫會把資料庫的寫入量綁在請求量上，而
 * {@code lastUsedAt} 的用途只是管理頁顯示「最近有沒有在用」——
 * 秒級精確度買不到任何決策，卻要付全量寫入。
 *
 * <h2>語意改變：精確值 → 分鐘級</h2>
 *
 * <p>現在同一系統的寫入最多 {@link #MIN_WRITE_INTERVAL} 一次。也就是說
 * {@code lastUsedAt} 是「最近一次寫入窗口內有使用」而非「最後一個請求的瞬間」。
 * 這是刻意的取捨：對運維的判讀（「這把金鑰還在用嗎」）等價，
 * 但把寫入量壓到最多每系統每分鐘一筆。
 *
 * <p>⚠️ 呼叫端若有其他欄位變更需要持久化（例如 legacy 金鑰透明升級），
 * 傳 {@code forceWrite=true} —— 否則「距離上次寫入不到一分鐘」會讓那些
 * 變更一起被跳過。
 */
@Component
public class ExternalSystemUsageTracker {

    /** 同一系統兩次 {@code lastUsedAt} 寫入之間的最短間隔。 */
    static final Duration MIN_WRITE_INTERVAL = Duration.ofMinutes(1);

    private final ExternalSystemRepository repo;

    public ExternalSystemUsageTracker(ExternalSystemRepository repo) {
        this.repo = repo;
    }

    /**
     * 記錄一次使用。距離上次寫入超過 {@link #MIN_WRITE_INTERVAL}（或從未寫入）
     * 才落 DB。
     */
    public void touch(ExternalSystem sys) {
        touch(sys, false);
    }

    /**
     * @param forceWrite true = 不論距離上次寫入多久都存檔（實體上還有其他變更，
     *                   例如 API key 的格式升級必須落地）
     */
    public void touch(ExternalSystem sys, boolean forceWrite) {
        Instant now = Instant.now();
        Instant last = sys.getLastUsedAt();
        boolean stale = last == null
                || Duration.between(last, now).compareTo(MIN_WRITE_INTERVAL) >= 0;
        if (stale) {
            sys.setLastUsedAt(now);
        }
        if (stale || forceWrite) {
            repo.save(sys);
        }
    }
}
