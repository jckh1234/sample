package com.example.externalapi.authorizer;

import com.amazonaws.services.lambda.runtime.events.APIGatewayCustomAuthorizerEvent;
import com.amazonaws.services.lambda.runtime.events.IamPolicyResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthorizerTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void stageWildcardCoversTheCachedStage() {
        String methodArn = "arn:aws:execute-api:us-east-1:123456789012:abc123/prod/GET/orders/42";
        assertEquals(
                "arn:aws:execute-api:us-east-1:123456789012:abc123/prod/*/*",
                AuthorizerSupport.stageWildcard(methodArn));
    }

    @Test
    void bearerTokenIsCaseInsensitiveAndDoesNotTreatOtherSchemesAsTokens() {
        assertEquals("access-token", AuthorizerSupport.bearerToken(Map.of("authorization", "bearer access-token")));
        assertEquals(null, AuthorizerSupport.bearerToken(Map.of("Authorization", "Basic abc")));
        assertEquals(null, AuthorizerSupport.bearerToken(Map.of()));
    }

    @Test
    void claimsAreStringsAndScpFallsBackToScope() throws Exception {
        JsonNode token = JSON.readTree("""
                {
                  "active": true,
                  "sub": "00u1",
                  "email": "user@example.com",
                  "scope": "openid items.read",
                  "aud": ["api://external", "other"],
                  "iss": "https://example.okta.com/oauth2/default/",
                  "exp": 1999999999,
                  "client_id": "client-app",
                  "preferred_username": "user@example.com",
                  "address": {"street": "hidden"}
                }
                """);
        Map<String, String> context = IntrospectionClaims.toContext(token);
        assertEquals("00u1", context.get("sub"));
        assertEquals("user@example.com", context.get("email"));
        assertEquals("openid items.read", context.get("scp"));
        assertEquals("openid items.read", context.get("scope"));
        assertEquals("api://external other", context.get("aud"));
        assertEquals("1999999999", context.get("exp"));
        assertEquals("client-app", context.get("cid"));
        assertEquals("user@example.com", context.get("username"));
        assertFalse(context.containsKey("address"));
        assertTrue(IntrospectionClaims.audienceMatches(token, "api://external"));
        assertTrue(IntrospectionClaims.issuerMatches(token, "https://example.okta.com/oauth2/default"));
        assertFalse(IntrospectionClaims.audienceMatches(token, "api://other-app"));
    }

    @Test
    void scpArrayIsJoined() throws Exception {
        JsonNode token = JSON.readTree("""
                {"active": true, "sub": "00u1", "scp": ["openid", "items.read"], "aud": "api://external"}
                """);
        assertEquals("openid items.read", IntrospectionClaims.toContext(token).get("scp"));
        assertTrue(IntrospectionClaims.audienceMatches(token, "api://external"));
    }

    @Test
    void allowPolicySerializesWithoutNullCondition() throws Exception {
        IamPolicyResponse policy = new RestApiAuthorizerPolicy(
                "00u1",
                "arn:aws:execute-api:us-east-1:123456789012:abc123/prod/*/*",
                Map.of("sub", "00u1", "scp", "openid items.read"));
        JsonNode tree = JSON.valueToTree(policy);
        assertEquals("00u1", tree.path("principalId").asText());
        assertEquals("2012-10-17", tree.at("/policyDocument/Version").asText());
        assertEquals("Allow", tree.at("/policyDocument/Statement/0/Effect").asText());
        assertEquals("execute-api:Invoke", tree.at("/policyDocument/Statement/0/Action").asText());
        assertTrue(tree.at("/policyDocument/Statement/0/Resource").toString().contains("prod/*/*"));
        assertFalse(tree.at("/policyDocument/Statement/0").has("Condition"));
        assertEquals("00u1", tree.at("/context/sub").asText());
        assertEquals("openid items.read", tree.at("/context/scp").asText());
    }

    @Test
    void missingBearerThrowsUnauthorized() {
        OktaAuthorizerHandler handler = new OktaAuthorizerHandler(config("http://127.0.0.1:9", ""), HttpExchanges.client(), Clock.systemUTC());
        APIGatewayCustomAuthorizerEvent event = APIGatewayCustomAuthorizerEvent.builder()
                .withMethodArn("arn:aws:execute-api:us-east-1:123456789012:abc123/prod/GET/orders")
                .withHeaders(Map.of("Accept", "application/json"))
                .build();
        UnauthorizedException denied = assertThrows(UnauthorizedException.class, () -> handler.handleRequest(event, null));
        assertEquals("Unauthorized", denied.getMessage());
    }

    @Test
    void introspectAllowCachesTheVaultSecret() throws Exception {
        AtomicInteger logins = new AtomicInteger();
        AtomicInteger reads = new AtomicInteger();
        AtomicInteger introspects = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            byte[] requestBody = exchange.getRequestBody().readAllBytes();
            byte[] response;
            int status = 200;
            if ("/v1/auth/approle/login".equals(path)) {
                logins.incrementAndGet();
                JsonNode login = JSON.readTree(requestBody);
                assertEquals("role-id", login.path("role_id").asText());
                assertEquals("secret-id", login.path("secret_id").asText());
                assertEquals("admin/external", exchange.getRequestHeaders().getFirst("X-Vault-Namespace"));
                response = """
                        {"auth":{"client_token":"vault-token"}}
                        """.getBytes(StandardCharsets.UTF_8);
            } else if ("/v1/secret/data/external-api/okta".equals(path)) {
                reads.incrementAndGet();
                assertEquals("vault-token", exchange.getRequestHeaders().getFirst("X-Vault-Token"));
                response = """
                        {"data":{"data":{"client_secret":"okta-client-secret"}}}
                        """.getBytes(StandardCharsets.UTF_8);
            } else if ("/oauth2/default/v1/introspect".equals(path)) {
                introspects.incrementAndGet();
                String form = new String(requestBody, StandardCharsets.UTF_8);
                Map<String, String> fields = formFields(form);
                assertEquals("access-token", fields.get("token"));
                assertEquals("access_token", fields.get("token_type_hint"));
                String basic = exchange.getRequestHeaders().getFirst("Authorization");
                String decoded = new String(Base64.getDecoder().decode(basic.substring("Basic ".length())), StandardCharsets.UTF_8);
                assertEquals("client-id:okta-client-secret", decoded);
                String issuer = "http://127.0.0.1:" + server.getAddress().getPort() + "/oauth2/default";
                response = ("""
                        {"active":true,"sub":"00u1","email":"user@example.com","scope":"openid items.read","aud":"api://external","iss":"%s","exp":1999999999}
                        """).formatted(issuer).getBytes(StandardCharsets.UTF_8);
            } else {
                status = 404;
                response = "{}".getBytes(StandardCharsets.UTF_8);
            }
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, response.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(response);
            }
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            OktaAuthorizerHandler handler = new OktaAuthorizerHandler(
                    config(base, "admin/external"),
                    HttpExchanges.client(),
                    Clock.systemUTC());
            IamPolicyResponse first = handler.handleRequest(allowEvent(), null);
            IamPolicyResponse second = handler.handleRequest(allowEvent(), null);
            assertEquals("00u1", first.getPrincipalId());
            assertEquals("user@example.com", String.valueOf(first.getContext().get("email")));
            assertEquals("openid items.read", second.getContext().get("scp"));
            assertEquals(2, introspects.get());
            assertEquals(1, logins.get());
            assertEquals(1, reads.get());
            String serialized = JSON.writeValueAsString(first);
            assertFalse(serialized.contains("access-token"));
            assertFalse(serialized.contains("okta-client-secret"));
            assertFalse(serialized.contains("secret-id"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void inactiveTokenThrowsUnauthorized() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] response;
            String path = exchange.getRequestURI().getPath();
            if (path.endsWith("/introspect")) {
                response = """
                        {"active":false}
                        """.getBytes(StandardCharsets.UTF_8);
            } else if (path.endsWith("/login")) {
                response = """
                        {"auth":{"client_token":"vault-token"}}
                        """.getBytes(StandardCharsets.UTF_8);
            } else {
                response = """
                        {"data":{"data":{"client_secret":"okta-client-secret"}}}
                        """.getBytes(StandardCharsets.UTF_8);
            }
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(response);
            }
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            OktaAuthorizerHandler handler = new OktaAuthorizerHandler(config(base, null), HttpExchanges.client(), Clock.systemUTC());
            UnauthorizedException denied = assertThrows(UnauthorizedException.class, () -> handler.handleRequest(allowEvent(), null));
            assertEquals("Unauthorized", denied.getMessage());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void vaultSecretIsRereadAfterTheTtl() throws Exception {
        MutableClock clock = new MutableClock();
        AtomicInteger reads = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            byte[] response = path.endsWith("/login")
                    ? """
                    {"auth":{"client_token":"vault-token"}}
                    """.getBytes(StandardCharsets.UTF_8)
                    : """
                    {"data":{"data":{"client_secret":"okta-client-secret"}}}
                    """.getBytes(StandardCharsets.UTF_8);
            if (path.contains("/data/")) {
                reads.incrementAndGet();
            }
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(response);
            }
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            AuthorizerConfig config = config(base, null, 300);
            VaultKvSecretCache cache = new VaultKvSecretCache(config, HttpExchanges.client(), clock);
            assertEquals("okta-client-secret", cache.clientSecret());
            assertEquals("okta-client-secret", cache.clientSecret());
            clock.advanceSeconds(299);
            assertEquals("okta-client-secret", cache.clientSecret());
            clock.advanceSeconds(2);
            assertEquals("okta-client-secret", cache.clientSecret());
            assertEquals(2, reads.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void failedVaultReadIsNotCached() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            int status = 200;
            byte[] response;
            if (path.endsWith("/login")) {
                response = """
                        {"auth":{"client_token":"vault-token"}}
                        """.getBytes(StandardCharsets.UTF_8);
            } else {
                int attempt = reads.incrementAndGet();
                if (attempt == 1) {
                    status = 500;
                    response = "{}".getBytes(StandardCharsets.UTF_8);
                } else {
                    response = """
                            {"data":{"data":{"client_secret":"okta-client-secret"}}}
                            """.getBytes(StandardCharsets.UTF_8);
                }
            }
            exchange.sendResponseHeaders(status, response.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(response);
            }
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            VaultKvSecretCache cache = new VaultKvSecretCache(config(base, null, 300), HttpExchanges.client(), Clock.systemUTC());
            assertThrows(IllegalStateException.class, cache::clientSecret);
            assertEquals("okta-client-secret", cache.clientSecret());
            assertEquals(2, reads.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void missingIssuerIsRejectedAtStartup() {
        Map<String, String> env = new LinkedHashMap<>(sampleEnv("http://127.0.0.1:9", null));
        env.remove("OKTA_ISSUER");
        IllegalStateException missing = assertThrows(IllegalStateException.class, () -> AuthorizerConfig.from(env));
        assertTrue(missing.getMessage().contains("OKTA_ISSUER"));
        assertFalse(missing.getMessage().contains("secret-id"));
    }

    private static APIGatewayCustomAuthorizerEvent allowEvent() {
        return APIGatewayCustomAuthorizerEvent.builder()
                .withType("REQUEST")
                .withMethodArn("arn:aws:execute-api:us-east-1:123456789012:abc123/prod/GET/orders/42")
                .withPath("/orders/42")
                .withHttpMethod("GET")
                .withResource("/{proxy+}")
                .withHeaders(Map.of("Authorization", "Bearer access-token"))
                .withQueryStringParameters(Map.of("status", "open"))
                .build();
    }

    private static AuthorizerConfig config(String base, String namespace) {
        return config(base, namespace, 300);
    }

    private static AuthorizerConfig config(String base, String namespace, int cacheSeconds) {
        return AuthorizerConfig.from(sampleEnv(base, namespace, cacheSeconds));
    }

    private static Map<String, String> sampleEnv(String base, String namespace) {
        return sampleEnv(base, namespace, 300);
    }

    private static Map<String, String> sampleEnv(String base, String namespace, int cacheSeconds) {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("OKTA_ISSUER", base + "/oauth2/default");
        env.put("OKTA_CLIENT_ID", "client-id");
        env.put("OKTA_AUDIENCE", "api://external");
        env.put("VAULT_ADDR", base);
        env.put("VAULT_ROLE_ID", "role-id");
        env.put("VAULT_SECRET_ID", "secret-id");
        env.put("VAULT_OKTA_SECRET_PATH", "secret/data/external-api/okta");
        env.put("VAULT_SECRET_CACHE_SECONDS", Integer.toString(cacheSeconds));
        if (namespace != null) {
            env.put("VAULT_NAMESPACE", namespace);
        }
        return env;
    }

    private static Map<String, String> formFields(String form) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (String part : form.split("&")) {
            String[] pair = part.split("=", 2);
            fields.put(
                    URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                    pair.length == 1 ? "" : URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
        }
        return fields;
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

        void advanceSeconds(long seconds) {
            now = now.plusSeconds(seconds);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
