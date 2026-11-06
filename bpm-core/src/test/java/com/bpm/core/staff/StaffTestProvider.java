package com.bpm.core.staff;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.*;
import tools.jackson.databind.json.JsonMapper;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.interfaces.RSAPrivateKey;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Ephemeral loopback identity/AuthZ/SAML contract provider; never part of the application. */
public class StaffTestProvider implements AutoCloseable {
    public static final String APPROVE = "bpm:approval:bff_contract_review:approve";
    public static final String ISSUER = "https://staff-sso.example.test";
    public static final String AUDIENCE = "bpm-test";
    public static final String KID = "test-key";
    public static final JsonMapper JSON = JsonMapper.builder().build();
    public final Set<String> denied = ConcurrentHashMap.newKeySet();
    public volatile String userType = "STAFF", status = "ACTIVE", dept = "50310001", identityResult = "FOUND";
    public volatile String inactiveUserId, approvalOrg;
    public volatile Instant delegationEnd;
    public volatile boolean expiredSnapshot;
    public volatile String callbackUrl = "http://127.0.0.1:31999/";
    public volatile String authzCode = "0000";
    public volatile boolean malformedAllowed, staleIdentity, failIdentity;
    public volatile List<String> lastSubjects = List.of();
    public final KeyPair key;
    public final Path publicKey;
    private final HttpServer server;
    private final Map<String, String> codes = new ConcurrentHashMap<>();
    public StaffTestProvider() {
        try {
            var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); key = generator.generateKeyPair();
            Path dir = Path.of("target", "staff-test"); Files.createDirectories(dir);
            publicKey = Files.createTempFile(dir, "public-", ".pem").toAbsolutePath();
            Files.writeString(publicKey, "-----BEGIN PUBLIC KEY-----\n" + Base64.getMimeEncoder().encodeToString(key.getPublic().getEncoded()) + "\n-----END PUBLIC KEY-----\n");
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", this::handle); server.start();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    public String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
    public void reset() {
        inactiveUserId = null; approvalOrg = null; delegationEnd = null; expiredSnapshot = false; denied.clear(); userType = "STAFF"; status = "ACTIVE"; dept = "50310001"; identityResult = "FOUND";
        authzCode = "0000"; malformedAllowed = staleIdentity = failIdentity = false;
    }
    public String token(String subject) { return token(subject, Map.of()); }
    public String token(String subject, Map<String, Object> overrides) {
        try {
            Instant now = Instant.now();
            var claims = new JWTClaimsSet.Builder().issuer(ISSUER).audience(AUDIENCE).subject(subject)
                    .issueTime(Date.from(now.minusSeconds(1))).expirationTime(Date.from(now.plusSeconds(600)))
                    .claim("roles", List.of("admin", "*", StaffPermissions.AUDIT))
                    .claim("office_code", "WRONG-JWT-ORG");
            overrides.forEach(claims::claim);
            var signed = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256)
                    .keyID(overrides.containsKey("kid") ? overrides.get("kid").toString() : KID).build(), claims.build());
            signed.sign(new RSASSASigner((RSAPrivateKey) key.getPrivate()));
            return signed.serialize();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    private void handle(HttpExchange exchange) throws java.io.IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (path.equals("/testing/deny")) {
                denied.add(APPROVE); reply(exchange, 200, Map.of()); return;
            }
            if (path.equals("/testing/regrant")) {
                denied.clear(); reply(exchange, 200, Map.of()); return;
            }
            if (path.equals("/saml/v1/get-jwt")) {
                String callback = params(exchange.getRequestURI().getRawQuery()).get("returnUrl");
                String code = UUID.randomUUID().toString(); codes.put(code, token("login-alias"));
                exchange.getResponseHeaders().set("Location", callback + (callback.contains("?") ? "&" : "?") + "code=" + code);
                exchange.sendResponseHeaders(302, -1); exchange.close(); return;
            }
            if (path.equals("/saml/v1/token")) {
                String token = codes.remove(params(body).getOrDefault("code", ""));
                reply(exchange, 200, token == null ? Map.of("code", "INVALID") : Map.of("code", "0000", "data", Map.of("token", token)));
                return;
            }
            if (!"Bearer fixture-service".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                reply(exchange, 401, Map.of()); return;
            }
            var input = JSON.readTree(body);
            if (path.equals("/resolve") || path.equals("/users/resolve")) {
                if (failIdentity) { reply(exchange, 503, Map.of()); return; }
                String subject = input.path("subject").asString("");
                String userId = input.path("userId").asString("login-alias".equals(subject) ? "W9000001" : subject);
                if ("missing".equals(subject) || "NOT_FOUND".equals(identityResult)) { reply(exchange, 200, Map.of("result", "NOT_FOUND")); return; }
                Instant now = Instant.now();
                var snapshot = new StaffIdentitySnapshot(userId, "內勤測試員", userType, userId.equals(inactiveUserId) ? "INACTIVE" : status, dept,
                        List.of(dept), "test-revision", now.minusSeconds(staleIdentity ? 120 : 1), now.plusSeconds(expiredSnapshot ? -1 : 60));
                reply(exchange, 200, Map.of("result", identityResult, "snapshot", snapshot)); return;
            }
            if (path.equals("/delegations/check") && delegationEnd != null) {
                Instant now = Instant.now();
                reply(exchange, 200, new StaffDirectoryClient.Delegation(input.get("owner").asString(),
                        input.get("delegate").asString(), "ACTIVE", now, now.plusSeconds(60), now.minusSeconds(300), delegationEnd));
                return;
            }
            if (path.equals("/api/v1/sdk/permissions/check")) {
                if (!input.has("subject_ids") || input.has("effective_subject_ids")
                        || !"bpm".equals(input.path("system_code").asString())
                        || !"ALL".equals(input.path("mode").asString()) || input.path("subject_attributes").size() != 0)
                    throw new IllegalArgumentException("Wrong AuthZ contract");
                var subjects = new ArrayList<String>(); input.path("subject_ids").forEach(v -> subjects.add(v.asString())); lastSubjects = subjects;
                String permission = input.path("permissions").get(0).asString();
                Object allowed = malformedAllowed ? "true" : !denied.contains(permission) && (!APPROVE.equals(permission) || approvalOrg == null || subjects.contains(approvalOrg));
                reply(exchange, 200, Map.of("code", authzCode, "data", Map.of("allowed", allowed))); return;
            }
            reply(exchange, 404, Map.of());
        } catch (Exception e) { reply(exchange, 500, Map.of("error", "fixture failure")); }
    }
    private static Map<String, String> params(String query) {
        var out = new HashMap<String, String>();
        if (query != null) for (String pair : query.split("&")) {
            String[] parts = pair.split("=", 2);
            if (parts.length == 2) out.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8), URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
        }
        return out;
    }
    private static void reply(HttpExchange exchange, int status, Object body) throws java.io.IOException {
        byte[] bytes = JSON.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
    }
    @Override public void close() { server.stop(0); try { Files.deleteIfExists(publicKey); } catch (Exception ignored) {} }
}
