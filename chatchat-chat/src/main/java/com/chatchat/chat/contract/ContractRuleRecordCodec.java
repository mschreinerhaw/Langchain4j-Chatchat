package com.chatchat.chat.contract;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Converts runtime rule objects to maintainable typed database records and back. */
@Component
public class ContractRuleRecordCodec {

    public List<ContractRuleNodeValue> flatten(Map<String, Object> rules) {
        if (rules == null || rules.isEmpty()) {
            throw new IllegalArgumentException("Contract rules cannot be empty");
        }
        List<ContractRuleNodeValue> nodes = new ArrayList<>();
        appendMap("", rules, nodes);
        return List.copyOf(nodes);
    }

    public Map<String, Object> assemble(List<ContractRuleNodeValue> storedNodes) {
        if (storedNodes == null || storedNodes.isEmpty()) {
            throw new IllegalStateException("Contract rule records cannot be empty");
        }
        List<ContractRuleNodeValue> nodes = new ArrayList<>(storedNodes);
        nodes.sort(Comparator.comparingInt(node -> depth(node.getRulePath())));
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> containers = new LinkedHashMap<>();
        containers.put("", root);
        for (ContractRuleNodeValue node : nodes) {
            Object parent = containers.get(node.getParentPath());
            if (parent == null) {
                throw new IllegalStateException("Missing parent rule record: " + node.getParentPath());
            }
            Object value = decodeValue(node);
            if (parent instanceof Map<?, ?> parentMap) {
                if (node.getRuleKey() == null || node.getRuleKey().isBlank()) {
                    throw new IllegalStateException("Object rule record requires rule_key: " + node.getRulePath());
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> writable = (Map<String, Object>) parentMap;
                writable.put(node.getRuleKey(), value);
            } else if (parent instanceof List<?> parentList) {
                if (node.getArrayIndex() == null || node.getArrayIndex() < 0) {
                    throw new IllegalStateException("Array rule record requires array_index: " + node.getRulePath());
                }
                @SuppressWarnings("unchecked")
                List<Object> writable = (List<Object>) parentList;
                if (node.getArrayIndex() != writable.size()) {
                    throw new IllegalStateException("Array rule indexes must be contiguous: " + node.getRulePath());
                }
                writable.add(value);
            } else {
                throw new IllegalStateException("Rule parent is not a container: " + node.getParentPath());
            }
            if (value instanceof Map<?, ?> || value instanceof List<?>) {
                containers.put(node.getRulePath(), value);
            }
        }
        return root;
    }

    private void appendMap(String parentPath, Map<?, ?> values, List<ContractRuleNodeValue> nodes) {
        for (Map.Entry<?, ?> entry : values.entrySet()) {
            String key = String.valueOf(entry.getKey());
            String path = parentPath + "/" + escape(key);
            ContractRuleNodeValue node = node(path, parentPath, key, null, entry.getValue());
            nodes.add(node);
            appendChildren(path, entry.getValue(), nodes);
        }
    }

    private void appendList(String parentPath, List<?> values, List<ContractRuleNodeValue> nodes) {
        for (int index = 0; index < values.size(); index++) {
            Object value = values.get(index);
            String path = parentPath + "/" + index;
            ContractRuleNodeValue node = node(path, parentPath, null, index, value);
            nodes.add(node);
            appendChildren(path, value, nodes);
        }
    }

    private void appendChildren(String path, Object value, List<ContractRuleNodeValue> nodes) {
        if (value instanceof Map<?, ?> map) {
            appendMap(path, map, nodes);
        } else if (value instanceof List<?> list) {
            appendList(path, list, nodes);
        }
    }

    private ContractRuleNodeValue node(
        String path, String parentPath, String ruleKey, Integer arrayIndex, Object value
    ) {
        String type;
        String text = null;
        if (value instanceof Map<?, ?>) {
            type = "OBJECT";
        } else if (value instanceof List<?>) {
            type = "ARRAY";
        } else if (value instanceof Boolean booleanValue) {
            type = "BOOLEAN";
            text = booleanValue.toString();
        } else if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            type = "INTEGER";
            text = value.toString();
        } else if (value instanceof Number) {
            type = "DECIMAL";
            text = value.toString();
        } else if (value == null) {
            type = "NULL";
        } else {
            type = "STRING";
            text = value.toString();
        }
        return new ContractRuleNodeValue(path, parentPath, ruleKey, arrayIndex, type, text);
    }

    private Object decodeValue(ContractRuleNodeValue node) {
        return switch (node.getValueType()) {
            case "OBJECT" -> new LinkedHashMap<String, Object>();
            case "ARRAY" -> new ArrayList<>();
            case "BOOLEAN" -> booleanValue(node);
            case "INTEGER" -> integer(requiredText(node));
            case "DECIMAL" -> new BigDecimal(requiredText(node));
            case "STRING" -> requiredText(node);
            case "NULL" -> null;
            default -> throw new IllegalStateException("Unsupported rule value_type: " + node.getValueType());
        };
    }

    private Object integer(String value) {
        long parsed = Long.parseLong(value);
        if (parsed >= Integer.MIN_VALUE && parsed <= Integer.MAX_VALUE) {
            return Integer.valueOf((int) parsed);
        }
        return Long.valueOf(parsed);
    }

    private Boolean booleanValue(ContractRuleNodeValue node) {
        String value = requiredText(node);
        if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
            throw new IllegalStateException("Invalid BOOLEAN rule value: " + node.getRulePath());
        }
        return Boolean.valueOf(value);
    }

    private String requiredText(ContractRuleNodeValue node) {
        if (node.getValueText() == null) {
            throw new IllegalStateException("Rule value_text is required: " + node.getRulePath());
        }
        return node.getValueText();
    }

    private int depth(String path) {
        return (int) path.chars().filter(character -> character == '/').count();
    }

    private String escape(String value) {
        return value.replace("~", "~0").replace("/", "~1");
    }
}
