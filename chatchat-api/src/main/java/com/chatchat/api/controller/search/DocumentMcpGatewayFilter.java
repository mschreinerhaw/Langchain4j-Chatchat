package com.chatchat.api.controller.search;

import com.chatchat.api.security.ApiAuthenticationFilter;
import com.chatchat.enterprise.service.EnterpriseAdminService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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

/** Authenticated API facade for document operations owned by the MCP service. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
@ConditionalOnProperty(prefix = "chatchat.document.gateway", name = "enabled", havingValue = "true")
public class DocumentMcpGatewayFilter extends OncePerRequestFilter {
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String baseUrl;
    private final String token;

    public DocumentMcpGatewayFilter(
        @Value("${chatchat.document.gateway.base-url:http://localhost:8090}") String baseUrl,
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
        String target = baseUrl + "/internal/api/v1/search" + suffix;
        if (request.getQueryString() != null) target += "?" + request.getQueryString();
        HttpRequest.Builder outgoing = HttpRequest.newBuilder(URI.create(target))
            .timeout(Duration.ofMinutes(10))
            .header("X-Document-Tenant-Id", tenantId)
            .header("X-Document-User-Id", userId);
        outgoing.header("X-Document-Gateway-Token", token);
        String username = attribute(request, ApiAuthenticationFilter.CURRENT_USERNAME);
        if (username != null) outgoing.header("X-Document-Username", username);
        Object view = request.getAttribute(ApiAuthenticationFilter.CURRENT_USER_VIEW);
        if (view instanceof EnterpriseAdminService.UserView user && user.roleIds() != null) {
            outgoing.header("X-Document-Roles", String.join(",", user.roleIds()));
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

    private String attribute(HttpServletRequest request, String name) {
        Object value = request.getAttribute(name);
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value);
    }
}
