package com.chatchat.knowledgebase.runtime.extraction;

import com.chatchat.common.knowledge.KnowledgeExtractionRequest;
import com.chatchat.common.knowledge.KnowledgeType;
import com.chatchat.knowledgebase.search.query.TextChunker;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DeterministicKnowledgeExtractorTest {

    @Test
    void extractsNormalizedMetricKnowledgeWithStableLineage() {
        DeterministicKnowledgeExtractor extractor = new DeterministicKnowledgeExtractor(new TextChunker());
        KnowledgeExtractionRequest request = new KnowledgeExtractionRequest(
            "doc-risk", "securities.customer_risk", "document://doc-risk",
            "# 集中度指标\n单票占比、Top3占比和行业占比是集中度指标。不得仅凭一个指标认定客户风险等级。",
            null, Map.of("title", "客户风险办法", "fileName", "risk.pdf", "version", "3"));

        var units = extractor.extract(request);

        assertThat(units).hasSize(1);
        assertThat(units.get(0).type()).isEqualTo(KnowledgeType.CONSTRAINT);
        assertThat(units.get(0).constraints()).anyMatch(value -> value.contains("不得"));
        assertThat(units.get(0).source().documentId()).isEqualTo("doc-risk");
        assertThat(units.get(0).knowledgeId()).startsWith("kir-");
    }
}
