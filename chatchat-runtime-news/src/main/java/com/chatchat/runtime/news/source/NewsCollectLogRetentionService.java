package com.chatchat.runtime.news.source;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class NewsCollectLogRetentionService {

    private final NewsCollectRecordRepository repository;

    public NewsCollectLogRetentionService(NewsCollectRecordRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public int deleteExpiredBatch(Instant cutoff, int batchSize) {
        int normalizedSize = Math.max(1, Math.min(batchSize, 10_000));
        List<Long> ids = repository.findExpiredIds(cutoff, PageRequest.of(0, normalizedSize));
        if (ids.isEmpty()) {
            return 0;
        }
        return repository.deleteExpiredIds(ids);
    }
}
