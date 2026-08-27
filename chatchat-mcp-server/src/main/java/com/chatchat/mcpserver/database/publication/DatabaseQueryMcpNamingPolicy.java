package com.chatchat.mcpserver.database.publication;

import com.chatchat.mcpserver.database.definition.DatabaseQueryConfig;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

@Component
public class DatabaseQueryMcpNamingPolicy {

    private static final int MAX_TOOL_NAME_LENGTH = 128;

    public String toolName(DatabaseQueryConfig config) {
        String category = normalize(first(config.getCapabilityCategory(),
            config.getBusinessGroup(), "data_asset_exploration"));
        String original = normalize(first(config.getToolName(), "query"));
        String base = original.startsWith(category + "_")
            ? original
            : category + "_" + removeRedundantCategoryPrefix(
                removeGenericPrefix(original), category);
        if (base.length() <= MAX_TOOL_NAME_LENGTH) {
            return base;
        }
        String suffix = "_" + shortHash(base);
        return base.substring(0, MAX_TOOL_NAME_LENGTH - suffix.length()) + suffix;
    }

    public String title(DatabaseQueryConfig config) {
        String categoryName = first(config.getBusinessGroupName(),
            config.getCapabilityCategory(), config.getBusinessGroup(), "数据资产探索");
        String title = first(config.getTitle(), config.getToolName(), "数据库查询能力");
        String prefix = "【" + categoryName + "】";
        return title.startsWith(prefix) ? title : prefix + title;
    }

    private String removeGenericPrefix(String value) {
        if (value.startsWith("sample_")) return value.substring("sample_".length());
        if (value.startsWith("query_")) return value.substring("query_".length());
        return value;
    }

    private String removeRedundantCategoryPrefix(String value, String category) {
        for (String token : category.split("_")) {
            if (value.startsWith(token + "_")) {
                return value.substring(token.length() + 1);
            }
        }
        return value;
    }

    private String normalize(String value) {
        String normalized = value.toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9_\\-]", "_")
            .replaceAll("_+", "_")
            .replaceAll("^_+|_+$", "");
        return normalized.isBlank() ? "query" : normalized;
    }

    private String shortHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest, 0, 4);
        } catch (Exception ignored) {
            return Integer.toUnsignedString(value.hashCode(), 16);
        }
    }

    private String first(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value.trim();
        }
        return "";
    }
}
