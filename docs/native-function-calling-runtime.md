# Native Function Calling with the MCP Runtime

The Agent supports a Runtime-designated native function-calling adapter. Function Calling does
not select a tool. The Runtime first resolves the user-controlled workflow and designates exactly
one ready tool; the model is then allowed only to generate arguments for that tool. Native calls
never execute a tool directly and never bypass `ToolRuntimeService` or the MCP Runtime Kernel.

## Execution path

```text
User workflow / Runtime scheduler
  -> RuntimeDesignatedFunctionCall(exactly one tool)
  -> ChatModel + one ToolSpecification + toolChoice=REQUIRED
  -> ToolExecutionRequest
  -> AgentDecision(action=tool)
  -> AgentOrchestrationEngine
  -> AgentToolExecutor
  -> ToolRuntimeService
  -> MCP Runtime Kernel
```

The adapter is used only when the Runtime scheduler has designated the next mandatory tool and
that exact tool is visible and declares the `DIRECT` workflow role. Request attributes cannot mint
this designation; the orchestration engine removes any caller-provided value and creates a
step-scoped contract itself.

Authoritative user workflow DAGs, document/web verification chains, and publisher parent-child
roles do not enter this adapter. Their ready-node selection, dependencies, routing and execution
continue to be owned by the InterpretationPlan/runtime workflow machinery.

If the provider does not support native function calling, returns malformed arguments, returns
multiple calls, or names anything other than the exact designated tool, planning falls back to
the JSON planner. Runtime deadline, cancellation and model-budget failures are not swallowed by
fallback.

## Configuration

```yaml
chatchat:
  agent-runtime:
    native-tool-calling-enabled: true
```

Environment variables:

- `CHATCHAT_AGENT_NATIVE_TOOL_CALLING_ENABLED`

Set `CHATCHAT_AGENT_NATIVE_TOOL_CALLING_ENABLED=false` to return all decisions to the existing
JSON/InterpretationPlan planner without changing MCP execution behavior.
