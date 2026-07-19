package com.chatchat.mcpserver.livedata;

import com.chatchat.mcpserver.ops.HttpEndpointConfig;
import com.chatchat.mcpserver.ops.HttpEndpointConfigService;
import com.chatchat.mcpserver.sql.SqlDatasourceConfig;
import com.chatchat.mcpserver.sql.SqlDatasourceConfigService;
import com.chatchat.tools.builtin.DynamicJdbcDriverLoader;
import com.chatchat.tools.livedata.LivedataApiDefinition;
import com.chatchat.tools.livedata.LivedataAutoRegistrationProperties;
import com.chatchat.tools.livedata.LivedataSettingsProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.util.List;

@Service
@Primary
@RequiredArgsConstructor
public class LivedataConfigService implements LivedataSettingsProvider {

    private final LivedataConfigRepository repository;
    private final LivedataAutoRegistrationProperties fallbackProperties;
    private final SqlDatasourceConfigService datasourceConfigService;
    private final HttpEndpointConfigService gatewayConfigService;
    private final DynamicJdbcDriverLoader driverLoader;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public LivedataConfig getConfig() {
        return repository.findById(LivedataConfig.SINGLETON_ID).orElseGet(this::fromFallback);
    }

    @Transactional
    public LivedataConfig save(LivedataConfig request) {
        LivedataConfig config = repository.findById(LivedataConfig.SINGLETON_ID).orElseGet(LivedataConfig::new);
        config.setId(LivedataConfig.SINGLETON_ID);
        config.setEnabled(request.isEnabled());
        config.setDatasourceId(blankToNull(request.getDatasourceId()));
        if (config.getDatasourceId() != null) {
            datasourceConfigService.getEnabled(config.getDatasourceId());
        }
        config.setGatewayId(blankToNull(request.getGatewayId()));
        if (config.getGatewayId() != null) {
            HttpEndpointConfig gateway = gatewayConfigService.getById(config.getGatewayId());
            if (!gateway.isEnabled()) {
                throw new IllegalArgumentException("LiveData gateway asset is disabled: " + config.getGatewayId());
            }
            config.setServiceBaseUrl(normalizeGatewayBaseUrl(gateway.getUrlTemplate(), request.getLoginPath()));
        } else {
            config.setServiceBaseUrl(blankToNull(request.getServiceBaseUrl()));
        }
        config.setTableName(firstText(request.getTableName(), "ld_dataservice_api"));
        config.setServicePathTemplate(firstText(request.getServicePathTemplate(), "/service/{serviceName}/call"));
        config.setLoginEnabled(request.isLoginEnabled());
        config.setLoginPath(firstText(request.getLoginPath(), "/login"));
        config.setLoginId(blankToNull(request.getLoginId()));
        config.setLoginPwd(blankToNull(request.getLoginPwd()));
        config.setLoginTimeoutMs(clamp(request.getLoginTimeoutMs(), 1000, 60000, 10000));
        config.setSessionTtlSeconds(clamp(request.getSessionTtlSeconds(), 60, 86400, 1800));
        config.setAmsToken(blankToNull(request.getAmsToken()));
        config.setDefaultNamespace(firstText(request.getDefaultNamespace(), "livedata"));
        config.setToolNamePrefix(firstText(request.getToolNamePrefix(), "livedata_"));
        config.setPublishedState(request.getPublishedState());
        config.setMaxApis(clamp(request.getMaxApis(), 1, 10000, 1000));
        config.setTimeoutMs(clamp(request.getTimeoutMs(), 1000, 60000, 20000));
        config.setCacheEnabled(request.isCacheEnabled());
        config.setCacheTtlSeconds(clamp(request.getCacheTtlSeconds(), 1, 86400, 300));
        config.setOverwriteExisting(request.isOverwriteExisting());
        config.setIncludeUnpublishedAsDisabled(request.isIncludeUnpublishedAsDisabled());
        config.setExposeAmsTokenParameter(request.isExposeAmsTokenParameter());
        validateTableName(config.getTableName());
        if (config.isEnabled()) {
            if (config.getDatasourceId() == null && !hasText(fallbackProperties.getJdbcUrl())) {
                throw new IllegalArgumentException("datasourceId is required when LiveData is enabled");
            }
            if (config.getGatewayId() == null) {
                throw new IllegalArgumentException("gatewayId is required when LiveData is enabled");
            }
        }
        return repository.save(config);
    }

