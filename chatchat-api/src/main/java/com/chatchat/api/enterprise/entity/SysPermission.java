package com.chatchat.api.enterprise.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "sys_permission")
public class SysPermission extends EnterpriseAuditable {

    @Column(length = 64)
    private String parentId;

    @Column(length = 128, nullable = false, unique = true)
    private String permissionCode;

    @Column(length = 128, nullable = false)
    private String permissionName;

    @Column(length = 32, nullable = false)
    private String permissionType;

    @Column(length = 512)
    private String resourcePath;

    @Column(length = 16)
    private String httpMethod;

    @Column(length = 128)
    private String icon;

    @Column(nullable = false)
    private Integer sortOrder = 0;

    @Column(length = 32, nullable = false)
    private String status = "enabled";
}
