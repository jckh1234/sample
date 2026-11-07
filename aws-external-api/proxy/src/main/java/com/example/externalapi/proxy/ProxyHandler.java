package com.example.externalapi.proxy;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Forwards an API Gateway proxy event to {@code UPSTREAM_BASE_URL}. The route's authorizer has already run;
 * this function reads {@code requestContext.authorizer} and does not call Okta. It overwrites identity headers
 * from that context so a caller cannot supply its own {@code X-User-Sub}.
 */
public final class ProxyHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS");
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(25);

    private final ProxyConfig config;
    private final HttpClient http;

    public ProxyHandler() {
        this(ProxyConfig.fromEnvironment(), httpClient());
    }

    ProxyHandler(ProxyConfig config, HttpClient http) {
        this.config = config;
        this.http = http;
    }

    static HttpClient httpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent event, Context context) {
        if (event == null) {
            return jsonError(400, "missing event");
        }
        String method = event.getHttpMethod() == null ? "" : event.getHttpMethod().toUpperCase(Locale.ROOT);
        if (!METHODS.contains(method)) {
            return jsonError(400, "unsupported method");
        }
        String subject = authorizerValue(event, "sub");
        if (subject == null) {
            log(context, "reject: missing authorizer sub");
            return jsonError(401, "missing authorizer subject");
        }
        URI upstream;
        try {
            upstream = ProxySupport.upstreamUri(config.upstreamBaseUrl(), ProxySupport.proxyPath(event), ProxySupport.query(event));
        } catch (IllegalArgumentException ex) {
            log(context, "reject: invalid path");
            return jsonError(400, "invalid path");
        }
        byte[] body;
        try {
            body = requestBody(event, method);
        } catch (IllegalArgumentException ex) {
            return jsonError(400, "invalid base64 body");
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(upstream)
                .timeout(REQUEST_TIMEOUT)
                .method(method, publisher(method, body));
        forwardHeaders(event).forEach(request::header);
        identityHeaders(event).forEach(request::header);
        try {
            HttpResponse<InputStream> response = http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = response.body()) {
                byte[] responseBody = in.readNBytes(ProxySupport.MAX_UPSTREAM_BYTES + 1);
                if (responseBody.length > ProxySupport.MAX_UPSTREAM_BYTES) {
                    log(context, "upstream response too large");
                    return jsonError(502, "upstream response too large");
                }
                log(context, method + " " + ProxySupport.proxyPath(event) + " -> " + response.statusCode());
                return toProxyResponse(response, responseBody);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log(context, "upstream interrupted");
            return jsonError(502, "upstream unavailable");
        } catch (IOException ex) {
            log(context, "upstream unavailable");
            return jsonError(502, "upstream unavailable");
        }
    }

    private static byte[] requestBody(APIGatewayProxyRequestEvent event, String method) {
        if ("GET".equals(method) || "HEAD".equals(method)) {
            return new byte[0];
        }
        String body = event.getBody();
        if (body == null || body.isEmpty()) {
            return new byte[0];
        }
        if (Boolean.TRUE.equals(event.getIsBase64Encoded())) {
            try {
                return Base64.getDecoder().decode(body);
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("invalid base64 body");
            }
        }
        return body.getBytes(StandardCharsets.UTF_8);
    }

    private static HttpRequest.BodyPublisher publisher(String method, byte[] body) {
        if ("GET".equals(method) || "HEAD".equals(method) || body.length == 0) {
            return HttpRequest.BodyPublishers.noBody();
        }
        return HttpRequest.BodyPublishers.ofByteArray(body);
    }

    private Map<String, String> forwardHeaders(APIGatewayProxyRequestEvent event) {
        Map<String, String> outbound = new LinkedHashMap<>();
        for (String allowed : config.forwardHeaders()) {
            HeaderMatch match = findHeader(event, allowed);
            if (match == null || match.value == null || match.value.isBlank()) {
                continue;
            }
            if (ProxySupport.blockedRequestHeader(allowed)) {
                continue;
            }
            outbound.put(ProxySupport.canonicalHeader(allowed, match.originalName), match.value);
        }
        return outbound;
    }

    private static Map<String, String> identityHeaders(APIGatewayProxyRequestEvent event) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (ProxySupport.ContextHeader mapping : ProxySupport.CONTEXT_HEADERS) {
            String value = authorizerValue(event, mapping.contextKey());
            if (value != null) {
                headers.put(mapping.headerName(), value);
            }
        }
        return headers;
    }

    private static String authorizerValue(APIGatewayProxyRequestEvent event, String key) {
        if (event.getRequestContext() == null || event.getRequestContext().getAuthorizer() == null) {
            return null;
        }
        return ProxySupport.contextValue(event.getRequestContext().getAuthorizer().get(key));
    }

    private static HeaderMatch findHeader(APIGatewayProxyRequestEvent event, String lowerName) {
        Map<String, List<String>> multi = event.getMultiValueHeaders();
        if (multi != null) {
            for (Map.Entry<String, List<String>> entry : multi.entrySet()) {
                if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(lowerName) && entry.getValue() != null) {
                    String joined = String.join(", ", entry.getValue());
                    return new HeaderMatch(entry.getKey(), joined);
                }
            }
        }
        Map<String, String> headers = event.getHeaders();
        if (headers == null) {
            return null;
        }
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(lowerName)) {
                return new HeaderMatch(entry.getKey(), entry.getValue());
            }
        }
        return null;
    }

    private static APIGatewayProxyResponseEvent toProxyResponse(HttpResponse<InputStream> response, byte[] body) {
        String contentType = response.headers().firstValue("content-type").orElse(null);
        boolean text = ProxySupport.textual(contentType);
        Map<String, String> headers = new LinkedHashMap<>();
        for (String name : ProxySupport.RESPONSE_HEADERS) {
            response.headers().firstValue(name).ifPresent(value ->
                    headers.put(ProxySupport.canonicalHeader(name, name), value));
        }
        APIGatewayProxyResponseEvent event = new APIGatewayProxyResponseEvent()
                .withStatusCode(response.statusCode())
                .withHeaders(headers)
                .withIsBase64Encoded(!text);
        if (text) {
            return event.withBody(new String(body, StandardCharsets.UTF_8));
        }
        return event.withBody(Base64.getEncoder().encodeToString(body));
    }

    private static APIGatewayProxyResponseEvent jsonError(int status, String message) {
        return new APIGatewayProxyResponseEvent()
                .withStatusCode(status)
                .withHeaders(Map.of("Content-Type", "application/json"))
                .withBody(ProxySupport.errorBody(message))
                .withIsBase64Encoded(false);
    }

    private static void log(Context context, String message) {
        String line = "[proxy] " + message + "\n";
        if (context != null && context.getLogger() != null) {
            context.getLogger().log(line);
            return;
        }
        System.err.print(line);
    }

    private record HeaderMatch(String originalName, String value) {
    }
}
