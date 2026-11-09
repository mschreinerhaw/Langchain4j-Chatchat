package com.chatchat.api.runtime.coding.analysis;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CodingRequiredArtifactsTest {
    @Test void existingContractAndExplicitDeliverablesRemainDistinct() {
        assertThat(CodingRequiredArtifacts.files(null)).containsExactly("rows.jsonl");
        assertThat(CodingRequiredArtifacts.files(List.of("report.md","chart.png","analysis.xlsx","rows.jsonl")))
            .containsExactly("rows.jsonl","report.md","chart.png","analysis.xlsx");
    }
    @Test void filenamesCannotExpandTheArchiveBoundary() {
        for(Object value:List.of(List.of("../report.md"),List.of("results/report.md"),List.of("secret.txt"),
                List.of("report.md","report.md"),"report.md"))
            assertThatThrownBy(()->CodingRequiredArtifacts.read(value)).isInstanceOf(IllegalArgumentException.class);
    }
}
