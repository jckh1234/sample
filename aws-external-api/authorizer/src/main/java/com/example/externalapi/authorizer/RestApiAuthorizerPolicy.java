package com.example.externalapi.authorizer;

import com.amazonaws.services.lambda.runtime.events.IamPolicyResponse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST API authorizer Allow policy. {@link IamPolicyResponse#getPolicyDocument()} emits IAM's capitalized
 * keys. This subclass drops a null {@code Condition}, which API Gateway rejects.
 */
final class RestApiAuthorizerPolicy extends IamPolicyResponse {
    RestApiAuthorizerPolicy(String principalId, String resourceArn, Map<String, String> context) {
        super(
                principalId,
                PolicyDocument.builder()
                        .withVersion(VERSION_2012_10_17)
                        .withStatement(List.of(allowStatement(resourceArn)))
                        .build(),
                new LinkedHashMap<>(context));
    }

    @Override
    public Map<String, Object> getPolicyDocument() {
        Map<String, Object> document = super.getPolicyDocument();
        Object statements = document.get("Statement");
        if (statements instanceof Map<?, ?>[] maps) {
            List<Map<String, Object>> cleaned = new ArrayList<>();
            for (Map<?, ?> statement : maps) {
                Map<String, Object> copy = new LinkedHashMap<>();
                statement.forEach((key, value) -> {
                    if (value != null) {
                        copy.put(String.valueOf(key), value);
                    }
                });
                cleaned.add(copy);
            }
            document.put("Statement", cleaned);
        }
        return document;
    }
}
