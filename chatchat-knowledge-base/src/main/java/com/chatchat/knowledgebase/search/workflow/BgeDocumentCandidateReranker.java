package com.chatchat.knowledgebase.search.workflow;

import com.chatchat.knowledgebase.search.config.SearchProperties;
import com.chatchat.knowledgebase.search.document.DocumentSearchCandidate;
import com.chatchat.knowledgebase.search.document.DocumentSearchPlan;
import com.chatchat.knowledgebase.search.model.SearchMatchedChunk;
import com.chatchat.knowledgebase.search.model.SearchResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Optional BGE-compatible reranker. Network failures degrade to RRF order. */
@Component
@RequiredArgsConstructor
@Slf4j
public class BgeDocumentCandidateReranker implements DocumentCandidateReranker {
    private final SearchProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder().build();

    @Override public int order() { return 100; }

    @Override
    public List<DocumentSearchCandidate> rerank(DocumentSearchPlan plan,
                                                 List<DocumentSearchCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) return List.of();
        SearchProperties.ProblemAnalysis workflow = config();
        int topN = Math.max(1, Math.min(workflow.getFinalEvidenceLimit(), candidates.size()));
        SearchProperties.ProblemAnalysis.BgeReranker bge = workflow.getBgeReranker();
        if (bge == null || !bge.isEnabled() || !hasText(bge.getEndpoint())) {
            return candidates.stream().limit(topN).toList();
        }
        List<String> documents = candidates.stream()
            .map(candidate -> candidateText(candidate.result(), bge.getMaxDocumentChars())).toList();
        Map<String, Object> body = Map.of(
            "model", bge.getModel(), "query", plan.query(), "documents", documents, "top_n", topN);
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder().uri(URI.create(bge.getEndpoint().trim()))
                .timeout(Duration.ofMillis(Math.max(1, bge.getRequestTimeoutMs())))
                .header("Accept", "application/json").header("Content-Type", "application/json");
            if (hasText(bge.getApiKey())) request.header("Authorization", "Bearer " + bge.getApiKey().trim());
            HttpResponse<String> response = httpClient.send(
                request.POST(HttpRequest.BodyPublishers.ofString(json(body))).build(),
                HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("BGE reranker request failed status={}", response.statusCode());
                return candidates.stream().limit(topN).toList();
            }
            List<Rank> ranks = parseRanks(objectMapper.readTree(response.body()), candidates.size());
            if (ranks.isEmpty()) return candidates.stream().limit(topN).toList();
            return ranks.stream().sorted(Comparator.comparingDouble(Rank::score).reversed())
                .limit(topN).map(rank -> candidates.get(rank.index())).toList();
        } catch (IOException ex) {
            log.warn("BGE reranker unavailable endpoint={} error={}", bge.getEndpoint(), ex.getMessage());
            return candidates.stream().limit(topN).toList();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return candidates.stream().limit(topN).toList();
        } catch (RuntimeException ex) {
            log.warn("BGE reranker degraded to RRF order error={}", ex.getMessage());
            return candidates.stream().limit(topN).toList();
        }
    }

    private List<Rank> parseRanks(JsonNode root, int size) {
        JsonNode results = root.path("results");
        if (!results.isArray()) results = root.path("data");
        List<Rank> ranks = new ArrayList<>();
        for (JsonNode result : results) {
            int index = result.path("index").asInt(-1);
            double score = result.has("relevance_score")
                ? result.path("relevance_score").asDouble() : result.path("score").asDouble();
            if (index >= 0 && index < size) ranks.add(new Rank(index, score));
        }
        return ranks;
    }

    private String candidateText(SearchResult result, int maxChars) {
        if (result == null) return "";
        StringBuilder text = new StringBuilder();
        append(text, result.title());
        append(text, result.summary());
        for (SearchMatchedChunk chunk : result.matchedChunks() == null
            ? List.<SearchMatchedChunk>of() : result.matchedChunks()) {
            append(text, chunk.section());
            append(text, hasText(chunk.content()) ? chunk.content() : chunk.text());
        }
        int limit = Math.max(1, maxChars);
        return text.length() <= limit ? text.toString() : text.substring(0, limit);
    }

    private void append(StringBuilder target, String value) {
        if (hasText(value)) target.append(value.trim()).append('\n');
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize BGE rerank request", ex);
        }
    }

    private SearchProperties.ProblemAnalysis config() {
        return properties.getProblemAnalysis() == null
            ? new SearchProperties.ProblemAnalysis() : properties.getProblemAnalysis();
    }

    private boolean hasText(String value) { return value != null && !value.isBlank(); }

    private record Rank(int index, double score) { }
}
