package com.bpm.core.support;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * 測試用的 uni-form DSL 產生器。
 *
 * <h2>為什麼要有這個</h2>
 *
 * <p>uni-form 重構後 {@code schemaJson} 必須是 DSL（{@code $uniform:"1.0"}），
 * 而「DSL 要塞進 HTTP body 的 JSON 字串欄位」需要一層轉義。
 * 每個測試自己寫一次引號地獄，會同時出現兩種失效：測試壞在轉義而不是
 * 斷言，以及有人用「先跳過驗證」的方式讓它變綠。
 *
 * <p>這裡只提供<b>最小合法</b>的形狀（{@code root} 內幾個 field 節點）：
 * {@code compileStatic} 與 DSL schema 都過。需要更複雜的 DSL（props、
 * condition）時請直接在該測試裡寫明文，不要讓這個工具變成半個 DSL 產生器。
 */
public final class UniformTestDsl {

    private UniformTestDsl() {
    }

    /**
     * 最小合法 DSL（一個 textarea 欄位），可讀的明文。
     *
     * @param key DSL 的 {@code key}（表單識別；與 formKey 對齊是慣例）
     */
    public static String minimal(String key) {
        return """
                {"$uniform":"1.0","key":"%s","title":{"zh-TW":"測試表單"},
                 "requires":{"core":"^1.0"},
                 "root":[{"id":"n_note","type":"textarea","key":"note","label":"備註"}]}
                """.formatted(key);
    }

    /**
     * 以指定的欄位 key 清單組一份合法 DSL。
     *
     * <p>全部用 {@code text} 型別（值語意最單純）；需要別的型別時自己寫明文。
     */
    public static String withKeys(String key, String... fieldKeys) {
        String fields = Arrays.stream(fieldKeys)
                .map(fieldKey -> "{\"id\":\"n_" + fieldKey + "\",\"type\":\"text\",\"key\":\""
                        + fieldKey + "\",\"label\":\"" + fieldKey + "\"}")
                .collect(Collectors.joining(","));
        return "{\"$uniform\":\"1.0\",\"key\":\"" + key + "\","
                + "\"title\":{\"zh-TW\":\"測試表單\"},"
                + "\"requires\":{\"core\":\"^1.0\"},"
                + "\"root\":[" + fields + "]}";
    }

    /**
     * 把明文 DSL 轉義成「可嵌在 JSON 字串裡」的形式。
     *
     * <p>典型用法：
     * {@code "{\"formKey\":\"k\",\"schemaJson\":\"" + UniformTestDsl.embedded(dsl) + "\"}"}
     */
    public static String embedded(String raw) {
        return raw.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "");
    }

    /** {@code minimal(key)} 的轉義版。 */
    public static String minimalEmbedded(String key) {
        return embedded(minimal(key));
    }
}
