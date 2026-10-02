package com.bpm.core.security;

import com.bpm.core.webhook.WebhookConsumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import java.util.Arrays;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * prod 不可用公開在 repo 裡的 HMAC 密鑰簽 webhook（#26/#27 收尾發現的不對稱）。
 *
 * <h2>這裡守的是哪一個部署事故</h2>
 *
 * <p>身分（{@code JwtSecurityValidator}）與閘道密鑰都有 prod 啟動防護，
 * webhook HMAC 沒有。而 {@code application.yml} 的 base 文件與
 * {@code WebhookConsumer} 的 {@code @Value} 都帶著公開的預設字面值
 * {@code bpm-webhook-secret}；#67/#26 之後這條路徑真的會投遞事件，
 * 於是「prod 忘了換密鑰」不再是無害的 —— 它會持續送出任何人
 * 都能偽造的簽章，且看起來一切正常。
 *
 * <p>用純單元測試（直接 new + {@link MockEnvironment}）而不是 Spring context：
 * 這個檢查的輸入只有「profile」與「密鑰字串」兩者，不需要整個應用起來。
 * 另外兩條測試守住「設定檔的形狀」與「三處預設字面值同步」——
 * 缺少任何一條，事故都能重演。
 */
class WebhookHmacSecretValidatorTest {

    /** 獨立寫一份字面值：防護要擋的是「實際公開的那個值」，不是常數自己。 */
    private static final String PUBLISHED_DEV_DEFAULT = "bpm-webhook-secret";

    private static WebhookHmacSecretValidator validator(String secret, String... activeProfiles) {
        var environment = new MockEnvironment();
        environment.setActiveProfiles(activeProfiles);
        return new WebhookHmacSecretValidator(environment, secret);
    }

    @Test
    @DisplayName("prod + repo 裡的預設字面值 → 拒絕啟動")
    void rejectsDevDefaultInProd() {
        assertThatThrownBy(() -> validator(PUBLISHED_DEV_DEFAULT, "prod").validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("hmac-secret")
                .hasMessageContaining("預設值");
    }

    @Test
    @DisplayName("prod + 空白／未設定 → 拒絕啟動（不提供預設值）")
    void rejectsBlankSecretInProd() {
        assertThatThrownBy(() -> validator("", "prod").validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("hmac-secret");
        assertThatThrownBy(() -> validator("   ", "prod").validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("hmac-secret");
        assertThatThrownBy(() -> validator(null, "prod").validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("hmac-secret");
    }

    @Test
    @DisplayName("prod + 部署環境提供的自訂值 → 通過")
    void acceptsCustomSecretInProd() {
        assertThatCode(() -> validator("prod-only-webhook-secret-from-vault", "prod").validate())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("非 prod + 預設值或空白 → 通過（開發預設；空白僅警告）")
    void acceptsDevDefaultOutsideProd() {
        assertThatCode(() -> validator(PUBLISHED_DEV_DEFAULT).validate())
                .doesNotThrowAnyException();
        assertThatCode(() -> validator(PUBLISHED_DEV_DEFAULT, "docker").validate())
                .doesNotThrowAnyException();
        assertThatCode(() -> validator("", "docker").validate())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("prod profile 不得繼承 base 的預設字面值")
    void prodProfileDoesNotInheritDevDefault() {
        // YamlPropertiesFactoryBean 把多文件 YAML 平坦化，後面的文件覆蓋前面的。
        // prod 是最後一份文件，所以這裡讀到的就是 prod profile 的值。
        var factory = new YamlPropertiesFactoryBean();
        factory.setResources(new ClassPathResource("application.yml"));
        var props = factory.getObject();
        assertThat(props).isNotNull();

        assertThat(props.getProperty("bpm.webhook.hmac-secret"))
                .as("prod profile 的 hmac-secret 必須來自環境變數 —— "
                        + "留著 base 的預設字面值，validator 的檢查就形同虛設")
                .isEqualTo("${BPM_WEBHOOK_HMAC_SECRET:}");
    }

    @Test
    @DisplayName("三處預設字面值（yml base／WebhookConsumer fallback／validator 常數）必須同步")
    void knownDefaultStaysInSyncAcrossSources() throws Exception {
        // yml 的 base 文件是第一份（prod 文件在最後）。
        var docs = new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"));
        assertThat(docs.get(0).getProperty("bpm.webhook.hmac-secret"))
                .as("application.yml base 文件的預設值變了，validator 的常數沒跟上")
                .isEqualTo(PUBLISHED_DEV_DEFAULT);

        var constructor = WebhookConsumer.class.getDeclaredConstructors()[0];
        String fallback = Arrays.stream(constructor.getParameters())
                .map(parameter -> parameter.getAnnotation(Value.class))
                .filter(Objects::nonNull)
                .map(Value::value)
                .filter(value -> value.startsWith("${bpm.webhook.hmac-secret:"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "WebhookConsumer 找不到 bpm.webhook.hmac-secret 的 @Value fallback"));
        String literal = fallback.substring(fallback.indexOf(':') + 1, fallback.length() - 1);
        assertThat(literal)
                .as("WebhookConsumer 的 fallback 變了，validator 的常數沒跟上 —— 防護會漏掉真正使用的預設值")
                .isEqualTo(PUBLISHED_DEV_DEFAULT);

        assertThat(WebhookHmacSecretValidator.DEV_DEFAULT_SECRET)
                .as("validator 認定的預設值與實際公開的預設值不一致")
                .isEqualTo(PUBLISHED_DEV_DEFAULT);
    }
}
