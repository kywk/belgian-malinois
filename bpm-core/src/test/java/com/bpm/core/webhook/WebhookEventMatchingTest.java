package com.bpm.core.webhook;

import org.flowable.task.service.delegate.DelegateTask;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * webhook 設定與事件的對應規則（#67）。
 *
 * <p>這段規則<b>刻意</b>獨立於整合測試。因為它是最容易寫錯、又最不容易
 * 從端到端結果看出來的地方：整套規則只有三條推導，但每一條寫錯都只是
 * 「多投遞」或「少投遞」，流程照跑、沒有錯誤。
 *
 * <p>其中 {@code reject} 那條特別值得獨立測：Flowable <b>沒有</b> reject 這個
 * task event，它是從「complete 事件 + {@code rejected == true}」推導出來的。
 * 最自然的錯法是「complete 事件都當 reject」，而那會把<b>退回</b>
 * （{@code approved=false}、{@code rejected} 為 null）也通知成駁回 ——
 * 對接的外部系統會把一張「要補件」的單當成「被拒」。
 */
class WebhookEventMatchingTest {

    private static DelegateTask taskWith(Object rejected) {
        DelegateTask task = mock(DelegateTask.class);
        when(task.getVariable("rejected")).thenReturn(rejected);
        return task;
    }

    @Nested
    @DisplayName("同名事件")
    class SameName {

        @Test
        @DisplayName("設定什麼事件就只對應什麼事件")
        void exactMatch() {
            for (String event : new String[]{"create", "complete", "delete", "assignment", "timeout"}) {
                assertThat(WebhookTaskListener.matches(event, event, taskWith(null)))
                        .as(event + " 應對應自己").isTrue();
            }
        }

        @Test
        @DisplayName("設定的 create 不得被 complete 觸發（反之亦然）")
        void doesNotCrossFire() {
            assertThat(WebhookTaskListener.matches("create", "complete", taskWith(null))).isFalse();
            assertThat(WebhookTaskListener.matches("complete", "create", taskWith(null))).isFalse();
            assertThat(WebhookTaskListener.matches("timeout", "create", taskWith(null))).isFalse();
        }

        @Test
        @DisplayName("大小寫與前後空白不影響判斷（設計器輸入不經過驗證）")
        void caseAndWhitespaceInsensitive() {
            assertThat(WebhookTaskListener.matches("Complete", "complete", taskWith(null))).isTrue();
            assertThat(WebhookTaskListener.matches("  complete  ", "complete", taskWith(null))).isTrue();
            // 兩邊都做 case-insensitive。引擎實際給的一律是小寫，
            // 對引擎那邊也寬容只是讓這條規則好推理，沒有副作用 ——
            // 真正要防的是「設定的 event 拼錯」，那個兩邊寬容都擋不住，
            // 而正確的行為是「不投遞」（有測試釘住）。
            assertThat(WebhookTaskListener.matches("complete", "COMPLETE", taskWith(null))).isTrue();
        }

        @Test
        @DisplayName("拼錯的事件名不會誤投遞給別的事件")
        void typoDoesNotMatchAnything() {
            // 這是「寬容比對」真正的風險面。create 拼成 creat 不得落到 complete 上，
            // 也不得落到 all 上 —— 設定錯了就是不投遞，讓使用者在畫面上看到
            // 「設定了卻沒收到」，比猜一個事件寄出去好。
            assertThat(WebhookTaskListener.matches("creat", "create", taskWith(null))).isFalse();
            assertThat(WebhookTaskListener.matches("complet", "complete", taskWith(null))).isFalse();
            assertThat(WebhookTaskListener.matches("rejected", "complete", taskWith(true))).isFalse();
        }
    }

    @Nested
    @DisplayName("all")
    class All {

        @Test
        @DisplayName("all 對每個事件都成立")
        void allMatchesEverything() {
            for (String event : new String[]{"create", "complete", "delete", "assignment", "timeout"}) {
                assertThat(WebhookTaskListener.matches("all", event, taskWith(null)))
                        .as("all 應對應 " + event).isTrue();
            }
            assertThat(WebhookTaskListener.matches("ALL", "create", taskWith(null))).isTrue();
        }
    }

    @Nested
    @DisplayName("reject 是推導出來的，不是 Flowable 的事件")
    class DerivedReject {

        @Test
        @DisplayName("complete 且 rejected=true 才算駁回")
        void rejectRequiresTheVariable() {
            assertThat(WebhookTaskListener.matches("reject", "complete", taskWith(true))).isTrue();
        }

        @Test
        @DisplayName("退回（approved=false、rejected 沒有值）不得被當成駁回")
        void returnedIsNotRejected() {
            // ⚠️ 這是本測試組最重要的一條。退回與駁回在 spec 裡是兩件事
            // （CLAUDE.md 必讀事實 3），接錯的後果是外部系統收到錯誤的最終狀態。
            assertThat(WebhookTaskListener.matches("reject", "complete", taskWith(null))).isFalse();
            assertThat(WebhookTaskListener.matches("reject", "complete", taskWith(false))).isFalse();
        }

        @Test
        @DisplayName("rejected=true 但事件不是 complete 時不得成立")
        void rejectOnlyDerivesFromComplete() {
            // 例如任務被刪除時剛好還留著 rejected 變數 —— 那不是駁回。
            assertThat(WebhookTaskListener.matches("reject", "create", taskWith(true))).isFalse();
            assertThat(WebhookTaskListener.matches("reject", "delete", taskWith(true))).isFalse();
        }

        @Test
        @DisplayName("complete 設定不得被 rejected=true 觸發成別的東西")
        void completeSettingStillMatchesEveryCompletion() {
            // complete 與 reject 是兩個並存的設定，不是互斥的。
            assertThat(WebhookTaskListener.matches("complete", "complete", taskWith(true))).isTrue();
            assertThat(WebhookTaskListener.matches("complete", "complete", taskWith(null))).isTrue();
        }
    }

    @Nested
    @DisplayName("不成立的設定")
    class Invalid {

        @Test
        @DisplayName("空白或 null 的 event 不得對應任何事件")
        void blankEventNeverMatches() {
            for (String event : new String[]{null, "", "   "}) {
                for (String actual : new String[]{"create", "complete"}) {
                    assertThat(WebhookTaskListener.matches(event, actual, taskWith(null)))
                            .as("event=" + event + " 對 " + actual).isFalse();
                }
            }
        }

        @Test
        @DisplayName("engine 端事件為 null 時不得對應（不是「全部對應」）")
        void nullEngineEventMatchesNothing() {
            // 刻意的：null 事件的語意是「我不知道發生了什麼」，
            // 而「不知道」時送一則 event 欄位錯誤的訊息，
            // 比不送更難讓接收端處理。
            assertThat(WebhookTaskListener.matches("all", null, taskWith(null))).isFalse();
            assertThat(WebhookTaskListener.matches("create", null, taskWith(null))).isFalse();
        }
    }
}
