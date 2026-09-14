package com.chatchat.agents.orchestration.analysis.dataset;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DatasetReferenceSequenceTest {

    @Test
    void disambiguatesDuplicatesWithoutCollidingWithSourceProvidedOccurrenceNames() {
        List<String> source = List.of("dataset", "dataset", "dataset#occurrence-2");
        DatasetReferenceSequence references = new DatasetReferenceSequence(source);

        List<String> generated = source.stream().map(references::next).toList();

        assertThat(generated).doesNotHaveDuplicates();
        assertThat(generated).contains("dataset", "dataset#occurrence-2");
        assertThat(generated.get(1)).startsWith("dataset#occurrence-2#runtime-");
    }
}
