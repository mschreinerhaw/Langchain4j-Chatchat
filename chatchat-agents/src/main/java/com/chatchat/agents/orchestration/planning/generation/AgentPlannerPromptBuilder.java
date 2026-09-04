package com.chatchat.agents.orchestration.planning.generation;

import com.chatchat.agents.orchestration.planning.validation.AgentPlanBudgetPolicy;

import com.chatchat.agents.protocol.ToolProtocolContractResolver;
import com.chatchat.agents.runtime.batch.ToolCallBatchSchema;
import com.chatchat.agents.runtime.context.AgentRoleAnalysisContext;
import com.chatchat.agents.runtime.observation.AgentRuntimeFactGroundingContract;
import com.chatchat.agents.runtime.plan.InterpretationExecutionProtocol;
import com.chatchat.agents.runtime.plan.InterpretationPlanJsonSchema;
import com.chatchat.agents.tool.RegistryMcpCapabilityHierarchy;
import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.mcp.capability.McpCapabilityHierarchy;
import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.common.tool.ToolWorkflowContract;
import com.chatchat.common.tool.ToolWorkflowRole;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.chatchat.agents.orchestration.support.AgentValueSupport.firstNonBlank;
import static com.chatchat.agents.orchestration.support.AgentValueSupport.firstObject;
import static com.chatchat.agents.orchestration.support.AgentValueSupport.booleanObject;
import static com.chatchat.agents.orchestration.support.AgentValueSupport.normalizeList;
import static com.chatchat.agents.orchestration.support.AgentValueSupport.stringList;
import static com.chatchat.agents.orchestration.support.AgentValueSupport.stringValue;

/** Compiles Runtime OS policy and tool contracts into the model-facing plan prompt. */
@Slf4j
public final class AgentPlannerPromptBuilder {
    private static final String DOCUMENT_SEARCH_TOOL = "document_search";
    private static final int MAX_USER_QUERY_PROMPT_CHARS = 32_000;
    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final McpCapabilityHierarchy capabilityHierarchy;
    private final ToolProtocolContractResolver toolProtocolContracts = new ToolProtocolContractResolver();

    public AgentPlannerPromptBuilder(ToolRegistry toolRegistry, ObjectMapper objectMapper, Clock clock) {
        this.toolRegistry = toolRegistry;
        this.objectMapper = objectMapper;
        this.clock = clock == null ? Clock.systemDefaultZone() : clock;
        this.capabilityHierarchy = toolRegistry == null ? McpCapabilityHierarchy.empty()
            : new RegistryMcpCapabilityHierarchy(toolRegistry);
    }

