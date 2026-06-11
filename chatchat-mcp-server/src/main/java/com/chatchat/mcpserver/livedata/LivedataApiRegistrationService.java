package com.chatchat.mcpserver.livedata;

import com.chatchat.mcpserver.api.ApiMcpToolPublisher;
import com.chatchat.mcpserver.api.ApiServiceConfig;
import com.chatchat.mcpserver.api.ApiServiceConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class LivedataApiRegistrationService {

    private final LivedataAutoRegistrationProperties properties;
    private final LivedataApiRepository repository;
    private final LivedataApiConfigMapper mapper;
    private final ApiServiceConfigService configService;
    private final ApiMcpToolPublisher publisher;

    /**
     * Lists the candidates.
     *
     * @return the candidates list
     */
    public List<LivedataApiCandidate> listCandidates() {
        ensureEnabled();
        List<LivedataApiCandidate> candidates = new ArrayList<>();
        for (LivedataApiDefinition definition : repository.findApis()) {
            candidates.add(toCandidate(definition));
        }
        return candidates;
    }

    /**
     * Registers the register.
     *
     * @param ids the ids value
     * @param overwriteExisting the overwrite existing value
     * @return the operation result
     */
    public LivedataRegistrationResult register(List<String> ids, Boolean overwriteExisting) {
        ensureEnabled();
        if (ids == null || ids.isEmpty()) {
            throw new IllegalArgumentException("ids is required");
        }

        boolean overwrite = overwriteExisting == null ? properties.isOverwriteExisting() : overwriteExisting;
        Map<String, LivedataApiDefinition> definitions = new LinkedHashMap<>();
        for (LivedataApiDefinition definition : repository.findApis()) {
            definitions.put(sourceId(definition), definition);
        }

        int registered = 0;
        int skipped = 0;
        int missing = 0;
        List<String> errors = new ArrayList<>();

        for (String id : ids) {
            LivedataApiDefinition definition = definitions.get(id);
            if (definition == null) {
                missing++;
                continue;
            }
            try {
                ApiServiceConfig config = mapper.toApiServiceConfig(definition);
                boolean exists = configService.existsByToolName(config.getToolName());
                if (exists && !overwrite) {
                    skipped++;
                    continue;
                }
                configService.upsertByToolName(config);
                registered++;
            } catch (Exception ex) {
                skipped++;
                errors.add(displayName(definition) + ": " + ex.getMessage());
                log.warn("Skip livedata API {} during manual registration: {}", displayName(definition), ex.getMessage());
            }
        }

        if (registered > 0) {
            publisher.refresh();
        }
        return new LivedataRegistrationResult(ids.size(), registered, skipped, missing, errors);
    }

    /**
     * Converts the value to candidate.
     *
     * @param definition the definition value
     * @return the converted candidate
     */
    private LivedataApiCandidate toCandidate(LivedataApiDefinition definition) {
        try {
            ApiServiceConfig config = mapper.toApiServiceConfig(definition);
            ApiServiceConfig existing = configService.findByToolName(config.getToolName()).orElse(null);
            return new LivedataApiCandidate(
                sourceId(definition),
                definition.apiId(),
                definition.apiName(),
                definition.namespace(),
                definition.serviceName(),
                definition.methodName(),
                definition.state(),
                definition.version(),
                definition.releaseVersion(),
                config.getToolName(),
                config.getTitle(),
                config.getDescription(),
                config.getUrlTemplate(),
                config.isEnabled(),
                existing != null,
                existing == null ? null : existing.getId(),
                true,
                null
            );
        } catch (Exception ex) {
            return new LivedataApiCandidate(
                sourceId(definition),
                definition.apiId(),
                definition.apiName(),
                definition.namespace(),
                definition.serviceName(),
                definition.methodName(),
                definition.state(),
                definition.version(),
                definition.releaseVersion(),
                null,
                firstNonBlank(definition.apiName(), definition.apiId(), definition.serviceName(), definition.id()),
                definition.description(),
                null,
                false,
                false,
                null,
                false,
                ex.getMessage()
            );
        }
    }

    /**
     * Ensures the enabled.
     */
    private void ensureEnabled() {
        if (!properties.isEnabled()) {
            throw new IllegalStateException("LiveData manual API registration is disabled");
        }
    }

    /**
     * Performs the source id operation.
     *
     * @param definition the definition value
     * @return the operation result
     */
    private String sourceId(LivedataApiDefinition definition) {
        String id = firstNonBlank(definition.id(), definition.apiId(), definition.serviceName(), definition.methodName());
        if (id != null) {
            return id;
        }
        return Integer.toHexString(definition.hashCode());
    }

    /**
     * Performs the display name operation.
     *
     * @param definition the definition value
     * @return the operation result
     */
    private String displayName(LivedataApiDefinition definition) {
        return firstNonBlank(definition.apiId(), definition.apiName(), definition.serviceName(), definition.id(), "-");
    }

    /**
     * Performs the first non blank operation.
     *
     * @param values the values value
     * @return the operation result
     */
    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    public record LivedataApiCandidate(
        String id,
        String apiId,
        String apiName,
        String namespace,
        String serviceName,
        String methodName,
        Integer state,
        String version,
        String releaseVersion,
        String toolName,
        String title,
        String description,
        String urlTemplate,
        boolean enabled,
        boolean registered,
        String existingServiceId,
        boolean canRegister,
        String error
    ) {
    }

    public record LivedataRegistrationResult(
        int requested,
        int registered,
        int skipped,
        int missing,
        List<String> errors
    ) {
    }
}
