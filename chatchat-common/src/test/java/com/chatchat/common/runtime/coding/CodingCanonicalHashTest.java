package com.chatchat.common.runtime.coding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import static com.chatchat.common.runtime.coding.CodingContractTestSupport.*;
import static org.assertj.core.api.Assertions.*;

class CodingCanonicalHashTest {
    @TempDir Path temporary;

    @Test void javaAndNodeMatchIndependentGoldenBytesAndSha256() throws Exception {
        ObjectNode task = fixture("task");
        JsonNode golden = fixture("canonical-vector");
        CodingTaskContract contract = JSON.treeToValue(task, CodingTaskContract.class);
        assertThat(CodingCanonicalHash.canonicalJson(contract)).isEqualTo(golden.get("canonicalJson").asText());
        assertThat(CodingCanonicalHash.sha256(contract)).isEqualTo(golden.get("sha256").asText());
        assertBothLanguages(task);
    }

    @Test void orderingExtensionsDatesAndSafeIntegerBoundaryMatchAcrossLanguages() throws Exception {
        ObjectNode task = fixture("task");
        ObjectNode reordered = JSON.createObjectNode();
        var names = new ArrayList<String>(); task.fieldNames().forEachRemaining(names::add); Collections.reverse(names);
        names.forEach(name -> reordered.set(name, task.get(name)));
        var metadata = (ArrayNode) reordered.get("metadataRefs");
        JsonNode first = metadata.get(0); metadata.set(0, metadata.get(1)); metadata.set(1, first);
        set(reordered, "/budget/deadlineAt", "2026-10-15T12:00:00.000Z");
        reordered.put("optionalFutureField", "not part of v1 hash");
        set(reordered, "/scope/tenantId", "  tenant-a  ");
        ((ObjectNode) reordered.get("objective")).put("optionalHint", "ignored");
        assertThat(CodingCanonicalHash.sha256(JSON.treeToValue(reordered, CodingTaskContract.class)))
            .isEqualTo(fixture("canonical-vector").get("sha256").asText());
        assertBothLanguages(reordered);
        set(reordered, "/budget/maxOutputBytes", CodingProtocol.MAX_SAFE_INTEGER);
        set(reordered, "/budget/maxCostAmount", "12345678901234567890.000000000000000001");
        set(reordered, "/budget/deadlineAt", "2026-10-15T12:00:00.123Z");
        set(reordered, "/metadataRefs", java.util.List.of("metadata:\ue000", "metadata:🧮", "metadata:alpha"));
        assertBothLanguages(reordered);
    }

    private void assertBothLanguages(ObjectNode task) throws Exception {
        Path script = Path.of(getClass().getResource("/runtime/coding/canonical-hash.mjs").toURI());
        Path input = temporary.resolve("input.json"), output = temporary.resolve("node-output.json"), errors = temporary.resolve("node-error.log");
        Files.writeString(input, JSON.writeValueAsString(task), StandardCharsets.UTF_8);
        Process process = new ProcessBuilder(System.getProperty("coding.node", "node"), script.toString(), input.toString())
            .redirectOutput(output.toFile()).redirectError(errors.toFile()).start();
        if (!process.waitFor(15, TimeUnit.SECONDS)) { process.destroyForcibly(); fail("Node canonical test timed out"); }
        assertThat(process.exitValue()).as(Files.readString(errors)).isZero();
        JsonNode node = JSON.readTree(Files.readString(output));
        CodingTaskContract contract = JSON.treeToValue(task, CodingTaskContract.class);
        assertThat(node.get("canonicalJson").asText()).isEqualTo(CodingCanonicalHash.canonicalJson(contract));
        assertThat(node.get("sha256").asText()).isEqualTo(CodingCanonicalHash.sha256(contract));
    }
}
