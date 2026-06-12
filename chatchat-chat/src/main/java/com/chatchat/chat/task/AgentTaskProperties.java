package com.chatchat.chat.task;

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
    private int corePoolSize = 4;
    private int maxPoolSize = 16;
    private int keepAliveSeconds = 60;
    private int listLimit = 50;
    private int recoveryBatchSize = 200;
    private int confirmationWaitSeconds = 180;
    private long schedulerScanMs = 30000;
    private int schedulerBatchSize = 100;
    private int schedulerDefaultMaxRetries = 2;
    private long schedulerDefaultRetryDelaySeconds = 60;
    private EventStore eventStore = new EventStore();

    @Getter
    @Setter
    public static class EventStore {
        private String type = "rocksdb";
        private String path = "./data/agent-event-rocksdb";
        private boolean createIfMissing = true;
    }
}
