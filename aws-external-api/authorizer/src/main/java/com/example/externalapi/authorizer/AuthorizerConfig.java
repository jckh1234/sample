package com.example.externalapi.authorizer;

import java.util.Map;

/**
 * Environment for the authorizer. Values are placeholders supplied at deploy time; this class never has defaults
 * that look like real credentials.
 */
final class AuthorizerConfig {
    static final String DEFAULT_SECRET_KEY = "client_secret";
    static final int DEFAULT_CACHE_SECONDS = 300;

    private final String oktaIssuer;
    private final String oktaClientId;
    private final String oktaAudience;
    private final String vaultAddr;
    private final String vaultRoleId;
    private final String vaultSecretId;
    private final String vaultSecretPath;
    private final String vaultSecretKey;
    private final String vaultNamespace;
    private final int cacheSeconds;

    private AuthorizerConfig(
            String oktaIssuer,
            String oktaClientId,
            String oktaAudience,
            String vaultAddr,
            String vaultRoleId,
            String vaultSecretId,
            String vaultSecretPath,
            String vaultSecretKey,
            String vaultNamespace,
            int cacheSeconds) {
        this.oktaIssuer = oktaIssuer;
        this.oktaClientId = oktaClientId;
        this.oktaAudience = oktaAudience;
        this.vaultAddr = vaultAddr;
        this.vaultRoleId = vaultRoleId;
        this.vaultSecretId = vaultSecretId;
        this.vaultSecretPath = vaultSecretPath;
        this.vaultSecretKey = vaultSecretKey;
        this.vaultNamespace = vaultNamespace;
        this.cacheSeconds = cacheSeconds;
    }

    static AuthorizerConfig fromEnvironment() {
        return from(System.getenv());
    }

    static AuthorizerConfig from(Map<String, String> env) {
        String secretKey = optional(env, "VAULT_OKTA_SECRET_KEY");
        return new AuthorizerConfig(
                required(env, "OKTA_ISSUER"),
                required(env, "OKTA_CLIENT_ID"),
                optional(env, "OKTA_AUDIENCE"),
                stripTrailingSlash(required(env, "VAULT_ADDR")),
                required(env, "VAULT_ROLE_ID"),
                required(env, "VAULT_SECRET_ID"),
                stripLeadingSlash(required(env, "VAULT_OKTA_SECRET_PATH")),
                secretKey == null ? DEFAULT_SECRET_KEY : secretKey,
                optional(env, "VAULT_NAMESPACE"),
                cacheSeconds(env));
    }

    String oktaIssuer() {
        return oktaIssuer;
    }

    String oktaClientId() {
        return oktaClientId;
    }

    /** Empty when the audience check is disabled. */
    String oktaAudience() {
        return oktaAudience;
    }

    String vaultAddr() {
        return vaultAddr;
    }

    String vaultRoleId() {
        return vaultRoleId;
    }

    String vaultSecretId() {
        return vaultSecretId;
    }

    String vaultSecretPath() {
        return vaultSecretPath;
    }

    String vaultSecretKey() {
        return vaultSecretKey;
    }

    /** Empty for Vault OSS. */
    String vaultNamespace() {
        return vaultNamespace;
    }

    int cacheSeconds() {
        return cacheSeconds;
    }

    String introspectUrl() {
        return AuthorizerSupport.normalizeIssuer(oktaIssuer) + "/v1/introspect";
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

    private static int cacheSeconds(Map<String, String> env) {
        String raw = optional(env, "VAULT_SECRET_CACHE_SECONDS");
        if (raw == null) {
            return DEFAULT_CACHE_SECONDS;
        }
        try {
            int parsed = Integer.parseInt(raw);
            if (parsed < 0) {
                throw new NumberFormatException("negative");
            }
            return parsed;
        } catch (NumberFormatException ex) {
            throw new IllegalStateException("VAULT_SECRET_CACHE_SECONDS must be a non-negative integer");
        }
    }

    private static String stripTrailingSlash(String value) {
        String trimmed = value;
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static String stripLeadingSlash(String value) {
        String trimmed = value;
        while (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        return trimmed;
    }
}
