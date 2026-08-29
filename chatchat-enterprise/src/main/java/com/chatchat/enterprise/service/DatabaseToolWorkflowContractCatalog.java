package com.chatchat.enterprise.service;

import com.chatchat.common.mcp.capability.McpCapabilityHierarchy;
import com.chatchat.common.mcp.capability.McpDynamicCapabilityRoute;
import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.common.tool.ToolWorkflowContract;
import com.chatchat.common.tool.ToolWorkflowContractCatalog;
import com.chatchat.common.tool.ToolWorkflowContractSnapshot;
import com.chatchat.common.tool.ToolWorkflowRole;
import com.chatchat.enterprise.entity.mcp.McpToolAsset;
import com.chatchat.enterprise.entity.mcp.McpToolWorkflowContract;
import com.chatchat.enterprise.repository.mcp.McpToolAssetRepository;
import com.chatchat.enterprise.repository.mcp.McpToolWorkflowContractRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Database-authoritative implementation of the versioned tool contract catalog. */
@Service
@RequiredArgsConstructor
public class DatabaseToolWorkflowContractCatalog implements ToolWorkflowContractCatalog {

    private static final String ACTIVE = "ACTIVE";
    private static final String DRAFT = "DRAFT";
    private static final String RETIRED = "RETIRED";

    private final McpToolAssetRepository tools;
    private final McpToolWorkflowContractRepository contracts;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(readOnly = true)
    public Optional<ToolWorkflowContractSnapshot> findActive(String serviceId,
                                                              String localToolName,
                                                              String remoteToolName) {
        return tools.findByLocalToolName(localToolName)
            .filter(tool -> serviceId == null || serviceId.equals(tool.getServiceId()))
            .filter(tool -> remoteToolName == null || remoteToolName.equals(tool.getRemoteToolName()))
            .flatMap(tool -> contracts.findFirstByToolIdAndStatusOrderByContractVersionDesc(tool.getId(), ACTIVE))
            .map(this::snapshot);
    }

    @Override
    @Transactional
    public Optional<ToolWorkflowContractSnapshot> synchronizeDiscovery(String serviceId,
                                                                       String serviceName,
                                                                       String localToolName,
                                                                       String remoteToolName,
                                                                       String description,
                                                                       Map<String, Object> inputSchema,
                                                                       Map<String, Object> outputSchema,
                                                                       Map<String, Object> discoveredMeta,
                                                                       boolean autoPublish) {
        Optional<McpToolAsset> stored = tools.findByLocalToolName(localToolName);
        boolean existingCatalogTool = stored.isPresent();
        McpToolAsset tool = stored.orElseGet(McpToolAsset::new);
        tool.setLocalToolName(localToolName);
        tool.setServiceId(serviceId);
        tool.setServiceName(serviceName);
        tool.setRemoteToolName(remoteToolName);
        tool.setDescription(description);
        tool.setResourceType("tool");
        tool.setInputSchemaJson(json(inputSchema));
        tool.setOutputSchemaJson(json(outputSchema));
        tool.setEnabled(true);
        tool.setStatus("online");
        tool = tools.saveAndFlush(tool);
        String synchronizedToolId = tool.getId();
        // Serialize discovery/publication for this tool across scheduler threads and nodes.
        tools.findLockedById(synchronizedToolId).orElseThrow(() ->
            new IllegalStateException("MCP tool disappeared during contract synchronization: " + synchronizedToolId));

        Map<String, Object> metadataMap = new LinkedHashMap<>();
        metadataMap.put("mcpToolMeta", discoveredMeta == null ? Map.of() : discoveredMeta);
        ToolMetadata metadata = ToolMetadata.builder().metadata(metadataMap).build();
        ToolWorkflowContract.validate(localToolName, metadata);
        List<McpToolWorkflowContract> history = contracts.findByToolIdOrderByContractVersionDesc(tool.getId());
        ToolWorkflowRole role = ToolWorkflowContract.declaredRole(metadata).orElseGet(() ->
            history.stream()
                .filter(item -> ACTIVE.equals(item.getStatus()))
                .findFirst()
                .map(item -> ToolWorkflowRole.valueOf(item.getWorkflowRole()))
                .orElseGet(() -> existingCatalogTool && history.isEmpty()
                    ? ToolWorkflowContract.resolveRole(localToolName, metadata)
                    : ToolWorkflowRole.DIRECT));
        Map<String, Object> published = publishedContract(discoveredMeta);
        if (published.isEmpty() && existingCatalogTool && history.isEmpty()
            && discoveredMeta != null && !discoveredMeta.isEmpty()) {
            // One-time migration copies the publisher's old routing metadata into the
            // versioned snapshot so legacy dynamic routes do not change behavior.
            published = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(discoveredMeta));
        }
        String family = text(published.get("protocolFamily"));
        String envelope = text(published.get("inputEnvelope"));
        String checksum = checksum(role, family, envelope, inputSchema, outputSchema, published);

