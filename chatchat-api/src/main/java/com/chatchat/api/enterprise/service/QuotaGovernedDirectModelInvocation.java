package com.chatchat.api.enterprise.service;

import com.chatchat.agents.orchestration.model.DirectModelInvocation;
import com.chatchat.agents.orchestration.model.AgentChatModelResolver;
import com.chatchat.agents.orchestration.model.ToolExecutionModels;
import com.chatchat.agents.model.ModelFailureCategory;
import com.chatchat.agents.orchestration.model.MeteredChatModel;
import com.chatchat.agents.runtime.event.AgentRunEvent;
import com.chatchat.agents.runtime.event.AgentRunEventPublisher;
import com.chatchat.agents.runtime.event.AgentRunEventType;
import com.chatchat.api.security.ApiAuthenticationFilter;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.enterprise.repository.identity.SysUserRepository;
import com.chatchat.enterprise.service.EnterpriseAdminService;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.DefaultChatRequestParameters;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Service
@RequiredArgsConstructor
@Slf4j
class QuotaGovernedDirectModelInvocation implements DirectModelInvocation {
    private final TokenQuotaLedger ledger;
    private final SysUserRepository users;
    private final EnterpriseAdminService identities;
    private final AgentChatModelResolver models;
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.beans.factory.ObjectProvider<AgentRunEventPublisher> eventPublishers;

    @Override
    public void freezeBillingRole(KernelDataScope scope) {
        KernelDataScope verified;
        try {
            verified = verifiedScope(scope);
        } catch (ResponseStatusException unavailable) {
            return; // Service tasks without an enterprise actor keep their existing non-model entry point.
        }
        ledger.bind(verified);
    }

    @Override
    public String text(KernelDataScope scope, String purpose, String modelName, String prompt, int outputLimit) {
        var selected = models.resolveChatModel(modelName);
        var configuration = models.checkpointModelConfiguration(modelName, selected);
        int maximum = configuration != null && configuration.get("maxTokens") instanceof Integer limit && limit > 0
            ? Math.min(limit, outputLimit) : outputLimit;
        var request = ChatRequest.builder().messages(UserMessage.from(prompt))
            .parameters(DefaultChatRequestParameters.builder().maxOutputTokens(maximum).build()).build();
        Object value = scope == null ? null : scope.attributes().get("__agentDeadlineAt");
        Long deadline = value instanceof Number number ? Long.valueOf(number.longValue())
            : value instanceof java.time.Instant instant ? Long.valueOf(instant.toEpochMilli()) : null;
        var model = ToolExecutionModels.independent(selected,
            () -> deadline == null ? -1 : Math.max(0L, deadline - System.currentTimeMillis()));
        var result = chat(scope, purpose, model, request, maximum);
        return result == null || result.aiMessage() == null ? null : result.aiMessage().text();
    }

    @Override
    public ChatResponse chat(KernelDataScope owner, String purpose, ChatModel model, ChatRequest request, Integer outputLimit) {
        boolean media = request.messages().stream().anyMatch(message -> message instanceof UserMessage user
            && user.contents().stream().anyMatch(content -> !(content instanceof dev.langchain4j.data.message.TextContent)));
        if (media) return governed(owner, purpose, model, request, 0L, null);
        long inputBound = request.toString().getBytes(StandardCharsets.UTF_8).length + 256L;
        return governed(owner, purpose, model, request, inputBound, outputLimit);
    }

    @Override
    public ChatResponse multimodal(KernelDataScope owner, String purpose, ChatModel model, ChatRequest request,
                                   Integer contextWindow, Integer outputLimit) {
        boolean bounded = contextWindow != null && contextWindow > 0;
        return governed(owner, purpose, model, request, bounded ? contextWindow.longValue() : 0L, bounded ? outputLimit : null);
    }

