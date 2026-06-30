package com.chatchat.agents.runtime;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "mcp-workflow")
public class McpWorkflowProperties {

    private boolean enabled = true;

    private Map<String, WorkflowSpec> workflows = new LinkedHashMap<>();

    private Map<String, ToolDependencySpec> toolDependencies = new LinkedHashMap<>();

    @Getter
    @Setter
    public static class WorkflowSpec {
        private List<WorkflowStep> steps = new ArrayList<>();
        private ExecutionStrategy executionStrategy = new ExecutionStrategy();
        private List<String> parallelSteps = new ArrayList<>();
    }

    @Getter
    @Setter
    public static class WorkflowStep {
        private String name;
        private Integer step;
        private String tool;
        private List<String> parallelSteps = new ArrayList<>();
        private boolean required = true;
        private String condition;
        private String confirmation;
        private List<String> dependsOn = new ArrayList<>();
        private List<String> optionalDependsOn = new ArrayList<>();
    }

    @Getter
    @Setter
    public static class ExecutionStrategy {
        private String mode = "sequential";
        private boolean stopOnError = true;
        private int maxSteps = 0;
        private boolean allowParallel = false;
    }

    @Getter
    @Setter
    public static class ToolDependencySpec {
        private List<String> dependsOn = new ArrayList<>();
        private List<String> requiredDependsOn = new ArrayList<>();
        private List<String> optionalDependsOn = new ArrayList<>();
    }
}
