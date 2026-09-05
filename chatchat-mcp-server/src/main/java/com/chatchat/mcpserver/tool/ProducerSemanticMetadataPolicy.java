package com.chatchat.mcpserver.tool;

import com.chatchat.agents.protocol.ModelProtocolJson;
import com.chatchat.common.runtime.summary.analysis.semantic.adapter.ProducerSemanticDeclarationProtocol;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

/** Producer-side persistence policy for the domain-neutral semantic declaration. */
public final class ProducerSemanticMetadataPolicy {

    private ProducerSemanticMetadataPolicy() {
    }

    public static String normalize(ObjectMapper objectMapper, String json, String fieldName) {
        if (json == null || json.isBlank()) return null;
        try {
            Map<String, Object> metadata = objectMapper.readValue(json, new TypeReference<>() { });
            return ModelProtocolJson.compact(
                ProducerSemanticDeclarationProtocol.canonicalizeOwnerMetadata(metadata));
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException(fieldName + " must be a valid JSON object");
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(fieldName + ".producerSemanticDeclaration is invalid: "
                + ex.getMessage(), ex);
        }
    }

    public static ProducerSemanticDeclarationProtocol.Readiness readiness(ObjectMapper objectMapper,
                                                                           String json,
                                                                           String fieldName) {
        if (json == null || json.isBlank()) {
            return ProducerSemanticDeclarationProtocol.Readiness.MISSING_OBSERVE_ONLY;
        }
        try {
            Map<String, Object> metadata = objectMapper.readValue(json, new TypeReference<>() { });
            return ProducerSemanticDeclarationProtocol.readiness(metadata);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException(fieldName + " must be a valid JSON object");
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(fieldName + ".producerSemanticDeclaration is invalid: "
                + ex.getMessage(), ex);
        }
    }
}
