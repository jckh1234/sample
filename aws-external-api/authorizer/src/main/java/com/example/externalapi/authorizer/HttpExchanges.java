package com.example.externalapi.authorizer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

final class HttpExchanges {
    static final ObjectMapper JSON = new ObjectMapper();
    static final int MAX_BYTES = 1_000_000;
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(6);

    private HttpExchanges() {
    }

    static HttpClient client() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    static Exchange send(HttpClient http, HttpRequest request) {
        HttpResponse<byte[]> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("http call interrupted");
        } catch (IOException ex) {
            throw new IllegalStateException("http call failed");
        }
        byte[] body = response.body() == null ? new byte[0] : response.body();
        if (body.length > MAX_BYTES) {
            throw new IllegalStateException("http response too large");
        }
        return new Exchange(response.statusCode(), body);
    }

    static final class Exchange {
        private final int status;
        private final byte[] body;

        Exchange(int status, byte[] body) {
            this.status = status;
            this.body = body;
        }

        int status() {
            return status;
        }

        JsonNode json() {
            if (body.length == 0) {
                return JSON.nullNode();
            }
            try {
                return JSON.readTree(body);
            } catch (IOException ex) {
                throw new IllegalStateException("http response was not json");
            }
        }
    }
}
