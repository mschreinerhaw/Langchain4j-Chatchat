package com.chatchat.api.runtime.coding.analysis;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Independent per-invocation tool ceilings; contains no business rules or model-generated policy. */
@Data @Component
@ConfigurationProperties(prefix="chatchat.coding.analysis")
public class CodingAnalysisProperties {
    private boolean enabled;
    private String environmentId;
    private int maxConcurrentTasks = 2;
    private int timeoutSeconds = 300;
    private int maxConsecutiveRejectedTasks = 2;
    private long maxModelCalls = 12;
    private long maxModelTokens = 65536;
    private long maxCodeExecutions = 12;
    private long maxToolCalls = 24;
    private String maxCostAmount = "10";
    private String costCurrency = "USD";
    private long maxInputRows = 100000;
    private long maxInputBytes = 16L * 1024 * 1024;
    private long maxResultRows = 10000;
    private long maxResultBytes = 4L * 1024 * 1024;
    private long maxMemoryBytes = 512L * 1024 * 1024;
    private long maxCpuMillis = 300000;
}
