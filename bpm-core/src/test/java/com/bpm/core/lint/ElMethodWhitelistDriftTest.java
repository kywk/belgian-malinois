package com.bpm.core.lint;

import com.bpm.core.service.ApplicantResolver;
import com.bpm.core.service.BpmPermissionService;
import com.bpm.core.service.BpmQueryService;
import com.bpm.core.service.DynamicAssigneeResolver;
import com.bpm.core.service.InitialAssigneeResolver;
import com.bpm.core.service.OrgService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code EL_METHOD_WHITELIST} 與 bean 實際類別的防漂移（#35）。
 *
 * <h2>為什麼要 reflection</h2>
 *
 * <p>{@link BpmnLintService#EL_METHOD_WHITELIST} 是一份<b>手寫字串清單</b>，
 * 它與六個 bean 類別之間沒有任何編譯器層級的關聯。沒有這個測試，兩種漂移
 * 都不會被發現：
 *
 * <ul>
 *   <li><b>清單有、類別沒有</b>（改名、刪方法）：lint 放行一個執行期
 *       {@code Unknown property used in expression} 的運算式 —— 錯誤在
 *       使用者送出簽核時才出現，正是 #35 要消滅的時間差。</li>
 *   <li><b>類別有、清單沒有</b>（新增 public 方法）：預設是<b>漏放</b>
 *       （非白名單方法不會被方法層規則檢查，但 bean 在白名單所以整個放行），
 *       新方法默默變成 EL 函式。所以第三條測試要求「每個 public 方法都必須
 *       在白名單或排除清單裡」—— 把漏放變成編譯後的紅燈。</li>
 * </ul>
 *
 * <p>本測試刻意<b>不</b>繼承 {@code IntegrationTestBase}：只做 reflection，
 * 不需要容器。它應該在任何 build 裡都跑得起。
 *
 * <p>⚠️ 清單的「歸屬」判定只看<b>方法名</b>，不驗簽章（#35 的驗收即如此）。
 * 同名多載（例如未來加了 {@code getDeptId(String, boolean)}）不會被這條測試
 * 區分 —— 屆時要改用完整的參數型別比對。
 */
class ElMethodWhitelistDriftTest {

    /**
     * bean 名稱 → 實際類別。名稱必須與 {@code FlowableConfig.setBeans()} 一致。
     *
     * <p>寫死在測試裡是刻意的：類別改名或 bean 名稱改動時這裡會編譯失敗／
     * 斷言失敗，強迫作者回來同步三份清單（本表、{@code EL_WHITELIST}、
     * {@code FlowableConfig}）。執行期命名空間是否真的接上由
     * {@code BpmnExpressionBeanScopeTest} 負責，兩者分工不同。
     */
    private static final Map<String, Class<?>> BEAN_CLASSES = Map.of(
            "orgService", OrgService.class,
            "permService", BpmPermissionService.class,
            "bpmQueryService", BpmQueryService.class,
            "assigneeResolver", InitialAssigneeResolver.class,
            "applicantResolver", ApplicantResolver.class,
            "dynamicAssignee", DynamicAssigneeResolver.class);

    /**
     * 類別<b>自己宣告</b>的 public 方法名。
     *
     * <p>用 {@code getDeclaredMethods()} 而非 {@code getMethods()}：後者會帶進
     * {@code Object.equals}／{@code hashCode}／{@code getClass} 等繼承方法，
     * 那些不是這個 bean 的業務介面，也不該進任何一張清單。
     * 排除 synthetic／bridge（泛型橋接方法）以免同名重複計數。
     */
    private static Set<String> declaredPublicMethods(Class<?> clazz) {
        return Arrays.stream(clazz.getDeclaredMethods())
                .filter(m -> Modifier.isPublic(m.getModifiers()))
                .filter(m -> !m.isSynthetic() && !m.isBridge())
                .map(Method::getName)
                .collect(Collectors.toSet());
    }

    @Test
    @DisplayName("bean 對應表、EL_WHITELIST、EL_METHOD_WHITELIST 三者的 bean 集合必須一致")
    void beanSetsAreInSync() {
        assertThat(BEAN_CLASSES.keySet())
                .as("bean 對應表與 EL_WHITELIST 不一致 —— 新增／移除 bean 時兩邊都要改")
                .containsExactlyInAnyOrderElementsOf(BpmnLintService.EL_WHITELIST);
        assertThat(BpmnLintService.EL_METHOD_WHITELIST.keySet())
                .as("EL_METHOD_WHITELIST 與 EL_WHITELIST 的 bean 不一致 —— "
                        + "白名單 bean 沒有方法清單時，lint 的方法層檢查會靜默跳過")
                .containsExactlyInAnyOrderElementsOf(BpmnLintService.EL_WHITELIST);
        assertThat(BpmnLintService.EL_METHOD_EXCLUDED.keySet())
                .as("排除清單出現了不認識的 bean")
                .isSubsetOf(BpmnLintService.EL_WHITELIST);
    }

    @Test
    @DisplayName("EL_METHOD_WHITELIST 列出的方法必須真的存在於對應類別")
    void everyWhitelistedMethodExists() {
        for (var entry : BpmnLintService.EL_METHOD_WHITELIST.entrySet()) {
            assertThat(declaredPublicMethods(BEAN_CLASSES.get(entry.getKey())))
                    .as("bean '%s' 的類別上找不到白名單列出的方法 —— "
                            + "lint 會放行一個執行期必然失敗的運算式", entry.getKey())
                    .containsAll(entry.getValue());
        }
    }

    @Test
    @DisplayName("類別的每個 public 方法都必須在白名單或排除清單裡（漏放會變紅燈）")
    void everyPublicMethodIsWhitelistedOrExplicitlyExcluded() {
        for (var entry : BEAN_CLASSES.entrySet()) {
            String bean = entry.getKey();
            Set<String> undeclared = new TreeSet<>(declaredPublicMethods(entry.getValue()));
            undeclared.removeAll(BpmnLintService.EL_METHOD_WHITELIST.getOrDefault(bean, Set.of()));
            undeclared.removeAll(BpmnLintService.EL_METHOD_EXCLUDED.getOrDefault(bean, Set.of()));

            assertThat(undeclared)
                    .as("bean '%s' 有 public 方法既不在 EL_METHOD_WHITELIST 也不在 EL_METHOD_EXCLUDED。"
                            + "要讓 BPMN 呼叫請進白名單；不該被呼叫請進排除清單並寫下原因。"
                            + "兩張表都在 BpmnLintService", bean)
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("排除清單列出的方法必須存在，且不得同時出現在白名單")
    void excludedMethodsExistAndAreDisjoint() {
        for (var entry : BpmnLintService.EL_METHOD_EXCLUDED.entrySet()) {
            String bean = entry.getKey();
            assertThat(declaredPublicMethods(BEAN_CLASSES.get(bean)))
                    .as("bean '%s' 的排除清單列了不存在的方法（改名後忘了同步）", bean)
                    .containsAll(entry.getValue());
            assertThat(BpmnLintService.EL_METHOD_WHITELIST.getOrDefault(bean, Set.of()))
                    .as("bean '%s' 的方法同時在白名單與排除清單 —— 兩張表互相矛盾", bean)
                    .doesNotContainAnyElementsOf(entry.getValue());
        }
    }
}
