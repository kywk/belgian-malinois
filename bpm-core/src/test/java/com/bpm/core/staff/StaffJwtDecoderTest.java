package com.bpm.core.staff;

import org.junit.jupiter.api.*;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.oauth2.jwt.JwtException;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class StaffJwtDecoderTest {
    static StaffTestProvider provider;
    @BeforeAll static void setup() { provider = new StaffTestProvider(); }
    @AfterAll static void stop() { provider.close(); }
    private StaffIntegrationProperties properties(String url, String identityToken, String authzToken) {
        return new StaffIntegrationProperties(true, StaffTestProvider.AUDIENCE, StaffTestProvider.KID,
                provider.publicKey.toUri().toString(), url, identityToken, url, authzToken, url,
                url + "/", false, null, null, Map.of());
    }
    @Test void acceptsOnlyPinnedSignatureAndNumericRequiredTimes() throws Exception {
        var decoder = new StaffIntegrationConfig().staffJwtDecoder(properties(provider.url(), "", ""),
                new DefaultResourceLoader(), new MockEnvironment().withProperty("spring.profiles.active", "test"), StaffTestProvider.ISSUER, "");
        assertThat(decoder.decode(provider.token("W9000001")).getSubject()).isEqualTo("W9000001");
        for (String field : List.of("iat", "exp")) {
            var missing = new HashMap<String, Object>(); missing.put(field, null);
            assertThatThrownBy(() -> decoder.decode(provider.token("W9000001", missing))).isInstanceOf(JwtException.class);
            assertThatThrownBy(() -> decoder.decode(provider.token("W9000001", Map.of(field, "123")))).isInstanceOf(JwtException.class);
        }
        assertThatThrownBy(() -> decoder.decode(provider.token("W9000001", Map.of("nbf", Date.from(Instant.now().plusSeconds(120))))))
                .isInstanceOf(JwtException.class);
        try (var foreign = new StaffTestProvider()) {
            assertThatThrownBy(() -> decoder.decode(foreign.token("W9000001"))).isInstanceOf(JwtException.class);
        }
    }
    @Test void formalModeRejectsDevSecretAndUnsafeUrls() {
        var config = new StaffIntegrationConfig();
        var resources = new DefaultResourceLoader();
        var env = new MockEnvironment().withProperty("spring.profiles.active", "test");
        assertThatThrownBy(() -> config.staffJwtDecoder(properties(provider.url(), "", ""), resources, env,
                StaffTestProvider.ISSUER, "a-dev-secret")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> config.staffJwtDecoder(properties("http://untrusted.example", "", ""), resources, env,
                StaffTestProvider.ISSUER, "")).isInstanceOf(IllegalStateException.class);
        var mixed = new MockEnvironment().withProperty("spring.profiles.active", "prod,test");
        assertThatThrownBy(() -> config.staffJwtDecoder(properties(provider.url(), "credential", "credential"), resources, mixed,
                StaffTestProvider.ISSUER, "")).isInstanceOf(IllegalStateException.class);
        var prod = new MockEnvironment().withProperty("spring.profiles.active", "prod");
        assertThatThrownBy(() -> config.staffJwtDecoder(properties("https://trusted.example", "", ""), resources, prod,
                StaffTestProvider.ISSUER, "")).isInstanceOf(IllegalStateException.class);
    }
}
