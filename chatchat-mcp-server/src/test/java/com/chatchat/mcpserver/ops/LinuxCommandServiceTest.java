package com.chatchat.mcpserver.ops;

import com.chatchat.mcpserver.audit.InvocationAuditService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LinuxCommandServiceTest {

    private final SshHostConfigService hostConfigService = mock(SshHostConfigService.class);
    private final CommandTemplateService templateService = mock(CommandTemplateService.class);
    private final InvocationAuditService auditService = mock(InvocationAuditService.class);
    private final LinuxCommandService linuxCommandService = new LinuxCommandService(
        hostConfigService,
        templateService,
        new LinuxCommandSafetyService(),
        new SafetyKernelService(),
        auditService,
        new ObjectMapper()
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
