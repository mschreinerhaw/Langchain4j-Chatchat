package com.chatchat.mcpserver.search.index;

import com.chatchat.mcpserver.sql.metadata.MetadataColumn;
import com.chatchat.mcpserver.sql.resolution.TableLocation;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Search-only semantic projection built from governed metadata; it contains no sampled data. */
public record AssetSemanticProfile(
    String assetKind,
    List<String> descriptions,
    List<FieldProfile> fields,
    List<String> contractCapabilities
) {
    public static final int MAX_PROFILE_FIELDS = 256;
    private static final int MAX_INDEX_TEXT_CHARS = 24_000;

    public AssetSemanticProfile {
        descriptions = immutable(descriptions);
        fields = fields == null ? List.of() : List.copyOf(fields);
        contractCapabilities = immutable(contractCapabilities);
    }

    public static AssetSemanticProfile table(TableLocation table,
                                             List<MetadataColumn> columns,
                                             String... datasourceDescriptions) {
        List<String> descriptions = new ArrayList<>();
        add(descriptions, table == null ? null : table.databaseComment());
        add(descriptions, table == null ? null : table.tableComment());
        if (datasourceDescriptions != null) {
            for (String description : datasourceDescriptions) add(descriptions, description);
        }
        List<FieldProfile> fields = columns == null ? List.of() : columns.stream()
            .filter(column -> column != null && column.name() != null && !column.name().isBlank())
            .limit(MAX_PROFILE_FIELDS)
            .map(column -> new FieldProfile(column.name(), column.comment(), column.dataType(), column.columnKey()))
            .toList();
        return new AssetSemanticProfile("table", descriptions, fields,
            table == null ? List.of() : List.of(safe(table.tableType())));
    }

    public static AssetSemanticProfile api(String... contractTexts) {
        List<String> values = new ArrayList<>();
        if (contractTexts != null) {
            for (String value : contractTexts) add(values, value);
        }
        return new AssetSemanticProfile("api", List.of(), List.of(), values);
    }

    public String indexText() {
        Set<String> values = new LinkedHashSet<>();
        add(values, assetKind);
        descriptions.forEach(value -> add(values, value));
        fields.forEach(field -> {
            add(values, field.name());
            add(values, field.comment());
            add(values, field.dataType());
            add(values, field.role());
        });
        contractCapabilities.forEach(value -> add(values, value));
        String result = String.join(" ", values);
        return result.length() <= MAX_INDEX_TEXT_CHARS ? result : result.substring(0, MAX_INDEX_TEXT_CHARS);
    }

    private static <T> List<T> immutable(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private static void add(java.util.Collection<String> values, String value) {
        if (value != null && !value.isBlank()) values.add(value.trim());
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    public record FieldProfile(String name, String comment, String dataType, String role) {
    }
}
