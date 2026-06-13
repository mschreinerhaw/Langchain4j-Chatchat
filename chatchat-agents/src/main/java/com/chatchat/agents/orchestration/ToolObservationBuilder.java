package com.chatchat.agents.orchestration;

import com.chatchat.common.tool.ToolOutput;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds compact, evidence-aware observations from tool output.
 */
class ToolObservationBuilder {

    private static final int WEB_SEARCH_REFERENCE_LIMIT = 10;
    private static final String DOCUMENT_SEARCH_TOOL = "document_search";
    private static final String WEB_SEARCH_TOOL = "web_search";
    private static final String SEARCH_AND_EXTRACT_TOOL = "search_and_extract";

    private final EvidenceTrustEvaluator evidenceTrustEvaluator;

    ToolObservationBuilder(EvidenceTrustEvaluator evidenceTrustEvaluator) {
        this.evidenceTrustEvaluator = evidenceTrustEvaluator == null ? new EvidenceTrustEvaluator() : evidenceTrustEvaluator;
    }

    String buildSuccessObservation(String toolName, ToolOutput output, String outputText) {
        Object data = output == null ? null : output.getData();
        if (isDocumentSearchToolName(toolName)) {
            return buildDocumentSearchObservation(toolName, output, data, outputText);
        }

        StringBuilder observation = new StringBuilder("Tool ")
            .append(toolName)
            .append(" succeeded.");
        if (!isWebEvidenceToolName(toolName)) {
            String message = output == null ? null : output.getMessage();
            if (message != null && !message.isBlank()) {
                observation.append(" Message: ").append(shortObservationText(message, 400));
            }
            String summary = shortObservationText(outputText, 600);
            if (summary != null && !summary.isBlank()) {
                observation.append(" Output summary: ").append(summary);
            }
            return observation.toString();
        }

        String message = output == null ? null : output.getMessage();
        if (message != null && !message.isBlank()) {
            observation.append(" Message: ").append(shortObservationText(message, 400));
        }
        Map<String, Object> root = asMap(data);
        if (!root.isEmpty()) {
            observation.append("\nWeb search summary: query=")
                .append(firstNonBlank(stringValue(root.get("query")), "unknown"))
                .append(", provider=")
                .append(firstNonBlank(stringValue(root.get("provider")), stringValue(root.get("configuredProvider"))))
                .append(", results=")
                .append(firstNonBlank(stringValue(root.get("count")), "unknown"))
                .append(", referenceUrls=")
                .append(firstNonBlank(stringValue(root.get("reference_url_count")), "unknown"))
                .append(", pageExcerpts=")
                .append(firstNonBlank(stringValue(root.get("page_excerpt_count")), "unknown"))
                .append(", contentMode=")
                .append(firstNonBlank(stringValue(root.get("contentMode")), "unknown"))
                .append('.');
        }
        List<WebCitation> citations = trustedWebCitations(data, observation);
        if (citations.isEmpty()) {
            String summary = shortObservationText(outputText, 600);
            if (summary != null && !summary.isBlank()) {
                observation.append(" Output summary: ").append(summary);
            }
            return observation.toString();
        }
        observation.append("\nWeb citation map. Use these labels in the final answer when relying on web search evidence:\n");
        for (int i = 0; i < citations.size(); i++) {
            WebCitation citation = citations.get(i);
            observation.append("[网页").append(i + 1).append("] ")
                .append(firstNonBlank(citation.title(), citation.url()))
                .append(" - ")
                .append(citation.url());
            if (citation.snippet() != null && !citation.snippet().isBlank()) {
                observation.append(" - ").append(citation.snippet());
            }
            observation.append("\n");
        }
        observation.append("Citation rule: append the matching [网页N] label immediately after any sentence that uses facts from that page.");
        return normalizeWebCitationLabels(observation.toString());
    }

    String buildFailureObservation(String toolName, ToolOutput output) {
        String error = firstNonBlank(output.getErrorMessage(), output.getExceptionType());
        if (error == null || error.isBlank()) {
            error = "unknown error";
        }
        return "Tool " + toolName + " failed. Error: " + error
            + ". Evidence from this tool is unavailable; the final answer must explicitly mention this limitation and must not claim successful verification from this tool.";
    }

