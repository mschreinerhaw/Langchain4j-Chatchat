package com.chatchat.enterprise.entity.identity;

import com.chatchat.enterprise.entity.common.EnterpriseAuditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "sys_menu_permission", indexes = {
    @Index(name = "idx_sys_menu_permission_permission", columnList = "permission_id")
}, uniqueConstraints = {
    @UniqueConstraint(name = "uk_sys_menu_permission", columnNames = {"menu_id", "permission_id"})
})
public class SysMenuPermission extends EnterpriseAuditable {

    @Column(length = 64, nullable = false)
    private String menuId;

    @Column(length = 64, nullable = false)
    private String permissionId;
}
