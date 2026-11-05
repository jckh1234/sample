package com.example.externalapi.authorizer;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;

/**
 * Copies a fixed set of introspect claims into authorizer context. API Gateway context values have to be
 * strings, numbers, or booleans, and this sample uses strings only. Nested objects are skipped. Arrays of
 * scalars are joined with a single space ({@code scp} is usually an array on an Okta access token).
 */
final class IntrospectionClaims {
    private IntrospectionClaims() {
    }

    static Map<String, String> toContext(JsonNode token) {
        Map<String, String> context = new LinkedHashMap<>();
        put(context, "sub", text(token.get("sub")));
        put(context, "email", text(token.get("email")));
        put(context, "uid", text(token.get("uid")));
        put(context, "username", firstText(token, "username", "preferred_username"));
        String scope = text(token.get("scope"));
        String scp = stringClaim(token.get("scp"));
        if (scp == null) {
            scp = scope;
        }
        put(context, "scp", scp);
        put(context, "scope", scope);
        put(context, "cid", firstText(token, "cid", "client_id"));
        put(context, "aud", stringClaim(token.get("aud")));
        put(context, "iss", text(token.get("iss")));
        JsonNode exp = token.get("exp");
        if (exp != null && exp.isNumber()) {
            put(context, "exp", exp.asText());
        }
        return context;
    }

    static boolean active(JsonNode token) {
        return token != null && token.path("active").isBoolean() && token.path("active").asBoolean();
    }

    static boolean issuerMatches(JsonNode token, String expectedIssuer) {
        String actual = text(token.get("iss"));
        if (actual == null) {
            return false;
        }
        return AuthorizerSupport.normalizeIssuer(actual).equals(AuthorizerSupport.normalizeIssuer(expectedIssuer));
    }

    /** A blank expected audience disables the check. */
    static boolean audienceMatches(JsonNode token, String expectedAudience) {
        if (expectedAudience == null || expectedAudience.isBlank()) {
            return true;
        }
        JsonNode aud = token.get("aud");
        if (aud == null || aud.isNull()) {
            return false;
        }
        if (aud.isTextual()) {
            return expectedAudience.equals(aud.asText());
        }
        if (aud.isArray()) {
            for (JsonNode entry : aud) {
                if (expectedAudience.equals(entry.asText())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void put(Map<String, String> context, String key, String value) {
        if (value != null && !value.isBlank()) {
            context.put(key, value);
        }
    }

    private static String firstText(JsonNode token, String primary, String fallback) {
        String value = text(token.get(primary));
        return value != null ? value : text(token.get(fallback));
    }

    private static String text(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            String value = node.asText();
            return value.isBlank() ? null : value;
        }
        return null;
    }

    private static String stringClaim(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return text(node);
        }
        if (node.isNumber() || node.isBoolean()) {
            return node.asText();
        }
        if (node.isArray()) {
            StringJoiner joiner = new StringJoiner(" ");
            for (JsonNode entry : node) {
                if (entry.isTextual() || entry.isNumber() || entry.isBoolean()) {
                    String value = entry.asText();
                    if (!value.isBlank()) {
                        joiner.add(value);
                    }
                }
            }
            String joined = joiner.toString();
            return joined.isBlank() ? null : joined;
        }
        return null;
    }
}