        Optional<McpToolWorkflowContract> same = contracts
            .findFirstByToolIdAndContractChecksumOrderByContractVersionDesc(tool.getId(), checksum);
        if (same.isEmpty()) {
            McpToolWorkflowContract candidate = new McpToolWorkflowContract();
            candidate.setToolId(tool.getId());
            candidate.setContractVersion(history.isEmpty() ? 1L : history.get(0).getContractVersion() + 1L);
            candidate.setSchemaVersion(ToolWorkflowContract.SCHEMA_VERSION);
            candidate.setWorkflowRole(role.name());
            candidate.setProtocolFamily(family);
            candidate.setInputEnvelope(envelope);
            candidate.setContractChecksum(checksum);
            candidate.setInputSchemaJson(json(inputSchema));
            candidate.setOutputSchemaJson(json(outputSchema));
            candidate.setExtensionsJson(json(published));
            // Existing catalog rows are migrated without downtime. Trusted services may opt in
            // to atomic publication; strict services keep newly discovered contracts as DRAFT.
            boolean publishNow = autoPublish || (existingCatalogTool && history.isEmpty());
            if (publishNow) {
                retireActive(tool.getId());
            }
            candidate.setStatus(publishNow ? ACTIVE : DRAFT);
            if (ACTIVE.equals(candidate.getStatus())) {
                candidate.setPublishedAt(Instant.now());
                candidate.setPublishedBy(autoPublish
                    ? "system-trusted-service-discovery" : "system-legacy-migration");
            }
            contracts.saveAndFlush(candidate);
        } else if (autoPublish && !ACTIVE.equals(same.get().getStatus())) {
            // Recover a DRAFT left behind by an earlier database/storage failure or by a
            // service whose publication policy was subsequently enabled.
            retireActive(tool.getId());
            McpToolWorkflowContract candidate = same.get();
            candidate.setStatus(ACTIVE);
            candidate.setPublishedAt(Instant.now());
            candidate.setPublishedBy("system-trusted-service-discovery");
            contracts.saveAndFlush(candidate);
        }
        return contracts.findFirstByToolIdAndStatusOrderByContractVersionDesc(tool.getId(), ACTIVE)
            .filter(active -> checksum.equals(active.getContractChecksum()))
            .map(this::snapshot);
    }

    private void retireActive(String toolId) {
        List<McpToolWorkflowContract> active = contracts.findByToolIdAndStatus(toolId, ACTIVE);
        active.forEach(item -> item.setStatus(RETIRED));
        if (!active.isEmpty()) {
            contracts.saveAllAndFlush(active);
        }
    }

    @Transactional(readOnly = true)
    public List<McpToolWorkflowContract> listContracts(String toolId) {
        requireTool(toolId);
        return contracts.findByToolIdOrderByContractVersionDesc(toolId);
    }

    /** Updates publisher-controlled metadata while a discovered version is still a draft. */
    @Transactional
    public McpToolWorkflowContract reviseDraft(String toolId, long version, DraftRevision revision) {
        lockTool(toolId);
        McpToolWorkflowContract draft = contract(toolId, version);
        if (!DRAFT.equals(draft.getStatus())) {
            throw new IllegalStateException("Only DRAFT tool contracts can be revised");
        }
        DraftRevision value = revision == null ? new DraftRevision(null, null, null, null) : revision;
        ToolWorkflowRole role = value.workflowRole() == null
            ? ToolWorkflowRole.valueOf(draft.getWorkflowRole()) : value.workflowRole();
        Map<String, Object> extensions = value.extensions() == null
            ? map(draft.getExtensionsJson()) : value.extensions();
        draft.setWorkflowRole(role.name());
        draft.setProtocolFamily(text(value.protocolFamily()) == null
            ? draft.getProtocolFamily() : text(value.protocolFamily()));
        draft.setInputEnvelope(text(value.inputEnvelope()) == null
            ? draft.getInputEnvelope() : text(value.inputEnvelope()));
        draft.setExtensionsJson(json(extensions));
        draft.setContractChecksum(checksum(role, draft.getProtocolFamily(), draft.getInputEnvelope(),
            map(draft.getInputSchemaJson()), map(draft.getOutputSchemaJson()), extensions));
        return contracts.saveAndFlush(draft);
    }

    /** Atomically activates one immutable version and retires the previous ACTIVE version. */
    @Transactional
    public ToolWorkflowContractSnapshot publish(String toolId, long version, String actor) {
        return publish(toolId, version, actor, null);
    }

    @Transactional
    public ToolWorkflowContractSnapshot publish(String toolId, long version, String actor,
                                                Long expectedActiveVersion) {
        lockTool(toolId);
        McpToolWorkflowContract selected = contract(toolId, version);
        List<McpToolWorkflowContract> active = contracts.findByToolIdAndStatus(toolId, ACTIVE);
        if (active.size() > 1) {
            throw new IllegalStateException("Catalog integrity violation: multiple ACTIVE tool contracts for " + toolId);
        }
        long activeVersion = active.isEmpty() ? 0L : active.get(0).getContractVersion();
        if (expectedActiveVersion != null && expectedActiveVersion != activeVersion) {
            throw new IllegalStateException("Tool contract publication conflict: expected ACTIVE version "
                + expectedActiveVersion + " but found " + activeVersion);
        }
        if (ACTIVE.equals(selected.getStatus())) {
            return snapshot(selected);
        }
        if (!DRAFT.equals(selected.getStatus()) && !RETIRED.equals(selected.getStatus())) {
            throw new IllegalStateException("Tool contract cannot be published from status " + selected.getStatus());
        }
        active.forEach(item -> item.setStatus(RETIRED));
        contracts.saveAll(active);
        selected.setStatus(ACTIVE);
        selected.setPublishedAt(Instant.now());
        selected.setPublishedBy(required(actor, "publisher"));
        return snapshot(contracts.saveAndFlush(selected));
    }

    private McpToolAsset lockTool(String toolId) {
        return tools.findLockedById(required(toolId, "toolId"))
            .orElseThrow(() -> new IllegalArgumentException("MCP tool not found: " + toolId));
    }

    private void requireTool(String toolId) {
        if (!tools.existsById(required(toolId, "toolId"))) {
            throw new IllegalArgumentException("MCP tool not found: " + toolId);
        }
    }

    private McpToolWorkflowContract contract(String toolId, long version) {
        return contracts.findByToolIdOrderByContractVersionDesc(toolId).stream()
            .filter(item -> item.getContractVersion() == version)
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException(
                "Tool contract version not found: " + toolId + "@" + version));
    }

    private ToolWorkflowContractSnapshot snapshot(McpToolWorkflowContract value) {
        return new ToolWorkflowContractSnapshot(value.getToolId(), value.getContractVersion(),
            value.getSchemaVersion(), ToolWorkflowRole.valueOf(value.getWorkflowRole()),
            value.getProtocolFamily(), value.getInputEnvelope(), value.getContractChecksum(),
            map(value.getInputSchemaJson()), map(value.getOutputSchemaJson()),
            map(value.getExtensionsJson()));
    }

    private Map<String, Object> publishedContract(Map<String, Object> meta) {
        if (meta == null) return Map.of();
        Map<String, Object> published = new LinkedHashMap<>();
        Object value = meta.get(ToolWorkflowContract.METADATA_KEY);
        if (value instanceof Map<?, ?> raw) {
            // Workflow fields remain flattened for backwards-compatible snapshots.
            published.putAll(stringMap(raw));
        }

        // Parent/child capability identity is part of the executable Runtime OS
        // contract. Persist only the structural routing metadata required to rebuild
        // the hierarchy; transient discovery/transport state must not enter governance.
        copyMapMetadata(published, meta, McpDynamicCapabilityRoute.METADATA_KEY);
        copyMapMetadata(published, meta, McpCapabilityHierarchy.METADATA_KEY);

        // Preserve legacy publishers during rolling upgrades. The bridge normalizes
        // these fields into the canonical dynamic route before planner projection.
        copyScalarMetadata(published, meta, "kind");
        copyScalarMetadata(published, meta, "parentToolName");
        copyScalarMetadata(published, meta, "routingMode");
        copyScalarMetadata(published, meta, "nodeKind");
        copyScalarMetadata(published, meta, "fallbackPolicy");
        return published.isEmpty() ? Map.of()
            : java.util.Collections.unmodifiableMap(published);
    }

    private void copyMapMetadata(Map<String, Object> target, Map<String, Object> source, String key) {
        Object value = source.get(key);
        if (value instanceof Map<?, ?> raw) {
            target.put(key, java.util.Collections.unmodifiableMap(stringMap(raw)));
        }
    }

    private void copyScalarMetadata(Map<String, Object> target, Map<String, Object> source, String key) {
        Object value = source.get(key);
        if (value instanceof String || value instanceof Number || value instanceof Boolean) {
            target.put(key, value);
        }
    }

    private String checksum(Object... values) {
        try {
            ObjectMapper canonical = objectMapper.copy()
                .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
            byte[] bytes = canonical.writeValueAsString(Arrays.asList(values)).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception ex) {
            throw new IllegalStateException("Cannot calculate tool contract checksum", ex);
        }
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value == null ? Map.of() : value); }
        catch (Exception ex) { throw new IllegalArgumentException("Invalid tool contract JSON", ex); }
    }

    private Map<String, Object> map(String value) {
        if (value == null || value.isBlank()) return Map.of();
        try { return objectMapper.readValue(value, new TypeReference<>() {}); }
        catch (Exception ex) { throw new IllegalStateException("Stored tool contract JSON is invalid", ex); }
    }

    private Map<String, Object> stringMap(Map<?, ?> value) {
        Map<String, Object> result = new LinkedHashMap<>();
        value.forEach((key, item) -> { if (key != null) result.put(String.valueOf(key), item); });
        return result;
    }

    private String text(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private String required(String value, String label) {
        String result = text(value);
        if (result == null) throw new IllegalArgumentException(label + " is required");
        return result;
    }

    public record DraftRevision(ToolWorkflowRole workflowRole,
                                String protocolFamily,
                                String inputEnvelope,
                                Map<String, Object> extensions) {
        public DraftRevision {
            extensions = extensions == null ? null
                : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(extensions));
        }
    }
}
