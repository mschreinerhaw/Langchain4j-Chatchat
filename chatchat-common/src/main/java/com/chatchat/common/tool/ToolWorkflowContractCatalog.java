package com.chatchat.common.tool;

import java.util.Map;
import java.util.Optional;

/** Persistent source for versioned tool workflow contracts. */
public interface ToolWorkflowContractCatalog {

    Optional<ToolWorkflowContractSnapshot> findActive(String serviceId,
                                                       String localToolName,
                                                       String remoteToolName);

    /** Synchronizes discovery as catalog state and returns ACTIVE only when publishable. */
    Optional<ToolWorkflowContractSnapshot> synchronizeDiscovery(String serviceId,
                                                                String serviceName,
                                                                String localToolName,
                                                                String remoteToolName,
                                                                String description,
                                                                Map<String, Object> inputSchema,
                                                                Map<String, Object> outputSchema,
                                                                Map<String, Object> discoveredMeta,
                                                                boolean autoPublish);
}