    private List<WebCitation> trustedWebCitations(Object data, StringBuilder observation) {
        Map<String, Object> root = asMap(data);
        List<Map<String, Object>> evidenceChunks = new ArrayList<>();
        addCandidateList(evidenceChunks, root.get("evidence_chunks"));
        if (evidenceChunks.isEmpty()) {
            return extractWebCitations(data);
        }

        EvidenceTrustEvaluator.TrustResult trustResult = evidenceTrustEvaluator.evaluate(evidenceChunks);
        Map<String, Object> trust = trustResult.metadata();
        observation.append("\nEvidence trust policy: version=")
            .append(firstNonBlank(stringValue(trust.get("version")), "agent_evidence_trust_policy_v1"))
            .append(", usable=")
            .append(firstNonBlank(stringValue(trust.get("usableCount")), "0"))
            .append(", ignoredLowScore=")
            .append(firstNonBlank(stringValue(trust.get("ignoredLowScoreCount")), "0"))
            .append(", downgradedDomains=")
            .append(firstNonBlank(stringValue(trust.get("downgradedDomainCount")), "0"))
            .append(", contradictionDetected=")
            .append(firstNonBlank(stringValue(trust.get("contradictionDetected")), "false"))
            .append('.');
        if (Boolean.TRUE.equals(trust.get("requestMoreEvidence"))) {
            observation.append(" Trust policy requests more evidence before making a strong claim: ")
                .append(firstNonBlank(stringValue(trust.get("reason")), "insufficient trusted evidence"))
                .append('.');
        }
        if (trustResult.usableEvidence().isEmpty()) {
            return List.of();
        }
        return extractWebCitations(Map.of("evidenceSnippets", trustResult.usableEvidence()));
    }

    private String buildDocumentSearchObservation(String toolName, ToolOutput output, Object data, String outputText) {
        StringBuilder observation = new StringBuilder("Tool ")
            .append(toolName)
            .append(" succeeded.");
        String message = output == null ? null : output.getMessage();
        if (message != null && !message.isBlank()) {
            observation.append(" Message: ").append(shortObservationText(message, 400));
        }

        Map<String, Object> root = asMap(data);
        if (!root.isEmpty()) {
            List<Map<String, Object>> results = new ArrayList<>();
            addCandidateList(results, root.get("results"));
            addCandidateList(results, root.get("items"));
            addCandidateList(results, root.get("records"));
            observation.append("\nDocument search summary: total=")
                .append(firstNonBlank(
                    firstNonBlank(stringValue(root.get("total")), stringValue(root.get("totalCount"))),
                    firstNonBlank(stringValue(root.get("count")), "unknown")
                ))
                .append(", returned=")
                .append(results.size())
                .append(", contentMode=")
                .append(firstNonBlank(stringValue(root.get("contentMode")), "unknown"))
                .append('.');
        }

        List<DocumentEvidence> evidence = extractDocumentEvidence(data);
        if (evidence.isEmpty()) {
            String summary = shortObservationText(outputText, 600);
            if (summary != null && !summary.isBlank()) {
                observation.append(" Output summary: ").append(summary);
            }
            return observation.toString();
        }

        observation.append("\nDocument evidence snippets:\n");
        for (int i = 0; i < evidence.size(); i++) {
            DocumentEvidence item = evidence.get(i);
            observation.append("[文档").append(i + 1).append("] ")
                .append(firstNonBlank(item.title(), "Untitled document"));
            if (item.docId() != null && !item.docId().isBlank()) {
                observation.append(" (docId=").append(item.docId()).append(")");
            }
            if (item.snippet() != null && !item.snippet().isBlank()) {
                observation.append(" - ").append(item.snippet());
            }
            observation.append("\n");
        }
        return observation.toString();
    }

