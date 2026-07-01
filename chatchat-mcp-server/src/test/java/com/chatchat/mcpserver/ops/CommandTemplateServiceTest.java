package com.chatchat.mcpserver.ops;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommandTemplateServiceTest {

    private final CommandTemplateConfigRepository repository = mock(CommandTemplateConfigRepository.class);

    @Test
    void doesNotSeedDefaultTemplatesUnlessExplicitlyEnabled() {
        CommandTemplateSeedProperties properties = new CommandTemplateSeedProperties();
        CommandTemplateService service = new CommandTemplateService(repository, new ObjectMapper(), properties);
        when(repository.findByEnabledTrueOrderByCodeAsc()).thenReturn(List.of());

        service.listEnabled();

        verify(repository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void enabledDefaultSeedDoesNotIncludeRuntimeSpecificOverviewCommands() {
        CommandTemplateSeedProperties properties = new CommandTemplateSeedProperties();
        properties.setSeedDefaultsEnabled(true);
        CommandTemplateService service = new CommandTemplateService(repository, new ObjectMapper(), properties);
        when(repository.findByCode(anyString())).thenReturn(Optional.empty());
        when(repository.findByEnabledTrueOrderByCodeAsc()).thenReturn(List.of());

        service.listEnabled();

        ArgumentCaptor<CommandTemplateConfig> captor = ArgumentCaptor.forClass(CommandTemplateConfig.class);
        verify(repository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        List<CommandTemplateConfig> saved = captor.getAllValues();
        assertThat(saved).extracting(CommandTemplateConfig::getCode)
            .contains("CHECK_SYSTEM_OVERVIEW", "CHECK_SERVICE_INFO");
        assertThat(saved)
            .filteredOn(template -> "CHECK_SERVICE_INFO".equals(template.getCode()))
            .singleElement()
            .satisfies(template -> {
                assertThat(template.getCommandTemplate())
                    .contains("systemctl show {{serviceName}}")
                    .contains("systemctl status {{serviceName}}")
                    .contains("pgrep -af {{serviceName}}")
                    .contains("ss -tulnp");
                assertThat(template.getParameterSchemaJson()).contains("serviceName");
                assertThat(template.getCategory()).isEqualTo("service_diagnostic");
            });
        assertThat(saved).extracting(CommandTemplateConfig::getCommandTemplate)
            .noneSatisfy(command -> assertThat(command).containsIgnoringCase("docker"));
    }

    @Test
    void repairsLegacyJavaProcessTemplateToUsePsFallback() {
        CommandTemplateSeedProperties properties = new CommandTemplateSeedProperties();
        properties.setSeedDefaultsEnabled(true);
        CommandTemplateConfig legacy = new CommandTemplateConfig();
        legacy.setCode("CHECK_JAVA_PROCESS");
        legacy.setTitle("Java process");
        legacy.setDescription("Read Java processes.");
        legacy.setCommandTemplate("jps -lv");
        legacy.setParameterSchemaJson("{}");
        legacy.setIntentSignalsJson("[]");
        legacy.setEnabled(true);
        CommandTemplateService service = new CommandTemplateService(repository, new ObjectMapper(), properties);
        when(repository.findByCode("CHECK_JAVA_PROCESS")).thenReturn(Optional.of(legacy));
        when(repository.findByCode(org.mockito.ArgumentMatchers.argThat(code -> !"CHECK_JAVA_PROCESS".equals(code))))
            .thenReturn(Optional.empty());
        when(repository.findByEnabledTrueOrderByCodeAsc()).thenReturn(List.of(legacy));

        service.listEnabled();

        ArgumentCaptor<CommandTemplateConfig> captor = ArgumentCaptor.forClass(CommandTemplateConfig.class);
        verify(repository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        assertThat(captor.getAllValues()).anySatisfy(saved -> {
            if ("CHECK_JAVA_PROCESS".equals(saved.getCode())) {
                assertThat(saved.getCommandTemplate())
                    .contains("jps -lv")
                    .contains("ps -eo")
                    .contains("awk");
            }
        });
    }
}
