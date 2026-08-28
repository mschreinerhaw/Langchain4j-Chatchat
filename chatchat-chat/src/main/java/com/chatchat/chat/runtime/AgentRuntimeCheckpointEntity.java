package com.chatchat.chat.runtime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "agent_runtime_checkpoint", indexes =
    @Index(name = "idx_runtime_checkpoint_run", columnList = "run_id, step_id"))
public class AgentRuntimeCheckpointEntity {

    @Id
    @Column(name = "checkpoint_id", length = 128, nullable = false)
    private String checkpointId;

    @Column(name = "run_id", length = 64, nullable = false)
    private String runId;

    @Column(name = "step_id", nullable = false)
    private Integer stepId;

    @Column(name = "checkpoint_json", columnDefinition = "LONGTEXT", nullable = false)
    private String checkpointJson;

    @Column(name = "updated_at", nullable = false)
    private Long updatedAt;
}
