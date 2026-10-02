package com.bpm.core.external;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

public final class ApiKeyUtil {

    private ApiKeyUtil() {}

    public static String generateKey() {
        return generate("sk-");
    }

    /**
     * 回呼密鑰的明文（工項 #21）。與 {@link #generateKey()} 同一套產生器、
     * 同一個熵來源，只有前綴不同 —— 前綴是為了讓「這是哪一種密鑰」在
     * 日誌與設定檔裡一眼可辨，而不是兩把長得一樣的字串。
     */
    public static String generateCallbackSecret() {
        return generate("cs-");
    }

    private static String generate(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "");
    }

    public static String hash(String plainKey) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256")
                    .digest(plainKey.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
