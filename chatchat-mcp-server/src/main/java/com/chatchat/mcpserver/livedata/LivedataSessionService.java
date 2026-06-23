package com.chatchat.mcpserver.livedata;

import com.chatchat.agents.protocol.ModelProtocolJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class LivedataSessionService {

    public static final String SESSION_ARGUMENT = "__livedata_session_id";

    private final LivedataAutoRegistrationProperties properties;
    private final ObjectMapper objectMapper;

    private volatile SessionState sessionState;

    /**
     * Performs the current session id operation.
     *
     * @return the operation result
     */
    public String currentSessionId() {
        if (!shouldLogin()) {
            throw loginConfigMissing();
        }
        SessionState current = sessionState;
        if (current != null && Instant.now().isBefore(current.expiresAt())) {
            return current.sessionId();
        }
        synchronized (this) {
            current = sessionState;
            if (current != null && Instant.now().isBefore(current.expiresAt())) {
                return current.sessionId();
            }
            sessionState = login();
            return sessionState.sessionId();
        }
    }

    /**
     * Performs the refresh session id operation.
     *
     * @return the operation result
     */
    public String refreshSessionId() {
        if (!shouldLogin()) {
            throw loginConfigMissing();
        }
        synchronized (this) {
            sessionState = login();
            return sessionState.sessionId();
        }
    }

    /**
     * Returns whether should login.
     *
     * @return whether the condition is satisfied
     */
    private boolean shouldLogin() {
        return properties.isLoginEnabled()
            && hasText(properties.getLoginId())
            && hasText(properties.getLoginPwd());
    }

    /**
     * Performs the login config missing operation.
     *
     * @return the operation result
     */
    private IllegalStateException loginConfigMissing() {
        return new IllegalStateException("LiveData login is required. Configure chatchat.mcp.livedata.login-id and login-pwd.");
    }

    /**
     * Performs the login operation.
     *
     * @return the operation result
     */
    private SessionState login() {
        try {
            String loginUrl = loginUrl();
            String requestBody = ModelProtocolJson.compact(Map.of(
                "loginId", properties.getLoginId(),
                "loginPwd", properties.getLoginPwd()
            ));
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(loginUrl))
                .timeout(Duration.ofMillis(Math.max(1000, properties.getLoginTimeoutMs())))
                .header("Content-Type", "application/json;charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                .build();

            HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(1000, properties.getLoginTimeoutMs())))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("LiveData login returned HTTP " + response.statusCode());
            }
            String sessionId = extractSessionId(response.body());
            if (!hasText(sessionId)) {
                throw new IllegalStateException("LiveData login response does not contain sessionId");
            }
            log.info("LiveData login succeeded, sessionId refreshed");
            return new SessionState(sessionId, Instant.now().plusSeconds(Math.max(60, properties.getSessionTtlSeconds())));
        } catch (Exception ex) {
            throw new IllegalStateException("LiveData login failed: " + ex.getMessage(), ex);
        }
    }

    /**
     * Performs the extract session id operation.
     *
     * @param body the body value
     * @return the operation result
     * @throws Exception if the operation fails
     */
    private String extractSessionId(String body) throws Exception {
        if (body == null || body.isBlank()) {
            return null;
        }
        JsonNode root = objectMapper.readTree(body);
        return firstText(root,
            "/sessionId",
            "/data",
            "/data/sessionId",
            "/result",
            "/result/sessionId",
            "/body/sessionId",
            "/data/body/sessionId"
        );
    }

    /**
     * Performs the first text operation.
     *
     * @param root the root value
     * @param paths the paths value
     * @return the operation result
     */
    private String firstText(JsonNode root, String... paths) {
        for (String path : paths) {
            JsonNode value = root.at(path);
            if (!value.isMissingNode() && !value.isNull() && hasText(value.asText())) {
                return value.asText();
            }
        }
        return null;
    }

    /**
     * Performs the login url operation.
     *
     * @return the operation result
     */
    private String loginUrl() {
        String baseUrl = properties.getServiceBaseUrl();
        if (!hasText(baseUrl)) {
            throw new IllegalStateException("chatchat.mcp.livedata.service-base-url is required");
        }
        String path = hasText(properties.getLoginPath()) ? properties.getLoginPath().trim() : "/login";
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        return baseUrl.trim().replaceAll("/+$", "") + path;
    }

    /**
     * Returns whether has text.
     *
     * @param value the value value
     * @return whether the condition is satisfied
     */
    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record SessionState(String sessionId, Instant expiresAt) {
    }
}
