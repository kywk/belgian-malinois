package com.bpm.core.webhook;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.Arrays;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code bpm.webhook.hmac-secret} 不得有程式碼內的 fallback（backlog #26 收尾，
 * 2026-10-02 裁決）。
 *
 * <h2>這裡守的是哪一個部署事故</h2>
 *
 * <p>{@code WebhookConsumer} 的 {@code @Value} 原本寫成
 * {@code ${bpm.webhook.hmac-secret:bpm-webhook-secret}}。冒號後那個字面值
 * <b>公開在版本控制中</b>；屬性一旦缺席（部署漏設、profile 寫錯），
 * 服務仍會啟動，並且持續用任何人都知道的密鑰對外簽章 ——
 * 接收端無法分辨真假，日誌與健康檢查看起來一切正常。
 *
 * <p>移除 fallback 後，屬性缺席會讓 placeholder 解析失敗、context 起不來
 * （fail-fast）。開發與測試仍拿得到值：base 的 {@code application.yml}
 * 提供 {@code bpm-webhook-secret} 這個 dev 預設，prod profile 另外由
 * {@code WebhookHmacSecretValidator} 拒絕空白或沿用開發預設值。
 *
 * <h2>三條測試各自證明什麼</h2>
 *
 * <ul>
 *   <li>{@link #contextFailsToStartWithoutProperty()} —— 行為證據：窄切片
 *       context（只有 {@code WebhookConsumer} 與它需要的兩個 bean）在
 *       <b>不提供屬性</b>時必須起不來。把 fallback 放回去，這條會紅。</li>
 *   <li>{@link #contextStartsWhenPropertyProvided()} —— 對照組：同一個切片
 *       提供屬性後必須正常啟動。沒有這條，上一條可能因為無關的原因（例如
 *       少一個 bean）而紅，卻被誤讀成「防護生效」。</li>
 *   <li>{@link #annotationHasNoDefault()} —— 原始碼形狀：{@code @Value} 的
 *       字串必須恰好是沒有冒號預設值的 placeholder。行為測試擋得住功能
 *       退化，形狀測試則在有人把 fallback「順手」加回去時留下清楚的紅燈。</li>
 * </ul>
 *
 * <p>⚠️ 刻意用 {@link ApplicationContextRunner} 而不是 {@code @SpringBootTest}：
 * 本專案的 {@code IntegrationTestBase.SERVLET_PORT} 是 static final 的單一
 * port，多一個 {@code @SpringBootTest} context 會讓其他測試整組紅掉。
 */
class WebhookConsumerHmacSecretRequiredTest {

    /**
     * 只註冊 placeholder 解析與 {@code WebhookConsumer} 依賴的兩個 bean。
     *
     * <p>不註冊 RabbitMQ 監聽的後處理器，所以 {@code @RabbitListener}
     * 在這個切片裡是惰性的 —— 被測的是建構子的 placeholder 解析。
     */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(WebhookUrlPolicy.class, () -> new WebhookUrlPolicy(""))
            .withBean(WebhookConsumer.class);

    @Test
    @DisplayName("屬性缺席時 context 啟動失敗（fail-fast）—— 不許程式碼自己補預設值")
    void contextFailsToStartWithoutProperty() {
        // ⚠️ ApplicationContextRunner 不會把啟動失敗往外拋：consumer 仍會被呼叫，
        // 失敗掛在 context.getStartupFailure()。用 assertThatThrownBy 會誤判這條測試。
        runner.run(context -> {
            assertThat(context)
                    .as("WebhookConsumer 仍帶著 @Value fallback：屬性缺席也能啟動，"
                            + "公開在 repo 的開發密鑰會被靜默用在正式環境")
                    .hasFailed();
            assertThat(context.getStartupFailure())
                    .hasStackTraceContaining("Could not resolve placeholder")
                    .hasStackTraceContaining("bpm.webhook.hmac-secret");
        });
    }

    @Test
    @DisplayName("屬性提供時 context 必須正常啟動（證明上一條不是因無關原因失敗）")
    void contextStartsWhenPropertyProvided() {
        runner.withPropertyValues("bpm.webhook.hmac-secret=unit-test-only-secret")
                .run(context -> assertThat(context)
                        .as("提供屬性後 context 仍起不來 —— 失敗原因不只是 placeholder")
                        .hasSingleBean(WebhookConsumer.class));
    }

    @Test
    @DisplayName("建構子的 @Value 必須是無預設值的 placeholder（原始碼形狀）")
    void annotationHasNoDefault() {
        var hmacValues = Arrays.stream(WebhookConsumer.class.getDeclaredConstructors())
                .flatMap(constructor -> Arrays.stream(constructor.getParameters()))
                .map(parameter -> parameter.getAnnotation(Value.class))
                .filter(Objects::nonNull)
                .map(Value::value)
                .filter(value -> value.contains("hmac-secret"))
                .toList();

        assertThat(hmacValues)
                .as("hmac-secret 的 @Value 必須恰好一處，且冒號後不得有預設值")
                .containsExactly("${bpm.webhook.hmac-secret}");
    }
}
