package com.chatchat.agents.runtime.plan;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Uses the real H2 migration seed for isolated Runtime OS tests. */
public final class MigrationSeedRuntimeSemanticPolicySource implements RuntimeSemanticPolicySource {
    private static final RuntimeSemanticPolicy POLICY = load();

    @Override
    public RuntimeSemanticPolicy snapshot() {
        return POLICY;
    }

    private static RuntimeSemanticPolicy load() {
        try {
            Path cwd = Path.of("").toAbsolutePath();
            Path migration = cwd.resolve("database/migration/h2/V20261009_01__agent_runtime_semantic_policy.sql");
            if (!Files.exists(migration)) migration = cwd.resolve("../database/migration/h2/"
                + "V20261009_01__agent_runtime_semantic_policy.sql");
            String sql = Files.readString(migration, StandardCharsets.UTF_8);
            String marker = "('default', '";
            int start = sql.indexOf(marker);
            int end = sql.indexOf("');", start);
            if (start < 0 || end < 0) throw new IllegalStateException("Migration seed not found");
            String json = sql.substring(start + marker.length(), end);
            Map<String, Object> policy = new ObjectMapper().readValue(json, new TypeReference<>() { });
            return RuntimeSemanticPolicy.from(policy);
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot load Runtime semantic migration seed", failure);
        }
    }
}
