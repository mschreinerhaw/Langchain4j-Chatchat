package com.chatchat.api.controller.search;

import com.chatchat.api.security.ApiAuthenticationFilter;
import com.chatchat.common.security.InternalCredentialProperties;
import com.chatchat.knowledgebase.search.document.DocumentFileResource;
import com.chatchat.knowledgebase.search.model.SearchDocument;
import com.chatchat.knowledgebase.search.security.SearchPermissionContext;
import com.chatchat.mcp.grpc.v1.DocumentTransferStart;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;

/** Sends legacy document content and its original file through the authenticated MCP gRPC channel. */
@Component
public class LegacyDocumentMcpTransferService {
    private final DocumentGrpcTransferClient client;
    private final ObjectMapper mapper;
    private final InternalCredentialProperties credentials;

    public LegacyDocumentMcpTransferService(DocumentGrpcTransferClient client, ObjectMapper mapper,
        InternalCredentialProperties credentials) {
        this.client = client;
        this.mapper = mapper;
        this.credentials = credentials;
    }

    public boolean enabled() {
        return credentials.isEnabled() && !credentials.resolvedSecret().isBlank();
    }

    public SearchDocument transfer(SearchDocument document, DocumentFileResource file,
                                   SearchPermissionContext context, HttpServletRequest request) {
        Object value = request == null ? null : request.getAttribute(ApiAuthenticationFilter.CURRENT_USERNAME);
        return transfer(document, file, context, value == null ? null : String.valueOf(value));
    }

    public SearchDocument transfer(SearchDocument document, DocumentFileResource file,
                                   SearchPermissionContext context, String username) {
        if (!enabled()) throw new IllegalStateException("MCP gRPC credential is not configured");
        try {
            DocumentTransferStart start = DocumentTransferStart.newBuilder()
                .setOperation("MIGRATE")
                .setTenantId(context.tenantId())
                .setUserId(context.userId())
                .setUsername(username == null ? "" : username)
                .setFileName(file == null || file.fileName() == null ? "" : file.fileName())
                .setContentType("application/octet-stream")
                .setDocumentJson(ByteString.copyFrom(mapper.writeValueAsBytes(document)))
                .build();
            if (file == null) return client.transfer(start, null);
            try (InputStream input = file.resource().getInputStream()) {
                return client.transfer(start, input);
            }
        } catch (IOException failure) {
            throw new IllegalStateException("failed to read legacy document", failure);
        }
    }
}
