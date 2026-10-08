package com.chatchat.mcpserver.datacapability.connection;

import com.chatchat.mcpserver.datacapability.definition.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Keep centrally managed assets available while query definitions still reference them. */
@Component
@RequiredArgsConstructor
public class CapabilityAssetReferenceGuard {
    private final CapabilityRepository definitions;
    private final ObjectMapper json;

    public void assertUnused(String assetId, boolean jdbcAsset) {
        for (CapabilityEntity definition : definitions.findAll()) {
            boolean matchesFamily = jdbcAsset
                ? definition.getType() == CapabilityType.TRINO || definition.getType() == CapabilityType.RELATIONAL
                : definition.getType() == CapabilityType.GRAPH || definition.getType() == CapabilityType.UNSTRUCTURED;
            if (!matchesFamily) continue;
            String reference;
            try { reference = json.readTree(definition.getDefinitionJson()).path("connectionId").asText(); }
            catch (Exception ex) { throw new IllegalStateException("Invalid data capability definition: " + definition.getCode(), ex); }
            if (assetId.equals(reference))
                throw new IllegalArgumentException("Datasource asset is referenced by capability: " + definition.getCode());
        }
    }
}
