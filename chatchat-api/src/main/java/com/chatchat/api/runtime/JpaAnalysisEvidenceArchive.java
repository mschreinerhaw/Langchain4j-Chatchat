package com.chatchat.api.runtime;

import com.chatchat.common.runtime.analysis.evidence.EvidenceBundle;
import com.chatchat.common.runtime.analysis.model.AnalysisContext;
import com.chatchat.common.runtime.analysis.spi.AnalysisEvidenceArchivePort;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/** PostgreSQL/JPA evidence archive with owner-scoped reads and content integrity checks. */
@Component
public class JpaAnalysisEvidenceArchive implements AnalysisEvidenceArchivePort {
    private static final int MAX_BUNDLE_BYTES = 2 * 1024 * 1024;
    private final AnalysisEvidenceArchiveRepository repository;
    private final ObjectMapper mapper;

    public JpaAnalysisEvidenceArchive(AnalysisEvidenceArchiveRepository repository, ObjectMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    @Override
    public Reference archive(AnalysisContext context, EvidenceBundle bundle) {
        String tenant = context.kernelScope().tenantId();
        String user = context.kernelScope().userId();
        String run = context.kernelScope().runId() == null
            ? context.kernelScope().requestId() : context.kernelScope().runId();
        if (blank(tenant) || blank(user) || blank(run) || bundle == null || bundle.evidence().isEmpty())
            throw new IllegalArgumentException("Accepted evidence and authenticated owner are required");
        try {
            String json = mapper.writeValueAsString(bundle);
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_BUNDLE_BYTES)
                throw new IllegalArgumentException("Evidence bundle exceeds archive limit");
            String sha = sha256(bytes);
            String id = UUID.nameUUIDFromBytes((tenant + "\u0000" + user + "\u0000" + run + "\u0000" + sha)
                .getBytes(StandardCharsets.UTF_8)).toString();
            repository.saveAndFlush(new AnalysisEvidenceArchiveEntity(id, tenant, user, run,
                sha, bytes.length, json));
            return new Reference(id, sha, bytes.length);
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("Evidence bundle is not serializable", invalid);
        }
    }

    @Override
    public Optional<ArchivedEvidence> read(String tenantId, String userId, String archiveId) {
        if (blank(tenantId) || blank(userId) || blank(archiveId)) return Optional.empty();
        return repository.findByArchiveIdAndTenantIdAndUserId(archiveId, tenantId, userId)
            .map(entity -> {
                byte[] bytes = entity.bundleJson.getBytes(StandardCharsets.UTF_8);
                if (bytes.length != entity.byteLength || !sha256(bytes).equals(entity.sha256))
                    throw new IllegalStateException("Archived evidence integrity check failed");
                return new ArchivedEvidence(new Reference(entity.archiveId, entity.sha256, entity.byteLength),
                    entity.bundleJson);
            });
    }

    private String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private boolean blank(String text) { return text == null || text.isBlank(); }
}
