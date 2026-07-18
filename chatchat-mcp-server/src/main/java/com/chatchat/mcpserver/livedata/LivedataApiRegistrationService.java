package com.chatchat.mcpserver.livedata;

import com.chatchat.mcpserver.api.ApiMcpToolPublisher;
import com.chatchat.mcpserver.api.ApiInvokeResult;
import com.chatchat.mcpserver.api.ApiInvokeService;
import com.chatchat.mcpserver.api.ApiServiceConfig;
import com.chatchat.mcpserver.api.ApiServiceConfigService;
import com.chatchat.mcpserver.ops.HttpEndpointConfig;
import com.chatchat.mcpserver.ops.HttpEndpointConfigService;
import com.chatchat.tools.livedata.LivedataApiDefinition;
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

    private final LivedataConfigService configService;
    private final LivedataApiConfigMapper mapper;
    private final ApiServiceConfigService apiServiceConfigService;
    private final ApiInvokeService apiInvokeService;
    private final HttpEndpointConfigService gatewayConfigService;
    private final ApiMcpToolPublisher publisher;

    /**
     * Lists the candidates.
     *
     * @return the candidates list
     */
    public List<LivedataApiCandidate> listCandidates() {
        ensureEnabled();
        List<LivedataApiCandidate> candidates = new ArrayList<>();
        for (LivedataApiDefinition definition : configService.findApis()) {
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

        boolean overwrite = overwriteExisting == null ? configService.current().isOverwriteExisting() : overwriteExisting;
        Map<String, LivedataApiDefinition> definitions = new LinkedHashMap<>();
        for (LivedataApiDefinition definition : configService.findApis()) {
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
                HttpEndpointConfig gateway = gatewayConfigService.upsertByToolName(mapper.toGatewayConfig(definition));
                ApiServiceConfig config = mapper.toApiServiceConfig(definition, gateway.getId());
                boolean exists = apiServiceConfigService.existsByToolName(config.getToolName());
                if (exists && !overwrite) {
                    skipped++;
                    continue;
                }
                apiServiceConfigService.upsertByToolName(config);
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

    public ApiInvokeResult test(String id, Map<String, Object> arguments) {
        ensureEnabled();
        LivedataApiDefinition definition = findDefinition(id);
        ApiServiceConfig mapped = mapper.toApiServiceConfig(definition);
        ApiServiceConfig registered = apiServiceConfigService.findByToolName(mapped.getToolName())
            .orElseThrow(() -> new IllegalStateException("Register the LiveData API before testing: " + displayName(definition)));
        return apiInvokeService.invoke(registered, arguments == null ? Map.of() : arguments);
    }

    /**
     * Removes the MCP registration while preserving the source definition in LiveData.
     */
    public LivedataDeletionResult deleteRegistration(String id) {
        ensureEnabled();
        LivedataApiDefinition definition = findDefinition(id);
        ApiServiceConfig mapped = mapper.toApiServiceConfig(definition);
        ApiServiceConfig registered = apiServiceConfigService.findByToolName(mapped.getToolName())
            .orElseThrow(() -> new IllegalStateException("LiveData API is not registered: " + displayName(definition)));

        HttpEndpointConfig generatedGateway = gatewayConfigService.findByToolName(
                mapper.toGatewayConfig(definition).getToolName())
            .filter(gateway -> gateway.getId().equals(registered.getGatewayId()))
            .orElseThrow(() -> new IllegalStateException(
                "The same-name API service is not managed by LiveData and cannot be deleted here: " + mapped.getToolName()));

        boolean gatewayShared = apiServiceConfigService.listAll().stream()
            .anyMatch(service -> !service.getId().equals(registered.getId())
                && generatedGateway.getId().equals(service.getGatewayId()));

        apiServiceConfigService.delete(registered.getId());
        boolean gatewayDeleted = false;
        if (!gatewayShared) {
            gatewayConfigService.delete(generatedGateway.getId());
            gatewayDeleted = true;
        }
        publisher.refresh();
        return new LivedataDeletionResult(id, registered.getId(), generatedGateway.getId(), gatewayDeleted);
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
            ApiServiceConfig existing = apiServiceConfigService.findByToolName(config.getToolName()).orElse(null);
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

    private LivedataApiDefinition findDefinition(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("LiveData API id is required");
        }
        return configService.findApis().stream()
            .filter(definition -> id.equals(sourceId(definition)))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("LiveData API not found: " + id));
    }

    /**
     * Ensures the enabled.
     */
    private void ensureEnabled() {
        if (!configService.current().isEnabled()) {
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

    public record LivedataDeletionResult(
        String sourceId,
        String serviceId,
        String gatewayId,
        boolean gatewayDeleted
    ) {
    }
}
