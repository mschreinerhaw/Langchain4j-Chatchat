package com.chatchat.integration.mcp.service;

import com.chatchat.integration.mcp.entity.McpCapability;
import com.chatchat.integration.mcp.repository.McpCapabilityRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
public class McpCapabilityService {
    public static final String NEWS_CAPABILITY_CODE = "news";
    private static final String LEGACY_NEWS_CAPABILITY_CODE = "builtin_news_collection";

    private final McpCapabilityRepository repository;

    public McpCapabilityService(McpCapabilityRepository repository) {
        this.repository = repository;
    }

    @PostConstruct
    @Transactional
    public void ensureBuiltInCapabilities() {
        repository.findByCapabilityCode(NEWS_CAPABILITY_CODE).orElseGet(() ->
            repository.findByCapabilityCode(LEGACY_NEWS_CAPABILITY_CODE).map(capability -> {
                capability.setCapabilityCode(NEWS_CAPABILITY_CODE);
                capability.setCapabilityName("资讯采集与分析");
                capability.setUpdatedAt(Instant.now());
                return repository.save(capability);
            }).orElseGet(() -> {
                McpCapability capability = new McpCapability();
                capability.setCapabilityCode(NEWS_CAPABILITY_CODE);
                capability.setCapabilityName("资讯采集与分析");
                capability.setCapabilityType("NEWS_COLLECTOR");
                capability.setProviderType("INTERNAL");
                capability.setProviderModule("chatchat-runtime-news");
                capability.setDescription("采集、标准化并检索资讯，供模型执行总结和事件分析。");
                return repository.save(capability);
            }));
    }

    public List<McpCapability> listAll() { return repository.findAll(); }

    public McpCapability require(Long id) {
        return repository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("MCP capability does not exist: " + id));
    }

    public McpCapability requireByCode(String capabilityCode) {
        return findByCode(capabilityCode)
            .orElseThrow(() -> new IllegalArgumentException("MCP capability does not exist: " + capabilityCode));
    }

    public Optional<McpCapability> findByCode(String capabilityCode) {
        return repository.findByCapabilityCode(capabilityCode);
    }

    public McpCapability setEnabled(Long id, boolean enabled) {
        McpCapability capability = require(id);
        capability.setEnabled(enabled);
        capability.setUpdatedAt(Instant.now());
        return repository.save(capability);
    }
}
