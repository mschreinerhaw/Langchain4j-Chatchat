package com.chatchat.knowledgebase.search;

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
@Table(name = "search_feedback")
public class SearchFeedbackEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 500, nullable = false)
    private String queryText;

    @Column(length = 100)
    private String feedbackType;

    @Column(length = 100)
    private String userId;

    @Column(length = 100)
    private String docId;

    @Column(length = 120)
    private String chunkId;

    @Column(columnDefinition = "TEXT")
    private String chunkText;

    private Boolean positive = true;

    private Long createdAt;
}
