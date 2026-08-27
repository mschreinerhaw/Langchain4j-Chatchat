package com.chatchat.mcpserver.ops.jmx;

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
import java.util.Set;

@Service
@RequiredArgsConstructor
public class JmxTemplateService {

    /** Legacy all-in-one template, retained only to migrate existing installations safely. */
    public static final String KAFKA_DEFAULT_CODE = "JMX_KAFKA_BROKER_OVERVIEW";
    public static final String KAFKA_TRAFFIC_CODE = "JMX_KAFKA_BROKER_TRAFFIC";
    public static final String KAFKA_PARTITIONS_CODE = "JMX_KAFKA_BROKER_PARTITIONS";
    public static final String KAFKA_REPLICATION_CODE = "JMX_KAFKA_BROKER_REPLICATION";
    public static final String KAFKA_REQUESTS_CODE = "JMX_KAFKA_BROKER_REQUESTS";
    public static final String KAFKA_JVM_CODE = "JMX_KAFKA_BROKER_JVM";
    public static final String DEFAULT_SERVICE_URL = "service:jmx:rmi:///jndi/rmi://127.0.0.1:9999/jmxrmi";

    private static final Set<String> KAFKA_SPLIT_DEFAULT_CODES = Set.of(
        KAFKA_TRAFFIC_CODE, KAFKA_PARTITIONS_CODE, KAFKA_REPLICATION_CODE, KAFKA_REQUESTS_CODE, KAFKA_JVM_CODE);

    private final JmxTemplateConfigRepository repository;
    private final ObjectMapper objectMapper;

    @Transactional
    public List<JmxTemplateConfig> listAll() {
        ensureKafkaDefaults();
        return repository.findAll().stream().sorted(Comparator.comparing(JmxTemplateConfig::getCode)).toList();
    }

    @Transactional
    public List<JmxTemplateConfig> listEnabled() {
        ensureKafkaDefaults();
        return repository.findByEnabledTrueOrderByCodeAsc();
    }

    public JmxTemplateConfig getEnabledByCode(String code) {
        ensureKafkaDefaults();
        return repository.findByCode(normalizeCode(code)).filter(JmxTemplateConfig::isEnabled)
            .orElseThrow(() -> new IllegalArgumentException("JMX template not found or disabled: " + code));
    }

    public JmxTemplateConfig getById(String id) {
        ensureKafkaDefaults();
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
        if (request.getPassword() != null) value.setPassword(request.getPassword());
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
        if (KAFKA_DEFAULT_CODE.equals(value.getCode()) || KAFKA_SPLIT_DEFAULT_CODES.contains(value.getCode())) {
            value.setEnabled(false);
            repository.save(value);
        } else {
            repository.delete(value);
        }
    }

    @Transactional
    public void ensureKafkaDefaults() {
        JmxTemplateConfig legacy = repository.findByCode(KAFKA_DEFAULT_CODE).orElse(null);
        boolean migrateAsEnabled = legacy != null && legacy.isEnabled();
        if (migrateAsEnabled) {
            legacy.setEnabled(false);
            repository.save(legacy);
        }
        for (KafkaTemplateDefinition definition : kafkaTemplateDefinitions()) {
            if (repository.findByCode(definition.code()).isEmpty()) {
                repository.save(kafkaTemplate(definition, legacy, migrateAsEnabled));
            }
        }
    }

