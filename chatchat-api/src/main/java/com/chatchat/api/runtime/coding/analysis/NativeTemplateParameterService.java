package com.chatchat.api.runtime.coding.analysis;

import com.chatchat.agents.orchestration.tool.ClineTemplateParameterPort;
import com.chatchat.agents.orchestration.retrieval.ClineDiscoveryPort;
import com.chatchat.agents.runtime.toolcall.TemplateInvocationBridge;
import com.chatchat.agents.runtime.toolcall.RequiredTemporalArgumentGrounding;
import com.chatchat.api.assets.UserAssetService;
import com.chatchat.api.runtime.coding.CodingInvocationContext;
import com.chatchat.api.runtime.coding.nativecline.NativeCodingTasks;
import com.chatchat.common.kernel.KernelDataScope;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import static com.chatchat.agents.runtime.coding.CodingTaskAccess.Action.*;

@Service
@ConditionalOnProperty(prefix = "chatchat.runtime.coding.unified", name = "enabled", havingValue = "true")
public final class NativeTemplateParameterService implements ClineTemplateParameterPort, ClineDiscoveryPort {
    private static final int MAX_BYTES = 96 * 1024;
    private final NativeCodingTasks coding;
    private final UserAssetService assets;
    private final CodingInvocationContext identity;
    private final ObjectMapper json;
    private final Set<String> agents;

    public NativeTemplateParameterService(NativeCodingTasks coding, UserAssetService assets,
            CodingInvocationContext identity, ObjectMapper json,
            @Value("${chatchat.coding.analysis.cline-parameter-agents:}") String agents) {
        this.coding = coding;
        this.assets = assets;
        this.identity = identity;
        this.json = json;
        this.agents = new HashSet<>(List.of(agents.trim().split("\\s*,\\s*")));
        this.agents.remove("");
    }

    @Override
    public boolean enabledFor(String agentId) {
        return agents.contains(agentId);
    }

    @Override
    public List<Map<String, Object>> propose(KernelDataScope scope, String modelName, int discoveryStepId,
            String query, List<Map<String, Object>> templates, Selection selection, BooleanSupplier cancelled) {
        var result = runProposal(scope, modelName, discoveryStepId, "template-parameter-extraction",
            "template-input.json", "template-parameters.json", Map.of("question", query, "templates", templates,
                "selection", selection.name()), cancelled);
        return protocols(result, templates, selection);
    }

    @Override
    public Map<String, Object> propose(KernelDataScope scope, String modelName, int stepId, Stage stage,
            Map<String, Object> input, BooleanSupplier cancelled) {
        var taskInput = new java.util.LinkedHashMap<String, Object>(input);
        taskInput.put("stage", stage.name());
        var result = runProposal(scope, modelName, stepId, "opensearch-asset-discovery",
            "discovery-input.json", "asset-discovery.json", taskInput, cancelled);
        if (result == null || !result.isObject()) throw new IllegalStateException("Invalid discovery proposal");
        return json.convertValue(result, new TypeReference<Map<String, Object>>() {});
    }

