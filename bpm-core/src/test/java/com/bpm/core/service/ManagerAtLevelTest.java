package com.bpm.core.service;

import com.bpm.core.support.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 指派運算式<b>永遠不可以求值成 null</b>（security-audit P2-6）。
 *
 * <h2>這個不變式為什麼是核心</h2>
 *
 * <p>設計器原本產生 {@code ${orgService.getManagerChain(initiator, 2)[1]}}。
 * JUEL 對越界索引<b>不拋例外，而是回 null</b>（已實測）。於是任務建立成功、
 * 案件存在，但 {@code assignee=null} 且沒有候選群組 ——
 * 這個任務對所有人都不可見，沒有人收到通知也沒有人能認領。
 *
 * <p>在簽核系統裡，「靜默產生看不見的任務」比「啟動失敗」嚴重得多：
 * 後者使用者立刻重試或回報，前者要等到有人問「我的假單怎麼還沒過」，
 * 而那時候已經沒有任何線索指向那次 BPMN 編輯。
 *
 * <p>所以 {@code getManagerAtLevel} 的契約是：回傳一個真實的人，或拋例外。
 * 沒有第三種結果。
 */
class ManagerAtLevelTest extends IntegrationTestBase {

    @Autowired private OrgService orgService;

    // fixture 的組織關係：
    //   user001 → mgr001 → dir001（dir001 是鏈頂）

    @Test
    @DisplayName("階數在鏈長之內時回對應的人")
    void returnsRequestedLevel() {
        assertThat(orgService.getManagerAtLevel("user001", 1)).isEqualTo("mgr001");
        assertThat(orgService.getManagerAtLevel("user001", 2)).isEqualTo("dir001");
    }

    @Test
    @DisplayName("鏈比要求的短時回最高階，而不是 null")
    void fallsBackToTopOfChainInsteadOfNull() {
        // mgr001 的鏈是 [dir001]，長度 1。要求第 2 階時，
        // 舊寫法 getManagerChain(mgr001, 2)[1] 會回 null → 看不見的任務。
        assertThat(orgService.getManagerAtLevel("mgr001", 2))
                .as("必須回最高階主管，絕不可回 null")
                .isEqualTo("dir001");

        // 要求遠超過鏈長也一樣。
        assertThat(orgService.getManagerAtLevel("mgr001", 5)).isEqualTo("dir001");
    }

    @Test
    @DisplayName("完全沒有主管時拋例外，不得回 null")
    void throwsWhenThereIsNoManagerAtAll() {
        // dir001 位於鏈頂，鏈長 0 —— 沒有任何說得過去的答案。
        // 拋例外讓錯誤停在流程啟動那一刻，而不是變成一個沒人看得到的任務。
        assertThatThrownBy(() -> orgService.getManagerAtLevel("dir001", 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dir001")
                .hasMessageContaining("沒有任何主管");
    }

    @Test
    @DisplayName("階數小於 1 是設定錯誤，必須拋例外")
    void rejectsNonPositiveLevel() {
        // 設計器的 NumberFieldEntry 沒有下限，使用者可以填 0 或負數。
        assertThatThrownBy(() -> orgService.getManagerAtLevel("user001", 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> orgService.getManagerAtLevel("user001", -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("絕不回傳本人 —— 那會變成自我簽核")
    void neverReturnsTheUserThemselves() {
        // getManagerChain 的 clean() 已排除本人（P1-9），這裡確認 getManagerAtLevel
        // 的 fallback 路徑沒有把它繞掉 —— 回最高階時若鏈裡只剩本人就會出事。
        for (String user : java.util.List.of("user001", "user002", "mgr001", "mgr002")) {
            for (int level = 1; level <= 5; level++) {
                assertThat(orgService.getManagerAtLevel(user, level))
                        .as("%s 的第 %d 階主管不可以是自己", user, level)
                        .isNotEqualTo(user);
            }
        }
    }
}
