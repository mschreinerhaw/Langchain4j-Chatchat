package com.chatchat.knowledgebase.runtime.extraction;

import com.chatchat.common.knowledge.KnowledgeExtractionPort;
import com.chatchat.common.knowledge.KnowledgeIR;
import com.chatchat.common.knowledge.KnowledgeIRIndexPort;
import com.chatchat.common.knowledge.KnowledgeIndexDocument;
import com.chatchat.common.knowledge.KnowledgeType;
import com.chatchat.knowledgebase.search.model.SearchDocument;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeDocumentIngestionServiceTest {

    @Test
    void extractsAndAtomicallyReplacesDocumentKnowledge() {
        KnowledgeExtractionPort extractor = mock(KnowledgeExtractionPort.class);
        KnowledgeIRIndexPort index = mock(KnowledgeIRIndexPort.class);
        KnowledgeIR unit = new KnowledgeIR(
            "kir-1", "securities", KnowledgeType.RULE, "规则", "说明",
            List.of(), List.of(), List.of(), List.of(), "精简规则", null, 0.8);
        when(extractor.extract(any())).thenReturn(List.of(unit));
        KnowledgeDocumentIngestionService service = new KnowledgeDocumentIngestionService(extractor, index);
        SearchDocument document = SearchDocument.builder()
            .docId("doc-1").title("风险办法").content("必须结合多个指标判断")
            .tenantId("tenant-a").userId("user-a").visibility("private")
            .permissionRoles(List.of("risk")).tags(List.of("securities")).version(2).build();

        assertThat(service.extractAndIndex(document)).isEqualTo(1);

        ArgumentCaptor<KnowledgeIndexDocument> indexed = ArgumentCaptor.forClass(KnowledgeIndexDocument.class);
        verify(index).replaceDocument(indexed.capture());
        assertThat(indexed.getValue().documentId()).isEqualTo("doc-1");
        assertThat(indexed.getValue().tenantId()).isEqualTo("tenant-a");
        assertThat(indexed.getValue().units()).containsExactly(unit);
    }
}
