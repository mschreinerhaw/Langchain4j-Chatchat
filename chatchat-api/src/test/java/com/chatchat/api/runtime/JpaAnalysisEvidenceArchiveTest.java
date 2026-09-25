package com.chatchat.api.runtime;

import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.runtime.analysis.evidence.EvidenceBundle;
import com.chatchat.common.runtime.analysis.evidence.ToolAnalysisEvidence;
import com.chatchat.common.runtime.analysis.model.AnalysisContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JpaAnalysisEvidenceArchiveTest {
    @Test void persistsOwnerScopedEvidenceAndVerifiesIntegrity() {
        var row = new AtomicReference<AnalysisEvidenceArchiveEntity>();
        var repository = mock(AnalysisEvidenceArchiveRepository.class);
        when(repository.saveAndFlush(any())).thenAnswer(call -> {
            var entity = call.getArgument(0, AnalysisEvidenceArchiveEntity.class);
            row.set(entity);
            return entity;
        });
        when(repository.findByArchiveIdAndTenantIdAndUserId(any(), any(), any())).thenAnswer(call ->
            Optional.ofNullable(row.get()).filter(entity -> entity.archiveId.equals(call.getArgument(0))
                && entity.tenantId.equals(call.getArgument(1)) && entity.userId.equals(call.getArgument(2))));
        var archive = new JpaAnalysisEvidenceArchive(repository, new ObjectMapper());
        var context = new AnalysisContext("analyze", new KernelDataScope("tenant", "user", "request",
            null, "run", null, Map.of()), "skill", List.of(), List.of(), List.of(), null, Map.of());
        var bundle = new EvidenceBundle(null, List.of(new ToolAnalysisEvidence("e1", "tool", "call",
            "verified", Map.of())), List.of(), Map.of());

        var reference = archive.archive(context, bundle);

        assertThat(reference.sha256()).hasSize(64);
        assertThat(archive.read("tenant", "user", reference.archiveId())).isPresent();
        assertThat(archive.read("tenant", "other-user", reference.archiveId())).isEmpty();
        assertThat(archive.archive(context, bundle).archiveId()).isEqualTo(reference.archiveId());
        row.get().bundleJson = "tampered";
        assertThatThrownBy(() -> archive.read("tenant", "user", reference.archiveId()))
            .isInstanceOf(IllegalStateException.class);
    }
}
