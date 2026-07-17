package com.chatchat.runtime.news.tool;

import com.chatchat.common.tool.ToolInput;
import com.chatchat.common.tool.ToolOutput;
import com.chatchat.runtime.news.source.NewsCollectRecordRepository;
import com.chatchat.runtime.news.source.NewsSourceRepository;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class NewsSourceStatusToolExecutor implements NewsToolExecutor {
    private final NewsSourceRepository sourceRepository;
    private final NewsCollectRecordRepository recordRepository;

    public NewsSourceStatusToolExecutor(NewsSourceRepository sourceRepository, NewsCollectRecordRepository recordRepository) {
        this.sourceRepository = sourceRepository;
        this.recordRepository = recordRepository;
    }

    @Override public ToolOutput execute(ToolInput input) {
        try {
            Number id = input.getParameterAsNumber("sourceId");
            var sources = id == null ? sourceRepository.findAll()
                : sourceRepository.findById(id.longValue()).map(List::of).orElse(List.of());
            List<Map<String, Object>> result = sources.stream().map(source -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("sourceId", source.getId());
                row.put("sourceCode", source.getSourceCode());
                row.put("sourceName", source.getSourceName());
                row.put("sourceType", source.getSourceType());
                row.put("enabled", source.isEnabled());
                row.put("lastCollectedAt", source.getLastCollectedAt());
                row.put("collectedRecords", recordRepository.countBySourceId(source.getId()));
                return row;
            }).toList();
            return ToolOutput.success(result);
        } catch (Exception ex) { return ToolOutput.failure(ex); }
    }
}
