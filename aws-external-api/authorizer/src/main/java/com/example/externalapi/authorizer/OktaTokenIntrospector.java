package com.example.externalapi.authorizer;

import com.fasterxml.jackson.databind.JsonNode;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * {@code POST {OKTA_ISSUER}/v1/introspect} with form fields {@code token} and
 * {@code token_type_hint=access_token}, and HTTP Basic {@code client_id:client_secret}.
 */
final class OktaTokenIntrospector {
    private final AuthorizerConfig config;
    private final HttpClient http;
    private final VaultKvSecretCache secrets;

    OktaTokenIntrospector(AuthorizerConfig config, HttpClient http, VaultKvSecretCache secrets) {
        this.config = config;
        this.http = http;
        this.secrets = secrets;
    }

    JsonNode introspect(String accessToken) {
        String secret = secrets.clientSecret();
        String form = "token=" + URLEncoder.encode(accessToken, StandardCharsets.UTF_8) + "&token_type_hint=access_token";
        HttpRequest request = HttpRequest.newBuilder(URI.create(config.introspectUrl()))
                .timeout(HttpExchanges.REQUEST_TIMEOUT)
                .header("Authorization", basic(config.oktaClientId(), secret))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                .build();
        HttpExchanges.Exchange response = HttpExchanges.send(http, request);
        if (response.status() == 401 || response.status() == 403) {
            throw new IllegalStateException("okta introspect rejected the client credentials");
        }
        if (response.status() == 400) {
            throw new UnauthorizedException();
        }
        if (response.status() != 200) {
            throw new IllegalStateException("okta introspect status " + response.status());
        }
        return response.json();
    }

    static String basic(String clientId, String clientSecret) {
        String raw = clientId + ":" + clientSecret;
        return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }
}