    private List<WebCitation> extractWebCitations(Object data) {
        Map<String, Object> root = asMap(data);
        if (root.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> candidates = new ArrayList<>();
        addCandidateList(candidates, root.get("results"));
        addCandidateList(candidates, root.get("items"));
        addCandidateList(candidates, root.get("organic_results"));
        addCandidateList(candidates, root.get("webPages"));
        addCandidateList(candidates, root.get("pageExcerpts"));
        addCandidateList(candidates, root.get("evidenceSnippets"));

        Map<String, WebCitation> byUrl = new LinkedHashMap<>();
        for (Map<String, Object> item : candidates) {
            String url = firstNonBlank(
                stringValue(item.get("url")),
                firstNonBlank(
                    stringValue(item.get("link")),
                    firstNonBlank(stringValue(item.get("href")), stringValue(item.get("source_url")))
                )
            );
            if (url == null || url.isBlank() || byUrl.containsKey(url)) {
                continue;
            }
            byUrl.put(url, new WebCitation(
                url,
                firstNonBlank(
                    stringValue(item.get("title")),
                    firstNonBlank(stringValue(item.get("name")), stringValue(item.get("source")))
                ),
                shortText(firstNonBlank(
                    stringValue(item.get("snippet")),
                    firstNonBlank(
                        stringValue(item.get("excerpt")),
                        firstNonBlank(
                            stringValue(item.get("pageExcerpt")),
                            firstNonBlank(
                                stringValue(item.get("contentExcerpt")),
                                firstNonBlank(stringValue(item.get("summary")), stringValue(item.get("content")))
                            )
                        )
                    )
                ))
            ));
        }

        Object referenceUrlsValue = root.get("reference_urls");
        if (!(referenceUrlsValue instanceof List<?> referenceUrls) || referenceUrls.isEmpty()) {
            return byUrl.values().stream().limit(WEB_SEARCH_REFERENCE_LIMIT).toList();
        }
        List<WebCitation> citations = new ArrayList<>();
        for (Object value : referenceUrls) {
            if (citations.size() >= WEB_SEARCH_REFERENCE_LIMIT) {
                break;
            }
            String url = stringValue(value);
            if (url == null || url.isBlank()) {
                continue;
            }
            WebCitation matched = byUrl.get(url);
            citations.add(matched == null ? new WebCitation(url, url, null) : matched);
        }
        return citations;
    }

    private List<DocumentEvidence> extractDocumentEvidence(Object data) {
        Map<String, Object> root = asMap(data);
        if (root.isEmpty()) {
            return List.of();
        }

        List<Map<String, Object>> candidates = new ArrayList<>();
        addCandidateList(candidates, root.get("evidenceSnippets"));
        addCandidateList(candidates, root.get("results"));
        addCandidateList(candidates, root.get("items"));
        addCandidateList(candidates, root.get("records"));

        List<DocumentEvidence> evidence = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Map<String, Object> item : candidates) {
            if (evidence.size() >= WEB_SEARCH_REFERENCE_LIMIT) {
                break;
            }
            String docId = firstNonBlank(
                firstNonBlank(stringValue(item.get("docId")), stringValue(item.get("documentId"))),
                firstNonBlank(stringValue(item.get("id")), stringValue(item.get("fileId")))
            );
            String title = firstNonBlank(
                firstNonBlank(stringValue(item.get("title")), stringValue(item.get("name"))),
                firstNonBlank(stringValue(item.get("filename")), stringValue(item.get("source")))
            );
            String snippet = shortText(firstNonBlank(
                stringValue(item.get("excerpt")),
                firstNonBlank(
                    stringValue(item.get("contentExcerpt")),
                    firstNonBlank(stringValue(item.get("snippet")), stringValue(item.get("summary")))
                )
            ));
            if ((title == null || title.isBlank()) && (snippet == null || snippet.isBlank())) {
                continue;
            }
            String key = firstNonBlank(docId, "") + "|" + firstNonBlank(title, "") + "|" + firstNonBlank(snippet, "");
            if (!seen.add(key)) {
                continue;
            }
            evidence.add(new DocumentEvidence(docId, title, snippet));
        }
        return evidence;
    }

    private void addCandidateList(List<Map<String, Object>> candidates, Object value) {
        if (!(value instanceof Collection<?> collection)) {
            return;
        }
        for (Object item : collection) {
            Map<String, Object> map = asMap(item);
            if (!map.isEmpty()) {
                candidates.add(map);
            }
        }
    }

    private boolean isWebEvidenceToolName(String toolName) {
        return isWebSearchToolName(toolName) || isSearchAndExtractToolName(toolName);
    }

    private boolean isWebSearchToolName(String toolName) {
        String semantic = toolSemanticKey(toolName);
        return WEB_SEARCH_TOOL.equals(semantic) || semantic.endsWith("_web_search") || semantic.contains("web_search");
    }

    private boolean isSearchAndExtractToolName(String toolName) {
        String semantic = toolSemanticKey(toolName);
        return SEARCH_AND_EXTRACT_TOOL.equals(semantic) || semantic.endsWith("_search_and_extract") || semantic.contains("search_and_extract");
    }

    private boolean isDocumentSearchToolName(String toolName) {
        String semantic = toolSemanticKey(toolName);
        return DOCUMENT_SEARCH_TOOL.equals(semantic)
            || semantic.endsWith("_document_search")
            || (semantic.contains("document") && semantic.contains("search"));
    }

    private String toolSemanticKey(String toolName) {
        if (toolName == null) {
            return "";
        }
        String normalized = toolName.trim().toLowerCase().replace('-', '_');
        while (normalized.startsWith("mcp_")) {
            normalized = normalized.substring(4);
        }
        String[] prefixes = {
            "chatchat_mcp_server_",
            "chatchat_",
            "xxx_"
        };
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String prefix : prefixes) {
                if (normalized.startsWith(prefix)) {
                    normalized = normalized.substring(prefix.length());
                    changed = true;
                }
            }
        }
        return normalized;
    }

    private Map<String, Object> asMap(Object data) {
        if (data instanceof Map<?, ?> map) {
            Map<String, Object> values = new LinkedHashMap<>();
            map.forEach((key, value) -> {
                if (key != null) {
                    values.put(String.valueOf(key), value);
                }
            });
            return values;
        }
        return Map.of();
    }

    private String shortText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 220 ? normalized : normalized.substring(0, 220);
    }

    private String shortObservationText(String value, int maxChars) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        int limit = Math.max(80, maxChars);
        return normalized.length() <= limit ? normalized : normalized.substring(0, limit);
    }

    private String normalizeWebCitationLabels(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        return value.replace("[缃戦〉", "[网页");
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second == null || second.isBlank() ? null : second;
    }

    private record WebCitation(
        String url,
        String title,
        String snippet
    ) {
    }

    private record DocumentEvidence(
        String docId,
        String title,
        String snippet
    ) {
    }
}
