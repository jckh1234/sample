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
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs {@link ProxyHandler} against {@code events/proxy-request.json}.
 * The upstream is a local {@link HttpServer}. Nothing leaves the machine.
 */
class ProxyEventFileTest {
    @Test
    void eventFileForwardsMethodPathQueryAndUserSub() throws Exception {
        AtomicReference<Captured> captured = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            captured.set(Captured.from(exchange));
            byte[] response = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(response);
            }
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            ProxyHandler handler = new ProxyHandler(
                    ProxyConfig.from(Map.of("UPSTREAM_BASE_URL", base)),
                    ProxyHandler.httpClient());
            APIGatewayProxyRequestEvent event = SampleEvents.load("proxy-request.json", APIGatewayProxyRequestEvent.class);

            APIGatewayProxyResponseEvent response = handler.handleRequest(event, null);

            assertEquals(200, response.getStatusCode());
            assertEquals("{\"ok\":true}", response.getBody());
            assertEquals(Boolean.FALSE, response.getIsBase64Encoded());
            Captured upstream = captured.get();
            assertEquals("GET", upstream.method);
            assertEquals("/orders/42", upstream.path);
            assertEquals("status=open", upstream.query);
            assertEquals("00u1example", upstream.headers.getFirst("X-User-Sub"));
            assertEquals("user@example.com", upstream.headers.getFirst("X-User-Email"));
            assertEquals("openid items.read", upstream.headers.getFirst("X-User-Scp"));
            assertEquals("application/json", upstream.headers.getFirst("Accept"));
            assertNull(upstream.headers.getFirst("Authorization"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void eventFileUpstreamFailureReturns502() throws Exception {
        ProxyHandler handler = new ProxyHandler(
                ProxyConfig.from(Map.of("UPSTREAM_BASE_URL", "http://127.0.0.1:9")),
                ProxyHandler.httpClient());
        APIGatewayProxyRequestEvent event = SampleEvents.load("proxy-request.json", APIGatewayProxyRequestEvent.class);

        APIGatewayProxyResponseEvent response = handler.handleRequest(event, null);

        assertEquals(502, response.getStatusCode());
        assertTrue(response.getBody().contains("upstream unavailable"));
    }

    private record Captured(String method, String path, String query, com.sun.net.httpserver.Headers headers) {
        static Captured from(HttpExchange exchange) throws IOException {
            exchange.getRequestBody().readAllBytes();
            return new Captured(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().getPath(),
                    exchange.getRequestURI().getRawQuery(),
                    exchange.getRequestHeaders());
        }
    }
}
