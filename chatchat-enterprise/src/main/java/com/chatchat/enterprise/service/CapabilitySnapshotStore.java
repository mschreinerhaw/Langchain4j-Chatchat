package com.chatchat.enterprise.service;

import com.chatchat.common.mcp.capability.CapabilityManifest;
import com.chatchat.common.mcp.service.McpToolDescriptor;
import com.chatchat.enterprise.entity.mcp.*;
import com.chatchat.enterprise.repository.mcp.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.util.*;

/** Stores projections and immutable content versions; existing tool tables remain authoritative. */
@Service
public class CapabilitySnapshotStore {
    private final CapabilityRegistryRepository registry;
    private final CapabilityVersionRepository versions;
    private final CapabilityManifestFactory factory;
    private final TransactionTemplate transaction;
    public CapabilitySnapshotStore(CapabilityRegistryRepository registry, CapabilityVersionRepository versions,
                                   ObjectMapper mapper, PlatformTransactionManager manager) {
        this.registry = registry; this.versions = versions;
        factory = new CapabilityManifestFactory(mapper); transaction = new TransactionTemplate(manager);
    }
    public synchronized List<CapabilityManifest> synchronize(List<McpToolDescriptor> tools,
                                                            Set<String> authoritativeServices) {
        return transaction.execute(ignored -> {
            Map<String, CapabilityRegistryEntry> existing = new HashMap<>();
            registry.findAll().forEach(entry -> existing.put(entry.getCapabilityId(), entry));
            Set<String> seen = new HashSet<>();
            List<CapabilityManifest> manifests = new ArrayList<>();
            for (McpToolDescriptor tool : tools) {
                CapabilityManifest manifest = factory.generate(tool, status(tool));
                seen.add(manifest.capabilityId());
                persist(manifest, tool.serviceId(), tool.localToolName(), existing.get(manifest.capabilityId()));
                manifests.add(manifest);
            }
            for (CapabilityRegistryEntry entry : existing.values()) {
                if (!seen.contains(entry.getCapabilityId()) && authoritativeServices.contains(entry.getServiceId())
                    && !"RETIRED".equals(entry.getStatus())) {
                    CapabilityManifestVersion old = versions.findById(entry.getCapabilityId() + ":" + entry.getCurrentVersion()).orElseThrow();
                    try {
                        CapabilityManifest previous = new ObjectMapper().readValue(old.getManifestJson(), CapabilityManifest.class);
                        persist(factory.withStatus(previous, "RETIRED"), entry.getServiceId(), entry.getToolName(), entry);
                    } catch (Exception ex) { throw new IllegalStateException("Cannot retire capability snapshot", ex); }
                }
            }
            return List.copyOf(manifests);
        });
    }
    private void persist(CapabilityManifest manifest, String service, String tool, CapabilityRegistryEntry entry) {
        String versionId = manifest.capabilityId() + ":" + manifest.version();
        if (!versions.existsById(versionId)) {
            CapabilityManifestVersion snapshot = new CapabilityManifestVersion();
            snapshot.setId(versionId); snapshot.setCapabilityId(manifest.capabilityId());
            snapshot.setContentHash(manifest.version()); snapshot.setManifestJson(factory.json(manifest));
            snapshot.setCreatedAt(Instant.now()); versions.save(snapshot);
        }
        if (entry != null && manifest.version().equals(entry.getCurrentVersion())) return;
        if (entry == null) { entry = new CapabilityRegistryEntry(); entry.setCapabilityId(manifest.capabilityId()); }
        entry.setServiceId(service); entry.setToolName(tool); entry.setStatus(manifest.status());
        entry.setCurrentVersion(manifest.version()); entry.setUpdatedAt(Instant.now()); registry.save(entry);
    }
    private String status(McpToolDescriptor tool) {
        Object declared = tool.governance().get("publicationStatus");
        if (Boolean.FALSE.equals(tool.governance().get("enabled"))) return "DISABLED";
        if (declared == null) return "PUBLISHED";
        return switch (String.valueOf(declared).toLowerCase(Locale.ROOT)) {
            case "active", "published", "online" -> "PUBLISHED";
            case "deprecated" -> "DEPRECATED";
            case "retired" -> "RETIRED";
            case "disabled" -> "DISABLED";
            default -> "DRAFT";
        };
    }
}
