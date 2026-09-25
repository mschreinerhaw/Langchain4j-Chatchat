package com.chatchat.api.runtime;

import com.chatchat.common.runtime.analysis.evidence.ExternalResearchEvidence;
import com.chatchat.common.runtime.analysis.execution.WorkflowExecutionResult;
import com.chatchat.common.runtime.analysis.model.AnalysisCapability;
import com.chatchat.common.runtime.analysis.model.AnalysisContext;
import com.chatchat.common.runtime.analysis.model.AnalysisScope;
import com.chatchat.common.runtime.analysis.plan.WorkflowPlan;
import com.chatchat.common.runtime.analysis.spi.AnalysisCapabilityOperator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Converts governed, Skill-bound web search results into attributable research evidence. */
@Component
public class GovernedExternalResearchOperator implements AnalysisCapabilityOperator {
    public static final String TOOL_NAME = "runtime.analysis.researchToolName";
    public static final String SEARCH_TERMS = "runtime.analysis.researchTerms";
    private final RegisteredToolAnalysisOperator tools;
    private final ObjectMapper mapper;

    public GovernedExternalResearchOperator(RegisteredToolAnalysisOperator tools, ObjectMapper mapper) {
        this.tools = tools;
        this.mapper = mapper;
    }

    @Override public AnalysisCapability capability() { return AnalysisCapability.EXTERNAL_RESEARCH; }
    @Override public boolean available(AnalysisContext context) {
        return context != null && context.attributes().get(TOOL_NAME) instanceof String name
            && ("web_search".equals(name) || name.matches("mcp_[a-zA-Z0-9_]+_web_search"));
    }

    @Override
    public WorkflowExecutionResult execute(AnalysisContext context, AnalysisScope scope, WorkflowPlan plan) {
        if (!available(context) || context.query().isBlank() || context.query().length() > 4000)
            return failed("A governed web_search tool and bounded research query are required");
        Object supplied = context.attributes().get(SEARCH_TERMS);
        if (!(supplied instanceof List<?> terms) || terms.isEmpty() || terms.size() > 8
            || terms.stream().anyMatch(term -> !(term instanceof String text)
                || text.isBlank() || text.length() > 80 || text.contains("\n") || text.contains("\r")
                || text.equalsIgnoreCase(context.query().trim())))
            return failed("Explicit bounded research terms are required for external search");
        String toolName = (String) context.attributes().get(TOOL_NAME);
        AnalysisContext toolContext = context.withAttribute(RegisteredToolAnalysisOperator.TOOL_NAME, toolName)
            .withAttribute(RegisteredToolAnalysisOperator.TOOL_ARGUMENTS,
                Map.of("query", context.query(), "queryTerms", terms, "num_results", 5));
        WorkflowExecutionResult result = tools.execute(toolContext, scope, plan);
        if (result.evidence().size() != 1) return result;
        try {
            JsonNode root = mapper.readTree(result.evidence().get(0).content());
            JsonNode data = root.has("data") ? root.path("data") : root;
            JsonNode entries = data.path("results");
            if (!entries.isArray() || entries.isEmpty() || entries.size() > 5)
                return failed("Web search returned no bounded source results");
            List<ExternalResearchEvidence> evidence = new ArrayList<>();
            Set<String> hosts = new LinkedHashSet<>();
            Set<String> urls = new LinkedHashSet<>();
            for (JsonNode entry : entries) {
                String url = value(entry, "url", 2048);
                String title = value(entry, "title", 300);
                String snippet = value(entry, "snippet", 2000);
                if (snippet == null) snippet = value(entry, "summary", 2000);
                if (url == null || title == null || snippet == null) continue;
                URI uri;
                try { uri = URI.create(url); }
                catch (IllegalArgumentException malformed) { continue; }
                String host = uri.getHost();
                if (host == null || uri.getUserInfo() != null || uri.getPort() != -1
                    || !("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    || !publicHost(host) || !urls.add(uri.normalize().toString())) continue;
                hosts.add(host.toLowerCase(Locale.ROOT));
                String publisher = value(entry, "sourceName", 200);
                if (publisher == null) publisher = host;
                String publishedAt = value(entry, "publishTime", 100);
                Map<String, Object> projection = Map.of("url", url, "title", title,
                    "publisher", publisher, "publishedAt", publishedAt == null ? "" : publishedAt,
                    "snippet", snippet);
                evidence.add(new ExternalResearchEvidence(UUID.randomUUID().toString(), url, publisher,
                    publishedAt == null ? "" : publishedAt, title + "\n" + snippet,
                    Map.of("tenantId", scope.tenantId(), "sourceTool", toolName,
                        "remoteProjection", projection)));
            }
            if (evidence.isEmpty()) return failed("Web search returned no attributable public source");
            return new WorkflowExecutionResult(List.copyOf(evidence),
                Map.of("sourceCount", evidence.size(), "distinctHostCount", hosts.size()),
                List.of("Research sources: " + evidence.size() + "; distinct hosts: " + hosts.size()
                    + ". Source sufficiency and freshness require user judgment."));
        } catch (Exception invalid) {
            return failed("Web search results cannot be verified as attributed sources");
        }
    }

    private boolean publicHost(String host) {
        String value = host.toLowerCase(Locale.ROOT);
        return value.contains(".") && !value.equals("localhost") && !value.endsWith(".localhost")
            && !value.endsWith(".local") && !value.endsWith(".internal")
            && !value.matches("(?:127|10|0)\\..*") && !value.matches("192\\.168\\..*")
            && !value.matches("172\\.(?:1[6-9]|2[0-9]|3[01])\\..*")
            && !value.matches("169\\.254\\..*") && !value.contains(":");
    }

    private String value(JsonNode node, String field, int limit) {
        JsonNode item = node.path(field);
        if (!item.isTextual()) return null;
        String text = item.asText().trim();
        return text.isEmpty() || text.length() > limit ? null : text;
    }

    private WorkflowExecutionResult failed(String reason) {
        return new WorkflowExecutionResult(List.of(), Map.of(), List.of(reason));
    }
}
