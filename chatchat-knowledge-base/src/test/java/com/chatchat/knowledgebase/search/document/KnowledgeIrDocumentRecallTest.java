package com.chatchat.knowledgebase.search.document;

import com.chatchat.knowledgebase.runtime.index.KnowledgeIREntity;
import com.chatchat.knowledgebase.runtime.index.KnowledgeIRRepository;
import com.chatchat.knowledgebase.search.query.SearchTokenizer;
import com.chatchat.knowledgebase.search.security.DocumentVisibilityContext;
import com.chatchat.knowledgebase.search.security.SearchPermissionContext;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgeIrDocumentRecallTest {
    @Test
    void chapterAndParagraphFactsRankTheNamedDocumentAboveGenericInstructions() {
        KnowledgeIRRepository repository = mock(KnowledgeIRRepository.class);
        KnowledgeIREntity named = unit("livedata-doc", "livedata 安装", "部署与验证", "livedata 安装说明");
        KnowledgeIREntity generic = unit("linux-doc", "Linux 日常命令", "FreeIPA 安装", "installation guide");
        List<KnowledgeIREntity> units = List.of(named, generic);
        when(repository.findMatchingUnits(eq("tenant-1"), anyString(), any(Pageable.class)))
            .thenAnswer(invocation -> {
                String term = invocation.getArgument(1, String.class).replace("%", "").toLowerCase(Locale.ROOT);
                return units.stream().filter(unit -> (unit.getTitle() + " " + unit.getSourceSection()
                    + " " + unit.getSearchText()).toLowerCase(Locale.ROOT).contains(term)).toList();
            });
        KnowledgeIrDocumentRecall recall = new KnowledgeIrDocumentRecall(repository, new SearchTokenizer());

        assertThat(recall.recall(plan(List.of()), 8).focusedQuery()).isEqualTo("livedata");
        assertThat(recall.recall(plan(List.of()), 8).documentIds()).containsExactly("livedata-doc");
        assertThat(recall.recall(plan(List.of("livedata-doc")), 8).documentIds())
            .containsExactly("livedata-doc");
    }

    @Test
    void doesNotTreatGenericInstallationFactsAsNamedDocumentExistence() {
        KnowledgeIRRepository repository = mock(KnowledgeIRRepository.class);
        KnowledgeIREntity generic = unit("linux-doc", "Linux commands", "FreeIPA installation", "installation guide");
        when(repository.findMatchingUnits(eq("tenant-1"), anyString(), any(Pageable.class)))
            .thenAnswer(invocation -> {
                String pattern = invocation.getArgument(1, String.class).toLowerCase(Locale.ROOT);
                return pattern.contains("livedata") ? List.of() : List.of(generic);
            });
        KnowledgeIrDocumentRecall recall = new KnowledgeIrDocumentRecall(repository, new SearchTokenizer());

        KnowledgeIrDocumentRecall.Recall result = recall.recall(plan(List.of()), 8);

        assertThat(result.focusedQuery()).isEqualTo("livedata");
        assertThat(result.documentIds()).isEmpty();
    }

    @Test
    void findsDocumentByDatabaseChapterHeadingBeforeParagraphMatches() {
        KnowledgeIRRepository repository = mock(KnowledgeIRRepository.class);
        KnowledgeIREntity named = unit("livedata-doc", "Operations", "LiveData setup", "prerequisites");
        when(repository.findMatchingHeadings(eq("tenant-1"), eq("%livedata%"), any(Pageable.class)))
            .thenReturn(List.of(named));
        KnowledgeIrDocumentRecall recall = new KnowledgeIrDocumentRecall(repository, new SearchTokenizer());

        assertThat(recall.recall(plan(List.of()), 8).documentIds()).containsExactly("livedata-doc");
    }

    @Test
    void excludesPrivateIrUnitsBeforeSearch() {
        KnowledgeIRRepository repository = mock(KnowledgeIRRepository.class);
        KnowledgeIREntity privateUnit = unit("private-doc", "livedata", "setup", "livedata");
        privateUnit.setVisibility("private");
        privateUnit.setOwnerUserId("another-user");
        when(repository.findMatchingUnits(eq("tenant-1"), anyString(), any(Pageable.class)))
            .thenReturn(List.of(privateUnit));
        KnowledgeIrDocumentRecall recall = new KnowledgeIrDocumentRecall(repository, new SearchTokenizer());

        assertThat(recall.recall(plan(List.of()), 8).documentIds()).isEmpty();
    }

    @Test
    void appliesIrAclBeforeReturningCandidateIds() {
        KnowledgeIRRepository repository = mock(KnowledgeIRRepository.class);
        KnowledgeIREntity privateUnit = unit("private-doc", "livedata", "setup", "livedata");
        privateUnit.setVisibility("private");
        privateUnit.setOwnerUserId("another-user");
        when(repository.findMatchingUnits(eq("tenant-1"), anyString(), any(Pageable.class)))
            .thenReturn(List.of(privateUnit));
        KnowledgeIrDocumentRecall recall = new KnowledgeIrDocumentRecall(repository, new SearchTokenizer());

        assertThat(recall.recall(plan(List.of()), 8).documentIds()).isEmpty();
    }

    private DocumentSearchPlan plan(List<String> scope) {
        return new DocumentSearchPlan(
            "livedata 安装说明 installation guide", 8, null, scope, scope, scope,
            String.join(",", scope), "how_to", List.of(), false,
            SearchPermissionContext.of("tenant-1", "user-1", List.of()),
            DocumentVisibilityContext.unrestricted(), null);
    }

    private KnowledgeIREntity unit(String documentId, String title, String section, String searchText) {
        KnowledgeIREntity unit = new KnowledgeIREntity();
        unit.setKnowledgeId(documentId + "-section");
        unit.setDocumentId(documentId);
        unit.setTitle(title);
        unit.setSourceSection(section);
        unit.setSearchText(searchText);
        return unit;
    }
}
