package com.example.externalapi.authorizer;

import com.fasterxml.jackson.databind.JsonNode;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Local SigV4 only. The access key is the AWS documentation example, not a live credential. */
final class IamLoginFixture {
    static final String VAULT_ROLE = "external-api-authorizer";

    private IamLoginFixture() {
    }

    static StsGetCallerIdentitySigner signer() {
        return new AwsSdkV2IamSigner(
                StaticCredentialsProvider.create(AwsBasicCredentials.create(
                        "AKIAIOSFODNN7EXAMPLE",
                        "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY")),
                Region.US_EAST_1);
    }

    static void assertLogin(JsonNode login) {
        assertEquals(VAULT_ROLE, login.path("role").asText());
        assertEquals("POST", login.path("iam_http_request_method").asText());
        assertFalse(login.has("role_id"));
        assertFalse(login.has("secret_id"));
        assertEquals("https://sts.us-east-1.amazonaws.com/", decode(login.path("iam_request_url").asText()));
        assertEquals(AwsSdkV2IamSigner.CALLER_IDENTITY_BODY, decode(login.path("iam_request_body").asText()));
        String headers = decode(login.path("iam_request_headers").asText());
        assertTrue(headers.contains("AWS4-HMAC-SHA256"));
        assertTrue(headers.contains("sts.us-east-1.amazonaws.com"));
    }

    private static String decode(String value) {
        return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
    }
}
