package com.chatchat.knowledgebase.search;

import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.parser.ParseContext;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentTextExtractorTest {

    private final DocumentTextExtractor extractor = new DocumentTextExtractor(new SearchProperties());

    @Test
    void skipsEmbeddedOleDocumentsForExcelWorkbooks() {
        ParseContext context = extractor.parseContext(false, "xlsx");

        EmbeddedDocumentExtractor embedded = context.get(EmbeddedDocumentExtractor.class);

        assertThat(embedded).isNotNull();
        assertThat(embedded.shouldParseEmbedded(null)).isFalse();
    }

    @Test
    void keepsDefaultEmbeddedDocumentHandlingForOtherDocuments() {
        ParseContext context = extractor.parseContext(false, "docx");

        assertThat(context.get(EmbeddedDocumentExtractor.class)).isNull();
    }
}
