package com.chatchat.enterprise.entity.identity;

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
@Table(name = "sys_menu", indexes = {
    @Index(name = "idx_sys_menu_parent_order", columnList = "parent_id, sort_order"),
    @Index(name = "idx_sys_menu_status", columnList = "status")
})
public class SysMenu extends EnterpriseAuditable {

    @Column(length = 64)
    private String parentId;

    @Column(length = 128, nullable = false, unique = true)
    private String menuCode;

    @Column(length = 128, nullable = false)
    private String menuName;

    @Column(length = 32, nullable = false)
    private String menuType = "menu";

    @Column(length = 512)
    private String routePath;

    @Column(length = 128)
    private String icon;

    @Column(nullable = false)
    private Integer sortOrder = 0;

    @Column(length = 32, nullable = false)
    private String status = "enabled";
}
