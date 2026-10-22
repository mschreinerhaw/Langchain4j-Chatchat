package com.chatchat.mcpserver.admin;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;
import java.util.UUID;

/** Local MCP console permissions only; never used for Runtime tool/data authorization. */
@Entity @Getter @Setter
@Table(name="mcp_admin_menu_grant", uniqueConstraints=@UniqueConstraint(columnNames={"username","menu_key"}))
public class AdminMenuGrant {
    @Id @Column(length=64) private String id = UUID.randomUUID().toString();
    @Column(nullable=false,length=128) private String username;
    @Column(name="menu_key",nullable=false,length=128) private String menuKey;
    @Column(nullable=false,length=128) private String grantedBy;
    @Column(nullable=false) private Instant createdAt = Instant.now();
}
