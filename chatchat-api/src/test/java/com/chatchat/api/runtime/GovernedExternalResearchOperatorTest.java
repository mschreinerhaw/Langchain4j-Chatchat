package com.chatchat.api.runtime;

import com.chatchat.agents.runtime.analysis.workflow.AnalysisOperatorRegistry;
import com.chatchat.agents.runtime.analysis.workflow.ExternalResearchWorkflow;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.runtime.analysis.evidence.ToolAnalysisEvidence;
import com.chatchat.common.runtime.analysis.evidence.ExternalResearchEvidence;
import com.chatchat.common.runtime.analysis.execution.WorkflowExecutionResult;
import com.chatchat.common.runtime.analysis.model.AnalysisContext;
import com.chatchat.common.runtime.analysis.model.AnalysisScope;
import com.chatchat.common.runtime.analysis.model.AnalysisIntent;
import com.chatchat.common.runtime.analysis.model.AnalysisCapability;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GovernedExternalResearchOperatorTest {
    private final RegisteredToolAnalysisOperator tools = mock(RegisteredToolAnalysisOperator.class);
    private final GovernedExternalResearchOperator operator =
        new GovernedExternalResearchOperator(tools, new ObjectMapper());

    @Test void acceptsTwoAttributedPublicSourcesFromGovernedSearch() {
        toolResult("{\"results\":["
            + "{\"url\":\"https://one.example.com/a\",\"title\":\"First\",\"snippet\":\"Fact A\",\"sourceName\":\"One\"},"
            + "{\"url\":\"https://two.example.org/b\",\"title\":\"Second\",\"snippet\":\"Fact B\",\"sourceName\":\"Two\"}]}");

        var result = operator.execute(context("mcp_news_web_search"), scope(), null);

        assertThat(result.evidence()).hasSize(2);
        assertThat(result.evidence().get(0).attributes()).containsKey("remoteProjection");
    }

    @Test void keepsSinglePublicSourceAndExcludesPrivateUrlWithoutJudgingSufficiency() {
        toolResult("{\"results\":["
            + "{\"url\":\"http://localhost/private\",\"title\":\"Secret\",\"snippet\":\"No\"},"
            + "{\"url\":\"https://one.example.com/a\",\"title\":\"First\",\"snippet\":\"Fact A\"}]}");

        var result = operator.execute(context("mcp_news_web_search"), scope(), null);

        assertThat(result.evidence()).hasSize(1);
        assertThat(result.evidence().get(0).content()).contains("First").doesNotContain("Secret");
        assertThat(result.outputs()).containsEntry("sourceCount", 1).containsEntry("distinctHostCount", 1);
        assertThat(result.observations()).singleElement()
            .satisfies(value -> assertThat(value).contains("user judgment"));
        assertThat(operator.available(context("arbitrary_tool"))).isFalse();
    }

    @Test void currentResearchPreservesStaleAndUndatedSourcesForUserJudgment() {
        toolResult("{\"results\":["
            + "{\"url\":\"https://one.example.com/a\",\"title\":\"First\",\"snippet\":\"Old\",\"publishTime\":\"2020-01-01\"},"
            + "{\"url\":\"https://two.example.org/b\",\"title\":\"Second\",\"snippet\":\"Undated\"}]}");
        AnalysisContext context = new AnalysisContext("Current news", new KernelDataScope("tenant", "user",
            "request", null, "run", null, Map.of()), "skill", List.of(), List.of(), List.of(),
            new AnalysisIntent("RESEARCH", List.of(), Set.of(AnalysisCapability.EXTERNAL_RESEARCH),
            "CURRENT", true), Map.of(GovernedExternalResearchOperator.TOOL_NAME, "mcp_news_web_search",
                GovernedExternalResearchOperator.SEARCH_TERMS, List.of("public news")));

        var result = operator.execute(context, scope(), null);

        assertThat(result.evidence()).hasSize(2);
        assertThat(result.evidence().get(0).attributes().get("remoteProjection").toString())
            .contains("2020-01-01");
        assertThat(((ExternalResearchEvidence) result.evidence().get(1)).publishedAt()).isEmpty();
    }

    @Test void rejectsOnlyWhenNoAttributablePublicSourceExists() {
        toolResult("{\"results\":[{\"url\":\"http://localhost/private\","
            + "\"title\":\"Secret\",\"snippet\":\"No\"}]}");

        assertThat(operator.execute(context("mcp_news_web_search"), scope(), null).evidence()).isEmpty();
    }

    @Test void malformedSourceDoesNotHideAnotherValidSource() {
        toolResult("{\"results\":["
            + "{\"url\":\"not a url\",\"title\":\"Bad\",\"snippet\":\"No\"},"
            + "{\"url\":\"https://one.example.com/a\",\"title\":\"First\",\"snippet\":\"Fact A\"}]}");

        assertThat(operator.execute(context("mcp_news_web_search"), scope(), null).evidence()).hasSize(1);
    }

    @Test void researchWorkflowAcceptsOneSourceWithoutImposingCrossSourcePolicy() {
        toolResult("{\"results\":[{\"url\":\"https://one.example.com/a\","
            + "\"title\":\"First\",\"snippet\":\"Fact A\"}]}");
        var context = context("mcp_news_web_search").withIntent(new AnalysisIntent("RESEARCH", List.of(),
            Set.of(AnalysisCapability.EXTERNAL_RESEARCH), "CURRENT", true));

        var outcome = new ExternalResearchWorkflow(new AnalysisOperatorRegistry(List.of(operator))).execute(context);

        assertThat(outcome.verification().accepted()).isTrue();
        assertThat(outcome.evidenceBundle().evidence()).hasSize(1);
        assertThat(outcome.verification().findings()).anyMatch(value -> value.contains("user judgment"));
    }

    private void toolResult(String content) {
        when(tools.execute(any(), any(), any())).thenReturn(new WorkflowExecutionResult(List.of(
            new ToolAnalysisEvidence("tool-1", "mcp_news_web_search", "call-1", content, Map.of())),
            Map.of(), List.of()));
    }

    private AnalysisContext context(String toolName) {
        return new AnalysisContext("Research public sources", new KernelDataScope("tenant", "user",
            "request", null, "run", null, Map.of()), "skill", List.of(), List.of(), List.of(), null,
            Map.of(GovernedExternalResearchOperator.TOOL_NAME, toolName,
                GovernedExternalResearchOperator.SEARCH_TERMS, List.of("public sources")));
    }

    private AnalysisScope scope() { return new AnalysisScope("tenant", "user", List.of(), List.of(), Map.of()); }
}
