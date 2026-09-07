package com.chatchat.chat.analysis.profile;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter @Entity
@Table(name = "domain_analysis_profile", uniqueConstraints = @UniqueConstraint(columnNames = {"tenant_id", "analysis_type"}))
public class DomainAnalysisProfileEntity {
    @Id @Column(length = 200) private String id;
    @Column(name = "tenant_id", nullable = false, length = 128) private String tenantId;
    @Column(name = "analysis_type", nullable = false, length = 64) private String analysisType;
    @Column(nullable = false, length = 120) private String name;
    @Column(nullable = false, length = 600) private String description;
    @Column(nullable = false) private boolean enabled;
    @Column(name = "guidance_json", nullable = false, columnDefinition = "TEXT") private String guidanceJson;
    @Version private Long revision;
}
