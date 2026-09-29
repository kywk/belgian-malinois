package com.bpm.core.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 三個 DataSource 的帳密必須來自<b>單一屬性來源</b>。
 *
 * <h2>這個測試要抓的是哪一個部署缺陷</h2>
 *
 * <p>P2-3 修正前，{@code application.yml} 在三處各寫死一份
 * {@code password: BpmDev@2026!}（primary／audit／form），而
 * {@code docker-compose.prod.yml} 只注入 {@code SPRING_DATASOURCE_PASSWORD}
 * —— 那只蓋掉 primary。上 production 時 audit 與 form 兩個池會拿
 * <b>dev 密碼</b>去連正式資料庫。
 *
 * <p>這個缺陷有兩種結局，都不好：
 * <ul>
 *   <li>prod 密碼不同 → Flyway migrator 在啟動時連不上而炸掉。這還算幸運，
 *       至少是大聲失敗。</li>
 *   <li>dev 與 prod 密碼剛好相同（很常見，因為 compose 檔是從 dev 複製的）
 *       → 能跑，但輪換 prod 密碼時只會換到一個池，下次重啟才爆，
 *       而且爆的時間點和改動完全脫鉤。</li>
 * </ul>
 *
 * <h2>為什麼斷言的是「YAML 原文是佔位符」而不是解析後的值</h2>
 *
 * <p>解析後的值在測試環境會被 {@code @DynamicPropertySource} 覆蓋成
 * Testcontainers 的帳密，三個都一樣 —— 所以「值相同」這個斷言在測試裡
 * <b>永遠會過</b>，完全抓不到本缺陷。真正要守的是「yml 沒有把帳密展開成三份
 * 各自獨立的字面值」，那是原文層次的性質。
 *
 * <p>這個測試不啟 Spring context，所以很快；它守的是設定檔的形狀，不是執行期行為。
 */
class DataSourceCredentialSourceTest {

    private static final String SHARED = "${bpm.datasource.password}";

    private static Properties load(String resource) {
        var factory = new YamlPropertiesFactoryBean();
        factory.setResources(new ClassPathResource(resource));
        var props = factory.getObject();
        assertThat(props).as("讀不到 %s", resource).isNotNull();
        return props;
    }

    @Test
    @DisplayName("三個 DataSource 的 password 都必須引用 bpm.datasource.password")
    void allThreePasswordsReferenceTheSharedProperty() {
        var props = load("application.yml");

        assertThat(props.getProperty("spring.datasource.password")).isEqualTo(SHARED);
        assertThat(props.getProperty("spring.datasource.audit.password"))
                .as("audit 的密碼寫死了 —— prod 只會覆蓋 primary，audit 會拿 dev 密碼連正式庫")
                .isEqualTo(SHARED);
        assertThat(props.getProperty("spring.datasource.form.password"))
                .as("form 的密碼寫死了 —— 同上")
                .isEqualTo(SHARED);
    }

    @Test
    @DisplayName("三個 DataSource 的 url 都必須引用 bpm.datasource.host")
    void allThreeUrlsReferenceTheSharedHost() {
        var props = load("application.yml");

        for (var key : new String[] {
                "spring.datasource.url",
                "spring.datasource.audit.url",
                "spring.datasource.form.url" }) {
            assertThat(props.getProperty(key))
                    .as("%s 必須用 ${bpm.datasource.host}，否則換 DB 位置要改三個地方"
                            + "（docker profile 就是這樣長出三份重複的）", key)
                    .startsWith("jdbc:sqlserver://${bpm.datasource.host};");
        }
    }

    @Test
    @DisplayName("bpm.datasource.* 必須有 dev 預設值，且 docker profile 只覆蓋 host")
    void sharedPropertiesHaveDefaultsAndDockerOverridesHostOnly() {
        // 注意：YamlPropertiesFactoryBean 會把多文件 YAML 平坦化成一份，
        // 後面的文件覆蓋前面的 —— 所以這裡拿到的 host 是 docker profile 的值。
        // 這正好可以驗證「docker profile 確實只改了 host」。
        var props = load("application.yml");

        assertThat(props.getProperty("bpm.datasource.username")).isEqualTo("sa");
        assertThat(props.getProperty("bpm.datasource.password")).isNotBlank();
        assertThat(props.getProperty("bpm.datasource.host"))
                .as("docker profile 應覆蓋 host 為容器名")
                .isEqualTo("mssql:1433");
    }
}
