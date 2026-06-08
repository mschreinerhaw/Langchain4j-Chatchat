package com.chatchat.enterprise.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "sys_role_permission")
public class SysRolePermission extends EnterpriseAuditable {

    @Column(length = 64, nullable = false)
    private String tenantId;

    @Column(length = 64, nullable = false)
    private String roleId;

    @Column(length = 64, nullable = false)
    private String permissionId;
}
