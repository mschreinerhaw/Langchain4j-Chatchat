package com.chatchat.api.datascience;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;
import java.util.UUID;

@Getter @Setter @Entity
@Table(name="ds_python_script_version", uniqueConstraints=@UniqueConstraint(name="uk_python_script_version",columnNames={"script_id","version_number"}))
public class PythonScriptVersionEntity {
    @Id @Column(length=64) private String id;
    @Column(name="script_id",length=64,nullable=false) private String scriptId;
    @Column(name="version_number",nullable=false) private int versionNumber;
    @Lob @Column(name="source_code",nullable=false,columnDefinition="LONGTEXT") private String sourceCode;
    @Column(name="source_hash",length=64,nullable=false) private String sourceHash;
    @Column(name="created_at",nullable=false) private Instant createdAt;
    @PrePersist void create(){ if(id==null) id=UUID.randomUUID().toString(); if(createdAt==null) createdAt=Instant.now(); }
}
