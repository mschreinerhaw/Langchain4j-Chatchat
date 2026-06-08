package com.chatchat.mcpserver.livedata;

import com.chatchat.mcpserver.api.ApiServiceConfig;
import com.chatchat.mcpserver.api.ApiServiceConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class LivedataApiAutoRegistrar {

    private final LivedataAutoRegistrationProperties properties;
    private final LivedataApiRepository repository;
    private final LivedataApiConfigMapper mapper;
    private final ApiServiceConfigService configService;

    @Order(Ordered.HIGHEST_PRECEDENCE)
    @EventListener(ApplicationReadyEvent.class)
    public void syncOnStartup() {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            sync();
        } catch (Exception ex) {
            log.warn("LiveData API auto registration failed: {}", ex.getMessage(), ex);
        }
    }

    public void sync() {
        int total = 0;
        int saved = 0;
        int skipped = 0;
        for (LivedataApiDefinition definition : repository.findApis()) {
            total++;
            try {
                ApiServiceConfig config = mapper.toApiServiceConfig(definition);
                if (!properties.isOverwriteExisting() && configService.existsByToolName(config.getToolName())) {
                    skipped++;
                    continue;
                }
                configService.upsertByToolName(config);
                saved++;
            } catch (Exception ex) {
                skipped++;
                log.warn("Skip livedata API {}: {}", definition.apiId(), ex.getMessage());
            }
        }
        log.info("LiveData API auto registration completed, total={}, saved={}, skipped={}", total, saved, skipped);
    }
}
