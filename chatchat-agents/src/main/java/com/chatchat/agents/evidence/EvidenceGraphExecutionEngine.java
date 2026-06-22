package com.chatchat.agents.evidence;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public class EvidenceGraphExecutionEngine {

    private static final Pattern TOKEN_SPLIT = Pattern.compile("[^\\p{IsHan}\\p{Alnum}_]+");
    private static final int MAX_GRAPH_PATH_CHUNKS = 5;
    private static final int MAX_SUPPORT_FAN_OUT = 4;
    private static final int MAX_CONFLICT_FAN_OUT = 4;
    private static final double SUPPORT_OVERLAP_THRESHOLD = 0.18;
    private static final double CONFLICT_OVERLAP_THRESHOLD = 0.12;

    private final EvidenceCitationBinder citationBinder;
    private final SqlEvidenceExtractor sqlExtractor;
    private final SqlValidationEngine sqlValidationEngine;

    public EvidenceGraphExecutionEngine() {
        this(new EvidenceCitationBinder(), new SqlEvidenceExtractor(), new SqlValidationEngine());
    }

    EvidenceGraphExecutionEngine(EvidenceCitationBinder citationBinder,
                                 SqlEvidenceExtractor sqlExtractor,
                                 SqlValidationEngine sqlValidationEngine) {
        this.citationBinder = citationBinder == null ? new EvidenceCitationBinder() : citationBinder;
        this.sqlExtractor = sqlExtractor == null ? new SqlEvidenceExtractor() : sqlExtractor;
        this.sqlValidationEngine = sqlValidationEngine == null ? new SqlValidationEngine() : sqlValidationEngine;
    }

    public EvidenceGraph build(List<EvidenceChunk> chunks) {
        return build("query:current", chunks);
    }

    public EvidenceGraph build(String queryId, List<EvidenceChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return new EvidenceGraph(EvidenceGraph.CONTRACT_VERSION, queryId, Map.of(), List.of(), List.of(), List.of());
        }
        Map<String, EvidenceGraphNode> nodes = new LinkedHashMap<>();
        List<EvidenceGraphEdge> edges = new ArrayList<>();
        List<EvidencePath> paths = new ArrayList<>();
        Set<String> sqlLineage = new LinkedHashSet<>();
        List<EvidenceGraphNode> chunkNodes = new ArrayList<>();

        int index = 0;
        for (EvidenceChunk chunk : chunks) {
            if (chunk == null || chunk.content() == null || chunk.content().isBlank()) {
                continue;
            }
            index++;
            String evidenceId = "evidence:" + index;
            String sourceRef = citationBinder.refId(chunk, index);
            String chunkNodeId = evidenceId + ":chunk";
            EvidenceGraphNode chunkNode = chunkNode(chunkNodeId, chunk, sourceRef);
            nodes.put(chunkNode.id(), chunkNode);
            chunkNodes.add(chunkNode);

            SqlEvidence sql = sqlExtractor.extract(chunk.content());
            SqlValidationResult validation = sqlValidationEngine.validate(sql);
            if (validation.parsed()) {
                String fragmentId = evidenceId + ":sql_fragment";
                String normalizedId = evidenceId + ":sql_normalized";
                String trustedId = evidenceId + ":sql_trusted";
                nodes.put(fragmentId, sqlNode(fragmentId, EvidenceGraphNodeType.SQL_FRAGMENT, sourceRef, sql, validation, chunkNode.confidence() * 0.96, true));
                nodes.put(normalizedId, sqlNode(normalizedId, EvidenceGraphNodeType.NORMALIZED_SQL, sourceRef, sql, validation, chunkNode.confidence() * 0.98, validation.semanticValidated()));
                nodes.put(trustedId, sqlNode(trustedId, EvidenceGraphNodeType.TRUSTED_SQL, sourceRef, sql, validation, Math.min(1.0, chunkNode.confidence() + 0.08), validation.trusted()));
                edges.add(new EvidenceGraphEdge(chunkNodeId, fragmentId, EvidenceGraphEdgeType.DERIVED_FROM, 0.96, "SQL fragment extracted from source chunk"));
                edges.add(new EvidenceGraphEdge(fragmentId, normalizedId, EvidenceGraphEdgeType.NORMALIZES_TO, 0.98, "SQL whitespace and formatting normalized"));
                edges.add(new EvidenceGraphEdge(normalizedId, trustedId, EvidenceGraphEdgeType.VALIDATED_AS, validation.trusted() ? 0.94 : 0.35, "SQL validation state=" + validation.states()));
                List<EvidenceGraphEdge> sqlPathEdges = edges.subList(Math.max(0, edges.size() - 3), edges.size());
                if (validation.trusted()) {
                    sqlLineage.addAll(sql.tableNames());
                }
                paths.add(new EvidencePath(
                    List.of(chunkNodeId, fragmentId, normalizedId, trustedId),
                    pathScore(
                        List.of(chunkNode, nodes.get(fragmentId), nodes.get(normalizedId), nodes.get(trustedId)),
                        sqlPathEdges,
                        edges
                    ),
                    validation.trusted() ? sql.tableNames() : List.of(),
                    validation.trusted(),
                    validation.trusted()
                ));
            } else {
                paths.add(new EvidencePath(
                    List.of(chunkNodeId),
                    pathScore(List.of(chunkNode), List.of(), edges),
                    List.of(),
                    false,
                    chunkNode.validated()
                ));
            }
        }
        linkEvidenceChunks(chunkNodes, edges, paths);

        List<EvidencePath> rankedPaths = paths.stream()
            .sorted(Comparator.comparingDouble(EvidencePath::score).reversed())
            .toList();
        return new EvidenceGraph(
            EvidenceGraph.CONTRACT_VERSION,
            queryId,
            nodes,
            List.copyOf(edges),
            rankedPaths,
            List.copyOf(sqlLineage)
        );
    }

    public EvidenceGraph build(String queryId, List<EvidenceChunk> chunks, DocumentSelectionContext selectionContext) {
        return build(queryId, chunks);
    }

    private EvidenceGraphNode chunkNode(String id, EvidenceChunk chunk, String sourceRef) {
        EvidenceGraphNodeType type = switch (chunk.evidenceType()) {
            case WEB -> EvidenceGraphNodeType.WEB_CHUNK;
            case DOCUMENT -> contentType(chunk.content());
        };
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("evidenceType", chunk.evidenceType().name());
        metadata.put("contractVersion", chunk.contractVersion());
        metadata.put("sourceRef", sourceRef);
        metadata.put("sourceGroup", sourceGroup(chunk, sourceRef));
        if (chunk.source() != null) {
            putIfPresent(metadata, "source", chunk.source().name());
            putIfPresent(metadata, "fileId", chunk.source().fileId());
            putIfPresent(metadata, "section", chunk.source().section());
            putIfPresent(metadata, "url", chunk.source().url());
            putIfPresent(metadata, "domain", chunk.source().domain());
        }
        if (chunk.citation() != null) {
            Object grade = chunk.citation().get("evidenceGrade");
            if (grade != null) {
                metadata.put("evidenceGrade", String.valueOf(grade));
            }
        }
        return new EvidenceGraphNode(
            id,
            type,
            sourceRef,
            chunk.content(),
            normalizeText(chunk.content()),
            confidence(chunk),
            true,
            metadata
        );
    }

    private void linkEvidenceChunks(List<EvidenceGraphNode> chunkNodes,
                                    List<EvidenceGraphEdge> edges,
                                    List<EvidencePath> paths) {
        List<EvidenceGraphNode> validatedChunks = chunkNodes.stream()
            .filter(node -> node != null && node.validated())
            .toList();
        if (validatedChunks.size() < 2) {
            return;
        }

        Set<String> edgeKeys = new LinkedHashSet<>();
        for (EvidenceGraphEdge edge : edges) {
            edgeKeys.add(edgeKey(edge.fromNodeId(), edge.toNodeId(), edge.type()));
        }

        Map<String, List<EvidenceGraphNode>> bySource = new LinkedHashMap<>();
        for (EvidenceGraphNode node : validatedChunks) {
            bySource.computeIfAbsent(sourceGroup(node), ignored -> new ArrayList<>()).add(node);
        }

        List<EvidenceGraphNode> bestSourceChain = List.of();
        for (List<EvidenceGraphNode> sourceNodes : bySource.values()) {
            if (sourceNodes.size() < 2) {
                continue;
            }
            EvidenceGraphNode sourcePrimary = sourceNodes.stream()
                .max(Comparator.comparingDouble(EvidenceGraphNode::confidence))
                .orElse(sourceNodes.get(0));
            List<EvidenceGraphNode> ordered = sourceNodes.stream()
                .filter(node -> node.id().equals(sourcePrimary.id()) || !contradicts(sourcePrimary, node))
                .sorted(Comparator.comparing(EvidenceGraphNode::id))
                .limit(MAX_GRAPH_PATH_CHUNKS)
                .toList();
            if (ordered.size() < 2) {
                continue;
            }
            for (int i = 0; i < ordered.size() - 1; i++) {
                addEdgeIfAbsent(
                    edges,
                    edgeKeys,
                    ordered.get(i).id(),
                    ordered.get(i + 1).id(),
                    EvidenceGraphEdgeType.EXPANDS,
                    expandWeight(ordered.get(i), ordered.get(i + 1)),
                    edgeReason("EXPANDS", ordered.get(i), ordered.get(i + 1))
                );
            }
            if (ordered.size() > bestSourceChain.size()) {
                bestSourceChain = ordered;
            }
        }

        EvidenceGraphNode primary = validatedChunks.stream()
            .max(Comparator.comparingDouble(EvidenceGraphNode::confidence))
            .orElse(validatedChunks.get(0));
        List<EvidenceGraphNode> related = validatedChunks.stream()
            .filter(node -> !node.id().equals(primary.id()))
            .filter(node -> !contradicts(primary, node))
            .filter(node -> sameSource(primary, node) || textOverlap(primary.normalizedContent(), node.normalizedContent()) >= SUPPORT_OVERLAP_THRESHOLD)
            .sorted(Comparator.comparingDouble(EvidenceGraphNode::confidence).reversed()
                .thenComparing(EvidenceGraphNode::id))
            .limit(Math.min(MAX_SUPPORT_FAN_OUT, Math.max(0, MAX_GRAPH_PATH_CHUNKS - 1)))
            .toList();
        for (EvidenceGraphNode node : related) {
            addEdgeIfAbsent(
                edges,
                edgeKeys,
                primary.id(),
                node.id(),
                EvidenceGraphEdgeType.SUPPORTS,
                supportWeight(primary, node),
                edgeReason("SUPPORTS", primary, node)
            );
        }
        linkContradictions(validatedChunks, edges, edgeKeys);

        List<EvidenceGraphNode> executionChain = bestSourceChain.size() >= 2
            ? bestSourceChain
            : linkedChain(primary, related, edges, edgeKeys);
        if (executionChain.size() < 2) {
            return;
        }
        Set<String> linkedChunkNodeIds = new LinkedHashSet<>(
            validatedChunks.stream().map(EvidenceGraphNode::id).toList()
        );
        paths.removeIf(path -> path.nodeIds().size() == 1 && linkedChunkNodeIds.contains(path.nodeIds().get(0)));
        List<EvidenceGraphEdge> selectedPathEdges = pathEdges(executionChain, edges);
        paths.add(new EvidencePath(
            executionChain.stream().map(EvidenceGraphNode::id).toList(),
            pathScore(executionChain, selectedPathEdges, edges),
            List.of(),
            false,
            executionChain.stream().allMatch(EvidenceGraphNode::validated)
        ));
    }

    private List<EvidenceGraphNode> linkedChain(EvidenceGraphNode primary,
                                                List<EvidenceGraphNode> related,
                                                List<EvidenceGraphEdge> edges,
                                                Set<String> edgeKeys) {
        if (primary == null || related == null || related.isEmpty()) {
            return List.of();
        }
        List<EvidenceGraphNode> chain = new ArrayList<>();
        chain.add(primary);
        chain.addAll(related);
        for (int i = 0; i < chain.size() - 1; i++) {
            addEdgeIfAbsent(
                edges,
                edgeKeys,
                chain.get(i).id(),
                chain.get(i + 1).id(),
                EvidenceGraphEdgeType.SUPPORTS,
                supportWeight(chain.get(i), chain.get(i + 1)),
                edgeReason("SUPPORTS", chain.get(i), chain.get(i + 1))
            );
        }
        return chain;
    }

    private void linkContradictions(List<EvidenceGraphNode> nodes,
                                    List<EvidenceGraphEdge> edges,
                                    Set<String> edgeKeys) {
        int added = 0;
        for (int i = 0; i < nodes.size() && added < MAX_CONFLICT_FAN_OUT; i++) {
            for (int j = i + 1; j < nodes.size() && added < MAX_CONFLICT_FAN_OUT; j++) {
                EvidenceGraphNode left = nodes.get(i);
                EvidenceGraphNode right = nodes.get(j);
                if (!contradicts(left, right)) {
                    continue;
                }
                EvidenceGraphNode from = left.confidence() >= right.confidence() ? left : right;
                EvidenceGraphNode to = from == left ? right : left;
                addEdgeIfAbsent(
                    edges,
                    edgeKeys,
                    from.id(),
                    to.id(),
                    EvidenceGraphEdgeType.CONTRADICTS,
                    contradictionWeight(left, right),
                    "Conflict detected by polarity mismatch; lower-ranked evidence is excluded from best path"
                );
                added++;
            }
        }
    }

    private void addEdgeIfAbsent(List<EvidenceGraphEdge> edges,
                                 Set<String> edgeKeys,
                                 String from,
                                 String to,
                                 EvidenceGraphEdgeType type,
                                 double weight,
                                 String reasoning) {
        if (from == null || from.isBlank() || to == null || to.isBlank() || from.equals(to) || type == null) {
            return;
        }
        String key = edgeKey(from, to, type);
        if (!edgeKeys.add(key)) {
            return;
        }
        edges.add(new EvidenceGraphEdge(from, to, type, round(clamp(weight)), reasoning));
    }

    private List<EvidenceGraphEdge> pathEdges(List<EvidenceGraphNode> pathNodes, List<EvidenceGraphEdge> edges) {
        if (pathNodes == null || pathNodes.size() < 2 || edges == null || edges.isEmpty()) {
            return List.of();
        }
        Set<String> pathPairs = new LinkedHashSet<>();
        for (int i = 0; i < pathNodes.size() - 1; i++) {
            pathPairs.add(pathNodes.get(i).id() + "->" + pathNodes.get(i + 1).id());
        }
        return edges.stream()
            .filter(edge -> pathPairs.contains(edge.fromNodeId() + "->" + edge.toNodeId()))
            .filter(edge -> edge.type() != EvidenceGraphEdgeType.CONTRADICTS)
            .toList();
    }

    private double supportWeight(EvidenceGraphNode from, EvidenceGraphNode to) {
        double overlap = textOverlap(from.normalizedContent(), to.normalizedContent());
        double confidence = (from.confidence() + to.confidence()) / 2.0;
        double sourceBoost = sameSource(from, to) ? 0.18 : 0.0;
        return round(clamp(0.46 + sourceBoost + confidence * 0.22 + overlap * 0.28));
    }

    private double expandWeight(EvidenceGraphNode from, EvidenceGraphNode to) {
        double confidence = (from.confidence() + to.confidence()) / 2.0;
        return round(clamp(0.5 + confidence * 0.22));
    }

    private double contradictionWeight(EvidenceGraphNode left, EvidenceGraphNode right) {
        double overlap = textOverlap(left.normalizedContent(), right.normalizedContent());
        double confidence = (left.confidence() + right.confidence()) / 2.0;
        return round(clamp(0.42 + confidence * 0.24 + overlap * 0.24));
    }

    private String edgeReason(String type, EvidenceGraphNode from, EvidenceGraphNode to) {
        String direction = sameSource(from, to) ? "source_sequence" : "semantic_overlap";
        return type + " direction=" + direction
            + " overlap=" + round(textOverlap(from.normalizedContent(), to.normalizedContent()))
            + " confidence=" + round((from.confidence() + to.confidence()) / 2.0);
    }

    private String edgeKey(String from, String to, EvidenceGraphEdgeType type) {
        return from + "->" + to + ":" + type;
    }

    private boolean sameSource(EvidenceGraphNode left, EvidenceGraphNode right) {
        String leftSource = sourceGroup(left);
        String rightSource = sourceGroup(right);
        return !leftSource.isBlank() && leftSource.equals(rightSource);
    }

    private boolean contradicts(EvidenceGraphNode left, EvidenceGraphNode right) {
        if (left == null || right == null) {
            return false;
        }
        Polarity leftPolarity = polarity(left.normalizedContent());
        Polarity rightPolarity = polarity(right.normalizedContent());
        return leftPolarity != Polarity.NEUTRAL
            && rightPolarity != Polarity.NEUTRAL
            && leftPolarity != rightPolarity
            && textOverlap(left.normalizedContent(), right.normalizedContent()) >= CONFLICT_OVERLAP_THRESHOLD;
    }

    private Polarity polarity(String value) {
        String text = normalizeText(value).toLowerCase();
        if (text.isBlank()) {
            return Polarity.NEUTRAL;
        }
        boolean negative = containsAny(text,
            "不支持", "不允许", "不能", "不可", "禁止", "无需", "没有影响", "无影响", "不产生影响",
            "unsupported", "not supported", "forbidden", "cannot", "no impact", "does not affect"
        );
        boolean positive = containsAny(text,
            "支持", "允许", "可以", "需要", "会影响", "产生影响", "存在影响",
            "supported", "allowed", "can ", "requires", "impacts", "affects"
        );
        if (negative) {
            return Polarity.NEGATIVE;
        }
        if (positive) {
            return Polarity.POSITIVE;
        }
        return Polarity.NEUTRAL;
    }

    private boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private String sourceGroup(EvidenceGraphNode node) {
        Object value = node == null || node.metadata() == null ? null : node.metadata().get("sourceGroup");
        return value == null ? "" : String.valueOf(value);
    }

    private String sourceGroup(EvidenceChunk chunk, String sourceRef) {
        if (chunk != null && chunk.source() != null) {
            if (chunk.source().fileId() != null && !chunk.source().fileId().isBlank()) {
                return chunk.source().fileId();
            }
            if (chunk.source().name() != null && !chunk.source().name().isBlank()) {
                return chunk.source().name();
            }
        }
        String ref = sourceRef == null ? "" : sourceRef;
        int chunkIndex = ref.indexOf("#chunk");
        return chunkIndex > 0 ? ref.substring(0, chunkIndex) : ref;
    }

    private double textOverlap(String left, String right) {
        Set<String> leftTokens = tokens(left);
        Set<String> rightTokens = tokens(right);
        if (leftTokens.isEmpty() || rightTokens.isEmpty()) {
            return 0.0;
        }
        Set<String> intersection = new LinkedHashSet<>(leftTokens);
        intersection.retainAll(rightTokens);
        Set<String> union = new LinkedHashSet<>(leftTokens);
        union.addAll(rightTokens);
        return union.isEmpty() ? 0.0 : (double) intersection.size() / union.size();
    }

    private Set<String> tokens(String value) {
        String text = normalizeText(value).toLowerCase();
        if (text.isBlank()) {
            return Set.of();
        }
        Set<String> tokens = new LinkedHashSet<>();
        for (String token : TOKEN_SPLIT.split(text)) {
            if (token.length() >= 2) {
                tokens.add(token);
            }
        }
        if (tokens.isEmpty() && text.length() >= 4) {
            for (int i = 0; i < text.length() - 1; i++) {
                tokens.add(text.substring(i, i + 2));
            }
        }
        return tokens;
    }

    private EvidenceGraphNode sqlNode(String id,
                                      EvidenceGraphNodeType type,
                                      String sourceRef,
                                      SqlEvidence sql,
                                      SqlValidationResult validation,
                                      double confidence,
                                      boolean validated) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("sqlType", sql.sqlType().name());
        metadata.put("isComplete", sql.complete());
        metadata.put("sqlStates", validation.states().stream().map(SqlState::name).toList());
        metadata.put("executionVerified", validation.executionVerified());
        metadata.put("validationScore", validation.validationScore());
        metadata.put("validationIssues", validation.issues());
        metadata.put("tables", sql.tableNames());
        metadata.put("columns", sql.columns());
        metadata.put("sqlLineage", validation.trusted() ? sql.tableNames() : List.of());
        return new EvidenceGraphNode(
            id,
            type,
            sourceRef,
            sql.rawSql(),
            sql.normalizedSql(),
            clamp(confidence * Math.max(0.1, validation.validationScore())),
            validated,
            metadata
        );
    }

    private EvidenceGraphNodeType contentType(String content) {
        String normalized = content == null ? "" : content;
        if (normalized.contains("|") && normalized.contains("---")) {
            return EvidenceGraphNodeType.TABLE_FACT;
        }
        return EvidenceGraphNodeType.DOC_CHUNK;
    }

    private double pathScore(List<EvidenceGraphNode> nodes,
                             List<EvidenceGraphEdge> pathEdges,
                             List<EvidenceGraphEdge> graphEdges) {
        if (nodes == null || nodes.isEmpty()) {
            return 0.0;
        }
        double nodeScore = 0.0;
        for (EvidenceGraphNode node : nodes) {
            nodeScore += nodeScore(node);
        }
        double edgeScore = edgeScore(pathEdges);
        double conflictPenalty = conflictPenalty(nodes, graphEdges);
        double lengthPenalty = 1.0 + Math.max(0, nodes.size() - 1) * 0.03;
        double diversityBonus = Math.min(0.12, Math.max(0, nodes.size() - 1) * 0.04);
        double blended = (nodeScore / nodes.size()) * 0.68 + edgeScore * 0.32;
        return round(clamp(blended / lengthPenalty + diversityBonus - conflictPenalty));
    }

    private double edgeScore(List<EvidenceGraphEdge> edges) {
        if (edges == null || edges.isEmpty()) {
            return 0.0;
        }
        double score = 0.0;
        for (EvidenceGraphEdge edge : edges) {
            double typeWeight = switch (edge.type()) {
                case VALIDATED_AS -> 1.0;
                case NORMALIZES_TO -> 0.94;
                case DERIVED_FROM -> 0.9;
                case SUPPORTS -> 0.82;
                case EXPANDS -> 0.68;
                case CONTRADICTS -> -0.7;
            };
            score += edge.weight() * typeWeight;
        }
        return clamp(score / edges.size());
    }

    private double conflictPenalty(List<EvidenceGraphNode> nodes, List<EvidenceGraphEdge> edges) {
        if (nodes == null || nodes.isEmpty() || edges == null || edges.isEmpty()) {
            return 0.0;
        }
        Set<String> pathNodeIds = new LinkedHashSet<>(nodes.stream().map(EvidenceGraphNode::id).toList());
        double penalty = 0.0;
        for (EvidenceGraphEdge edge : edges) {
            if (edge.type() == EvidenceGraphEdgeType.CONTRADICTS
                && (pathNodeIds.contains(edge.fromNodeId()) || pathNodeIds.contains(edge.toNodeId()))) {
                penalty += edge.weight() * 0.18;
            }
        }
        return Math.min(0.22, penalty);
    }

    private double nodeScore(EvidenceGraphNode node) {
        if (node == null) {
            return 0.0;
        }
        double typeWeight = switch (node.type()) {
            case TRUSTED_SQL -> 1.0;
            case NORMALIZED_SQL -> 0.92;
            case SQL_FRAGMENT -> 0.86;
            case TABLE_FACT -> 0.78;
            case TEXT_FACT -> 0.72;
            case DOC_CHUNK, WEB_CHUNK -> 0.68;
        };
        return node.confidence() * typeWeight;
    }

    private double confidence(EvidenceChunk chunk) {
        double value = chunk.score() == null ? 0.82 : chunk.score();
        if (value > 1.0) {
            value = value / 100.0;
        }
        Object grade = chunk.citation() == null ? null : chunk.citation().get("evidenceGrade");
        if (grade != null && "A".equalsIgnoreCase(String.valueOf(grade))) {
            value += 0.08;
        } else if (grade != null && "B".equalsIgnoreCase(String.valueOf(grade))) {
            value += 0.03;
        }
        EvidenceGovernance governance = chunk.governance();
        if (governance != null && "BLOCKED".equalsIgnoreCase(governance.policyStatus())) {
            value = 0.0;
        }
        return clamp(value);
    }

    private String normalizeText(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    private void putIfPresent(Map<String, Object> metadata, String key, String value) {
        if (value != null && !value.isBlank()) {
            metadata.put(key, value);
        }
    }

    private enum Polarity {
        POSITIVE,
        NEGATIVE,
        NEUTRAL
    }
}
