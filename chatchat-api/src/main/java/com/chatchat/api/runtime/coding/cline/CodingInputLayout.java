package com.chatchat.api.runtime.coding.cline;

import com.chatchat.common.runtime.coding.CodingInputManifest;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Structural file-protocol guidance only; never includes source rows or execution authority. */
final class CodingInputLayout {
    private CodingInputLayout() { }

    static Map<String, Object> describe(Map<String, Object> context) {
        if (!(context.get("inputFiles") instanceof Map<?, ?> files)) return Map.of();
        if (!CodingInputManifest.VERSION.equals(files.get("schemaVersion"))
                || !Set.of("PARQUET", "CSV").contains(files.get("format")))
            throw new IllegalArgumentException("Unsupported immutable input layout");
        String fileId = (String) files.get("manifestFileId");
        String hash = (String) files.get("manifestSha256");
        CodingInputManifest.fileId(fileId);
        CodingInputManifest.hash(hash);
        return Map.of("schemaVersion", "runtime_os.coding.input-layout.v1",
            "reader", Map.of("type", "VERIFIED_TRANSPORT_READER", "module", "runtime_input",
                "functions", Map.of("load_rows",List.of("manifestPath","expectedManifestSha256"),
                    "load_native_rows",List.of("manifestPath","expectedManifestSha256","native_file_id")),
                "usage", "Choose the authorized input and, for an original Parquet, its native_file_id; use these installed transport readers to obtain logical dictionaries without regenerating transport decoding. Consume the iterator fully for row-count validation. Both verify checksums and preserve missing/null, JSON and Decimal values; neither selects data or performs analysis. Native file IDs come from this manifest's nativeFiles, never from an uploaded original manifest. Other original formats or Parquet without a bound original manifest use installed format readers.",
                "columnMappingScope", "MANIFEST_PARTS_ONLY",
                "nativeSchemaLocation", "schema.json/nativeFileSchemas, matched by native fileId; sourceManifest binds an original column map to its exact part SHA-256, size and row count",
                "logicalColumns", "The input manifest columns describe only its own parts, NEVER a nativeFiles original. For a native Parquet use its own sourceManifest.columns when status=BOUND in nativeFileSchemas; otherwise inspect that original's own schema. Decode JSON cells and preserve missing/null and exact numeric types."),
            "manifestPath", "/data/input/" + fileId + "/manifest.json",
            "expectedManifestSha256", hash,
            "manifestRootFields", Arrays.stream(CodingInputManifest.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).toList(),
            "partPathPattern", "/data/input/{part.fileId}/{part.fileName}",
            "repairGuidance", "The control-plane input descriptor is NOT the content of manifest.json. "
                + "The file contains the flat manifest object: use manifest['columns'], manifest['parts'] and manifest['rowCount']. "
                + "It has no 'inputManifest' wrapper and no self 'sha256' field. "
                + "Verify the manifest bytes against expectedManifestSha256 supplied here, then verify each part against part['sha256']. "
                + "All file IDs are sibling directories under /data/input. "
                + "A KeyError at an input-reading line requires correcting the reader against these protocol fields; "
                + "never replace missing business values with defaults or skip checksum verification.");
    }
}
