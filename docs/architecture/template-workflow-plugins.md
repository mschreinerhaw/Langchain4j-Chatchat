# Template workflow plugins

Template-governed tools are compiled as asset workflows instead of being inferred from tool
names. The model supplies intent and business parameters; it does not own workflow topology,
template identity, or runtime bindings.

## Runtime flow

1. An MCP publisher declares `workflowContract.workflowRole`, `protocolFamily`, and `assetType`.
2. `TemplateWorkflowPluginRegistry` selects exactly one plugin for each template executor.
3. `TemplateExecutionDagRepairPass` restricts candidate asset/template nodes to that plugin and
   materializes the required dependencies, edge contract, and scalar template-id binding.
4. `InterpretationPlanValidator` uses the same plugin to verify provenance before execution.
5. `InterpretationPlanRuntime` executes the resulting immutable DAG and resolves its declared
   bindings; it does not choose the asset workflow.

The registry is loaded through Java `ServiceLoader`. Applications can also inject a registry
directly into `InterpretationPlanOptimizer` and `InterpretationPlanValidator`, which is useful for
Spring composition and isolated tests. A higher-priority plugin can extend or override a built-in
asset protocol without changing Runtime.

## Plugin contract

`TemplateWorkflowPlugin` owns:

- matching publisher-declared execution, discovery, and asset identities;
- whether a separate asset-discovery node is mandatory;
- the discovery result path containing the selected template id;
- the executor input path receiving that id;
- required executor fields compiled from reviewed discovery metadata at runtime;
- deterministic priority when an application supplies a specialized implementation.

Built-in implementations currently cover API, SQL/database, Python analysis, SSH, and HTTP
assets. `publisher-contract-template-workflow.v1` is a low-priority compatibility plugin: it joins
only tools declaring the exact same protocol family (or old role-only tools). It must not be used
to add new name-based rules.

## Adding an asset

1. Publish stable workflow metadata from the MCP tool provider.
2. Implement `TemplateWorkflowPlugin`; prefer `ProtocolFamilyTemplateWorkflowPlugin` when the
   standard discovery-to-execution shape is sufficient.
3. Register the implementation under
   `META-INF/services/com.chatchat.agents.runtime.plan.template.TemplateWorkflowPlugin`, or inject
   it in the application registry.
4. Add a contract test proving that unrelated discovery nodes cannot bind to the executor and
   that the plugin's binding paths are accepted by the validator.

`runtimeOwnedExecutionInputs()` is intentionally scoped to a selected plugin and a compatible
upstream template-discovery dependency. The standard contract owns the parameter container; the
SQL implementation additionally owns `executionContext`, because datasource routing is resolved
from the selected template/asset metadata after planning. A field is never waived for a direct or
cross-protocol invocation.

Remaining asset-specific result interpretation can move behind this contract incrementally. The
existing Runtime remains a compatibility layer after plugin-owned DAG compilation and validation.
