package com.example.externalapi.authorizer;

import com.amazonaws.services.lambda.runtime.events.APIGatewayCustomAuthorizerEvent;
import com.amazonaws.services.lambda.runtime.events.IamPolicyResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs {@link OktaAuthorizerHandler} against {@code events/authorizer-request.json}.
 * Vault and Okta are a local {@link HttpServer}. Nothing leaves the machine.
 */
class AuthorizerEventFileTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void activeTokenFromEventFileAllowsAndCopiesContext() throws Exception {
        AtomicInteger logins = new AtomicInteger();
        AtomicInteger reads = new AtomicInteger();
        AtomicInteger introspects = new AtomicInteger();
        try (LocalOktaVault vault = new LocalOktaVault(true, 200, logins, reads, introspects)) {
            OktaAuthorizerHandler handler = vault.handler();
            APIGatewayCustomAuthorizerEvent event = SampleEvents.load(
                    "authorizer-request.json", APIGatewayCustomAuthorizerEvent.class);

            IamPolicyResponse policy = handler.handleRequest(event, null);

            assertEquals("00u-from-okta", policy.getPrincipalId());
            assertEquals("00u-from-okta", policy.getContext().get("sub"));
            assertEquals("user@example.com", policy.getContext().get("email"));
            assertEquals("openid items.read", policy.getContext().get("scp"));
            assertEquals("orders/42", event.getPathParameters().get("proxy"));
            assertEquals("open", event.getQueryStringParameters().get("status"));
            JsonNode tree = JSON.valueToTree(policy);
            assertEquals("Allow", tree.at("/policyDocument/Statement/0/Effect").asText());
            assertTrue(tree.at("/policyDocument/Statement/0/Resource").toString().contains("/prod/*/*"));
            String serialized = JSON.writeValueAsString(policy);
            assertFalse(serialized.contains("REPLACE_WITH_ACCESS_TOKEN"));
            assertFalse(serialized.contains("okta-client-secret"));
            assertEquals(1, logins.get());
            assertEquals(1, reads.get());
            assertEquals(1, introspects.get());
            IamLoginFixture.assertLogin(vault.lastLogin());
        }
    }

    @Test
    void inactiveIntrospectFromEventFileThrowsUnauthorized() throws Exception {
        try (LocalOktaVault vault = new LocalOktaVault(false, 200, new AtomicInteger(), new AtomicInteger(), new AtomicInteger())) {
            OktaAuthorizerHandler handler = vault.handler();
            APIGatewayCustomAuthorizerEvent event = SampleEvents.load(
                    "authorizer-request.json", APIGatewayCustomAuthorizerEvent.class);

            UnauthorizedException denied = assertThrows(UnauthorizedException.class, () -> handler.handleRequest(event, null));
            assertEquals("Unauthorized", denied.getMessage());
        }
    }

    @Test
    void failedIntrospectFromEventFileThrowsUnauthorized() throws Exception {
        try (LocalOktaVault vault = new LocalOktaVault(false, 400, new AtomicInteger(), new AtomicInteger(), new AtomicInteger())) {
            OktaAuthorizerHandler handler = vault.handler();
            APIGatewayCustomAuthorizerEvent event = SampleEvents.load(
                    "authorizer-request.json", APIGatewayCustomAuthorizerEvent.class);

            UnauthorizedException denied = assertThrows(UnauthorizedException.class, () -> handler.handleRequest(event, null));
            assertEquals("Unauthorized", denied.getMessage());
        }
    }

    private static final class LocalOktaVault implements AutoCloseable {
        private final HttpServer server;
        private final String base;
        private volatile JsonNode lastLogin;

        LocalOktaVault(boolean active, int introspectStatus, AtomicInteger logins, AtomicInteger reads, AtomicInteger introspects)
                throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                byte[] requestBody = exchange.getRequestBody().readAllBytes();
                byte[] response;
                int status = 200;
                if ("/v1/auth/aws/login".equals(path)) {
                    logins.incrementAndGet();
                    JsonNode login = JSON.readTree(requestBody);
                    lastLogin = login;
                    if (!IamLoginFixture.VAULT_ROLE.equals(login.path("role").asText())
                            || !"POST".equals(login.path("iam_http_request_method").asText())) {
                        status = 401;
                    }
                    response = """
                            {"auth":{"client_token":"vault-token"}}
                            """.getBytes(StandardCharsets.UTF_8);
                } else if ("/v1/secret/data/external-api/okta".equals(path)) {
                    reads.incrementAndGet();
                    if (!"vault-token".equals(exchange.getRequestHeaders().getFirst("X-Vault-Token"))) {
                        status = 403;
                    }
                    response = """
                            {"data":{"data":{"client_secret":"okta-client-secret"}}}
                            """.getBytes(StandardCharsets.UTF_8);
                } else if ("/oauth2/default/v1/introspect".equals(path)) {
                    introspects.incrementAndGet();
                    Map<String, String> fields = formFields(new String(requestBody, StandardCharsets.UTF_8));
                    String basic = exchange.getRequestHeaders().getFirst("Authorization");
                    String decoded = new String(Base64.getDecoder().decode(basic.substring("Basic ".length())), StandardCharsets.UTF_8);
                    if (!"REPLACE_WITH_ACCESS_TOKEN".equals(fields.get("token"))
                            || !"access_token".equals(fields.get("token_type_hint"))
                            || !"client-id:okta-client-secret".equals(decoded)) {
                        status = 400;
                        response = """
                                {"error":"invalid_request"}
                                """.getBytes(StandardCharsets.UTF_8);
                    } else if (introspectStatus != 200) {
                        status = introspectStatus;
                        response = """
                                {"error":"invalid_token"}
                                """.getBytes(StandardCharsets.UTF_8);
                    } else {
                        String issuer = "http://127.0.0.1:" + server.getAddress().getPort() + "/oauth2/default";
                        response = ("""
                                {"active":%s,"sub":"00u-from-okta","email":"user@example.com","scope":"openid items.read","aud":"api://external","iss":"%s","exp":1999999999}
                                """).formatted(active, issuer).getBytes(StandardCharsets.UTF_8);
                    }
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
            base = "http://127.0.0.1:" + server.getAddress().getPort();
        }

        JsonNode lastLogin() {
            return lastLogin;
        }

        OktaAuthorizerHandler handler() {
            Map<String, String> env = new LinkedHashMap<>();
            env.put("OKTA_ISSUER", base + "/oauth2/default");
            env.put("OKTA_CLIENT_ID", "client-id");
            env.put("OKTA_AUDIENCE", "api://external");
            env.put("VAULT_ADDR", base);
            env.put("VAULT_AWS_AUTH_ROLE", IamLoginFixture.VAULT_ROLE);
            env.put("VAULT_AUTH_MOUNT", "aws");
            env.put("VAULT_OKTA_SECRET_PATH", "secret/data/external-api/okta");
            env.put("VAULT_SECRET_CACHE_SECONDS", "0");
            return new OktaAuthorizerHandler(
                    AuthorizerConfig.from(env), HttpExchanges.client(), Clock.systemUTC(), IamLoginFixture.signer());
        }

        @Override
        public void close() {
            server.stop(0);
        }
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
}
