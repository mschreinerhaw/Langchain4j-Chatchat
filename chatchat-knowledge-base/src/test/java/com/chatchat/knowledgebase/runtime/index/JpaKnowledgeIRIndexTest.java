package com.chatchat.knowledgebase.runtime.index;

import com.chatchat.common.knowledge.model.KnowledgeIR;
import com.chatchat.common.knowledge.index.KnowledgeIndexDocument;
import com.chatchat.common.knowledge.index.KnowledgeIRQuery;
import com.chatchat.common.knowledge.model.KnowledgeScope;
import com.chatchat.common.knowledge.model.KnowledgeSourceReference;
import com.chatchat.common.knowledge.model.KnowledgeType;
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
    void preservesLongSectionAndTitleWithoutTruncation() {
        KnowledgeIRRepository repository = mock(KnowledgeIRRepository.class);
        JpaKnowledgeIRIndex index = new JpaKnowledgeIRIndex(repository, new ObjectMapper(), new SearchTokenizer());
        String longHeading = "开头" + "段".repeat(650) + "结尾";
        KnowledgeIR unit = new KnowledgeIR("unit-1", "general", KnowledgeType.INTERPRETATION,
            longHeading, "summary", List.of(), List.of(), List.of(), List.of(), "summary",
            new KnowledgeSourceReference("doc-1", "doc-1", "chunk-1", "guide.doc", longHeading,
                "1", "doc://doc-1"), 0.7D);

        index.replaceDocument(new KnowledgeIndexDocument("doc-1", "tenant-1", "user-1", "tenant",
            List.of(), List.of(), "1", List.of(unit)));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<KnowledgeIREntity>> saved = ArgumentCaptor.forClass(Iterable.class);
        verify(repository).saveAll(saved.capture());
        KnowledgeIREntity entity = saved.getValue().iterator().next();
        assertThat(entity.getSourceSection()).isEqualTo(longHeading);
        assertThat(entity.getTitle()).isEqualTo(longHeading);
    }

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

    @Test
    void roleVisibleIrRequiresCanonicalRoleFromKnowledgeScope() {
        KnowledgeIRRepository repository = mock(KnowledgeIRRepository.class);
        JpaKnowledgeIRIndex index = new JpaKnowledgeIRIndex(repository, new ObjectMapper(), new SearchTokenizer());
        KnowledgeIREntity roleDocument = entity(4L, "doc-role", "tenant-a", "other-user",
            "role", "RULE", "advisor policy");
        roleDocument.setPermissionRolesJson("[\"advisor\"]");
        when(repository.findByDocumentIdInAndActiveTrue(List.of("doc-role"))).thenReturn(List.of(roleDocument));

        List<KnowledgeIR> granted = index.search(new KnowledgeIRQuery(
            new KnowledgeScope("agent", "tenant-a", "user-a", List.of("doc-role"), List.of(),
                List.of(), List.of("advisor")), Set.of(KnowledgeType.RULE), "advisor", List.of(), 10));
        List<KnowledgeIR> denied = index.search(new KnowledgeIRQuery(
            new KnowledgeScope("agent", "tenant-a", "user-b", List.of("doc-role"), List.of(),
                List.of(), List.of("viewer")), Set.of(KnowledgeType.RULE), "advisor", List.of(), 10));

        assertThat(granted).hasSize(1);
        assertThat(denied).isEmpty();
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
