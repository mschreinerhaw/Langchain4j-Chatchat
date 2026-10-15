package com.chatchat.api.runtime;

import com.chatchat.agents.runtime.coding.CodingJournal;
import com.chatchat.agents.runtime.coding.CodingTaskAccess;
import com.chatchat.agents.runtime.coding.DurableCodingTaskService;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.runtime.coding.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/** First D1 callback slice: durable model admission and receipt reconciliation. */
@RestController
@RequestMapping("/internal/coding/cline")
@ConditionalOnProperty(prefix = "chatchat.coding.cline", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "chatchat.coding", name = "enabled", havingValue = "true")
public final class ClineModelCallbackController {
    private static final Set<CodingTaskAccess.Action> GRANTS = EnumSet.of(
        CodingTaskAccess.Action.READ, CodingTaskAccess.Action.WORKER, CodingTaskAccess.Action.EVIDENCE);
    private final CodingInvocationContext identity;
    private final DurableCodingTaskService tasks;
    private final ClineRuntimeModelBridge model;
    private final ClineModelReceiptStore receipts;
    private final ObjectMapper mapper;
    private final Map<String, Instant> freshDispatches = new ConcurrentHashMap<>();

    public ClineModelCallbackController(CodingInvocationContext identity, DurableCodingTaskService tasks,
            ClineRuntimeModelBridge model, ClineModelReceiptStore receipts, ObjectMapper mapper) {
        this.identity = identity; this.tasks = tasks; this.model = model; this.receipts = receipts; this.mapper = mapper;
    }

    @PostMapping("/reserve-model")
    public Map<String, Object> reserve(@RequestBody JsonNode body) {
        return bound(body, call -> {
            CodingTaskContract contract = contract(body, call);
            JsonNode request = body.path("model");
            String operationId = operationId(request.path("operationId").asText(null));
            if (request.path("modelCalls").asLong(-1) != 1)
                throw new IllegalArgumentException("Exactly one model call must be reserved");
            long tokens = request.path("maxTokens").asLong(-1);
            if (tokens < 1 || tokens > contract.budget().maxModelTokens())
                throw new IllegalArgumentException("Model token reservation exceeds contract");
            BigDecimal totalCost = new BigDecimal(contract.budget().maxCostAmount());
            String cost = totalCost.divide(BigDecimal.valueOf(Math.max(1, contract.budget().maxModelCalls())),
                12, RoundingMode.UP).stripTrailingZeros().toPlainString();
            var reserved = tasks.reserve(call.scope(), call.taskId(), call.lease(), call.snapshot().revision(),
                operationId, sha(call.contractHash() + "\0" + operationId + "\0" + tokens),
                CodingJournal.OperationKind.MODEL,
                new CodingTaskOutcome.Counters(1, tokens, 0, 0, 0, 0, 0, cost));
            if (!reserved.admitted()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Model budget denied");
            var intent = tasks.startIntent(call.scope(), call.taskId(), call.lease(),
                reserved.outcome().revision(), operationId, "runtime-model:" + operationId);
            if (intent.dispatchIntentCreated()) {
                Instant cutoff = Instant.now().minus(Duration.ofMinutes(5));
                freshDispatches.entrySet().removeIf(entry -> entry.getValue().isBefore(cutoff));
                freshDispatches.put(key(call, operationId), Instant.now());
            }
            return Map.of("admitted", true, "dispatchIntentCreated", intent.dispatchIntentCreated(),
                "revision", intent.outcome().revision());
        });
    }

    @PostMapping("/model")
    public Map<String, Object> model(@RequestBody JsonNode body) {
        return bound(body, call -> {
            CodingTaskContract contract = contract(body, call);
            JsonNode request = body.path("request");
            String operationId = operationId(request.path("operationId").asText(null));
            var prior = receipts.response(call.scope(), call.taskId(), operationId);
            if (prior.isPresent()) return prior.get();
            Instant permittedAt = freshDispatches.remove(key(call, operationId));
            if (!tasks.unresolvedOperations(call.scope(), call.taskId()).contains(operationId)
                    || permittedAt == null || permittedAt.isBefore(Instant.now().minus(Duration.ofMinutes(5))))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Model dispatch is not a fresh A2 start intent");
            // The provider call is bounded to 90 seconds; the renewed lease survives callback and settlement.
            tasks.renew(call.scope(), call.taskId(), call.lease(), Duration.ofMinutes(5),
                call.snapshot().revision());
            Map<String, Object> response = model.complete(contract, request);
            assertStillOwned(call);
            receipts.commit(contract, operationId, "runtime-model:" + operationId, response);
            return response;
        });
    }

    @PostMapping("/settle-model")
    public Map<String, Object> settle(@RequestBody JsonNode body) {
        return bound(body, call -> {
            contract(body, call);
            String operationId = operationId(body.path("model").path("operationId").asText(null));
            Map<String, Object> response = receipts.response(call.scope(), call.taskId(), operationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                    "Provider response is unknown; keep the budget reservation"));
            String proof = receipts.proofRef(call.scope(), call.taskId(), operationId);
            var current = tasks.get(call.scope(), call.taskId());
            if (tasks.verifiedOperation(call.scope(), call.taskId(), operationId).isEmpty())
                current = tasks.reconcileStopped(call.scope(), call.taskId(), call.lease(),
                    current.revision(), operationId, proof);
            @SuppressWarnings("unchecked") Map<String, Object> usage = (Map<String, Object>) response.get("usage");
            long input = number(usage, "inputTokens"), output = number(usage, "outputTokens");
            current = tasks.settle(call.scope(), call.taskId(), call.lease(), current.revision(),
                operationId, new CodingTaskOutcome.Counters(1, Math.addExact(input, output),
                    0, 0, 0, 0, 0, "0"), false);
            return Map.of("revision", current.revision(), "meteringComplete", false,
                "receiptRef", proof);
        });
    }

