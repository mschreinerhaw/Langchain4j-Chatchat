package com.chatchat.common.knowledge.template;

import com.chatchat.common.knowledge.SearchStatus;
import com.chatchat.common.knowledge.StandardSearchResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TemplateKnowledgeProtocolTest {

    @Test
    void normalizesToolSpecificAliasesIntoTheCanonicalKnowledgeProtocol() {
        StandardSearchResult<StandardTemplateKnowledge> result = TemplateKnowledgeProtocol.searchResult(
            "customer profile",
            List.of(Map.of(
                "template_id", "customer_profile_v1",
                "description", "Read customer profile",
                "parameterContract", Map.of("executionTool", "api_template_execute"),
                "inputSchema", Map.of(
                    "type", "object",
                    "properties", Map.of("customerId", Map.of("type", "string")),
                    "required", List.of("customerId")),
                "relevanceScore", 0.97D)),
            1, 10, false, Map.of("source", "api_template_query"));

        assertThat(result.schemaVersion()).isEqualTo(StandardSearchResult.SCHEMA_VERSION);
        assertThat(result.status()).isEqualTo(SearchStatus.FOUND);
        assertThat(result.hits()).singleElement().satisfies(hit -> {
            assertThat(hit.rank()).isEqualTo(1);
            assertThat(hit.score()).isEqualTo(0.97D);
            assertThat(hit.document().schemaVersion()).isEqualTo(StandardTemplateKnowledge.SCHEMA_VERSION);
            assertThat(hit.document().templateId()).isEqualTo("customer_profile_v1");
            assertThat(hit.document().executorTool()).isEqualTo("api_template_execute");
            assertThat(hit.document().requiredParameters()).containsExactly("customerId");
        });
    }

    @Test
    void acceptsNullValuesAtTheUntrustedToolBoundary() {
        Map<String, Object> candidate = new java.util.LinkedHashMap<>();
        candidate.put("templateId", "nullable_template");
        candidate.put("description", null);

        StandardTemplateKnowledge template = TemplateKnowledgeProtocol.template(candidate);

        assertThat(template).isNotNull();
        assertThat(template.attributes()).containsKey("description");
    }

    @Test
    void marksMalformedPublisherCandidatesAsPartialInsteadOfSilentlyAcceptingDrift() {
        StandardSearchResult<StandardTemplateKnowledge> result = TemplateKnowledgeProtocol.searchResult(
            "health", List.of(
                Map.of("templateId", "valid_template"),
                Map.of("title", "missing template id")),
            2, 10, false, Map.of());

        assertThat(result.status()).isEqualTo(SearchStatus.PARTIAL);
        assertThat(result.hits()).hasSize(1);
        assertThat(result.metadata()).containsEntry("rejectedCandidateCount", 1);
    }

    @Test
    void publishesTypedRecoveryEventsForMissingSelectionAndParameters() {
        TemplateResolutionEvent selection = TemplateResolutionEvent.missingId("req-1", "template_query");
        TemplateResolutionEvent parameters = TemplateResolutionEvent.missingParameters(
            "req-1", "customer_profile_v1", List.of("customerId"));

        assertThat(selection.type()).isEqualTo(TemplateResolutionEventType.TEMPLATE_ID_MISSING);
        assertThat(selection.recoveryAction()).isEqualTo(TemplateRecoveryAction.SEARCH_TEMPLATE);
        assertThat(parameters.type()).isEqualTo(TemplateResolutionEventType.TEMPLATE_PARAMETERS_MISSING);
        assertThat(parameters.missingParameters()).containsExactly("customerId");
        assertThat(parameters.recoveryAction()).isEqualTo(TemplateRecoveryAction.REQUEST_PARAMETERS);
    }

    @Test
    void validatesAndCarriesProducerSemanticsWithoutRuntimeDomainKnowledge() {
        Map<String, Object> declaration = Map.of(
            "schemaVersion", "producer_semantic_declaration.v1",
            "capabilityId", "generic.observation",
            "allowedOperations", List.of("OBSERVE"),
            "fields", List.of(Map.of("name", "value", "meaning", "producer supplied value")),
            "evidenceScope", Map.of("grain", "", "timeScope", "",
                "populationScope", "", "completeness", "UNKNOWN"),
            "rules", List.of());

        StandardTemplateKnowledge template = TemplateKnowledgeProtocol.template(Map.of(
            "templateId", "generic_observation", "producerSemanticDeclaration", declaration));

        assertThat(template.attributes().get("producerSemanticDeclaration").toString())
            .contains("generic.observation", "OBSERVE");
        assertThat(template.attributes().get("analysisContext").toString())
            .contains("capabilityId=generic.observation", "producer supplied value");
    }
}
