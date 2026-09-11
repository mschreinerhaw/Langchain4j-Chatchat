package com.chatchat.mcpserver.tool;

import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Validates, publishes and rolls back one contributor's differential publication batch. */
@Slf4j
public final class McpToolPublicationPipeline {

    private static final Map<McpSyncServer, Map<String, Map<String, ToolPublication>>> SNAPSHOTS =
        java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private static final Map<McpSyncServer, Map<String, Set<String>>> PENDING_REMOVALS =
        java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    private McpToolPublicationPipeline() {
    }

    public static PublicationResult publish(McpSyncServer server, McpToolContributor contributor) {
        if (contributor == null || contributor.contributorId() == null
            || contributor.contributorId().isBlank()) {
            throw new IllegalArgumentException("MCP contributor id is required");
        }
        if (server == null) {
            return new PublicationResult(contributor.contributorId(), true, 0, 0, 0,
                List.of(), List.of());
        }
        long started = System.currentTimeMillis();
        List<ToolPublication> supplied = contributor.contribute();
        List<ToolPublication> contributed = supplied == null ? List.of() : List.copyOf(supplied);
        Set<String> suppliedRetired = contributor.retiredToolNames();
        Set<String> retired = suppliedRetired == null ? Set.of() : suppliedRetired.stream()
            .filter(name -> name != null && !name.isBlank())
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Map<String, ToolPublication> desired = new LinkedHashMap<>();
        for (ToolPublication publication : contributed) {
            McpToolPublicationReviewer.review(publication);
            ToolPublication duplicate = desired.putIfAbsent(publication.toolName(), publication);
            if (duplicate != null) {
                throw new IllegalArgumentException("Duplicate tool from contributor "
                    + contributor.contributorId() + ": " + publication.toolName());
            }
        }

        synchronized (server) {
            Map<String, Map<String, ToolPublication>> byContributor =
                SNAPSHOTS.computeIfAbsent(server, ignored -> new LinkedHashMap<>());
            Map<String, Set<String>> pendingByContributor =
                PENDING_REMOVALS.computeIfAbsent(server, ignored -> new LinkedHashMap<>());
            Set<String> ownedNames = desired.values().stream()
                .filter(item -> item.descriptor().status() != McpToolPublicationStatus.RETIRED)
                .map(ToolPublication::toolName)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            assertNoCrossContributorConflicts(contributor.contributorId(), ownedNames, byContributor);
            Map<String, ToolPublication> previous = new LinkedHashMap<>(
                byContributor.getOrDefault(contributor.contributorId(), Map.of()));
            detectBreakingChanges(previous, desired);

            List<String> attempted = new ArrayList<>();
            try {
                for (ToolPublication publication : desired.values()) {
                    if (!publication.descriptor().status().publishable()) continue;
                    ToolPublication old = previous.get(publication.toolName());
                    if (sameContract(old, publication)) continue;
                    attempted.add(publication.toolName());
                    McpDynamicToolRegistryMirror.publishIfAvailable(publication);
                    McpToolPublicationReviewer.addReviewedTool(server, publication);
                }
            } catch (RuntimeException failure) {
                rollback(server, previous, attempted);
                throw failure;
            }

            Set<String> retained = desired.values().stream()
                .filter(item -> item.descriptor().status().publishable())
                .map(ToolPublication::toolName)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            Set<String> priorPending = pendingByContributor.getOrDefault(
                contributor.contributorId(), Set.of());
            Set<String> obsolete = new LinkedHashSet<>(previous.keySet());
            obsolete.addAll(priorPending);
            // A contributor may declare a large legacy namespace as retired (for
            // example one historical tool per host or datasource). On a fresh
            // process most of those names are not present in the SDK registry.
            // Calling removeTool for every absent name is expensive because the
            // SDK serializes registry mutations and notification bookkeeping.
            // Previous/pending publications must still be removed even if the
            // SDK snapshot has drifted; declaration-only retirements are limited
            // to names that are actually live.
            Set<String> liveToolNames = server.listTools().stream()
                .map(McpSchema.Tool::name)
                .filter(name -> name != null && !name.isBlank())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            retired.stream().filter(liveToolNames::contains).forEach(obsolete::add);
            obsolete.removeAll(retained);
            Set<String> removed = obsolete.stream().filter(name -> remove(server, name))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            Set<String> failedRemovals = new LinkedHashSet<>(previous.keySet());
            failedRemovals.addAll(priorPending);
            failedRemovals.retainAll(obsolete);
            failedRemovals.removeAll(removed);
            if (failedRemovals.isEmpty()) pendingByContributor.remove(contributor.contributorId());
            else pendingByContributor.put(contributor.contributorId(), Set.copyOf(failedRemovals));
            obsolete.forEach(McpDynamicToolRegistryMirror::unpublishIfAvailable);
            byContributor.put(contributor.contributorId(), new LinkedHashMap<>(desired));
            if (!attempted.isEmpty() || !removed.isEmpty()) server.notifyToolsListChanged();
            long duration = Math.max(0L, System.currentTimeMillis() - started);
            log.info("MCP publication completed contributor={} desired={} published={} removed={} durationMs={}",
                contributor.contributorId(), desired.size(), retained.size(), removed.size(), duration);
            return new PublicationResult(contributor.contributorId(), true, desired.size(), retained.size(),
                removed.size(), List.copyOf(retained), List.copyOf(removed));
        }
    }