    private ChatResponse governed(KernelDataScope owner, String purpose, ChatModel model, ChatRequest request,
                                  long inputBound, Integer outputLimit) {
        var scope = verifiedScope(owner == null ? authenticatedRequestScope() : owner);
        if (!purpose.matches("[a-z0-9-]{1,48}")) throw new IllegalArgumentException("Invalid model purpose");
        String taskId = purpose + "-" + scope.runId();
        String requestId = UUID.randomUUID().toString();
        Long boundedOutput = outputLimit != null && outputLimit > 0 ? outputLimit.longValue() : null;
        var decision = ledger.reserve(scope, taskId, requestId, inputBound, boundedOutput);
        if (decision != TokenQuotaLedger.Decision.ADMITTED) {
            var status = decision == TokenQuotaLedger.Decision.QUOTA_EXHAUSTED
                ? HttpStatus.TOO_MANY_REQUESTS : HttpStatus.FORBIDDEN;
            throw new ResponseStatusException(status, "模型额度准入失败 " + decision);
        }
        ChatResponse response = null;
        var facts = new LinkedHashMap<String, Object>();
        facts.put("requestId", requestId);
        facts.put("modelPhase", purpose);
        facts.put("modelSource", "JAVA");
        facts.put("messageCount", request.messages().size());
        facts.put("toolCount", request.toolSpecifications() == null ? 0 : request.toolSpecifications().size());
        long started = System.nanoTime();
        publishModelEvent(scope, facts, "STARTED");
        try (var timing = MeteredChatModel.observeTiming()) {
            response = model.chat(request);
            if (timing.firstTokenMs() != null) facts.put("firstTokenMs", timing.firstTokenMs());
            if (response != null) {
                if (response.modelName() != null && response.modelName().matches("[\\p{L}0-9_./:@-]{1,240}"))
                    facts.put("providerModel", response.modelName());
                if (response.finishReason() != null) facts.put("finishReason", response.finishReason().name());
                if (response.tokenUsage() != null) {
                    var usage = response.tokenUsage();
                    if (usage.inputTokenCount() != null) facts.put("inputTokens", usage.inputTokenCount());
                    if (usage.outputTokenCount() != null) facts.put("outputTokens", usage.outputTokenCount());
                    if (usage.totalTokenCount() != null) facts.put("totalTokens", usage.totalTokenCount());
                    facts.put("usageSource", "UPSTREAM");
                }
            }
            return response;
        } catch (RuntimeException failure) {
            facts.put("reasonCode", ModelFailureCategory.classify(failure).name());
            throw failure;
        } finally {
            facts.putIfAbsent("usageSource", "UNAVAILABLE");
            facts.put("durationMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            publishModelEvent(scope, facts, response != null ? "COMPLETED"
                : "MODEL_CANCELLED".equals(facts.get("reasonCode")) ? "CANCELLED" : "FAILED");
            Integer actual = response == null || response.tokenUsage() == null ? null : response.tokenUsage().totalTokenCount();
            try {
                ledger.settle(scope, taskId, requestId, actual != null && actual > 0 ? actual.longValue() : null, false);
            } catch (RuntimeException pending) {
                log.warn("Direct model quota settlement pending taskId={} requestId={}", taskId, requestId);
            }
        }
    }

    private void publishModelEvent(KernelDataScope scope, Map<String, Object> facts, String state) {
        if (eventPublishers == null) return;
        var metadata = new LinkedHashMap<>(facts);
        metadata.put("eventKind", "MODEL_CALL");
        metadata.put("eventState", state);
        try {
            eventPublishers.orderedStream().forEach(publisher -> publisher.publish(AgentRunEvent.of(
                scope.runId(), AgentRunEventType.OBSERVATION_RECORDED, "Java model call " + state,
                Map.of("metadata", metadata))));
        } catch (RuntimeException failure) {
            log.warn("Java model telemetry publish failed requestId={}", facts.get("requestId"));
        }
    }

    private KernelDataScope verifiedScope(KernelDataScope scope) {
        if (scope.tenantId() == null || scope.userId() == null || scope.runId() == null || scope.runId().length() > 196)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "模型请求缺少可验证的用户身份");
        var user = users.findById(scope.userId()).or(() -> users.findByUsername(scope.userId()))
            .filter(identity -> scope.tenantId().equals(identity.getTenantId()));
        if (user.isEmpty() || !identities.isActiveIdentity(scope.tenantId(), user.get().getId()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "模型请求身份已失效");
        return new KernelDataScope(scope.tenantId(), user.get().getId(), scope.requestId(),
            scope.conversationId(), scope.runId(), scope.environment(), scope.attributes());
    }

    @Override
    public KernelDataScope authenticatedRequestScope() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "模型请求缺少可验证的用户身份");
        var request = attributes.getRequest();
        Object tenant = request.getAttribute(ApiAuthenticationFilter.CURRENT_TENANT_ID);
        Object user = request.getAttribute(ApiAuthenticationFilter.CURRENT_USER_ID);
        String id = UUID.randomUUID().toString();
        return new KernelDataScope(tenant instanceof String s ? s : null, user instanceof String s ? s : null,
            id, null, id, null, Map.of());
    }
}
