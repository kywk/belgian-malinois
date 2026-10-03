package com.bpm.core.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.env.MockPropertySource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 環境變數替換器（backlog #53，spec §12.3）—— 純單元測試。
 *
 * <h2>這一組測試釘住的政策</h2>
 *
 * <ul>
 *   <li>只認 {@code ${ENV_[A-Z0-9_]+}}；其他 {@code ${...}} 逐位元不動。</li>
 *   <li>無佔位符時回傳<b>同一個物件</b> —— 「與加入本功能前相同」的邊界。</li>
 *   <li>查不到值或值只有空白 → {@link BpmnEnvSubstitutionException}（部署端
 *       轉 400）；訊息指名變數與設定鍵。</li>
 *   <li>值一律 XML 轉義，且轉義的驗證不是比對字串長相，是<b>用 XML 解析器
 *       解析後等於原值</b> —— 少轉義一個字元這裡就會壞。</li>
 *   <li>值含巢狀 {@code ${ENV_*}} → fail-closed（不做遞迴替換）。</li>
 * </ul>
 */
class BpmnEnvSubstitutorTest {

    private static BpmnEnvSubstitutor with(String name, String value) {
        return new BpmnEnvSubstitutor(new MockEnvironment()
                .withProperty(BpmnEnvSubstitutor.KEY_PREFIX + name, value));
    }

    // ── 替換本身 ───────────────────────────────────────────────────

    @Test
    @DisplayName("屬性值與文字節點裡的佔位符都會被替換，usedNames 依出現順序")
    void substitutesAttributeAndTextNode() {
        var env = new MockEnvironment()
                .withProperty("bpmn.variables.ENV_FINANCE_GROUP", "finance_dept_approver")
                .withProperty("bpmn.variables.ENV_DOC_TEXT", "由環境決定");
        String xml = """
                <definitions xmlns:flowable="http://flowable.org/bpmn">
                  <userTask id="t" flowable:candidateGroups="${ENV_FINANCE_GROUP}"/>
                  <documentation>${ENV_DOC_TEXT}</documentation>
                </definitions>
                """;

        var result = new BpmnEnvSubstitutor(env).resolve(xml);

        assertThat(result.xml())
                .contains("flowable:candidateGroups=\"finance_dept_approver\"")
                .contains("<documentation>由環境決定</documentation>")
                .doesNotContain("${ENV_");
        assertThat(result.usedNames()).containsExactly("ENV_FINANCE_GROUP", "ENV_DOC_TEXT");
        assertThat(result.hasSubstitutions()).isTrue();
    }

    @Test
    @DisplayName("多個佔位符都替換；同名重複出現只記一次 usedNames")
    void multiplePlaceholdersAndDeduplicatedNames() {
        var env = new MockEnvironment()
                .withProperty("bpmn.variables.ENV_A", "a1")
                .withProperty("bpmn.variables.ENV_B", "b1");

        var result = new BpmnEnvSubstitutor(env)
                .resolve("x=\"${ENV_A}\" y=\"${ENV_B}\" z=\"${ENV_A}\"");

        assertThat(result.xml()).isEqualTo("x=\"a1\" y=\"b1\" z=\"a1\"");
        assertThat(result.usedNames())
                .as("同名出現兩次，稽核清單不該重複")
                .containsExactly("ENV_A", "ENV_B");
    }

    @Test
    @DisplayName("沒有佔位符時回傳同一個物件（逐位元不變）")
    void noPlaceholderReturnsSameInstance() {
        String xml = "<definitions>${orgService.getDirectManager(initiator)}</definitions>";

        var result = new BpmnEnvSubstitutor(new MockEnvironment()).resolve(xml);

        assertThat(result.xml())
                .as("部署端依賴這個性質維持「無佔位符＝零行為差異」")
                .isSameAs(xml);
        assertThat(result.hasSubstitutions()).isFalse();
        assertThat(result.usedNames()).isEmpty();
    }

    // ── fail-closed ────────────────────────────────────────────────

    @Test
    @DisplayName("值未設定 → 例外，訊息同時指名變數與設定鍵")
    void missingValueFailsClosed() {
        var substitutor = new BpmnEnvSubstitutor(new MockEnvironment());

        assertThatThrownBy(() -> substitutor.resolve("<x g=\"${ENV_FINANCE_GROUP}\"/>"))
                .isInstanceOf(BpmnEnvSubstitutionException.class)
                .hasMessageContaining("ENV_FINANCE_GROUP")
                .hasMessageContaining("bpmn.variables.ENV_FINANCE_GROUP")
                .satisfies(e -> {
                    var ex = (BpmnEnvSubstitutionException) e;
                    assertThat(ex.variableName()).isEqualTo("ENV_FINANCE_GROUP");
                    assertThat(ex.configKey()).isEqualTo("bpmn.variables.ENV_FINANCE_GROUP");
                });
    }

    @Test
    @DisplayName("值只有空白 → 視同未設定（空字串替換會製造沒有人看得到的任務）")
    void blankValueFailsClosed() {
        assertThatThrownBy(() -> with("ENV_X", "   ").resolve("g=\"${ENV_X}\""))
                .isInstanceOf(BpmnEnvSubstitutionException.class)
                .hasMessageContaining("空白");
    }

