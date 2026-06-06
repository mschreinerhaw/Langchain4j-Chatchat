package com.chatchat.api.mcp.service;

import com.chatchat.api.mcp.config.McpStdioProxyProperties;
import com.chatchat.api.mcp.entity.McpServiceConfig;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Local stdio MCP proxy service.
 *
 * <p>Transforms JSON-RPC requests from gateway into stdio framed messages and
 * reads framed JSON-RPC responses from local MCP server processes.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class McpStdioProxyService {

    private static final String JSON_RPC_VERSION = "2.0";
    private static final String MCP_PROTOCOL_VERSION = "2024-11-05";

    private final ObjectMapper objectMapper;
    private final McpStdioProxyProperties properties;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    public Object callForResult(McpServiceConfig config, String method, Map<String, Object> params) {
        Map<String, Object> response = execute(config, method, params, UUID.randomUUID().toString(), true);
        Object error = response.get("error");
        if (error instanceof Map<?, ?> errorMap) {
            Object messageObj = errorMap.containsKey("message") ? errorMap.get("message") : errorMap;
            String message = String.valueOf(messageObj);
            throw new IllegalStateException(message);
        }
        return response.get("result");
    }

    public Map<String, Object> forwardJsonRpc(McpServiceConfig config, Map<String, Object> request) {
        String method = asText(request.get("method"));
        if (method == null || method.isBlank()) {
            throw new IllegalArgumentException("jsonrpc method is required");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> params = request.get("params") instanceof Map<?, ?> map
            ? objectMapper.convertValue(map, new TypeReference<Map<String, Object>>() {})
            : Map.of();
        String id = request.containsKey("id") ? asText(request.get("id")) : null;
        boolean expectResponse = id != null && !id.isBlank();
        return execute(config, method, params, id, expectResponse);
    }

    public void closeSession(String serviceId) {
        if (serviceId == null || serviceId.isBlank()) {
            return;
        }
        Session removed = sessions.remove(serviceId);
        closeSession(removed);
    }

    @Scheduled(fixedDelayString = "${chatchat.mcp.stdio-proxy.cleanup-interval-ms:30000}")
    public void cleanupIdleSessions() {
        if (!properties.isEnabled()) {
            closeAll();
            return;
        }
        long now = System.currentTimeMillis();
        long ttl = Math.max(properties.getIdleTtlMs(), Duration.ofMinutes(1).toMillis());
        List<String> evicted = new LinkedList<>();
        sessions.forEach((key, session) -> {
            boolean dead = !session.running || !session.process.isAlive();
            boolean idleExpired = (now - session.lastUsedAt) > ttl;
            if (dead || idleExpired) {
                if (sessions.remove(key, session)) {
                    evicted.add(key);
                    closeSession(session);
                }
            }
        });
        if (!evicted.isEmpty()) {
            log.info("Evicted {} stdio MCP session(s): {}", evicted.size(), evicted);
        }
    }

    @PreDestroy
    public void closeAll() {
        sessions.values().forEach(this::closeSession);
        sessions.clear();
    }

    private Map<String, Object> execute(McpServiceConfig config, String method, Map<String, Object> params,
                                        String id, boolean expectResponse) {
        if (!properties.isEnabled()) {
            throw new IllegalStateException("stdio proxy is disabled by configuration");
        }
        Session session = getOrCreateSession(config);
        session.callLock.lock();
        try {
            session.lastUsedAt = System.currentTimeMillis();
            if (!session.running || !session.process.isAlive()) {
                throw new IllegalStateException("stdio process is not alive");
            }
            if (!session.initialized && !isInitializeMethod(method)) {
                initializeSession(session);
            }

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("jsonrpc", JSON_RPC_VERSION);
            payload.put("method", method);
            if (params != null && !params.isEmpty()) {
                payload.put("params", params);
            }
            if (expectResponse) {
                payload.put("id", id);
            }

            writeFrame(session.stdin, payload);

            if (!expectResponse) {
                return Map.of("jsonrpc", JSON_RPC_VERSION);
            }
            Map<String, Object> response = awaitResponse(session, id, properties.getRequestTimeoutMs());
            if (response == null) {
                throw new IllegalStateException("stdio MCP request timeout");
            }
            return response;
        } catch (Exception ex) {
            log.warn("stdio MCP request failed for service {} method {}: {}",
                config.getId(), method, ex.getMessage());
            closeAndRemove(config);
            throw ex instanceof RuntimeException runtimeException
                ? runtimeException
                : new IllegalStateException(ex.getMessage(), ex);
        } finally {
            session.lastUsedAt = System.currentTimeMillis();
            session.callLock.unlock();
        }
    }

    private Session getOrCreateSession(McpServiceConfig config) {
        String key = sessionKey(config);
        String fingerprint = sessionFingerprint(config);
        Session current = sessions.get(key);
        if (current != null && current.running && current.process.isAlive() &&
            Objects.equals(current.fingerprint, fingerprint)) {
            return current;
        }

        synchronized (sessions) {
            current = sessions.get(key);
            if (current != null && current.running && current.process.isAlive() &&
                Objects.equals(current.fingerprint, fingerprint)) {
                return current;
            }
            closeSession(current);
            if (sessions.size() >= Math.max(1, properties.getMaxSessions())) {
                throw new IllegalStateException("stdio proxy session limit reached");
            }
            Session created = startSession(config, key, fingerprint);
            sessions.put(key, created);
            return created;
        }
    }

    private Session startSession(McpServiceConfig config, String key, String fingerprint) {
        String command = trimToNull(config.getStdioCommand());
        if (command == null) {
            throw new IllegalArgumentException("stdioCommand is required for mcp_stdio_proxy protocol");
        }
        assertCommandAllowed(command);

        List<String> commandLine = new ArrayList<>();
        commandLine.add(command);
        commandLine.addAll(parseArgs(config.getStdioArgsJson()));

        ProcessBuilder builder = new ProcessBuilder(commandLine);
        String workDir = trimToNull(config.getStdioWorkingDirectory());
        if (workDir != null) {
            builder.directory(new File(workDir));
        }
        Map<String, String> extraEnv = parseEnv(config.getStdioEnvJson());
        if (!extraEnv.isEmpty()) {
            builder.environment().putAll(extraEnv);
        }

        try {
            Process process = builder.start();
            Session session = new Session(key, fingerprint, process, process.getOutputStream());
            startStdoutReader(session);
            startStderrReader(session);

            long waitMs = Math.max(1000L, properties.getStartupTimeoutMs());
            long deadline = System.currentTimeMillis() + waitMs;
            while (!process.isAlive() && System.currentTimeMillis() < deadline) {
                Thread.sleep(20L);
            }
            if (!process.isAlive()) {
                throw new IllegalStateException("stdio process exited during startup");
            }
            log.info("Started stdio MCP session {} with command {}", key, commandLine);
            return session;
        } catch (Exception ex) {
            throw new IllegalStateException("failed to start stdio MCP process: " + ex.getMessage(), ex);
        }
    }

    private void startStdoutReader(Session session) {
        session.stdoutReader = new Thread(() -> {
            try (InputStream inputStream = session.process.getInputStream()) {
                while (session.running) {
                    Map<String, Object> message = readFrame(inputStream);
                    if (message == null) {
                        break;
                    }
                    session.incoming.offer(message);
                }
            } catch (Exception ex) {
                if (session.running) {
                    log.warn("stdio MCP stdout reader stopped for {}: {}", session.key, ex.getMessage());
                }
            } finally {
                session.running = false;
            }
        }, "mcp-stdio-out-" + sanitizeThreadName(session.key));
        session.stdoutReader.setDaemon(true);
        session.stdoutReader.start();
    }

    private void startStderrReader(Session session) {
        session.stderrReader = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(session.process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.isBlank()) {
                        log.debug("[mcp-stdio:{}] {}", session.key, line);
                    }
                }
            } catch (IOException ignored) {
                // ignore
            }
        }, "mcp-stdio-err-" + sanitizeThreadName(session.key));
        session.stderrReader.setDaemon(true);
        session.stderrReader.start();
    }

    private void initializeSession(Session session) throws IOException {
        String initId = UUID.randomUUID().toString();
        Map<String, Object> initParams = new LinkedHashMap<>();
        initParams.put("protocolVersion", MCP_PROTOCOL_VERSION);
        initParams.put("capabilities", Map.of("tools", Map.of()));
        initParams.put("clientInfo", Map.of("name", "chatchat-stdio-proxy", "version", "1.0.0"));

        Map<String, Object> initializeRequest = new LinkedHashMap<>();
        initializeRequest.put("jsonrpc", JSON_RPC_VERSION);
        initializeRequest.put("id", initId);
        initializeRequest.put("method", "initialize");
        initializeRequest.put("params", initParams);
        writeFrame(session.stdin, initializeRequest);

        Map<String, Object> initResponse = awaitResponse(session, initId, properties.getStartupTimeoutMs());
        if (initResponse == null) {
            throw new IllegalStateException("initialize timeout");
        }
        if (initResponse.get("error") instanceof Map<?, ?> error) {
            throw new IllegalStateException("initialize failed: " + error);
        }

        Map<String, Object> initializedNotification = new LinkedHashMap<>();
        initializedNotification.put("jsonrpc", JSON_RPC_VERSION);
        initializedNotification.put("method", "notifications/initialized");
        initializedNotification.put("params", Map.of());
        writeFrame(session.stdin, initializedNotification);

        session.initialized = true;
    }

    private Map<String, Object> awaitResponse(Session session, String requestId, long timeoutMs) {
        long deadline = System.currentTimeMillis() + Math.max(1000L, timeoutMs);
        while (System.currentTimeMillis() < deadline) {
            long remain = deadline - System.currentTimeMillis();
            try {
                Map<String, Object> message = session.incoming.poll(Math.max(1L, remain), TimeUnit.MILLISECONDS);
                if (message == null) {
                    continue;
                }
                String responseId = asText(message.get("id"));
                if (responseId != null && responseId.equals(requestId)) {
                    return message;
                }
                if (message.containsKey("method")) {
                    log.debug("stdio MCP notification [{}]: {}", session.key, message.get("method"));
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    private Map<String, Object> readFrame(InputStream inputStream) throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        while (true) {
            String line = readAsciiLine(inputStream);
            if (line == null) {
                return null;
            }
            if (line.isBlank()) {
                break;
            }
            int split = line.indexOf(':');
            if (split <= 0) {
                continue;
            }
            String key = line.substring(0, split).trim();
            String value = line.substring(split + 1).trim();
            headers.put(key.toLowerCase(Locale.ROOT), value);
        }

        String lengthText = headers.get("content-length");
        if (lengthText == null || lengthText.isBlank()) {
            throw new IOException("missing Content-Length header");
        }
        int length;
        try {
            length = Integer.parseInt(lengthText);
        } catch (NumberFormatException ex) {
            throw new IOException("invalid Content-Length: " + lengthText, ex);
        }
        if (length <= 0) {
            throw new IOException("invalid frame length: " + length);
        }
        byte[] body = inputStream.readNBytes(length);
        if (body.length < length) {
            return null;
        }
        return objectMapper.readValue(body, new TypeReference<>() {});
    }

    private String readAsciiLine(InputStream inputStream) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int read;
        boolean readAny = false;
        while ((read = inputStream.read()) != -1) {
            readAny = true;
            if (read == '\n') {
                break;
            }
            if (read != '\r') {
                buffer.write(read);
            }
        }
        if (!readAny && read == -1) {
            return null;
        }
        return buffer.toString(StandardCharsets.US_ASCII);
    }

    private void writeFrame(OutputStream outputStream, Map<String, Object> payload) throws IOException {
        byte[] body = objectMapper.writeValueAsBytes(payload);
        String header = "Content-Length: " + body.length + "\r\n\r\n";
        outputStream.write(header.getBytes(StandardCharsets.US_ASCII));
        outputStream.write(body);
        outputStream.flush();
    }

    private List<String> parseArgs(String argsJson) {
        String text = trimToNull(argsJson);
        if (text == null) {
            return List.of();
        }
        try {
            List<Object> values = objectMapper.readValue(text, new TypeReference<List<Object>>() {});
            List<String> args = new ArrayList<>();
            for (Object value : values) {
                if (value != null) {
                    args.add(String.valueOf(value));
                }
            }
            return args;
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("stdioArgsJson must be a JSON array");
        }
    }

    private Map<String, String> parseEnv(String envJson) {
        String text = trimToNull(envJson);
        if (text == null) {
            return Map.of();
        }
        try {
            Map<String, Object> values = objectMapper.readValue(text, new TypeReference<Map<String, Object>>() {});
            Map<String, String> env = new LinkedHashMap<>();
            values.forEach((k, v) -> {
                if (k != null && !k.isBlank() && v != null) {
                    env.put(k, String.valueOf(v));
                }
            });
            return env;
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("stdioEnvJson must be a JSON object");
        }
    }

    private void assertCommandAllowed(String command) {
        List<String> allowList = properties.getCommandAllowList();
        if (allowList == null || allowList.isEmpty()) {
            return;
        }
        String normalized = command.trim().toLowerCase(Locale.ROOT);
        String baseName = new File(command.trim()).getName().toLowerCase(Locale.ROOT);
        boolean allowed = allowList.stream()
            .filter(Objects::nonNull)
            .map(item -> item.trim().toLowerCase(Locale.ROOT))
            .anyMatch(item -> !item.isBlank() && (item.equals(normalized) || item.equals(baseName)));
        if (!allowed) {
            throw new IllegalArgumentException("stdio command is not in allow list: " + command);
        }
    }

    private boolean isInitializeMethod(String method) {
        if (method == null) {
            return false;
        }
        return "initialize".equals(method) || "notifications/initialized".equals(method);
    }

    private String sessionKey(McpServiceConfig config) {
        if (config.getId() != null && !config.getId().isBlank()) {
            return config.getId();
        }
        if (config.getName() != null && !config.getName().isBlank()) {
            return config.getName();
        }
        return "mcp-stdio";
    }

    private String sessionFingerprint(McpServiceConfig config) {
        return String.join("|",
            trimToNull(config.getStdioCommand()) == null ? "" : trimToNull(config.getStdioCommand()),
            trimToNull(config.getStdioArgsJson()) == null ? "" : trimToNull(config.getStdioArgsJson()),
            trimToNull(config.getStdioEnvJson()) == null ? "" : trimToNull(config.getStdioEnvJson()),
            trimToNull(config.getStdioWorkingDirectory()) == null ? "" : trimToNull(config.getStdioWorkingDirectory())
        );
    }

    private void closeAndRemove(McpServiceConfig config) {
        String key = sessionKey(config);
        Session removed = sessions.remove(key);
        closeSession(removed);
    }

    private void closeSession(Session session) {
        if (session == null) {
            return;
        }
        session.running = false;
        try {
            session.stdin.close();
        } catch (Exception ignored) {
            // ignore
        }
        try {
            session.process.destroy();
            if (session.process.isAlive()) {
                session.process.destroyForcibly();
            }
        } catch (Exception ignored) {
            // ignore
        }
    }

    private String asText(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String text = value.trim();
        return text.isEmpty() ? null : text;
    }

    private String sanitizeThreadName(String value) {
        if (value == null || value.isBlank()) {
            return "default";
        }
        return value.replaceAll("[^a-zA-Z0-9_-]+", "_");
    }

    private static class Session {
        private final String key;
        private final String fingerprint;
        private final Process process;
        private final OutputStream stdin;
        private final BlockingQueue<Map<String, Object>> incoming = new LinkedBlockingQueue<>();
        private final ReentrantLock callLock = new ReentrantLock();

        private volatile boolean running = true;
        private volatile boolean initialized = false;
        private volatile long lastUsedAt = System.currentTimeMillis();
        private volatile Thread stdoutReader;
        private volatile Thread stderrReader;

        private Session(String key, String fingerprint, Process process, OutputStream stdin) {
            this.key = key;
            this.fingerprint = fingerprint;
            this.process = process;
            this.stdin = stdin;
        }
    }
}
