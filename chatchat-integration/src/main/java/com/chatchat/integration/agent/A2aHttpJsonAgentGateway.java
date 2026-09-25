package com.chatchat.integration.agent;

import com.chatchat.common.runtime.agent.*;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.agents.runtime.federation.RemoteAgentEvidenceProjector;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.a2aproject.sdk.client.Client;
import org.a2aproject.sdk.client.MessageEvent;
import org.a2aproject.sdk.client.TaskEvent;
import org.a2aproject.sdk.client.config.ClientConfig;
import org.a2aproject.sdk.client.http.JdkA2AHttpClient;
import org.a2aproject.sdk.client.transport.rest.RestTransport;
import org.a2aproject.sdk.client.transport.rest.RestTransportConfig;
import org.a2aproject.sdk.client.transport.spi.interceptors.ClientCallContext;
import org.a2aproject.sdk.spec.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.*;

/** SDK-backed A2A HTTP+JSON gateway. Only registry-approved endpoints are callable. */
@Component
public class A2aHttpJsonAgentGateway implements AgentGatewayPort {
    private static final ThreadPoolExecutor A2A_CALLS = new ThreadPoolExecutor(2, 8, 60,
        TimeUnit.SECONDS, new ArrayBlockingQueue<>(64), r -> {
            Thread thread = new Thread(r, "a2a-agent-call");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    private final WebClient.Builder clients;
    private final ObjectMapper mapper;
    private final AgentCredentialResolver credentials;
    private final RemoteAgentEvidenceProjector projector;
    private final AgentCardDiscoveryService cards;
    private final AgentTaskLinkStore taskLinks;

    @Autowired
    public A2aHttpJsonAgentGateway(WebClient.Builder clients, ObjectMapper mapper,
                                   ObjectProvider<AgentCredentialResolver> credentials,
                                   RemoteAgentEvidenceProjector projector, AgentCardDiscoveryService cards,
                                   AgentTaskLinkStore taskLinks) {
        this.clients = clients;
        this.mapper = mapper;
        this.credentials = credentials.getIfAvailable();
        this.projector = projector;
        this.cards = cards;
        this.taskLinks = taskLinks;
    }

    A2aHttpJsonAgentGateway(WebClient.Builder clients, ObjectMapper mapper,
                            ObjectProvider<AgentCredentialResolver> credentials,
                            RemoteAgentEvidenceProjector projector) {
        this(clients, mapper, credentials, projector, new AgentCardDiscoveryService(mapper),
            new InMemoryAgentTaskLinkStore());
    }

    A2aHttpJsonAgentGateway(WebClient.Builder clients, ObjectMapper mapper,
                            ObjectProvider<AgentCredentialResolver> credentials,
                            RemoteAgentEvidenceProjector projector, AgentTaskLinkStore taskLinks) {
        this(clients, mapper, credentials, projector, new AgentCardDiscoveryService(mapper), taskLinks);
    }

    @Override
    public AgentExecutionOutcome invoke(AgentDescriptor agent, AgentExecutionRequest request) {
        return dispatch(agent, request, false);
    }

    @Override
    public AgentExecutionOutcome resume(AgentDescriptor agent, AgentExecutionRequest request) {
        return dispatch(agent, request, true);
    }

    private AgentExecutionOutcome dispatch(AgentDescriptor agent, AgentExecutionRequest request, boolean resume) {
        AgentExecutionRequest safeRequest;
        try { safeRequest = projector.project(request); }
        catch (IllegalArgumentException error) {
            return failure(agent, request, "AGENT_PROJECTION_REJECTED", "Remote evidence projection was rejected");
        }
        if (agent.protocol() == AgentDescriptor.Protocol.HTTP_JSON && !resume) return invokeGovernedHttp(agent, safeRequest);
        if (agent.protocol() != AgentDescriptor.Protocol.A2A_HTTP_JSON) {
            return failure(agent, safeRequest, "AGENT_PROTOCOL_UNSUPPORTED", "Unsupported protocol " + agent.protocol());
        }
        try {
            String token = bearerToken(agent);
            Future<AgentExecutionOutcome> call = A2A_CALLS.submit(() -> invokeSdk(agent, safeRequest, token, resume));
            try {
                return call.get(safeRequest.constraints().timeoutMs(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException error) {
                call.cancel(true);
                return new AgentExecutionOutcome(null, safeRequest.executionId(), agent.agentId(),
                    AgentExecutionOutcome.Status.TIMED_OUT, List.of(), List.of(), List.of(), List.of(),
                    "AGENT_TIMEOUT", "A2A call exceeded its deadline", Map.of(), Map.of());
            } finally {
                if (!call.isDone()) call.cancel(true);
            }
        } catch (Exception error) {
            return failure(agent, safeRequest, "AGENT_TRANSPORT_FAILURE", "A2A call failed: " + error.getClass().getSimpleName());
        }
    }

    private AgentExecutionOutcome invokeSdk(AgentDescriptor agent, AgentExecutionRequest request, String token,
                                            boolean resume) throws Exception {
        purgeTaskLinks();
        AgentTaskLinkStore.TaskLink link = resume ? taskLinks.find(request.executionId()).orElse(null) : null;
        if (resume && (link == null || !agent.agentId().equals(link.agentId())
            || !Objects.equals(request.scope().tenantId(), link.tenantId())))
            return failure(agent, request, "AGENT_TASK_UNKNOWN", "No resumable A2A task belongs to this execution");
        if (!resume && taskLinks.count() >= 10_000)
            return failure(agent, request, "AGENT_TASK_CAPACITY", "A2A task tracking capacity exhausted");
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();
        AgentQueryParameterHttpClient transport = new AgentQueryParameterHttpClient(
            new JdkA2AHttpClient(http), AgentRequestParameters.query(agent));
        Map<String, String> headers = token == null ? Map.of() : Map.of("Authorization", "Bearer " + token);
        AgentCard card;
        try { card = cards.discover(agent, token); }
        catch (IllegalArgumentException rejected) {
            return failure(agent, request, rejected.getMessage().contains("endpoint")
                ? "AGENT_CARD_MISMATCH" : "AGENT_CARD_UNTRUSTED", rejected.getMessage());
        }
        if (!cardApproved(agent, card)) return failure(agent, request, "AGENT_CARD_MISMATCH",
            "Agent Card must advertise HTTP+JSON at the registered endpoint");

        List<org.a2aproject.sdk.client.ClientEvent> events = new ArrayList<>();
        ClientConfig config = ClientConfig.builder().setStreaming(false).setPolling(false)
            .setAcceptedOutputModes(request.outputContract().acceptedMediaTypes()).build();
        try (Client client = Client.builder(card).clientConfig(config)
            .withTransport(RestTransport.class, new RestTransportConfig(transport)).build()) {
            Message.Builder messageBuilder = Message.builder().role(Message.Role.ROLE_USER)
                .messageId(UUID.randomUUID().toString())
                .parts(new DataPart(mapper.convertValue(projectRemoteRequest(agent, request), Map.class)))
                .metadata(Map.of("runtimeProtocol", "runtime_os.agent_compute.v1"));
            if (resume) messageBuilder.taskId(link.taskId()).contextId(link.contextId());
            Message message = messageBuilder.build();
            MessageSendConfiguration sendConfig = MessageSendConfiguration.builder()
                .acceptedOutputModes(request.outputContract().acceptedMediaTypes())
                .returnImmediately(true).build();
            MessageSendParams params = new MessageSendParams(message, sendConfig,
                Map.of("executionId", request.executionId()));
            ClientCallContext context = new ClientCallContext(Map.of(), headers);
            client.sendMessage(params, List.of((event, ignored) -> events.add(event)), error -> {}, context);
            if (events.isEmpty()) return failure(agent, request, "AGENT_EMPTY_RESPONSE", "A2A returned no event");
            var event = events.get(events.size() - 1);
            if (event instanceof MessageEvent direct) {
                if (resume) taskLinks.delete(request.executionId());
                return decodeParts(agent, request, AgentExecutionOutcome.Status.PARTIAL,
                    direct.getMessage().parts(), "A2A direct message");
            }
            if (!(event instanceof TaskEvent taskEvent)) return failure(agent, request,
                "AGENT_RESPONSE_INVALID", "A2A returned no task or message");
            Task task = taskEvent.getTask();
            if (task.id() == null || task.id().isBlank())
                return failure(agent, request, "AGENT_RESPONSE_INVALID", "A2A task has no identifier");
            if (resume && !link.taskId().equals(task.id())) {
                taskLinks.delete(request.executionId());
                return failure(agent, request, "AGENT_TASK_MISMATCH", "A2A response changed the task identity");
            }
            if (resume && link.contextId() != null && !link.contextId().equals(task.contextId())) {
                taskLinks.delete(request.executionId());
                return failure(agent, request, "AGENT_CONTEXT_MISMATCH", "A2A response changed the task context");
            }
            taskLinks.save(new AgentTaskLinkStore.TaskLink(request.executionId(),
                request.scope().tenantId(), agent.agentId(), task.id(), task.contextId(), System.currentTimeMillis()));
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(request.constraints().timeoutMs());
            while (task.status() != null && !task.status().state().isFinal()
                && !task.status().state().isInterrupted() && System.nanoTime() < deadline) {
                Thread.sleep(200);
                task = client.getTask(new TaskQueryParams(task.id()), context);
            }
            if (task.status() != null && task.status().state().isFinal())
                taskLinks.delete(request.executionId());
            return decodeTask(agent, request, task);
        }
    }

    @Override
    public AgentExecutionOutcome cancel(AgentDescriptor agent, String executionId) {
        purgeTaskLinks();
        AgentTaskLinkStore.TaskLink link = taskLinks.find(executionId).orElse(null);
        if (link == null || !agent.agentId().equals(link.agentId()))
            return new AgentExecutionOutcome(null, executionId, agent.agentId(),
                AgentExecutionOutcome.Status.BLOCKED, List.of(), List.of(), List.of(), List.of(),
                "AGENT_TASK_UNKNOWN", "No active A2A task belongs to this agent and execution", Map.of(), Map.of());
        Future<AgentExecutionOutcome> call = A2A_CALLS.submit(() -> cancelSdk(agent, executionId, link));
        try {
            return call.get(10, TimeUnit.SECONDS);
        } catch (Exception error) {
            call.cancel(true);
            return new AgentExecutionOutcome(null, executionId, agent.agentId(),
                AgentExecutionOutcome.Status.TIMED_OUT, List.of(), List.of(), List.of(), List.of(),
                "AGENT_CANCEL_FAILED", "A2A cancellation did not complete", Map.of(), Map.of());
        }
    }

    private AgentExecutionOutcome cancelSdk(AgentDescriptor agent, String executionId,
                                            AgentTaskLinkStore.TaskLink link) throws Exception {
        String token = bearerToken(agent);
        Map<String, String> headers = token == null ? Map.of() : Map.of("Authorization", "Bearer " + token);
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();
        AgentQueryParameterHttpClient transport = new AgentQueryParameterHttpClient(
            new JdkA2AHttpClient(http), AgentRequestParameters.query(agent));
        AgentCard card;
        try { card = cards.discover(agent, token); }
        catch (IllegalArgumentException rejected) {
            return new AgentExecutionOutcome(null, executionId, agent.agentId(),
                AgentExecutionOutcome.Status.BLOCKED, List.of(), List.of(), List.of(), List.of(),
                "AGENT_CARD_UNTRUSTED", rejected.getMessage(), Map.of(), Map.of());
        }
        if (!cardApproved(agent, card)) return new AgentExecutionOutcome(null, executionId, agent.agentId(),
            AgentExecutionOutcome.Status.BLOCKED, List.of(), List.of(), List.of(), List.of(),
            "AGENT_CARD_MISMATCH", "Agent Card changed since task dispatch", Map.of(), Map.of());
        try (Client client = Client.builder(card).clientConfig(ClientConfig.builder().setStreaming(false).build())
            .withTransport(RestTransport.class, new RestTransportConfig(transport)).build()) {
            Task task = client.cancelTask(new CancelTaskParams(link.taskId()),
                new ClientCallContext(Map.of(), headers));
            if (task.status() != null && task.status().state().isFinal()) taskLinks.delete(executionId);
            AgentExecutionOutcome.Status status = task.status() != null
                && task.status().state() == TaskState.TASK_STATE_CANCELED
                ? AgentExecutionOutcome.Status.CANCELLED : AgentExecutionOutcome.Status.PARTIAL;
            return new AgentExecutionOutcome(null, executionId, agent.agentId(), status,
                List.of(), List.of(), List.of(), List.of(task.status() == null
                    ? "A2A cancellation state unknown" : task.status().state().name()),
                "", "", Map.of(), Map.of("a2aTaskId", task.id()));
        }
    }

    private void purgeTaskLinks() {
        long cutoff = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(1);
        taskLinks.deleteExpired(cutoff);
    }

    private Map<String, Object> projectRemoteRequest(AgentDescriptor agent, AgentExecutionRequest request) {
        Map<String, Object> projected = new LinkedHashMap<>();
        projected.put("schemaVersion", request.schemaVersion());
        projected.put("executionId", request.executionId());
        projected.put("tenantId", request.scope().tenantId());
        projected.put("capability", request.capability());
        projected.put("task", request.task());
        projected.put("evidence", request.evidence());
        projected.put("capabilityGrants", request.capabilityGrants());
        projected.put("constraints", request.constraints());
        projected.put("outputContract", request.outputContract());
        projected.put("agentExecutionMode", request.executionMode().name());
        if ("analysis_package.v1".equals(request.metadata().get(AgentExecutionRequest.DOMAIN_PACKAGE_METADATA_KEY)))
            projected.put("analysisPackage", AnalysisPackage.from(request));
        Map<String, String> configured = AgentRequestParameters.body(agent);
        if (!configured.isEmpty()) projected.put("providerRequestParameters", configured);
        Object collaborationTaskId = request.metadata().get(AgentExecutionRequest.COLLABORATION_TASK_METADATA_KEY);
        if (collaborationTaskId instanceof String taskId && !taskId.isBlank())
            projected.put("collaborationTaskId", taskId);
        // User identity, run metadata, credentials and Runtime-internal scope attributes stay local.
        return projected;
    }

    private boolean sameEndpoint(URI approved, String advertised) {
        try {
            return approved.normalize().equals(URI.create(advertised).normalize());
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private boolean cardApproved(AgentDescriptor agent, AgentCard card) {
        return card.supportedInterfaces() != null && card.supportedInterfaces().stream()
            .anyMatch(value -> "HTTP+JSON".equalsIgnoreCase(value.protocolBinding())
                && sameEndpoint(agent.endpoint(), value.url()));
    }

    private AgentExecutionOutcome decodeTask(AgentDescriptor agent, AgentExecutionRequest request, Task task) {
        TaskState state = task.status() == null ? TaskState.TASK_STATE_UNSPECIFIED : task.status().state();
        AgentExecutionOutcome.Status status = switch (state) {
            case TASK_STATE_COMPLETED -> AgentExecutionOutcome.Status.COMPLETED;
            case TASK_STATE_INPUT_REQUIRED -> AgentExecutionOutcome.Status.INPUT_REQUIRED;
            case TASK_STATE_AUTH_REQUIRED, TASK_STATE_REJECTED -> AgentExecutionOutcome.Status.BLOCKED;
            case TASK_STATE_CANCELED -> AgentExecutionOutcome.Status.CANCELLED;
            case TASK_STATE_FAILED -> AgentExecutionOutcome.Status.FAILED;
            default -> AgentExecutionOutcome.Status.TIMED_OUT;
        };
        List<Part<?>> parts = new ArrayList<>();
        if (task.artifacts() != null) task.artifacts().forEach(artifact -> parts.addAll(artifact.parts()));
        if (task.status() != null && task.status().message() != null)
            parts.addAll(task.status().message().parts());
        return decodeParts(agent, request, status, parts, state.name());
    }

    private AgentExecutionOutcome decodeParts(AgentDescriptor agent, AgentExecutionRequest request,
                                              AgentExecutionOutcome.Status status, List<Part<?>> parts,
                                              String limitation) {
        List<AgentExecutionOutcome.Artifact> artifacts = new ArrayList<>();
        for (Part<?> part : parts) {
            if (part instanceof DataPart data) {
                try {
                    var raw = mapper.valueToTree(data.data());
                    if (!AgentExecutionOutcome.SCHEMA_VERSION.equals(raw.path("schemaVersion").asText()))
                        throw new IllegalArgumentException("not an outcome contract");
                    AgentExecutionOutcome value = mapper.convertValue(data.data(), AgentExecutionOutcome.class);
                    AgentExecutionOutcome checked = validateIdentity(agent, request, value);
                    if (checked != value) return checked;
                    if (status == AgentExecutionOutcome.Status.INPUT_REQUIRED
                        && checked.status() != AgentExecutionOutcome.Status.INPUT_REQUIRED
                        && checked.status() != AgentExecutionOutcome.Status.SUPPLEMENT_EVIDENCE)
                        return new AgentExecutionOutcome(null, checked.executionId(), checked.providerAgentId(),
                            AgentExecutionOutcome.Status.INPUT_REQUIRED, checked.claims(), checked.artifacts(),
                            checked.missingEvidence(), checked.limitations(), checked.errorCode(),
                            checked.errorMessage(), checked.usage(), checked.metadata());
                    if (status != AgentExecutionOutcome.Status.INPUT_REQUIRED
                        && status != AgentExecutionOutcome.Status.PARTIAL
                        && status != AgentExecutionOutcome.Status.COMPLETED
                        && checked.status() != status)
                        return new AgentExecutionOutcome(null, checked.executionId(), checked.providerAgentId(),
                            status, checked.claims(), checked.artifacts(), checked.missingEvidence(),
                            checked.limitations(), checked.errorCode(), checked.errorMessage(),
                            checked.usage(), checked.metadata());
                    return checked;
                } catch (IllegalArgumentException ignored) { /* non-contract data remains an artifact */ }
                artifacts.add(new AgentExecutionOutcome.Artifact(UUID.randomUUID().toString(),
                    "application/json", mapper.valueToTree(data.data()).toString(), Map.of()));
            } else if (part instanceof TextPart text) {
                artifacts.add(new AgentExecutionOutcome.Artifact(UUID.randomUUID().toString(),
                    "text/plain", text.text(), Map.of()));
            }
        }
        AgentExecutionOutcome.Status effective = status == AgentExecutionOutcome.Status.COMPLETED
            ? AgentExecutionOutcome.Status.PARTIAL : status;
        return new AgentExecutionOutcome(null, request.executionId(), agent.agentId(), effective,
            List.of(), artifacts, List.of(), List.of(limitation), "", "", Map.of(), Map.of());
    }

    private AgentExecutionOutcome invokeGovernedHttp(AgentDescriptor agent, AgentExecutionRequest request) {
        try {
            var call = clients.build().post().uri(agent.endpoint())
                .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON);
            String token = bearerToken(agent);
            if (token != null) call.headers(headers -> headers.setBearerAuth(token));
            Map<String, Object> body = new LinkedHashMap<>(projectRemoteRequest(agent, request));
            body.put("scope", new KernelDataScope(request.scope().tenantId(), null,
                null, null, null, request.scope().environment(), Map.of()));
            AgentExecutionOutcome outcome = call.bodyValue(body).retrieve()
                .bodyToMono(AgentExecutionOutcome.class)
                .block(Duration.ofMillis(request.constraints().timeoutMs()));
            return outcome == null ? failure(agent, request, "AGENT_EMPTY_RESPONSE", "HTTP agent returned no body")
                : validateIdentity(agent, request, outcome);
        } catch (RuntimeException error) {
            return failure(agent, request, "AGENT_TRANSPORT_FAILURE", "HTTP agent call failed: " + error.getClass().getSimpleName());
        }
    }

    private AgentExecutionOutcome validateIdentity(AgentDescriptor agent, AgentExecutionRequest request,
                                                     AgentExecutionOutcome value) {
        if (!request.executionId().equals(value.executionId()))
            return failure(agent, request, "AGENT_EXECUTION_ID_MISMATCH", "Agent changed execution identity");
        if (!value.providerAgentId().isBlank() && !agent.agentId().equals(value.providerAgentId()))
            return failure(agent, request, "AGENT_PROVIDER_ID_MISMATCH", "Agent changed provider identity");
        return value;
    }

    private String bearerToken(AgentDescriptor agent) {
        if (agent.credentialRef().isBlank()) return null;
        if (credentials == null) throw new IllegalStateException("Agent credential resolver is not configured");
        Optional<String> value = credentials.resolveBearerToken(agent.credentialRef());
        return value.orElseThrow(() -> new IllegalStateException("Agent credential reference cannot be resolved"));
    }

    private AgentExecutionOutcome failure(AgentDescriptor agent, AgentExecutionRequest request, String code, String message) {
        return new AgentExecutionOutcome(null, request.executionId(), agent.agentId(),
            AgentExecutionOutcome.Status.FAILED, List.of(), List.of(), List.of(), List.of(),
            code, message, Map.of(), Map.of());
    }

}
