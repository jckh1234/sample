package com.example.externalapi.authorizer;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayCustomAuthorizerEvent;
import com.amazonaws.services.lambda.runtime.events.IamPolicyResponse;
import com.fasterxml.jackson.databind.JsonNode;

import java.net.http.HttpClient;
import java.time.Clock;
import java.util.Map;

/**
 * API Gateway REQUEST authorizer.
 *
 * <p>On a cache miss, API Gateway invokes this function before the proxy. An Allow result carries string
 * claims in {@code context}. A deny throws {@link UnauthorizedException} so API Gateway returns 401 and
 * does not call the proxy. Vault and Okta failures stay as ordinary exceptions, which API Gateway surfaces
 * as an authorizer configuration error (500) rather than a bad caller token.
 */
public final class OktaAuthorizerHandler
        implements RequestHandler<APIGatewayCustomAuthorizerEvent, IamPolicyResponse> {
    private final OktaTokenIntrospector introspector;
    private final String expectedIssuer;
    private final String expectedAudience;

    public OktaAuthorizerHandler() {
        this(AuthorizerConfig.fromEnvironment(), HttpExchanges.client(), Clock.systemUTC());
    }

    OktaAuthorizerHandler(AuthorizerConfig config, HttpClient http, Clock clock) {
        this(config, http, clock, AwsSdkV2IamSigner.fromDefaultCredentials());
    }

    OktaAuthorizerHandler(AuthorizerConfig config, HttpClient http, Clock clock, StsGetCallerIdentitySigner signer) {
        this.introspector = new OktaTokenIntrospector(config, http, new VaultKvSecretCache(config, http, clock, signer));
        this.expectedIssuer = config.oktaIssuer();
        this.expectedAudience = config.oktaAudience();
    }

    @Override
    public IamPolicyResponse handleRequest(APIGatewayCustomAuthorizerEvent event, Context context) {
        try {
            return authorize(event, context);
        } catch (UnauthorizedException denied) {
            throw denied;
        } catch (RuntimeException failed) {
            AuthorizerSupport.log(context, "error: " + failed.getClass().getSimpleName());
            throw failed;
        }
    }

    private IamPolicyResponse authorize(APIGatewayCustomAuthorizerEvent event, Context context) {
        if (event == null) {
            throw new IllegalArgumentException("authorizer event is missing");
        }
        String token = AuthorizerSupport.bearerToken(event.getHeaders());
        if (token == null) {
            AuthorizerSupport.log(context, "deny: missing Authorization bearer");
            throw new UnauthorizedException();
        }
        JsonNode introspected = introspector.introspect(token);
        if (!IntrospectionClaims.active(introspected)) {
            AuthorizerSupport.log(context, "deny: inactive token");
            throw new UnauthorizedException();
        }
        if (!IntrospectionClaims.issuerMatches(introspected, expectedIssuer)) {
            AuthorizerSupport.log(context, "deny: issuer mismatch");
            throw new UnauthorizedException();
        }
        if (!IntrospectionClaims.audienceMatches(introspected, expectedAudience)) {
            AuthorizerSupport.log(context, "deny: audience mismatch");
            throw new UnauthorizedException();
        }
        Map<String, String> claims = IntrospectionClaims.toContext(introspected);
        String subject = claims.get("sub");
        if (subject == null || subject.isBlank()) {
            AuthorizerSupport.log(context, "deny: missing sub");
            throw new UnauthorizedException();
        }
        AuthorizerSupport.log(context, "allow");
        return new RestApiAuthorizerPolicy(subject, AuthorizerSupport.stageWildcard(event.getMethodArn()), claims);
    }
}
