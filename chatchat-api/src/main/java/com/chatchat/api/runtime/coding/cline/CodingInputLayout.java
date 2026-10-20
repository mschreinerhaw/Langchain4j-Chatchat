package com.chatchat.api.runtime.coding.cline;

import com.chatchat.common.runtime.coding.CodingInputManifest;
import java.util.Arrays;
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
            "reader", Map.of("module", "runtime_input", "function", "load_rows",
                "usage", "from runtime_input import load_rows; rows = list(load_rows(manifestPath, expectedManifestSha256))",
                "returns", "logical dictionaries; JSON decoded; missing/null distinct; exact integer/decimal types"),
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
