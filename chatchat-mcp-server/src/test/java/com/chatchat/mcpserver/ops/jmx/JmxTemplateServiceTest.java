package com.chatchat.mcpserver.ops.jmx;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JmxTemplateServiceTest {

    private final JmxTemplateConfigRepository repository = mock(JmxTemplateConfigRepository.class);
    private final JmxTemplateService service = new JmxTemplateService(repository, new ObjectMapper());

    @Test
    void seedsKafkaMonitoringTemplatesSplitByMetricDomain() {
        when(repository.findByCode(JmxTemplateService.KAFKA_DEFAULT_CODE)).thenReturn(Optional.empty());
        when(repository.findAll()).thenReturn(List.of());

        service.listAll();

        ArgumentCaptor<JmxTemplateConfig> captor = ArgumentCaptor.forClass(JmxTemplateConfig.class);
        verify(repository, times(5)).save(captor.capture());
        List<JmxTemplateConfig> templates = captor.getAllValues();
        assertThat(templates).extracting(JmxTemplateConfig::getCode).containsExactly(
            "JMX_KAFKA_BROKER_TRAFFIC",
            "JMX_KAFKA_BROKER_PARTITIONS",
            "JMX_KAFKA_BROKER_REPLICATION",
            "JMX_KAFKA_BROKER_REQUESTS",
            "JMX_KAFKA_BROKER_JVM");
        assertThat(templates).allSatisfy(template -> {
            assertThat(template.isEnabled()).isFalse();
            assertThat(template.getServiceUrl()).isEqualTo(JmxTemplateService.DEFAULT_SERVICE_URL);
        });
        assertThat(templates).extracting(JmxTemplateConfig::getQueriesJson)
            .anySatisfy(json -> assertThat(json).contains("MessagesInPerSec").doesNotContain("HeapMemoryUsage"))
            .anySatisfy(json -> assertThat(json).contains("UnderReplicatedPartitions").doesNotContain("MessagesInPerSec"))
            .anySatisfy(json -> assertThat(json).contains("HeapMemoryUsage").contains("GarbageCollector,name=*")
                .doesNotContain("RequestMetrics"));
    }

    @Test
    void disablesEnabledLegacyOverviewAndMigratesConnectionToEnabledSplitTemplates() {
        JmxTemplateConfig legacy = new JmxTemplateConfig();
        legacy.setCode(JmxTemplateService.KAFKA_DEFAULT_CODE);
        legacy.setServiceUrl("service:jmx:rmi:///jndi/rmi://kafka01:9999/jmxrmi");
        legacy.setUsername("monitor");
        legacy.setPassword("secret");
        legacy.setTimeoutMs(23000);
        legacy.setEnabled(true);
        when(repository.findByCode(JmxTemplateService.KAFKA_DEFAULT_CODE)).thenReturn(Optional.of(legacy));

        service.ensureKafkaDefaults();

        ArgumentCaptor<JmxTemplateConfig> captor = ArgumentCaptor.forClass(JmxTemplateConfig.class);
        verify(repository, times(6)).save(captor.capture());
        assertThat(legacy.isEnabled()).isFalse();
        assertThat(captor.getAllValues().stream().filter(value -> value != legacy).toList())
            .hasSize(5)
            .allSatisfy(template -> {
                assertThat(template.isEnabled()).isTrue();
                assertThat(template.getServiceUrl()).isEqualTo(legacy.getServiceUrl());
                assertThat(template.getUsername()).isEqualTo("monitor");
                assertThat(template.getPassword()).isEqualTo("secret");
                assertThat(template.getTimeoutMs()).isEqualTo(23000);
            });
    }

    @Test
    void validatesObjectNamesAndForcesReadOnlyGovernance() {
        JmxTemplateConfig value = new JmxTemplateConfig();
        value.setCode("jmx_java_memory");
        value.setTitle("Java memory");
        value.setServiceUrl(JmxTemplateService.DEFAULT_SERVICE_URL);
        value.setQueriesJson("[{\"name\":\"memory\",\"objectName\":\"java.lang:type=Memory\",\"attributes\":[\"HeapMemoryUsage\"]}]");
        value.setTimeoutMs(500000);
        value.setRiskLevel("HIGH");
        value.setRuntimeAction("confirm_required");
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        JmxTemplateConfig saved = service.save(value);

        assertThat(saved.getCode()).isEqualTo("JMX_JAVA_MEMORY");
        assertThat(saved.getRiskLevel()).isEqualTo("LOW");
        assertThat(saved.getRuntimeAction()).isEqualTo("readonly");
        assertThat(saved.getTimeoutMs()).isEqualTo(60000);
    }

    @Test
    void rejectsInvalidMBeanQuery() {
        JmxTemplateConfig value = new JmxTemplateConfig();
        value.setCode("JMX_BAD");
        value.setServiceUrl(JmxTemplateService.DEFAULT_SERVICE_URL);
        value.setQueriesJson("[{\"objectName\":\"not an object name\",\"attributes\":[\"Value\"]}]");

        assertThatThrownBy(() -> service.saveTransient(value))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("JMX queries JSON is invalid");
    }
}
