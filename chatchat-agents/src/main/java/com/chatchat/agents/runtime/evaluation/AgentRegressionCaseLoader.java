package com.chatchat.agents.runtime.evaluation;

import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

public class AgentRegressionCaseLoader {

    public AgentRegressionCase load(InputStream inputStream) {
        if (inputStream == null) {
            throw new IllegalArgumentException("Regression case YAML input stream is required");
        }
        Object loaded = new Yaml().load(inputStream);
        if (!(loaded instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("Regression case YAML must be a mapping");
        }
        return fromMap(map);
    }

    private AgentRegressionCase fromMap(Map<?, ?> root) {
        Map<?, ?> input = map(root.get("input"));
        Map<?, ?> expected = map(root.get("expected"));
        return new AgentRegressionCase(
            string(root.get("id")),
            string(root.get("name")),
            strings(root.get("tags")),
            new AgentRegressionCase.Input(string(input.get("query"))),
            new AgentRegressionCase.Expected(
                retrieval(map(expected.get("retrieval"))),
                evidence(map(expected.get("evidence"))),
                review(map(expected.get("review"))),
                answer(map(expected.get("answer")))
            )
        );
    }

    private AgentRegressionCase.Retrieval retrieval(Map<?, ?> values) {
        return new AgentRegressionCase.Retrieval(strings(values.get("mustContain")));
    }

    private AgentRegressionCase.Evidence evidence(Map<?, ?> values) {
        return new AgentRegressionCase.Evidence(
            integer(values.get("minChunks"), 0),
            strings(values.get("mustContainKeywords")),
            decimal(values.get("minScore"))
        );
    }

    private AgentRegressionCase.Review review(Map<?, ?> values) {
        return new AgentRegressionCase.Review(
            bool(values.get("mustPass"), true),
            bool(values.get("allowPartialEvidence"), false),
            decimal(values.get("maxRejectRate")),
            decimal(values.get("minScore"))
        );
    }

    private AgentRegressionCase.Answer answer(Map<?, ?> values) {
        return new AgentRegressionCase.Answer(strings(values.get("mustContainAny")));
    }

    private Map<?, ?> map(Object value) {
        return value instanceof Map<?, ?> map ? map : Map.of();
    }

    private List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
            .map(this::string)
            .filter(item -> item != null && !item.isBlank())
            .toList();
    }

    private String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private int integer(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private Double decimal(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private boolean bool(Object value, boolean fallback) {
        return value == null ? fallback : Boolean.parseBoolean(String.valueOf(value));
    }
}
