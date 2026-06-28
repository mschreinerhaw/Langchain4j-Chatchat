package com.chatchat.mcpserver.livedata;

import com.chatchat.agents.protocol.ModelProtocolJson;

import com.chatchat.mcpserver.api.ApiServiceConfig;
import com.chatchat.tools.livedata.LivedataApiDefinition;
import com.chatchat.tools.livedata.LivedataAutoRegistrationProperties;
import com.chatchat.tools.livedata.LivedataSessionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class LivedataApiConfigMapper {

    private final ObjectMapper objectMapper;
    private final LivedataAutoRegistrationProperties properties;

    /**
     * Converts the value to api service config.
     *
     * @param definition the definition value
     * @return the converted api service config
     */
    public ApiServiceConfig toApiServiceConfig(LivedataApiDefinition definition) {
        List<ParamDefinition> params = parseParams(definition.params());
        String serviceName = resolveServiceName(definition);
        String namespace = firstNonBlank(definition.namespace(), properties.getDefaultNamespace());

        ApiServiceConfig config = new ApiServiceConfig();
        config.setToolName(toToolName(definition));
        config.setTitle(firstNonBlank(definition.apiName(), definition.apiId(), serviceName));
        config.setDescription(toDescription(definition));
        config.setMethod("POST");
        config.setUrlTemplate(toUrlTemplate(definition, serviceName, namespace));
        config.setHeadersJson(writeJson(Map.of("Content-Type", "application/json;charset=UTF-8")));
        config.setBodyTemplate(toBodyTemplate(params, namespace));
        config.setInputSchemaJson(toInputSchema(params));
        config.setEnabled(definition.state() == null || definition.state() == properties.getPublishedState());
        config.setTimeoutMs(properties.getTimeoutMs());
        config.setCacheEnabled(properties.isCacheEnabled());
        config.setCacheTtlSeconds(properties.getCacheTtlSeconds());
        return config;
    }

    /**
     * Converts the value to tool name.
     *
     * @param definition the definition value
     * @return the converted tool name
     */
    private String toToolName(LivedataApiDefinition definition) {
        String raw = firstNonBlank(definition.apiId(), definition.methodName(), definition.id());
        String normalized = raw == null ? "api" : raw.trim()
            .replaceAll("[^A-Za-z0-9_]+", "_")
            .replaceAll("_+", "_")
            .replaceAll("^_|_$", "");
        if (normalized.isBlank()) {
            normalized = "api_" + Math.abs(String.valueOf(definition.id()).hashCode());
        }
        String prefix = properties.getToolNamePrefix() == null ? "" : properties.getToolNamePrefix().trim();
        String toolName = prefix + normalized;
        return toolName.length() <= 128 ? toolName : toolName.substring(0, 118) + "_" + Integer.toHexString(toolName.hashCode());
    }

    /**
     * Converts the value to description.
     *
     * @param definition the definition value
     * @return the converted description
     */
    private String toDescription(LivedataApiDefinition definition) {
        List<String> parts = new ArrayList<>();
        addIfPresent(parts, definition.description());
        addIfPresent(parts, definition.apiName());
        String apiKey = firstNonBlank(definition.apiId(), definition.serviceName(), definition.methodName());
        if (apiKey != null) {
            addIfPresent(parts, "LiveData API: " + apiKey);
        }
        addIfPresent(parts, "namespace: " + firstNonBlank(definition.namespace(), properties.getDefaultNamespace()));
        String version = firstNonBlank(definition.releaseVersion(), definition.version());
        if (version != null) {
            addIfPresent(parts, "version: " + version);
        }
        return String.join("\n", parts);
    }

    /**
     * Converts the value to url template.
     *
     * @param definition the definition value
     * @param serviceName the service name value
     * @param namespace the namespace value
     * @return the converted url template
     */
    private String toUrlTemplate(LivedataApiDefinition definition, String serviceName, String namespace) {
        String baseUrl = trimTrailingSlash(properties.getServiceBaseUrl());
        String path = properties.getServicePathTemplate();
        if (path == null || path.isBlank()) {
            path = "/service/{serviceName}/call";
        }
        path = path
            .replace("{serviceName}", safeUrlSegment(serviceName))
            .replace("{apiId}", safeUrlSegment(firstNonBlank(definition.apiId(), "")))
            .replace("{methodName}", safeUrlSegment(firstNonBlank(definition.methodName(), "")))
            .replace("{namespace}", safeUrlSegment(firstNonBlank(namespace, "")));
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        return baseUrl + path;
    }

    /**
     * Converts the value to body template.
     *
     * @param params the params value
     * @param namespace the namespace value
     * @return the converted body template
     */
    private String toBodyTemplate(List<ParamDefinition> params, String namespace) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessionId", sessionIdTemplate());
        body.put("namespace", firstNonBlank(namespace, properties.getDefaultNamespace()));
        body.put("head", Map.of(
            "x-ams-token", properties.isExposeAmsTokenParameter() ? "{{amsToken}}" : firstNonBlank(properties.getAmsToken(), "")
        ));

        Map<String, Object> data = new LinkedHashMap<>();
        for (ParamDefinition param : params) {
            data.put(param.name(), "{{" + param.name() + "}}");
        }
        body.put("data", data);
        return writeJson(body);
    }

    /**
     * Performs the session id template operation.
     *
     * @return the operation result
     */
    private String sessionIdTemplate() {
        return "{{" + LivedataSessionService.SESSION_ARGUMENT + "}}";
    }

    /**
     * Converts the value to input schema.
     *
     * @param params the params value
     * @return the converted input schema
     */
    private String toInputSchema(List<ParamDefinition> params) {
        Map<String, Object> schema = new LinkedHashMap<>();
        Map<String, Object> propertiesNode = new LinkedHashMap<>();
        Set<String> required = new LinkedHashSet<>();

        if (properties.isExposeAmsTokenParameter()) {
            propertiesNode.put("amsToken", Map.of("type", "string", "description", "LiveData x-ams-token"));
            required.add("amsToken");
        }

        for (ParamDefinition param : params) {
            Map<String, Object> field = new LinkedHashMap<>();
            field.put("type", param.jsonType());
            if (param.description() != null && !param.description().isBlank()) {
                field.put("description", param.description());
            }
            propertiesNode.put(param.name(), field);
            if (param.required()) {
                required.add(param.name());
            }
        }

        schema.put("type", "object");
        schema.put("properties", propertiesNode);
        schema.put("required", List.copyOf(required));
        schema.put("additionalProperties", params.isEmpty());
        return writeJson(schema);
    }

    /**
     * Parses the params.
     *
     * @param paramsJson the params json value
     * @return the parsed params
     */
    private List<ParamDefinition> parseParams(String paramsJson) {
        if (paramsJson == null || paramsJson.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(paramsJson);
            List<JsonNode> nodes = extractParamNodes(root);
            List<ParamDefinition> params = new ArrayList<>();
            for (JsonNode node : nodes) {
                ParamDefinition param = toParamDefinition(node);
                if (param != null) {
                    params.add(param);
                }
            }
            return params;
        } catch (Exception ex) {
            return List.of();
        }
    }

    /**
     * Performs the extract param nodes operation.
     *
     * @param root the root value
     * @return the operation result
     */
    private List<JsonNode> extractParamNodes(JsonNode root) {
        if (root == null || root.isNull()) {
            return List.of();
        }
        if (root.isArray()) {
            return toList(root);
        }
        for (String field : List.of("params", "parameters", "fields", "columns", "items", "data")) {
            JsonNode child = root.get(field);
            if (child != null && child.isArray()) {
                return toList(child);
            }
        }
        if (root.isObject()) {
            List<JsonNode> nodes = new ArrayList<>();
            root.properties().forEach(entry -> {
                if (entry.getValue().isObject()) {
                    ObjectNode copy = entry.getValue().deepCopy();
                    if (!copy.hasNonNull("name")) {
                        copy.put("name", entry.getKey());
                    }
                    nodes.add(copy);
                }
            });
            return nodes;
        }
        return List.of();
    }

    /**
     * Converts the value to list.
     *
     * @param array the array value
     * @return the converted list
     */
    private List<JsonNode> toList(JsonNode array) {
        List<JsonNode> list = new ArrayList<>();
        array.forEach(list::add);
        return list;
    }

    /**
     * Converts the value to param definition.
     *
     * @param node the node value
     * @return the converted param definition
     */
    private ParamDefinition toParamDefinition(JsonNode node) {
        if (!node.isObject()) {
            return null;
        }
        String name = readText(node, "name", "paramName", "param_name", "field", "fieldName", "field_name", "key", "code", "id");
        name = normalizeParamName(name);
        if (name == null) {
            return null;
        }
        String type = normalizeJsonType(readText(node, "type", "dataType", "data_type", "javaType", "fieldType", "paramType"));
        String description = readText(node, "description", "desc", "comment", "label", "title", "nameCn", "paramNameCn");
        boolean required = readRequired(node);
        return new ParamDefinition(name, type, description, required);
    }

    /**
     * Normalizes the param name.
     *
     * @param value the value value
     * @return the operation result
     */
    private String normalizeParamName(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().replaceAll("[^A-Za-z0-9_]+", "_").replaceAll("_+", "_").replaceAll("^_|_$", "");
        return normalized.isBlank() ? null : normalized;
    }

    /**
     * Normalizes the json type.
     *
     * @param value the value value
     * @return the operation result
     */
    private String normalizeJsonType(String value) {
        if (value == null) {
            return "string";
        }
        String type = value.trim().toLowerCase(Locale.ROOT);
        if (type.contains("int") || type.equals("long") || type.equals("short")) {
            return "integer";
        }
        if (type.contains("decimal") || type.contains("double") || type.contains("float") || type.contains("number")) {
            return "number";
        }
        if (type.contains("bool")) {
            return "boolean";
        }
        if (type.contains("array") || type.contains("list")) {
            return "array";
        }
        if (type.contains("object") || type.contains("map")) {
            return "object";
        }
        return "string";
    }

    /**
     * Returns whether read required.
     *
     * @param node the node value
     * @return whether the condition is satisfied
     */
    private boolean readRequired(JsonNode node) {
        for (String field : List.of("required", "isRequired", "is_required", "must")) {
            JsonNode value = node.get(field);
            if (value != null) {
                return value.asBoolean(false) || "1".equals(value.asText());
            }
        }
        JsonNode nullable = node.get("nullable");
        return nullable != null && !nullable.asBoolean(true);
    }

    /**
     * Resolves the service name.
     *
     * @param definition the definition value
     * @return the resolved service name
     */
    private String resolveServiceName(LivedataApiDefinition definition) {
        String serviceName = firstNonBlank(definition.serviceName(), definition.apiId());
        String methodName = firstNonBlank(definition.methodName(), "");
        if (serviceName == null || serviceName.isBlank()) {
            return methodName;
        }
        if (methodName.isBlank() || serviceName.endsWith("." + methodName) || serviceName.equals(methodName)) {
            return serviceName;
        }
        return serviceName + "." + methodName;
    }

    /**
     * Reads the text.
     *
     * @param node the node value
     * @param fields the fields value
     * @return the operation result
     */
    private String readText(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value != null && !value.isNull() && !value.asText().isBlank()) {
                return value.asText();
            }
        }
        return null;
    }

    /**
     * Writes the json.
     *
     * @param value the value value
     * @return the operation result
     */
    private String writeJson(Object value) {
        try {
            return ModelProtocolJson.compact(value);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Failed to build livedata API JSON config", ex);
        }
    }

    /**
     * Performs the safe url segment operation.
     *
     * @param value the value value
     * @return the operation result
     */
    private String safeUrlSegment(String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * Performs the trim trailing slash operation.
     *
     * @param value the value value
     * @return the operation result
     */
    private String trimTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("chatchat.mcp.livedata.service-base-url is required");
        }
        return value.trim().replaceAll("/+$", "");
    }

    /**
     * Adds the if present.
     *
     * @param parts the parts value
     * @param value the value value
     */
    private void addIfPresent(List<String> parts, String value) {
        if (value != null && !value.isBlank()) {
            parts.add(value.trim());
        }
    }

    /**
     * Performs the first non blank operation.
     *
     * @param values the values value
     * @return the operation result
     */
    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private record ParamDefinition(String name, String jsonType, String description, boolean required) {
    }
}
