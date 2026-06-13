package com.chatchat.agents.runtime;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "chatchat.agent-runtime")
public class AgentRuntimeProperties {

    private int corePoolSize = 4;
    private int maxPoolSize = 16;
    private int queueCapacity = 100;
    private int keepAliveSeconds = 60;
    private String threadNamePrefix = "agent-runtime-";
    private int maxStoredRuns = 10_000;
    private long terminalRunTtlMs = 0;
    private String storeType = "rocksdb";
    private String rocksDbPath = "./data/agent-runtime-rocksdb";
    private boolean rocksDbCreateIfMissing = true;
    private boolean failInterruptedRunsOnStartup = true;

    public int corePoolSize() {
        return Math.max(1, corePoolSize);
    }

    public int maxPoolSize() {
        return Math.max(corePoolSize(), maxPoolSize);
    }

    public int queueCapacity() {
        return Math.max(1, queueCapacity);
    }

    public int keepAliveSeconds() {
        return Math.max(1, keepAliveSeconds);
    }

    public String threadNamePrefix() {
        return threadNamePrefix == null || threadNamePrefix.isBlank()
            ? "agent-runtime-"
            : threadNamePrefix;
    }

    public int maxStoredRuns() {
        return Math.max(1, maxStoredRuns);
    }

    public long terminalRunTtlMs() {
        return Math.max(0, terminalRunTtlMs);
    }

    public String storeType() {
        return storeType == null || storeType.isBlank() ? "rocksdb" : storeType.trim();
    }

    public String rocksDbPath() {
        return rocksDbPath == null || rocksDbPath.isBlank()
            ? "./data/agent-runtime-rocksdb"
            : rocksDbPath.trim();
    }
}
