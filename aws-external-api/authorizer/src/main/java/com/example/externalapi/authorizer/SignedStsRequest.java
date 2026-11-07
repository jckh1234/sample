package com.example.externalapi.authorizer;

/** Pieces of a signed GetCallerIdentity call. Vault expects each field except the method to be Base64-encoded. */
final class SignedStsRequest {
    private final String method;
    private final String url;
    private final String body;
    private final String headersJson;

    SignedStsRequest(String method, String url, String body, String headersJson) {
        this.method = method;
        this.url = url;
        this.body = body;
        this.headersJson = headersJson;
    }

    String method() {
        return method;
    }

    String url() {
        return url;
    }

    String body() {
        return body;
    }

    String headersJson() {
        return headersJson;
    }
}