    private static boolean sameContract(ToolPublication current, ToolPublication next) {
        return current != null
            && java.util.Objects.equals(current.descriptor(), next.descriptor())
            && java.util.Objects.equals(current.specification().tool(), next.specification().tool());
    }

    private static void assertNoCrossContributorConflicts(String contributorId, Set<String> names,
                                                           Map<String, Map<String, ToolPublication>> snapshots) {
        snapshots.forEach((owner, publications) -> {
            if (owner.equals(contributorId)) return;
            names.forEach(name -> {
                ToolPublication existing = publications.get(name);
                if (existing != null
                    && existing.descriptor().status() != McpToolPublicationStatus.RETIRED) {
                    throw new IllegalStateException("MCP tool publication conflict: " + name
                        + " is already owned by " + owner);
                }
            });
        });
    }

    private static void detectBreakingChanges(Map<String, ToolPublication> previous,
                                               Map<String, ToolPublication> desired) {
        desired.forEach((name, next) -> {
            ToolPublication current = previous.get(name);
            if (current == null || next.descriptor().breakingChangeApproved()) return;
            List<String> changes = breakingChanges(current.specification().tool(), next.specification().tool());
            if (!changes.isEmpty()) {
                throw new IllegalStateException("MCP_BREAKING_SCHEMA_CHANGE: tool=" + name
                    + ", changes=" + changes
                    + "; set breakingChangeApproved=true only after an explicit migration decision");
            }
        });
    }

    @SuppressWarnings("unchecked")
    static List<String> breakingChanges(McpSchema.Tool current, McpSchema.Tool next) {
        Map<String, Object> oldSchema = current.inputSchema() == null ? Map.of() : current.inputSchema();
        Map<String, Object> newSchema = next.inputSchema() == null ? Map.of() : next.inputSchema();
        Set<String> oldRequired = stringSet(oldSchema.get("required"));
        Set<String> newRequired = stringSet(newSchema.get("required"));
        List<String> changes = new ArrayList<>();
        newRequired.stream().filter(name -> !oldRequired.contains(name))
            .forEach(name -> changes.add("required_added:" + name));
        Map<String, Object> oldProperties = map(oldSchema.get("properties"));
        Map<String, Object> newProperties = map(newSchema.get("properties"));
        oldProperties.forEach((name, oldValue) -> {
            if (!newProperties.containsKey(name)) {
                changes.add("property_removed:" + name);
                return;
            }
            Object oldType = map(oldValue).get("type");
            Object newType = map(newProperties.get(name)).get("type");
            if (oldType != null && newType != null && !oldType.equals(newType)) {
                changes.add("type_changed:" + name + ":" + oldType + "->" + newType);
            }
        });
        return List.copyOf(changes);
    }

    private static void rollback(McpSyncServer server, Map<String, ToolPublication> previous,
                                 List<String> added) {
        for (String name : added) {
            ToolPublication old = previous.get(name);
            try {
                if (old == null) server.removeTool(name);
                else {
                    McpDynamicToolRegistryMirror.publishIfAvailable(old);
                    McpToolPublicationReviewer.addReviewedTool(server, old);
                }
                if (old == null) McpDynamicToolRegistryMirror.unpublishIfAvailable(name);
            } catch (Exception rollbackFailure) {
                log.error("MCP publication rollback failed tool={}", name, rollbackFailure);
            }
        }
    }

    private static boolean remove(McpSyncServer server, String name) {
        if (name == null || name.isBlank()) return false;
        try {
            server.removeTool(name);
            return true;
        } catch (Exception absent) {
            log.debug("MCP tool was not registered tool={} reason={}", name, absent.getMessage());
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> raw)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private static Set<String> stringSet(Object value) {
        if (!(value instanceof Iterable<?> iterable)) return Set.of();
        Set<String> result = new LinkedHashSet<>();
        iterable.forEach(item -> { if (item != null) result.add(String.valueOf(item)); });
        return result;
    }

    public record PublicationResult(String contributorId, boolean success, int desired,
                                    int published, int removed, List<String> publishedTools,
                                    List<String> removedTools) {
    }
}
