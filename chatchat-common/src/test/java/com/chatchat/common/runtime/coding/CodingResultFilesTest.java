package com.chatchat.common.runtime.coding;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class CodingResultFilesTest {
    @Test void canonicalArtifactOrderingDoesNotChangeThePrimaryDataset() {
        assertThat(CodingProtocol.primaryResultFile(List.of("analysis.py","chart.png","report.md","rows.jsonl"))).isEqualTo("rows.jsonl");
        assertThat(CodingProtocol.primaryResultFile(List.of("output.jsonl"))).isEqualTo("output.jsonl");
        assertThat(CodingProtocol.primaryResultFile(List.of("details.jsonl","rows.jsonl"))).isEqualTo("rows.jsonl");
    }
    @Test void missingOrAmbiguousPrimaryIsRejected() {
        for(var files:List.of(List.of("report.md"),List.of("a.jsonl","b.jsonl")))
            assertThatThrownBy(()->CodingProtocol.primaryResultFile(files)).isInstanceOf(IllegalArgumentException.class);
    }
}
