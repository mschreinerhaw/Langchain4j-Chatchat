package com.chatchat.mcpserver.ops.jmx;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import javax.management.Attribute;
import javax.management.AttributeList;
import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.TabularData;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class JmxMonitorService {

    private final JmxTemplateService templateService;
    private final ObjectMapper objectMapper;

    public JmxMonitorResult execute(String templateCode) {
        return execute(templateService.getEnabledByCode(templateCode));
    }

    public JmxMonitorResult test(JmxTemplateConfig template) {
        return execute(template);
    }

    JmxMonitorResult execute(JmxTemplateConfig template) {
        long started = System.currentTimeMillis();
        List<Map<String, Object>> metrics = new ArrayList<>();
        List<Map<String, Object>> errors = new ArrayList<>();
        Map<String, Object> environment = new LinkedHashMap<>();
        environment.put("jmx.remote.x.request.waiting.timeout", (long) Math.max(1000, template.getTimeoutMs()));
        if (template.getUsername() != null && !template.getUsername().isBlank()) {
            environment.put(JMXConnector.CREDENTIALS,
                new String[] {template.getUsername(), template.getPassword() == null ? "" : template.getPassword()});
        }
        try (JMXConnector connector = JMXConnectorFactory.connect(new JMXServiceURL(template.getServiceUrl()), environment)) {
            MBeanServerConnection connection = connector.getMBeanServerConnection();
            for (JmxQuery query : queries(template.getQueriesJson())) {
                collect(connection, query, metrics, errors);
            }
            return new JmxMonitorResult(true, template.getCode(), redact(template.getServiceUrl()), metrics, errors,
                System.currentTimeMillis() - started, null);
        } catch (Exception ex) {
            return new JmxMonitorResult(false, template.getCode(), redact(template.getServiceUrl()), metrics, errors,
                System.currentTimeMillis() - started, ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    private void collect(MBeanServerConnection connection, JmxQuery query,
                         List<Map<String, Object>> metrics, List<Map<String, Object>> errors) {
        try {
            ObjectName pattern = new ObjectName(query.objectName());
            Set<ObjectName> names = pattern.isPattern() ? connection.queryNames(pattern, null) : Set.of(pattern);
            if (names.isEmpty()) {
                errors.add(error(query.name(), query.objectName(), "MBean was not found"));
                return;
            }
            for (ObjectName name : names) {
                AttributeList values = connection.getAttributes(name, query.attributes().toArray(String[]::new));
                Map<String, Object> attributes = new LinkedHashMap<>();
                for (Attribute attribute : values.asList()) {
                    attributes.put(attribute.getName(), normalizeValue(attribute.getValue()));
                }
                metrics.add(Map.of(
                    "name", query.name(),
                    "objectName", name.getCanonicalName(),
                    "attributes", attributes
                ));
            }
        } catch (Exception ex) {
            errors.add(error(query.name(), query.objectName(), ex.getClass().getSimpleName() + ": " + ex.getMessage()));
        }
    }

    private Object normalizeValue(Object value) {
        if (value instanceof CompositeData composite) {
            Map<String, Object> result = new LinkedHashMap<>();
            composite.getCompositeType().keySet().forEach(key -> result.put(key, normalizeValue(composite.get(key))));
            return result;
        }
        if (value instanceof TabularData table) {
            return table.values().stream().map(this::normalizeValue).toList();
        }
        if (value != null && value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            List<Object> result = new ArrayList<>(length);
            for (int index = 0; index < length; index++) result.add(normalizeValue(java.lang.reflect.Array.get(value, index)));
            return result;
        }
        return value;
    }

    private List<JmxQuery> queries(String json) throws Exception {
        List<Map<String, Object>> values = objectMapper.readValue(json, new TypeReference<>() {});
        return values.stream().map(value -> new JmxQuery(
            String.valueOf(value.getOrDefault("name", value.get("objectName"))),
            String.valueOf(value.get("objectName")),
            value.get("attributes") instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of()
        )).toList();
    }

    private Map<String, Object> error(String name, String objectName, String message) {
        return Map.of("name", name, "objectName", objectName, "message", message == null ? "Unknown error" : message);
    }

    private String redact(String serviceUrl) {
        return serviceUrl == null ? null : serviceUrl.replaceAll("(?i)(password|token|secret)=[^&;]+", "$1=***");
    }

    private record JmxQuery(String name, String objectName, List<String> attributes) {
    }
}
