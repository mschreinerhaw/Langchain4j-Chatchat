package com.chatchat.api.runtime;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnalysisEvidenceArchiveMaintenanceTest {
    @Test void repairsPendingIndexAndDeletesOnlyAfterIndexRemoval() {
        var repository = mock(AnalysisEvidenceArchiveRepository.class);
        var index = mock(AnalysisEvidenceSearchIndex.class);
        var pending = entity("pending", System.currentTimeMillis());
        var expired = entity("expired", 1);
        when(index.enabled()).thenReturn(true);
        when(repository.pendingIndex(any(Pageable.class))).thenReturn(List.of(pending));
        when(repository.expiredBefore(anyLong(), any(Pageable.class))).thenReturn(List.of(expired));

        new AnalysisEvidenceArchiveMaintenance(repository, index, 1).maintain();

        assertThat(pending.indexStatus).isEqualTo("INDEXED");
        verify(repository).saveAndFlush(pending);
        verify(index).remove("expired");
        verify(repository).delete(expired);
    }

    @Test void indexFailureKeepsAuthoritativeArchive() {
        var repository = mock(AnalysisEvidenceArchiveRepository.class);
        var index = mock(AnalysisEvidenceSearchIndex.class);
        var expired = entity("expired", 1);
        when(index.enabled()).thenReturn(true);
        when(repository.pendingIndex(any(Pageable.class))).thenReturn(List.of());
        when(repository.expiredBefore(anyLong(), any(Pageable.class))).thenReturn(List.of(expired));
        doThrow(new IllegalStateException("unavailable")).when(index).remove("expired");

        new AnalysisEvidenceArchiveMaintenance(repository, index, 1).maintain();

        verify(repository, never()).delete(expired);
    }

    private AnalysisEvidenceArchiveEntity entity(String id, long createdAt) {
        var entity = new AnalysisEvidenceArchiveEntity(id, "tenant", "user", "run", "sha", 2, "{}");
        entity.createdAtEpochMs = createdAt;
        return entity;
    }
}