    @Test
    @DisplayName("值含無法解析的巢狀 ${...}：Spring 的解析例外收斂成替換例外（部署端 400，不是 500）")
    void unresolvableNestedValueFailsClosed() {
        // 實測行為：Environment.getProperty 會把值裡的 ${...} 一併解析，
        // 無法解析時直接拋 PlaceholderResolutionException。裸拋會讓部署端
        // 回 500；這裡必須收斂成 BpmnEnvSubstitutionException → 400。
        assertThatThrownBy(() -> with("ENV_A", "${ENV_B}").resolve("g=\"${ENV_A}\""))
                .isInstanceOf(BpmnEnvSubstitutionException.class)
                .hasMessageContaining("ENV_A")
                .hasMessageContaining("bpmn.variables.ENV_A");
    }

    @Test
    @DisplayName("巢狀 ${ENV_*} 殘留（環境設為忽略未解析佔位符時）仍被擋下，不遞迴替換")
    void nestedEnvPlaceholderInValueFailsClosed() {
        // 非預設設定（ignoreUnresolvableNestedPlaceholders=true）下，
        // getProperty 會把無法解析的 ${ENV_B} 原樣回傳 —— 這是最後一道防線：
        // 放行等於部署一份執行期才爆的 BPMN，且 lint 把 ENV_* 視為合法字面。
        var environment = new StandardEnvironment();
        environment.setIgnoreUnresolvableNestedPlaceholders(true);
        environment.getPropertySources().addFirst(new MockPropertySource()
                .withProperty("bpmn.variables.ENV_A", "${ENV_B}"));

        assertThatThrownBy(() -> new BpmnEnvSubstitutor(environment).resolve("g=\"${ENV_A}\""))
                .isInstanceOf(BpmnEnvSubstitutionException.class)
                .hasMessageContaining("ENV_B");
    }

    // ── XML 轉義 ───────────────────────────────────────────────────

    @Test
    @DisplayName("值含 XML 特殊字元會被轉義，解析後等於原值")
    void specialCharactersAreEscaped() throws Exception {
        String raw = "A&B<C>D\"E'F";

        var result = with("ENV_X", raw).resolve("<x g=\"${ENV_X}\"/>");

        assertThat(result.xml())
                .as("特殊字元必須以實體形式出現在 XML 裡")
                .contains("A&amp;B&lt;C&gt;D&quot;E&apos;F")
                .doesNotContain(raw);
        // 真正要證明的不是字串長相，是解析結果：少轉義任何一個字元，
        // 這裡不是解析失敗就是拿到被切斷的值。
        var doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new ByteArrayInputStream(result.xml().getBytes(StandardCharsets.UTF_8)));
        assertThat(doc.getDocumentElement().getAttribute("g")).isEqualTo(raw);
    }

    @Test
    @DisplayName("值含 $ 與反斜線不會被 appendReplacement 當成群組參照")
    void dollarAndBackslashInValueAreLiteral() {
        // Matcher.appendReplacement 對 $ 與 \ 有特殊語意；沒有 quoteReplacement
        // 會拋 IllegalArgumentException 或插入錯誤內容。
        var result = with("ENV_X", "a$b\\c$1").resolve("g=\"${ENV_X}\"");

        assertThat(result.xml()).isEqualTo("g=\"a$b\\c$1\"");
    }

    // ── 命名空間邊界 ───────────────────────────────────────────────

    @Test
    @DisplayName("非 ENV 命名空間的 ${...} 原樣不動（Flowable EL）")
    void nonEnvExpressionsAreUntouched() {
        String xml = """
                <userTask flowable:assignee="${orgService.getDirectManager(initiator)}"
                          flowable:candidateGroups="${dept}"/>
                """;

        var result = new BpmnEnvSubstitutor(new MockEnvironment()).resolve(xml);

        assertThat(result.xml()).isSameAs(xml);
        assertThat(result.usedNames()).isEmpty();
    }

    @Test
    @DisplayName("${ENV_}（空名）、${env_x}（小寫）、${ENV-X} 都不是合法佔位符，原樣不動")
    void invalidEnvNamesAreNotPlaceholders() {
        for (String xml : List.of("a=\"${ENV_}\"", "a=\"${env_x}\"", "a=\"${ENV-X}\"")) {
            var result = new BpmnEnvSubstitutor(new MockEnvironment()).resolve(xml);
            assertThat(result.xml()).as(xml).isSameAs(xml);
            assertThat(result.hasSubstitutions()).as(xml).isFalse();
        }
    }

    // ── 設定覆蓋方式（實測） ───────────────────────────────────────

    @Test
    @DisplayName("環境變數 BPMN_VARIABLES_ENV_FINANCE_GROUP 可覆蓋（Spring relaxed binding）")
    void environmentVariableOverrideWorks() {
        // 餵給 SystemEnvironmentPropertySource 的是環境變數形狀的鍵名 ——
        // 這正是 Spring 在 prod 讀 OS 環境時用的 property source 實作。
        // 驗證 lookup 路徑認得它，而不是只驗「YAML 鍵查得到」。
        var environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource(
                "test-env", Map.of("BPMN_VARIABLES_ENV_FINANCE_GROUP", "finance_dept_approver")));

        var result = new BpmnEnvSubstitutor(environment)
                .resolve("<userTask flowable:candidateGroups=\"${ENV_FINANCE_GROUP}\"/>");

        assertThat(result.xml())
                .as("relaxed binding 不可行時，prod 覆蓋方式必須改文件化（PM 政策 2）")
                .contains("finance_dept_approver");
    }
}
