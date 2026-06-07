package com.chatchat.api.enterprise.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "sys_role_org_scope")
public class SysRoleOrgScope extends EnterpriseAuditable {

    @Column(length = 64, nullable = false)
    private String tenantId;

    @Column(length = 64, nullable = false)
    private String roleId;

    @Column(length = 32, nullable = false)
    private String scopeType = "org_and_children";

    @Column(length = 64)
    private String orgId;
}
