package com.example.externalapi.authorizer;

import java.util.Map;

/**
 * Environment for the authorizer. Values are placeholders supplied at deploy time; this class never has defaults
 * that look like real credentials.
 */
final class AuthorizerConfig {
    static final String DEFAULT_SECRET_KEY = "client_secret";
    static final String DEFAULT_AUTH_MOUNT = "aws";
    static final int DEFAULT_CACHE_SECONDS = 300;

    private final String oktaIssuer;
    private final String oktaClientId;
    private final String oktaAudience;
    private final String vaultAddr;
    private final String vaultAwsAuthRole;
    private final String vaultAuthMount;
    private final String vaultSecretPath;
    private final String vaultSecretKey;
    private final String vaultNamespace;
    private final int cacheSeconds;

    private AuthorizerConfig(
            String oktaIssuer,
            String oktaClientId,
            String oktaAudience,
            String vaultAddr,
            String vaultAwsAuthRole,
            String vaultAuthMount,
            String vaultSecretPath,
            String vaultSecretKey,
            String vaultNamespace,
            int cacheSeconds) {
        this.oktaIssuer = oktaIssuer;
        this.oktaClientId = oktaClientId;
        this.oktaAudience = oktaAudience;
        this.vaultAddr = vaultAddr;
        this.vaultAwsAuthRole = vaultAwsAuthRole;
        this.vaultAuthMount = vaultAuthMount;
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
        String mount = optional(env, "VAULT_AUTH_MOUNT");
        return new AuthorizerConfig(
                required(env, "OKTA_ISSUER"),
                required(env, "OKTA_CLIENT_ID"),
                optional(env, "OKTA_AUDIENCE"),
                stripTrailingSlash(required(env, "VAULT_ADDR")),
                required(env, "VAULT_AWS_AUTH_ROLE"),
                authMount(mount),
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

    /** Vault AWS auth role name. This is not an IAM role ARN. */
    String vaultAwsAuthRole() {
        return vaultAwsAuthRole;
    }

    /** Auth mount path segment. Default {@code aws}, so login is {@code /v1/auth/aws/login}. */
    String vaultAuthMount() {
        return vaultAuthMount;
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

    private static String authMount(String mount) {
        String value = mount == null ? DEFAULT_AUTH_MOUNT : mount;
        if (!value.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalStateException("VAULT_AUTH_MOUNT must be a single path segment");
        }
        return value;
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
