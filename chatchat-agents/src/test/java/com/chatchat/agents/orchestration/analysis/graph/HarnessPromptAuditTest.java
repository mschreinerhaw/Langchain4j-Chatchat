package com.chatchat.agents.orchestration.analysis.graph;

import com.chatchat.agents.orchestration.analysis.dataset.AnalysisEvidenceCoordinator.Dataset;
import com.chatchat.agents.protocol.ModelProtocolJson;
import com.chatchat.agents.runtime.analysis.AnalysisEvidenceSpillStore;
import com.chatchat.agents.runtime.context.SkillAnalysisContext;
import com.chatchat.agents.runtime.governance.GovernanceIsolationScope;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

/** Captures the exact strings given to ChatModel.chat, including all injected layers and visualization contracts. */
class HarnessPromptAuditTest {
    @Test void measuresActualRequestsWithFixedDataAndGuidance() throws Exception {
        String phase = System.getProperty("harness.audit.phase", "after");
        var prompts = new ArrayList<String>();
        var metadata = new LinkedHashMap<String,Object>();
        metadata.put("modelAnalysisProtocol", "model_native_analysis.v2");
        metadata.put("harnessLegacyTextTransport", true);
        metadata.put("analysisInterpretationPlanValidated", true);
        metadata.put("analysisInterpretationPlan", Map.of("version", "1.0", "intent", Map.of("type", "analysis", "goal", "Inspect authorized evidence"),
            "context", Map.of("constraints", List.of("Use authorized evidence only")),
            "steps", List.of(Map.of("id", 1, "actionType", "reasoning", "dependsOn", List.of()))));
        metadata.put("skillAnalysisContext", SkillAnalysisContext.create("APPLIED", List.of(Map.of("id", "method-1")),
            Map.of("PLAN", List.of("Planning method ".repeat(50)), "ACQUISITION", List.of("Acquisition method ".repeat(50)),
                "ANALYSIS", List.of("Distinguish observations and hypotheses"), "REPORT", List.of("State evidence limits"))));
        var model = mock(ChatModel.class);
        when(model.chat(anyString())).thenAnswer(call -> {
            prompts.add(call.getArgument(0));
            return prompts.size() == 1 ? ModelProtocolJson.compact(Map.of("schemaVersion", "model_native_analysis.v2",
                "decision", Map.of("action", "CONTINUE"), "output_type", "intermediate", "content", "Recorded work",
                "plan", HarnessProtocolBoundaryTest.plan(),
                "evidenceRequests", List.of(Map.of("operation", "READ_TEXT", "datasetReference", "source", "record", 1, "field", "text", "fromChar", 0, "maxChars", 3000))))
                : ModelProtocolJson.compact(Map.of("schemaVersion", "model_native_analysis.v2", "decision", Map.of("action", "COMPLETE"),
                    "output_type", "final", "content", "Supported answer"));
        });
        new ModelNativeAnalysisHarness(3).execute("Inspect authorized evidence", List.of(new Dataset("source", Map.of("unit", "text", "source", "fixture"),
            List.of(Map.of("text", "Evidence ".repeat(600))))), model,
            GovernanceIsolationScope.runtime("tenant", "user", "audit-run", "request", "session"),
            AnalysisEvidenceSpillStore.disabled(), metadata, () -> {}, event -> {});
        assertThat(prompts).hasSize(2);
        Path dir = Path.of("..", "target", "codex-live", "runtime-protocol-audit-20261012"); Files.createDirectories(dir);
        var measures = new ArrayList<Map<String,Object>>();
        for (int i = 0; i < prompts.size(); i++) {
            String prompt = prompts.get(i); Files.writeString(dir.resolve(phase + "-turn-" + (i + 1) + ".txt"), prompt);
            measures.add(Map.of("turn", i + 1, "utf16Chars", prompt.length(), "utf8Bytes", prompt.getBytes(StandardCharsets.UTF_8).length,
                "staticChars", ModelAnalysisPrompt.CORE.length() + ModelAnalysisPrompt.protocol(true).length()));
        }
        Files.writeString(dir.resolve(phase + "-sizes.json"), ModelProtocolJson.compact(measures));
    }
}
