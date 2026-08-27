package com.chatchat.chat.task.core;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "chatchat.agent.task")
public class AgentTaskProperties {

    private int queueCapacity = 1000;
    private int corePoolSize = 16;
    private int maxPoolSize = 16;
    private int feedbackCorePoolSize = 2;
    private int feedbackMaxPoolSize = 8;
    private int feedbackQueueCapacity = 1000;
    private int maxConcurrentTasksPerTenant = 4;
    private int keepAliveSeconds = 60;
    /** Legacy compatibility setting; Agent tasks do not impose an implicit Runtime deadline. */
    private long executionTimeoutMs = 0L;
    private int listLimit = 50;
    private int recoveryBatchSize = 200;
    private int confirmationWaitSeconds = 600;
    private long schedulerScanMs = 30000;
    private int schedulerBatchSize = 100;
    private int schedulerDefaultMaxRetries = 2;
    private long schedulerDefaultRetryDelaySeconds = 60;
    private boolean databaseQueueEnabled = true;
    private long databaseQueuePollMs = 250;
    private long databaseQuotaReconcileMs = 30_000;
    private int databaseQueueClaimBatchSize = 16;
    private long workerLeaseMs = 30_000;
    private long workerHeartbeatMs = 10_000;
    private int taskMaxAttempts = 3;
    private long retryBaseDelayMs = 1_000;
    private long retryMaxDelayMs = 60_000;
    private String workerVersion = "1.0";
    private String workerCapabilities = "dag-v2,conditional-edge-v1";
    private EventStore eventStore = new EventStore();

    @Getter
    @Setter
    public static class EventStore {
        private String type = "rocksdb";
        private String path = "./data/agent-event-rocksdb";
        private boolean createIfMissing = true;
    }
}
