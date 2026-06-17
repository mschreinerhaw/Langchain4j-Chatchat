package com.chatchat.knowledgebase.search.rule;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "query_expand_rule")
public class QueryExpandRuleEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 50)
    private String intent;

    @Column(length = 100)
    private String sourceWord;

    @Column(columnDefinition = "TEXT")
    private String expandWords;

    private Integer weight = 1;

    private Integer priority = 0;

    private Boolean enabled = true;

    private Integer version = 1;

    private Long createdAt;

    private Long updatedAt;
}
