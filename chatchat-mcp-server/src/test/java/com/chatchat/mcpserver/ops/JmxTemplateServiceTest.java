package com.chatchat.mcpserver.ops;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JmxTemplateServiceTest {

    private final JmxTemplateConfigRepository repository = mock(JmxTemplateConfigRepository.class);
    private final JmxTemplateService service = new JmxTemplateService(repository, new ObjectMapper());

    @Test
    void seedsDisabledKafkaMonitoringTemplateWithEditablePlaceholderAddress() {
        when(repository.findByCode(JmxTemplateService.KAFKA_DEFAULT_CODE)).thenReturn(Optional.empty());
        when(repository.findAll()).thenReturn(List.of());

        service.listAll();

        ArgumentCaptor<JmxTemplateConfig> captor = ArgumentCaptor.forClass(JmxTemplateConfig.class);
        verify(repository).save(captor.capture());
        JmxTemplateConfig kafka = captor.getValue();
        assertThat(kafka.getCode()).isEqualTo("JMX_KAFKA_BROKER_OVERVIEW");
        assertThat(kafka.isEnabled()).isFalse();
        assertThat(kafka.getServiceUrl()).isEqualTo("service:jmx:rmi:///jndi/rmi://127.0.0.1:9999/jmxrmi");
        assertThat(kafka.getQueriesJson())
            .contains("MessagesInPerSec")
            .contains("UnderReplicatedPartitions")
            .contains("OfflinePartitionsCount")
            .contains("HeapMemoryUsage")
            .contains("GarbageCollector,name=*");
        assertThat(kafka.getIntentSignalsJson()).contains("Kafka 监控").contains("副本不同步");
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
