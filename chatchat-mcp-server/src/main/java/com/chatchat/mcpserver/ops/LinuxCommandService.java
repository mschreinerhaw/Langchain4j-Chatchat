package com.chatchat.mcpserver.ops;

import com.chatchat.mcpserver.audit.InvocationAuditService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.ClientChannel;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.NamedResource;
import org.apache.sshd.common.config.keys.FilePasswordProvider;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.util.security.SecurityUtils;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class LinuxCommandService {

    private static final Pattern TEMPLATE_TOKEN = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_][A-Za-z0-9_.-]*)\\s*}}");
    private static final int LOG_OUTPUT_LIMIT = 4000;

    private final SshHostConfigService hostConfigService;
    private final CommandTemplateService templateService;
    private final LinuxCommandSafetyService safetyService;
    private final InvocationAuditService auditService;
    private final ObjectMapper objectMapper;

    public LinuxCommandResult execute(Map<String, Object> arguments) {
        long startedAt = System.currentTimeMillis();
        Map<String, Object> request = normalizeRequest(arguments);
        SshHostConfig host = null;
        String command = null;
        LinuxCommandResult result;
        try {
            host = hostConfigService.getEnabled(text(request, "hostId"));
            assertExecutionCapability(host);
            CommandTemplateConfig template = templateService.getByCode(text(request, "template"));
            assertTemplateAllowed(host, template.getCode());
            command = renderCommand(template.getCommandTemplate(), mapValue(request.get("parameters")));
            safetyService.assertSafe(command);
            log.info("MCP Linux command execution requested: hostId={}, hostName={}, endpoint={}:{}, env={}, tool={}, template={}, sourceTaskId={}, reason={}",
                host.getId(), host.getName(), host.getHostname(), normalizePort(host.getPort()), host.getEnvironment(),
                host.getToolName(), template.getCode(), request.get("sourceTaskId"), request.get("reason"));
            result = executeSsh(host, template.getCode(), command, request, startedAt);
        } catch (Exception ex) {
            long durationMs = Math.max(0, System.currentTimeMillis() - startedAt);
            result = new LinuxCommandResult(
                false,
                host == null ? text(request, "hostId") : host.getId(),
                host == null ? null : host.getHostname(),
                host == null ? null : host.getToolName(),
                host == null ? null : host.getEnvironment(),
                text(request, "template"),
                command,
                -1,
                "",
                "",
                durationMs,
                ex.getMessage(),
                request
            );
            log.warn("MCP Linux command execution failed before/while running: hostId={}, template={}, durationMs={}, error={}",
                result.hostId(), result.template(), durationMs, ex.getMessage());
        }
        logLinuxResult(result);
        auditService.recordLinuxCommandCall(host, result);
        return result;
    }

    public LinuxCommandResult execute(SshHostConfig host, Map<String, Object> arguments) {
        Map<String, Object> request = new LinkedHashMap<>(arguments == null ? Map.of() : arguments);
        request.put("hostId", host.getId());
        return execute(request);
    }

    public LinuxCommandResult testConnection(SshHostConfig host) {
        long startedAt = System.currentTimeMillis();
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("host", host.getHostname());
        request.put("port", host.getPort());
        request.put("username", host.getUsername());
        request.put("authType", host.getAuthType());
        log.info("MCP SSH execution capability probe started: hostName={}, endpoint={}:{}, env={}, authType={}",
            host.getName(), host.getHostname(), normalizePort(host.getPort()), host.getEnvironment(), host.getAuthType());
        try (SshClient client = SshClient.setUpDefaultClient()) {
            assertExecutionCapability(host);
            client.setServerKeyVerifier((session, remoteAddress, serverKey) -> verifyHostKey(host, serverKey));
            client.start();
            try (ClientSession session = client.connect(host.getUsername(), host.getHostname(), normalizePort(host.getPort()))
                .verify(Duration.ofMillis(connectTimeoutMs(host)))
                .getSession()) {
                authenticate(session, host);
                String probeCommand = "echo MCP_SSH_EXECUTION_PROBE";
                try (ByteArrayOutputStream stdout = new ByteArrayOutputStream();
                     ByteArrayOutputStream stderr = new ByteArrayOutputStream();
                     ClientChannel channel = session.createExecChannel(probeCommand)) {
                    channel.setOut(stdout);
                    channel.setErr(stderr);
                    channel.open().verify(Duration.ofMillis(connectTimeoutMs(host)));
                    channel.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), Duration.ofMillis(host.getCommandTimeoutMs()));
                    int exitCode = channel.getExitStatus() == null ? -1 : channel.getExitStatus();
                    if (exitCode != 0) {
                        throw new IllegalStateException("SSH probe command exited with code " + exitCode
                            + ": " + truncate(stderr.toString(StandardCharsets.UTF_8)));
                    }
                }
                long durationMs = Math.max(0, System.currentTimeMillis() - startedAt);
                LinuxCommandResult result = new LinuxCommandResult(
                    true,
                    host.getId(),
                    host.getHostname(),
                    host.getToolName(),
                    host.getEnvironment(),
                    "connection_test",
                    "echo MCP_SSH_EXECUTION_PROBE",
                    0,
                    "SSH connection authenticated and probe command executed successfully.",
                    "",
                    durationMs,
                    null,
                    request
                );
                logLinuxResult(result);
                return result;
            } finally {
                client.stop();
            }
        } catch (Exception ex) {
            long durationMs = Math.max(0, System.currentTimeMillis() - startedAt);
            LinuxCommandResult result = new LinuxCommandResult(
                false,
                host.getId(),
                host.getHostname(),
                host.getToolName(),
                host.getEnvironment(),
                "connection_test",
                null,
                -1,
                "",
                "",
                durationMs,
                ex.getMessage(),
                request
            );
            logLinuxResult(result);
            return result;
        }
    }

    private LinuxCommandResult executeSsh(SshHostConfig host, String template, String command,
                                          Map<String, Object> request, long startedAt) throws Exception {
        try (SshClient client = SshClient.setUpDefaultClient()) {
            client.setServerKeyVerifier((session, remoteAddress, serverKey) -> verifyHostKey(host, serverKey));
            client.start();
            try (ClientSession session = client.connect(host.getUsername(), host.getHostname(), normalizePort(host.getPort()))
                .verify(Duration.ofMillis(connectTimeoutMs(host)))
                .getSession()) {
                log.info("MCP Linux command SSH connected: hostId={}, hostName={}, endpoint={}:{}, env={}, template={}",
                    host.getId(), host.getName(), host.getHostname(), normalizePort(host.getPort()), host.getEnvironment(), template);
                authenticate(session, host);
                log.info("MCP Linux command SSH authenticated: hostId={}, hostName={}, authType={}, template={}",
                    host.getId(), host.getName(), host.getAuthType(), template);
                try (ByteArrayOutputStream stdout = new ByteArrayOutputStream();
                     ByteArrayOutputStream stderr = new ByteArrayOutputStream();
                     ClientChannel channel = session.createExecChannel(command)) {
                    channel.setOut(stdout);
                    channel.setErr(stderr);
                    channel.open().verify(Duration.ofMillis(connectTimeoutMs(host)));
                    channel.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), Duration.ofMillis(host.getCommandTimeoutMs()));
                    Integer exitStatus = channel.getExitStatus();
                    long durationMs = Math.max(0, System.currentTimeMillis() - startedAt);
                    int exitCode = exitStatus == null ? -1 : exitStatus;
                    return new LinuxCommandResult(
                        exitCode == 0,
                        host.getId(),
                        host.getHostname(),
                        host.getToolName(),
                        host.getEnvironment(),
                        template,
                        command,
                        exitCode,
                        stdout.toString(StandardCharsets.UTF_8),
                        stderr.toString(StandardCharsets.UTF_8),
                        durationMs,
                        exitCode == 0 ? null : "SSH command exited with code " + exitCode,
                        request
                    );
                }
            } finally {
                client.stop();
            }
        }
    }

    private void assertExecutionCapability(SshHostConfig host) {
        if (host.getCapabilitiesJson() == null || host.getCapabilitiesJson().isBlank()) {
            log.warn("MCP SSH asset has no protocol capabilities configured; allowing legacy execution: hostId={}, hostName={}, tool={}",
                host.getId(), host.getName(), host.getToolName());
            return;
        }
        Set<String> capabilities;
        try {
            capabilities = objectMapper.readValue(host.getCapabilitiesJson(), new TypeReference<List<String>>() {}).stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        } catch (Exception ex) {
            throw new IllegalArgumentException("SSH host capabilities config is invalid");
        }
        if (capabilities.stream().noneMatch(value -> value.equals("linux_command_execute")
            || value.equals("ssh_exec")
            || value.equals("ssh")
            || value.equals("shell_exec"))) {
            throw new IllegalArgumentException("SSH host does not declare Linux command execution capability: " + host.getToolName());
        }
    }

    private void logLinuxResult(LinuxCommandResult result) {
        if (result == null) {
            return;
        }
        String levelMessage = "MCP Linux command execution result: success={}, hostId={}, host={}, tool={}, env={}, template={}, exitCode={}, durationMs={}, command={}, stdout={}, stderr={}, error={}";
        if (result.success()) {
            log.info(levelMessage,
                result.success(), result.hostId(), result.host(), result.toolName(), result.environment(), result.template(),
                result.exitCode(), result.durationMs(), result.command(), truncate(result.stdout()), truncate(result.stderr()),
                result.errorMessage());
        } else {
            log.warn(levelMessage,
                result.success(), result.hostId(), result.host(), result.toolName(), result.environment(), result.template(),
                result.exitCode(), result.durationMs(), result.command(), truncate(result.stdout()), truncate(result.stderr()),
                result.errorMessage());
        }
    }

    private String truncate(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.replace("\r", "\\r").replace("\n", "\\n");
        if (normalized.length() <= LOG_OUTPUT_LIMIT) {
            return normalized;
        }
        return normalized.substring(0, LOG_OUTPUT_LIMIT) + "...<truncated>";
    }

    private boolean verifyHostKey(SshHostConfig host, java.security.PublicKey serverKey) {
        String expected = host.getHostKeyFingerprint();
        if (expected == null || expected.isBlank()) {
            return !"PROD".equalsIgnoreCase(host.getEnvironment());
        }
        String actual = KeyUtils.getFingerPrint(serverKey);
        return actual != null && actual.equalsIgnoreCase(expected.trim());
    }

    private void authenticate(ClientSession session, SshHostConfig host) throws Exception {
        if ("PRIVATE_KEY".equalsIgnoreCase(host.getAuthType())) {
            if (host.getPrivateKey() == null || host.getPrivateKey().isBlank()) {
                throw new IllegalArgumentException("SSH private key is required");
            }
            Iterable<KeyPair> identities = SecurityUtils.loadKeyPairIdentities(
                session,
                NamedResource.ofName(host.getName() + "-private-key"),
                new ByteArrayInputStream(host.getPrivateKey().getBytes(StandardCharsets.UTF_8)),
                host.getPassphrase() == null || host.getPassphrase().isBlank()
                    ? FilePasswordProvider.EMPTY
                    : FilePasswordProvider.of(host.getPassphrase())
            );
            for (KeyPair identity : identities) {
                session.addPublicKeyIdentity(identity);
            }
        } else if (host.getPassword() == null || host.getPassword().isBlank()) {
            throw new IllegalArgumentException("SSH password is required");
        } else {
            session.addPasswordIdentity(host.getPassword());
        }
        session.auth().verify(Duration.ofMillis(connectTimeoutMs(host)));
    }

    private int normalizePort(int port) {
        return port <= 0 ? 22 : Math.min(port, 65535);
    }

    private int connectTimeoutMs(SshHostConfig host) {
        return Math.max(1000, host.getConnectTimeoutMs());
    }

    private Map<String, Object> normalizeRequest(Map<String, Object> arguments) {
        Map<String, Object> request = new LinkedHashMap<>(arguments == null ? Map.of() : arguments);
        requireText(text(request, "hostId"), "hostId is required");
        requireText(text(request, "template"), "template is required");
        request.putIfAbsent("parameters", Map.of());
        request.putIfAbsent("sourceTaskId", "");
        request.putIfAbsent("reason", "");
        return request;
    }

    private void assertTemplateAllowed(SshHostConfig host, String templateCode) {
        String json = host.getAllowedCommandsJson();
        if (json == null || json.isBlank()) {
            return;
        }
        try {
            List<String> commands = objectMapper.readValue(json, new TypeReference<>() {});
            Set<String> allowed = commands.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.trim().toUpperCase(Locale.ROOT))
                .collect(Collectors.toSet());
            if (!allowed.isEmpty() && !allowed.contains(templateCode.toUpperCase(Locale.ROOT))) {
                throw new IllegalArgumentException("Command template is not allowed for this host: " + templateCode);
            }
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("allowedCommands config is invalid");
        }
    }

    private String renderCommand(String template, Map<String, Object> parameters) {
        Matcher matcher = TEMPLATE_TOKEN.matcher(template == null ? "" : template);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String name = matcher.group(1);
            String replacement = safeParameter(name, parameters.get(name));
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(buffer);
        return buffer.toString().trim();
    }

    private String safeParameter(String name, Object value) {
        String text = value == null ? "" : String.valueOf(value).trim();
        if (text.isBlank()) {
            throw new IllegalArgumentException("Template parameter is required: " + name);
        }
        if ("lines".equals(name)) {
            int lines = Integer.parseInt(text);
            if (lines < 1 || lines > 1000) {
                throw new IllegalArgumentException("lines must be between 1 and 1000");
            }
            return String.valueOf(lines);
        }
        if ("path".equals(name)) {
            if (!text.matches("[A-Za-z0-9_./:-]{1,300}")) {
                throw new IllegalArgumentException("path contains unsafe characters");
            }
            return text;
        }
        if (!text.matches("[A-Za-z0-9_.@:-]{1,128}")) {
            throw new IllegalArgumentException("parameter contains unsafe characters: " + name);
        }
        return text;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private String text(Map<String, Object> map, String key) {
        Object value = map == null ? null : map.get(key);
        return value == null ? null : String.valueOf(value).trim();
    }

    private String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
