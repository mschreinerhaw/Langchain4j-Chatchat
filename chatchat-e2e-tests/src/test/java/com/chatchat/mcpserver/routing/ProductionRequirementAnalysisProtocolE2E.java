package com.chatchat.mcpserver.routing;

import com.chatchat.mcpserver.api.publication.ApiRequirementAnalysisMcpToolPublisher;
import com.chatchat.mcpserver.routing.protocol.RequirementAnalysisProtocol;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class ProductionRequirementAnalysisProtocolE2E {

    @Test
    void extremePlannerPayloadIsNormalizedWithoutBusinessSpecificRules() {
        List<Map<String, Object>> requirements = new ArrayList<>();
        for (int index = 0; index < RequirementAnalysisProtocol.MAX_REQUIREMENTS; index++) {
            Map<String, Object> requirement = new LinkedHashMap<>();
            if (index % 2 == 0) {
                requirement.put("intent", "  动态需求-" + index + "-Δ  ");
            } else {
                requirement.put("description", "dynamic-requirement-" + index);
            }
            requirement.put("requiredOutputs", List.of("field-" + index));
            requirement.put("constraints", List.of("constraint-" + index));
            requirements.add(requirement);
        }
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("requirements", requirements);
        arguments.put("limitPerRequirement", Integer.MAX_VALUE);
        arguments.put("context", Map.of(
            "env", "candidate-" + System.nanoTime(),
            "service", "service-" + System.nanoTime(),
            "untrustedExtra", "must-not-become-a-filter"));

        RequirementAnalysisProtocol.NormalizedRequest normalized =
            RequirementAnalysisProtocol.normalize(arguments);

        assertThat(normalized.goal()).isEqualTo("动态需求-0-Δ");
        assertThat(normalized.requirements()).hasSize(RequirementAnalysisProtocol.MAX_REQUIREMENTS);
        assertThat(normalized.requirements()).extracting(item -> item.get("id"))
            .containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(1, RequirementAnalysisProtocol.MAX_REQUIREMENTS)
                .mapToObj(index -> "requirement_" + index).toList());
        assertThat(normalized.limitPerRequirement()).isEqualTo(10);

        Map<String, Object> filters = RequirementAnalysisProtocol.discoveryFilters(
            normalized.requirements().get(0), normalized.goal(), normalized.context());
        assertThat(filters).containsKeys("intent", "goal", "keywords", "retrievalSignals", "env", "service")
            .doesNotContainKey("untrustedExtra");
    }

    @Test
    void malformedEmptyAndOversizedPlannerPayloadsFailClosed() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> RequirementAnalysisProtocol.normalize(null))
            .withMessageContaining("at least one");
        assertThatIllegalArgumentException()
            .isThrownBy(() -> RequirementAnalysisProtocol.normalize(Map.of("requirements", List.of())))
            .withMessageContaining("at least one");
        assertThatIllegalArgumentException()
            .isThrownBy(() -> RequirementAnalysisProtocol.normalize(Map.of("requirements", List.of("not-an-object"))))
            .withMessageContaining("must be an object");
        assertThatIllegalArgumentException()
            .isThrownBy(() -> RequirementAnalysisProtocol.normalize(Map.of("requirements", List.of(Map.of("intent", "  ")))))
            .withMessageContaining("description or intent");
        assertThatIllegalArgumentException()
            .isThrownBy(() -> RequirementAnalysisProtocol.normalize(Map.of("query", "  ")))
            .withMessageContaining("requirements or query");
        assertThatIllegalArgumentException()
            .isThrownBy(() -> RequirementAnalysisProtocol.normalize(Map.of(
                "requirements", "not-an-array", "query", "must-not-hide-malformed-structured-input")))
            .withMessageContaining("must be an array");

        List<Map<String, Object>> oversized = java.util.stream.IntStream
            .rangeClosed(1, RequirementAnalysisProtocol.MAX_REQUIREMENTS + 1)
            .mapToObj(index -> Map.<String, Object>of("intent", "requirement-" + index))
            .toList();
        assertThatIllegalArgumentException()
            .isThrownBy(() -> RequirementAnalysisProtocol.normalize(Map.of("requirements", oversized)))
            .withMessageContaining("exceeds maximum");
    }

    @Test
    void apiAndHttpPublishersAreForcedToUseOneDomainNeutralProtocol() throws IOException {
        Path root = repositoryRoot();
        String protocol = Files.readString(root.resolve(
            "chatchat-mcp-server/src/main/java/com/chatchat/mcpserver/routing/protocol/RequirementAnalysisProtocol.java"));
        String apiPublisher = Files.readString(root.resolve(
            "chatchat-mcp-server/src/main/java/com/chatchat/mcpserver/api/publication/ApiRequirementAnalysisMcpToolPublisher.java"));
        String httpPublisher = Files.readString(root.resolve(
            "chatchat-mcp-server/src/main/java/com/chatchat/mcpserver/ops/discovery/HttpRequirementAnalysisMcpToolPublisher.java"));

        assertThat(apiPublisher).contains(
            "RequirementAnalysisProtocol.inputProperties()",
            "RequirementAnalysisProtocol.normalize(arguments)",
            "RequirementAnalysisProtocol.discoveryFilters(");
        assertThat(httpPublisher).contains(
            "RequirementAnalysisProtocol.inputProperties()",
            "RequirementAnalysisProtocol.normalize(arguments)",
            "RequirementAnalysisProtocol.discoveryFilters(");
        assertThat(protocol)
            .doesNotContain("YARN", "ResourceManager", "master10", "apex.com", "192.168.")
            .doesNotMatch("(?s).*https?://.*");
    }

    private Path repositoryRoot() {
        String configured = System.getProperty("chatchat.e2e.repository-root");
        Path root = configured == null || configured.isBlank()
            ? Path.of("").toAbsolutePath().normalize().getParent()
            : Path.of(configured).toAbsolutePath().normalize();
        assertThat(root.resolve("pom.xml")).isRegularFile();
        return root;
    }
}
