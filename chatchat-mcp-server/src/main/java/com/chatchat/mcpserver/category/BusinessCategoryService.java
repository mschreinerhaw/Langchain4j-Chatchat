package com.chatchat.mcpserver.category;

import com.chatchat.agents.protocol.ModelProtocolJson;
import com.chatchat.mcpserver.api.ApiServiceConfig;
import com.chatchat.mcpserver.api.ApiServiceConfigRepository;
import com.chatchat.mcpserver.database.DatabaseQueryConfig;
import com.chatchat.mcpserver.database.DatabaseQueryConfigRepository;
import com.chatchat.mcpserver.ops.HttpEndpointConfigRepository;
import com.chatchat.mcpserver.ops.SshHostConfigRepository;
import com.chatchat.mcpserver.sql.SqlDatasourceConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class BusinessCategoryService {

    private static final Logger log = LoggerFactory.getLogger(BusinessCategoryService.class);
    public static final String DEFAULT_CODE = "default";
    public static final String DEFAULT_NAME = "默认分类";

    private final BusinessCategoryRepository repository;
    private final ApiServiceConfigRepository apiRepository;
    private final DatabaseQueryConfigRepository queryRepository;
    private final SshHostConfigRepository sshRepository;
    private final SqlDatasourceConfigRepository sqlRepository;
    private final HttpEndpointConfigRepository httpRepository;
    private final JdbcTemplate jdbcTemplate;

    public BusinessCategoryService(BusinessCategoryRepository repository,
                                   ApiServiceConfigRepository apiRepository,
                                   DatabaseQueryConfigRepository queryRepository,
                                   SshHostConfigRepository sshRepository,
                                   SqlDatasourceConfigRepository sqlRepository,
                                   HttpEndpointConfigRepository httpRepository,
                                   JdbcTemplate jdbcTemplate) {
        this.repository = repository;
        this.apiRepository = apiRepository;
        this.queryRepository = queryRepository;
        this.sshRepository = sshRepository;
        this.sqlRepository = sqlRepository;
        this.httpRepository = httpRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Order(Ordered.HIGHEST_PRECEDENCE + 50)
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void migrateLegacyCategories() {
        migrateLegacyTable("mcp_data_query_category", true, false);
        migrateLegacyTable("mcp_api_service_category", false, true);
        BusinessCategory defaultCategory = ensureDefaultCategory();
        backfillUncategorizedAssets(defaultCategory);
        reconcileApiGatewayCategories(defaultCategory);
    }

    @Transactional(readOnly = true)
    public List<BusinessCategory> listAll() {
        return repository.findAllByOrderBySortOrderAscNameAsc();
    }

    @Transactional(readOnly = true)
    public List<BusinessCategory> listEnabled() {
        return repository.findByEnabledTrueOrderBySortOrderAscNameAsc();
    }

    @Transactional(readOnly = true)
    public BusinessCategory require(String idOrCode) {
        if (idOrCode == null || idOrCode.isBlank()) {
            throw new IllegalArgumentException("business category is required");
        }
        return repository.findById(idOrCode)
            .or(() -> repository.findByCodeIgnoreCase(idOrCode))
            .orElseThrow(() -> new IllegalArgumentException("Business category not found: " + idOrCode));
    }

    @Transactional
    public BusinessCategory resolveOrDefault(String idOrCode) {
        return idOrCode == null || idOrCode.isBlank()
            ? ensureDefaultCategory()
            : require(idOrCode);
    }

    @Transactional
    public BusinessCategory ensureDefaultCategory() {
        BusinessCategory category = repository.findByCodeIgnoreCase(DEFAULT_CODE).orElseGet(() -> {
            BusinessCategory created = new BusinessCategory();
            created.setCode(DEFAULT_CODE);
            created.setName(DEFAULT_NAME);
            created.setDescription("用户未指定业务分类时自动归入此分类");
            created.setDomain(DEFAULT_CODE);
            created.setKeywordsJson(ModelProtocolJson.compact(List.of(DEFAULT_CODE, "默认", "未分类")));
            created.setSortOrder(10_000);
            created.setEnabled(true);
            return repository.save(created);
        });
        if (!category.isEnabled()) {
            category.setEnabled(true);
            return repository.save(category);
        }
        return category;
    }

    @Transactional
    public BusinessCategory save(BusinessCategory draft) {
        if (draft == null) throw new IllegalArgumentException("business category is required");
        String code = normalizeCode(draft.getCode());
        String name = required(draft.getName(), "name");
        repository.findByCodeIgnoreCase(code)
            .filter(existing -> draft.getId() == null || !existing.getId().equals(draft.getId()))
            .ifPresent(existing -> {
                throw new IllegalArgumentException("category code already exists: " + code);
            });
        BusinessCategory target = draft.getId() == null || draft.getId().isBlank()
            ? new BusinessCategory()
            : repository.findById(draft.getId())
                .orElseThrow(() -> new IllegalArgumentException("Business category not found: " + draft.getId()));
        if (DEFAULT_CODE.equalsIgnoreCase(target.getCode()) && !DEFAULT_CODE.equals(code)) {
            throw new IllegalArgumentException("default category code cannot be changed");
        }
        target.setCode(code);
        target.setName(name);
        target.setDescription(text(draft.getDescription()));
        target.setDomain(first(draft.getDomain(), "finance"));
        target.setKeywordsJson(first(draft.getKeywordsJson(), "[]"));
        target.setSortOrder(draft.getSortOrder());
        target.setEnabled(DEFAULT_CODE.equals(code) || draft.isEnabled());
        BusinessCategory saved = repository.save(target);
        propagateTemplateMetadata(saved);
        return saved;
    }

    @Transactional
    public void delete(String id) {
        BusinessCategory category = repository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Business category not found: " + id));
        if (DEFAULT_CODE.equalsIgnoreCase(category.getCode())) {
            throw new IllegalArgumentException("default category cannot be deleted");
        }
        long references = apiRepository.countByCategoryId(id)
            + queryRepository.countByCategoryId(id)
            + sshRepository.countByCategoryId(id)
            + sqlRepository.countByCategoryId(id)
            + httpRepository.countByCategoryId(id);
        if (references > 0) {
            throw new IllegalArgumentException("Business category is referenced by assets or templates");
        }
        repository.deleteById(id);
    }

    private void propagateTemplateMetadata(BusinessCategory category) {
        List<ApiServiceConfig> apiConfigs = apiRepository.findByCategoryId(category.getId());
        if (apiConfigs != null) {
            apiConfigs.forEach(config -> {
                config.setBusinessGroup(category.getCode());
                config.setBusinessGroupName(category.getName());
                config.setBusinessGroupDescription(category.getDescription());
                apiRepository.save(config);
            });
        }
        List<DatabaseQueryConfig> queryConfigs = queryRepository.findByCategoryId(category.getId());
        if (queryConfigs != null) {
            queryConfigs.forEach(config -> {
                config.setCapabilityCategory(category.getCode());
                config.setBusinessGroup(category.getCode());
                config.setBusinessGroupName(category.getName());
                config.setBusinessGroupDescription(category.getDescription());
                queryRepository.save(config);
            });
        }
    }

    private void backfillUncategorizedAssets(BusinessCategory defaultCategory) {
        sshRepository.findAll().stream()
            .filter(asset -> asset.getCategoryId() == null || asset.getCategoryId().isBlank())
            .forEach(asset -> {
                asset.setCategoryId(defaultCategory.getId());
                sshRepository.save(asset);
            });
        sqlRepository.findAll().stream()
            .filter(asset -> asset.getCategoryId() == null || asset.getCategoryId().isBlank())
            .forEach(asset -> {
                asset.setCategoryId(defaultCategory.getId());
                sqlRepository.save(asset);
            });
        httpRepository.findAll().stream()
            .filter(asset -> asset.getCategoryId() == null || asset.getCategoryId().isBlank())
            .forEach(asset -> {
                asset.setCategoryId(defaultCategory.getId());
                httpRepository.save(asset);
            });
    }

    private void reconcileApiGatewayCategories(BusinessCategory defaultCategory) {
        int[] synchronizedCount = {0};
        httpRepository.findAll().forEach(gateway -> {
            if (gateway.getId() == null || gateway.getId().isBlank()) {
                return;
            }
            List<ApiServiceConfig> linkedServices = apiRepository.findByGatewayId(gateway.getId());
            if (linkedServices == null || linkedServices.isEmpty()) {
                return;
            }
            BusinessCategory selected = linkedServices.stream()
                .map(this::resolveApiServiceCategory)
                .filter(java.util.Objects::nonNull)
                .filter(category -> !DEFAULT_CODE.equalsIgnoreCase(category.getCode()))
                .findFirst()
                .orElseGet(() -> resolveExistingCategory(gateway.getCategoryId(), null)
                    .orElse(defaultCategory));

            boolean gatewayChanged = !selected.getId().equals(gateway.getCategoryId());
            if (gatewayChanged) {
                gateway.setCategoryId(selected.getId());
                httpRepository.save(gateway);
            }
            boolean serviceChanged = false;
            for (ApiServiceConfig service : linkedServices) {
                if (!selected.getId().equals(service.getCategoryId())
                    || !selected.getCode().equals(service.getBusinessGroup())
                    || !selected.getName().equals(service.getBusinessGroupName())) {
                    applyApiCategory(service, selected);
                    apiRepository.save(service);
                    serviceChanged = true;
                }
            }
            if (gatewayChanged || serviceChanged) {
                synchronizedCount[0]++;
            }
        });
        if (synchronizedCount[0] > 0) {
            log.info("Reconciled API gateway and service business categories count={}", synchronizedCount[0]);
        }
    }

    private BusinessCategory resolveApiServiceCategory(ApiServiceConfig service) {
        java.util.Optional<BusinessCategory> byId = resolveExistingCategory(service.getCategoryId(), null);
        if (byId.isPresent() && !DEFAULT_CODE.equalsIgnoreCase(byId.get().getCode())) {
            return byId.get();
        }
        java.util.Optional<BusinessCategory> byCode = resolveExistingCategory(null, service.getBusinessGroup());
        if (byCode.isPresent() && !DEFAULT_CODE.equalsIgnoreCase(byCode.get().getCode())) {
            return byCode.get();
        }
        return byId.or(() -> byCode).orElse(null);
    }

    private java.util.Optional<BusinessCategory> resolveExistingCategory(String id, String code) {
        if (id != null && !id.isBlank()) {
            java.util.Optional<BusinessCategory> byId = repository.findById(id);
            if (byId.isPresent()) {
                return byId;
            }
        }
        if (code != null && !code.isBlank()) {
            return repository.findByCodeIgnoreCase(code);
        }
        return java.util.Optional.empty();
    }

    private void applyApiCategory(ApiServiceConfig config, BusinessCategory category) {
        config.setCategoryId(category.getId());
        config.setBusinessGroup(category.getCode());
        config.setBusinessGroupName(category.getName());
        config.setBusinessGroupDescription(category.getDescription());
    }

    private void migrateLegacyTable(String table, boolean hasDomain, boolean apiCategory) {
        String fields = hasDomain
            ? "id,code,name,description,domain,keywords_json,sort_order,enabled"
            : "id,code,name,description,keywords_json,sort_order,enabled";
        List<Map<String, Object>> rows;
        try {
            rows = jdbcTemplate.queryForList("select " + fields + " from " + table);
        } catch (DataAccessException ignored) {
            return;
        }
        int migrated = 0;
        for (Map<String, Object> row : rows) {
            String oldId = text(row.get("id"));
            String code = normalizeCode(text(row.get("code")));
            BusinessCategory target = repository.findByCodeIgnoreCase(code).orElse(null);
            if (target == null) {
                target = new BusinessCategory();
                target.setId(repository.existsById(oldId) ? UUID.randomUUID().toString() : oldId);
                target.setCode(code);
                target.setName(required(text(row.get("name")), "name"));
                target.setDescription(text(row.get("description")));
                target.setDomain(hasDomain ? first(text(row.get("domain")), "finance") : "finance");
                target.setKeywordsJson(first(text(row.get("keywords_json")), ModelProtocolJson.compact(List.of())));
                target.setSortOrder(number(row.get("sort_order")));
                target.setEnabled(booleanValue(row.get("enabled")));
                target = repository.save(target);
                migrated++;
            }
            if (!oldId.equals(target.getId())) {
                remapLegacyReferences(oldId, target, apiCategory);
            }
        }
        if (migrated > 0) {
            log.info("Unified business categories migrated table={} count={}", table, migrated);
        }
    }

    private void remapLegacyReferences(String oldId, BusinessCategory category, boolean apiCategory) {
        if (apiCategory) {
            List<ApiServiceConfig> configs = apiRepository.findByCategoryId(oldId);
            if (configs != null) {
                configs.forEach(config -> {
                    config.setCategoryId(category.getId());
                    apiRepository.save(config);
                });
            }
            return;
        }
        List<DatabaseQueryConfig> configs = queryRepository.findByCategoryId(oldId);
        if (configs != null) {
            configs.forEach(config -> {
                config.setCategoryId(category.getId());
                queryRepository.save(config);
            });
        }
    }

    private String normalizeCode(String value) {
        String code = required(value, "code").toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9_]+", "_")
            .replaceAll("_+", "_")
            .replaceAll("^_|_$", "");
        if (code.isBlank()) throw new IllegalArgumentException("category code is required");
        return code;
    }

    private String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }

    private String first(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private int number(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) return bool;
        if (value instanceof Number number) return number.intValue() != 0;
        return !"false".equalsIgnoreCase(text(value)) && !"0".equals(text(value));
    }
}