    private JsonNode runProposal(KernelDataScope scope, String modelName, int stepId, String skillName,
            String inputName, String outputName, Map<String, Object> inputData, BooleanSupplier cancelled) {
        if (scope == null || scope.tenantId() == null || scope.userId() == null || scope.runId() == null)
            throw new SecurityException("Authenticated parent task required");
        var ownerScope = new KernelDataScope(scope.tenantId(), scope.userId(), scope.requestId(),
            scope.conversationId(), scope.runId(), scope.environment(), Map.of());
        return identity.as(ownerScope, Set.of(READ, SUBMIT, STOP), () -> {
            try {
                byte[] descriptors = json.writeValueAsBytes(inputData);
                if (descriptors.length > MAX_BYTES) throw new IllegalArgumentException("Parameter task input too large");
                String requestId = skillName + "-" + stepId + "-" + UserAssetService.hash(descriptors);
                var existing = coding.snapshot(ownerScope, requestId);
                String task = coding.taskIdentity(ownerScope, requestId);
                if (existing.isEmpty()) {
                    var owner = UserAssetService.Owner.of(ownerScope);
                    byte[] skill;
                    try (var stream = new ClassPathResource("skills/" + skillName + "/SKILL.md").getInputStream()) {
                        skill = stream.readAllBytes();
                    }
                    var input = assets.store(owner, UserAssetService.Kind.CONTEXT, task, scope.runId(),
                        requestId + ":input", inputName, "application/json", descriptors);
                    var guide = assets.store(owner, UserAssetService.Kind.CONTEXT, task, scope.runId(),
                        requestId + ":skill", "SKILL.md", "text/markdown", skill);
                    String instruction = "Read the supplied SKILL.md and " + inputName + "."
                        + temporalReference(skillName, inputData.get("question"))
                        + " Follow the skill and deliver only its RESULT.";
                    task = coding.submit(ownerScope, requestId, List.of(input.id(), guide.id()), instruction,
                        modelName, List.of("RESULT"), Map.of()).taskId();
                }
                long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(3);
                var result = coding.await(ownerScope, task, () -> cancelled.getAsBoolean() || System.nanoTime() >= deadline);
                if (!"COMPLETED".equals(result.get("status")) || !(result.get("artifacts") instanceof List<?> artifacts))
                    throw new IllegalStateException("Cline did not deliver template parameters");
                var matches = artifacts.stream().filter(UserAssetService.File.class::isInstance)
                    .map(UserAssetService.File.class::cast).filter(file -> outputName.equals(file.fileName())).toList();
                if (matches.size() != 1 || matches.get(0).size() > MAX_BYTES)
                    throw new IllegalStateException("Expected one bounded parameter artifact");
                return json.readTree(assets.readTask(ownerScope, task, matches.get(0).id()));
            } catch (IOException invalid) {
                throw new IllegalStateException("Invalid Cline parameter artifact", invalid);
            } catch (java.util.concurrent.CancellationException stopped) {
                if (cancelled.getAsBoolean()) throw stopped;
                throw new IllegalStateException("Cline parameter task exceeded its wait budget", stopped);
            }
        });
    }

    static String temporalReference(String skillName, Object question) {
        if (!"template-parameter-extraction".equals(skillName) || !(question instanceof String query)
            || !RequiredTemporalArgumentGrounding.hasRelativeTimeExpression(query)) return "";
        ZoneId zone = ZoneId.systemDefault();
        return " Interpret only the explicit relative time in the question using " + LocalDate.now(zone)
            + " in " + zone + ".";
    }

    List<Map<String, Object>> protocols(JsonNode result, List<Map<String, Object>> templates, Selection selection) {
        if (result == null || !result.isObject() || result.size() != 1 || !result.path("parameterProtocols").isArray())
            throw new IllegalArgumentException("Invalid parameter proposals");
        Set<String> remaining = new HashSet<>();
        templates.forEach(template -> remaining.add(template.get("templateId").toString()));
        List<Map<String, Object>> proposals = new ArrayList<>();
        for (var proposal : result.path("parameterProtocols")) {
            String id = proposal.path("template_id").asText();
            if (!proposal.isObject() || proposal.size() != 4 || !remaining.remove(id)
                || !TemplateInvocationBridge.PROTOCOL_VERSION.equals(proposal.path("protocol_version").asText())
                || !proposal.path("arguments").isObject() || !proposal.path("unresolved_parameters").isArray())
                throw new IllegalArgumentException("Invalid or unauthorized template parameter proposal");
            for (var argument : proposal.path("arguments")) {
                var evidence = argument.path("evidence");
                if (!argument.isObject() || argument.size() != 3 || !argument.hasNonNull("value")
                    || !TemplateInvocationBridge.USER_QUERY_SOURCE.equals(argument.path("source").asText())
                    || !evidence.path("quote").isTextual() || evidence.path("quote").asText().isBlank()
                    || evidence.size() != (evidence.has("interpretation") ? 2 : 1)
                    || (evidence.has("interpretation") && !"model".equals(evidence.path("interpretation").asText())))
                    throw new IllegalArgumentException("Invalid parameter provenance");
            }
            for (var unresolved : proposal.path("unresolved_parameters"))
                if (!unresolved.isTextual() || unresolved.asText().isBlank())
                    throw new IllegalArgumentException("Invalid unresolved parameter");
            proposals.add(json.convertValue(proposal, new TypeReference<Map<String, Object>>() {}));
        }
        if (selection == Selection.ALL_PUBLISHED && !remaining.isEmpty())
            throw new IllegalArgumentException("Missing proposals for published templates");
        return List.copyOf(proposals);
    }
}
