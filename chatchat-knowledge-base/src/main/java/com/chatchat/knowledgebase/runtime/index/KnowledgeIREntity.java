package com.chatchat.knowledgebase.runtime.index;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "knowledge_ir_unit",
    uniqueConstraints = @UniqueConstraint(name = "uk_knowledge_ir_doc_unit", columnNames = {"document_id", "knowledge_id"}),
    indexes = {
        @Index(name = "idx_knowledge_ir_document", columnList = "document_id,active"),
        @Index(name = "idx_knowledge_ir_tenant_type", columnList = "tenant_id,knowledge_type,active")
    })
public class KnowledgeIREntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "knowledge_id", length = 160, nullable = false)
    private String knowledgeId;
    @Column(name = "document_id", length = 160, nullable = false)
    private String documentId;
    @Column(name = "tenant_id", length = 100, nullable = false)
    private String tenantId;
    @Column(name = "owner_user_id", length = 100)
    private String ownerUserId;
    @Column(length = 20)
    private String visibility;
    @Column(name = "permission_roles", columnDefinition = "TEXT")
    private String permissionRolesJson;
    @Column(name = "tags_json", columnDefinition = "TEXT")
    private String tagsJson;
    @Column(length = 80)
    private String version;
    @Column(length = 200)
    private String domain;
    @Column(name = "knowledge_type", length = 40, nullable = false)
    private String knowledgeType;
    @Column(length = 500)
    private String title;
    @Column(name = "semantic_description", columnDefinition = "TEXT")
    private String semanticDescription;
    @Column(name = "rules_json", columnDefinition = "TEXT")
    private String rulesJson;
    @Column(name = "constraints_json", columnDefinition = "TEXT")
    private String constraintsJson;
    @Column(name = "applicable_intents_json", columnDefinition = "TEXT")
    private String applicableIntentsJson;
    @Column(name = "required_inputs_json", columnDefinition = "TEXT")
    private String requiredInputsJson;
    @Column(name = "compact_representation", columnDefinition = "TEXT", nullable = false)
    private String compactRepresentation;
    @Column(name = "search_text", columnDefinition = "TEXT", nullable = false)
    private String searchText;
    @Column(name = "source_id", length = 200)
    private String sourceId;
    @Column(name = "source_chunk_id", length = 200)
    private String sourceChunkId;
    @Column(name = "source_document_name", length = 500)
    private String sourceDocumentName;
    @Column(name = "source_section", length = 500)
    private String sourceSection;
    @Column(name = "source_citation", columnDefinition = "TEXT")
    private String sourceCitation;
    private Double relevance;
    private Boolean active = true;
    private Long createdAt;
    private Long updatedAt;
}