    public String build(String query,
                                      String systemPrompt,
                                      List<String> availableTools,
                                      List<String> observations,
                                      List<String> boundDocumentIds,
                                      List<String> boundDocumentTags,
                                      List<String> mandatoryTools,
                                      boolean requireToolBeforeFinal,
                                      boolean requireDocumentWebVerification,
                                      String documentSearchTool,
                                      String verificationWebSearchTool,
                                      Map<String, Object> runtimeAttributes) {
        StringBuilder prompt = new StringBuilder();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            prompt.append("System instruction: ").append(systemPrompt).append("\n\n");
        }
        String roleContext = AgentRoleAnalysisContext.promptSectionFromRuntime(
            runtimeAttributes, "DAG_BUILD_AND_TEMPLATE_DISCOVERY_PLANNING");
        if (!roleContext.isEmpty()) prompt.append(roleContext).append('\n');
        prompt.append("You are an agent planner.\n");
        prompt.append("Goal: produce a safe, executable InterpretationPlan for the MCP runtime.\n");
        ZoneId runtimeZone = runtimeZoneId(runtimeAttributes);
        LocalDate runtimeDate = LocalDate.now(clock.withZone(runtimeZone));
        prompt.append("Authoritative Runtime temporal context:\n");
        prompt.append("- Current date is ").append(runtimeDate).append(" in timezone ")
            .append(runtimeZone.getId()).append(".\n");
        prompt.append("- Resolve relative dates such as today/current/\u4eca\u5929/\u4eca\u65e5 from this Runtime value. "
            + "Never infer a different current date from model memory.\n");
        prompt.append("- Preserve relative wording in search keywords unless an exact date is required by the tool schema; "
            + "when an exact date is required, derive it only from this Runtime context.\n\n");
        prompt.append("Planning contract:\n");
        prompt.append("- Output exactly one InterpretationPlan JSON object. Do not output an envelope, markdown, code fences, comments, or natural language.\n");
        prompt.append("- Escape every ASCII double quote inside JSON string values (including Markdown answer text), or use Chinese corner quotes.\n");
        prompt.append("- The InterpretationPlan is the single source of truth for this loop iteration.\n");
        prompt.append("- Do not emit candidate_answer or another answer channel. A user-facing result belongs only in final_answer.input.answer.\n");
        prompt.append("- The user query MUST first be converted into this executable InterpretationPlan before any tool execution.\n");
        prompt.append("- Do not output legacy action/tool JSON such as {\"action\":\"tool\"} or {\"action\":\"final\"}.\n");
        prompt.append("- The plan is declarative. Do not claim that a tool has already run unless it appears in Observations so far.\n");
        prompt.append("- Use integer step ids starting at 1. Keep depends_on as explicit arrays of prior step ids.\n");
        prompt.append("- Include exactly one final_answer step. Put the user-facing answer in final_answer.input.answer only when observations are sufficient.\n");
        prompt.append("- final_answer.input.answer MUST be a polished Chinese Markdown document string, not a single plain paragraph. Do not wrap it in code fences.\n");
        prompt.append("- When the requested deliverable is a non-executed draft artifact, final_answer.input MUST include artifact_contract={artifact_type,delivery_mode:\"DRAFT\",execution_status:\"NOT_EXECUTED\",authorization_status,human_review_required,assumptions,disclosure}. Runtime consumes this structured contract and never infers artifact intent from query or answer keywords. Omit artifact_contract for normal answers and actual execution requests.\n");
        prompt.append("- For mcp_tool steps, tool_name MUST be one of the available tools and input MUST be the exact tool payload.\n");
        prompt.append("- If the user specifies an official source or website, preserve that source constraint in the relevant tool input.\n");
        prompt.append("- Use execution_policy.allow_tool only for tools intentionally approved by policy context; use deny_tool for tools that must never run.\n");
        prompt.append("- Use execution_policy.max_rewrite_times to bound automatic replanning; default to 1 for tool-backed plans.\n");
        prompt.append("- Use execution_policy.fallback_mode as safe_answer or partial_result when tools may fail.\n");
        prompt.append("- Do not set execution_policy.timeout_ms for MCP tools that may search, crawl, query data, or otherwise run for a long time; omit timeout_ms unless runtime policy explicitly provides one.\n");
        prompt.append("- Use execution_policy.tool_priority, cost_budget, latency_budget_ms, and accuracy_vs_speed when policy context constrains cost, latency, or quality.\n");
        prompt.append("- Every execution_policy.tool_priority value MUST be a number from 0.0 to 1.0. Higher priority means closer to 1.0; never use rank numbers such as 2.0.\n");
        prompt.append("- execution_policy.accuracy_vs_speed MUST also be from 0.0 to 1.0.\n");
        prompt.append("- Use plan.stability to lock critical nodes/tools/edges that optimizer and rewriter must preserve.\n");
        prompt.append("- Use plan.dependency_contracts only for dependency semantics. Required dependencies MUST also appear in target depends_on; optional dependencies need condition or reason.\n");
        prompt.append("- Mutually exclusive semantic paths MUST use first-class plan.branch_groups plus plan.conditional_edges, never optional dependency_contracts. Java computes Ready nodes; selection_strategy=llm may choose only from ready candidate_step_ids.\n");
        prompt.append("- For mutually exclusive semantic paths, declare each alternative as required=false with a non-empty, mutually exclusive condition and the same target step. Runtime will ask the model to choose only among those Ready alternatives. Do not use this pattern for additive work where every step must run.\n");
        prompt.append("- Add plan.edge_contracts when a later step needs a typed field from an earlier tool output.\n");
        prompt.append("- If information is missing, add missing_info and plan the smallest safe retrieval/tool step instead of inventing facts.\n\n");
        prompt.append("Diagnostic coverage contract:\n");
        prompt.append("- For health checks, incident diagnosis, capacity checks, or multi-resource operational analysis, add plan.diagnostic_profile.\n");
        prompt.append("- diagnostic_profile.checks must enumerate every evidence requirement stated by the user, including checks that cannot fit the current execution budget.\n");
        prompt.append("- Use reusable semantic capabilities and dimensions; never put a concrete template id, asset id, host name, database name, or environment-specific value in check_id or capability.\n");
        prompt.append("- diagnostic_profile checks may declare a positive weight for evidence importance. Weights are relative and must reflect diagnostic materiality, not template order or execution cost.\n");
        prompt.append("- diagnostic_profile.completion_policy may define retry_budget, max_attempts, high_confidence_threshold, and partial_evidence_threshold. Retries are bounded and must target missing evidence only.\n");
        prompt.append("- Map each planned evidence-producing step through step_ids. When max_steps cannot fit a required check, keep that check with step_ids=[] so Runtime reports execution_budget_exhausted instead of silently omitting it.\n");
        prompt.append("- Do not pre-fill health scores. Scores and confidence are produced only from explicit structured tool evidence; missing checks keep the overall assessment at INSUFFICIENT_EVIDENCE.\n\n");
        appendAgentBudgetContract(prompt, runtimeAttributes);
        prompt.append(AgentRuntimeFactGroundingContract.promptSection());
        prompt.append("MCP interaction contract:\n");
        prompt.append("- The user-bound workflow tool names, required flags, dependencies, and order are the execution contract. Do not insert, replace, or reorder tools based on intent, keywords, metadata, or returned template names.\n");
        prompt.append("- Template ids, template names, mcpToolName, and execution.callTool values returned by discovery are template names, not Agent Runtime workflow tool names.\n");
        prompt.append("- Do not put a returned template name into plan.steps[].tool_name. Put it into the template selector declared by the selected template contract and call only its configured workflow executor.\n");
        prompt.append("- Finding a template or asset is not execution evidence. final_answer must depend on the actual executor step or an explicit error/permission observation.\n\n");
        prompt.append("Sequential MCP batch contract:\n");
        prompt.append("- When two or more governed templates use an executor that declares template_execution and batch_execution, prefer one mcp_tool step whose input is {batchId, executionMode:\"SEQUENTIAL\", stopOnFailure:false, calls:[{callId,toolName,arguments}]}.\n");
        prompt.append("- Runtime owns a failure-isolated template execution layer: every compiled child must receive SUCCESS, EMPTY/RESULT_MISSING, BLOCKED, FAILED, or NOT_EXECUTED evidence. A child error must not stop later templates; stopOnFailure is compatibility-only and must be false.\n");
        prompt.append("- When one batch step supplies multiple diagnostic_profile checks, set every child callId to the exact diagnostic check_id it proves. Never map multiple checks to a single non-batch executor call; one successful scalar call is evidence for only its explicitly identified check.\n");
        prompt.append("- Keep calls in diagnostic priority order. Each call arguments object must satisfy that executor's normal authorized template contract; never include raw transport payloads, credentials, or fields forbidden by the tool-published protocol.\n");
        prompt.append("- A batch is one model planning decision but each child is one real remote tool invocation. Runtime validates and audits every child independently, persists its evidence immediately, continues after individual failures by default, and returns one ordered structured batch result.\n");
        prompt.append("- For diagnostic_profile checks backed by discovered templates, do not add a reasoning/aggregation step that copies template ids into named output fields and do not create edge contracts or bindings for such model-generated fields. Map the checks to the shared executor step; Runtime deterministically matches only the discovered, asset-scoped, authorized template metadata and compiles the child calls.\n");
        prompt.append("- Do not create one plan/rewrite/model round per template when all required template identifiers and parameters are already known. final_answer must depend on the single batch step.\n\n");
        appendAgentRuntimeEnvironmentContract(prompt, runtimeAttributes);
        prompt.append("Data template execution contract:\n");
        prompt.append("- Use only the exact tools present in Available tools and the configured workflow; tool metadata may describe input/output contracts but must not cause tool substitution.\n");
        prompt.append("- Model output is an untrusted candidate plan. Runtime owns argument compilation and rejects values that do not satisfy this contract before any MCP call.\n");
        prompt.append("- Use the fixed semantic ToolCall DSL: input.toolCall={toolName,action,parameters,context}. You select the workflow tool/action and state business values; Runtime resolves MCP metadata and compiles the concrete executor request.\n");
        prompt.append("- toolCall.parameters contains semantic business values only. toolCall.context contains purpose/step/dependency context and an optional logical target. Do not construct MCP transport fields, template parameter containers, binding positions, retries, or authorization fields.\n");
        prompt.append("- When a template must be discovered at runtime, plan template discovery before execution and do not guess undeclared parameter names. After discovery, the DAG controller emits ")
            .append(InterpretationExecutionProtocol.TEMPLATE_PARAMETER_PROTOCOL_VERSION)
            .append(" as an evidence-based parameter profile from the current user query and successful completed tool outputs; Runtime verifies every evidence reference and compiles it against the returned parameterSchema.\n");
        prompt.append("- The model may analyze, normalize and organize semantic parameter values, but it must cite either an exact user-query quote or an exact completed step_id/output_path. Runtime owns approval, defaults, routing and MCP execution.\n");
        prompt.append("- For a non-template tool with a missing published-Schema argument, you may propose input.contextParameterEvidence=[{parameter,source:'completed_step',stepId,outputPath}] or [{parameter,source:'user_query',quote,value}]. Add a DAG dependency on the cited step. Runtime re-reads the source and rejects invented or ambiguous values.\n");
        prompt.append("- template/templateId MUST be one scalar string copied from templates[i].templateId. A binding to templateId MUST select the leaf path templates[i].templateId; never bind templates[i], selectedTemplate, parameterSchema, parameterContract, or invocationExample as the template value.\n");
        prompt.append("- input.parameters contains execution VALUES only. parameterSchema, requiredParameters, parameterContract, and invocationExample are read-only metadata used to construct/validate those values and MUST NOT be copied into input.parameters.\n");
        prompt.append("- NEVER create a binding from templates[i].parameterSchema, requiredParameters, parameterContract, or invocationExample to parameters. If no required business parameter exists, keep input.parameters={} and omit that binding.\n");
        prompt.append("- Asset discovery returns the canonical name at $.assets[0].asset.name. Every binding into filters.assetName or executionContext.assetName MUST use exactly $.assets[0].asset.name; never use $.assets[0].assetName or $.assets[0].name.\n");
        prompt.append("- For each required cross-step value, declare both the executable plan.binding and its edge_contract using the same canonical source field.\n");
        prompt.append("- input.executionContext is a structured routing object only. Keep business arguments under input.parameters and never move schema metadata or an entire discovery result into executionContext.\n");
        prompt.append("- Read the selected discovery result's declared executor tool and execution context exactly as returned; use its parameter schema and required parameters only to construct values.\n");
        prompt.append("- Table/schema metadata is discovery evidence, not query-result evidence. A final answer that claims computed or retrieved data must depend on the declared executor step.\n");
        prompt.append("- If the configured workflow lacks a required discovery or executor tool, report the missing bound tool instead of inventing or selecting another tool.\n");
        prompt.append("- Never call a template executor with empty parameters when its discovered contract declares required parameters. Repair the plan with a prior discovery step or bind values from user input/tool output.\n");
        prompt.append("- Template execution must include the logical execution context declared by asset discovery, template routing metadata, metadata-location evidence, or an observed invocation example.\n");
        prompt.append("- Do not put JSONPath strings such as $.assets[0].asset.name inside executionContext. Use plan.bindings only when a prior observed step really returns that field.\n");
        prompt.append("- Do not put binding placeholders such as {{bindings.assetName}}, ${step1.value}, or empty stand-ins anywhere in step input. Use explicit bindings only and set input_path/input_field to the complete destination path, for example $.filters.assetName or $.executionContext.assetName.\n");
        prompt.append("- Never put a raw transport payload inside a discovered template's parameter container unless that exact field is declared by its parameter schema and allowed by its Protocol Driver.\n");
        prompt.append("- Do not invent template names or reuse an asset display name as an unrelated execution parameter; use only typed structured discovery outputs.\n\n");
        if (requireToolBeforeFinal) {
            prompt.append("Mandatory tool policy:\n");
            prompt.append("- This agent is bound to required runtime tools. Your response MUST be an InterpretationPlan that includes the required tool steps.\n");
            prompt.append("- Do not make the final_answer step independent until all required tools have been called and observed.\n");
            prompt.append("- Required tools are ordered by workflow or runtime policy: ").append(mandatoryTools).append("\n");
            prompt.append("- If no required tool has been observed yet, include the first required tool as the first executable mcp_tool step.\n");
            prompt.append("- Do not place a tool from a later workflow stage before earlier required stages have succeeded.\n");
            prompt.append("- Each later required tool step MUST depend_on the immediately previous required tool step, preserving the configured Agent workflow order.\n");
            prompt.append("- Tools listed in the same workflow parallel stage may be represented as independent steps with the same dependencies.\n");
            prompt.append("- If the user request is analytical, portfolio-related, market-related, data-driven, or requires validation, include the mandatory tools before final_answer.\n\n");
        }
        appendMcpWorkflowOrchestrationContract(prompt, runtimeAttributes);
        appendMcpControlPlaneToolContracts(prompt, availableTools);
        prompt.append(toolProtocolContracts.plannerSection(availableTools, toolRegistry));
        prompt.append("Respond with strict JSON only.\n");
        prompt.append("You MUST output ONLY a valid InterpretationPlan JSON following schema. No natural language.\n");
        prompt.append("If you cannot produce a valid plan, output a final_answer step whose input.answer explains the missing requirement.\n");
        prompt.append("InterpretationPlan JSON Schema:\n");
        prompt.append(InterpretationPlanJsonSchema.SCHEMA).append("\n\n");
        prompt.append("Final answer policy:\n");
        prompt.append("- Set review.self_check.tool_sufficiency=true only when observations already satisfy the user request without another tool call.\n");
        prompt.append("- When action is final_answer, write input.answer as Markdown with concise headings/lists where useful.\n");
        prompt.append("- Runtime policy may still reject final answers when required tool or verification constraints are incomplete.\n\n");
        prompt.append("Available tools:\n").append(describeTools(availableTools, runtimeAttributes)).append("\n");
        Object requiredToolParameters = runtimeAttributes == null
            ? null
            : runtimeAttributes.get("requiredToolParameters");
        if (requiredToolParameters instanceof Map<?, ?> required && !required.isEmpty()) {
            prompt.append("Runtime-required tool parameters (schema-validated and enforced during execution):\n")
                .append(required).append("\n")
                .append("- Do not remove or override these configured parameters in planned tool inputs.\n\n");
        }
        String resolvedDocumentSearchTool = firstNonBlank(documentSearchTool, DOCUMENT_SEARCH_TOOL);
        if (containsTool(availableTools, resolvedDocumentSearchTool)) {
            prompt.append("Document search contract:\n");
            prompt.append("- Treat ").append(resolvedDocumentSearchTool)
                .append(" as bounded topK evidence retrieval, not full-library exploration.\n");
            prompt.append("- Preserve the user's original document title phrase in input.query. Do not rewrite a title-like query into only a bag of keywords.\n");
            prompt.append("- Do not put document_ids, documentIds, fileIds, or file_ids into ").append(resolvedDocumentSearchTool)
                .append(" input unless the user explicitly asks to search only those exact document ids. Bound document ids are recall hints, not hard filters.\n");
            prompt.append("- If strict document-id scoping is explicitly required by the user, set strict_document_scope=true and explain that recall is limited to that scope.\n");
            prompt.append("- For document explanation questions, plan retrieval followed by evidence expansion/review before final_answer when evidence is title-only, partial, or ambiguous. Do not force max_steps=2 for document retrieval.\n");
            prompt.append("- For document retrieval plans, execution_policy.max_steps should allow retrieval plus expansion/review, normally at least 4 unless observations already contain sufficient evidence.\n");
            prompt.append("- If the document query is broad or ambiguous, rewrite it to include at least one concrete constraint such as entity, time, keyword, document title, code, or domain.\n");
            prompt.append("- If document retrieval returns empty, refine the query at most once; if the refined query is still empty, stop retrieval and plan an insufficient-evidence answer.\n");
            prompt.append("- Do not plan wildcard, exhaustive, or full-dataset document search strategies.\n\n");
        }
        String discoverySearchTool = preferredWebSearchTool(availableTools);
        String crawlerTool = preferredCrawlerTool(availableTools);
        if (discoverySearchTool != null) {
            prompt.append("Unified search contract:\n");
            prompt.append("- Pass the user's original query to the governed search capability. Internal source routing is owned by the tool implementation; do not invent hidden tools, source identifiers, or dataset codes.\n\n");
        }
        if (discoverySearchTool != null && crawlerTool != null) {
            prompt.append("Web evidence workflow:\n");
            prompt.append("1. Use ").append(discoverySearchTool)
                .append(" and other web discovery tools only to discover candidate pages, page links, search routes, and short snippets. Do not treat discovery snippets as final evidence.\n");
            prompt.append("2. After a web discovery tool returns candidates, runtime will ask the model to choose relevant URLs.\n");
            prompt.append("3. Then call ").append(crawlerTool)
                .append(" to fetch cleaned full page content from the selected URL before analysis.\n");
            prompt.append("4. The final_answer step MUST depend on the crawler/content step, not only on web discovery.\n");
            prompt.append("5. If an official website or exchange site is required, keep that source constraint in the web discovery query/input.\n\n");
            prompt.append("web_search retrieves standardized local news and, when the internal Tencent WSA enhancer is configured, "
                + "can supplement current hotspots, place names, and knowledge beyond the local corpus from the public web. "
                + "The external provider remains an internal implementation detail, not a separate tool.\n\n");
            prompt.append("Crawler input contract:\n");
            prompt.append("- Never use ").append(crawlerTool).append(" as a search tool. It cannot accept a free-text query.\n");
            prompt.append("- ").append(crawlerTool).append(" may only be called with an absolute URL selected from prior web discovery results and accepted by its published input schema.\n");
            prompt.append("- If no URL has been observed yet, call a web discovery tool first and do not call ").append(crawlerTool).append(".\n\n");
            prompt.append("Binding contract:\n");
            prompt.append("- Use plan.bindings for data flow from one step output to another step input. edge_contracts only validate data shape; they do not populate inputs.\n");
            prompt.append("- When ").append(crawlerTool).append(" depends on ").append(discoverySearchTool)
                .append(", bind the selected search result URL into crawler input url, e.g. {\"from\":1,\"output_path\":\"$.results[0].url\",\"to\":2,\"input_field\":\"url\",\"type\":\"jsonpath\"}.\n");
            prompt.append("- Do not use placeholder inputs such as {\"url\":\"\"} or template strings such as ${step1.results[0].url}; use plan.bindings instead.\n\n");
        }
        if (!boundDocumentIds.isEmpty() || !boundDocumentTags.isEmpty()) {
            prompt.append("Knowledge document recall hints:\n");
            if (!boundDocumentIds.isEmpty()) {
                prompt.append("- document_ids: ").append(boundDocumentIds).append("\n");
            }
            if (!boundDocumentTags.isEmpty()) {
                prompt.append("- tags: ").append(boundDocumentTags).append("\n");
            }
            prompt.append("Document workflow:\n");
            prompt.append("1. If the user asks about research material, reports, files, or document-backed facts, call ")
                .append(resolvedDocumentSearchTool)
                .append(" first.\n");
            prompt.append("2. Keep ").append(resolvedDocumentSearchTool)
                .append(" open-recall by default. Use tags as soft context when useful; do not use document_ids as a hard input filter unless the user explicitly requested exact document-id scoping.\n");
            prompt.append("3. Use retrieved evidence as the basis of the final answer; if evidence is insufficient, say what is missing.\n");
            prompt.append("4. Do not invent facts beyond retrieved documents and tool observations.\n\n");
        }
        if (requireDocumentWebVerification) {
            prompt.append("Document-web verification workflow:\n");
            prompt.append("1. Call ").append(documentSearchTool).append(" first to retrieve internal knowledge evidence.\n");
            prompt.append("2. Then call ").append(verificationWebSearchTool).append(" to validate and supplement with collected news evidence.\n");
            prompt.append("3. Do not return a final answer until both ").append(documentSearchTool).append(" and ")
                .append(verificationWebSearchTool)
                .append(" have been observed.\n");
            prompt.append("4. In the final answer, separate internal document evidence from collected news evidence.\n");
            prompt.append("5. If the two sources conflict, explicitly state the conflict and compare their publication times and source provenance.\n\n");
        }
        if (!observations.isEmpty()) {
            prompt.append("Observations so far:\n");
            observations.forEach(ob -> prompt.append("- ").append(ob).append("\n"));
            prompt.append("Citation requirement:\n");
            prompt.append("- If observations include web citation labels such as [\u7f51\u98751], cite web-derived statements with the matching label immediately after the sentence.\n");
            prompt.append("- Do not cite web facts without a matching citation label from the observations.\n");
            prompt.append("\n");
        }
        prompt.append("User query:\n").append(boundedUserQuery(query));
        return prompt.toString();
    }

    private void appendAgentBudgetContract(StringBuilder prompt, Map<String, Object> runtimeAttributes) {
        AgentPlanBudgetPolicy.BudgetCaps caps = AgentPlanBudgetPolicy.fromRuntimeAttributes(runtimeAttributes);
        if (!caps.configured()) {
            return;
        }
        prompt.append("Agent-configured smart decision budget (authoritative hard ceilings):\n");
        if (caps.maxSteps() != null) {
            prompt.append("- execution_policy.max_steps MUST be <= ").append(caps.maxSteps()).append(".\n");
            prompt.append("- The complete plan, including final_answer, MUST contain no more than ")
                .append(caps.maxSteps()).append(" steps.\n");
        }
        if (caps.costBudget() != null) {
            prompt.append("- execution_policy.cost_budget MUST be <= ").append(caps.costBudget()).append(".\n");
        }
        if (caps.latencyBudgetMs() != null) {
            prompt.append("- execution_policy.latency_budget_ms MUST be <= ")
                .append(caps.latencyBudgetMs()).append(".\n");
        }
        prompt.append("- You may choose a smaller budget for this task, but never raise or ignore an Agent-configured ceiling.\n\n");
    }

    private void appendMcpWorkflowOrchestrationContract(StringBuilder prompt, Map<String, Object> runtimeAttributes) {
        List<Map<String, Object>> authoritativeDag = objectMapList(runtimeAttributes == null
            ? null : runtimeAttributes.get("authoritativeWorkflowDag"));
        if (!authoritativeDag.isEmpty()) {
            appendAuthoritativeWorkflowContract(prompt, authoritativeDag);
            return;
        }
        Map<String, Object> workflow = workflowConfigMap(runtimeAttributes == null ? null : runtimeAttributes.get("mcpWorkflow"));
        if (workflow.isEmpty()) {
            return;
        }
        Object enabled = workflow.get("enabled");
        if (enabled instanceof Boolean bool && !bool) {
            return;
        }
        Object steps = firstObject(workflow, "steps", "workflowSteps");
        if (!(steps instanceof List<?> list) || list.isEmpty()) {
            return;
        }
        prompt.append("MCP tool orchestration contract from current Agent Runtime OS:\n");
        prompt.append("- Treat this workflow as a mandatory reasoning and execution graph, not a loose tool suggestion.\n");
        prompt.append("- The InterpretationPlan MUST preserve every required step, required dependency, condition, and confirmation node from this workflow.\n");
        prompt.append("- Workflow dependencies may be required or optional. Required dependencies must become plan.dependency_contracts required=true and the matching target step depends_on. Optional dependencies must become required=false with condition/reason, and should only become executable steps when the user request needs them.\n");
        prompt.append("- When a workflow step has dependsOn, treat it as required unless the workflow explicitly marks it optional. The matching plan step MUST depend_on the referenced prior workflow tool step.\n");
        prompt.append("- When a workflow step has confirmation, keep that tool as its own mcp_tool step so runtime can request/record confirmation at that node.\n");
        int index = 1;
        for (Object item : list) {
            Map<String, Object> step = asMap(item);
            if (step.isEmpty()) {
                index++;
                continue;
            }
            String tool = stringValue(firstObject(step, "tool", "toolName"));
            List<String> parallelSteps = stringList(firstObject(step, "parallelSteps", "parallel_steps"));
            if ((tool == null || tool.isBlank()) && parallelSteps.isEmpty()) {
                index++;
                continue;
            }
            String order = stringValue(firstObject(step, "step", "order"));
            Boolean required = booleanObject(step.get("required"));
            List<String> dependsOn = stringList(firstObject(step, "dependsOn", "depends_on"));
            List<String> optionalDependsOn = stringList(firstObject(step, "optionalDependsOn", "optional_depends_on", "optionalDependencies", "optional_dependencies"));
            String condition = stringValue(step.get("condition"));
            String confirmation = stringValue(step.get("confirmation"));
            prompt.append("  step ").append(firstNonBlank(order, String.valueOf(index))).append(": ");
            if (tool != null && !tool.isBlank()) {
                prompt.append("tool=").append(tool);
            }
            if (!parallelSteps.isEmpty()) {
                prompt.append(tool == null || tool.isBlank() ? "" : ", ")
                    .append("parallelSteps=").append(parallelSteps);
            }
            prompt.append(", required=").append(!Boolean.FALSE.equals(required));
            if (!dependsOn.isEmpty()) {
                prompt.append(", dependsOn=").append(dependsOn);
            }
            if (!optionalDependsOn.isEmpty()) {
                prompt.append(", optionalDependsOn=").append(optionalDependsOn);
            }
            if (condition != null && !condition.isBlank()) {
                prompt.append(", condition=").append(condition);
            }
            if (confirmation != null && !confirmation.isBlank()) {
                prompt.append(", confirmation=").append(confirmation);
            }
            prompt.append("\n");
            index++;
        }
        Map<String, Object> executionStrategy = asMap(firstObject(workflow, "executionStrategy", "execution_strategy"));
        if (!executionStrategy.isEmpty()) {
            prompt.append("- executionStrategy=").append(executionStrategy).append("\n");
        }
        prompt.append("\n");
    }

    private void appendAuthoritativeWorkflowContract(StringBuilder prompt,
                                                       List<Map<String, Object>> authoritativeDag) {
        prompt.append("MCP tool orchestration contract from current Agent Runtime OS:\n");
        prompt.append("- This is the model-facing leaf-capability projection of the user-configured workflow.\n");
        prompt.append("- Abstract parent tools are internal Runtime delegation boundaries and MUST NOT appear in the InterpretationPlan.\n");
        prompt.append("- Preserve every listed tool and dependency exactly; independently selected sibling implementations remain independent workflow nodes.\n");
        int index = 1;
        for (Map<String, Object> node : authoritativeDag) {
            String id = stringValue(firstObject(node, "id", "step"));
            String tool = stringValue(firstObject(node, "tool", "toolName", "tool_name"));
            if (tool == null || tool.isBlank()) {
                continue;
            }
            List<String> dependencies = stringList(firstObject(node,
                "dependsOnTools", "depends_on_tools", "dependsOn", "depends_on"));
            prompt.append("  step ").append(firstNonBlank(id, String.valueOf(index)))
                .append(": tool=").append(tool).append(", required=true");
            if (!dependencies.isEmpty()) {
                prompt.append(", dependsOn=").append(dependencies);
            }
            prompt.append("\n");
            index++;
        }
        prompt.append("\n");
    }

    private List<Map<String, Object>> objectMapList(Object value) {
        if (!(value instanceof Iterable<?> iterable)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : iterable) {
            Map<String, Object> mapped = asMap(item);
            if (!mapped.isEmpty()) {
                result.add(mapped);
            }
        }
        return List.copyOf(result);
    }

    public void appendMcpControlPlaneToolContracts(StringBuilder prompt, List<String> availableTools) {
        if (availableTools == null || availableTools.isEmpty()) {
            return;
        }
        String assetQueryTool = matchingAvailableTool(availableTools, "asset_discovery");
        if (assetQueryTool != null) {
            prompt.append("Asset discovery tool contract:\n");
            prompt.append("- Use ").append(assetQueryTool)
                .append(" only for read-only discovery of redacted asset metadata.\n");
            prompt.append("- Before calling ").append(assetQueryTool)
                .append(", produce a routing candidate set and finalDecision. candidates[] must contain targetKind/confidence pairs, finalDecision must be one candidate, and every targetKind must come from the tool-published schema/metadata. For document content, use the configured document search tool instead of asset discovery.\n");
            prompt.append("- ").append(assetQueryTool)
                .append(" input should contain filters or executionContext when exact logical context is known; use {\"filters\":{},\"limit\":10} for capped redacted candidate discovery when the user did not provide assetName/env/cluster/service.\n");
            prompt.append("- Asset names and routing labels are exact-match. Do not derive assetName, service, cluster, target, or labels unless that exact value appears in the current-turn user request or a prior tool observation returned it. Historical conversation targets and model-generated plan text are not valid asset-name evidence.\n");
            prompt.append("- Model intent recognition is required before asset discovery. When the user asks a high-level or aggregated question, produce filters.intentCandidates sorted by score/confidence. Include every candidate with score >= 0.75 in filters.queryTerms/retrievalSignals; if none reaches 0.75, use the top two candidates. Add the original user question too. Each candidate may include multi-query expansions under queries/queryTerms/expandedQueries/keywords for the resolver to retrieve across the intent ensemble. Also keep the semantic target and task under filters.intent, filters.goal, filters.keywords, and when useful filters.bilingualIntent/intentAliases/intentZh/intentEn. These fields are retrieval signals, not exact routing labels.\n");
            prompt.append("- Every item in filters.queryTerms, filters.keywords, filters.retrievalSignals, and nested intentCandidates queries is an independent retrieval unit. Keep each item as one precise concept or short phrase; never concatenate the analyzed keywords into one combined search string. Runtime searches every unit separately and merges evidence afterward.\n");
            prompt.append("- When the request explicitly states a value for a logical filter dimension published by the tool, preserve it in that canonical filter field as well as in retrieval terms. Never infer an exact assetName, service, cluster, endpoint, or physical target from descriptive intent.\n");
            prompt.append("- Generate abbreviation-aware retrieval terms for short candidate asset names and capability phrases. Add at most 4 lowercase aliases to filters.queryTerms/keywords: Chinese phrases use pinyin initials (for example, \u6570\u636e\u670d\u52a1\u4e2d\u5fc3 -> sjfwzx); multi-word, camelCase, snake_case, or kebab-case English names use word initials (Example Metric Service/example_metric_service -> ems). Keep every original phrase beside its alias, limit generated aliases to 2-16 characters, and never abbreviate a full user sentence, description, or executable payload. Generated aliases are weak retrieval signals only; never put them in assetName, service, cluster, labels, template, or templateId. If the user supplied a compact abbreviation, preserve it and add a plausible full phrase only when supported by the request; do not guess an exact registered identity.\n");
            prompt.append("- Never concatenate an assetName with descriptive text, asset type, capability, or assumption. For example, keep the user-provided asset phrase unchanged; if the exact asset name is uncertain, omit filters.assetName and use semantic retrieval filters instead of pretending the phrase is an exact registered name.\n");
            prompt.append("- Do not invent service labels such as service:<topic> from natural-language topic words until an asset/tool observation proves they are registered routing labels.\n");
            prompt.append("- Valid input shape when no exact target clue is known: {\"candidates\":[{\"targetKind\":\"<kind-from-tool-metadata>\",\"confidence\":0.82}],\"finalDecision\":\"<same-kind>\",\"filters\":{},\"trace\":{\"plannerVersion\":\"v1.1\",\"model\":\"<model>\"},\"limit\":10}.\n");
            prompt.append("- When only an aggregate target clue and task intent are known, keep the clue under semantic intentCandidates/queryTerms and omit exact routing labels until discovery returns them.\n");
            prompt.append("- When an exact asset identity is user-provided or previously observed, use the tool-published targetKind and canonical identity filter without altering the value.\n");
            prompt.append("- Use routing labels only when they were explicitly provided by the user or returned by asset metadata, and only in fields published by the tool schema.\n");
            prompt.append("- The response contains the single canonical asset view in assets[]. Use assets[0].asset.type for asset type, assets[0].asset.environment, assets[0].asset.name, assets[0].asset.toolName, and assets[0].capabilities.allowedCommandTemplates[].templateId or allowedCommandTemplateIds[] only as authorization context, not as semantic ranking. Do not require top-level assetType because it is query scope and may be null when the request did not preselect an asset type.\n");
            prompt.append("- Do not pass concrete physical target or connection fields forbidden by the tool-published contract.\n\n");
            prompt.append("- Do not replace ").append(assetQueryTool)
                .append(" with a reasoning step that guesses env, service, cluster, or target. If broad discovery returns multiple plausible assets, ask the user for logical context.\n\n");
        }
        String templateQueryTool = matchingAvailableTool(availableTools, "template_discovery");
        if (templateQueryTool != null) {
            prompt.append("Template discovery tool contract:\n");
            prompt.append("- Use ").append(templateQueryTool)
                .append(" only for read-only discovery of registered execution templates.\n");
            prompt.append("- Before calling ").append(templateQueryTool)
                .append(", produce a routing candidate set and finalDecision. candidates[] must contain targetKind/confidence pairs, and finalDecision must be one of the candidates. Runtime resolves the selected target kind from the tool metadata and user-selected scope. For document, use document search instead of template discovery.\n");
            prompt.append("- ").append(templateQueryTool)
                .append(" returns ranked template candidates in templates[]. It never returns raw executable payloads or executionSpec.\n");
            prompt.append("- Template discovery is candidate recall, not final selection: always request limit >= 10 so multi-part intents can retrieve complementary templates. The runtime review stage may select one or more returned templates for execution.\n");
            prompt.append("- Query it when the plan/user-configured dependency requires template discovery before execution. Prefer filters.assetName and filters.env from the prior typed asset discovery result.\n");
            prompt.append("- If the user asks for a capability and no exact asset context is known, query by candidate set plus intent, for example {\"candidates\":[{\"targetKind\":\"host\",\"confidence\":0.82},{\"targetKind\":\"database\",\"confidence\":0.51}],\"finalDecision\":\"host\",\"filters\":{\"intent\":\"<user-capability-intent>\"},\"trace\":{\"plannerVersion\":\"v1.1\",\"model\":\"<model>\"},\"limit\":10}; still use a returned templateId exactly.\n");
            prompt.append("- Valid host input example: {\"candidates\":[{\"targetKind\":\"host\",\"confidence\":0.9}],\"finalDecision\":\"host\",\"filters\":{\"assetName\":\"<asset-name-from-typed-asset-discovery>\",\"env\":\"<env-from-typed-asset-discovery>\",\"intent\":\"<user-capability-intent>\"},\"trace\":{\"plannerVersion\":\"v1.1\",\"model\":\"<model>\"},\"limit\":10}.\n");
            prompt.append("- For template discovery filters, if the user intent contains database/component/metric/action names, include both Chinese and English retrieval terms. filters.intent keeps the user's original natural-language intent; filters.bilingualIntent must include Chinese aliases and English technical terms; filters.intentZh and filters.intentEn should split the primary Chinese/English intent; filters.intentAliases must include Chinese aliases plus English technical terms; filters.keywords must include canonical DB/component keywords, command names, metric names, and common aliases. Do not rely on Chinese-only or English-only intent for template retrieval.\n");
            prompt.append("- Put each analyzed template capability (for example transaction history, filled orders, asset snapshot, and profit/loss) in a separate filters.queryTerms/keywords item. Do not join capabilities into one query: each array item is searched and evaluated independently before candidates are unioned and deduplicated.\n");
            prompt.append("- Apply the same abbreviation-aware expansion used by asset discovery to template naming/capability phrases: add at most 4 lowercase Chinese pinyin-initial or English word-initial aliases to filters.queryTerms/keywords, retain the original phrases, and keep each alias between 2 and 16 characters. Never generate an alias as template/templateId or use abbreviations derived from descriptions, executable payloads, or the whole user sentence.\n");
            prompt.append("- Valid database input example: {\"candidates\":[{\"targetKind\":\"database\",\"confidence\":0.9}],\"finalDecision\":\"database\",\"filters\":{\"assetName\":\"<asset-name-from-typed-asset-discovery>\",\"env\":\"<env-from-typed-asset-discovery>\",\"intent\":\"<database-query-intent>\",\"bilingualIntent\":[\"<Chinese alias>\",\"<English technical term>\"],\"intentZh\":\"<Chinese intent>\",\"intentEn\":\"<English technical intent>\",\"intentAliases\":[\"<Chinese alias>\",\"<English technical term>\"],\"keywords\":[\"<canonical command or metric>\",\"<Chinese keyword>\",\"<English keyword>\"]},\"trace\":{\"plannerVersion\":\"v1.1\",\"model\":\"<model>\"},\"limit\":10}.\n");
            prompt.append("- templates[] is ranked by relevanceScore. Choose the returned template or complementary templates whose name, description, intentSignals, matchReasons, output schema, and asset type collectively cover the user intent; do not blindly bind the first result or the first asset allowedCommandTemplates item.\n");
            prompt.append("- If the selected template's parameterSchema.required is non-empty, include a parameters object in the execution tool input with exactly those required fields; never place template parameters at the top level.\n");
            prompt.append("- Also read templates[].requiredParameters, templates[].parameterContract, and templates[].invocationExample. These fields are authoritative. Put required values only in parameterContract.argumentContainer.\n");
            prompt.append("- Do not invent template ids if ").append(templateQueryTool)
                .append(" returns no suitable template; ask the user/admin to register or allow one.\n\n");
        }
    }

    public String describeTools(List<String> availableTools, Map<String, Object> runtimeAttributes) {
        if (availableTools == null || availableTools.isEmpty()) {
            return "- (none)";
        }
        StringBuilder sb = new StringBuilder();
        for (String toolName : availableTools) {
            ToolMetadata metadata = toolRegistry.getToolMetadata(toolName);
            String configuredDescription = configuredToolDescription(toolName, runtimeAttributes);
            if (metadata != null) {
                sb.append("- ")
                    .append(toolName)
                    .append(": ")
                    .append(firstNonBlank(configuredDescription, metadata.getDescription()))
                    .append("\n");
                appendApplicability(sb, metadata);
                appendBatchInputSchema(sb, toolName, metadata);
            } else {
                ToolRegistry.Tool simpleTool = toolRegistry.getTool(toolName);
                String description = simpleTool == null ? "No description available" : simpleTool.getDescription();
                sb.append("- ").append(toolName).append(": ")
                    .append(firstNonBlank(configuredDescription, description))
                    .append("\n");
            }
        }
        return sb.toString();
    }

    private void appendBatchInputSchema(StringBuilder prompt, String toolName, ToolMetadata metadata) {
        if (prompt == null || metadata == null || !ToolCallBatchSchema.supports(toolName, metadata)) {
            return;
        }
        Map<String, Object> values = asMap(metadata.getMetadata());
        Map<String, Object> schema = asMap(values.get("inputSchema"));
        if (schema.isEmpty()) {
            schema = ToolCallBatchSchema.augmentDeclared(Map.of());
        }
        try {
            prompt.append("  Formal runtime inputSchema: ")
                .append(objectMapper.writeValueAsString(schema))
                .append("\n");
        } catch (Exception ex) {
            log.debug("Unable to serialize batch input schema for tool={}: {}", toolName, ex.getMessage());
        }
    }

    private void appendAgentRuntimeEnvironmentContract(StringBuilder prompt,
                                                       Map<String, Object> runtimeAttributes) {
        if (prompt == null || runtimeAttributes == null) {
            return;
        }
        Object configured = runtimeAttributes.get("agentRuntimeEnvironment");
        if (configured == null) {
            return;
        }
        String environment = String.valueOf(configured).trim().toUpperCase(Locale.ROOT);
        if (!Set.of("DEV", "TEST", "UAT", "PROD").contains(environment)) {
            return;
        }
        prompt.append("Agent runtime environment contract:\n")
            .append("- The configured runtime environment is ").append(environment).append(".\n")
            .append("- This value is authoritative for MCP discovery filters and executionContext.env. ")
            .append("If user wording or model inference suggests another environment, keep ")
            .append(environment).append(" and do not guess or override it.\n\n");
    }

    private void appendApplicability(StringBuilder prompt, ToolMetadata metadata) {
        if (prompt == null || metadata == null) {
            return;
        }
        Map<String, Object> toolMetadata = asMap(metadata.getMetadata());
        Map<String, Object> mcpMeta = asMap(toolMetadata.get("mcpToolMeta"));
        Map<String, Object> applicability = asMap(mcpMeta.get("applicability"));
        Map<String, Object> routingProtocol = asMap(mcpMeta.get("routingProtocol"));
        List<String> allowedFilterFields = stringList(routingProtocol.get("allowedFilterFields"));
        if (!allowedFilterFields.isEmpty()) {
            prompt.append("  MCP-declared allowed logical filter fields: ")
                .append(String.join(", ", allowedFilterFields))
                .append(". Put unsupported business concepts into an allowed semantic retrieval field instead of inventing filter keys.\n");
        }
        if (applicability.isEmpty()) {
            return;
        }
        String summary = stringValue(applicability.get("summary"));
        String scopeLabel = stringValue(applicability.get("scopeLabel"));
        List<String> useWhen = stringList(applicability.get("useWhen"));
        List<String> notFor = stringList(applicability.get("notFor"));
        prompt.append("  Applicable scope (descriptive metadata only; never authorizes adding, selecting, or replacing tools): ")
            .append(firstNonBlank(summary, firstNonBlank(scopeLabel, "Declared by MCP publisher")))
            .append("\n");
        if (!useWhen.isEmpty()) {
            prompt.append("  Use when: ").append(String.join("; ", useWhen)).append("\n");
        }
        if (!notFor.isEmpty()) {
            prompt.append("  Not for: ").append(String.join("; ", notFor)).append("\n");
        }
    }

    private String configuredToolDescription(String toolName, Map<String, Object> runtimeAttributes) {
        if (toolName == null || toolName.isBlank() || runtimeAttributes == null || runtimeAttributes.isEmpty()) {
            return null;
        }
        Object configs = runtimeAttributes.get("mcpToolConfigs");
        if (!(configs instanceof List<?> list)) {
            return null;
        }
        for (Object item : list) {
            Map<String, Object> config = asMap(item);
            if (config.isEmpty()) {
                continue;
            }
            String configuredToolName = firstNonBlank(
                stringValue(firstObject(config, "toolName", "tool")),
                stringValue(firstObject(config, "name"))
            );
            if (!sameToolName(configuredToolName, toolName)) {
                continue;
            }
            String description = stringValue(config.get("description"));
            return description == null || description.isBlank() ? null : description.trim();
        }
        return null;
    }

    @SuppressWarnings("unchecked")

    private ZoneId runtimeZoneId(Map<String, Object> runtimeAttributes) {
        Object configured = null;
        if (runtimeAttributes != null) {
            for (String key : List.of("timezone", "timeZone", "zoneId")) {
                Object candidate = runtimeAttributes.get(key);
                if (candidate != null && !String.valueOf(candidate).isBlank()) {
                    configured = candidate;
                    break;
                }
            }
        }
        if (configured != null) {
            try {
                return ZoneId.of(String.valueOf(configured).trim());
            } catch (Exception ignored) {
                // Invalid request values cannot replace the server Runtime timezone.
            }
        }
        return ZoneId.systemDefault();
    }

    private String preferredWebSearchTool(List<String> availableTools) {
        return normalizeList(availableTools).stream()
            .filter(this::isWebSearchTool)
            .findFirst().orElse(null);
    }

    private String preferredCrawlerTool(List<String> availableTools) {
        List<String> tools = normalizeList(availableTools);
        String crawlUrl = tools.stream()
            .filter(tool -> "crawl_url".equals(toolSemanticKey(tool)) || toolSemanticKey(tool).endsWith("_crawl_url"))
            .findFirst().orElse(null);
        return crawlUrl != null ? crawlUrl : tools.stream().filter(this::isCrawlerTool).findFirst().orElse(null);
    }

    private boolean isWebSearchTool(String toolName) {
        String semantic = toolSemanticKey(toolName);
        return semantic.equals("web_search") || semantic.endsWith("_web_search") || semantic.contains("web_search");
    }

    private boolean isWebDiscoveryTool(String toolName) {
        String semantic = toolSemanticKey(toolName);
        return isWebSearchTool(toolName)
            || semantic.equals("web_page_analyze") || semantic.contains("web_page_analyze")
            || semantic.equals("site_intelligence_resolver") || semantic.contains("site_intelligence")
            || semantic.equals("finance_site_search") || semantic.contains("finance_site_search")
            || semantic.equals("generic_web_site_search") || semantic.contains("generic_web_site_search")
            || semantic.equals("web_site_search")
            || (semantic.contains("site_search") && !semantic.contains("search_and_extract"));
    }

    private boolean isCrawlerTool(String toolName) {
        String semantic = toolSemanticKey(toolName);
        return !isWebDiscoveryTool(toolName) && (semantic.equals("crawl_url") || semantic.contains("crawl")
            || semantic.contains("crawler") || semantic.contains("fetch_page") || semantic.contains("page_content")
            || semantic.contains("download") || semantic.contains("extract"));
    }

    private boolean containsTool(List<String> tools, String toolName) {
        return toolName != null && !toolName.isBlank() && tools != null
            && tools.stream().anyMatch(tool -> sameToolName(tool, toolName));
    }

    private String boundedUserQuery(String query) {
        if (query == null || query.length() <= MAX_USER_QUERY_PROMPT_CHARS) return query == null ? "" : query;
        int tailLength = MAX_USER_QUERY_PROMPT_CHARS / 4;
        int headLength = MAX_USER_QUERY_PROMPT_CHARS - tailLength;
        int omitted = query.length() - headLength - tailLength;
        return query.substring(0, headLength) + "\n...[user query truncated " + omitted
            + " chars; preserving tail]...\n" + query.substring(query.length() - tailLength);
    }

    private Map<String, Object> workflowConfigMap(Object rawWorkflow) {
        if (rawWorkflow instanceof List<?> list) {
            Map<String, Object> workflow = new LinkedHashMap<>();
            workflow.put("enabled", true);
            workflow.put("steps", list);
            return workflow;
        }
        return asMap(rawWorkflow);
    }

    private boolean sameToolName(String first, String second) {
        return first != null && second != null && toolSemanticKey(first).equals(toolSemanticKey(second));
    }

    private String matchingAvailableTool(List<String> availableTools, String semanticToolName) {
        if (availableTools == null || semanticToolName == null) return null;
        List<String> matches = new ArrayList<>();
        for (String availableTool : availableTools) {
            String semantic = toolSemanticKey(availableTool);
            ToolWorkflowRole role = workflowRole(availableTool);
            if (("asset_discovery".equals(semanticToolName) && role == ToolWorkflowRole.ASSET_DISCOVERY)
                || (("template_discovery".equals(semanticToolName) || "template_query".equals(semanticToolName)) && role == ToolWorkflowRole.TEMPLATE_DISCOVERY)
                || semanticToolName.equals(semantic)
                || ("asset_discovery".equals(semanticToolName) && ("asset_query".equals(semantic) || "asset_search".equals(semantic)))
                || ("template_discovery".equals(semanticToolName) && "template_query".equals(semantic))
                || ("asset_discovery".equals(semanticToolName) && (semantic.endsWith("_asset_query") || semantic.endsWith("_asset_search")))
                || ("template_discovery".equals(semanticToolName) && (semantic.endsWith("_template_query") || semantic.endsWith("_template_search")))
                || ("asset_query".equals(semanticToolName) && (semantic.endsWith("_asset_query") || semantic.endsWith("_asset_search")))
                || ("template_query".equals(semanticToolName) && (semantic.endsWith("_template_query") || semantic.endsWith("_template_search")))) {
                matches.add(availableTool);
            }
        }
        if (matches.isEmpty()) return null;
        List<String> mostSpecific = capabilityHierarchy.mostSpecific(matches);
        if (mostSpecific.size() == 1) {
            String selected = mostSpecific.get(0);
            return capabilityHierarchy.directlyInvocable(selected) ? selected : null;
        }
        return matches.stream()
            .filter(tool -> capabilityHierarchy.node(tool).map(node -> node.abstractCapability() || !node.businessImplementation()).orElse(true))
            .filter(capabilityHierarchy::directlyInvocable).findFirst().orElse(null);
    }

    private String toolSemanticKey(String toolName) {
        if (toolName == null) return "";
        String normalized = toolName.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        while (normalized.startsWith("mcp_")) normalized = normalized.substring(4);
        for (String prefix : List.of("chatchat_mcp_server_", "chatchat_", "xxx_")) {
            while (normalized.startsWith(prefix)) normalized = normalized.substring(prefix.length());
        }
        return normalized;
    }

    private ToolWorkflowRole workflowRole(String toolName) {
        if (toolRegistry != null) {
            ToolWorkflowRole role = toolRegistry.getWorkflowRole(toolName);
            return role != null ? role : ToolWorkflowContract.resolveRole(toolName, toolRegistry.getToolMetadata(toolName));
        }
        return ToolWorkflowContract.resolveRole(toolName, null);
    }

    private Map<String, Object> asMap(Object data) {
        if (!(data instanceof Map<?, ?> map)) return Map.of();
        Map<String, Object> values = new LinkedHashMap<>();
        map.forEach((key, value) -> { if (key != null) values.put(String.valueOf(key), value); });
        return values;
    }
}
