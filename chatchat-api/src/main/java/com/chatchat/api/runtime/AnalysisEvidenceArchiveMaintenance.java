package com.chatchat.api.runtime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Repairs the rebuildable index and applies an opt-in retention policy. */
@Component
public class AnalysisEvidenceArchiveMaintenance {
    private final AnalysisEvidenceArchiveRepository repository;
    private final AnalysisEvidenceSearchIndex index;
    private final int retentionDays;

    public AnalysisEvidenceArchiveMaintenance(AnalysisEvidenceArchiveRepository repository,
                                              AnalysisEvidenceSearchIndex index,
                                              @Value("${chatchat.analysis.evidence.retention-days:0}") int retentionDays) {
        this.repository = repository;
        this.index = index;
        this.retentionDays = retentionDays;
    }

    @Scheduled(fixedDelayString = "${chatchat.analysis.evidence.maintenance-interval-ms:300000}")
    public void maintain() {
        if (index.enabled()) {
            for (var entity : repository.pendingIndex(PageRequest.of(0, 100))) {
                try {
                    index.index(entity);
                    entity.indexStatus = "INDEXED";
                    repository.saveAndFlush(entity);
                } catch (RuntimeException failed) {
                    // Retry in the next maintenance pass; do not mutate verified evidence.
                }
            }
        }
        if (retentionDays <= 0 || !index.enabled()) return;
        long cutoff = System.currentTimeMillis() - Duration.ofDays(retentionDays).toMillis();
        for (var entity : repository.expiredBefore(cutoff, PageRequest.of(0, 100))) {
            try {
                index.remove(entity.archiveId);
                repository.delete(entity);
            } catch (RuntimeException failed) {
                // Never delete the authoritative copy while its secondary index may remain.
            }
        }
    }
}
