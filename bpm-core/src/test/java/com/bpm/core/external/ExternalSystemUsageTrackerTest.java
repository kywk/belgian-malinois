package com.bpm.core.external;

import com.bpm.core.model.ExternalSystem;
import com.bpm.core.repository.ExternalSystemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link ExternalSystemUsageTracker} 的單元測試：lastUsedAt 寫入節流。
 *
 * <h2>這組測試在防什麼缺陷</h2>
 *
 * <p>節流有兩個方向：
 * <ul>
 *   <li><b>沒節到</b>：每個認證請求還是寫一筆 DB —— R-09 記載的效能債原狀。</li>
 *   <li><b>節過頭</b>：把「金鑰透明升級」這種必須落地的變更也一起跳過
 *       （forceWrite 存在的理由）；或第一次使用時不寫，讓管理頁永遠顯示
 *       「從未使用」。</li>
 * </ul>
 *
 * <p>時間語意用「把 lastUsedAt 撥到過去」製造，不 sleep。
 */
class ExternalSystemUsageTrackerTest {

    private ExternalSystemRepository repo;
    private ExternalSystemUsageTracker tracker;
    private ExternalSystem sys;

    @BeforeEach
    void setUp() {
        repo = mock(ExternalSystemRepository.class);
        tracker = new ExternalSystemUsageTracker(repo);
        sys = new ExternalSystem();
        sys.setSystemId("erp");
    }

    @Test
    @DisplayName("從未使用 → 第一次寫入 lastUsedAt 並存檔")
    void firstTouchWrites() {
        tracker.touch(sys);

        assertThat(sys.getLastUsedAt()).isNotNull();
        verify(repo, times(1)).save(sys);
    }

    @Test
    @DisplayName("距上次寫入不到一分鐘 → 不再寫 DB，也不改值")
    void recentTouchSkipsWrite() {
        tracker.touch(sys);
        Instant first = sys.getLastUsedAt();

        tracker.touch(sys);

        assertThat(sys.getLastUsedAt()).as("值維持第一次寫入的瞬間").isEqualTo(first);
        verify(repo, times(1)).save(sys);
    }

    @Test
    @DisplayName("距上次寫入超過一分鐘 → 再次寫入")
    void staleTouchWritesAgain() {
        tracker.touch(sys);
        Instant first = sys.getLastUsedAt();
        sys.setLastUsedAt(first.minus(ExternalSystemUsageTracker.MIN_WRITE_INTERVAL)
                .minusSeconds(1));

        tracker.touch(sys);

        assertThat(sys.getLastUsedAt()).isAfterOrEqualTo(first);
        verify(repo, times(2)).save(sys);
    }

    @Test
    @DisplayName("forceWrite：即使距上次寫入很近，也必須存檔（金鑰升級不能只留在記憶體）")
    void forceWriteAlwaysPersists() {
        tracker.touch(sys);
        Instant first = sys.getLastUsedAt();

        tracker.touch(sys, true);

        assertThat(sys.getLastUsedAt())
                .as("forceWrite 的目的是保存其他欄位變更，不應順手更新 lastUsedAt")
                .isEqualTo(first);
        verify(repo, times(2)).save(sys);
    }
}
