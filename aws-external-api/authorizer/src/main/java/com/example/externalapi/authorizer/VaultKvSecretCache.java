package com.example.externalapi.authorizer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Base64;

/**
 * AWS IAM auth login plus a KV v2 read. The Okta client secret is cached in this execution environment until
 * {@code VAULT_SECRET_CACHE_SECONDS} elapses. The Vault token itself is not cached; a refresh signs again and logs in.
 */
final class VaultKvSecretCache {
    private final AuthorizerConfig config;
    private final HttpClient http;
    private final Clock clock;
    private final StsGetCallerIdentitySigner signer;
    private final Object lock = new Object();
    private String cachedSecret;
    private long expiresAtMillis;

    VaultKvSecretCache(AuthorizerConfig config, HttpClient http, Clock clock, StsGetCallerIdentitySigner signer) {
        this.config = config;
        this.http = http;
        this.clock = clock;
        this.signer = signer;
    }

    String clientSecret() {
        synchronized (lock) {
            long now = clock.millis();
            if (cachedSecret != null && now < expiresAtMillis) {
                return cachedSecret;
            }
            String secret = readSecret(login());
            if (config.cacheSeconds() > 0) {
                cachedSecret = secret;
                expiresAtMillis = clock.millis() + config.cacheSeconds() * 1000L;
            } else {
                cachedSecret = null;
                expiresAtMillis = 0L;
            }
            return secret;
        }
    }

    private String login() {
        SignedStsRequest signed = signer.signGetCallerIdentity();
        ObjectNode body = HttpExchanges.JSON.createObjectNode();
        body.put("role", config.vaultAwsAuthRole());
        body.put("iam_http_request_method", signed.method());
        body.put("iam_request_url", base64(signed.url()));
        body.put("iam_request_body", base64(signed.body()));
        body.put("iam_request_headers", base64(signed.headersJson()));
        HttpRequest.Builder request = HttpRequest.newBuilder(uri("/v1/auth/" + config.vaultAuthMount() + "/login"))
                .timeout(HttpExchanges.REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
        applyNamespace(request);
        HttpExchanges.Exchange response = HttpExchanges.send(http, request.build());
        if (response.status() != 200) {
            throw new IllegalStateException("vault aws iam login status " + response.status());
        }
        String token = response.json().path("auth").path("client_token").asText("");
        if (token.isBlank()) {
            throw new IllegalStateException("vault aws iam login returned no client token");
        }
        return token;
    }

    private static String base64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private String readSecret(String vaultToken) {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri("/v1/" + config.vaultSecretPath()))
                .timeout(HttpExchanges.REQUEST_TIMEOUT)
                .header("Accept", "application/json")
                .header("X-Vault-Token", vaultToken)
                .GET();
        applyNamespace(request);
        HttpExchanges.Exchange response = HttpExchanges.send(http, request.build());
        if (response.status() == 404) {
            throw new IllegalStateException("vault secret path not found");
        }
        if (response.status() != 200) {
            throw new IllegalStateException("vault kv read status " + response.status());
        }
        JsonNode value = response.json().path("data").path("data").path(config.vaultSecretKey());
        if (!value.isTextual() || value.asText().isBlank()) {
            throw new IllegalStateException("vault secret key is missing or not a string");
        }
        return value.asText();
    }

    private void applyNamespace(HttpRequest.Builder request) {
        if (config.vaultNamespace() != null) {
            request.header("X-Vault-Namespace", config.vaultNamespace());
        }
    }

    private URI uri(String path) {
        return URI.create(config.vaultAddr() + path);
    }
}
