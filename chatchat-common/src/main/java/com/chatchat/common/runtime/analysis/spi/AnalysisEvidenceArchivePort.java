package com.chatchat.common.runtime.analysis.spi;

import com.chatchat.common.runtime.analysis.evidence.EvidenceBundle;
import com.chatchat.common.runtime.analysis.model.AnalysisContext;
import java.util.Optional;

/** Durable, tenant-scoped archive of a Judge-accepted final evidence bundle. */
public interface AnalysisEvidenceArchivePort {
    Reference archive(AnalysisContext context, EvidenceBundle bundle);
    Optional<ArchivedEvidence> read(String tenantId, String userId, String archiveId);

    record Reference(String archiveId, String sha256, long byteLength) { }
    record ArchivedEvidence(Reference reference, String bundleJson) { }
}
