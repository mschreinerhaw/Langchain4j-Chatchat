package com.chatchat.mcpserver.datacapability.execution;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

@Entity @Table(name = "mcp_data_capability_execution", indexes = @Index(name = "idx_mcp_data_capability_execution", columnList = "capabilityCode,startedAt"))
@Getter @Setter
public class CapabilityExecution {
    @Id @Column(length = 36) private String id;
    @Column(nullable = false, length = 100) private String capabilityCode;
    @Column(nullable = false, length = 20) private String status;
    @Column(nullable = false) private boolean preview;
    @Column(nullable = false) private Instant startedAt;
    private Instant finishedAt;
    private long durationMs;
    @Column(length = 2000) private String error;
    @Column(length = org.hibernate.Length.LONG32) private String resultJson;
}
