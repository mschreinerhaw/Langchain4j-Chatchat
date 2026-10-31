package com.chatchat.chat.skills.runtime;

import com.chatchat.agents.model.ConfigurableChatModelFactory;
import com.chatchat.agents.runtime.context.SkillAnalysisContext;
import com.chatchat.agents.runtime.event.*;
import com.chatchat.chat.interaction.model.InteractionRequest;
import com.chatchat.chat.interaction.service.InteractionExecution;
import com.chatchat.chat.skills.model.SkillDefinition;
import com.chatchat.runtime.skill.api.discovery.SkillSearchRequest;
import com.chatchat.runtime.skill.api.identity.SkillRoleContext;
import com.chatchat.runtime.skill.port.inbound.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** Authorized, versioned Skill delivery only. Cline selects methods during its existing analysis loop. */
@Component
@lombok.extern.slf4j.Slf4j
public final class SkillAnalysisContextService {
    private static final int MAX_SKILLS = 6;
    private final SkillRouter router;
    private final ObjectProvider<AgentRunEventPublisher> publishers;
    // Constructor compatibility with existing integrations; no model is created or called here.
    public SkillAnalysisContextService(SkillRouter router, SkillResolver resolver, ConfigurableChatModelFactory models,
                                       ObjectMapper mapper, ObjectProvider<AgentRunEventPublisher> publishers) {
        this.router = router; this.publishers = publishers;
    }
    public Map<String,Object> prepare(InteractionRequest request, SkillDefinition agent, List<String> roles) {
        return prepare(request,agent,roles,null);
    }
    public Map<String,Object> prepare(InteractionRequest request, SkillDefinition agent, List<String> roles,
                                     com.chatchat.chat.interaction.model.InteractionContext conversation) {
        InteractionExecution.checkCancellation(request);
        var identity = new SkillRoleContext(request.getTenantId(),request.getUserId(),roles,List.of(),Map.of("agentId",agent.id(),"skillPurpose","AGENT_NATIVE"));
        Object bound = agent.workflowConfig() == null ? null : agent.workflowConfig().get("boundDomainSkillIds");
        List<String> ids = bound instanceof List<?> list ? list.stream().filter(String.class::isInstance).map(String.class::cast).toList() : List.of();
        publish(request,"RUNNING",Map.of());
        if (ids.isEmpty()) return completed(request,SkillAnalysisContext.create("NO_CANDIDATES",List.of(),Map.of()),agent);
        try {
            // Explicit Agent bindings are a capability catalog, not a task-time
            // semantic selection. Avoid requiring the search index for known IDs.
            var routed = router.route(new SkillSearchRequest("",identity,ids,20,Map.of()));
            if (routed.candidates().isEmpty()) return completed(request,SkillAnalysisContext.create(
                "SOURCE_FAILED".equals(routed.status()) ? "UNAVAILABLE" : "NO_CANDIDATES",List.of(),Map.of()),agent);
            var delivered = new ArrayList<Map<String,Object>>();
            boolean limited = routed.candidates().size() > MAX_SKILLS || "PARTIAL_SOURCE_FAILURE".equals(routed.status());
            for (var descriptor : routed.candidates().stream().limit(MAX_SKILLS).toList()) {
                if (!ids.contains(descriptor.id())) continue;
                InteractionExecution.checkCancellation(request);
                /* Binding registers L1 capabilities only. Instructions and resources are
                   resolved by the authenticated Coding callback when Cline requests them. */
                delivered.add(Map.of("id", descriptor.id(), "version", descriptor.version(),
                    "name", Objects.toString(descriptor.metadata().get("nativeName"),descriptor.name()), "description", descriptor.description(),
                    "category", descriptor.category(), "agentId", agent.id(),
                    "selectionOwner", "CLINE", "contentDelivery", "ON_DEMAND"));
            }
            InteractionExecution.checkCancellation(request);
            return completed(request,SkillAnalysisContext.create(limited ? "AVAILABLE_WITH_LIMITS" : "AVAILABLE",delivered,Map.of()),agent);
        } catch (java.util.concurrent.CancellationException cancelled) {
            publish(request,"CANCELLED",Map.of()); throw cancelled;
        } catch (Exception failure) {
            InteractionExecution.propagateCancellation(request,failure);
            log.warn("Skill delivery unavailable agentId={} failureType={}",agent.id(),failure.getClass().getSimpleName());
            return completed(request,SkillAnalysisContext.create("UNAVAILABLE",List.of(),Map.of()),agent);
        }
    }
    public static List<String> contentChunks(String text) {
        var result = new ArrayList<String>();
        for (int from = 0; from < text.length();) {
            int to = Math.min(from+8000,text.length());
            if (to < text.length() && Character.isHighSurrogate(text.charAt(to-1))) to--;
            result.add(text.substring(from,to)); from = to;
        }
        return List.copyOf(result);
    }
    private Map<String,Object> completed(InteractionRequest request,Map<String,Object> context,SkillDefinition agent) {
        context=SkillAnalysisContext.withBuiltinPolicy(context,agent.id(),BuiltinCodingSkills.disabled(
            agent.workflowConfig()==null?null:agent.workflowConfig().get("disabledBuiltinSkillIds")));
        publish(request,"COMPLETED",context); return context;
    }
    private void publish(InteractionRequest request,String state,Map<String,Object> context) {
        Object id = request.getToolInput() == null ? null : request.getToolInput().get("__agentRunId");
        if (!(id instanceof String runId) || runId.isBlank()) return;
        var provenance = new ArrayList<Map<String,Object>>();
        if (context.get("skills") instanceof List<?> skills) for (Object item : skills) {
            if (!(item instanceof Map<?,?> skill)) continue;
            var fields = new LinkedHashMap<String,Object>();
            for (String key : List.of("id","name","version","contentSha256","selectionOwner"))
                if (skill.get(key) != null) fields.put(key,skill.get(key));
            provenance.add(Map.copyOf(fields));
        }
        var event = Map.<String,Object>of("stage","SKILL_ANALYSIS_CONTEXT","status",state,
            "contextStatus",context.getOrDefault("status",state),"fingerprint",context.getOrDefault("fingerprint",""),
            "skills",List.copyOf(provenance),"engine","RUNTIME_CONTEXT_DELIVERY",
            "modelCalls",0,"analysisDecisionOwner","CLINE");
        publishers.orderedStream().forEach(publisher -> publisher.publish(AgentRunEvent.of(runId,
            AgentRunEventType.OBSERVATION_RECORDED,"Skill 上下文交付："+event.get("contextStatus"),event)));
    }
}
