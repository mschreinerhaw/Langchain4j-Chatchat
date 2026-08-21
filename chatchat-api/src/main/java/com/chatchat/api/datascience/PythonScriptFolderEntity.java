package com.chatchat.api.datascience;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter @Setter @Entity
@Table(name="ds_python_script_folder", indexes={
    @Index(name="idx_python_script_folder_owner", columnList="tenant_id,owner_id,sort_order,name")
})
public class PythonScriptFolderEntity {
    @Id @Column(length=64) private String id;
    @Column(name="tenant_id",length=64,nullable=false) private String tenantId;
    @Column(name="owner_id",length=64,nullable=false) private String ownerId;
    @Column(name="parent_id",length=64) private String parentId;
    @Column(length=120,nullable=false) private String name;
    @Column(name="sort_order",nullable=false) private int sortOrder;
    @Column(name="created_at",nullable=false) private Instant createdAt;
    @Column(name="updated_at",nullable=false) private Instant updatedAt;

    @PrePersist void create(){if(id==null)id=UUID.randomUUID().toString();Instant now=Instant.now();createdAt=now;updatedAt=now;}
    @PreUpdate void update(){updatedAt=Instant.now();}
}
