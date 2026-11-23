package com.chatchat.mcpserver.ops.jmx;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import javax.management.remote.JMXConnectorServerFactory;
import javax.management.remote.JMXServiceURL;
import java.lang.management.ManagementFactory;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JmxMonitorServiceTest {
    @Test
    void registeredTemplateCollectsRealReadOnlyMetricsAndReportsOmittedAttributes() throws Exception {
        var endpoint = JMXConnectorServerFactory.newJMXConnectorServer(
            new JMXServiceURL("service:jmx:rmi://127.0.0.1"), Map.of(), ManagementFactory.getPlatformMBeanServer());
        endpoint.start();
        try {
            var template = new JmxTemplateConfig();
            template.setCode("JVM_LOCAL");
            template.setServiceUrl(endpoint.getAddress().toString());
            template.setQueriesJson("""
                [{"name":"runtime","objectName":"java.lang:type=Runtime","attributes":["Uptime","UnknownAttribute"]}]
                """);
            var registry = mock(JmxTemplateService.class);
            when(registry.getEnabledByCode(template.getCode())).thenReturn(template);
            var result = new JmxMonitorService(registry, new ObjectMapper()).execute(template.getCode());

            assertThat(result.success()).isTrue();
            assertThat(result.metrics()).singleElement().satisfies(metric ->
                assertThat(((Map<?, ?>) metric.get("attributes")).get("Uptime")).isInstanceOf(Number.class));
            assertThat(result.errors()).singleElement().satisfies(error ->
                assertThat(error.get("message")).isEqualTo("One or more configured attributes were unavailable"));
        } finally {
            endpoint.stop();
        }
    }
}
