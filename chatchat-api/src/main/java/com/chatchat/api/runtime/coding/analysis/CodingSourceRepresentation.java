package com.chatchat.api.runtime.coding.analysis;

import java.util.Map;
import java.util.List;
import java.util.ArrayList;

/** Producer-declared transport semantics only; no source values or business interpretation. */
final class CodingSourceRepresentation {
    private CodingSourceRepresentation() { }

    static Map<String, Object> describe(Map<String, Object> context) {
        if (context.get("inputVersions") instanceof List<?> versions) {
            List<Map<String, Object>> sources = new ArrayList<>();
            for (Object value : versions) {
                if (!(value instanceof Map<?, ?> version)
                    || !(version.get("datasetReference") instanceof String reference)
                    || !(version.get("sourceRepresentation") instanceof Map<?, ?> representation)) continue;
                Map<String, Object> declared = new java.util.HashMap<>();
                for (String key : List.of("contentType", "recordMeaning", "stream"))
                    if (representation.get(key) instanceof String text) declared.put(key, text);
                Map<String, Object> hint = describe(declared);
                if (!hint.isEmpty()) sources.add(Map.of("datasetReference", reference, "representation", hint));
            }
            if (sources.isEmpty()) return Map.of();
            return Map.of("schemaVersion", "runtime_os.coding.source-representation-set.v1",
                "datasets", List.copyOf(sources), "reconstruction",
                "Group logical rows by their exact datasetReference first. Reconstruct and decode each group's record content "
                    + "independently using its declared representation before joining or analyzing datasets. "
                    + "Do not concatenate different datasets or apply a source format to undeclared sources.");
        }
        Object mime = context.get("contentType");
        boolean chunks = "CONTENT_CHUNK_NOT_BUSINESS_ROW".equals(context.get("recordMeaning"));
        boolean stream = "stdout".equals(context.get("stream")) || "stderr".equals(context.get("stream"));
        if (!(chunks || stream) || !("application/json".equals(mime) || "text/plain".equals(mime))) return Map.of();
        return Map.of("schemaVersion", "runtime_os.coding.source-representation.v1",
            "contentType", mime, "recordMeaning", "CONTENT_CHUNK_NOT_BUSINESS_ROW",
            "contentField", "content", "orderField", "chunkIndex",
            "reconstruction", "Order all chunks, verify their fromChar/toChar coverage and concatenate content without inserting separators. "
                + ("application/json".equals(mime)
                    ? "Decode the reconstructed text as JSON first. It is a serialized governed tool result, not a raw command table. "
                        + "Inspect its object/array structure inside Python and locate the declared data or stream before interpreting it. "
                    : "Interpret the reconstructed text according to the declared source format; do not assume a table layout. ")
                + "Chunk counts are not business record counts. Preserve source completeness and never treat failed parsing as an empty source.");
    }
}
