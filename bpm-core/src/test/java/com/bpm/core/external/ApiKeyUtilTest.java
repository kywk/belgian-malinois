package com.bpm.core.external;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ApiKeyUtil} 的單元測試：API key 與回呼密鑰的產生與雜湊。
 *
 * <h2>這組測試在防什麼缺陷</h2>
 *
 * <p>明文密鑰只給呼叫端一次，資料庫存的是 SHA-256 雜湊 —— 所以
 * {@code hash} 的演算法與大小寫是<b>資料格式契約</b>：改了它，
 * 所有既有系統的 key 都會突然驗不過。前綴則讓日誌與設定檔裡
 * 「這是哪一種密鑰」一眼可辨（{@code sk-} vs {@code cs-}），
 * 兩把長得一樣的字串在事故現場是很貴的混淆。
 */
class ApiKeyUtilTest {

    @Test
    @DisplayName("API key 前綴 sk-、長度 3＋32，且每次產生不同")
    void apiKeyShape() {
        String key = ApiKeyUtil.generateKey();

        assertThat(key).startsWith("sk-").hasSize(35);
        assertThat(ApiKeyUtil.generateKey()).isNotEqualTo(key);
    }

    @Test
    @DisplayName("回呼密鑰前綴 cs-，與 API key 可辨識")
    void callbackSecretShape() {
        String secret = ApiKeyUtil.generateCallbackSecret();

        assertThat(secret).startsWith("cs-").hasSize(35);
        assertThat(ApiKeyUtil.generateCallbackSecret()).isNotEqualTo(secret);
    }

    @Test
    @DisplayName("hash 是 SHA-256 小寫 hex（已知向量）")
    void hashIsSha256Hex() {
        assertThat(ApiKeyUtil.hash("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    @DisplayName("hash 決定性、64 字元、對輸入敏感")
    void hashIsDeterministicAndSensitive() {
        String h = ApiKeyUtil.hash("sk-abc");
        assertThat(ApiKeyUtil.hash("sk-abc")).isEqualTo(h);
        assertThat(ApiKeyUtil.hash("sk-abd")).isNotEqualTo(h);
        assertThat(h).hasSize(64);
    }

    @Test
    @DisplayName("UTF-8 編碼：非 ASCII 密鑰也要能穩定雜湊")
    void utf8Input() throws Exception {
        String expected = java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256")
                        .digest("密鑰".getBytes(StandardCharsets.UTF_8)));
        assertThat(ApiKeyUtil.hash("密鑰")).isEqualTo(expected);
    }
}
