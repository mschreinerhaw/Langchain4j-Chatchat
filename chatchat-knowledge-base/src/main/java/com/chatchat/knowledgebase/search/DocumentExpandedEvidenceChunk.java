package com.chatchat.knowledgebase.search;

import java.util.List;

public record DocumentExpandedEvidenceChunk(
    String refId,
    String chunkId,
    String fileId,
    String fileName,
    String section,
    Integer chunkIndex,
    String chunkType,
    Double score,
    String text,
    List<String> highlights,
    Citation citation,
    boolean isEvidenceReady,
    DocumentEvidenceGrade evidenceGrade
) {
    public DocumentExpandedEvidenceChunk {
        highlights = highlights == null ? List.of() : List.copyOf(highlights);
        evidenceGrade = evidenceGrade == null ? DocumentEvidenceGrade.C : evidenceGrade;
    }

    public static DocumentExpandedEvidenceChunk from(DocumentEvidenceChunk chunk) {
        return new DocumentExpandedEvidenceChunk(
            chunk.refId(),
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
            true,
            grade(chunk.score())
        );
    }

    private static DocumentEvidenceGrade grade(Double score) {
        double value = score == null ? 0.0D : score;
        if (value >= 80.0D) {
            return DocumentEvidenceGrade.A;
        }
        if (value >= 50.0D) {
            return DocumentEvidenceGrade.B;
        }
        return DocumentEvidenceGrade.C;
    }
}