    @Transactional(readOnly = true)
    public List<LivedataApiDefinition> findApis() {
        LivedataConfig config = getConfig();
        LivedataAutoRegistrationProperties settings = toProperties(config);
        DataSource dataSource;
        if (hasText(config.getDatasourceId())) {
            SqlDatasourceConfig datasource = datasourceConfigService.getEnabled(config.getDatasourceId());
            dataSource = driverLoader.createDataSource(
                datasource.getJdbcUrl(),
                datasource.getUsername(),
                datasource.getPassword(),
                datasource.getDriverClass()
            );
        } else {
            if (!hasText(settings.getJdbcUrl())) {
                throw new IllegalStateException(
                    "LiveData datasource is not configured; select an enabled SQL datasource asset before loading APIs"
                );
            }
            dataSource = driverLoader.createDataSource(
                settings.getJdbcUrl(),
                settings.getUsername(),
                settings.getPassword(),
                settings.getDriverClass()
            );
        }
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.setMaxRows(settings.getMaxApis());
        String sql = """
            select id, api_id, api_name, params, description, namespace, service_name,
                   method_name, state, version, release_version
            from %s
            %s
            order by update_time desc, create_time desc, id desc
            """.formatted(safeTableName(settings.getTableName()), whereClause(settings));
        return jdbcTemplate.query(sql, rowMapper());
    }

    @Override
    @Transactional(readOnly = true)
    public LivedataAutoRegistrationProperties current() {
        return toProperties(getConfig());
    }

    private LivedataConfig fromFallback() {
        LivedataConfig config = new LivedataConfig();
        config.setEnabled(fallbackProperties.isEnabled());
        config.setTableName(firstText(fallbackProperties.getTableName(), "ld_dataservice_api"));
        config.setServiceBaseUrl(fallbackProperties.getServiceBaseUrl());
        config.setServicePathTemplate(firstText(fallbackProperties.getServicePathTemplate(), "/service/{serviceName}/call"));
        config.setLoginEnabled(fallbackProperties.isLoginEnabled());
        config.setLoginPath(firstText(fallbackProperties.getLoginPath(), "/login"));
        config.setLoginId(fallbackProperties.getLoginId());
        config.setLoginPwd(fallbackProperties.getLoginPwd());
        config.setLoginTimeoutMs(fallbackProperties.getLoginTimeoutMs());
        config.setSessionTtlSeconds(fallbackProperties.getSessionTtlSeconds());
        config.setAmsToken(fallbackProperties.getAmsToken());
        config.setDefaultNamespace(firstText(fallbackProperties.getDefaultNamespace(), "livedata"));
        config.setToolNamePrefix(firstText(fallbackProperties.getToolNamePrefix(), "livedata_"));
        config.setPublishedState(fallbackProperties.getPublishedState());
        config.setMaxApis(fallbackProperties.getMaxApis());
        config.setTimeoutMs(fallbackProperties.getTimeoutMs());
        config.setCacheEnabled(fallbackProperties.isCacheEnabled());
        config.setCacheTtlSeconds(fallbackProperties.getCacheTtlSeconds());
        config.setOverwriteExisting(fallbackProperties.isOverwriteExisting());
        config.setIncludeUnpublishedAsDisabled(fallbackProperties.isIncludeUnpublishedAsDisabled());
        config.setExposeAmsTokenParameter(fallbackProperties.isExposeAmsTokenParameter());
        return config;
    }

