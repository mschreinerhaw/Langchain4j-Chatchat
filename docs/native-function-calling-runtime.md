# Native Function Calling with the MCP Runtime

The Agent supports a guarded native function-calling fast path. Native model tool calls are
planning decisions only: they never execute a tool directly and never bypass `ToolRuntimeService`
or the MCP Runtime Kernel.

## Execution path

```text
ChatModel + ToolSpecification
  -> ToolExecutionRequest
  -> AgentDecision(action=tool)
  -> AgentOrchestrationEngine
  -> AgentToolExecutor
  -> ToolRuntimeService
  -> MCP Runtime Kernel
```

The fast path is used only for the first decision when exactly one mandatory tool is visible,
the tool declares the `DIRECT` workflow role, no authoritative workflow DAG is active, and
document/web verification is not required. Multi-step discovery, template and execution flows
continue to use the InterpretationPlan DAG planner.

If the provider does not support native function calling, returns malformed arguments, returns
multiple calls, or names a tool outside the request allow-list, planning falls back to the JSON
planner. Runtime deadline, cancellation and model-budget failures are not swallowed by fallback.

## Configuration

```yaml
chatchat:
  agent-runtime:
    native-tool-calling-enabled: true
    native-tool-calling-max-tools: 8
```

Environment variables:

- `CHATCHAT_AGENT_NATIVE_TOOL_CALLING_ENABLED`
- `CHATCHAT_AGENT_NATIVE_TOOL_CALLING_MAX_TOOLS`

Set `CHATCHAT_AGENT_NATIVE_TOOL_CALLING_ENABLED=false` to return all decisions to the existing
JSON/InterpretationPlan planner without changing MCP execution behavior.
