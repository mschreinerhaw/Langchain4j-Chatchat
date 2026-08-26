package com.chatchat.mcpserver.grpc;

import com.chatchat.common.security.InternalCredentialProperties;
import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Authenticates the private API-to-MCP channel using the encrypted internal credential. */
public final class McpGrpcAuthorizationInterceptor implements ServerInterceptor {
    private static final Metadata.Key<String> AUTHORIZATION =
        Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
    private final InternalCredentialProperties credentials;

    public McpGrpcAuthorizationInterceptor(InternalCredentialProperties credentials) {
        this.credentials = credentials;
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
        ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next
    ) {
        if (credentials == null || !credentials.isEnabled()) {
            return Contexts.interceptCall(Context.current(), call, headers, next);
        }
        String expected = "Bearer " + credentials.resolvedSecret();
        String supplied = headers.get(AUTHORIZATION);
        if (expected.equals("Bearer ") || supplied == null || !MessageDigest.isEqual(
            expected.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) {
            call.close(Status.UNAUTHENTICATED.withDescription("invalid internal MCP credential"),
                new Metadata());
            return new ServerCall.Listener<>() { };
        }
        return Contexts.interceptCall(Context.current(), call, headers, next);
    }
}
