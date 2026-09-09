package com.chatchat.mcpserver.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** The single startup coordinator for independently isolated MCP tool contributors. */
@Component
@Slf4j
public class McpToolPublicationCoordinator {

    private final List<McpToolContributor> contributors;
    private final AtomicLong successfulPublications = new AtomicLong();
    private final AtomicLong failedPublications = new AtomicLong();

    public McpToolPublicationCoordinator(List<McpToolContributor> contributors) {
        this.contributors = contributors == null ? List.of() : List.copyOf(contributors);
        java.util.Set<String> ids = new java.util.LinkedHashSet<>();
        for (McpToolContributor contributor : this.contributors) {
            if (contributor == null || contributor.contributorId() == null
                || contributor.contributorId().isBlank()) {
                throw new IllegalArgumentException("MCP contributor id is required");
            }
            if (!ids.add(contributor.contributorId())) {
                throw new IllegalArgumentException("Duplicate MCP contributor id: "
                    + contributor.contributorId());
            }
        }
    }

    // Publish Runtime tools before optional startup enrichments (for example an
    // enterprise metadata refresh) are allowed to contact external systems.
    // A slow enrichment must never leave an already-listening MCP transport
    // with only its statically registered tools.
    @Order(Ordered.HIGHEST_PRECEDENCE)
    @EventListener(ApplicationReadyEvent.class)
    public void publishOnStartup() {
        refreshAll("application_ready");
    }

    public Map<String, McpPublicationStartupGuard.StartupPublicationResult> refreshAll(String trigger) {
        Map<String, McpPublicationStartupGuard.StartupPublicationResult> results = new LinkedHashMap<>();
        for (McpToolContributor contributor : contributors) {
            McpPublicationStartupGuard.StartupPublicationResult result = McpPublicationStartupGuard.run(
                contributor.getClass(), contributor::refreshPublication);
            results.put(contributor.contributorId(), result);
            if (result.success()) successfulPublications.incrementAndGet();
            else failedPublications.incrementAndGet();
        }
        log.info("MCP publication batch completed trigger={} contributors={} successful={} failed={}",
            trigger, contributors.size(), results.values().stream().filter(
                McpPublicationStartupGuard.StartupPublicationResult::success).count(),
            results.values().stream().filter(item -> !item.success()).count());
        return Map.copyOf(results);
    }

    public Map<String, Long> metrics() {
        return Map.of("successfulPublications", successfulPublications.get(),
            "failedPublications", failedPublications.get());
    }
}
