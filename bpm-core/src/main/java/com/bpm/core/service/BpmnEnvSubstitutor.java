package com.bpm.core.service;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * BPMN XML 的部署期環境變數替換（backlog #53，spec §12.3）。
 *
 * <p>BPMN 是<b>跨環境共用</b>的產物：同一份 XML 在 dev 指向測試群組、
 * 在 prod 指向正式群組。spec §12.3 的作法是在 XML 裡寫佔位符
 * （{@code flowable:candidateGroups="${ENV_FINANCE_GROUP}"}），部署時以
 * 環境設定 {@code bpmn.variables.ENV_FINANCE_GROUP} 的值替換後才送進引擎。
 *
 * <h2>政策（本類別是唯一實作，改動前先讀完）</h2>
 *
 * <ol>
 *   <li><b>只認 {@code ${ENV_...}} 命名空間</b>（{@code ENV_[A-Z0-9_]+}）。
 *       其他 {@code ${...}} 原樣不動 —— 它們是 Flowable 的 EL 運算式，
 *       替換掉會改變語意。豁免的邊界必須與
 *       {@code BpmnLintService} 的規則 i 豁免<b>逐字相同</b>：lint 放行的
 *       集合若比這裡大，就會部署一份執行期求值 {@code ${ENV_X}} 的 BPMN。</li>
 *   <li><b>值來源是 Spring 設定</b>（{@code bpmn.variables.<NAME>}），透過
 *       {@link Environment} 查詢。刻意<b>不</b>直接讀 {@code System.getenv}：
 *       那會繞過 Spring 的設定體系（profile、測試覆蓋、relaxed binding）。
 *       環境變數覆蓋走 Spring 自己的機制
 *       （{@code BPMN_VARIABLES_ENV_FINANCE_GROUP}，見
 *       {@code BpmnEnvSubstitutorTest} 的實測）。</li>
 *   <li><b>fail-closed</b>：查不到值或值只有空白 → 拋
 *       {@link BpmnEnvSubstitutionException}，由部署端轉成 400。
 *       空白也算未設定：{@code BPMN_VARIABLES_ENV_X=} 與「沒設」在維運上
 *       是同一件事，而空字串替換進 candidateGroups 會製造沒有人看得到的任務
 *       （與 lint 規則 k 防的是同一種靜默卡死）。</li>
 *   <li><b>原始 XML 才是落地與版控的內容</b>：寫檔與 Git commit 用呼叫端的
 *       原始字串；{@link Resolution#xml()} 只在記憶體用於引擎部署與稽核 hash
 *       —— 環境差異（含正式群組名）不進版控。</li>
 *   <li><b>值必須 XML 轉義</b>：值含 {@code &} 或 {@code <} 會讓整份 XML
 *       解析失敗；不轉義等於讓設定值能注入元素。五個特殊字元一律轉成實體，
 *       屬性與文字節點都安全。</li>
 * </ol>
 *
 * <h2>刻意不做的兩件事</h2>
 *
 * <ul>
 *   <li><b>不遞迴替換</b>：替換值若又含 {@code ${ENV_*}}，這裡直接擋下
 *       （{@link BpmnEnvSubstitutionException}）。單次替換會留下未展開的
 *       佔位符，而 lint 把它視為合法字面 → 錯誤延後到執行期；遞迴則要處理
 *       循環（A→B→A），維運排查成本遠高於需求本身。Spring 的
 *       {@code Environment} 已支援 {@code ${其他設定鍵}} 形式的間接，
 *       真的有鏈結需求用那個，不在此處發明第二套。
 *       <br>實測補充：{@code Environment.getProperty} 本身就會解析值裡的
 *       {@code ${...}}，無法解析時<b>直接拋例外</b>
 *       （{@code ignoreUnresolvableNestedPlaceholders} 預設 false）——
 *       這裡把它收斂成同一種 400 例外；殘留檢查只在「環境設了忽略未解析
 *       佔位符」之類的非預設設定下才會走到，留著是防禦性的一道。</li>
 *   <li><b>不碰 CDATA</b>：轉義後的值在 CDATA 區段內會原樣保留實體字元
 *       （CDATA 不做實體展開）。BPMN 實務上不會在 CDATA 裡放環境佔位符；
 *       這是已知限制，不是沒想到。</li>
 * </ul>
 */
@Service
public class BpmnEnvSubstitutor {

    /**
     * 唯一被認得的佔位符：{@code ${ENV_[A-Z0-9_]+}}。
     *
     * <p>名稱必須<b>全大寫</b>且至少一個字元：{@code ${ENV_}}（空名）與
     * {@code ${env_x}}（小寫）都不匹配，原樣留給 lint 擋下。pattern 同時是
     * {@code BpmnLintService} 豁免規則的孿生體，兩邊必須一起改。
     */
    static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{(ENV_[A-Z0-9_]+)\\}");

    /** 設定鍵前綴（spec 樣張：{@code bpmn.variables.ENV_FINANCE_GROUP}）。 */
    static final String KEY_PREFIX = "bpmn.variables.";

    private final Environment environment;

    public BpmnEnvSubstitutor(Environment environment) {
        this.environment = environment;
    }

    /**
     * 替換結果。
     *
     * @param xml       替換後的 XML；沒有任何佔位符時是<b>傳入的同一個物件</b>
     *                  （逐位元不變，呼叫端可據此走「零行為差異」的路徑）
     * @param usedNames 實際用到的變數名，依首次出現順序、去重。
     *                  稽核只記名字清單，不記值
     */
    public record Resolution(String xml, List<String> usedNames) {

        public Resolution {
            usedNames = List.copyOf(usedNames);
        }

        /** 是否有任何替換發生。決定稽核要不要加 resolved 欄位、要不要多 lint 一份。 */
        public boolean hasSubstitutions() {
            return !usedNames.isEmpty();
        }
    }

    /**
     * 把 XML 裡的 {@code ${ENV_*}} 換成 {@code bpmn.variables.*} 的值。
     *
     * @throws BpmnEnvSubstitutionException 值未設定、只有空白、或值本身含
     *                                      未展開的 {@code ${ENV_*}}
     */
    public Resolution resolve(String xml) {
        // 快路徑：沒有任何 ENV 佔位符就回傳原物件。部署絕大多數是這個分支，
        // 而「無佔位符時逐位元不變」是與 #61 版控行為相容的關鍵。
        if (xml == null || !xml.contains("${ENV_")) {
            return new Resolution(xml, List.of());
        }
        Matcher matcher = PLACEHOLDER.matcher(xml);
        // 有 "${ENV_" 但不是合法佔位符（例如 ${ENV_}）：同樣原樣回傳，
        // 由 lint 的規則 i 擋下，不在這裡製造第二套驗證。
        if (!matcher.find()) {
            return new Resolution(xml, List.of());
        }

        Map<String, String> values = new LinkedHashMap<>();
        StringBuilder out = new StringBuilder(xml.length() + 64);
        do {
            String name = matcher.group(1);
            String value = values.computeIfAbsent(name, this::requireValue);
            // appendReplacement 把 $ 與 \ 當群組參照 —— 值可能含它們，
            // 不 quote 會拋 IllegalArgumentException 或插入錯內容。
            matcher.appendReplacement(out, Matcher.quoteReplacement(escapeXml(value)));
        } while (matcher.find());
        matcher.appendTail(out);

        return new Resolution(out.toString(), new ArrayList<>(values.keySet()));
    }

    /**
     * 查一個變數的值；查不到、空白、或含巢狀佔位符一律擋下。
     *
     * <p>同一個名字在一次 resolve 內只查一次（呼叫端用 computeIfAbsent），
     * 所以訊息與值不會因為重複查詢而有不一致的窗口。
     */
    private String requireValue(String name) {
        String key = KEY_PREFIX + name;
        String value;
        try {
            value = environment.getProperty(key);
        } catch (IllegalArgumentException e) {
            // Spring 會把設定值裡的巢狀 ${...} 一併解析，無法解析時 getProperty
            // 直接拋（ignoreUnresolvableNestedPlaceholders 預設 false）。
            // 這是設定問題，收斂成 400 的替換例外，而不是裸的 500。
            throw new BpmnEnvSubstitutionException(name, key,
                    "設定值無法解析（可能含未設定的巢狀 ${...}）：" + e.getMessage(), e);
        }
        if (value == null) {
            throw new BpmnEnvSubstitutionException(name, key,
                    "設定中找不到值；請在部署環境提供 " + key);
        }
        if (value.isBlank()) {
            throw new BpmnEnvSubstitutionException(name, key,
                    "設定值是空白；空白與未設定同義（空字串替換會製造沒有人看得到的任務）");
        }
        // 巢狀佔位符：單次替換不會展開它，而 lint 把 ENV_* 視為合法字面，
        // 放行等於部署一份執行期才爆的 BPMN。Spring 的 ${設定鍵} 間接
        // 在 getProperty 時已解析完畢，這裡剩下的是「值指向另一個 ENV_」。
        Matcher nested = PLACEHOLDER.matcher(value);
        if (nested.find()) {
            throw new BpmnEnvSubstitutionException(name, key,
                    "設定值含未展開的巢狀佔位符 " + nested.group()
                            + "；本功能不做遞迴替換（避免循環），請直接提供最終值");
        }
        return value;
    }

    /**
     * XML 轉義：{@code & < > " '} 一律換成實體。
     *
     * <p>五個全轉是為了<b>同一個值可以出現在屬性與文字節點</b>：
     * 文字節點只需要 {@code &} 與 {@code <}，但 {@code &quot;}／{@code &apos;}
     * 在文字節點是合法的字元參照（解析後等於原字元），多轉不影響語意。
     * {@code >} 也轉，順帶消滅 {@code ]]>} 在 CDATA 外的邊界形狀。
     *
     * <p>不轉 {@code $}：它不是 XML 特殊字元，而值本來就允許含 EL 運算式
     * （{@code ${orgService.…}}）—— 那由部署端對 resolved XML 再 lint 一次把關。
     */
    static String escapeXml(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&apos;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
