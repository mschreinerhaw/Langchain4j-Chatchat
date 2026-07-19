package com.chatchat.runtime.news.analysis;

import com.chatchat.runtime.news.config.NewsRuntimeProperties;
import com.chatchat.runtime.news.model.NewsDocument;
import com.chatchat.runtime.news.store.NewsDocumentStore;
import com.chatchat.runtime.news.store.NewsIndexStateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConditionalOnProperty(prefix = "chatchat.runtime.news.open-search", name = "enabled", havingValue = "true")
public class NewsAnalysisScheduler {
    private static final Logger log = LoggerFactory.getLogger(NewsAnalysisScheduler.class);
    private final NewsRuntimeProperties properties;
    private final NewsIndexStateService stateService;
    private final NewsDocumentStore store;
    private final NewsContentAnalyzer analyzer;

    public NewsAnalysisScheduler(NewsRuntimeProperties properties, NewsIndexStateService stateService,
                                 NewsDocumentStore store, NewsContentAnalyzer analyzer) {
        this.properties = properties;
        this.stateService = stateService;
        this.store = store;
        this.analyzer = analyzer;
    }

    @Scheduled(fixedDelayString = "${chatchat.runtime.news.analysis.poll-delay-millis:5000}")
    public void analyzePending() {
        if (!properties.getAnalysis().isEnabled()) return;
        List<NewsAnalysisTaskEntity> tasks = stateService.claimAnalysisTasks(properties.getAnalysis().getBatchSize());
        for (NewsAnalysisTaskEntity task : tasks) analyze(task);
    }

    private void analyze(NewsAnalysisTaskEntity task) {
        try {
            NewsDocument document = store.findById(task.getDocumentId()).orElseThrow(() ->
                new IllegalStateException("Indexed news document was not found: " + task.getDocumentId()));
            NewsDocument analyzed = analyzer.analyze(document);
            store.bulkIndex(List.of(analyzed));
            stateService.analysisCompleted(task.getId(), task.getDocumentId());
            log.info("news_analysis_completed taskId={} documentId={} categories={} tags={}",
                task.getId(), task.getDocumentId(), analyzed.categories(), analyzed.tags());
        } catch (Exception ex) {
            stateService.analysisFailed(task.getId(), task.getDocumentId(), ex.getMessage());
            log.warn("news_analysis_failed taskId={} documentId={} error={}",
                task.getId(), task.getDocumentId(), ex.getMessage());
        }
    }
}