    @PostMapping("/event")
    public Map<String, Object> event(@RequestBody JsonNode body) {
        return bound(body, call -> Map.of("authoritativeRevision", call.snapshot().revision()));
    }

    /** An SDK summary cannot be published as a verified Coding result. */
    @PostMapping("/finish")
    public Map<String, Object> finish(@RequestBody JsonNode body) {
        return bound(body, call -> {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "SDK output has no independently verified Coding dataset or terminal evidence");
        });
    }

    private record Call(KernelDataScope scope, String taskId, String contractHash,
                        CodingJournal.Lease lease, CodingTaskOutcome snapshot) { }

    private <T> T bound(JsonNode body, Function<Call, T> operation) {
        try {
            if (body == null || !body.isObject()) throw new IllegalArgumentException("Callback body is required");
            KernelDataScope scope = mapper.treeToValue(body.path("scope"), KernelDataScope.class);
            String taskId = body.path("taskId").asText(null);
            String contractHash = body.path("contractHash").asText(null);
            long fencingToken = body.path("fencingToken").asLong(-1);
            if (scope == null || taskId == null || contractHash == null || fencingToken < 1)
                throw new IllegalArgumentException("Complete callback binding is required");
            return identity.as(scope, GRANTS, () -> {
                CodingTaskOutcome snapshot = tasks.get(scope, taskId);
                CodingJournal.Lease lease = tasks.ownership(scope, taskId);
                if (!snapshot.contractSha256().equals(contractHash)
                        || lease.fencingToken() != fencingToken || lease.owner() == null
                        || !lease.owner().startsWith("cline-adapter:")
                        || !lease.leaseUntil().isAfter(Instant.now())
                        || snapshot.status().terminal() || snapshot.cancellationRequested()
                        || snapshot.executionControl().stopReason() != null)
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Callback task, lease, or stop state is no longer current");
                return operation.apply(new Call(scope, taskId, contractHash, lease, snapshot));
            });
        } catch (ResponseStatusException known) { throw known; }
        catch (Exception invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid Coding callback binding", invalid);
        }
    }

    private CodingTaskContract contract(JsonNode body, Call call) {
        try {
            CodingTaskContract contract = mapper.treeToValue(body.path("contract"), CodingTaskContract.class);
            call.snapshot().validateAgainst(contract);
            if (contract.authorization().networkPolicy() != CodingTaskContract.NetworkPolicy.NONE)
                throw new SecurityException("Coding model cannot access a networked Sandbox");
            return contract;
        } catch (Exception invalid) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Callback contract is not authorized", invalid);
        }
    }

    private void assertStillOwned(Call call) {
        CodingJournal.Lease lease = tasks.ownership(call.scope(), call.taskId());
        CodingTaskOutcome current = tasks.get(call.scope(), call.taskId());
        if (lease.fencingToken() != call.lease().fencingToken()
                || !lease.owner().equals(call.lease().owner())
                || !lease.leaseUntil().isAfter(Instant.now())
                || current.status().terminal() || current.cancellationRequested()
                || current.executionControl().stopReason() != null)
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Coding ownership or stop state changed while the model was running");
    }

    private static String operationId(String value) {
        if (value == null || !value.matches("model-[1-9][0-9]{0,5}"))
            throw new IllegalArgumentException("Bounded model operation ID is required");
        return value;
    }

    private static long number(Map<String, Object> value, String key) {
        Object actual = value == null ? null : value.get(key);
        if (!(actual instanceof Number number) || number.longValue() < 0)
            throw new IllegalStateException("Provider token usage is unknown; retain reservation");
        return number.longValue();
    }

    private static String key(Call call, String operation) {
        return call.scope().tenantId() + "\0" + call.scope().userId() + "\0" + call.scope().runId()
            + "\0" + call.taskId() + "\0" + operation + "\0" + call.lease().fencingToken();
    }

    private static String sha(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
}
