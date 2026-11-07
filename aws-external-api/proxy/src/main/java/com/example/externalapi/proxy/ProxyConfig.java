package com.example.externalapi.proxy;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class ProxyConfig {
    static final List<String> DEFAULT_FORWARD_HEADERS = List.of(
            "content-type",
            "accept",
            "accept-language",
            "x-request-id",
            "x-correlation-id");

    private final String upstreamBaseUrl;
    private final List<String> forwardHeaders;

    private ProxyConfig(String upstreamBaseUrl, List<String> forwardHeaders) {
        this.upstreamBaseUrl = upstreamBaseUrl;
        this.forwardHeaders = forwardHeaders;
    }

    static ProxyConfig fromEnvironment() {
        return from(System.getenv());
    }

    static ProxyConfig from(Map<String, String> env) {
        String baseUrl = required(env, "UPSTREAM_BASE_URL");
        ProxySupport.upstreamUri(baseUrl, "", Map.of());
        return new ProxyConfig(baseUrl, forwardHeaders(env));
    }

    String upstreamBaseUrl() {
        return upstreamBaseUrl;
    }

    List<String> forwardHeaders() {
        return forwardHeaders;
    }

    private static List<String> forwardHeaders(Map<String, String> env) {
        String raw = optional(env, "FORWARD_HEADERS");
        if (raw == null) {
            return DEFAULT_FORWARD_HEADERS;
        }
        List<String> parsed = new ArrayList<>();
        for (String part : raw.split(",")) {
            String header = part.trim().toLowerCase(Locale.ROOT);
            if (!header.isEmpty() && !ProxySupport.blockedRequestHeader(header)) {
                parsed.add(header);
            }
        }
        return List.copyOf(parsed);
    }

    private static String required(Map<String, String> env, String key) {
        String value = optional(env, key);
        if (value == null) {
            throw new IllegalStateException("missing environment variable " + key);
        }
        return value;
    }

    private static String optional(Map<String, String> env, String key) {
        if (env == null) {
            return null;
        }
        String value = env.get(key);
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
