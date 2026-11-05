package com.example.externalapi.authorizer;

/**
 * API Gateway maps an authorizer exception whose message is exactly {@code Unauthorized} to HTTP 401
 * and does not invoke the proxy. The message must stay exactly that word.
 */
final class UnauthorizedException extends RuntimeException {
    UnauthorizedException() {
        super("Unauthorized");
    }
}