    /** @deprecated use the metric-domain split defaults created by {@link #ensureKafkaDefaults()}. */
    @Deprecated
    public void ensureKafkaDefault() {
        ensureKafkaDefaults();
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

    private JmxTemplateConfig kafkaTemplate(KafkaTemplateDefinition definition, JmxTemplateConfig legacy,
                                              boolean enabled) {
        JmxTemplateConfig value = new JmxTemplateConfig();
        value.setCode(definition.code());
        value.setTitle(definition.title());
        value.setDescription(definition.description());
        value.setServiceUrl(legacy == null ? DEFAULT_SERVICE_URL : legacy.getServiceUrl());
        value.setUsername(legacy == null ? null : legacy.getUsername());
        value.setPassword(legacy == null ? null : legacy.getPassword());
        value.setQueriesJson(ModelProtocolJson.compact(definition.queries()));
        value.setIntentSignalsJson(ModelProtocolJson.compact(definition.intentSignals()));
        value.setCategory("kafka_monitoring");
        value.setRiskLevel("LOW");
        value.setRuntimeAction("readonly");
        value.setTimeoutMs(legacy == null ? 10000 : legacy.getTimeoutMs());
        value.setEnabled(enabled);
        return value;
    }

    private List<KafkaTemplateDefinition> kafkaTemplateDefinitions() {
        return List.of(
            new KafkaTemplateDefinition(KAFKA_TRAFFIC_CODE, "Kafka Broker 消息流量", "消息吞吐及 Broker 入站、出站字节速率。",
                List.of(
                    query("messages_in", "kafka.server:type=BrokerTopicMetrics,name=MessagesInPerSec", "Count", "OneMinuteRate", "FiveMinuteRate"),
                    query("bytes_in", "kafka.server:type=BrokerTopicMetrics,name=BytesInPerSec", "Count", "OneMinuteRate"),
                    query("bytes_out", "kafka.server:type=BrokerTopicMetrics,name=BytesOutPerSec", "Count", "OneMinuteRate")),
                List.of("Kafka 流量", "消息吞吐", "消息速率", "入站流量", "出站流量")),
            new KafkaTemplateDefinition(KAFKA_PARTITIONS_CODE, "Kafka Broker 分区与控制器", "分区、Leader、离线分区及活动控制器状态。",
                List.of(
                    query("partition_count", "kafka.server:type=ReplicaManager,name=PartitionCount", "Value"),
                    query("leader_count", "kafka.server:type=ReplicaManager,name=LeaderCount", "Value"),
                    query("offline_partitions", "kafka.controller:type=KafkaController,name=OfflinePartitionsCount", "Value"),
                    query("active_controller", "kafka.controller:type=KafkaController,name=ActiveControllerCount", "Value")),
                List.of("Kafka 分区", "Leader 数量", "离线分区", "活动控制器")),
            new KafkaTemplateDefinition(KAFKA_REPLICATION_CODE, "Kafka Broker 副本与 ISR", "副本同步健康度及 ISR 扩张、收缩变化。",
                List.of(
                    query("under_replicated_partitions", "kafka.server:type=ReplicaManager,name=UnderReplicatedPartitions", "Value"),
                    query("isr_shrinks", "kafka.server:type=ReplicaManager,name=IsrShrinksPerSec", "Count", "OneMinuteRate"),
                    query("isr_expands", "kafka.server:type=ReplicaManager,name=IsrExpandsPerSec", "Count", "OneMinuteRate")),
                List.of("Kafka 副本", "副本不同步", "ISR 收缩", "ISR 扩张")),
            new KafkaTemplateDefinition(KAFKA_REQUESTS_CODE, "Kafka Broker 请求性能", "网络线程、请求处理线程利用率及生产消费请求延迟。",
                List.of(
                    query("network_idle", "kafka.network:type=SocketServer,name=NetworkProcessorAvgIdlePercent", "Value"),
                    query("request_handler_idle", "kafka.server:type=KafkaRequestHandlerPool,name=RequestHandlerAvgIdlePercent", "Count", "OneMinuteRate"),
                    query("produce_request_time", "kafka.network:type=RequestMetrics,name=TotalTimeMs,request=Produce", "Count", "Mean", "50thPercentile", "95thPercentile", "99thPercentile"),
                    query("fetch_request_time", "kafka.network:type=RequestMetrics,name=TotalTimeMs,request=FetchConsumer", "Count", "Mean", "50thPercentile", "95thPercentile", "99thPercentile")),
                List.of("Kafka 请求", "请求延迟", "Produce 延迟", "Fetch 延迟", "线程空闲率")),
            new KafkaTemplateDefinition(KAFKA_JVM_CODE, "Kafka Broker JVM 运行状态", "Kafka Broker JVM 堆、非堆内存及垃圾回收指标。",
                List.of(
                    query("jvm_memory", "java.lang:type=Memory", "HeapMemoryUsage", "NonHeapMemoryUsage"),
                    query("jvm_gc", "java.lang:type=GarbageCollector,name=*", "CollectionCount", "CollectionTime")),
                List.of("Kafka JVM", "JVM 内存", "堆内存", "非堆内存", "垃圾回收", "GC"))
        );
    }

    private Map<String, Object> query(String name, String objectName, String... attributes) {
        return Map.of("name", name, "objectName", objectName, "attributes", List.of(attributes));
    }

    private record KafkaTemplateDefinition(String code, String title, String description,
                                           List<Map<String, Object>> queries, List<String> intentSignals) {
    }
}
