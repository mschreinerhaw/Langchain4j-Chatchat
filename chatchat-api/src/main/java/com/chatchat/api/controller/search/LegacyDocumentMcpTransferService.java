package com.chatchat.api.controller.search;

import com.chatchat.api.security.ApiAuthenticationFilter;
import com.chatchat.common.security.InternalCredentialProperties;
import com.chatchat.enterprise.service.EnterpriseAdminService;
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
import java.util.List;

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
        Object view = request == null ? null : request.getAttribute(ApiAuthenticationFilter.CURRENT_USER_VIEW);
        List<String> permissions = view instanceof EnterpriseAdminService.UserView user
            ? user.permissionCodes() : List.of();
        return transfer(document, file, context, value == null ? null : String.valueOf(value), permissions);
    }

    public SearchDocument transfer(SearchDocument document, DocumentFileResource file,
                                   SearchPermissionContext context, String username) {
        return transfer(document, file, context, username, List.of());
    }

    public SearchDocument transfer(SearchDocument document, DocumentFileResource file,
                                   SearchPermissionContext context, String username,
                                   List<String> permissions) {
        if (!enabled()) throw new IllegalStateException("MCP gRPC credential is not configured");
        try {
            DocumentTransferStart start = DocumentTransferStart.newBuilder()
                .setOperation("MIGRATE")
                .setTenantId(context.tenantId())
                .setUserId(context.userId())
                .setUsername(username == null ? "" : username)
                .setRoles(String.join(",", context.roles()))
                .setPermissions(String.join(",", permissions == null ? List.of() : permissions))
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
