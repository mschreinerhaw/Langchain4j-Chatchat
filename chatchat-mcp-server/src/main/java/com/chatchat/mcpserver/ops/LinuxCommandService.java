package com.chatchat.mcpserver.ops;

import com.chatchat.mcpserver.audit.InvocationAuditService;
import com.chatchat.mcpserver.template.TemplateParameterValidator;
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
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
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
    private final SafetyKernelService safetyKernelService;
    private final InvocationAuditService auditService;
    private final ObjectMapper objectMapper;
    private final TemplateParameterValidator parameterValidator;

    public LinuxCommandResult execute(Map<String, Object> arguments) {
        long startedAt = System.currentTimeMillis();
        Map<String, Object> request = normalizeRequest(arguments);
        SshHostConfig host = null;
        String command = null;
        List<String> commands = List.of();
        LinuxCommandResult result;
        try {
            host = hostConfigService.getEnabled(text(request, "hostId"));
            assertExecutionCapability(host);
            String requestedTemplate = assertTemplateAllowed(host, text(request, "template"));
            CommandTemplateConfig template = getAllowedTemplate(host, requestedTemplate);
            Map<String, Object> collectedParameters = parameterValidator.collect(
                template.getParameterSchemaJson(),
                mapValue(request.get("parameters")),
                request
            );
            Map<String, Object> parameters = parameterValidator.validate(
                template.getCode(),
                template.getParameterSchemaJson(),
                collectedParameters
            );
            request.put("parameters", parameters);
            command = renderCommand(template.getCommandTemplate(), parameters);
            commands = parseCommands(command);
            commands.forEach(safetyService::assertSafe);
            commands.forEach(safetyKernelService::assertAllowed);
            log.info("MCP Linux command execution requested: hostId={}, hostName={}, endpoint={}:{}, env={}, tool={}, template={}, sourceTaskId={}, reason={}",
                host.getId(), host.getName(), host.getHostname(), normalizePort(host.getPort()), host.getEnvironment(),
                host.getToolName(), template.getCode(), request.get("sourceTaskId"), request.get("reason"));
            result = executeSsh(host, template.getCode(), commands, request, startedAt);
        } catch (Exception ex) {
            long durationMs = Math.max(0, System.currentTimeMillis() - startedAt);
            List<LinuxCommandStepResult> failedSteps = commandFailureSteps(commands, command, ex.getMessage(), durationMs);
            result = new LinuxCommandResult(
                false,
                host == null ? text(request, "hostId") : host.getId(),
                host == null ? null : host.getHostname(),
                host == null ? null : host.getToolName(),
                host == null ? null : host.getEnvironment(),
                text(request, "template"),
                commands.isEmpty() ? command : String.join(System.lineSeparator(), commands),
                commandHash(commands),
                failedSteps,
                failedSteps.isEmpty() ? null : failedSteps.get(0).stepIndex(),
                failedSteps.isEmpty() ? null : failedSteps.get(0).command(),
                -1,
                "",
                ex.getMessage(),
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
                    sha256("echo MCP_SSH_EXECUTION_PROBE"),
                    List.of(new LinuxCommandStepResult(
                        1,
                        "echo MCP_SSH_EXECUTION_PROBE",
                        sha256("echo MCP_SSH_EXECUTION_PROBE"),
                        0,
                        "SSH connection authenticated and probe command executed successfully.",
                        "",
                        durationMs,
                        true
                    )),
                    null,
                    null,
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
                null,
                List.of(),
                null,
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

    private LinuxCommandResult executeSsh(SshHostConfig host, String template, List<String> commands,
                                          Map<String, Object> request, long startedAt) throws Exception {
        List<LinuxCommandStepResult> steps = new ArrayList<>();
        StringBuilder stdoutAll = new StringBuilder();
        StringBuilder stderrAll = new StringBuilder();
        int lastExitCode = 0;
        for (int index = 0; index < commands.size(); index++) {
            String current = commands.get(index);
            log.info("MCP Linux command SSH exec command: hostId={}, template={}, step={}/{}, command={}",
                host.getId(), template, index + 1, commands.size(), current);
            long stepStartedAt = System.currentTimeMillis();
            LinuxCommandStepResult step;
            try {
                step = executeSingleSshCommand(host, current, index + 1);
            } catch (Exception ex) {
                step = failedCommandStep(current, index + 1, ex.getMessage(),
                    Math.max(0, System.currentTimeMillis() - stepStartedAt));
            }
            steps.add(step);
            lastExitCode = step.exitCode();
            log.info("MCP Linux command SSH step completed: hostId={}, hostName={}, template={}, step={}/{}, exitCode={}, success={}, durationMs={}, stdout={}, stderr={}",
                host.getId(), host.getName(), template, step.stepIndex(), commands.size(), step.exitCode(), step.success(),
                step.durationMs(), truncate(step.stdout()), truncate(step.stderr()));
            appendCommandOutput(stdoutAll, step.stepIndex(), step.command(), step.stdout());
            appendCommandOutput(stderrAll, step.stepIndex(), step.command(), step.stderr());
        }
        long durationMs = Math.max(0, System.currentTimeMillis() - startedAt);
        LinuxCommandStepResult firstNonZeroStep = firstNonZeroStep(steps);
        LinuxCommandStepResult firstTransportFailure = firstTransportFailure(steps);
        return new LinuxCommandResult(
            firstTransportFailure == null,
            host.getId(),
            host.getHostname(),
            host.getToolName(),
            host.getEnvironment(),
            template,
            String.join(System.lineSeparator(), commands),
            commandHash(commands),
            steps,
            firstNonZeroStep == null ? null : firstNonZeroStep.stepIndex(),
            firstNonZeroStep == null ? null : firstNonZeroStep.command(),
            lastExitCode,
            stdoutAll.toString(),
            stderrAll.toString(),
            durationMs,
            firstTransportFailure == null
                ? null
                : "SSH command step " + firstTransportFailure.stepIndex() + " did not complete: " + truncate(firstTransportFailure.stderr()),
            request
        );
    }

    private LinuxCommandStepResult firstNonZeroStep(List<LinuxCommandStepResult> steps) {
        if (steps == null) {
            return null;
        }
        return steps.stream()
            .filter(step -> step != null && !step.success())
            .findFirst()
            .orElse(null);
    }

    private LinuxCommandStepResult firstTransportFailure(List<LinuxCommandStepResult> steps) {
        if (steps == null) {
            return null;
        }
        return steps.stream()
            .filter(step -> step != null && step.exitCode() == -1)
            .findFirst()
            .orElse(null);
    }

    private LinuxCommandStepResult executeSingleSshCommand(SshHostConfig host, String command, int stepIndex) throws Exception {
        long startedAt = System.currentTimeMillis();
        try (SshClient client = SshClient.setUpDefaultClient()) {
            client.setServerKeyVerifier((session, remoteAddress, serverKey) -> verifyHostKey(host, serverKey));
            client.start();
            try (ClientSession session = client.connect(host.getUsername(), host.getHostname(), normalizePort(host.getPort()))
                .verify(Duration.ofMillis(connectTimeoutMs(host)))
                .getSession()) {
                String sshCommand = sshLoginShellCommand(command);
                log.info("MCP Linux command SSH connected: hostId={}, hostName={}, endpoint={}:{}, env={}, step={}, command={}, sshCommand={}",
                    host.getId(), host.getName(), host.getHostname(), normalizePort(host.getPort()), host.getEnvironment(),
                    stepIndex, command, sshCommand);
                authenticate(session, host);
                log.info("MCP Linux command SSH authenticated: hostId={}, hostName={}, authType={}, step={}",
                    host.getId(), host.getName(), host.getAuthType(), stepIndex);
                try (ByteArrayOutputStream stdout = new ByteArrayOutputStream();
                     ByteArrayOutputStream stderr = new ByteArrayOutputStream();
                     ClientChannel channel = session.createExecChannel(sshCommand)) {
                    channel.setOut(stdout);
                    channel.setErr(stderr);
                    channel.open().verify(Duration.ofMillis(connectTimeoutMs(host)));
                    channel.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), Duration.ofMillis(host.getCommandTimeoutMs()));
                    Integer exitStatus = channel.getExitStatus();
                    int exitCode = exitStatus == null ? -1 : exitStatus;
                    long durationMs = Math.max(0, System.currentTimeMillis() - startedAt);
                    return new LinuxCommandStepResult(
                        stepIndex,
                        command,
                        sha256(command),
                        exitCode,
                        stdout.toString(StandardCharsets.UTF_8),
                        stderr.toString(StandardCharsets.UTF_8),
                        durationMs,
                        exitCode == 0
                    );
                }
            } finally {
                client.stop();
            }
        }
    }

    String sshLoginShellCommand(String command) {
        String original = command == null ? "" : command;
        String prelude = "if [ -f ~/.bashrc ]; then . ~/.bashrc >/dev/null 2>&1 || true; fi; ";
        return "bash -lc " + shellSingleQuote(prelude + original);
    }

    private String shellSingleQuote(String value) {
        return "'" + (value == null ? "" : value).replace("'", "'\\''") + "'";
    }

    private List<LinuxCommandStepResult> commandFailureSteps(List<String> commands, String command,
                                                             String errorMessage, long durationMs) {
        List<String> values = commands == null || commands.isEmpty()
            ? parseFallbackCommand(command)
            : commands;
        if (values.isEmpty()) {
            return List.of();
        }
        List<LinuxCommandStepResult> steps = new ArrayList<>();
        for (int index = 0; index < values.size(); index++) {
            steps.add(failedCommandStep(values.get(index), index + 1, errorMessage, index == 0 ? durationMs : 0L));
        }
        return steps;
    }

    private LinuxCommandStepResult failedCommandStep(String command, int stepIndex, String errorMessage, long durationMs) {
        return new LinuxCommandStepResult(
            stepIndex,
            command,
            sha256(command),
            -1,
            "",
            errorMessage == null ? "" : errorMessage,
            durationMs,
            false
        );
    }

    private List<String> parseFallbackCommand(String command) {
        if (command == null || command.isBlank()) {
            return List.of();
        }
        return command.lines()
            .map(String::trim)
            .filter(value -> !value.isBlank())
            .toList();
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
        Map<String, Object> diagnostics = linuxDiagnostics(result);
        String levelMessage = "MCP Linux command execution result: success={}, hostId={}, host={}, tool={}, env={}, template={}, exitCode={}, durationMs={}, command={}, stdout={}, stderr={}, diagnostics={}, error={}";
        if (result.success()) {
            log.info(levelMessage,
                result.success(), result.hostId(), result.host(), result.toolName(), result.environment(), result.template(),
                result.exitCode(), result.durationMs(), result.command(), truncate(result.stdout()), truncate(result.stderr()),
                diagnostics, result.errorMessage());
        } else {
            log.warn(levelMessage,
                result.success(), result.hostId(), result.host(), result.toolName(), result.environment(), result.template(),
                result.exitCode(), result.durationMs(), result.command(), truncate(result.stdout()), truncate(result.stderr()),
                diagnostics, result.errorMessage());
        }
    }

    private Map<String, Object> linuxDiagnostics(LinuxCommandResult result) {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("schemaVersion", "linux_command_diagnostics.v1");
        diagnostics.put("hostId", result.hostId());
        diagnostics.put("host", result.host());
        diagnostics.put("toolName", result.toolName());
        diagnostics.put("environment", result.environment());
        diagnostics.put("template", result.template());
        diagnostics.put("sourceTaskId", result.request() == null ? null : result.request().get("sourceTaskId"));
        diagnostics.put("reason", result.request() == null ? null : result.request().get("reason"));
        diagnostics.put("parameters", result.request() == null ? Map.of() : mapValue(result.request().get("parameters")));
        diagnostics.put("commandHash", result.commandHash());
        diagnostics.put("stepCount", result.steps().size());
        diagnostics.put("failedStepIndex", result.failedStepIndex());
        diagnostics.put("failedCommandHash", result.failedCommand() == null ? null : sha256(result.failedCommand()));
        diagnostics.put("exitCode", result.exitCode());
        diagnostics.put("transportSuccess", result.success());
        diagnostics.put("commandSuccess", result.steps().stream().allMatch(LinuxCommandStepResult::success));
        diagnostics.put("nonZeroStepIndexes", result.steps().stream()
            .filter(step -> !step.success())
            .map(LinuxCommandStepResult::stepIndex)
            .toList());
        diagnostics.put("durationMs", result.durationMs());
        diagnostics.put("stdoutLength", result.stdout() == null ? 0 : result.stdout().length());
        diagnostics.put("stderrLength", result.stderr() == null ? 0 : result.stderr().length());
        diagnostics.put("steps", result.steps().stream()
            .map(step -> {
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("stepIndex", step.stepIndex());
                value.put("commandHash", step.commandHash());
                value.put("exitCode", step.exitCode());
                value.put("success", step.success());
                value.put("durationMs", step.durationMs());
                value.put("stdoutLength", step.stdout() == null ? 0 : step.stdout().length());
                value.put("stderrLength", step.stderr() == null ? 0 : step.stderr().length());
                return value;
            })
            .toList());
        return diagnostics;
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

    private String assertTemplateAllowed(SshHostConfig host, String templateCode) {
        Set<String> allowed = allowedTemplateCodes(host, true);
        String normalizedTemplateCode = templateCode == null ? "" : templateCode.toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalizedTemplateCode)) {
            throw new IllegalArgumentException("Command template is not authorized for this host: " + templateCode
                + ". Use only an existing templateId returned by ssh_template_query for this asset. Allowed templates: " + allowed
                + ". Do not invent template names.");
        }
        return normalizedTemplateCode;
    }

    private CommandTemplateConfig getAllowedTemplate(SshHostConfig host, String requestedCode) {
        String code = requireText(requestedCode, "template is required").trim().toUpperCase(Locale.ROOT);
        try {
            return templateService.getByCode(code);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Authorized command template is not registered or is disabled: " + code
                + ". This asset allowed the template, but the template registry cannot load it. Ask an administrator to repair the existing allowlist/template registry entry. Allowed templates for host "
                + host.getToolName() + ": " + allowedTemplateCodes(host, false));
        }
    }

    private Set<String> allowedTemplateCodes(SshHostConfig host, boolean failWhenEmpty) {
        String json = host.getAllowedCommandsJson();
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("No command templates are allowed for this host: " + host.getToolName());
        }
        try {
            List<String> commands = objectMapper.readValue(json, new TypeReference<>() {});
            Set<String> allowed = commands.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.trim().toUpperCase(Locale.ROOT))
                .collect(Collectors.toSet());
            if (allowed.isEmpty() && failWhenEmpty) {
                throw new IllegalArgumentException("No command templates are allowed for this host: " + host.getToolName());
            }
            return allowed;
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

    private List<String> parseCommands(String command) {
        String text = command == null ? "" : command.trim();
        if (text.startsWith("[")) {
            try {
                List<String> jsonCommands = objectMapper.readValue(text, new TypeReference<>() {});
                List<String> commands = jsonCommands.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(String::trim)
                    .toList();
                if (commands.isEmpty()) {
                    throw new IllegalArgumentException("Command steps cannot be empty");
                }
                return commands;
            } catch (IllegalArgumentException ex) {
                throw ex;
            } catch (Exception ex) {
                throw new IllegalArgumentException("Command steps JSON array is invalid");
            }
        }
        List<String> commands = splitLinesOutsideQuotes(text).stream()
            .map(String::trim)
            .filter(value -> !value.isBlank())
            .toList();
        if (commands.isEmpty()) {
            throw new IllegalArgumentException("Command cannot be empty");
        }
        return commands;
    }

    private List<String> splitLinesOutsideQuotes(String command) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean singleQuoted = false;
        boolean doubleQuoted = false;
        for (int index = 0; index < command.length(); index++) {
            char ch = command.charAt(index);
            if (ch == '\'' && !doubleQuoted) {
                singleQuoted = !singleQuoted;
            } else if (ch == '"' && !singleQuoted) {
                doubleQuoted = !doubleQuoted;
            }
            if ((ch == '\n' || ch == '\r') && !singleQuoted && !doubleQuoted) {
                parts.add(current.toString());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        parts.add(current.toString());
        return parts;
    }

    private String commandHash(List<String> commands) {
        if (commands == null || commands.isEmpty()) {
            return null;
        }
        return sha256(String.join("\n", commands));
    }

    private String sha256(String value) {
        if (value == null) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to calculate command hash", ex);
        }
    }

    private void appendCommandOutput(StringBuilder output, int step, String command, String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        output.append(">>> [").append(step).append("] ").append(command).append(System.lineSeparator());
        output.append(text);
        if (!text.endsWith("\n") && !text.endsWith("\r")) {
            output.append(System.lineSeparator());
        }
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
