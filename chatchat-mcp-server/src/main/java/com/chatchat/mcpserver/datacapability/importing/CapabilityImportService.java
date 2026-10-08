package com.chatchat.mcpserver.datacapability.importing;

import com.chatchat.mcpserver.datacapability.definition.*;
import com.fasterxml.jackson.databind.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;

@Service @RequiredArgsConstructor
public class CapabilityImportService {
    private final CapabilityService capabilities;
    private final CapabilityRepository definitions;
    private final CapabilityImportRepository repository;
    private final ObjectMapper json;

    /** Each row commits independently; a malformed row cannot roll back successful registrations. */
    public CapabilityImportBatch importDefinitions(List<JsonNode> rows, boolean dryRun) {
        if (rows == null || rows.isEmpty() || rows.size() > 1000) throw new IllegalArgumentException("Supply 1-1000 capability definitions");
        CapabilityImportBatch batch = new CapabilityImportBatch();
        batch.setId(UUID.randomUUID().toString()); batch.setCreatedAt(Instant.now()); batch.setDryRun(dryRun);
        List<Map<String, Object>> results = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int index = 0; index < rows.size(); index++) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("row", index + 1);
            try {
                CapabilityDefinition definition = json.treeToValue(rows.get(index), CapabilityDefinition.class);
                capabilities.validate(definition);
                result.put("code", definition.code());
                if (!seen.add(definition.code()) || definitions.existsById(definition.code()))
                    throw new IllegalArgumentException("Duplicate capability code: " + definition.code());
                if (!dryRun) capabilities.create(definition);
                result.put("status", dryRun ? "VALID" : "IMPORTED"); batch.setSucceeded(batch.getSucceeded() + 1);
            } catch (Exception ex) {
                result.put("status", "ERROR");
                String message = ex.getMessage() == null ? "Invalid definition" : ex.getMessage();
                result.put("error", message.substring(0, Math.min(message.length(), 1000)));
                batch.setFailed(batch.getFailed() + 1);
            }
            results.add(result);
        }
        try { batch.setResultsJson(json.writeValueAsString(results)); }
        catch (Exception ex) { throw new IllegalStateException("Cannot encode import feedback", ex); }
        return repository.saveAndFlush(batch);
    }
    public List<CapabilityImportBatch> history() { return repository.findTop50ByOrderByCreatedAtDesc(); }
    public CapabilityImportBatch publicationFailed(CapabilityImportBatch batch, String message) {
        String error = message == null ? "MCP publication failed" : message;
        batch.setPublicationError(error.substring(0, Math.min(error.length(), 2000)));
        return repository.saveAndFlush(batch);
    }
    public CapabilityImportBatch get(String id) {
        return repository.findById(id).orElseThrow(() -> new IllegalArgumentException("Import batch not found"));
    }
    public CapabilityDefinition template(CapabilityType type) {
        Map<String, Object> options = switch (type) {
            case TRINO -> Map.of("catalog", "hive", "schema", "default");
            case GRAPH -> Map.of("database", "neo4j");
            case UNSTRUCTURED -> Map.of("index", "documents");
            case TRADING_CALENDAR -> Map.of("market", "SSE");
            default -> Map.of();
        };
        String query = switch (type) {
            case GRAPH -> "MATCH (n) WHERE n.name = $name RETURN n LIMIT 100";
            case UNSTRUCTURED -> "{\"query\":{\"match\":{\"title\":\"{{name}}\"}}}";
            case TRADING_CALENDAR -> "isTradingDay";
            default -> "SELECT * FROM example_table WHERE name = {{name}}";
        };
        String param = type == CapabilityType.TRADING_CALENDAR ? "date" : "name";
        return new CapabilityDefinition("example_" + type.name().toLowerCase(Locale.ROOT), "查询示例", "按业务修改查询与连接配置", type,
            null, type == CapabilityType.TRADING_CALENDAR ? null : "replace_with_connection_id", query,
            Map.of("type", "object", "properties", Map.of(param, Map.of("type", "string")), "required", List.of(param)),
            Map.of(), options, 30, 100, false, false, false);
    }
}
