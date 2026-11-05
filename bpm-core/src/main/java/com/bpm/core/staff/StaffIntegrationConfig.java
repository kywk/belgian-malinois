package com.bpm.core.staff;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.client.RestClient;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.net.URI;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashSet;

@Configuration
@ConditionalOnProperty(name = "bpm.staff.enabled", havingValue = "true")
@EnableConfigurationProperties(StaffIntegrationProperties.class)
public class StaffIntegrationConfig {
    @Bean
    JwtDecoder staffJwtDecoder(StaffIntegrationProperties p, ResourceLoader resources,
            Environment env, @Value("${bpm.security.jwt.issuer-uri:}") String issuer,
            @Value("${bpm.security.jwt.dev-secret:}") String devSecret) throws Exception {
        if (devSecret != null && !devSecret.isBlank()) throw new IllegalStateException("內勤模式不可設定 dev-secret");
        for (String value : new String[]{issuer, p.audience(), p.keyId(), p.publicKeyLocation(),
                p.identityUrl(), p.authzUrl(), p.samlUrl(), p.callbackUrl()}) {
            if (value == null || value.isBlank()) throw new IllegalStateException("內勤整合必要設定不完整，請參考 staff-sso 部署文件");
        }
        for (String url : new String[]{p.identityUrl(), p.authzUrl(), p.samlUrl(), p.callbackUrl()}) {
            URI uri = URI.create(url);
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null
                    || (!"https".equals(uri.getScheme()) && !("http".equals(uri.getScheme())
                    && !env.matchesProfiles("prod") && env.matchesProfiles("test", "dev") && java.util.Set.of("localhost", "127.0.0.1").contains(uri.getHost()))))
                throw new IllegalStateException("內勤整合 URL 必須為 HTTPS；dev/test 只允許 loopback HTTP");
        }
        if (env.matchesProfiles("prod") && (p.identityToken() == null || p.identityToken().isBlank()
                || p.authzToken() == null || p.authzToken().isBlank()))
            throw new IllegalStateException("prod 必須設定身分橋接與 AuthZ 服務認證");
        String pem;
        try (var in = resources.getResource(p.publicKeyLocation()).getInputStream()) {
            pem = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        byte[] encoded = Base64.getMimeDecoder().decode(pem.replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", ""));
        var key = (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(encoded));
        var decoder = NimbusJwtDecoder.withPublicKey(key).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer), jwt -> {
                    Instant now = Instant.now();
                    boolean valid = "RS256".equals(jwt.getHeaders().get("alg"))
                            && p.keyId().equals(jwt.getHeaders().get("kid"))
                            && jwt.getAudience() != null && jwt.getAudience().contains(p.audience())
                            && jwt.getSubject() != null && !jwt.getSubject().isBlank()
                            && jwt.getSubject().chars().noneMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c))
                            && jwt.getIssuedAt() != null && jwt.getExpiresAt() != null
                            && jwt.getExpiresAt().isAfter(jwt.getIssuedAt())
                            && !jwt.getIssuedAt().isAfter(now.plusSeconds(60));
                    return valid ? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(
                            new OAuth2Error("invalid_token", "Invalid staff token claims", null));
                }));
        return token -> {
            try {
                var claims = com.nimbusds.jwt.SignedJWT.parse(token).getPayload().toJSONObject();
                if (claims == null || !(claims.get("sub") instanceof String) || !(claims.get("iat") instanceof Number) || !(claims.get("exp") instanceof Number)
                        || (claims.containsKey("nbf") && !(claims.get("nbf") instanceof Number)))
                    throw new BadJwtException("Token timestamps must be numeric seconds");
            } catch (java.text.ParseException e) { throw new BadJwtException("Invalid token"); }
            return decoder.decode(token);
        };
    }

    static RestClient client(String url, String token) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(3));
        var builder = RestClient.builder().baseUrl(url).requestFactory(factory);
        if (token != null && !token.isBlank()) builder.defaultHeader("Authorization", token);
        return builder.build();
    }
    @Bean StaffDirectoryClient staffDirectory(StaffIntegrationProperties p) {
        return new StaffDirectoryClient(client(p.identityUrl(), p.identityToken()));
    }
    @Bean StaffAuthzClient staffAuthz(StaffIntegrationProperties p) {
        return new StaffAuthzClient(client(p.authzUrl(), p.authzToken()));
    }

    @Bean @Order(1)
    SecurityFilterChain staffChain(HttpSecurity http, StaffIntegrationProperties p,
            StaffDirectoryClient directory, StaffAuthzClient authz, JwtDecoder decoder) throws Exception {
        http.securityMatcher(request -> {
            String path = request.getRequestURI().substring(request.getContextPath().length());
            // CI gateway deployment keeps its machine policy; Bearer always selects staff validation.
            if (path.equals("/api/deployments") && request.getHeader("Authorization") == null) return false;
            return path.startsWith("/api/") && !path.startsWith("/api/internal/")
                    && !path.startsWith("/api/external/") && !path.startsWith("/api/callback/");
        });
        http.csrf(c -> c.disable()).httpBasic(c -> c.disable()).formLogin(c -> c.disable())
                .sessionManagement(c -> c.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        http.oauth2ResourceServer(o -> o.jwt(j -> j.decoder(decoder).jwtAuthenticationConverter(jwt -> {
            var identity = directory.resolve(jwt.getIssuer().toString(), jwt.getSubject());
            var candidates = new LinkedHashSet<>(StaffPermissions.CATALOG);
            candidates.addAll(p.approvalPermissions().values());
            var permissions = candidates.stream().filter(code -> authz.allowed(identity, code)).toList();
            return new StaffAuthentication(jwt, identity, permissions);
        })).authenticationEntryPoint((req, res, e) -> StaffErrorWriter.write(req, res,
                e instanceof StaffFailure f ? f : new StaffFailure("BPM_AUTH_INVALID", 401, "登入憑證無效或已逾期"))));
        http.addFilterAfter(new OncePerRequestFilter() {
            @Override protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res,
                    FilterChain chain) throws ServletException, IOException {
                String path = req.getRequestURI().substring(req.getContextPath().length());
                if (!path.startsWith("/api/auth/staff/")) {
                    try {
                        var staff = StaffAuthentication.current();
                        if (staff == null) throw new StaffFailure("BPM_AUTH_INVALID", 401, "請使用內勤 SSO 登入");
                        require(staff, StaffPermissions.CONSOLE);
                        require(staff, requiredPermission(path, req.getMethod()));
                        if (path.startsWith("/api/deployments") && !p.allowHumanDeploy())
                            throw StaffFailure.denied();
                    } catch (StaffFailure failure) {
                        StaffErrorWriter.write(req, res, failure);
                        return;
                    }
                }
                chain.doFilter(req, res);
            }
        }, BearerTokenAuthenticationFilter.class);
        http.authorizeHttpRequests(a -> a.requestMatchers("/api/auth/staff/config", "/api/auth/staff/token")
                .permitAll().anyRequest().authenticated());
        return http.build();
    }
    static void require(StaffAuthentication staff, String permission) {
        if (permission != null && !staff.permissions().contains(permission)) throw StaffFailure.denied();
    }
    static String requiredPermission(String path, String method) {
        boolean read = "GET".equals(method) || "HEAD".equals(method);
        if (path.startsWith("/api/audit-logs")) return StaffPermissions.AUDIT;
        if (path.startsWith("/api/deployments")) return StaffPermissions.DEPLOY;
        if (path.startsWith("/api/admin/")) return StaffPermissions.MANAGE;
        if (path.equals("/api/forms") || path.startsWith("/api/forms/")) return read ? null : StaffPermissions.DESIGN;
        if (path.startsWith("/api/bpmn")) return StaffPermissions.DESIGN;
        if (path.startsWith("/api/me/")) return null;
        return read ? StaffPermissions.READ : StaffPermissions.ACT;
    }
}
