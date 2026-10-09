package com.chatchat.mcpserver.sql.metadata;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** Physical identifiers retain their spelling and hierarchy, including catalog and nested fields. */
public record MetadataObject(String datasourceId, String databaseType, String kind,
                             String namespace, String name, List<String> path, String description,
                             List<Field> fields, Map<String, Object> attributes) {
    public MetadataObject {
        path = path == null ? List.of() : List.copyOf(path);
        fields = fields == null ? List.of() : fields.stream().sorted(java.util.Comparator.comparing(Field::name)
            .thenComparing(field -> java.util.Objects.toString(field.nativeType(), ""))
            .thenComparing(field -> field.attributes().toString())).toList();
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    public String qualifiedName() { return String.join(".", path); }

    public String id() {
        // Length-prefixed components also distinguish identifiers containing dots or colons.
        StringBuilder key = new StringBuilder();
        for (String part : java.util.stream.Stream.concat(
            java.util.stream.Stream.of(datasourceId, databaseType, kind), path.stream()).toList()) {
            String value = part == null ? "" : part;
            key.append(value.length()).append(':').append(value);
        }
        return "metadata_object:" + digest(key.toString());
    }

    public String indexText() {
        return qualifiedName() + " " + kind + " " + (description == null ? "" : description) + " "
            + fields.stream().map(field -> field.name() + " " + field.nativeType() + " "
                + (field.description() == null ? "" : field.description())).collect(java.util.stream.Collectors.joining(" "));
    }

    static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }

    public record Field(String name, List<String> path, String nativeType, Boolean nullable,
                        String description, Map<String, Object> attributes) {
        public Field {
            path = path == null ? List.of() : List.copyOf(path);
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        }
    }
}
