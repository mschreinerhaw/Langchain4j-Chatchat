package com.chatchat.enterprise.entity.security;

import com.chatchat.enterprise.entity.common.EnterpriseAuditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "skill_resource_scope", indexes = {
    @Index(name = "idx_skill_resource_scope", columnList = "tenant_id,skill_id,resource_type")
})
public class SkillResourceScope extends EnterpriseAuditable {
    @Column(name = "tenant_id", length = 64, nullable = false)
    private String tenantId;
    @Column(name = "skill_id", length = 64, nullable = false)
    private String skillId;
    @Column(name = "resource_type", length = 32, nullable = false)
    private String resourceType;
    @Column(name = "resource_id", length = 128, nullable = false)
    private String resourceId;
    @Column(nullable = false)
    private boolean enabled = true;
}
