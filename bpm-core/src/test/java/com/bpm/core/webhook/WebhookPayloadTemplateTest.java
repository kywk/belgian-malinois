package com.bpm.core.webhook;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * webhook 自訂 body 模板的渲染規則（#28）—— <b>純單元測試</b>。
 *
 * <h2>為什麼渲染必須獨立測</h2>
 *
 * <p>端到端測試（{@code WebhookDeliveryWiringTest}）證明「自訂 body 真的被
 * 投遞、簽章對它有效」，但證明不了邊界：值裡有引號／反斜線／換行時是否
 * 產生合法 JSON、null 是否變成空字串、未知 placeholder 是否原樣保留。
 * 這些錯了都不會有錯誤訊息 —— 接收端只會收到一個格式壞掉或語意不同的
 * body。所以這裡逐條釘住 {@link WebhookPayloadTemplate#render} 本身。
 *
 * <h2>安全紅線的測試在這裡的形狀</h2>
 *
 * <p>{@code flowVariableIsNotResolvable} 不是「黑名單擋住 salary」，
 * 而是「render 只查傳進來的 map，map 裡沒有 salary 就代換不了」——
 * 這正是 P2-1 紅線在 #28 的實作方式：結構上拿不到，而不是靠過濾。
 */
class WebhookPayloadTemplateTest {

    @Nested
    @DisplayName("代換與轉義")
    class Rendering {

        @Test
        @DisplayName("已知欄位被代換成值；使用者自己寫的外引號保留")
        void replacesKnownFields() {
            String template = "{\"task\":\"{{taskName}}\",\"pid\":\"{{processInstanceId}}\"}";

            assertThat(WebhookPayloadTemplate.render(template, Map.of(
                    "taskName", "審核關卡",
                    "processInstanceId", "pi-1")))
                    .isEqualTo("{\"task\":\"審核關卡\",\"pid\":\"pi-1\"}");
        }

        @Test
        @DisplayName("值含雙引號／反斜線／換行 → 轉義成合法 JSON 字串內容")
        void escapesJsonStringContent() {
            Map<String, Object> payload = new HashMap<>();
            payload.put("taskName", "審核\"關卡\\最終\r\n版本");

            String body = WebhookPayloadTemplate.render("{\"t\":\"{{taskName}}\"}", payload);

            // 外引號是使用者寫的，值只提供「引號內的安全內容」。
            assertThat(body).isEqualTo("{\"t\":\"審核\\\"關卡\\\\最終\\r\\n版本\"}");
        }

        @Test
        @DisplayName("null → 空字串（鍵仍留著，接收端 schema 固定）")
        void nullBecomesEmptyString() {
            Map<String, Object> payload = new HashMap<>();
            payload.put("businessKey", null);

            assertThat(WebhookPayloadTemplate.render("{\"bk\":\"{{businessKey}}\"}", payload))
                    .isEqualTo("{\"bk\":\"\"}");
        }

        @Test
        @DisplayName("數字用 JSON 表示：放引號內外都成立")
        void numbersAreJsonRepresentations() {
            Map<String, Object> payload = Map.of("overdueHours", 3L);

            assertThat(WebhookPayloadTemplate.render("{\"h\":{{overdueHours}}}", payload))
                    .isEqualTo("{\"h\":3}");
            assertThat(WebhookPayloadTemplate.render("{\"h\":\"{{overdueHours}}\"}", payload))
                    .isEqualTo("{\"h\":\"3\"}");
        }

        @Test
        @DisplayName("未知 placeholder 原樣保留，不是清成空字串")
        void unknownPlaceholdersAreKept() {
            // 清成空字串的話，接收端只會看到一個空欄位，無從得知是模板寫錯。
            // 原樣保留則讓 {{typo}} 在接收端顯而易見。
            assertThat(WebhookPayloadTemplate.render(
                    "{\"x\":\"{{nope}}\",\"y\":\"{{taskName}}\"}",
                    Map.of("taskName", "T")))
                    .isEqualTo("{\"x\":\"{{nope}}\",\"y\":\"T\"}");
        }

        @Test
        @DisplayName("placeholder 內允許空白：{{ taskName }} 等同 {{taskName}}")
        void trimsPlaceholderName() {
            assertThat(WebhookPayloadTemplate.render("{{ taskName }}", Map.of("taskName", "T")))
                    .isEqualTo("T");
        }

        @Test
        @DisplayName("沒有 placeholder 的模板逐字元不變")
        void templateWithoutPlaceholdersIsUntouched() {
            String template = "{\"fixed\":true}";
            assertThat(WebhookPayloadTemplate.render(template, Map.of("taskName", "T")))
                    .isEqualTo(template);
        }

        @Test
        @DisplayName("render(null) → null（呼叫端據此不設 __webhookBody）")
        void nullTemplateReturnsNull() {
            assertThat(WebhookPayloadTemplate.render(null, Map.of())).isNull();
        }
    }

    @Nested
    @DisplayName("P2-1 紅線：模板拿不到流程變數")
    class RedLines {

        @Test
        @DisplayName("payload map 裡沒有的名字（流程變數 salary）不會被代換 —— 原樣保留")
        void flowVariableIsNotResolvable() {
            // 這裡刻意傳一個「像流程變數」的名字，證明 render 的行為只取決於
            // 傳入的 map。實作端（兩個 listener）傳的是白名單欄位的 payload，
            // 流程變數從來不在裡面 —— 不是被過濾，是拿不到。
            assertThat(WebhookPayloadTemplate.render("{{salary}}", Map.of("taskName", "T")))
                    .isEqualTo("{{salary}}");
        }
    }

    @Nested
    @DisplayName("knownFields（lint 用的聯集）")
    class KnownFields {

        @Test
        @DisplayName("兩層的欄位都在清單內；流程變數不在")
        void containsBothLayersAndNoVariables() {
            assertThat(WebhookPayloadTemplate.KNOWN_FIELDS)
                    // 節點層
                    .contains("taskId", "taskName", "assignee", "dueDate", "operatorId",
                            "action", "rejectReason", "overdueHours")
                    // 流程層
                    .contains("result")
                    // 兩層共用
                    .contains("event", "timestamp", "processInstanceId",
                            "processDefinitionKey", "businessKey")
                    .doesNotContain("variables", "allVariables", "salary");
        }

        @Test
        @DisplayName("unknownFields 只回未知的、依出現順序去重")
        void unknownFieldsAreDeduplicatedInOrder() {
            assertThat(WebhookPayloadTemplate.unknownFields(
                    "{{a}} {{taskName}} {{b}} {{a}} {{ c }}"))
                    .containsExactly("a", "b", "c");
            assertThat(WebhookPayloadTemplate.unknownFields(null)).isEmpty();
            assertThat(WebhookPayloadTemplate.unknownFields("no placeholders")).isEmpty();
        }
    }
}
