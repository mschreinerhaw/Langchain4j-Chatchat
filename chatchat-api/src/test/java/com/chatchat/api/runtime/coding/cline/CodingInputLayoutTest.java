package com.chatchat.api.runtime.coding.cline;

import com.chatchat.common.runtime.coding.CodingInputManifest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CodingInputLayoutTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = CodingInputManifest.Format.class, names = {"PARQUET", "CSV"})
    void controlMetadataDistinguishesActualManifestBytesAndExternalChecksum(CodingInputManifest.Format format) {
        String manifestId = "c" + "a".repeat(63), schemaId = "c" + "b".repeat(63), partId = "c" + "c".repeat(63);
        String hash = "d".repeat(64);
        var manifest = new CodingInputManifest(CodingInputManifest.VERSION, "source", format, schemaId, hash, 0, 0,
            List.of(new CodingInputManifest.Column("field", "c0", CodingInputManifest.Type.STRING, 0, 0, 0, 0)),
            List.of(new CodingInputManifest.Part(partId, "part-00000." + format.name().toLowerCase(Locale.ROOT), hash, 0, 0)));
        var document = new ObjectMapper().valueToTree(manifest);
        var fields = new ArrayList<String>(); document.fieldNames().forEachRemaining(fields::add);
        var layout = CodingInputLayout.describe(Map.of("inputFiles", descriptor(manifestId, hash, format.name()),
            "rows", List.of("PRIVATE_SOURCE_ROW")));
        assertThat(layout).containsEntry("expectedManifestSha256", hash)
            .containsEntry("manifestPath", "/data/input/" + manifestId + "/manifest.json")
            .containsEntry("manifestRootFields", fields);
        assertThat(document.has("sha256")).isFalse();
        assertThat(document.has("inputManifest")).isFalse();
        assertThat(layout.toString()).doesNotContain("PRIVATE_SOURCE_ROW");
        assertThat(layout.get("reader").toString()).contains("DECLARED_FILES", "No application-specific Python module")
            .doesNotContain("runtime_input", "load_rows");
        assertThat(layout.get("repairGuidance").toString()).contains("NOT the content", "no self", "never replace missing business values");
    }
    @Test void legacyJsonLinesDoesNotReceiveATableManifestHint() {
        assertThat(CodingInputLayout.describe(Map.of())).isEmpty();
    }
    @Test void unsafeFileIdentityAndUnknownFormatCannotProduceAReaderPath() {
        assertThatThrownBy(() -> CodingInputLayout.describe(Map.of("inputFiles", descriptor("../../private", "a".repeat(64), "PARQUET"))))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CodingInputLayout.describe(Map.of("inputFiles", descriptor("c" + "a".repeat(63), "a".repeat(64), "UNKNOWN"))))
            .isInstanceOf(IllegalArgumentException.class);
    }
    private static Map<String, Object> descriptor(String file, String hash, String format) {
        return Map.of("schemaVersion", CodingInputManifest.VERSION, "format", format,
            "manifestFileId", file, "manifestSha256", hash);
    }
}
