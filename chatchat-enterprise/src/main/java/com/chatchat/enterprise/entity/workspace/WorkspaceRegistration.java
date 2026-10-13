package com.chatchat.enterprise.entity.workspace;

import com.chatchat.enterprise.entity.common.EnterpriseAuditable;
import jakarta.persistence.*;
import lombok.*;

@Entity @Table(name = "workspace_registry") @Getter @Setter
public class WorkspaceRegistration extends EnterpriseAuditable {
    @Column(nullable = false, length = 32) private String status = "enabled";
    @Column(nullable = false, length = 128) private String accessPermissionCode;
    @Column(nullable = false, length = org.hibernate.Length.LONG32) private String manifestJson;
    @Column(nullable = false) private boolean defaultWorkspace;
    @Version private Long version;
}
