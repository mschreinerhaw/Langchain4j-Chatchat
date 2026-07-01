package com.chatchat.mcpserver.ops;

import com.chatchat.mcpserver.audit.InvocationAuditService;
import com.chatchat.mcpserver.template.TemplateParameterValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LinuxCommandServiceTest {

    private final SshHostConfigService hostConfigService = mock(SshHostConfigService.class);
    private final CommandTemplateService templateService = mock(CommandTemplateService.class);
    private final InvocationAuditService auditService = mock(InvocationAuditService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final LinuxCommandService linuxCommandService = new LinuxCommandService(
        hostConfigService,
        templateService,
        new LinuxCommandSafetyService(),
        new SafetyKernelService(),
        auditService,
        objectMapper,
        new TemplateParameterValidator(objectMapper)
    );

    @Test
    void deniesWhenHostAllowedCommandsAreNotConfigured() {
        SshHostConfig host = host("host-1", null);
        CommandTemplateConfig template = template("CHECK_UPTIME", "uptime");
        when(hostConfigService.getEnabled("host-1")).thenReturn(host);
        when(templateService.getByCode("CHECK_UPTIME")).thenReturn(template);

        LinuxCommandResult result = linuxCommandService.execute(Map.of(
            "hostId", "host-1",
            "template", "CHECK_UPTIME"
        ));

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("No command templates are allowed");
    }

    @Test
    void safetyKernelStillBlocksHardForbiddenTemplateCommands() {
        SshHostConfig host = host("host-1", "[\"DANGEROUS\"]");
        CommandTemplateConfig template = template("DANGEROUS", "rm -rf /");
        when(hostConfigService.getEnabled("host-1")).thenReturn(host);
        when(templateService.getByCode("DANGEROUS")).thenReturn(template);

        LinuxCommandResult result = linuxCommandService.execute(Map.of(
            "hostId", "host-1",
            "template", "DANGEROUS"
        ));

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("safety kernel");
        Map<?, ?> execution = result.execution();
        Map<?, ?> step = (Map<?, ?>) ((List<?>) execution.get("steps")).get(0);
        Map<?, ?> input = (Map<?, ?>) step.get("input");
        Map<?, ?> output = (Map<?, ?>) step.get("output");
        assertThat(execution.get("schemaVersion")).isEqualTo("execution_unit.v1");
        assertThat(step.get("stepType")).isEqualTo("command");
        assertThat(input.get("command")).isEqualTo("rm -rf /");
        assertThat(output.get("exitCode")).isEqualTo(-1);
        assertThat(output.get("stderr")).asString().contains("safety kernel");
    }

    @Test
    void rejectsInventedTemplateNamesBeforeRegistryLookup() {
        SshHostConfig host = host("host-1", "[\"CHECK_SYSTEM_OVERVIEW\"]");
        when(hostConfigService.getEnabled("host-1")).thenReturn(host);

        LinuxCommandResult result = linuxCommandService.execute(Map.of(
            "hostId", "host-1",
            "template", "DOCKER_PS"
        ));

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("not authorized", "template_query", "CHECK_SYSTEM_OVERVIEW", "Do not invent");
        verify(templateService, never()).getByCode("DOCKER_PS");
    }

    @Test
    void validatesTemplateParametersBeforeRenderingCommand() {
        SshHostConfig host = host("host-1", "[\"TAIL_LOG\"]");
        CommandTemplateConfig template = template("TAIL_LOG", "tail -n {{lines}} {{path}}");
        template.setParameterSchemaJson("""
            {"type":"object","properties":{"lines":{"type":"integer","minimum":1,"maximum":1000},"path":{"type":"string","pattern":"^[A-Za-z0-9_./:-]{1,300}$"}},"required":["lines","path"]}
            """);
        when(hostConfigService.getEnabled("host-1")).thenReturn(host);
        when(templateService.getByCode("TAIL_LOG")).thenReturn(template);

        LinuxCommandResult result = linuxCommandService.execute(Map.of(
            "hostId", "host-1",
            "template", "TAIL_LOG",
            "parameters", Map.of("path", "/var/log/app.log")
        ));

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("Template parameter is required: lines", "parameters.lines");
    }

    @Test
    void rejectsUnsafeServiceNameBeforeRenderingCommand() {
        SshHostConfig host = host("host-1", "[\"CHECK_SERVICE_INFO\"]");
        CommandTemplateConfig template = template("CHECK_SERVICE_INFO", "pgrep -af {{serviceName}}");
        template.setParameterSchemaJson("""
            {"type":"object","properties":{"serviceName":{"type":"string","pattern":"^[A-Za-z0-9_][A-Za-z0-9_.@:-]{0,127}$"}},"required":["serviceName"]}
            """);
        when(hostConfigService.getEnabled("host-1")).thenReturn(host);
        when(templateService.getByCode("CHECK_SERVICE_INFO")).thenReturn(template);

        LinuxCommandResult result = linuxCommandService.execute(Map.of(
            "hostId", "host-1",
            "template", "CHECK_SERVICE_INFO",
            "parameters", Map.of("serviceName", "-nginx")
        ));

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("serviceName", "does not match required pattern");
    }

    @Test
    void wrapsSshCommandsInLoginShellSoProfileEnvironmentIsAvailable() {
        String command = linuxCommandService.sshLoginShellCommand("jps -l | grep 'MainApp'");

        assertThat(command).startsWith("bash -lc ");
        assertThat(command).contains(". ~/.bashrc");
        assertThat(command).contains("jps -l");
        assertThat(command).contains("grep '\\''MainApp'\\'''");
    }

    private SshHostConfig host(String id, String allowedCommandsJson) {
        SshHostConfig host = new SshHostConfig();
        host.setId(id);
        host.setName("host");
        host.setToolName("ssh_host");
        host.setHostname("127.0.0.1");
        host.setUsername("root");
        host.setAllowedCommandsJson(allowedCommandsJson);
        return host;
    }

    private CommandTemplateConfig template(String code, String command) {
        CommandTemplateConfig template = new CommandTemplateConfig();
        template.setCode(code);
        template.setCommandTemplate(command);
        template.setEnabled(true);
        return template;
    }
}
