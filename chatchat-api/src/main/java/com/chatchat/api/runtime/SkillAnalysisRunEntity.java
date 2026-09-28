package com.chatchat.api.runtime;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
@Getter @Setter @Entity
@Table(name="ds_skill_analysis_run",indexes=@Index(name="idx_skill_run_owner",columnList="tenant_id,user_id"))
public class SkillAnalysisRunEntity {
    @Id @Column(length=64) private String id;
    @Column(name="tenant_id",nullable=false,length=64) private String tenantId;
    @Column(name="user_id",nullable=false,length=64) private String userId;
    private java.time.Instant createdAt;
    @Column(length=org.hibernate.Length.LONG32,nullable=false) private String requestJson;
    @Column(length=org.hibernate.Length.LONG32,nullable=false) private String resultJson;
    @Column(length=org.hibernate.Length.LONG32) private String reviewJson;
    @Version private long revision;
}

