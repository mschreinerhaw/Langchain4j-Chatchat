package com.chatchat.common.runtime.summary.analysis.contract;

import java.io.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AnalysisAcceptanceContractTest {
    @Test void policySurvivesGraphCheckpointSerialization() throws Exception {
        var contract = AnalysisAcceptanceContract.standard().toMap();
        var buffer = new ByteArrayOutputStream();
        try (var output = new ObjectOutputStream(buffer)) { output.writeObject(contract); }
        try (var input = new ObjectInputStream(new ByteArrayInputStream(buffer.toByteArray()))) {
            assertThat(input.readObject()).isEqualTo(contract);
        }
    }
}
