package com.example.externalapi.proxy;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class ProxySupport {
    static final ObjectMapper JSON = new ObjectMapper();
    static final int MAX_UPSTREAM_BYTES = 5_000_000;
    static final Set<String> BLOCKED_REQUEST_HEADERS = Set.of(
            "authorization",
            "cookie",
            "host",
            "content-length",
            "connection",
            "transfer-encoding",
            "x-user-sub",
            "x-user-email",
            "x-user-scp",
            "x-user-scope",
            "x-user-uid",
            "x-user-name");

    private static final Map<String, String> CANONICAL_HEADERS = Map.ofEntries(
            Map.entry("content-type", "Content-Type"),
            Map.entry("accept", "Accept"),
            Map.entry("accept-language", "Accept-Language"),
            Map.entry("x-request-id", "X-Request-Id"),
            Map.entry("x-correlation-id", "X-Correlation-Id"),
            Map.entry("cache-control", "Cache-Control"),
            Map.entry("etag", "ETag"),
            Map.entry("last-modified", "Last-Modified"),
            Map.entry("location", "Location"));

    static final List<String> RESPONSE_HEADERS = List.of(
            "content-type",
            "cache-control",
            "etag",
            "last-modified",
            "location",
            "x-request-id",
            "x-correlation-id");

    static final List<ContextHeader> CONTEXT_HEADERS = List.of(
            new ContextHeader("sub", "X-User-Sub"),
            new ContextHeader("email", "X-User-Email"),
            new ContextHeader("scp", "X-User-Scp"),
            new ContextHeader("scope", "X-User-Scope"),
            new ContextHeader("uid", "X-User-Uid"),
            new ContextHeader("username", "X-User-Name"));

    private ProxySupport() {
    }

    static boolean blockedRequestHeader(String lowerName) {
        return BLOCKED_REQUEST_HEADERS.contains(lowerName);
    }

    static String canonicalHeader(String lowerName, String originalName) {
        return CANONICAL_HEADERS.getOrDefault(lowerName, originalName);
    }

    /**
     * Prefer the greedy {@code {proxy+}} value. It does not include the stage. The fallback strips a leading
     * {@code /{stage}} from {@code path} when the event was not produced by a proxy resource.
     */
    static String proxyPath(APIGatewayProxyRequestEvent event) {
        if (event.getPathParameters() != null) {
            String proxy = event.getPathParameters().get("proxy");
            if (proxy != null) {
                return proxy;
            }
        }
        String path = event.getPath() == null ? "" : event.getPath();
        String stage = event.getRequestContext() == null ? null : event.getRequestContext().getStage();
        if (stage != null && !stage.isBlank()) {
            String prefix = "/" + stage;
            if (path.equals(prefix)) {
                return "";
            }
            if (path.startsWith(prefix + "/")) {
                return path.substring(prefix.length() + 1);
            }
        }
        return path.startsWith("/") ? path.substring(1) : path;
    }

    static Map<String, List<String>> query(APIGatewayProxyRequestEvent event) {
        if (event.getMultiValueQueryStringParameters() != null) {
            return event.getMultiValueQueryStringParameters();
        }
        Map<String, String> single = event.getQueryStringParameters();
        if (single == null || single.isEmpty()) {
            return Map.of();
        }
        Map<String, List<String>> query = new LinkedHashMap<>();
        single.forEach((key, value) -> query.put(key, value == null ? List.of() : List.of(value)));
        return query;
    }

    static URI upstreamUri(String baseUrl, String proxyPath, Map<String, List<String>> query) {
        URI base = URI.create(baseUrl.trim());
        if (base.getScheme() == null || base.getHost() == null) {
            throw new IllegalArgumentException("UPSTREAM_BASE_URL must be an absolute http(s) URL");
        }
        if (!base.getScheme().equalsIgnoreCase("http") && !base.getScheme().equalsIgnoreCase("https")) {
            throw new IllegalArgumentException("UPSTREAM_BASE_URL scheme must be http or https");
        }
        if (base.getUserInfo() != null) {
            throw new IllegalArgumentException("UPSTREAM_BASE_URL must not include userinfo");
        }
        if (base.getRawQuery() != null || base.getRawFragment() != null) {
            throw new IllegalArgumentException("UPSTREAM_BASE_URL must not include a query or fragment");
        }
        String extra = normalizeProxyPath(proxyPath);
        String basePath = base.getRawPath() == null ? "" : stripTrailingSlash(base.getRawPath());
        String path = extra.isEmpty() ? (basePath.isEmpty() ? "/" : basePath) : basePath + "/" + encodePath(extra);
        String host = base.getHost();
        if (host.indexOf(':') >= 0) {
            host = "[" + host + "]";
        }
        StringBuilder raw = new StringBuilder();
        raw.append(base.getScheme()).append("://").append(host);
        if (base.getPort() != -1) {
            raw.append(':').append(base.getPort());
        }
        raw.append(path);
        String encodedQuery = encodeQuery(query);
        if (!encodedQuery.isEmpty()) {
            raw.append('?').append(encodedQuery);
        }
        return URI.create(raw.toString());
    }

    static String contextValue(Object value) {
        if (value instanceof String text) {
            return text.isBlank() ? null : text;
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        return null;
    }

    static boolean textual(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return true;
        }
        String type = contentType.toLowerCase(Locale.ROOT);
        int separator = type.indexOf(';');
        if (separator >= 0) {
            type = type.substring(0, separator).trim();
        }
        return type.startsWith("text/")
                || type.endsWith("+json")
                || type.endsWith("+xml")
                || type.equals("application/json")
                || type.equals("application/xml")
                || type.equals("application/javascript");
    }

    static String errorBody(String message) {
        try {
            return JSON.writeValueAsString(Map.of("message", message));
        } catch (JsonProcessingException ex) {
            return "{\"message\":\"proxy error\"}";
        }
    }

    private static String normalizeProxyPath(String proxyPath) {
        if (proxyPath == null || proxyPath.isBlank()) {
            return "";
        }
        String path = proxyPath.trim();
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        if (path.indexOf('\\') >= 0 || path.contains("://")) {
            throw new IllegalArgumentException("invalid proxy path");
        }
        for (String segment : path.split("/", -1)) {
            if ("..".equals(segment)) {
                throw new IllegalArgumentException("invalid proxy path");
            }
        }
        return path;
    }

    private static String encodePath(String path) {
        String[] parts = path.split("/", -1);
        StringBuilder encoded = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                encoded.append('/');
            }
            encoded.append(URLEncoder.encode(parts[i], StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return encoded.toString();
    }

    private static String encodeQuery(Map<String, List<String>> query) {
        if (query == null || query.isEmpty()) {
            return "";
        }
        StringBuilder encoded = new StringBuilder();
        for (Map.Entry<String, List<String>> entry : query.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            List<String> values = entry.getValue() == null ? List.of("") : entry.getValue();
            if (values.isEmpty()) {
                appendQuery(encoded, entry.getKey(), "");
                continue;
            }
            for (String value : values) {
                appendQuery(encoded, entry.getKey(), value == null ? "" : value);
            }
        }
        return encoded.toString();
    }

    private static void appendQuery(StringBuilder encoded, String key, String value) {
        if (encoded.length() > 0) {
            encoded.append('&');
        }
        encoded.append(URLEncoder.encode(key, StandardCharsets.UTF_8).replace("+", "%20"));
        encoded.append('=');
        encoded.append(URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"));
    }

    private static String stripTrailingSlash(String value) {
        String trimmed = value;
        while (trimmed.endsWith("/") && !trimmed.isEmpty()) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    record ContextHeader(String contextKey, String headerName) {
    }
}
