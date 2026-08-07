package com.chatchat.mcpserver.templatepublication;

import com.chatchat.mcpserver.api.ApiServiceConfigService;
import com.chatchat.mcpserver.database.DatabaseQueryConfigService;
import com.chatchat.mcpserver.ops.CommandTemplateService;
import com.chatchat.mcpserver.ops.HttpEndpointConfigService;
import com.chatchat.mcpserver.sql.SqlTemplateService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
public class TemplateAssetCatalogService {

    public static final String SSH = "ssh_host";
    public static final String SQL = "sql_datasource";
    public static final String HTTP = "http_endpoint";
    public static final String DATABASE_QUERY = "database_query";
    public static final String API = "api_service";

    private final CommandTemplateService commandTemplateService;
    private final SqlTemplateService sqlTemplateService;
    private final HttpEndpointConfigService httpEndpointConfigService;
    private final DatabaseQueryConfigService databaseQueryConfigService;
    private final ApiServiceConfigService apiServiceConfigService;

    public List<TemplateAsset> listEnabled() {
        List<TemplateAsset> result = new ArrayList<>();
        commandTemplateService.listEnabled().forEach(item -> result.add(asset(
            SSH, item.getCode(), item.getTitle(), item.getDescription(), item.getCategory())));
        sqlTemplateService.listEnabled().forEach(item -> result.add(asset(
            SQL, item.getCode(), item.getTitle(), item.getDescription(), item.getCategory())));
        httpEndpointConfigService.listEnabled().forEach(item -> {
            String templateId = firstText(item.getToolName(), firstText(item.getName(), item.getId()));
            result.add(asset(HTTP, templateId, firstText(item.getTitle(), item.getName()),
                item.getDescription(), item.getCategory()));
        });
        databaseQueryConfigService.listEnabled().forEach(item -> result.add(asset(
            DATABASE_QUERY, item.getToolName(), item.getTitle(), item.getDescription(), item.getCapabilityCategory())));
        apiServiceConfigService.listEnabled().forEach(item -> result.add(asset(
            API, item.getToolName(), item.getTitle(), item.getDescription(), item.getBusinessGroup())));
        return result.stream()
            .sorted(Comparator.comparing(TemplateAsset::assetType).thenComparing(TemplateAsset::title))
            .toList();
    }

    public boolean contains(String key) {
        return listEnabled().stream().anyMatch(item -> item.key().equals(key));
    }

    private TemplateAsset asset(String assetType, String templateId, String title,
                                String description, String category) {
        return new TemplateAsset(assetType + ":" + templateId, assetType, templateId,
            firstText(title, templateId), firstText(description, ""), firstText(category, ""));
    }

    private String firstText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    public record TemplateAsset(String key, String assetType, String templateId, String title,
                                String description, String category) { }
}
