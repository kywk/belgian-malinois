package com.bpm.core.external;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

public final class ApiKeyUtil {

    private ApiKeyUtil() {}

    public static String generateKey() {
        return "sk-" + UUID.randomUUID().toString().replace("-", "");
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
