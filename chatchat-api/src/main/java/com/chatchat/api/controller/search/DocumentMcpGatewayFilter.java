package com.chatchat.api.controller.search;

import com.chatchat.api.security.ApiAuthenticationFilter;
import com.chatchat.common.response.ApiResponse;
import com.chatchat.enterprise.service.EnterpriseAdminService;
import com.chatchat.knowledgebase.search.model.SearchDocument;
import com.chatchat.mcp.grpc.v1.DocumentTransferStart;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.nio.charset.StandardCharsets;

/** Authenticated API facade for document operations owned by the MCP service. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
@ConditionalOnProperty(prefix = "chatchat.document.gateway", name = "enabled", havingValue = "true")
public class DocumentMcpGatewayFilter extends OncePerRequestFilter {
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String baseUrl;
    private final String token;
    @Autowired(required = false)
    private DocumentGrpcTransferClient documentGrpcTransferClient;
    @Autowired(required = false)
    private ObjectMapper objectMapper;
    @Autowired(required = false)
    private EnterpriseAdminService enterpriseAdminService;

    public DocumentMcpGatewayFilter(
        @Value("${chatchat.mcp.center.base-url}") String baseUrl,
        @Value("${CHATCHAT_DOCUMENT_GATEWAY_TOKEN:}") String token) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.token = token;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !(path.equals("/api/v1/search") || path.startsWith("/api/v1/search/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws IOException {
        if (token.isBlank()) {
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "document gateway token is not configured");
            return;
        }
        String tenantId = attribute(request, ApiAuthenticationFilter.CURRENT_TENANT_ID);
        String userId = attribute(request, ApiAuthenticationFilter.CURRENT_USER_ID);
        if (tenantId == null || userId == null) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        String suffix = path.substring("/api/v1/search".length());
        String username = attribute(request, ApiAuthenticationFilter.CURRENT_USERNAME);
        if ("POST".equalsIgnoreCase(request.getMethod())
            && ("/documents/upload".equals(suffix) || "/documents/upload/batch".equals(suffix))) {
            transferUpload(request, response, tenantId, userId, username, suffix.endsWith("/batch"));
            return;
        }
        String target = baseUrl + "/internal/api/v1/search" + suffix;
        if (request.getQueryString() != null) target += "?" + request.getQueryString();
        HttpRequest.Builder outgoing = HttpRequest.newBuilder(URI.create(target))
            .timeout(Duration.ofMinutes(10))
            .header("X-Document-Tenant-Id", tenantId)
            .header("X-Document-User-Id", userId);
        outgoing.header("X-Document-Gateway-Token", token);
        if (username != null) outgoing.header("X-Document-Username", username);
        Object view = request.getAttribute(ApiAuthenticationFilter.CURRENT_USER_VIEW);
        if (view instanceof EnterpriseAdminService.UserView user) {
            outgoing.header("X-Document-Roles", String.join(",", documentRoleKeys(user)));
        }
        for (String name : List.of("Content-Type", "Accept", "X-Upload-Request-Id")) {
            String value = request.getHeader(name);
            if (value != null) outgoing.header(name, value);
        }
        HttpRequest.BodyPublisher body = hasBody(request.getMethod())
            ? HttpRequest.BodyPublishers.ofInputStream(() -> inputStream(request))
            : HttpRequest.BodyPublishers.noBody();
        outgoing.method(request.getMethod(), body);
        try {
            HttpResponse<InputStream> result = client.send(outgoing.build(), HttpResponse.BodyHandlers.ofInputStream());
            response.setStatus(result.statusCode());
            for (String name : List.of("content-type", "content-disposition", "cache-control")) {
                result.headers().firstValue(name).ifPresent(value -> response.setHeader(name, value));
            }
            try (InputStream stream = result.body()) {
                stream.transferTo(response.getOutputStream());
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "MCP document service interrupted");
        } catch (UncheckedIOException exception) {
            response.sendError(HttpServletResponse.SC_BAD_GATEWAY, "MCP document request failed");
        } catch (IOException exception) {
            response.sendError(HttpServletResponse.SC_BAD_GATEWAY, "MCP document service unavailable");
        }
    }

    private void transferUpload(HttpServletRequest request, HttpServletResponse response,
                                String tenantId, String userId, String username, boolean batch) throws IOException {
        if (documentGrpcTransferClient == null || objectMapper == null) {
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "MCP document RPC is unavailable");
            return;
        }
        Map<String, String> fields = new HashMap<>();
        List<Part> files = new ArrayList<>();
        try {
            for (Part part : request.getParts()) {
                if ((batch && "files".equals(part.getName())) || (!batch && "file".equals(part.getName()))) {
                    files.add(part);
                } else if (part.getSubmittedFileName() == null) {
                    if (part.getSize() > 64 * 1024) {
                        response.sendError(HttpServletResponse.SC_BAD_REQUEST, "document field exceeds 64KB");
                        return;
                    }
                    fields.put(part.getName(), new String(part.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
                }
            }
            if (files.isEmpty()) {
                response.sendError(HttpServletResponse.SC_BAD_REQUEST, "file is required");
                return;
            }
            if (batch) {
                String category = fields.getOrDefault("category", "");
                if (category.isBlank()) {
                    response.sendError(HttpServletResponse.SC_BAD_REQUEST, "category is required");
                    return;
                }
                String tags = fields.getOrDefault("tags", "");
                fields.put("tags", tags.isBlank() ? category : category + "," + tags);
            }
            Object view = request.getAttribute(ApiAuthenticationFilter.CURRENT_USER_VIEW);
            String roles = view instanceof EnterpriseAdminService.UserView user
                ? String.join(",", documentRoleKeys(user)) : "";
            List<SearchDocument> saved = new ArrayList<>();
            for (Part part : files) {
                if (part.getSize() > (batch ? 5L : 55L) * 1024 * 1024) {
                    response.sendError(HttpServletResponse.SC_BAD_REQUEST, "file exceeds upload limit");
                    return;
                }
                DocumentTransferStart start = DocumentTransferStart.newBuilder()
                    .setOperation("UPLOAD").setTenantId(tenantId).setUserId(userId)
                    .setUsername(username == null ? "" : username).setRoles(roles)
                    .setFileName(part.getSubmittedFileName() == null ? "document" : part.getSubmittedFileName())
                    .setContentType(part.getContentType() == null ? "" : part.getContentType())
                    .putAllFields(fields).build();
                try (InputStream stream = part.getInputStream()) {
                    saved.add(documentGrpcTransferClient.transfer(start, stream));
                }
            }
            response.setContentType("application/json;charset=UTF-8");
            objectMapper.writeValue(response.getOutputStream(), batch
                ? ApiResponse.success(saved, "Documents uploaded and indexed")
                : ApiResponse.success(saved.get(0), "Document uploaded and indexed"));
        } catch (ServletException exception) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "invalid multipart document upload");
        } catch (IllegalArgumentException exception) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, exception.getMessage());
        } catch (StatusRuntimeException exception) {
            int status = exception.getStatus().getCode() == Status.Code.INVALID_ARGUMENT
                ? HttpServletResponse.SC_BAD_REQUEST : HttpServletResponse.SC_BAD_GATEWAY;
            response.sendError(status, exception.getStatus().getDescription());
        } catch (RuntimeException exception) {
            response.sendError(HttpServletResponse.SC_BAD_GATEWAY, "MCP document RPC failed");
        }
    }

    private InputStream inputStream(HttpServletRequest request) {
        try {
            return request.getInputStream();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private boolean hasBody(String method) {
        return "POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method)
            || "PATCH".equalsIgnoreCase(method);
    }

    private List<String> documentRoleKeys(EnterpriseAdminService.UserView user) {
        if (enterpriseAdminService != null) {
            return enterpriseAdminService.authorizationRoleKeys(user.id());
        }
        LinkedHashSet<String> fallback = new LinkedHashSet<>(
            user.roleIds() == null ? List.of() : user.roleIds());
        if ("admin".equalsIgnoreCase(user.username())) {
            fallback.add("SUPER_ADMIN");
        }
        return List.copyOf(fallback);
    }

    private String attribute(HttpServletRequest request, String name) {
        Object value = request.getAttribute(name);
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value);
    }
}
