package com.bpm.core.notify;

import tools.jackson.databind.ObjectMapper;

/**
 * Teams Incoming Webhook 訊息的 payload 形狀（#32／#44 共用，唯一一份）。
 *
 * <h2>形狀：只送 {@code {"text": ...}}</h2>
 *
 * <p>Teams Incoming Webhook 最單純、官方也接受的形狀是
 * {@code {"text": "..."}}；完整的 MessageCard 需要 {@code @type}／
 * {@code @context} 等欄位，而本專案刻意<b>不引入新依賴</b>、也不為單行
 * 通知長出一套卡片模型。{@code title} 因此不是獨立欄位，而是接進
 * {@code text}（Teams 的 {@code text} 支援 markdown）。JSON 由
 * {@link ObjectMapper} 產生，不手寫跳脫 —— 內容來自模板／流程變數／BPMN
 * 欄位，可能含引號／反斜線／換行。
 *
 * <h2>為什麼抽出來</h2>
 *
 * <p>#44 的 {@link TeamsNotifyDelegate} 與 #32 的 {@link EmailConsumer}
 * 是兩條獨立路徑，但「送到 Teams 的東西長什麼樣」是同一條契約。原本
 * delegate 內有一個 private {@code payload()}，若 consumer 再抄一份，
 * 下次調整形狀（例如改送 MessageCard）就會只改一邊 —— 本 repo 反覆記載的
 * 缺陷成因（同一條規則有兩套形狀）。delegate 的 {@code payload()} 保留為
 * 測試入口，實作直接轉呼叫這裡。
 */
final class TeamsWebhookPayload {

    private TeamsWebhookPayload() {
    }

    /**
     * 組出 payload：單一欄位 {@code text}；{@code title} 非空白時以換行
     * 接在 {@code message} 前（{@code title + "\n" + message}）。
     *
     * <p>{@code title} 空白／null 與 #44 同一語意：只有訊息本文，不留
     * 前導換行。{@code message} 為 null 時視為空字串（呼叫端應自行擋掉
     * 空訊息；這裡防的是序列化 NPE）。
     */
    static String json(ObjectMapper objectMapper, String title, String message) {
        String text = message == null ? "" : message;
        if (title != null && !title.isBlank()) {
            text = title + "\n" + text;
        }
        return objectMapper.createObjectNode().put("text", text).toString();
    }
}
