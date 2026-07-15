package com.chatchat.tools.workflow;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiFunction;

/**
 * Validates and executes a read-only SQL dependency graph. Business modules provide the
 * actual database executor, while this class owns deterministic DAG scheduling and input mapping.
 */
public class SqlWorkflowEngine {

    public SqlWorkflowExecution execute(List<SqlWorkflowNode> nodes,
                                        Map<String, Object> userInput,
                                        Map<String, Object> systemContext,
                                        int maxParallelism,
                                        BiFunction<SqlWorkflowNode, Map<String, Object>, SqlWorkflowNodeResult> executor) {
        long startedAt = System.currentTimeMillis();
        List<List<SqlWorkflowNode>> levels = executionLevels(nodes);
        Map<String, SqlWorkflowExecution.StepExecution> completed = new LinkedHashMap<>();
        List<SqlWorkflowExecution.StepExecution> executions = new ArrayList<>();
        boolean workflowStopped = false;
        int executionOrder = 0;
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, Math.min(maxParallelism, 16)));
        try {
            for (int levelIndex = 0; levelIndex < levels.size(); levelIndex++) {
                List<SqlWorkflowNode> level = levels.get(levelIndex);
                int currentLevel = levelIndex + 1;
                List<CompletableFuture<SqlWorkflowExecution.StepExecution>> futures = new ArrayList<>();
                for (SqlWorkflowNode node : level) {
                    int order = ++executionOrder;
                    String skipReason = workflowStopped ? "workflow stopped by an upstream policy" : dependencySkipReason(node, completed);
                    if (skipReason != null) {
                        SqlWorkflowExecution.StepExecution skipped = new SqlWorkflowExecution.StepExecution(
                        node, order, currentLevel, "SKIPPED", Map.of(), null, skipReason);
                        executions.add(skipped);
                        completed.put(node.code(), skipped);
                        continue;
                    }
                    Map<String, Object> parameters;
                    try {
                        parameters = resolveParameters(node, userInput, systemContext, completed);
                    } catch (RuntimeException ex) {
                        SqlWorkflowExecution.StepExecution failed = new SqlWorkflowExecution.StepExecution(
                            node, order, currentLevel, "FAILED", Map.of(),
                            new SqlWorkflowNodeResult(false, Map.of(), ex.getMessage(), 0L), null);
                        executions.add(failed);
                        completed.put(node.code(), failed);
                        if ("STOP".equals(normalized(node.failureStrategy(), "STOP"))) workflowStopped = true;
                        continue;
                    }
                    futures.add(CompletableFuture.supplyAsync(() -> {
                        SqlWorkflowNodeResult result;
                        try {
                            result = executor.apply(node, parameters);
                        } catch (RuntimeException ex) {
                            result = new SqlWorkflowNodeResult(false, Map.of(), ex.getMessage(), 0L);
                        }
                        return new SqlWorkflowExecution.StepExecution(node, order, currentLevel,
                            result.success() ? "SUCCESS" : "FAILED", parameters, result, null);
                    }, pool));
                }
                for (CompletableFuture<SqlWorkflowExecution.StepExecution> future : futures) {
                    SqlWorkflowExecution.StepExecution execution = future.join();
                    executions.add(execution);
                    completed.put(execution.node().code(), execution);
                    if (stopsWorkflow(execution)) {
                        workflowStopped = true;
                    }
                }
            }
        } finally {
            pool.shutdownNow();
        }
        executions.sort(Comparator.comparingInt(SqlWorkflowExecution.StepExecution::executionOrder));
        boolean failed = executions.stream().anyMatch(item -> "FAILED".equals(item.status()));
        boolean partial = failed || executions.stream().anyMatch(item -> "SKIPPED".equals(item.status()));
        return new SqlWorkflowExecution(
            UUID.randomUUID().toString(),
            workflowStopped ? "FAILED" : partial ? "PARTIAL_SUCCESS" : "SUCCESS",
            levels.stream().map(level -> level.stream().map(SqlWorkflowNode::code).toList()).toList(),
            List.copyOf(executions),
            Math.max(0L, System.currentTimeMillis() - startedAt)
        );
    }

    public List<List<SqlWorkflowNode>> executionLevels(List<SqlWorkflowNode> source) {
        if (source == null || source.isEmpty()) {
            throw new IllegalArgumentException("SQL workflow requires at least one enabled node");
        }
        Map<String, SqlWorkflowNode> nodes = new LinkedHashMap<>();
        for (SqlWorkflowNode node : source) {
            String code = text(node == null ? null : node.code());
            if (code == null) throw new IllegalArgumentException("SQL workflow node code is required");
            if (nodes.putIfAbsent(code, node) != null) {
                throw new IllegalArgumentException("SQL workflow node code duplicated: " + code);
            }
        }
        Map<String, Integer> indegree = new LinkedHashMap<>();
        Map<String, List<String>> outgoing = new LinkedHashMap<>();
        nodes.keySet().forEach(code -> {
            indegree.put(code, 0);
            outgoing.put(code, new ArrayList<>());
        });
        for (SqlWorkflowNode node : nodes.values()) {
            Set<String> distinct = new LinkedHashSet<>(node.dependencies());
            for (String dependency : distinct) {
                if (!nodes.containsKey(dependency)) {
                    throw new IllegalArgumentException("SQL workflow node " + node.code() + " depends on missing node: " + dependency);
                }
                if (node.code().equals(dependency)) {
                    throw new IllegalArgumentException("SQL workflow node cannot depend on itself: " + node.code());
                }
                indegree.compute(node.code(), (ignored, value) -> value + 1);
                outgoing.get(dependency).add(node.code());
            }
        }
        Comparator<SqlWorkflowNode> order = Comparator.comparingInt(SqlWorkflowNode::displayOrder).thenComparing(SqlWorkflowNode::code);
        List<List<SqlWorkflowNode>> levels = new ArrayList<>();
        Set<String> remaining = new LinkedHashSet<>(nodes.keySet());
        while (!remaining.isEmpty()) {
            List<SqlWorkflowNode> ready = remaining.stream()
                .filter(code -> indegree.get(code) == 0)
                .map(nodes::get).sorted(order).toList();
            if (ready.isEmpty()) {
                throw new IllegalArgumentException("SQL workflow contains a circular dependency: " + String.join(", ", remaining));
            }
            levels.add(ready);
            for (SqlWorkflowNode node : ready) {
                remaining.remove(node.code());
                outgoing.get(node.code()).forEach(target -> indegree.compute(target, (ignored, value) -> value - 1));
            }
        }
        return List.copyOf(levels);
    }

    private Map<String, Object> resolveParameters(SqlWorkflowNode node,
                                                  Map<String, Object> userInput,
                                                  Map<String, Object> systemContext,
                                                  Map<String, SqlWorkflowExecution.StepExecution> completed) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (userInput != null) result.putAll(userInput);
        result.putAll(node.staticParameters());
        for (SqlWorkflowParameterMapping mapping : node.parameterMappings()) {
            String parameter = text(mapping.parameter());
            if (parameter == null) continue;
            Object value = switch (normalized(mapping.sourceType(), "USER_INPUT")) {
                case "SYSTEM_CONTEXT" -> valueAt(systemContext, mapping.sourceKey());
                case "UPSTREAM_RESULT" -> upstreamValue(completed, mapping);
                case "STATIC" -> mapping.defaultValue();
                default -> valueAt(userInput, first(mapping.sourceKey(), parameter));
            };
            if (value == null) value = mapping.defaultValue();
            if (value == null && mapping.required()) {
                throw new IllegalArgumentException("Required parameter " + parameter + " cannot be resolved for SQL node " + node.code());
            }
            if (value != null) result.put(parameter, value);
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(result));
    }

    private Object upstreamValue(Map<String, SqlWorkflowExecution.StepExecution> completed,
                                 SqlWorkflowParameterMapping mapping) {
        SqlWorkflowExecution.StepExecution source = completed.get(mapping.sourceNode());
        if (source == null || source.result() == null || !source.result().success()) return null;
        return valueAt(source.result().data(), first(mapping.sourceExpression(), "$"));
    }

    private Object valueAt(Object value, String expression) {
        if (value == null) return null;
        String path = text(expression);
        if (path == null || "$".equals(path)) return value;
        path = path.replaceFirst("^\\$\\.?", "");
        Object current = value;
        for (String token : path.split("\\.")) {
            if (token.isBlank()) continue;
            int bracket = token.indexOf('[');
            String key = bracket < 0 ? token : token.substring(0, bracket);
            if (!key.isBlank()) {
                current = current instanceof Map<?, ?> map ? map.get(key) : null;
            }
            while (current != null && bracket >= 0) {
                int close = token.indexOf(']', bracket);
                if (close < 0) return null;
                int index = Integer.parseInt(token.substring(bracket + 1, close));
                if (current instanceof List<?> list) current = index < list.size() ? list.get(index) : null;
                else if (current.getClass().isArray()) current = index < Array.getLength(current) ? Array.get(current, index) : null;
                else return null;
                bracket = token.indexOf('[', close + 1);
            }
        }
        return current;
    }

    private String dependencySkipReason(SqlWorkflowNode node, Map<String, SqlWorkflowExecution.StepExecution> completed) {
        for (String dependency : node.dependencies()) {
            SqlWorkflowExecution.StepExecution execution = completed.get(dependency);
            if (execution == null || !"SUCCESS".equals(execution.status())) {
                return "dependency did not complete successfully: " + dependency;
            }
            if ("SKIP_DEPENDENTS".equals(normalized(execution.node().emptyResultStrategy(), "CONTINUE"))
                && rowCount(execution.result().data()) == 0) {
                return "dependency returned an empty result: " + dependency;
            }
        }
        return null;
    }

    private boolean stopsWorkflow(SqlWorkflowExecution.StepExecution execution) {
        if (execution.result() == null) return false;
        if (!execution.result().success()) return "STOP".equals(normalized(execution.node().failureStrategy(), "STOP"));
        return rowCount(execution.result().data()) == 0
            && "STOP".equals(normalized(execution.node().emptyResultStrategy(), "CONTINUE"));
    }

    private int rowCount(Map<String, Object> data) {
        Object count = data == null ? null : data.get("rowCount");
        if (count instanceof Number number) return number.intValue();
        Object rows = data == null ? null : data.get("rows");
        return rows instanceof List<?> list ? list.size() : 0;
    }

    private String normalized(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim().toUpperCase(Locale.ROOT);
    }

    private String first(String first, String fallback) {
        return first == null || first.isBlank() ? fallback : first;
    }

    private String text(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
