package com.chatchat.knowledgebase.search;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class EvidenceContextFormatter {

    public static final String CONTRACT_VERSION = "document_evidence_v1";
    public static final int DEFAULT_MAX_EVIDENCE = 8;
    public static final int DEFAULT_MAX_TOTAL_CHARS = 6000;
    public static final int DEFAULT_MAX_CHUNKS_PER_FILE = 3;
    public static final double DEFAULT_MIN_SCORE = 20.0D;

    public DocumentSearchResult toSearchResult(String query, String intent, List<DocumentEvidenceChunk> chunks) {
        List<DocumentEvidenceChunk> selected = selectEvidence(
            chunks,
            DEFAULT_MAX_EVIDENCE,
            DEFAULT_MAX_TOTAL_CHARS,
            DEFAULT_MAX_CHUNKS_PER_FILE,
            DEFAULT_MIN_SCORE
        );
        return new DocumentSearchResult(
            CONTRACT_VERSION,
            query,
            intent,
            selected.size(),
            selected,
            formatContext(selected),
            citations(selected)
        );
    }

    public String formatContext(DocumentSearchResult result) {
        return result == null ? "" : formatContext(result.results());
    }

    public String formatContext(List<DocumentEvidenceChunk> chunks) {
        List<ContextEvidence> entries = mergeConsecutiveEvidence(chunks == null ? List.of() : chunks);
        if (entries.isEmpty()) {
            return "";
        }
        StringBuilder context = new StringBuilder();
        int index = 1;
        for (ContextEvidence entry : entries) {
            if (!hasText(entry.content())) {
                continue;
            }
            if (context.length() > 0) {
                context.append("\n\n");
            }
            context.append("[证据").append(index++).append("]\n");
            appendLine(context, "引用ID", String.join(", ", entry.refIds()));
            appendLine(context, "文件", entry.fileName());
            appendLine(context, "章节", entry.section());
            appendLine(context, "chunk", entry.chunkLabel());
            appendLine(context, "类型", entry.chunkType());
            appendLine(context, "分数", entry.score() == null ? null : String.valueOf(entry.score()));
            context.append("\n内容:\n");
            context.append(entry.content().trim());
        }
        return context.toString();
    }

    public CitationBoundAnswer bindAnswer(String answer, DocumentSearchResult result) {
        return new CitationBoundAnswer(answer == null ? "" : answer, result == null ? List.of() : result.citations());
    }

    public CitationBoundAnswer bindAnswer(String answer, List<DocumentEvidenceChunk> chunks) {
        return new CitationBoundAnswer(answer == null ? "" : answer, citations(chunks));
    }

    public EvidenceAnswer toEvidenceAnswer(String answer,
                                           List<DocumentEvidenceChunk> chunks,
                                           String confidence,
                                           List<String> missingInfo) {
        return new EvidenceAnswer(
            answer == null ? "" : answer,
            answerCitations(chunks),
            hasText(confidence) ? confidence : "medium",
            missingInfo == null ? List.of() : List.copyOf(missingInfo)
        );
    }

    public List<DocumentEvidenceCitation> citations(List<DocumentEvidenceChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        List<DocumentEvidenceCitation> values = new ArrayList<>();
        for (DocumentEvidenceChunk chunk : chunks) {
            if (chunk == null) {
                continue;
            }
            values.add(new DocumentEvidenceCitation(
                refId(chunk),
                chunk.fileId(),
                chunk.chunkId(),
                chunk.fileName(),
                chunk.section(),
                chunk.chunkIndex(),
                citationText(chunk)
            ));
        }
        return List.copyOf(values);
    }

    public List<AnswerCitation> answerCitations(List<DocumentEvidenceChunk> chunks) {
        return citations(chunks).stream()
            .map(citation -> new AnswerCitation(
                citation.refId(),
                citation.fileId(),
                citation.fileName(),
                citation.section(),
                citation.chunkIndex()
            ))
            .toList();
    }

    public List<DocumentEvidenceChunk> selectEvidence(List<DocumentEvidenceChunk> chunks,
                                                      int maxEvidence,
                                                      int maxTotalChars,
                                                      int maxChunksPerFile,
                                                      double minScore) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        int evidenceLimit = Math.max(1, maxEvidence);
        int charLimit = Math.max(500, maxTotalChars);
        int perFileLimit = Math.max(1, maxChunksPerFile);
        Map<String, Integer> perFile = new HashMap<>();
        List<DocumentEvidenceChunk> selected = new ArrayList<>();
        int usedChars = 0;
        for (DocumentEvidenceChunk chunk : chunks) {
            if (chunk == null || !hasText(chunk.content())) {
                continue;
            }
            if (score(chunk) < minScore) {
                continue;
            }
            String fileKey = hasText(chunk.fileId()) ? chunk.fileId() : chunk.fileName();
            int fileCount = perFile.getOrDefault(fileKey, 0);
            if (fileCount >= perFileLimit) {
                continue;
            }
            int remaining = charLimit - usedChars;
            if (remaining <= 0 || selected.size() >= evidenceLimit) {
                break;
            }
            DocumentEvidenceChunk normalized = normalizeRefId(chunk);
            String content = normalized.content();
            if (content.length() > remaining) {
                content = content.substring(0, Math.max(0, remaining)).trim();
                normalized = new DocumentEvidenceChunk(
                    normalized.refId(),
                    normalized.chunkId(),
                    normalized.fileId(),
                    normalized.fileName(),
                    normalized.section(),
                    normalized.chunkIndex(),
                    normalized.chunkType(),
                    normalized.score(),
                    content,
                    normalized.highlights(),
                    normalized.citation(),
                    normalized.trace(),
                    normalized.tenantId(),
                    normalized.userId(),
                    normalized.visibility(),
                    normalized.permissionRoles()
                );
            }
            selected.add(normalized);
            usedChars += normalized.content().length();
            perFile.put(fileKey, fileCount + 1);
        }
        return List.copyOf(selected);
    }

    public String refId(DocumentEvidenceChunk chunk) {
        if (chunk == null) {
            return "";
        }
        if (hasText(chunk.refId())) {
            return chunk.refId().trim();
        }
        String fileId = hasText(chunk.fileId()) ? chunk.fileId().trim() : "unknown";
        String chunkIndex = chunk.chunkIndex() == null ? "summary" : String.valueOf(chunk.chunkIndex());
        return "doc://" + fileId + "#chunk=" + chunkIndex;
    }

    private DocumentEvidenceChunk normalizeRefId(DocumentEvidenceChunk chunk) {
        if (chunk == null || hasText(chunk.refId())) {
            return chunk;
        }
        return new DocumentEvidenceChunk(
            refId(chunk),
            chunk.chunkId(),
            chunk.fileId(),
            chunk.fileName(),
            chunk.section(),
            chunk.chunkIndex(),
            chunk.chunkType(),
            chunk.score(),
            chunk.content(),
            chunk.highlights(),
            chunk.citation(),
            chunk.trace(),
            chunk.tenantId(),
            chunk.userId(),
            chunk.visibility(),
            chunk.permissionRoles()
        );
    }

    private List<ContextEvidence> mergeConsecutiveEvidence(List<DocumentEvidenceChunk> chunks) {
        List<ContextEvidence> entries = new ArrayList<>();
        for (DocumentEvidenceChunk chunk : chunks) {
            if (chunk == null || !hasText(chunk.content())) {
                continue;
            }
            ContextEvidence current = ContextEvidence.from(chunk, refId(chunk));
            if (!entries.isEmpty()) {
                ContextEvidence previous = entries.get(entries.size() - 1);
                if (previous.canMerge(current)) {
                    entries.set(entries.size() - 1, previous.merge(current));
                    continue;
                }
            }
            entries.add(current);
        }
        return entries;
    }

    private String citationText(DocumentEvidenceChunk chunk) {
        if (chunk == null) {
            return "";
        }
        String source = chunk.citation() == null ? chunk.fileName() : chunk.citation().source();
        String locator = chunk.citation() == null ? "" : chunk.citation().locator();
        if (!hasText(locator)) {
            return nullToEmpty(source);
        }
        if (!hasText(source)) {
            return locator;
        }
        return source + " / " + locator;
    }

    private double score(DocumentEvidenceChunk chunk) {
        return chunk == null || chunk.score() == null ? 0.0D : chunk.score();
    }

    private void appendLine(StringBuilder builder, String label, String value) {
        if (hasText(value)) {
            builder.append(label).append(": ").append(value.trim()).append('\n');
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private record ContextEvidence(
        Set<String> refIds,
        String fileId,
        String fileName,
        String section,
        Integer firstChunk,
        Integer lastChunk,
        String chunkType,
        Double score,
        String content
    ) {
        static ContextEvidence from(DocumentEvidenceChunk chunk, String refId) {
            Set<String> refs = new LinkedHashSet<>();
            refs.add(refId);
            return new ContextEvidence(
                refs,
                chunk.fileId(),
                chunk.fileName(),
                chunk.section(),
                chunk.chunkIndex(),
                chunk.chunkIndex(),
                chunk.chunkType(),
                chunk.score(),
                chunk.content()
            );
        }

        boolean canMerge(ContextEvidence other) {
            if (other == null || firstChunk == null || lastChunk == null || other.firstChunk == null) {
                return false;
            }
            return same(fileId, other.fileId)
                && same(section, other.section)
                && lastChunk + 1 == other.firstChunk;
        }

        ContextEvidence merge(ContextEvidence other) {
            Set<String> refs = new LinkedHashSet<>(refIds);
            refs.addAll(other.refIds);
            return new ContextEvidence(
                refs,
                fileId,
                fileName,
                section,
                firstChunk,
                other.lastChunk,
                chunkType,
                score == null ? other.score : (other.score == null ? score : Math.max(score, other.score)),
                content + "\n\n" + other.content
            );
        }

        String chunkLabel() {
            if (firstChunk == null) {
                return "";
            }
            if (lastChunk == null || firstChunk.equals(lastChunk)) {
                return String.valueOf(firstChunk);
            }
            return firstChunk + "-" + lastChunk;
        }

        private boolean same(String left, String right) {
            String safeLeft = left == null ? "" : left;
            String safeRight = right == null ? "" : right;
            return safeLeft.equals(safeRight);
        }
    }
}
