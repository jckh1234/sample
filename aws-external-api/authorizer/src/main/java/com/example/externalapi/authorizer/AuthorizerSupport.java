package com.example.externalapi.authorizer;

import com.amazonaws.services.lambda.runtime.Context;

import java.util.Map;

final class AuthorizerSupport {
    private AuthorizerSupport() {
    }

/**
 * API Gateway caches the Allow policy for ReauthorizeEvery seconds and reuses it for every request
 * with the same Authorization header. The returned resource therefore allows every method and path
 * on that stage. A policy that names only the current methodArn would deny the next path until the
 * gateway cache expires.
 */
    static String stageWildcard(String methodArn) {
        if (methodArn == null || methodArn.isBlank()) {
            throw new IllegalStateException("methodArn is missing");
        }
        int slash = methodArn.indexOf('/');
        if (slash < 0) {
            throw new IllegalStateException("methodArn has no stage");
        }
        String rest = methodArn.substring(slash + 1);
        int stageSlash = rest.indexOf('/');
        String stage = stageSlash < 0 ? rest : rest.substring(0, stageSlash);
        if (stage.isEmpty()) {
            throw new IllegalStateException("methodArn has no stage");
        }
        return methodArn.substring(0, slash) + "/" + stage + "/*/*";
    }

    static String normalizeIssuer(String issuer) {
        if (issuer == null) {
            return "";
        }
        String trimmed = issuer.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    static String bearerToken(Map<String, String> headers) {
        String authorization = header(headers, "Authorization");
        if (authorization == null) {
            return null;
        }
        String trimmed = authorization.trim();
        if (trimmed.length() < "Bearer ".length() || !trimmed.regionMatches(true, 0, "Bearer ", 0, "Bearer ".length())) {
            return null;
        }
        String token = trimmed.substring("Bearer ".length()).trim();
        return token.isEmpty() ? null : token;
    }

    static String header(Map<String, String> headers, String name) {
        if (headers == null || name == null) {
            return null;
        }
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }

    static void log(Context context, String message) {
        String line = "[okta-authorizer] " + message + "\n";
        if (context != null && context.getLogger() != null) {
            context.getLogger().log(line);
            return;
        }
        System.err.print(line);
    }
}
