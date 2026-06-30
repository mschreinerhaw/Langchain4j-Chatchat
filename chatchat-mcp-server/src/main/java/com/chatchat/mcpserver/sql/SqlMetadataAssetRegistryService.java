package com.chatchat.mcpserver.sql;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class SqlMetadataAssetRegistryService {

    private final SqlMetadataAssetRegistryRepository repository;

    public List<SqlMetadataAssetRegistry> listByDatasource(String datasourceId) {
        if (datasourceId == null || datasourceId.isBlank()) {
            return List.of();
        }
        return repository.findByDatasourceIdOrderByDatabaseNameAsc(datasourceId.trim());
    }

    public List<SqlMetadataAssetRegistry> listEnabledByDatasource(String datasourceId) {
        if (datasourceId == null || datasourceId.isBlank()) {
            return List.of();
        }
        return repository.findByDatasourceIdAndEnabledTrueOrderByDatabaseNameAsc(datasourceId.trim());
    }

    @Transactional
    public SqlMetadataAssetRegistry save(String datasourceId, SqlMetadataAssetRegistry request) {
        String resolvedDatasourceId = requiredText(datasourceId, "datasourceId is required");
        String databaseName = normalizeName(request == null ? null : request.getDatabaseName(), "databaseName is required");
        SqlMetadataAssetRegistry registry = request != null && request.getId() != null && !request.getId().isBlank()
            ? repository.findById(request.getId()).orElse(new SqlMetadataAssetRegistry())
            : repository.findByDatasourceIdAndDatabaseNameIgnoreCase(resolvedDatasourceId, databaseName)
                .orElse(new SqlMetadataAssetRegistry());
        registry.setDatasourceId(resolvedDatasourceId);
        registry.setDatabaseName(databaseName);
        registry.setOwnerUser(blankToNull(request == null ? null : request.getOwnerUser()));
        registry.setEnabled(request == null || request.isEnabled());
        registry.setRefreshMode(normalizeRefreshMode(request == null ? null : request.getRefreshMode()));
        if (registry.getIndexStatus() == null || registry.getIndexStatus().isBlank()) {
            registry.setIndexStatus("PENDING");
        }
        return repository.save(registry);
    }

    @Transactional
    public void delete(String datasourceId, String registryId) {
        SqlMetadataAssetRegistry registry = repository.findById(requiredText(registryId, "registryId is required"))
            .orElseThrow(() -> new IllegalArgumentException("SQL metadata asset registry not found: " + registryId));
        if (!registry.getDatasourceId().equals(requiredText(datasourceId, "datasourceId is required"))) {
            throw new IllegalArgumentException("SQL metadata asset registry does not belong to datasource: " + datasourceId);
        }
        repository.delete(registry);
    }

    @Transactional
    public void deleteByDatasource(String datasourceId) {
        if (datasourceId != null && !datasourceId.isBlank()) {
            repository.deleteByDatasourceId(datasourceId.trim());
        }
    }

    @Transactional
    public SqlMetadataAssetRegistry syncDefaultForDatasource(SqlDatasourceConfig datasource) {
        List<String> databaseNames = defaultDatabaseNames(datasource);
        if (datasource == null || datasource.getId() == null || datasource.getId().isBlank() || databaseNames.isEmpty()) {
            return null;
        }
        Set<String> desired = databaseNames.stream()
            .map(value -> value.trim().toLowerCase(Locale.ROOT))
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<SqlMetadataAssetRegistry> existing = repository.findByDatasourceIdOrderByDatabaseNameAsc(datasource.getId());
        for (SqlMetadataAssetRegistry registry : existing) {
            if (registry.getDatabaseName() != null
                && !desired.contains(registry.getDatabaseName().trim().toLowerCase(Locale.ROOT))) {
                registry.setEnabled(false);
            }
        }
        SqlMetadataAssetRegistry first = null;
        for (String databaseName : databaseNames) {
            SqlMetadataAssetRegistry registry = repository.findByDatasourceIdAndDatabaseNameIgnoreCase(datasource.getId(), databaseName)
                .orElseGet(SqlMetadataAssetRegistry::new);
            registry.setDatasourceId(datasource.getId());
            registry.setDatabaseName(databaseName);
            registry.setOwnerUser(blankToNull(firstText(datasource.getUsername(), datasource.getMetadataScopeValue())));
            registry.setEnabled(datasource.isEnabled());
            registry.setRefreshMode(datasource.isMetadataAutoRefreshEnabled() ? "AUTO" : "MANUAL");
            if (registry.getIndexStatus() == null || registry.getIndexStatus().isBlank()) {
                registry.setIndexStatus("PENDING");
            }
            SqlMetadataAssetRegistry saved = repository.save(registry);
            if (first == null) {
                first = saved;
            }
        }
        if (!existing.isEmpty()) {
            repository.saveAll(existing);
        }
        return first;
    }

    @Transactional
    public void markIndexed(String datasourceId, List<String> indexedDatabases, String error) {
        List<SqlMetadataAssetRegistry> registries = listByDatasource(datasourceId);
        if (registries.isEmpty()) {
            return;
        }
        Set<String> indexed = new LinkedHashSet<>();
        if (indexedDatabases != null) {
            indexedDatabases.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .forEach(indexed::add);
        }
        Instant now = Instant.now();
        boolean failed = error != null && !error.isBlank();
        for (SqlMetadataAssetRegistry registry : registries) {
            if (failed) {
                registry.setIndexStatus("FAILED");
                registry.setIndexMessage(error);
            } else if (indexed.contains(registry.getDatabaseName().trim().toLowerCase(Locale.ROOT))) {
                registry.setIndexStatus("INDEXED");
                registry.setIndexMessage(null);
                registry.setLastIndexedAt(now);
            } else if (registry.isEnabled()) {
                registry.setIndexStatus("EMPTY");
                registry.setIndexMessage("No tables indexed for registered database/schema");
            }
        }
        repository.saveAll(registries);
    }

    String defaultDatabaseName(SqlDatasourceConfig datasource) {
        List<String> values = defaultDatabaseNames(datasource);
        return values.isEmpty() ? null : values.get(0);
    }

    List<String> defaultDatabaseNames(SqlDatasourceConfig datasource) {
        if (datasource == null) {
            return List.of();
        }
        String scopeType = firstText(datasource.getMetadataScopeType(), "JDBC_DATABASE")
            .toUpperCase(Locale.ROOT)
            .replace('-', '_')
            .replace(' ', '_');
        if ("ALL_VISIBLE_SCHEMAS".equals(scopeType)) {
            return List.of();
        }
        String explicit = blankToNull(datasource.getMetadataScopeValue());
        String jdbcDatabase = databaseNameFromJdbcUrl(datasource.getJdbcUrl());
        String username = blankToNull(datasource.getUsername());
        if (explicit != null) {
            List<String> explicitValues = splitScopeValues(explicit);
            if (!explicitValues.isEmpty()) {
                return explicitValues;
            }
        }
        String value = switch (scopeType) {
            case "LOGIN_USER_SCHEMA" -> firstText(username, jdbcDatabase);
            default -> firstText(jdbcDatabase, username);
        };
        return value == null ? List.of() : List.of(value);
    }

    private List<String> splitScopeValues(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(value.split("[,;\\r\\n]+"))
            .map(String::trim)
            .filter(item -> !item.isBlank())
            .distinct()
            .toList();
    }

    private String normalizeRefreshMode(String value) {
        String normalized = firstText(value, "MANUAL").toUpperCase(Locale.ROOT);
        return "AUTO".equals(normalized) ? "AUTO" : "MANUAL";
    }

    private String databaseNameFromJdbcUrl(String jdbcUrl) {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            return null;
        }
        String trimmed = jdbcUrl.trim();
        java.util.regex.Matcher parameterMatcher = java.util.regex.Pattern
            .compile("(?i)[;?&](?:databaseName|database)=([^;?&]+)")
            .matcher(trimmed);
        if (parameterMatcher.find()) {
            return blankToNull(parameterMatcher.group(1));
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
            .compile("(?i)^jdbc:[^:]+://[^/]+/([^?;]+)")
            .matcher(trimmed);
        return matcher.find() ? blankToNull(matcher.group(1)) : null;
    }

    private String normalizeName(String value, String message) {
        return requiredText(value, message).trim();
    }

    private String requiredText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
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
}
