package com.chatchat.knowledgebase.runtime.index;

import com.chatchat.common.knowledge.KnowledgeIR;
import com.chatchat.common.knowledge.KnowledgeIRQuery;
import com.chatchat.common.knowledge.KnowledgeScope;
import com.chatchat.common.knowledge.KnowledgeType;
import com.chatchat.knowledgebase.search.query.SearchTokenizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JpaKnowledgeIRIndexTest {

    @Test
    void enforcesTenantOwnerTypeAndBoundDocumentScope() {
        KnowledgeIRRepository repository = mock(KnowledgeIRRepository.class);
        JpaKnowledgeIRIndex index = new JpaKnowledgeIRIndex(repository, new ObjectMapper(), new SearchTokenizer());
        when(repository.findByDocumentIdInAndActiveTrue(List.of("doc-1", "doc-2"))).thenReturn(List.of(
            entity(1L, "doc-1", "tenant-a", "user-a", "private", "RULE", "集中度风险规则"),
            entity(2L, "doc-2", "tenant-b", "user-a", "tenant", "RULE", "集中度风险规则"),
            entity(3L, "doc-1", "tenant-a", "user-a", "private", "METRIC", "集中度指标")
        ));

        List<KnowledgeIR> result = index.search(new KnowledgeIRQuery(
            new KnowledgeScope("agent", "tenant-a", "user-a", List.of("doc-1", "doc-2"), List.of(), List.of()),
            Set.of(KnowledgeType.RULE), "集中度风险", List.of("判断规则"), 10));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).source().documentId()).isEqualTo("doc-1");
    }

    private KnowledgeIREntity entity(Long id, String documentId, String tenant, String owner,
                                     String visibility, String type, String text) {
        KnowledgeIREntity entity = new KnowledgeIREntity();
        entity.setId(id);
        entity.setKnowledgeId("kir-" + id);
        entity.setDocumentId(documentId);
        entity.setTenantId(tenant);
        entity.setOwnerUserId(owner);
        entity.setVisibility(visibility);
        entity.setKnowledgeType(type);
        entity.setDomain("securities");
        entity.setTitle(text);
        entity.setSemanticDescription(text);
        entity.setCompactRepresentation(text);
        entity.setSearchText(text);
        entity.setRulesJson("[]");
        entity.setConstraintsJson("[]");
        entity.setApplicableIntentsJson("[]");
        entity.setRequiredInputsJson("[]");
        entity.setTagsJson("[]");
        entity.setRelevance(0.7);
        entity.setActive(true);
        return entity;
    }
}
