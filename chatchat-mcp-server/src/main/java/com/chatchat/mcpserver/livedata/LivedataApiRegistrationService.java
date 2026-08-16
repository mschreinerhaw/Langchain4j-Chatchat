package com.chatchat.mcpserver.livedata;

import com.chatchat.mcpserver.api.ApiMcpToolPublisher;
import com.chatchat.mcpserver.api.ApiInvokeResult;
import com.chatchat.mcpserver.api.ApiInvokeService;
import com.chatchat.mcpserver.api.ApiServiceConfig;
import com.chatchat.mcpserver.api.ApiServiceConfigService;
import com.chatchat.mcpserver.ops.HttpEndpointConfig;
import com.chatchat.mcpserver.ops.HttpEndpointConfigService;
import com.chatchat.mcpserver.search.McpAssetLuceneIndexService;
import com.chatchat.mcpserver.search.McpTemplateLuceneIndexService;
import com.chatchat.tools.livedata.LivedataApiDefinition;
import com.chatchat.tools.livedata.LivedataAutoRegistrationProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
    private final McpTemplateLuceneIndexService templateIndexService;
    private final McpAssetLuceneIndexService assetIndexService;

    @Order(Ordered.HIGHEST_PRECEDENCE + 300)
    @EventListener(ApplicationReadyEvent.class)
    public void synchronizeParameterContractsOnStartup() {
        try {
            LivedataParameterSyncResult result = synchronizeRegisteredParameterContracts();
            log.info("LiveData API parameter contracts synchronized on startup: {}", result);
        } catch (Exception ex) {
            log.warn("LiveData API parameter contract synchronization deferred: {}", ex.getMessage(), ex);
        }
    }

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
        HttpEndpointConfig sourceGateway = configuredGateway();

        for (String id : ids) {
            LivedataApiDefinition definition = definitions.get(id);
            if (definition == null) {
                missing++;
                continue;
            }
            try {
                HttpEndpointConfig gateway = gatewayConfigService.upsertByToolName(
                    mapper.toGatewayConfig(definition, sourceGateway));
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

    /**
     * Synchronizes source-declared input and return-field contracts into already
     * registered API services and their generated gateways. It never creates new
     * API registrations and preserves manually maintained identity, capability,
     * category and transport governance.
     */
    public LivedataParameterSyncResult synchronizeRegisteredParameterContracts() {
        ensureEnabled();
        List<LivedataApiDefinition> definitions = configService.findApis();
        LivedataAutoRegistrationProperties settings = configService.current();
        HttpEndpointConfig sourceGateway = configuredGateway();
        int matched = 0;
        int updated = 0;
        int unchanged = 0;
        int skipped = 0;
        List<String> errors = new ArrayList<>();
        List<ApiServiceConfig> updatedServices = new ArrayList<>();

        for (LivedataApiDefinition definition : definitions) {
            try {
                ApiServiceConfig mapped = mapper.toApiServiceConfig(definition, null, settings);
                Optional<ApiServiceConfig> registered =
                    apiServiceConfigService.findByToolName(mapped.getToolName());
                if (registered.isEmpty()) {
                    continue;
                }
                matched++;
                ApiServiceConfig existing = registered.get();
                if (existing.getGatewayId() == null || existing.getGatewayId().isBlank()) {
                    skipped++;
                    continue;
                }
                HttpEndpointConfig gateway = gatewayConfigService.getById(existing.getGatewayId());
                HttpEndpointConfig mappedGateway = mapper.toGatewayConfig(definition, sourceGateway, settings);
                if (!isLivedataGateway(gateway)
                    || gateway.getToolName() == null
                    || mappedGateway.getToolName() == null
                    || !gateway.getToolName().equalsIgnoreCase(mappedGateway.getToolName())) {
                    skipped++;
                    continue;
                }
                String synchronizedOutputSchema = mapped.getOutputSchemaJson() == null
                    || mapped.getOutputSchemaJson().isBlank()
                    ? existing.getOutputSchemaJson()
                    : mapped.getOutputSchemaJson();
                boolean serviceChanged = !same(existing.getInputSchemaJson(), mapped.getInputSchemaJson())
                    || !same(existing.getOutputSchemaJson(), synchronizedOutputSchema);
                boolean gatewayChanged = !same(gateway.getInputSchemaJson(), mappedGateway.getInputSchemaJson())
                    || !same(gateway.getBodyTemplate(), mappedGateway.getBodyTemplate());
                if (!serviceChanged && !gatewayChanged) {
                    unchanged++;
                    continue;
                }
                if (gatewayChanged) {
                    gatewayConfigService.updateParameterContract(gateway.getId(),
                        mappedGateway.getInputSchemaJson(), mappedGateway.getBodyTemplate());
                }
                ApiServiceConfig saved = serviceChanged
                    ? apiServiceConfigService.updateDataContract(existing.getId(),
                        mapped.getInputSchemaJson(), synchronizedOutputSchema)
                    : existing;
                updatedServices.add(saved);
                updated++;
            } catch (Exception ex) {
                skipped++;
                errors.add(displayName(definition) + ": " + exceptionMessage(ex));
                log.warn("LiveData API parameter synchronization skipped sourceId={} apiId={} error={}",
                    sourceId(definition), definition.apiId(), exceptionMessage(ex));
            }
        }

        if (!updatedServices.isEmpty()) {
            publisher.refresh();
            templateIndexService.upsertApiServiceTemplates(updatedServices);
            assetIndexService.refresh("api_service");
        }
        return new LivedataParameterSyncResult(
            definitions.size(), matched, updated, unchanged, skipped, List.copyOf(errors));
    }

    public ApiInvokeResult test(String id, Map<String, Object> arguments) {
        ensureEnabled();
        LivedataApiDefinition definition = findDefinition(id);
        ApiServiceConfig mapped = mapper.toApiServiceConfig(definition);
        Optional<ApiServiceConfig> registered = apiServiceConfigService.findByToolName(mapped.getToolName());
        if (registered.isPresent()) {
            return apiInvokeService.invoke(registered.get(), arguments == null ? Map.of() : arguments);
        }
        HttpEndpointConfig sourceGateway = configuredGateway();
        HttpEndpointConfig transientGateway = mapper.toGatewayConfig(definition, sourceGateway);
        applyTransientTransport(mapped, transientGateway);
        log.info("LiveData candidate test uses selected gateway defaults tool={} sourceGatewayId={}",
            mapped.getToolName(), sourceGateway.getId());
        return apiInvokeService.invoke(mapped, arguments == null ? Map.of() : arguments);
    }

    private void applyTransientTransport(ApiServiceConfig target, HttpEndpointConfig transport) {
        target.setGatewayId(null);
        target.setMethod(transport.getMethod());
        target.setUrlTemplate(transport.getUrlTemplate());
        target.setHeadersJson(transport.getHeadersJson());
        target.setBodyTemplate(transport.getBodyTemplate());
        target.setTimeoutMs(transport.getTimeoutMs());
    }

    /**
     * Deletes an API service and its unshared generated gateway when the service originated from LiveData.
     * Generic API services are left untouched and return an empty result for the normal delete path.
     */
    public Optional<LivedataDeletionResult> deleteRegisteredServiceIfManaged(String serviceId) {
        ApiServiceConfig registered = apiServiceConfigService.getById(serviceId);
        if (registered.getGatewayId() == null || registered.getGatewayId().isBlank()) {
            return Optional.empty();
        }
        HttpEndpointConfig gateway;
        try {
            gateway = gatewayConfigService.getById(registered.getGatewayId());
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
        if (!isLivedataGateway(gateway)) {
            return Optional.empty();
        }
        boolean gatewayShared = apiServiceConfigService.listAll().stream()
            .anyMatch(service -> !service.getId().equals(registered.getId())
                && gateway.getId().equals(service.getGatewayId()));
        apiServiceConfigService.delete(registered.getId());
        boolean gatewayDeleted = false;
        if (!gatewayShared) {
            gatewayConfigService.delete(gateway.getId());
            gatewayDeleted = true;
        }
        return Optional.of(new LivedataDeletionResult(null, registered.getId(), gateway.getId(), gatewayDeleted));
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
                config.getInputSchemaJson(),
                config.getUrlTemplate(),
                config.isEnabled(),
                existing != null,
                existing == null ? null : existing.getId(),
                true,
                null
            );
        } catch (Exception ex) {
            String error = exceptionMessage(ex);
            log.warn("LiveData API cannot be mapped sourceId={} apiId={} serviceName={} error={}",
                sourceId(definition), definition.apiId(), definition.serviceName(), error, ex);
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
                null,
                false,
                false,
                null,
                false,
                error
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
        String inputSchemaJson,
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

    private HttpEndpointConfig configuredGateway() {
        String gatewayId = configService.getConfig().getGatewayId();
        if (gatewayId == null || gatewayId.isBlank()) {
            throw new IllegalStateException("LiveData gateway asset is not configured");
        }
        HttpEndpointConfig gateway = gatewayConfigService.getById(gatewayId);
        if (!gateway.isEnabled()) {
            throw new IllegalStateException("LiveData gateway asset is disabled: " + gatewayId);
        }
        return gateway;
    }

    private boolean isLivedataGateway(HttpEndpointConfig gateway) {
        if (gateway == null || gateway.getTags() == null) {
            return false;
        }
        return java.util.Arrays.stream(gateway.getTags().split("[,\\s]+"))
            .anyMatch(tag -> "livedata".equalsIgnoreCase(tag));
    }

    private String exceptionMessage(Exception ex) {
        if (ex == null) return "Unknown LiveData API mapping error";
        if (ex.getMessage() != null && !ex.getMessage().isBlank()) return ex.getMessage();
        return ex.getClass().getSimpleName();
    }

    private boolean same(String first, String second) {
        return java.util.Objects.equals(
            first == null ? null : first.trim(),
            second == null ? null : second.trim()
        );
    }

    public record LivedataDeletionResult(
        String sourceId,
        String serviceId,
        String gatewayId,
        boolean gatewayDeleted
    ) {
    }

    public record LivedataParameterSyncResult(
        int inspected,
        int matched,
        int updated,
        int unchanged,
        int skipped,
        List<String> errors
    ) {
    }
}
