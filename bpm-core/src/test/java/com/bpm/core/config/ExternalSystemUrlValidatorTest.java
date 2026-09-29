package com.bpm.core.config;

import com.bpm.core.controller.MockOrgController;
import com.bpm.core.controller.MockPermController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 設錯的授權來源不該啟動成功（security-audit P2-7）。
 *
 * <h2>這裡守的是哪一個部署事故</h2>
 *
 * <p>三件事相乘造成它：mock controller 沒有任何條件限制、
 * {@code org-service-url} 預設指向自己的 {@code /mock/org} 而沒有任何 profile
 * 覆蓋它、三個 compose 檔都用同一個 {@code docker} profile。
 * 結果是正式環境的組織架構與權限判定全部來自一份 9 人開發 fixture，
 * <b>而且不會報錯</b> —— 服務健康、流程跑得動、任務都派得出去，只是派給 mgr001。
 *
 * <p>所以這組測試不只驗證「validator 會拋例外」，還驗證
 * {@code @ConditionalOnProperty} 真的讓 mock controller 在關閉時<b>不存在</b>，
 * 以及設定檔的形狀（prod profile 確實關掉 mock 且不給 URL 預設值）。
 * 三者缺一，事故就能重演。
 */
class ExternalSystemUrlValidatorTest {

    private static final String MOCK_ORG = "http://localhost:8080/mock/org";
    private static final String MOCK_PERM = "http://localhost:8080/mock/perm";
    private static final String REAL_ORG = "https://org.internal.example.com";
    private static final String REAL_PERM = "https://perm.internal.example.com";

    private static void validate(boolean mockEnabled, String org, String perm) {
        new ExternalSystemUrlValidator(mockEnabled, org, perm).validate();
    }

    @Test
    @DisplayName("mock 關閉但 URL 仍指向 /mock/ → 拒絕啟動")
    void rejectsMockUrlWhenMockDisabled() {
        // 這正是「正式環境跑在開發 fixture 上」的設定組合。
        assertThatThrownBy(() -> validate(false, MOCK_ORG, MOCK_PERM))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("org-service-url")
                .hasMessageContaining("mock-enabled=false");
    }

    @Test
    @DisplayName("URL 未設定 → 拒絕啟動（不提供預設值）")
    void rejectsBlankUrl() {
        assertThatThrownBy(() -> validate(false, "", REAL_PERM))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("org-service-url");
        assertThatThrownBy(() -> validate(false, REAL_ORG, "  "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("perm-service-url");
    }

    @Test
    @DisplayName("mock 開啟但 URL 指向外部系統 → 拒絕啟動（意圖矛盾）")
    void rejectsContradictoryCombination() {
        // 這個方向也要擋：mock controller 會註冊，但沒人會呼叫它。
        // 看起來像「mock 開著」，實際上打的是真實系統 —— 無法判斷意圖就不要猜。
        assertThatThrownBy(() -> validate(true, REAL_ORG, REAL_PERM))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("互相矛盾");
    }

    @Test
    @DisplayName("兩組一致的設定都必須放行")
    void acceptsConsistentConfigurations() {
        validate(true, MOCK_ORG, MOCK_PERM);   // 開發
        validate(false, REAL_ORG, REAL_PERM);  // 正式
    }

    @Test
    @DisplayName("mock-enabled=false 時 mock controller 必須不存在")
    void mockControllersAreAbsentWhenDisabled() {
        // 用 ApplicationContextRunner 而非 @SpringBootTest：這裡要驗的是
        // @ConditionalOnProperty 的行為，不需要整個應用起來。
        var runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
                .withUserConfiguration(MockOrgController.class, MockPermController.class);

        runner.withPropertyValues("bpm.external.mock-enabled=false")
                .run(ctx -> assertThat(ctx)
                        .as("關閉時 mock controller 仍被註冊 —— @ConditionalOnProperty 沒生效")
                        .doesNotHaveBean(MockOrgController.class)
                        .doesNotHaveBean(MockPermController.class));

        // 完全不設這個屬性（部署時漏設）也必須是「不存在」。
        // 遺漏設定要倒向安全的那一邊。
        runner.run(ctx -> assertThat(ctx)
                .as("未設定時 mock 竟然存在 —— havingValue 的預設行為不對")
                .doesNotHaveBean(MockOrgController.class)
                .doesNotHaveBean(MockPermController.class));

        runner.withPropertyValues("bpm.external.mock-enabled=true")
                .run(ctx -> assertThat(ctx)
                        .as("開啟時 mock controller 必須存在，否則開發環境會壞")
                        .hasSingleBean(MockOrgController.class)
                        .hasSingleBean(MockPermController.class));
    }

    @Test
    @DisplayName("prod profile 必須關掉 mock 且不給 URL 預設值")
    void prodProfileDisablesMockAndRequiresExplicitUrls() {
        // YamlPropertiesFactoryBean 把多文件 YAML 平坦化，後面的文件覆蓋前面的。
        // prod 是最後一份文件，所以這裡讀到的就是 prod profile 的值。
        var factory = new YamlPropertiesFactoryBean();
        factory.setResources(new ClassPathResource("application.yml"));
        var props = factory.getObject();
        assertThat(props).isNotNull();

        assertThat(props.getProperty("bpm.external.mock-enabled"))
                .as("prod profile 沒有關掉開發 fixture")
                .isEqualTo("false");
        assertThat(props.getProperty("bpm.external.org-service-url"))
                .as("prod 的 org URL 必須來自環境變數且無預設值 —— 猜一個位置就是猜授權來源")
                .isEqualTo("${ORG_SERVICE_URL:}");
        assertThat(props.getProperty("bpm.external.perm-service-url"))
                .isEqualTo("${PERM_SERVICE_URL:}");
    }
}
