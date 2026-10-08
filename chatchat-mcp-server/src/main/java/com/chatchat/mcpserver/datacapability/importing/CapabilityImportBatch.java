package com.chatchat.mcpserver.datacapability.importing;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

@Entity @Table(name = "mcp_data_capability_import") @Getter @Setter
public class CapabilityImportBatch {
    @Id @Column(length = 36) private String id;
    @Column(nullable = false) private Instant createdAt;
    private boolean dryRun;
    private int succeeded;
    private int failed;
    @Column(length = 2000) private String publicationError;
    @Column(nullable = false, length = org.hibernate.Length.LONG32) private String resultsJson;
}
