package com.chatchat.mcpserver.ops;

import com.chatchat.agents.protocol.ModelProtocolJson;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.management.ObjectName;
import javax.management.remote.JMXServiceURL;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class JmxTemplateService {

    public static final String KAFKA_DEFAULT_CODE = "JMX_KAFKA_BROKER_OVERVIEW";
    public static final String DEFAULT_SERVICE_URL = "service:jmx:rmi:///jndi/rmi://127.0.0.1:9999/jmxrmi";

    private final JmxTemplateConfigRepository repository;
    private final ObjectMapper objectMapper;

    @Transactional
    public List<JmxTemplateConfig> listAll() {
        ensureKafkaDefault();
        return repository.findAll().stream().sorted(Comparator.comparing(JmxTemplateConfig::getCode)).toList();
    }

    @Transactional
    public List<JmxTemplateConfig> listEnabled() {
        ensureKafkaDefault();
        return repository.findByEnabledTrueOrderByCodeAsc();
    }

    public JmxTemplateConfig getEnabledByCode(String code) {
        ensureKafkaDefault();
        return repository.findByCode(normalizeCode(code)).filter(JmxTemplateConfig::isEnabled)
            .orElseThrow(() -> new IllegalArgumentException("JMX template not found or disabled: " + code));
    }

    public JmxTemplateConfig getById(String id) {
        ensureKafkaDefault();
        return repository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("JMX template not found: " + id));
    }

    @Transactional
    public JmxTemplateConfig save(JmxTemplateConfig request) {
        normalize(request);
        return repository.save(request);
    }

    public JmxTemplateConfig saveTransient(JmxTemplateConfig request) {
        normalize(request);
        return request;
    }

    @Transactional
    public JmxTemplateConfig update(String id, JmxTemplateConfig request) {
        JmxTemplateConfig value = repository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("JMX template not found: " + id));
        value.setCode(text(request.getCode(), value.getCode()));
        value.setTitle(text(request.getTitle(), value.getTitle()));
        value.setDescription(request.getDescription());
        value.setServiceUrl(text(request.getServiceUrl(), value.getServiceUrl()));
        value.setUsername(request.getUsername());
        if (request.getPassword() != null) {
            value.setPassword(request.getPassword());
        }
        value.setQueriesJson(text(request.getQueriesJson(), value.getQueriesJson()));
        value.setIntentSignalsJson(request.getIntentSignalsJson());
        value.setCategory(text(request.getCategory(), value.getCategory()));
        value.setTimeoutMs(request.getTimeoutMs());
        value.setEnabled(request.isEnabled());
        normalize(value);
        return repository.save(value);
    }

    @Transactional
    public void delete(String id) {
        JmxTemplateConfig value = repository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("JMX template not found: " + id));
        if (KAFKA_DEFAULT_CODE.equals(value.getCode())) {
            value.setEnabled(false);
            repository.save(value);
        } else {
            repository.delete(value);
        }
    }

    @Transactional
    public void ensureKafkaDefault() {
        if (repository.findByCode(KAFKA_DEFAULT_CODE).isPresent()) {
            return;
        }
        JmxTemplateConfig value = new JmxTemplateConfig();
        value.setCode(KAFKA_DEFAULT_CODE);
        value.setTitle("Kafka Broker JMX overview");
        value.setDescription("Kafka broker health, traffic, request, replica, controller, JVM memory and GC monitoring.");
        value.setServiceUrl(DEFAULT_SERVICE_URL);
        value.setQueriesJson(ModelProtocolJson.compact(kafkaQueries()));
        value.setIntentSignalsJson(ModelProtocolJson.compact(List.of(
            "Kafka 监控", "Kafka 积压分析", "Kafka Broker", "分区离线", "副本不同步", "ISR 收缩",
            "消息流量", "请求延迟", "JVM 内存", "Kafka JMX")));
        value.setCategory("kafka_monitoring");
        value.setRiskLevel("LOW");
        value.setRuntimeAction("readonly");
        value.setTimeoutMs(10000);
        value.setEnabled(false);
        repository.save(value);
    }

    private void normalize(JmxTemplateConfig value) {
        value.setCode(normalizeCode(value.getCode()));
        value.setTitle(text(value.getTitle(), value.getCode()));
        value.setServiceUrl(require(value.getServiceUrl(), "JMX service URL is required"));
        try {
            new JMXServiceURL(value.getServiceUrl());
        } catch (Exception ex) {
            throw new IllegalArgumentException("JMX service URL is invalid", ex);
        }
        value.setQueriesJson(normalizeQueries(value.getQueriesJson()));
        value.setIntentSignalsJson(normalizeArray(value.getIntentSignalsJson()));
        value.setCategory(text(value.getCategory(), "java_monitoring").toLowerCase(Locale.ROOT));
        value.setRiskLevel("LOW");
        value.setRuntimeAction("readonly");
        value.setTimeoutMs(Math.max(1000, Math.min(value.getTimeoutMs(), 60000)));
    }

    @SuppressWarnings("unchecked")
    private String normalizeQueries(String json) {
        try {
            Object parsed = objectMapper.readValue(require(json, "JMX queries cannot be empty"), Object.class);
            if (!(parsed instanceof List<?> list) || list.isEmpty()) {
                throw new IllegalArgumentException("JMX queries must be a non-empty JSON array");
            }
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> query)) {
                    throw new IllegalArgumentException("Each JMX query must be an object");
                }
                new ObjectName(require(String.valueOf(query.get("objectName")), "JMX objectName is required"));
                Object attributes = query.get("attributes");
                if (!(attributes instanceof List<?> values) || values.isEmpty()) {
                    throw new IllegalArgumentException("JMX query attributes must be a non-empty array");
                }
            }
            return ModelProtocolJson.compact(parsed);
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("JMX queries JSON is invalid", ex);
        }
    }

    private String normalizeArray(String json) {
        if (json == null || json.isBlank()) return "[]";
        try {
            Object parsed = objectMapper.readValue(json, Object.class);
            return parsed instanceof List<?> ? ModelProtocolJson.compact(parsed) : "[]";
        } catch (Exception ex) {
            return "[]";
        }
    }

    private String normalizeCode(String code) {
        String value = require(code, "JMX template code is required").toUpperCase(Locale.ROOT);
        if (!value.matches("[A-Z0-9_-]{2,128}")) throw new IllegalArgumentException("Invalid JMX template code");
        return value;
    }

    private String require(String value, String message) {
        if (value == null || value.isBlank() || "null".equals(value)) throw new IllegalArgumentException(message);
        return value.trim();
    }

    private String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private List<Map<String, Object>> kafkaQueries() {
        return List.of(
            query("messages_in", "kafka.server:type=BrokerTopicMetrics,name=MessagesInPerSec", "Count", "OneMinuteRate", "FiveMinuteRate"),
            query("bytes_in", "kafka.server:type=BrokerTopicMetrics,name=BytesInPerSec", "Count", "OneMinuteRate"),
            query("bytes_out", "kafka.server:type=BrokerTopicMetrics,name=BytesOutPerSec", "Count", "OneMinuteRate"),
            query("partition_count", "kafka.server:type=ReplicaManager,name=PartitionCount", "Value"),
            query("leader_count", "kafka.server:type=ReplicaManager,name=LeaderCount", "Value"),
            query("under_replicated_partitions", "kafka.server:type=ReplicaManager,name=UnderReplicatedPartitions", "Value"),
            query("offline_partitions", "kafka.controller:type=KafkaController,name=OfflinePartitionsCount", "Value"),
            query("active_controller", "kafka.controller:type=KafkaController,name=ActiveControllerCount", "Value"),
            query("isr_shrinks", "kafka.server:type=ReplicaManager,name=IsrShrinksPerSec", "Count", "OneMinuteRate"),
            query("isr_expands", "kafka.server:type=ReplicaManager,name=IsrExpandsPerSec", "Count", "OneMinuteRate"),
            query("network_idle", "kafka.network:type=SocketServer,name=NetworkProcessorAvgIdlePercent", "Value"),
            query("request_handler_idle", "kafka.server:type=KafkaRequestHandlerPool,name=RequestHandlerAvgIdlePercent", "Count", "OneMinuteRate"),
            query("produce_request_time", "kafka.network:type=RequestMetrics,name=TotalTimeMs,request=Produce", "Count", "Mean", "50thPercentile", "95thPercentile", "99thPercentile"),
            query("fetch_request_time", "kafka.network:type=RequestMetrics,name=TotalTimeMs,request=FetchConsumer", "Count", "Mean", "50thPercentile", "95thPercentile", "99thPercentile"),
            query("jvm_memory", "java.lang:type=Memory", "HeapMemoryUsage", "NonHeapMemoryUsage"),
            query("jvm_gc", "java.lang:type=GarbageCollector,name=*", "CollectionCount", "CollectionTime")
        );
    }

    private Map<String, Object> query(String name, String objectName, String... attributes) {
        return Map.of("name", name, "objectName", objectName, "attributes", List.of(attributes));
    }
}
