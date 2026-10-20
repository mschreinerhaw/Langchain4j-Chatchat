package com.chatchat.api.datascience;

import com.chatchat.agents.model.ConfigurableChatModelFactory;
import com.chatchat.agents.runtime.plan.InterpretationPlan;
import com.chatchat.agents.runtime.plan.InterpretationPlanValidator;
import com.chatchat.agents.runtime.plan.persistence.InterpretationPlanStore;
import com.chatchat.agents.runtime.store.AgentRunStore;
import com.chatchat.common.config.ModelResourceRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Code-development extension of the existing analysis InterpretationPlan, validator and plan store. */
@Service @RequiredArgsConstructor @Slf4j
public class StudioCodePlanService {
    private static final String ORIGIN = "studio-code-request-sha256:";
    private final ChatModel chatModel;
    private final ConfigurableChatModelFactory models;
    private final ModelResourceRegistry resources;
    private final AgentRunStore runStore;
    private final ObjectMapper mapper;
    public record Ticket(String planId, InterpretationPlan interpretationPlan, String status) { }

    public Ticket create(String tenant, String user, String language, String action, String input, String modelName) {
        String selected = modelName == null || modelName.isBlank() ? resources.defaultChatModel() : modelName;
        if (!resources.selectableChatModels().contains(selected)) throw new IllegalArgumentException("所选计划模型不可用");
        return create(tenant, user, language, action, input, selected, null);
    }

    /** Compatibility signature. Runtime admits the request; Cline owns all analytical planning. */
    public Ticket create(String tenant, String user, String language, String action, String input, String modelName, ChatModel ignored) {
        if (tenant == null || tenant.isBlank() || user == null || user.isBlank())
            throw new IllegalArgumentException("Authenticated Studio identity required");
        if (!Set.of("sql", "python").contains(language) || input == null || input.isBlank())
            throw new IllegalArgumentException("Studio task description required");
        String id = "studio-code-" + UUID.randomUUID();
        try {
            var plan = mapper.convertValue(Map.of("version", "1.0",
                "intent", Map.of("type", "code_development", "goal", "将授权代码任务交付 Cline，由 Cline 制定分析与生成方案", "risk_level", "low"),
                "context", Map.of("key_facts", List.of("Language: " + language, "Action: " + action),
                    "assumptions", List.of(), "missing_info", List.of(),
                    "constraints", List.of("analysisDecisionOwner=CLINE", "Runtime admission only; no exploration or execution has occurred",
                        ORIGIN + fingerprint(tenant, user, language, action, input, modelName))),
                "plan", Map.of("steps", List.of(Map.of("id", 1, "action_type", "reasoning",
                    "input", Map.of("description", "Cline 根据授权上下文自主规划、生成代码并处理解析反馈；代码变更等待用户审查"),
                    "depends_on", List.of())))), InterpretationPlan.class);
            validate(plan);
            store().savePlan(tenant, id, id, plan, "ANALYSIS_READY");
            log.info("studio_code_plan phase=READY owner=CLINE modelCalls=0 planId={} language={} action={}", id, language, action);
            return new Ticket(id, plan, "READY");
        } catch (Exception invalid) {
            throw new IllegalStateException("代码任务交付准备失败，尚未开始探索。请求编号：" + id, invalid);
        }
    }

    public Ticket require(String tenant, String user, String language, String action, String input, String modelName, String id) {
        String expected = ORIGIN + fingerprint(tenant, user, language, action, input, modelName);
        var record = store().getSnapshot(tenant, id).orElseThrow(() -> new IllegalArgumentException("代码计划不存在或已失效，请重新制定计划"));
        if (!id.startsWith("studio-code-") || !id.equals(record.planId()) || !"ANALYSIS_READY".equals(record.status())
            || record.createdAt() == null || System.currentTimeMillis() - record.createdAt() > 30 * 60_000)
            throw new IllegalArgumentException("代码计划已失效，请重新制定计划");
        try {
            var plan = mapper.readValue(record.planJson(), InterpretationPlan.class);
            validate(plan);
            if (!plan.context().constraints().contains(expected)) throw new IllegalArgumentException("代码、需求、模型或身份已变化，请重新制定计划");
            return new Ticket(id, plan, "READY");
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) { throw new IllegalStateException("无法恢复代码计划", invalid); }
    }

    public String context(Ticket ticket) {
        try { return "\nRuntime code task admission (not an analytical plan or execution authority). "
            + "Cline owns the analysis plan, method selection, code generation and repair. "
            + "This delivery outline does not prescribe a fixed tool order or prove exploration or validation.\n"
            + mapper.writeValueAsString(ticket.interpretationPlan()); }
        catch (Exception invalid) { throw new IllegalStateException("无法读取代码计划", invalid); }
    }
    private void validate(InterpretationPlan plan) {
        var result = new InterpretationPlanValidator().validateAnalysisPlan(plan);
        if (!result.valid() || plan.steps().size() > 16) throw new IllegalArgumentException("Invalid code InterpretationPlan");
        for (var step : plan.steps()) {
            if (!Set.of("reasoning", "retrieval", "aggregation", "validation").contains(step.actionType()) || step.toolName() != null
                || !(step.input().get("description") instanceof String description) || description.isBlank() || description.length() > 1000)
                throw new IllegalArgumentException("Invalid code analysis step");
        }
    }
    private InterpretationPlanStore store() {
        if (runStore instanceof InterpretationPlanStore store) return store;
        throw new IllegalStateException("当前 Runtime 未配置解释计划存储，尚未开始探索");
    }
    private String fingerprint(String tenant, String user, String language, String action, String input, String modelName) {
        try {
            String value = mapper.writeValueAsString(Arrays.asList(tenant, user, language, action, input, modelName));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception invalid) { throw new IllegalStateException("无法绑定代码计划上下文", invalid); }
    }
}
