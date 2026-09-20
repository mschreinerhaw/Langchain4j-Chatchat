package com.chatchat.chat.skills.domain.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** Single import boundary used by application services; Runtime never depends on an external format. */
@Component
@RequiredArgsConstructor
public final class ExternalSkillAdapterGateway {
    private final List<ExternalSkillAdapter> adapters;
    private final ExternalSkillCompiler compiler;
    private final ObjectMapper objectMapper;

    public ExternalSkillCompilation adaptAndCompile(ExternalSkillSource source) {
        ExternalSkillAdapter adapter = adapters.stream().filter(candidate -> candidate.supports(source)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("No adapter supports this external skill format"));
        AdaptedExternalSkill document = adapter.adapt(source);
        RuntimeSkillIr skillIr = compiler.compile(document);
        try {
            byte[] original = source.originalArtifact();
            byte[] artifact = original.length == 0
                ? source.content().getBytes(java.nio.charset.StandardCharsets.UTF_8) : original;
            return new ExternalSkillCompilation(skillIr, artifact, sha256(artifact),
                objectMapper.writeValueAsString(document), objectMapper.writeValueAsString(skillIr),
                source.sourceReference());
        } catch (Exception ex) {
            throw new IllegalArgumentException("Unable to materialize external skill compilation", ex);
        }
    }

    private String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }
}
