package com.chatchat.api.runtime;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
@Getter @Setter @Entity
@Table(name="ds_skill_data_binding", uniqueConstraints=@UniqueConstraint(columnNames={"tenant_id","domain_skill_id","contract_id"}))
public class SkillDataBindingEntity {
    @Id @Column(length=64) private String id;
    @Column(name="tenant_id",nullable=false,length=64) private String tenantId;
    @Column(name="domain_skill_id",nullable=false,length=64) private String domainSkillId;
    @Column(name="contract_id",nullable=false,length=120) private String contractId;
    @Column(length=org.hibernate.Length.LONG32,nullable=false) private String draftJson;
    @Column(length=org.hibernate.Length.LONG32) private String publishedJson;
    @Column(length=64) private String publishedBy;
    private java.time.Instant publishedAt;
    @Version private long revision;
}

