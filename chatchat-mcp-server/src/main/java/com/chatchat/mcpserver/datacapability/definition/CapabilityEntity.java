package com.chatchat.mcpserver.datacapability.definition;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

@Entity @Table(name = "mcp_data_capability") @Getter @Setter
public class CapabilityEntity {
    @Id @Column(length = 100) private String code;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) private CapabilityType type;
    @Column(nullable = false, length = org.hibernate.Length.LONG32) private String definitionJson;
    @Column(nullable = false) private Instant updatedAt;
}
