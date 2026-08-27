package com.chatchat.agents.runtime.answer;


import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DraftArtifactRuntimePolicyTest {

    private final DraftArtifactRuntimePolicy policy = new DraftArtifactRuntimePolicy();

    @Test
    void labelsExplicitDdlDraftAndWritesAuditableRuntimeState() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("artifactContract", Map.of(
            "artifactType", "DDL",
            "deliveryMode", "DRAFT",
            "executionStatus", "NOT_EXECUTED",
            "authorizationStatus", "NOT_APPLICABLE_TO_DRAFT",
            "humanReviewRequired", true,
            "assumptions", java.util.List.of("命名和约束需要人工复核"),
            "disclosure", "> 制品状态：以下内容是未执行的 DDL 草稿，仅供人工审核。"
        ));

        DraftArtifactRuntimePolicy.Result result = policy.enforce(
            "```sql\nCREATE TABLE customer (id BIGINT);\n```",
            metadata
        );

        assertThat(result.draftArtifact()).isTrue();
        assertThat(result.answer())
            .startsWith("> 制品状态：以下内容是未执行的 DDL 草稿")
            .contains("CREATE TABLE customer");
        assertThat(metadata)
            .containsEntry("draftArtifactContractVersion", DraftArtifactRuntimePolicy.CONTRACT_VERSION)
            .containsEntry("artifactType", "DDL")
            .containsEntry("artifactExecutionStatus", "NOT_EXECUTED")
            .containsEntry("artifactAuthorizationStatus", "NOT_APPLICABLE_TO_DRAFT")
            .containsEntry("artifactHumanReviewRequired", true)
            .containsEntry("draftArtifactDisclosureApplied", true);
    }

    @Test
    void doesNotRelabelAnExecutionRequestAsDraft() {
        DraftArtifactRuntimePolicy.Result result = policy.enforce(
            "```sql\nCREATE TABLE customer (id BIGINT);\n```",
            new LinkedHashMap<>()
        );

        assertThat(result.draftArtifact()).isFalse();
        assertThat(result.answer()).doesNotContain("制品状态");
    }
}
