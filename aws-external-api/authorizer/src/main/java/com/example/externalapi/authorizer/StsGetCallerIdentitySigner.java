package com.example.externalapi.authorizer;

/** Signs {@code sts:GetCallerIdentity} locally so Vault can replay the request. */
interface StsGetCallerIdentitySigner {
    SignedStsRequest signGetCallerIdentity();
}
