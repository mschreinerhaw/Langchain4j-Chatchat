package com.chatchat.agents.orchestration.answer;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class GroundedDocumentEvidenceReaderTest {
    @Test
    void runtimeDiagnosticsAndCanonicalMirrorsAreNotSourceDocumentContent() {
        String observation = """
            Unified evidence context (contractVersion=evidence_v1):
            [Evidence 1]
            type: DOCUMENT
            citation: doc://core#chunk=1
            source: Core guide
            content:
            Core deployment requires a database.
            [Evidence 2]
            type: DOCUMENT
            citation: doc://optional#chunk=2
            source: Optional guide
            content:
            Optional capabilities require additional services.
            Canonical evidence store (contractVersion=evidence_canonical_v1):
            rawContent: duplicated source text
            Evidence graph execution (contractVersion=graph):
            runtime diagnostic text
            Document evidence coverage: partial selected path
            """;
        var evidence = new GroundedDocumentEvidenceReader().extract(List.of(observation));
        assertThat(evidence).hasSize(2);
        assertThat(evidence.get(1).content()).isEqualTo("Optional capabilities require additional services.");
    }
}
