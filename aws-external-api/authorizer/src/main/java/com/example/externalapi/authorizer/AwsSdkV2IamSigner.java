package com.example.externalapi.authorizer;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.http.auth.aws.signer.AwsV4FamilyHttpSigner;
import software.amazon.awssdk.http.auth.aws.signer.AwsV4HttpSigner;
import software.amazon.awssdk.http.auth.spi.signer.SignedRequest;
import software.amazon.awssdk.identity.spi.AwsCredentialsIdentity;
import software.amazon.awssdk.regions.Region;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Builds the signed {@code sts:GetCallerIdentity} request Vault's AWS auth method replays.
 * The authorizer does not call STS. Lambda's execution role supplies the credentials.
 */
final class AwsSdkV2IamSigner implements StsGetCallerIdentitySigner {
    static final String CALLER_IDENTITY_BODY = "Action=GetCallerIdentity&Version=2011-06-15";

    private final AwsCredentialsProvider credentialsProvider;
    private final Region region;

    static AwsSdkV2IamSigner fromDefaultCredentials() {
        return new AwsSdkV2IamSigner(DefaultCredentialsProvider.create(), regionFromEnvironment());
    }

    AwsSdkV2IamSigner(AwsCredentialsProvider credentialsProvider, Region region) {
        this.credentialsProvider = credentialsProvider;
        this.region = region;
    }

    @Override
    public SignedStsRequest signGetCallerIdentity() {
        AwsCredentialsIdentity identity;
        try {
            identity = credentialsProvider.resolveCredentials();
        } catch (RuntimeException ex) {
            throw new IllegalStateException("aws credentials are not available for vault iam auth");
        }
        String host = stsHost(region);
        String url = "https://" + host + "/";
        byte[] body = CALLER_IDENTITY_BODY.getBytes(StandardCharsets.UTF_8);
        SdkHttpRequest request = SdkHttpRequest.builder()
                .method(SdkHttpMethod.POST)
                .uri(URI.create(url))
                .putHeader("Host", host)
                .putHeader("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
                .build();
        SignedRequest signed = AwsV4HttpSigner.create().sign(spec -> spec
                .identity(identity)
                .request(request)
                .payload(() -> new ByteArrayInputStream(body))
                .putProperty(AwsV4FamilyHttpSigner.SERVICE_SIGNING_NAME, "sts")
                .putProperty(AwsV4HttpSigner.REGION_NAME, region.id()));
        return new SignedStsRequest("POST", url, CALLER_IDENTITY_BODY, headersJson(signed.request().headers()));
    }

    static Region regionFromEnvironment() {
        String region = firstNonBlank(System.getenv("AWS_REGION"), System.getenv("AWS_DEFAULT_REGION"));
        if (region == null) {
            region = "us-east-1";
        }
        return Region.of(region);
    }

    private static String stsHost(Region region) {
        String id = region.id();
        if (id.startsWith("cn-")) {
            return "sts." + id + ".amazonaws.com.cn";
        }
        return "sts." + id + ".amazonaws.com";
    }

    private static String headersJson(Map<String, java.util.List<String>> headers) {
        ObjectNode node = HttpExchanges.JSON.createObjectNode();
        headers.forEach((name, values) -> {
            ArrayNode array = node.putArray(name);
            for (String value : values) {
                array.add(value);
            }
        });
        return node.toString();
    }

    private static String firstNonBlank(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary.trim();
        }
        if (fallback != null && !fallback.isBlank()) {
            return fallback.trim();
        }
        return null;
    }
}
