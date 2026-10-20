package com.chatchat.chat.asset;

import com.chatchat.agents.runtime.context.SkillAnalysisContext;
import com.chatchat.chat.interaction.model.*;
import com.chatchat.chat.interaction.service.CodingAnswerPort;
import com.chatchat.chat.interaction.service.InteractionExecution;
import com.chatchat.chat.skills.catalog.SkillCatalogService;
import com.chatchat.common.runtime.analysis.asset.*;
import com.chatchat.common.runtime.analysis.model.AnalysisContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.stereotype.Component;

/** Runtime supplies already authorized asset declarations; Coding owns their interpretation. */
@Component
public class PublishedSkillGuidanceEnhancer implements AssetGuidanceEnhancer {
    private final CodingAnswerPort coding;
    private final SkillCatalogService agents;
    private final ObjectMapper mapper;
    public PublishedSkillGuidanceEnhancer(CodingAnswerPort coding, SkillCatalogService agents, ObjectMapper mapper) {
        this.coding = coding; this.agents = agents; this.mapper = mapper;
    }
    @Override public Recommendation recommend(AnalysisContext context, AssetContext asset) {
        var scope = context.kernelScope();
        var internal = new LinkedHashMap<String,Object>();
        if (scope.runId() != null) internal.put("__agentRunId",scope.runId());
        for (String key : List.of("__agentCancellation","__agentDeadlineAt"))
            if (context.attributes().get(key) != null) internal.put(key,context.attributes().get(key));
        var request = InteractionRequest.builder().query(context.query()).tenantId(scope.tenantId())
            .userId(scope.userId()).skillId(context.skillId()).toolInput(internal).build();
        try {
            InteractionExecution.checkCancellation(request);
            String supplied = "Authorized asset metadata only. Explain declared parameters and possible uses; "
                + "distinguish suggestions from verified usage. Missing output contracts do not prove field or metric support. "
                + "No template business execution or usage/quality measurements were delivered.\n"
                + mapper.writeValueAsString(asset);
            var conversation = InteractionContext.builder().requestId(scope.requestId()).conversationId(scope.conversationId())
                .skillAnalysisContext(SkillAnalysisContext.from(context.attributes())).build();
            var answer = coding.answer(request,conversation,agents.resolve(context.skillId()),supplied);
            InteractionExecution.checkCancellation(request);
            if (answer.getAnswer() == null || answer.getAnswer().isBlank()) throw new IllegalStateException("Empty Coding guidance");
            // Delivered Skill candidates are not a claim that Coding activated every one of them.
            return new Recommendation(answer.getAnswer(),List.of(),"CODING_REVIEWED");
        } catch (java.util.concurrent.CancellationException cancelled) { throw cancelled; }
        catch (Exception unavailable) {
            InteractionExecution.propagateCancellation(request,unavailable);
            return new Recommendation("Coding 领域建议暂不可用；已授权的模板元数据仍可使用。",List.of(),"UNAVAILABLE");
        }
    }
}
