package com.bpm.core.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * #7 遺留：撤回 {@code reason} 的長度上限（{@code ProcessController.cancelReasonOf}）。
 *
 * <h2>為什麼要有這一條</h2>
 *
 * <p>{@code reason} 原樣進 {@code ACT_HI_PROCINST.DELETE_REASON_}
 * （{@code nvarchar(4000)}）。沒有限制時，超過欄位容量的輸入在
 * {@code deleteProcessInstance} 才炸成 500 —— 使用者錯誤被回報成
 * 伺服器故障。這裡直接釘住「上限是多少、訊息說了什麼、邊界怎麼算」，
 * 不必為了驗一句訊息啟動整個 Spring context（HTTP 層的行為與
 * 零副作用由 {@code ProcessCancelReasonLimitTest} 驗）。
 *
 * <h2>負向控制組（2026-10-03 實測）</h2>
 *
 * <p>把 {@code ProcessController} 的長度檢查整段拿掉（其餘不動）：
 * {@link #overLimitIsRejectedWithLimitInMessage} 紅（不再拋例外），
 * 其餘四條綠 —— 它們測的是非字串、省略、trim 等其他規則，
 * 不依賴這個上限。
 */
class ProcessControllerCancelReasonTest {

    @Test
    @DisplayName("trim 後 1000 字元（上限）可接受，且原樣回傳")
    void maxLengthIsAccepted() {
        String reason = "長".repeat(1000);

        assertThat(ProcessController.cancelReasonOf(Map.of("reason", reason)))
                .as("上限本身是合法的，不是『大於等於就擋』")
                .isEqualTo(reason);
    }

    @Test
    @DisplayName("1001 字元 → 400，訊息同時寫明實際長度與上限")
    void overLimitIsRejectedWithLimitInMessage() {
        String reason = "x".repeat(1001);

        assertThatThrownBy(() -> ProcessController.cancelReasonOf(Map.of("reason", reason)))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode())
                            .as("輸入太長是使用者錯誤（400），不是引擎層的 500")
                            .isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getReason())
                            .as("訊息必須說得出上限，呼叫端才知道要裁到多少")
                            .contains("1000")
                            .contains("1001");
                });
    }

    @Test
    @DisplayName("前後空白不計入長度：trim 後剛好 1000 字元仍可接受")
    void surroundingWhitespaceDoesNotCountTowardLimit() {
        String reason = "  " + "y".repeat(1000) + "  ";

        assertThat(ProcessController.cancelReasonOf(Map.of("reason", reason)))
                .as("空白不算內容；若先判原始長度，前後各一個空白就會誤擋合法輸入")
                .isEqualTo("y".repeat(1000));
    }

    @Test
    @DisplayName("純空白（即使遠超上限）走『省略』路徑，不是超長錯誤")
    void blankWhitespaceFallsBackToDefault() {
        assertThat(ProcessController.cancelReasonOf(Map.of("reason", " ".repeat(5000))))
                .as("空白不可能表達撤回原因，與既有『省略或空白 → 固定值』同一取向")
                .isEqualTo("applicant-cancel");
    }

    @Test
    @DisplayName("既有行為不變：非字串 → 400；null body／缺 reason → 固定值")
    void nonStringAndMissingStayAsBefore() {
        assertThatThrownBy(() -> ProcessController.cancelReasonOf(Map.of("reason", 123)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));

        assertThat(ProcessController.cancelReasonOf(null)).isEqualTo("applicant-cancel");
        assertThat(ProcessController.cancelReasonOf(Map.of())).isEqualTo("applicant-cancel");
    }
}