    private LivedataAutoRegistrationProperties toProperties(LivedataConfig config) {
        LivedataAutoRegistrationProperties properties = new LivedataAutoRegistrationProperties();
        properties.setEnabled(config.isEnabled());
        properties.setJdbcUrl(fallbackProperties.getJdbcUrl());
        properties.setUsername(fallbackProperties.getUsername());
        properties.setPassword(fallbackProperties.getPassword());
        properties.setDriverClass(fallbackProperties.getDriverClass());
        properties.setTableName(firstText(config.getTableName(), "ld_dataservice_api"));
        properties.setServiceBaseUrl(normalizeGatewayBaseUrl(
            firstText(config.getServiceBaseUrl(), fallbackProperties.getServiceBaseUrl()),
            firstText(config.getLoginPath(), fallbackProperties.getLoginPath(), "/login")));
        properties.setServicePathTemplate(firstText(config.getServicePathTemplate(), "/service/{serviceName}/call"));
        properties.setLoginEnabled(config.isLoginEnabled());
        properties.setLoginPath(firstText(config.getLoginPath(), "/login"));
        String loginId = firstText(config.getLoginId(), fallbackProperties.getLoginId());
        String loginPwd = firstText(config.getLoginPwd(), fallbackProperties.getLoginPwd());
        if ((!hasText(loginId) || !hasText(loginPwd)) && hasText(config.getGatewayId())) {
            GatewayLoginCredentials gatewayCredentials = gatewayLoginCredentials(config.getGatewayId());
            loginId = firstText(loginId, gatewayCredentials.loginId());
            loginPwd = firstText(loginPwd, gatewayCredentials.loginPwd());
        }
        properties.setLoginId(loginId);
        properties.setLoginPwd(loginPwd);
        properties.setLoginTimeoutMs(clamp(config.getLoginTimeoutMs(), 1000, 60000, 10000));
        properties.setSessionTtlSeconds(clamp(config.getSessionTtlSeconds(), 60, 86400, 1800));
        properties.setAmsToken(firstText(config.getAmsToken(), fallbackProperties.getAmsToken()));
        properties.setDefaultNamespace(firstText(config.getDefaultNamespace(), "livedata"));
        properties.setToolNamePrefix(firstText(config.getToolNamePrefix(), "livedata_"));
        properties.setPublishedState(config.getPublishedState());
        properties.setMaxApis(clamp(config.getMaxApis(), 1, 10000, 1000));
        properties.setTimeoutMs(clamp(config.getTimeoutMs(), 1000, 60000, 20000));
        properties.setCacheEnabled(config.isCacheEnabled());
        properties.setCacheTtlSeconds(clamp(config.getCacheTtlSeconds(), 1, 86400, 300));
        properties.setOverwriteExisting(config.isOverwriteExisting());
        properties.setIncludeUnpublishedAsDisabled(config.isIncludeUnpublishedAsDisabled());
        properties.setExposeAmsTokenParameter(config.isExposeAmsTokenParameter());
        return properties;
    }

    private GatewayLoginCredentials gatewayLoginCredentials(String gatewayId) {
        try {
            HttpEndpointConfig gateway = gatewayConfigService.getById(gatewayId);
            if (!hasText(gateway.getBodyTemplate())) {
                return GatewayLoginCredentials.EMPTY;
            }
            JsonNode body = objectMapper.readTree(gateway.getBodyTemplate());
            return new GatewayLoginCredentials(literalText(body, "loginId"), literalText(body, "loginPwd"));
        } catch (Exception ignored) {
            return GatewayLoginCredentials.EMPTY;
        }
    }

    private String literalText(JsonNode node, String field) {
        if (node == null || !node.isObject()) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || !hasText(value.asText())) {
            return null;
        }
        String text = value.asText().trim();
        return text.matches("^\\{\\{[^}]+}}$") ? null : text;
    }

    private String whereClause(LivedataAutoRegistrationProperties properties) {
        if (properties.isIncludeUnpublishedAsDisabled()) {
            return "";
        }
        return "where state = " + properties.getPublishedState();
    }

    private String safeTableName(String tableName) {
        String value = firstText(tableName, "ld_dataservice_api");
        if (!value.matches("[A-Za-z0-9_.$`]+")) {
            throw new IllegalArgumentException("Invalid livedata table name: " + tableName);
        }
        return value;
    }

    private void validateTableName(String tableName) {
        safeTableName(tableName);
    }

    private String normalizeGatewayBaseUrl(String value, String loginPath) {
        String baseUrl = value == null ? null : value.trim().replaceAll("/+$", "");
        if (baseUrl == null || baseUrl.isBlank()) return baseUrl;
        String path = firstText(loginPath, "/login");
        if (!path.startsWith("/")) path = "/" + path;
        path = path.replaceAll("/+$", "");
        if (!path.isBlank() && baseUrl.endsWith(path)) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - path.length()).replaceAll("/+$", "");
        }
        return baseUrl;
    }

    private record GatewayLoginCredentials(String loginId, String loginPwd) {
        private static final GatewayLoginCredentials EMPTY = new GatewayLoginCredentials(null, null);
    }

    private RowMapper<LivedataApiDefinition> rowMapper() {
        return (rs, rowNum) -> new LivedataApiDefinition(
            rs.getString("id"),
            rs.getString("api_id"),
            rs.getString("api_name"),
            rs.getString("params"),
            rs.getString("description"),
            rs.getString("namespace"),
            rs.getString("service_name"),
            rs.getString("method_name"),
            readInteger(rs.getObject("state")),
            rs.getString("version"),
            rs.getString("release_version")
        );
    }

    private Integer readInteger(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private int clamp(int value, int min, int max, int fallback) {
        int normalized = value <= 0 ? fallback : value;
        return Math.max(min, Math.min(normalized, max));
    }

    private String firstText(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
