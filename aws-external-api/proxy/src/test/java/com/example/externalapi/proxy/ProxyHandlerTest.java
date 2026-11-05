package com.example.externalapi.proxy;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProxyHandlerTest {
    @Test
    void buildsUpstreamUriFromProxyPathAndRepeatedQuery() {
        String uri = ProxySupport.upstreamUri(
                "https://internal.example.net/api/",
                "orders/my file",
                Map.of("status", List.of("open", "hold"), "q", List.of("a b"))).toString();
        assertTrue(uri.startsWith("https://internal.example.net/api/orders/my%20file?"));
        assertEquals(
                "https://internal.example.net/orders",
                ProxySupport.upstreamUri("https://internal.example.net/", "orders", Map.of()).toString());
        assertEquals(
                "https://internal.example.net/",
                ProxySupport.upstreamUri("https://internal.example.net", "", Map.of()).toString());
        assertTrue(uri.contains("status=open"));
        assertTrue(uri.contains("status=hold"));
        assertTrue(uri.contains("q=a%20b"));
    }

    @Test
    void rejectsUserinfoAndParentSegments() {
        assertThrows(IllegalArgumentException.class, () ->
                ProxySupport.upstreamUri("https://user:secret@internal.example.net/api", "orders", Map.of()));
        assertThrows(IllegalArgumentException.class, () ->
                ProxySupport.upstreamUri("https://internal.example.net/api", "orders/../../etc", Map.of()));
    }

    @Test
    void stripsStageWhenProxyParameterIsAbsent() {
        APIGatewayProxyRequestEvent event = new APIGatewayProxyRequestEvent()
                .withPath("/prod/orders/42")
                .withRequestContext(new APIGatewayProxyRequestEvent.ProxyRequestContext().withStage("prod"));
        assertEquals("orders/42", ProxySupport.proxyPath(event));
    }

    @Test
    void forwardsMethodPathQueryBodyAndReplacesIdentityHeaders() throws Exception {
        AtomicReference<Captured> captured = new AtomicReference<>();
        HttpServer server = server(exchange -> {
            captured.set(Captured.from(exchange));
            byte[] response = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("X-Request-Id", "req-9");
            exchange.getResponseHeaders().set("Set-Cookie", "session=hidden");
            exchange.sendResponseHeaders(201, response.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(response);
            }
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/api";
            ProxyHandler handler = new ProxyHandler(ProxyConfig.from(Map.of("UPSTREAM_BASE_URL", base)), ProxyHandler.httpClient());
            APIGatewayProxyRequestEvent event = new APIGatewayProxyRequestEvent()
                    .withHttpMethod("Post")
                    .withPath("/orders")
                    .withPathParameters(Map.of("proxy", "orders"))
                    .withHeaders(Map.of(
                            "Content-Type", "application/json",
                            "Authorization", "Bearer caller-token",
                            "X-User-Sub", "spoofed",
                            "Accept", "application/json"))
                    .withQueryStringParameters(Map.of("status", "open"))
                    .withBody("{\"a\":1}")
                    .withIsBase64Encoded(false);
            APIGatewayProxyRequestEvent.ProxyRequestContext requestContext = new APIGatewayProxyRequestEvent.ProxyRequestContext();
            requestContext.setStage("prod");
            requestContext.setAuthorizer(new HashMap<>(Map.of(
                    "sub", "00u1",
                    "email", "user@example.com",
                    "scp", "openid items.read")));
            event.setRequestContext(requestContext);

            APIGatewayProxyResponseEvent response = handler.handleRequest(event, null);
            assertEquals(201, response.getStatusCode());
            assertEquals("{\"ok\":true}", response.getBody());
            assertEquals(Boolean.FALSE, response.getIsBase64Encoded());
            assertEquals("application/json", response.getHeaders().get("Content-Type"));
            assertEquals("req-9", response.getHeaders().get("X-Request-Id"));
            assertFalse(response.getHeaders().containsKey("Set-Cookie"));

            Captured upstream = captured.get();
            assertEquals("POST", upstream.method);
            assertEquals("/api/orders", upstream.path);
            assertEquals("status=open", upstream.query);
            assertEquals("{\"a\":1}", upstream.body);
            assertEquals("application/json", upstream.headers.getFirst("Content-Type"));
            assertEquals("application/json", upstream.headers.getFirst("Accept"));
            assertEquals("00u1", upstream.headers.getFirst("X-User-Sub"));
            assertEquals("user@example.com", upstream.headers.getFirst("X-User-Email"));
            assertEquals("openid items.read", upstream.headers.getFirst("X-User-Scp"));
            assertNull(upstream.headers.getFirst("Authorization"));
            assertFalse("spoofed".equals(upstream.headers.getFirst("X-User-Sub")));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void missingAuthorizerSubjectDoesNotCallUpstream() throws Exception {
        AtomicReference<String> called = new AtomicReference<>();
        HttpServer server = server(exchange -> {
            called.set(exchange.getRequestURI().getPath());
            byte[] response = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(response);
            }
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            ProxyHandler handler = new ProxyHandler(ProxyConfig.from(Map.of("UPSTREAM_BASE_URL", base)), ProxyHandler.httpClient());
            APIGatewayProxyResponseEvent response = handler.handleRequest(
                    new APIGatewayProxyRequestEvent().withHttpMethod("GET").withPathParameters(Map.of("proxy", "orders")),
                    null);
            assertEquals(401, response.getStatusCode());
            assertTrue(response.getBody().contains("missing authorizer subject"));
            assertNull(called.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void binaryUpstreamBodyIsBase64AndRedirectsAreNotFollowed() throws Exception {
        HttpServer server = server(exchange -> {
            if ("/file".equals(exchange.getRequestURI().getPath())) {
                byte[] response = new byte[] {0, 1, 2, (byte) 255};
                exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
                exchange.sendResponseHeaders(200, response.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(response);
                }
                return;
            }
            exchange.getResponseHeaders().set("Location", "/file");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            ProxyHandler handler = new ProxyHandler(ProxyConfig.from(Map.of("UPSTREAM_BASE_URL", base)), ProxyHandler.httpClient());
            APIGatewayProxyRequestEvent redirect = authorized("GET", "go");
            APIGatewayProxyResponseEvent redirectResponse = handler.handleRequest(redirect, null);
            assertEquals(302, redirectResponse.getStatusCode());
            assertEquals("/file", redirectResponse.getHeaders().get("Location"));

            APIGatewayProxyResponseEvent fileResponse = handler.handleRequest(authorized("GET", "file"), null);
            assertEquals(200, fileResponse.getStatusCode());
            assertEquals(Boolean.TRUE, fileResponse.getIsBase64Encoded());
            assertEquals(Base64.getEncoder().encodeToString(new byte[] {0, 1, 2, (byte) 255}), fileResponse.getBody());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void upstreamOutageReturns502() {
        ProxyHandler handler = new ProxyHandler(
                ProxyConfig.from(Map.of("UPSTREAM_BASE_URL", "http://127.0.0.1:9")),
                ProxyHandler.httpClient());
        APIGatewayProxyResponseEvent response = handler.handleRequest(authorized("GET", "orders"), null);
        assertEquals(502, response.getStatusCode());
        assertTrue(response.getBody().contains("upstream unavailable"));
    }

    private static APIGatewayProxyRequestEvent authorized(String method, String proxy) {
        APIGatewayProxyRequestEvent event = new APIGatewayProxyRequestEvent()
                .withHttpMethod(method)
                .withPathParameters(Map.of("proxy", proxy));
        APIGatewayProxyRequestEvent.ProxyRequestContext requestContext = new APIGatewayProxyRequestEvent.ProxyRequestContext();
        requestContext.setAuthorizer(new HashMap<>(Map.of("sub", "00u1")));
        event.setRequestContext(requestContext);
        return event;
    }

    private static HttpServer server(com.sun.net.httpserver.HttpHandler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", handler);
        return server;
    }

    private record Captured(String method, String path, String query, String body, com.sun.net.httpserver.Headers headers) {
        static Captured from(HttpExchange exchange) throws IOException {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String query = exchange.getRequestURI().getRawQuery();
            return new Captured(exchange.getRequestMethod(), exchange.getRequestURI().getPath(), query, body, exchange.getRequestHeaders());
        }
    }
}
