package com.chatchat.api.datascience;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PythonDataScienceFileTypeTest {

    @Test
    void acceptsEverySupportedAnalysisFileExtensionCaseInsensitively() {
        Map<String, String> supported = Map.ofEntries(
            Map.entry("data.csv", "CSV"),
            Map.entry("legacy.XLS", "XLS"),
            Map.entry("workbook.xlsx", "XLSX"),
            Map.entry("records.json", "JSON"),
            Map.entry("notes.txt", "TXT"),
            Map.entry("application.LOG", "LOG"),
            Map.entry("dataset.parquet", "PARQUET"),
            Map.entry("warehouse.orc", "ORC"),
            Map.entry("archive.zip", "ZIP")
        );

        supported.forEach((name, type) -> assertThat(PythonDataScienceService.fileType(name)).isEqualTo(type));
    }

    @Test
    void rejectsFilesOutsideTheAnalysisWhitelist() {
        assertThatThrownBy(() -> PythonDataScienceService.fileType("payload.exe"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("LOG")
            .hasMessageContaining("ORC");
    }
}
