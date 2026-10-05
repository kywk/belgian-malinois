package com.bpm.core.external;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

/**
 * API key／回呼密鑰的產生與「舊版」雜湊。
 *
 * <h2>雜湊格式的世代（R-25）</h2>
 *
 * <p>資料庫的 {@code api_key} 欄位有兩種格式，以有無 {@link #V2_PREFIX} 區分：
 *
 * <ul>
 *   <li><b>無前綴（legacy）</b>：單輪 SHA-256 小寫 hex，{@link #hash} 的輸出。
 *       這是本類別唯一不變的部分 —— 它同時是<b>舊資料的驗證方式</b>與
 *       {@code ApiKeyUtilTest} 釘住的資料格式契約，不可改動。</li>
 *   <li><b>{@code v2:} 前綴</b>：HMAC-SHA256(server secret, key) 小寫 hex。
 *       實作在 {@link ApiKeyHasher}（server secret 不在 static utility 裡）。
 *       同樣具備「拿明文重算再比對」的可查詢性，但資料庫單獨外洩時
 *       無法離線比對出跨系統重用的金鑰；沒有 server secret 也無法偽造。</li>
 * </ul>
 *
 * <p>驗證端必須<b>雙讀</b>（有前綴走 v2、無前綴走 legacy），否則既有 DB 裡
 * 所有金鑰會在部署當天全部失效。前綴就是讓「該走哪條路」可辨識的依據 ——
 * 不靠長度或內容猜測。
 */
public final class ApiKeyUtil {

    /**
     * v2 雜湊的前綴。
     *
     * <p>⚠️ 這個字串是<b>資料格式契約</b>：一旦有金鑰以此格式落庫就不能再改，
     * 改了等於全部驗證失敗。放在這裡的理由與 {@link #hash} 相同 ——
     * 格式知識只能有一份，驗證端與測試都指向它。
     */
    public static final String V2_PREFIX = "v2:";

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

    /**
     * 舊版雜湊：單輪 SHA-256 小寫 hex（無 salt）。
     *
     * <p>⚠️ 這是<b>向後相容的驗證路徑</b>，不是新金鑰的儲存格式。
     * 新金鑰一律走 {@link ApiKeyHasher#hash}（{@code v2:}）；
     * 既有資料列在第一次通過驗證時由 {@code ExternalApiAuthFilter} 透明升級。
     */
    public static String hash(String plainKey) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256")
                    .digest(plainKey.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** 這個落庫值是不是 v2（HMAC）格式。null 與無前綴都是 legacy。 */
    public static boolean isV2(String storedHash) {
        return storedHash != null && storedHash.startsWith(V2_PREFIX);
    }
}
