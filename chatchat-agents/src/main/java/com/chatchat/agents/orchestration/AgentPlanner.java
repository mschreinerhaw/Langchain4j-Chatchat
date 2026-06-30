package com.chatchat.agents.orchestration;

import com.chatchat.agents.protocol.ModelProtocolJson;
import com.chatchat.agents.runtime.plan.InterpretationPlan;
import com.chatchat.agents.runtime.plan.InterpretationPlanJsonSchema;
import com.chatchat.agents.runtime.plan.InterpretationPlanValidator;
import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.tool.ToolMetadata;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Builds planner prompts and parses planner decisions.
 */
@Slf4j
class AgentPlanner {

    private static final String DOCUMENT_SEARCH_TOOL = "document_search";
    private static final String FINAL = "final";
    private static final String TOOL = "tool";
    private static final int DEFAULT_PLAN_REPAIR_ATTEMPTS = 3;
    private static final int MAX_PLAN_REPAIR_ATTEMPTS = 3;

    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;
    private final InterpretationPlanValidator interpretationPlanValidator = new InterpretationPlanValidator();

    AgentPlanner(ToolRegistry toolRegistry, ObjectMapper objectMapper) {
        this.toolRegistry = toolRegistry;
        this.objectMapper = objectMapper;
    }

    AgentDecision decideNextAction(ChatModel activeChatModel,
                                   String query,
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
        String prompt = buildPlannerPrompt(
            query,
            systemPrompt,
            availableTools,
            observations,
            boundDocumentIds,
            boundDocumentTags,
            mandatoryTools,
            requireToolBeforeFinal,
            requireDocumentWebVerification,
            documentSearchTool,
            verificationWebSearchTool,
            runtimeAttributes
        );
        PlannerValidationContext validationContext = new PlannerValidationContext(
            normalizeList(mandatoryTools),
            requireToolBeforeFinal,
            requireDocumentWebVerification,
            documentSearchTool,
            verificationWebSearchTool,
            normalizeList(availableTools),
            query
        );
        String runId = stringValue(runtimeAttributes == null ? null : runtimeAttributes.get("__agentRunId"));
        int maxAttempts = plannerRepairAttempts(runtimeAttributes);
        String currentPrompt = prompt;
        AgentDecision lastDecision = null;
        String lastRaw = null;
        String logRunId = runId == null ? "" : runId;
        List<PlanCandidate> candidates = new ArrayList<>();
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            long startedAt = System.currentTimeMillis();
            log.info("agentModelRequest phase=planner runId={} attempt={}/{} modelClass={} promptChars={} toolCount={} observationCount={}",
                logRunId,
                attempt,
                maxAttempts,
                activeChatModel == null ? null : activeChatModel.getClass().getName(),
                currentPrompt.length(),
                availableTools == null ? 0 : availableTools.size(),
                observations == null ? 0 : observations.size());
            String raw = activeChatModel.chat(currentPrompt);
            lastRaw = raw;
            log.info("agentModelResponse phase=planner runId={} attempt={}/{} durationMs={} responseChars={}",
                logRunId,
                attempt,
                maxAttempts,
                System.currentTimeMillis() - startedAt,
                raw == null ? 0 : raw.length());
            logPlannerRawOutput(logRunId, attempt, maxAttempts, raw);
            AgentDecision decision = parseDecision(raw, validationContext);
            if (decision == null) {
                lastDecision = invalidPlannerDecision(raw, "non_json_response", "Planner did not return valid JSON.");
                logPlannerDecision(logRunId, attempt, maxAttempts, lastDecision);
            } else {
                logPlannerDecision(logRunId, attempt, maxAttempts, decision);
            }
            if (decision != null) {
                lastDecision = decision;
            }
            candidates.add(planCandidate(attempt, raw, lastDecision, validationContext));
            if (decision != null && !plannerPlanInvalid(decision)) {
                PlanRewriteContext rewriteContext = planRewriteContext(candidates);
                return withAttributionMetadata(
                    decision,
                    candidates.get(candidates.size() - 1),
                    rewriteContext,
                    "Selected the first runtime-valid plan candidate.",
                    false,
                    List.of()
                );
            }
            if (attempt < maxAttempts && shouldRepairPlan(lastDecision, validationContext)) {
                currentPrompt = buildPlannerRepairPrompt(prompt, raw, lastDecision, attempt + 1, maxAttempts);
                continue;
            }
            break;
        }
        PlanRewriteContext rewriteContext = planRewriteContext(candidates);
        AgentDecision attributionDecision = attributeAndSelectBestPlan(rewriteContext, validationContext, logRunId);
        if (attributionDecision != null) {
            return attributionDecision;
        }
        return lastDecision == null
            ? invalidPlannerDecision(lastRaw, "non_json_response", "Planner did not return valid JSON.")
            : lastDecision;
    }

    private String buildPlannerPrompt(String query,
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
        prompt.append("You are an agent planner.\n");
        prompt.append("Goal: produce a safe, executable InterpretationPlan for the MCP runtime.\n");
        prompt.append("Planning contract:\n");
        prompt.append("- Output exactly one JSON object. Do not output markdown, code fences, comments, or natural language.\n");
        prompt.append("- The JSON object MUST conform to the InterpretationPlan schema below.\n");
        prompt.append("- The user query MUST first be converted into this executable InterpretationPlan before any tool execution.\n");
        prompt.append("- Do not output legacy action/tool JSON such as {\"action\":\"tool\"} or {\"action\":\"final\"}.\n");
        prompt.append("- The plan is declarative. Do not claim that a tool has already run unless it appears in Observations so far.\n");
        prompt.append("- Use integer step ids starting at 1. Keep depends_on as explicit arrays of prior step ids.\n");
        prompt.append("- Include exactly one final_answer step. Put the user-facing answer in final_answer.input.answer only when observations are sufficient.\n");
        prompt.append("- final_answer.input.answer MUST be a polished Chinese Markdown document string, not a single plain paragraph. Do not wrap it in code fences.\n");
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
        prompt.append("- Add plan.edge_contracts when a later step needs a typed field from an earlier tool output.\n");
        prompt.append("- If information is missing, add missing_info and plan the smallest safe retrieval/tool step instead of inventing facts.\n\n");
        String sqlTemplateQueryTool = matchingAvailableTool(availableTools, "template_discovery");
        String sqlMetadataSearchTool = matchingAvailableTool(availableTools, "sql_metadata_search");
        prompt.append("SQL template execution contract:\n");
        if (sqlTemplateQueryTool != null) {
            prompt.append("- For SQL datasource analysis, call the available typed template discovery tool ")
                .append(sqlTemplateQueryTool)
                .append(" and bind the selected returned templates[].templateId into sql_query_execute.templateId.\n");
        } else {
            prompt.append("- No SQL template discovery tool is available in this request. Do not add sql_datasource_template_query/template_query steps. Only call sql_query_execute when a concrete templateId and parameter contract already appear in Available tools metadata, prior observations, or user-provided governed context; otherwise stop at final_answer with the missing template-discovery requirement.\n");
        }
        if (sqlMetadataSearchTool != null) {
            prompt.append("- For SQL table analysis or unknown schema/database, call ")
                .append(sqlMetadataSearchTool)
                .append(" before SQL execution. Pass the explicit tableName from the user request and includeColumns=true so cached column names/types/comments are returned. Use results[].sqlExecutionBinding as the authoritative source for schemaName/databaseName/tableName.\n");
        } else {
            prompt.append("- If sql_metadata_search is not available, do not guess schema/database/table binding from free text; use already observed structured metadata only.\n");
        }
        prompt.append("- sql_query_execute with templateId MUST pass only fields declared by templates[].parameterSchema under input.parameters.\n");
        prompt.append("- Treat templates[].parameterSchema, templates[].requiredParameters, templates[].parameterContract, and templates[].invocationExample as the binding contract. If requiredParameters contains tableName, copy the table name from the user request or sql_metadata_search result into input.parameters.tableName before calling sql_query_execute.\n");
        prompt.append("- Never call sql_query_execute with templateId and parameters={} when the selected template has any required parameter. Missing required template parameters must be repaired by adding a prior discovery/planning step or by binding the value from user input/tool output.\n");
        prompt.append("- sql_query_execute MUST include logical executionContext from typed asset discovery, for example {\"assetName\":\"<asset-name>\",\"env\":\"<env>\"}.\n");
        prompt.append("- Do not put JSONPath strings such as $.assets[0].asset.name inside executionContext. Use plan.bindings or depend on the asset_query step so runtime can inject concrete values.\n");
        prompt.append("- Never put raw SQL such as SHOW CREATE TABLE, DESC/DESCRIBE, SELECT, SHOW STATUS, or information_schema queries inside input.parameters.sql/rawSql/query/statement.\n");
        prompt.append("- Do not invent SQL template names. If a requested analysis needs table metadata, choose an observed returned TABLE_METADATA template and pass tableName from the user/query context. Pass schemaName/databaseName only when it came from sql_metadata_search results[].sqlExecutionBinding.parameters or a table-location template; never bind assetName to schemaName.\n\n");
        prompt.append("Template-governed HTTP/API/SSH execution contract:\n");
        prompt.append("- HTTP/API/SSH execution must follow the same template governance as SQL: discover/select a registered template, read templates[].parameterSchema/requiredParameters/parameterContract/invocationExample, then call the execution tool with only declared parameters.\n");
        prompt.append("- For http_request_execute, use template from http_endpoint_template_query.templates[].templateId and put all endpoint arguments under input.parameters. Do not pass raw url, uri, method, headers, body, host, hostname, ip, or endpointId.\n");
        prompt.append("- For API service tools returned by api_template_query, use the returned toolName/templateId exactly and pass only arguments declared by templates[].parameterSchema. Do not invent raw URL, headers, or body fields.\n");
        prompt.append("- For linux_command_execute, use template from ssh_template_query.templates[].templateId and put all command arguments under input.parameters. Do not pass command, rawCommand, shell, host, hostname, ip, or hostId.\n");
        prompt.append("- Never call any template-governed execution tool with empty parameters when the selected template declares requiredParameters. Add a prior discovery/planning step or bind values from user input/tool output.\n\n");
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
        if (discoverySearchTool != null && crawlerTool != null) {
            prompt.append("Web evidence workflow:\n");
            prompt.append("1. Use ").append(discoverySearchTool)
                .append(" and other web discovery tools only to discover candidate pages, page links, search routes, and short snippets. Do not treat discovery snippets as final evidence.\n");
            prompt.append("2. After a web discovery tool returns candidates, runtime will ask the model to choose relevant URLs.\n");
            prompt.append("3. Then call ").append(crawlerTool)
                .append(" to fetch cleaned full page content from the selected URL before analysis.\n");
            prompt.append("4. The final_answer step MUST depend on the crawler/content step, not only on web discovery.\n");
            prompt.append("5. If an official website or exchange site is required, keep that source constraint in the web discovery query/input.\n\n");
            prompt.append("Discovery tools include web_search, web_page_analyze, site_intelligence_resolver, finance_site_search, and generic_web_site_search when available.\n\n");
            prompt.append("Crawler input contract:\n");
            prompt.append("- Never use ").append(crawlerTool).append(" as a search tool. It cannot accept a free-text query.\n");
            prompt.append("- ").append(crawlerTool).append(" may only be called with an HTTP/HTTPS url selected from prior web discovery results, for example {\"url\":\"https://example.com/page\"}.\n");
            prompt.append("- If no URL has been observed yet, call a web discovery tool first and do not call ").append(crawlerTool).append(".\n\n");
            prompt.append("Binding contract:\n");
            prompt.append("- Use plan.bindings for data flow from one step output to another step input. edge_contracts only validate data shape; they do not populate inputs.\n");
            prompt.append("- When ").append(crawlerTool).append(" depends on ").append(discoverySearchTool)
                .append(", bind the selected search result URL into crawler input url, e.g. {\"from\":1,\"output_path\":\"$.results[0].url\",\"to\":2,\"input_field\":\"url\",\"type\":\"jsonpath\"}.\n");
            prompt.append("- Do not use placeholder inputs such as {\"url\":\"\"} or template strings such as ${step1.results[0].url}; use plan.bindings instead.\n\n");
            prompt.append("Web search query fidelity:\n");
            prompt.append("- Current date for relative-time interpretation is ")
                .append(LocalDate.now(ZoneId.of("Asia/Shanghai")))
                .append(" (Asia/Shanghai).\n");
            prompt.append("- For web_search.query, preserve the user's original search phrase as much as possible. Do not append inferred years, stale years, or extra date tokens.\n");
            prompt.append("- If the user says today, latest, current, recent, \u4eca\u5929, \u6700\u65b0, \u8fd1\u671f, or \u5f53\u524d, keep that temporal wording instead of converting it to another year unless the user explicitly requested an absolute date.\n\n");
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
            prompt.append("2. Then call ").append(verificationWebSearchTool).append(" to validate and supplement with public/web evidence.\n");
            prompt.append("3. Do not return a final answer until both ").append(documentSearchTool).append(" and ")
                .append(verificationWebSearchTool)
                .append(" have been observed.\n");
            prompt.append("4. In the final answer, separate internal document evidence from web verification evidence.\n");
            prompt.append("5. If the two sources conflict, explicitly state the conflict and prefer internal documents for internal/business facts unless web evidence is newer and the answer calls for current public facts.\n\n");
        }
        if (!observations.isEmpty()) {
            prompt.append("Observations so far:\n");
            observations.forEach(ob -> prompt.append("- ").append(ob).append("\n"));
            prompt.append("Citation requirement:\n");
            prompt.append("- If observations include web citation labels such as [\u7f51\u98751], cite web-derived statements with the matching label immediately after the sentence.\n");
            prompt.append("- Do not cite web facts without a matching citation label from the observations.\n");
            prompt.append("\n");
        }
        prompt.append("User query:\n").append(query);
        return prompt.toString();
    }

    private void appendMcpWorkflowOrchestrationContract(StringBuilder prompt, Map<String, Object> runtimeAttributes) {
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
        prompt.append("- The InterpretationPlan MUST preserve every required step, dependency, condition, and confirmation node from this workflow.\n");
        prompt.append("- When a workflow step has dependsOn, the matching plan step MUST depend_on the referenced prior workflow tool step.\n");
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

    private void appendMcpControlPlaneToolContracts(StringBuilder prompt, List<String> availableTools) {
        if (availableTools == null || availableTools.isEmpty()) {
            return;
        }
        String assetQueryTool = matchingAvailableTool(availableTools, "asset_discovery");
        if (assetQueryTool != null) {
            prompt.append("Asset discovery tool contract:\n");
            prompt.append("- Use ").append(assetQueryTool)
                .append(" only for read-only discovery of redacted asset metadata.\n");
            prompt.append("- Before calling ").append(assetQueryTool)
                .append(", produce a routing candidate set and finalDecision. candidates[] must contain targetKind/confidence pairs, and finalDecision must be one of the candidates. Use database for database/SQL datasource assets, host for OS/server/service host assets, http for HTTP endpoint assets, and api for API service assets. For document, use the document search tool instead of asset discovery.\n");
            prompt.append("- ").append(assetQueryTool)
                .append(" input should contain filters or executionContext when exact logical context is known; use {\"filters\":{},\"limit\":10} for capped redacted candidate discovery when the user did not provide assetName/env/cluster/service.\n");
            prompt.append("- Asset names and routing labels are exact-match. Do not derive assetName, service, cluster, target, or labels from the user intent unless the user explicitly provided that exact value or a prior tool observation returned it.\n");
            prompt.append("- Never concatenate an assetName with descriptive text, asset type, capability, or assumption. For example, keep the user-provided asset phrase unchanged; if the exact asset name is uncertain, omit filters.assetName and use broad discovery with filters={}.\n");
            prompt.append("- Do not invent service labels such as service:<topic> from natural-language topic words until an asset/tool observation proves they are registered routing labels.\n");
            prompt.append("- Valid input example when no exact database context is known: {\"candidates\":[{\"targetKind\":\"database\",\"confidence\":0.82},{\"targetKind\":\"http\",\"confidence\":0.42}],\"finalDecision\":\"database\",\"filters\":{},\"trace\":{\"plannerVersion\":\"v1.1\",\"model\":\"<model>\"},\"limit\":10}.\n");
            prompt.append("- Valid input example when the host asset name is explicitly known: {\"candidates\":[{\"targetKind\":\"host\",\"confidence\":0.9}],\"finalDecision\":\"host\",\"filters\":{\"assetName\":\"<existing-asset-name>\"},\"trace\":{\"plannerVersion\":\"v1.1\",\"model\":\"<model>\"},\"limit\":10}.\n");
            prompt.append("- Valid input example when using labels already returned by asset metadata or explicitly provided by the user: {\"targetKind\":\"host\",\"confidence\":0.86,\"filters\":{\"env\":\"<existing-env>\",\"service\":\"<existing-service-label>\"},\"trace\":{\"plannerVersion\":\"v1.0\",\"model\":\"<model>\"},\"limit\":10}.\n");
            prompt.append("- The response contains the single canonical asset view in assets[]. Use assets[0].asset.type for asset type, assets[0].asset.environment, assets[0].asset.name, assets[0].asset.toolName, and assets[0].capabilities.allowedCommandTemplates[].templateId or allowedCommandTemplateIds[] only as authorization context, not as semantic ranking. Do not require top-level assetType because it is query scope and may be null when the request did not preselect an asset type.\n");
            prompt.append("- Do not pass hostname, host, ip, url, jdbcUrl, datasourceId, endpointId, or other concrete target fields.\n\n");
            prompt.append("- Do not replace ").append(assetQueryTool)
                .append(" with a reasoning step that guesses env, service, cluster, or target. If broad discovery returns multiple plausible assets, ask the user for logical context.\n\n");
        }
        String templateQueryTool = matchingAvailableTool(availableTools, "template_discovery");
        if (templateQueryTool != null) {
            prompt.append("Template discovery tool contract:\n");
            prompt.append("- Use ").append(templateQueryTool)
                .append(" only for read-only discovery of registered execution templates.\n");
            prompt.append("- Before calling ").append(templateQueryTool)
                .append(", produce a routing candidate set and finalDecision. candidates[] must contain targetKind/confidence pairs, and finalDecision must be one of the candidates. Runtime routes host to SSH templates, database to SQL datasource templates, http to HTTP templates, api to API templates, and business_database_query to business SQL MCP tools. For document, use document search instead of template discovery.\n");
            prompt.append("- ").append(templateQueryTool)
                .append(" returns the single canonical template view in templates[]. It never returns raw shell commands or executionSpec.\n");
            prompt.append("- Query it when the plan/user-configured dependency requires template discovery before execution. Prefer filters.assetName and filters.env from the prior typed asset discovery result.\n");
            prompt.append("- If the user asks for a capability and no exact asset context is known, query by candidate set plus intent, for example {\"candidates\":[{\"targetKind\":\"host\",\"confidence\":0.82},{\"targetKind\":\"database\",\"confidence\":0.51}],\"finalDecision\":\"host\",\"filters\":{\"intent\":\"<user-capability-intent>\"},\"trace\":{\"plannerVersion\":\"v1.1\",\"model\":\"<model>\"},\"limit\":10}; still use a returned templateId exactly.\n");
            prompt.append("- Valid host input example: {\"candidates\":[{\"targetKind\":\"host\",\"confidence\":0.9}],\"finalDecision\":\"host\",\"filters\":{\"assetName\":\"<asset-name-from-typed-asset-discovery>\",\"env\":\"<env-from-typed-asset-discovery>\",\"intent\":\"<user-capability-intent>\"},\"trace\":{\"plannerVersion\":\"v1.1\",\"model\":\"<model>\"},\"limit\":10}.\n");
            prompt.append("- For template discovery filters, if the user intent contains database/component/metric/action names, include both Chinese and English retrieval terms. filters.intent keeps the user's original natural-language intent; filters.bilingualIntent must include Chinese aliases and English technical terms; filters.intentZh and filters.intentEn should split the primary Chinese/English intent; filters.intentAliases must include Chinese aliases plus English technical terms; filters.keywords must include canonical DB/component keywords, command names, metric names, and common aliases. Do not rely on Chinese-only or English-only intent for template retrieval.\n");
            prompt.append("- Valid database input example: {\"candidates\":[{\"targetKind\":\"database\",\"confidence\":0.9}],\"finalDecision\":\"database\",\"filters\":{\"assetName\":\"<asset-name-from-typed-asset-discovery>\",\"env\":\"<env-from-typed-asset-discovery>\",\"intent\":\"<database-query-intent>\",\"bilingualIntent\":[\"<Chinese alias>\",\"<English technical term>\"],\"intentZh\":\"<Chinese intent>\",\"intentEn\":\"<English technical intent>\",\"intentAliases\":[\"<Chinese alias>\",\"<English technical term>\"],\"keywords\":[\"<canonical command or metric>\",\"<Chinese keyword>\",\"<English keyword>\"]},\"trace\":{\"plannerVersion\":\"v1.1\",\"model\":\"<model>\"},\"limit\":10}.\n");
            prompt.append("- templates[] is ranked by relevanceScore. Choose the returned template whose name, description, intentSignals, matchReasons, and asset type best match the user intent; do not blindly bind the first asset allowedCommandTemplates item.\n");
            prompt.append("- If the selected template's parameterSchema.required is non-empty, include a parameters object in the execution tool input with exactly those required fields; never place template parameters at the top level.\n");
            prompt.append("- Also read templates[].requiredParameters, templates[].parameterContract, and templates[].invocationExample. These fields are authoritative. For SQL/HTTP/SSH gateway tools, required values must be placed under the execution step's input.parameters; for direct API tools, follow parameterContract.argumentContainer.\n");
            prompt.append("- Do not invent template ids if ").append(templateQueryTool)
                .append(" returns no suitable template; ask the user/admin to register or allow one.\n\n");
        }
        String linuxCommandTool = matchingAvailableTool(availableTools, "linux_command_execute");
        if (linuxCommandTool != null) {
            prompt.append("Linux command gateway contract:\n");
            prompt.append("- Use ").append(linuxCommandTool)
                .append(" only with a registered template id and logical executionContext.\n");
            prompt.append("- Input MUST use field template, not command, command_template, shell, host, hostname, or ip.\n");
            prompt.append("- The template value MUST be copied exactly from configured template metadata: typed template discovery templates[].templateId when that step is part of the plan, otherwise typed asset discovery allowedCommandTemplates[].templateId or allowedCommandTemplateIds[]. Never synthesize aliases or alternate names.\n");
            prompt.append("- Template arguments MUST be passed under parameters and must satisfy the selected template's parameterSchema, for example {\"template\":\"<templateId>\",\"parameters\":{\"<requiredParam>\":\"<value>\"},\"executionContext\":{\"assetName\":\"<asset>\"}}.\n");
            prompt.append("- Prefer executionContext.assetName from asset discovery for exact routing, plus env when available. Example: {\"template\":\"<templateId-from-typed-template-discovery>\",\"executionContext\":{\"assetName\":\"<asset-name-from-typed-asset-discovery>\",\"env\":\"<env-from-typed-asset-discovery>\"},\"reason\":\"inspect host state\"}.\n");
            prompt.append("- Follow the dependency order configured by the user/runtime. Do not insert a hard-coded template discovery step unless the plan needs template discovery or configured dependencies require it.\n");
            prompt.append("- If typed asset discovery returns allowedCommandTemplates and no template discovery step is configured, choose the safest matching registered template from that authorized list and then call ")
                .append(linuxCommandTool)
                .append("; do not stop after discovery when the user asked for live system analysis.\n");
            prompt.append("- If no suitable command template is known or returned, produce a final answer asking the user/admin to register a safe template; do not invent raw shell commands.\n\n");
        }
        String httpRequestTool = matchingAvailableTool(availableTools, "http_request_execute");
        if (httpRequestTool != null) {
            prompt.append("HTTP request gateway contract:\n");
            prompt.append("- Use ").append(httpRequestTool)
                .append(" only with a registered HTTP template id and logical executionContext.\n");
            prompt.append("- Input MUST use field template plus parameters; never use url, uri, method, headers, body, host, hostname, ip, or endpointId.\n");
            prompt.append("- The template value MUST be copied exactly from http_endpoint_template_query.templates[].templateId or typed API/HTTP discovery metadata.\n");
            prompt.append("- Template arguments MUST be passed under parameters and must satisfy templates[].parameterSchema/requiredParameters. Missing required fields must be bound before execution.\n");
            prompt.append("- Prefer executionContext.assetName from asset discovery for exact routing, plus env when available.\n\n");
        }
    }

    private String describeTools(List<String> availableTools, Map<String, Object> runtimeAttributes) {
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
    private AgentDecision parseDecision(String raw, PlannerValidationContext validationContext) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String json = extractJson(raw);
        try {
            Map<String, Object> payload = objectMapper.readValue(json, Map.class);
            AgentDecision interpretationPlanDecision = parseInterpretationPlanDecision(payload, validationContext);
            if (interpretationPlanDecision != null) {
                return interpretationPlanDecision;
            }
            if (requiresStrictInterpretationPlan(validationContext)) {
                return invalidPlannerDecision(
                    raw,
                    "legacy_action_not_allowed",
                    "MCP workflow requires an InterpretationPlan; legacy action JSON is not allowed."
                );
            }
            String action = stringValue(payload.get("action"));
            if (action == null) {
                return null;
            }
            action = action.toLowerCase(Locale.ROOT);
            Map<String, Object> executionPlan = asMap(payload.get("executionPlan"));
            if (FINAL.equals(action)) {
                Boolean sufficient = booleanObject(firstObject(payload, "sufficient", "isSufficient"));
                if (sufficient == null) {
                    sufficient = booleanObject(firstObject(executionPlan, "sufficient", "isSufficient"));
                }
                return new AgentDecision(
                    FINAL,
                    null,
                    Map.of(),
                    stringValue(payload.get("answer")),
                    stringValue(payload.get("reason")),
                    executionPlan,
                    sufficient
                );
            }
            if (!TOOL.equals(action)) {
                return null;
            }
            Object argsObj = payload.get("arguments");
            Map<String, Object> arguments = argsObj instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
            return new AgentDecision(
                TOOL,
                stringValue(payload.get("toolName")),
                arguments,
                null,
                stringValue(payload.get("reason")),
                executionPlan,
                null
            );
        } catch (Exception ex) {
            log.debug("Failed to parse planner decision: {}", raw, ex);
            return null;
        }
    }

    private AgentDecision parseInterpretationPlanDecision(Map<String, Object> payload,
                                                         PlannerValidationContext validationContext) {
        if (payload == null || !payload.containsKey("plan") || !payload.containsKey("intent")) {
            return null;
        }
        payload = normalizeInterpretationPlanPayload(payload);
        InterpretationPlan interpretationPlan = objectMapper.convertValue(payload, InterpretationPlan.class);
        InterpretationPlanValidator.ValidationResult validation =
            interpretationPlanValidator.validate(
                interpretationPlan,
                toolRegistry,
                new LinkedHashSet<>(validationContext == null ? List.of() : normalizeList(validationContext.availableTools()))
            );
        List<String> runtimeIssues = validateRuntimePlanRules(interpretationPlan, validationContext);
        Map<String, Object> validationMetadata = validationMetadata(validation, runtimeIssues);
        if (!validation.valid() || !runtimeIssues.isEmpty()) {
            return new AgentDecision(
                FINAL,
                null,
                Map.of(),
                "Planner produced an invalid InterpretationPlan.",
                "invalid_interpretation_plan",
                validationMetadata,
                false,
                interpretationPlan
            );
        }
        InterpretationPlan.Step nextStep = nextExecutableStep(interpretationPlan);
        if (nextStep == null) {
            return new AgentDecision(
                FINAL,
                null,
                Map.of(),
                answerFromFinalStep(interpretationPlan, null),
                "interpretation_plan_without_actionable_step",
                validationMetadata,
                null,
                interpretationPlan
            );
        }
        Map<String, Object> executionPlan = new LinkedHashMap<>(validationMetadata);
        executionPlan.put("plan_step_id", nextStep.id());
        executionPlan.put("action_type", nextStep.actionType());
        executionPlan.put("intent", interpretationPlan.intent() == null ? null : interpretationPlan.intent().goal());
        executionPlan.put("risk_level", interpretationPlan.intent() == null ? null : interpretationPlan.intent().riskLevel());
        executionPlan.put("tool", nextStep.toolName());

        if (nextStep.mcpToolAction()) {
            return new AgentDecision(
                TOOL,
                nextStep.toolName(),
                nextStep.input() == null ? Map.of() : nextStep.input(),
                null,
                interpretationPlan.intent() == null ? null : interpretationPlan.intent().goal(),
                executionPlan,
                false,
                interpretationPlan
            );
        }
        if (nextStep.finalAnswerAction()) {
            return new AgentDecision(
                FINAL,
                null,
                Map.of(),
                answerFromFinalStep(interpretationPlan, nextStep),
                "interpretation_plan_final_answer",
                executionPlan,
                interpretationPlan.review() != null
                    && interpretationPlan.review().selfCheck() != null
                    && Boolean.TRUE.equals(interpretationPlan.review().selfCheck().toolSufficiency()),
                interpretationPlan
            );
        }
        return new AgentDecision(
            FINAL,
            null,
            Map.of(),
            answerFromFinalStep(interpretationPlan, nextStep),
            "interpretation_plan_reasoning_step",
            executionPlan,
            null,
            interpretationPlan
        );
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> normalizeInterpretationPlanPayload(Map<String, Object> payload) {
        Map<String, Object> normalized = new LinkedHashMap<>(payload);
        alias(normalized, "executionPolicy", "execution_policy");

        Map<String, Object> plan = mutableMap(normalized.get("plan"));
        if (!plan.isEmpty()) {
            alias(plan, "edgeContracts", "edge_contracts");
            Object rawSteps = plan.get("steps");
            if (rawSteps instanceof List<?> steps) {
                List<Object> normalizedSteps = new ArrayList<>();
                for (Object rawStep : steps) {
                    Map<String, Object> step = mutableMap(rawStep);
                    if (step.isEmpty()) {
                        normalizedSteps.add(rawStep);
                        continue;
                    }
                    alias(step, "actionType", "action_type");
                    alias(step, "toolName", "tool_name");
                    alias(step, "dependsOn", "depends_on");
                    alias(step, "outputContract", "output_contract");
                    Map<String, Object> outputContract = mutableMap(step.get("output_contract"));
                    if (!outputContract.isEmpty()) {
                        alias(outputContract, "schemaHint", "schema_hint");
                        step.put("output_contract", outputContract);
                    }
                    step.put("input", normalizeStepInput(stringValue(step.get("tool_name")), step.get("input")));
                    normalizedSteps.add(step);
                }
                plan.put("steps", normalizedSteps);
            }
            Map<String, Object> stability = mutableMap(plan.get("stability"));
            if (!stability.isEmpty()) {
                alias(stability, "stableNodes", "stable_nodes");
                alias(stability, "criticalTools", "critical_tools");
                alias(stability, "lockedEdges", "locked_edges");
                alias(stability, "mutableActionTypes", "mutable_action_types");
                plan.put("stability", stability);
            }
            normalized.put("plan", plan);
        }

        Map<String, Object> policy = mutableMap(normalized.get("execution_policy"));
        if (!policy.isEmpty()) {
            alias(policy, "maxSteps", "max_steps");
            alias(policy, "allowParallel", "allow_parallel");
            alias(policy, "allowTool", "allow_tool");
            alias(policy, "denyTool", "deny_tool");
            alias(policy, "timeoutMs", "timeout_ms");
            alias(policy, "maxRewriteTimes", "max_rewrite_times");
            alias(policy, "fallbackMode", "fallback_mode");
            alias(policy, "toolPriority", "tool_priority");
            alias(policy, "costBudget", "cost_budget");
            alias(policy, "latencyBudgetMs", "latency_budget_ms");
            alias(policy, "accuracyVsSpeed", "accuracy_vs_speed");
            policy.put("tool_priority", clampPriorityMap(policy.get("tool_priority")));
            policy.put("accuracy_vs_speed", clampNullableDouble(policy.get("accuracy_vs_speed"), 0.0, 1.0));
            normalized.put("execution_policy", policy);
        }

        Map<String, Object> intent = mutableMap(normalized.get("intent"));
        if (!intent.isEmpty()) {
            alias(intent, "riskLevel", "risk_level");
            normalized.put("intent", intent);
        }

        Map<String, Object> context = mutableMap(normalized.get("context"));
        if (!context.isEmpty()) {
            alias(context, "keyFacts", "key_facts");
            alias(context, "missingInfo", "missing_info");
            normalized.put("context", context);
        }

        Map<String, Object> review = mutableMap(normalized.get("review"));
        if (!review.isEmpty()) {
            alias(review, "selfCheck", "self_check");
            Map<String, Object> selfCheck = mutableMap(review.get("self_check"));
            if (!selfCheck.isEmpty()) {
                alias(selfCheck, "completenessScore", "completeness_score");
                alias(selfCheck, "hallucinationRisk", "hallucination_risk");
                alias(selfCheck, "toolSufficiency", "tool_sufficiency");
                alias(selfCheck, "missingSteps", "missing_steps");
                review.put("self_check", selfCheck);
            }
            alias(review, "fallbackPlan", "fallback_plan");
            normalized.put("review", review);
        }
        return normalized;
    }

    private Map<String, Object> normalizeStepInput(String toolName, Object rawInput) {
        Map<String, Object> input = mutableMap(rawInput);
        if (input.isEmpty()) {
            return input;
        }
        String semanticTool = toolSemanticKey(toolName);
        if (isAssetDiscoverySemantic(semanticTool)) {
            normalizeDiscoveryQueryInput(input);
        }
        if (isTemplateDiscoverySemantic(semanticTool)) {
            normalizeDiscoveryQueryInput(input);
        }
        if ("linux_command_execute".equals(semanticTool)) {
            alias(input, "command_template", "template");
            alias(input, "commandTemplate", "template");
            alias(input, "templateCode", "template");
            alias(input, "context", "executionContext");
        }
        return input;
    }

    private void normalizeDiscoveryQueryInput(Map<String, Object> input) {
        Object context = input.remove("context");
        if (context instanceof Map<?, ?> map) {
            input.putIfAbsent("filters", map);
            return;
        }
        if (context != null && !String.valueOf(context).isBlank()) {
            String text = String.valueOf(context).trim();
            Map<String, Object> filters = mutableMap(input.get("filters"));
            if (looksLikeAssetName(text)) {
                filters.putIfAbsent("assetName", text);
            } else {
                filters.putIfAbsent("service", text);
            }
            input.put("filters", filters);
        }
    }

    private boolean looksLikeAssetName(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String text = value.trim();
        String normalized = text.toLowerCase(Locale.ROOT);
        return normalized.contains("_")
            || normalized.contains(":")
            || normalized.startsWith("ssh_")
            || normalized.startsWith("sql_")
            || normalized.startsWith("http_");
    }

    private Map<String, Double> clampPriorityMap(Object value) {
        Map<String, Object> raw = mutableMap(value);
        if (raw.isEmpty()) {
            return Map.of();
        }
        Map<String, Double> clamped = new LinkedHashMap<>();
        raw.forEach((tool, priority) -> {
            Double number = doubleValue(priority);
            if (tool != null && !tool.isBlank() && number != null) {
                clamped.put(tool, clamp(number, 0.0, 1.0));
            }
        });
        return clamped;
    }

    private Double clampNullableDouble(Object value, double min, double max) {
        Double number = doubleValue(value);
        return number == null ? null : clamp(number, min, max);
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private Double doubleValue(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private void alias(Map<String, Object> values, String alias, String canonical) {
        if (values == null || !values.containsKey(alias) || values.containsKey(canonical)) {
            return;
        }
        values.put(canonical, values.remove(alias));
    }

    private Map<String, Object> mutableMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> values = new LinkedHashMap<>();
        map.forEach((key, item) -> {
            if (key != null) {
                values.put(String.valueOf(key), item);
            }
        });
        return values;
    }

    private InterpretationPlan.Step nextExecutableStep(InterpretationPlan interpretationPlan) {
        if (interpretationPlan == null || interpretationPlan.steps().isEmpty()) {
            return null;
        }
        return interpretationPlan.steps().stream()
            .filter(step -> step != null && (step.mcpToolAction() || step.finalAnswerAction()))
            .findFirst()
            .orElse(interpretationPlan.steps().get(0));
    }

    private String answerFromFinalStep(InterpretationPlan interpretationPlan, InterpretationPlan.Step finalStep) {
        Map<String, Object> input = finalStep == null ? Map.of() : finalStep.input();
        String answer = firstNonBlank(
            stringValue(firstObject(input, "answer", "response", "text", "result")),
            null
        );
        if (answer != null) {
            return answer;
        }
        return interpretationPlan == null || interpretationPlan.intent() == null
            ? ""
            : firstNonBlank(interpretationPlan.intent().goal(), "");
    }

    private Map<String, Object> validationMetadata(InterpretationPlanValidator.ValidationResult validation,
                                                   List<String> runtimeIssues) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("plannerProtocol", "interpretation_plan");
        boolean runtimeValid = runtimeIssues == null || runtimeIssues.isEmpty();
        metadata.put("interpretationPlanValid", validation.valid() && runtimeValid);
        metadata.put("interpretationPlanSchemaValid", validation.valid());
        metadata.put("interpretationPlanRuntimeRulesValid", runtimeValid);
        metadata.put("interpretationPlanExecutable", validation.executable() && runtimeValid);
        metadata.put("interpretationPlanApprovalRequired", validation.approvalRequired());
        metadata.put("interpretationPlanIssues", validation.issues().stream()
            .map(issue -> Map.of(
                "severity", issue.severity(),
                "path", issue.path(),
                "message", issue.message()
            ))
            .toList());
        metadata.put("interpretationPlanRuntimeIssues", runtimeIssues == null ? List.of() : runtimeIssues);
        metadata.put("orderedStepIds", validation.orderedSteps().stream()
            .map(InterpretationPlan.Step::id)
            .toList());
        return metadata;
    }

    private List<String> validateRuntimePlanRules(InterpretationPlan plan, PlannerValidationContext context) {
        if (context == null || plan == null) {
            return List.of();
        }
        List<String> issues = new ArrayList<>();
        List<String> mandatoryTools = normalizeList(context.mandatoryTools());
        if (context.requireToolBeforeFinal() && mandatoryTools.isEmpty()) {
            issues.add("Runtime policy requires a tool before final answer, but no mandatory tool was provided.");
        }
        Map<Integer, InterpretationPlan.Step> stepsById = new LinkedHashMap<>();
        Map<String, List<Integer>> toolStepIds = new LinkedHashMap<>();
        InterpretationPlan.Step finalStep = null;
        for (InterpretationPlan.Step step : plan.steps()) {
            if (step == null || step.id() == null) {
                continue;
            }
            stepsById.put(step.id(), step);
            if (step.finalAnswerAction()) {
                finalStep = step;
            }
            if (step.mcpToolAction() && step.toolName() != null && !step.toolName().isBlank()) {
                toolStepIds.computeIfAbsent(step.toolName(), ignored -> new ArrayList<>()).add(step.id());
            }
        }
        Integer previousMandatoryStepId = null;
        for (String mandatoryTool : mandatoryTools) {
            Integer mandatoryStepId = firstToolStepId(toolStepIds, mandatoryTool);
            if (mandatoryStepId == null) {
                issues.add("Mandatory tool is missing from InterpretationPlan: " + mandatoryTool);
                continue;
            }
            if (previousMandatoryStepId != null && mandatoryStepId <= previousMandatoryStepId) {
                issues.add("Mandatory tools must appear in configured order: " + mandatoryTool);
            }
            if (previousMandatoryStepId != null
                && !dependsOnStep(mandatoryStepId, previousMandatoryStepId, stepsById, new LinkedHashSet<>())) {
                issues.add("Mandatory tool must depend on previous configured workflow step: " + mandatoryTool);
            }
            if (finalStep == null || !dependsOnStep(finalStep.id(), mandatoryStepId, stepsById, new LinkedHashSet<>())) {
                issues.add("final_answer must depend on mandatory tool before answering: " + mandatoryTool);
            }
            previousMandatoryStepId = mandatoryStepId;
        }
        if (context.requireDocumentWebVerification()) {
            Integer documentStepId = firstToolStepId(toolStepIds, context.documentSearchTool());
            Integer webStepId = firstToolStepId(toolStepIds, context.verificationWebSearchTool());
            boolean documentRequiredInPlan = containsTool(mandatoryTools, context.documentSearchTool());
            boolean webRequiredInPlan = containsTool(mandatoryTools, context.verificationWebSearchTool());
            if (documentRequiredInPlan && documentStepId == null) {
                issues.add("Document-web verification requires document tool in plan: " + context.documentSearchTool());
            }
            if (webRequiredInPlan && webStepId == null) {
                issues.add("Document-web verification requires web verification tool in plan: " + context.verificationWebSearchTool());
            }
            if (documentStepId != null && webStepId != null && webStepId <= documentStepId) {
                issues.add("Document-web verification must plan document search before web verification.");
            }
            if (documentRequiredInPlan && finalStep != null && documentStepId != null
                && !dependsOnStep(finalStep.id(), documentStepId, stepsById, new LinkedHashSet<>())) {
                issues.add("final_answer must depend on document verification evidence.");
            }
            if (webRequiredInPlan && finalStep != null && webStepId != null
                && !dependsOnStep(finalStep.id(), webStepId, stepsById, new LinkedHashSet<>())) {
                issues.add("final_answer must depend on web verification evidence.");
            }
        }
        validateAssetDiscoveryIsNotGuessed(plan, context, toolStepIds, issues);
        validateWebSearchCrawlerSplit(plan, context, stepsById, toolStepIds, finalStep, issues);
        return issues;
    }

    private void validateAssetDiscoveryIsNotGuessed(InterpretationPlan plan,
                                                    PlannerValidationContext context,
                                                    Map<String, List<Integer>> toolStepIds,
                                                    List<String> issues) {
        if (plan == null || context == null || issues == null
            || matchingAvailableTool(context.availableTools(), "asset_discovery") == null) {
            return;
        }
        boolean hasAssetQueryStep = toolStepIds.keySet().stream()
            .map(this::toolSemanticKey)
            .anyMatch(this::isAssetDiscoverySemantic);
        if (!hasAssetQueryStep && contextClaimsGuessedAssetRouting(plan.context())) {
            issues.add("Asset routing context must come from typed asset discovery, user-provided executionContext, or observations; plan context must not assume assetName/env/datasource registration.");
            return;
        }
        for (InterpretationPlan.Step step : plan.steps()) {
            if (step == null || !"reasoning".equals(step.actionType())) {
                continue;
            }
            String text = normalize(step.input() == null ? "" : step.input().toString());
            boolean mentionsAssetQueryFailure = (text.contains("asset_query") || text.contains("asset_discovery"))
                && (text.contains("reject") || text.contains("rejected") || text.contains("refuse")
                    || text.contains("denied") || text.contains("confirmation") || text.contains("failed")
                    || text.contains("失败") || text.contains("拒绝") || text.contains("确认"));
            boolean guessesTargetContext = text.contains("assume") || text.contains("default")
                || text.contains("env") || text.contains("service") || text.contains("cluster") || text.contains("target")
                || text.contains("假设") || text.contains("默认");
            if (mentionsAssetQueryFailure && guessesTargetContext) {
                issues.add("Do not use a reasoning step to replace typed asset discovery or guess env/service after discovery failure; call the typed asset discovery tool or ask the user for logical executionContext.");
                return;
            }
            if (!hasAssetQueryStep && guessesTargetContext && text.contains("service") && text.contains("env")) {
                issues.add("Asset routing context must come from typed asset discovery, user-provided executionContext, or observations; reasoning steps must not invent env/service defaults.");
                return;
            }
        }
    }

    private boolean contextClaimsGuessedAssetRouting(InterpretationPlan.Context context) {
        if (context == null) {
            return false;
        }
        String text = normalize(String.join(" ",
            safeTextList(context.keyFacts()),
            safeTextList(context.assumptions()),
            safeTextList(context.constraints())
        ));
        if (text.isBlank()) {
            return false;
        }
        boolean assetRouting = containsAny(text,
            "asset", "assetname", "datasource", "data source", "env", "environment", "service", "cluster",
            "资产", "数据源", "环境", "服务", "集群"
        );
        boolean guessed = containsAny(text,
            "assume", "assumption", "default", "registered", "known", "already known",
            "假设", "默认", "已注册", "已知", "当前工具链不包含", "工具缺失", "不可用", "直接通过"
        );
        return assetRouting && guessed;
    }

    private String safeTextList(List<String> values) {
        return values == null ? "" : String.join(" ", values);
    }

    private void validateWebSearchCrawlerSplit(InterpretationPlan plan,
                                               PlannerValidationContext context,
                                               Map<Integer, InterpretationPlan.Step> stepsById,
                                               Map<String, List<Integer>> toolStepIds,
                                               InterpretationPlan.Step finalStep,
                                               List<String> issues) {
        if (plan == null || context == null || issues == null) {
            return;
        }
        String crawlerTool = preferredCrawlerTool(context.availableTools());
        if (crawlerTool == null) {
            return;
        }
        List<Integer> webDiscoverySteps = new ArrayList<>();
        List<Integer> crawlerSteps = new ArrayList<>();
        for (Map.Entry<String, List<Integer>> entry : toolStepIds.entrySet()) {
            if (isWebDiscoveryTool(entry.getKey())) {
                webDiscoverySteps.addAll(entry.getValue());
            } else if (sameToolName(entry.getKey(), crawlerTool) || isCrawlerTool(entry.getKey())) {
                crawlerSteps.addAll(entry.getValue());
            }
        }
        if (webDiscoverySteps.isEmpty()) {
            return;
        }
        if (crawlerSteps.isEmpty()) {
            issues.add("web discovery must be followed by a crawler/content tool before final_answer: " + crawlerTool);
            return;
        }
        for (Integer webStepId : webDiscoverySteps) {
            boolean hasCrawlerAfterDiscovery = crawlerSteps.stream()
                .anyMatch(crawlerStepId -> crawlerStepId > webStepId
                    && dependsOnStep(crawlerStepId, webStepId, stepsById, new LinkedHashSet<>()));
            if (!hasCrawlerAfterDiscovery) {
                issues.add("crawler/content step must depend on each web discovery step before analysis.");
            }
        }
        if (finalStep != null) {
            boolean finalDependsOnCrawler = crawlerSteps.stream()
                .anyMatch(crawlerStepId -> dependsOnStep(finalStep.id(), crawlerStepId, stepsById, new LinkedHashSet<>()));
            if (!finalDependsOnCrawler) {
                issues.add("final_answer must depend on crawler/content evidence, not only on web discovery snippets.");
            }
        }
    }

    private String preferredWebSearchTool(List<String> availableTools) {
        List<String> tools = normalizeList(availableTools);
        return tools.stream()
            .filter(this::isWebSearchTool)
            .findFirst()
            .orElse(null);
    }

    private String preferredCrawlerTool(List<String> availableTools) {
        List<String> tools = normalizeList(availableTools);
        String crawlUrl = tools.stream()
            .filter(tool -> "crawl_url".equals(toolSemanticKey(tool)) || toolSemanticKey(tool).endsWith("_crawl_url"))
            .findFirst()
            .orElse(null);
        if (crawlUrl != null) {
            return crawlUrl;
        }
        return tools.stream()
            .filter(this::isCrawlerTool)
            .findFirst()
            .orElse(null);
    }

    private boolean isWebSearchTool(String toolName) {
        String semantic = toolSemanticKey(toolName);
        return semantic.equals("web_search") || semantic.endsWith("_web_search") || semantic.contains("web_search");
    }

    private boolean isWebDiscoveryTool(String toolName) {
        String semantic = toolSemanticKey(toolName);
        return isWebSearchTool(toolName)
            || semantic.equals("web_page_analyze")
            || semantic.contains("web_page_analyze")
            || semantic.equals("site_intelligence_resolver")
            || semantic.contains("site_intelligence")
            || semantic.equals("finance_site_search")
            || semantic.contains("finance_site_search")
            || semantic.equals("generic_web_site_search")
            || semantic.contains("generic_web_site_search")
            || semantic.equals("web_site_search")
            || (semantic.contains("site_search") && !semantic.contains("search_and_extract"));
    }

    private boolean isCrawlerTool(String toolName) {
        String semantic = toolSemanticKey(toolName);
        return !isWebDiscoveryTool(toolName)
            && (semantic.equals("crawl_url")
            || semantic.contains("crawl")
            || semantic.contains("crawler")
            || semantic.contains("fetch_page")
            || semantic.contains("page_content")
            || semantic.contains("download")
            || semantic.contains("extract"));
    }

    private boolean containsTool(List<String> tools, String toolName) {
        if (toolName == null || toolName.isBlank() || tools == null || tools.isEmpty()) {
            return false;
        }
        return tools.stream().anyMatch(tool -> sameToolName(tool, toolName));
    }

    private Integer firstToolStepId(Map<String, List<Integer>> toolStepIds, String toolName) {
        if (toolName == null || toolName.isBlank() || toolStepIds == null || toolStepIds.isEmpty()) {
            return null;
        }
        for (Map.Entry<String, List<Integer>> entry : toolStepIds.entrySet()) {
            if (sameToolName(entry.getKey(), toolName) && entry.getValue() != null && !entry.getValue().isEmpty()) {
                return entry.getValue().get(0);
            }
        }
        return null;
    }

    private boolean dependsOnStep(Integer fromStepId,
                                  Integer requiredDependencyId,
                                  Map<Integer, InterpretationPlan.Step> stepsById,
                                  Set<Integer> visited) {
        if (fromStepId == null || requiredDependencyId == null) {
            return false;
        }
        if (fromStepId.equals(requiredDependencyId)) {
            return true;
        }
        if (visited == null) {
            visited = new LinkedHashSet<>();
        }
        if (!visited.add(fromStepId)) {
            return false;
        }
        InterpretationPlan.Step from = stepsById.get(fromStepId);
        if (from == null || from.dependsOn() == null || from.dependsOn().isEmpty()) {
            return false;
        }
        if (from.dependsOn().contains(requiredDependencyId)) {
            return true;
        }
        for (Integer dependency : from.dependsOn()) {
            if (dependsOnStep(dependency, requiredDependencyId, stepsById, visited)) {
                return true;
            }
        }
        return false;
    }

    private boolean requiresStrictInterpretationPlan(PlannerValidationContext context) {
        return context != null
            && (context.requireToolBeforeFinal()
            || context.requireDocumentWebVerification()
            || !normalizeList(context.mandatoryTools()).isEmpty());
    }

    private boolean plannerPlanInvalid(AgentDecision decision) {
        return decision != null
            && "invalid_interpretation_plan".equals(decision.reason())
            && decision.executionPlan() != null
            && "interpretation_plan".equals(decision.executionPlan().get("plannerProtocol"));
    }

    private boolean shouldRepairPlan(AgentDecision decision, PlannerValidationContext context) {
        return decision != null
            && (plannerPlanInvalid(decision) || (requiresStrictInterpretationPlan(context)
            && "legacy_action_not_allowed".equals(decision.reason())));
    }

    private void logPlannerRawOutput(String runId, int attempt, int maxAttempts, String raw) {
        log.info("agentPlannerRawOutput phase=planner runId={} attempt={}/{} chars={} content=\n{}",
            runId,
            attempt,
            maxAttempts,
            raw == null ? 0 : raw.length(),
            ModelProtocolJson.prettyJsonForLog(raw));
    }

    private void logPlannerDecision(String runId, int attempt, int maxAttempts, AgentDecision decision) {
        if (decision == null) {
            log.warn("agentPlannerDecision phase=planner_parse runId={} attempt={}/{} status=null_decision",
                runId, attempt, maxAttempts);
            return;
        }
        Map<String, Object> executionPlan = decision.executionPlan() == null ? Map.of() : decision.executionPlan();
        Object protocol = executionPlan.get("plannerProtocol");
        Object valid = executionPlan.get("interpretationPlanValid");
        Object executable = executionPlan.get("interpretationPlanExecutable");
        Object runtimeIssues = executionPlan.get("interpretationPlanRuntimeIssues");
        log.info(
            "agentPlannerDecision phase=planner_parse runId={} attempt={}/{} action={} reason={} protocol={} planPresent={} valid={} executable={} sufficient={} toolName={} runtimeIssues={} answerPreview={}",
            runId,
            attempt,
            maxAttempts,
            decision.action(),
            decision.reason(),
            protocol,
            decision.interpretationPlan() != null,
            valid,
            executable,
            decision.sufficient(),
            decision.toolName(),
            runtimeIssues,
            abbreviate(decision.answer(), 500)
        );
        if (decision.interpretationPlan() != null) {
            log.info("agentPlannerInterpretationPlan phase=planner_parse runId={} attempt={}/{} planJson=\n{}",
                runId,
                attempt,
                maxAttempts,
                prettyJson(decision.interpretationPlan()));
            log.info("agentPlannerInterpretationPlanValidation phase=planner_parse runId={} attempt={}/{} validationMetadata=\n{}",
                runId,
                attempt,
                maxAttempts,
                prettyJson(executionPlan));
        } else {
            log.warn("agentPlannerNoInterpretationPlan phase=planner_parse runId={} attempt={}/{} action={} reason={} rawAnswerPreview={}",
                runId,
                attempt,
                maxAttempts,
                decision.action(),
                decision.reason(),
                abbreviate(decision.answer(), 1000));
        }
    }

    private String prettyJson(Object value) {
        return ModelProtocolJson.pretty(value);
    }

    private String abbreviate(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        if (maxChars <= 0 || value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, maxChars) + "...";
    }

    private AgentDecision invalidPlannerDecision(String raw, String reason, String issue) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("plannerProtocol", "interpretation_plan");
        metadata.put("interpretationPlanValid", false);
        metadata.put("interpretationPlanSchemaValid", false);
        metadata.put("interpretationPlanRuntimeRulesValid", false);
        metadata.put("interpretationPlanExecutable", false);
        metadata.put("interpretationPlanRuntimeIssues", issue == null || issue.isBlank() ? List.of() : List.of(issue));
        return new AgentDecision(
            FINAL,
            null,
            Map.of(),
            raw,
            reason,
            metadata,
            false
        );
    }

    private String buildPlannerRepairPrompt(String originalPrompt,
                                            String previousOutput,
                                            AgentDecision invalidDecision,
                                            int nextAttempt,
                                            int maxAttempts) {
        StringBuilder prompt = new StringBuilder();
        prompt.append(originalPrompt).append("\n\n");
        prompt.append("Previous planner output was rejected by runtime validation.\n");
        prompt.append("Repair attempt: ").append(nextAttempt).append('/').append(maxAttempts).append("\n");
        prompt.append("Validation issues:\n");
        Object runtimeIssues = invalidDecision == null || invalidDecision.executionPlan() == null
            ? null
            : invalidDecision.executionPlan().get("interpretationPlanRuntimeIssues");
        Object schemaIssues = invalidDecision == null || invalidDecision.executionPlan() == null
            ? null
            : invalidDecision.executionPlan().get("interpretationPlanIssues");
        boolean issueWritten = false;
        if (schemaIssues instanceof List<?> issues && !issues.isEmpty()) {
            for (Object issue : issues) {
                prompt.append("- ").append(issue).append("\n");
                issueWritten = true;
            }
        }
        if (runtimeIssues instanceof List<?> issues && !issues.isEmpty()) {
            for (Object issue : issues) {
                prompt.append("- ").append(issue).append("\n");
                issueWritten = true;
            }
        }
        if (!issueWritten) {
            prompt.append("- ").append(invalidDecision == null ? "Invalid planner output" : invalidDecision.reason()).append("\n");
        }
        prompt.append("Rejected output:\n").append(previousOutput == null ? "" : previousOutput).append("\n\n");
        prompt.append("Regenerate the entire response as strict InterpretationPlan JSON only. ");
        prompt.append("Do not omit any mandatory MCP tool and do not return legacy action JSON. ");
        prompt.append("Keep tool_priority and accuracy_vs_speed values within 0.0 to 1.0.");
        return prompt.toString();
    }

    private PlanRewriteContext planRewriteContext(List<PlanCandidate> candidates) {
        List<PlanCandidate> values = candidates == null ? List.of() : List.copyOf(candidates);
        PlanCandidate last = values.isEmpty() ? null : values.get(values.size() - 1);
        String lastFailureReason = last == null || last.decision() == null ? "unknown" : last.decision().reason();
        String failurePattern = dominantFailurePattern(values);
        return new PlanRewriteContext(values.size(), values, lastFailureReason, failurePattern);
    }

    private PlanCandidate planCandidate(int attempt,
                                        String raw,
                                        AgentDecision decision,
                                        PlannerValidationContext validationContext) {
        String label = String.valueOf((char) ('A' + Math.max(0, attempt - 1)));
        String failurePattern = failurePattern(decision);
        String fingerprint = planFingerprint(decision);
        Map<String, Object> scoreDetails = deterministicPlanScoreDetails(decision, validationContext);
        int score = ((Number) scoreDetails.getOrDefault("total", 0)).intValue();
        return new PlanCandidate(attempt, label, raw, decision, failurePattern, fingerprint, score, scoreDetails);
    }

    private String dominantFailurePattern(List<PlanCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return "UNKNOWN";
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (PlanCandidate candidate : candidates) {
            counts.merge(candidate.failurePattern(), 1, Integer::sum);
        }
        String bestPattern = "UNKNOWN";
        int bestCount = -1;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (entry.getValue() > bestCount) {
                bestPattern = entry.getKey();
                bestCount = entry.getValue();
            }
        }
        return bestPattern;
    }

    private String failurePattern(AgentDecision decision) {
        if (decision == null) {
            return "NON_JSON";
        }
        if ("legacy_action_not_allowed".equals(decision.reason())) {
            return "LEGACY_ACTION";
        }
        List<String> issues = plannerIssues(decision).stream()
            .map(issue -> issue.toLowerCase(Locale.ROOT))
            .toList();
        if (issues.stream().anyMatch(issue -> issue.contains("missing") || issue.contains("not found")
            || issue.contains("available tool") || issue.contains("unavailable") || issue.contains("unknown tool"))) {
            return "TOOL_MISSING";
        }
        if (issues.stream().anyMatch(issue -> issue.contains("depend") || issue.contains("step")
            || issue.contains("cycle") || issue.contains("final_answer"))) {
            return "DAG_INVALID";
        }
        Object schemaValid = decision.executionPlan() == null ? null : decision.executionPlan().get("interpretationPlanSchemaValid");
        if (Boolean.FALSE.equals(schemaValid)) {
            return "SCHEMA_INVALID";
        }
        if (!issues.isEmpty()) {
            return "RUNTIME_POLICY";
        }
        return "UNKNOWN";
    }

    private String planFingerprint(AgentDecision decision) {
        InterpretationPlan plan = decision == null ? null : decision.interpretationPlan();
        if (plan == null || plan.steps().isEmpty()) {
            return "no-plan";
        }
        StringBuilder canonical = new StringBuilder();
        for (InterpretationPlan.Step step : plan.steps()) {
            canonical.append(step.id()).append('|')
                .append(step.actionType()).append('|')
                .append(step.toolName()).append('|')
                .append(step.dependsOn()).append(';');
        }
        return Integer.toHexString(canonical.toString().hashCode());
    }

    private int deterministicPlanScore(AgentDecision decision, PlannerValidationContext validationContext) {
        Map<String, Object> details = deterministicPlanScoreDetails(decision, validationContext);
        return ((Number) details.getOrDefault("total", 0)).intValue();
    }

    private Map<String, Object> deterministicPlanScoreDetails(AgentDecision decision,
                                                              PlannerValidationContext validationContext) {
        InterpretationPlan plan = decision == null ? null : decision.interpretationPlan();
        if (plan == null) {
            return Map.of(
                "toolAvailability", 0,
                "dagValidity", 0,
                "executionCost", 0,
                "runtimePolicyFit", 0,
                "total", 0
            );
        }
        int toolAvailability = toolAvailabilityScore(plan, validationContext);
        int dagValidity = dagValidityScore(decision);
        int executionCost = executionCostScore(plan);
        int runtimePolicyFit = runtimePolicyFitScore(plan, decision, validationContext);
        Map<String, Object> coverage = coverageScoreDetails(plan, validationContext);
        int coverageScore = ((Number) coverage.getOrDefault("coverageScore", 0)).intValue();
        int total = Math.max(0, Math.min(100, toolAvailability + dagValidity + executionCost + runtimePolicyFit + coverageScore));
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("toolAvailability", toolAvailability);
        details.put("dagValidity", dagValidity);
        details.put("executionCost", executionCost);
        details.put("runtimePolicyFit", runtimePolicyFit);
        details.put("coverageScore", coverageScore);
        details.put("coverage", coverage);
        details.put("total", total);
        return details;
    }

    private int toolAvailabilityScore(InterpretationPlan plan, PlannerValidationContext validationContext) {
        List<String> tools = plan.steps().stream()
            .filter(InterpretationPlan.Step::mcpToolAction)
            .map(InterpretationPlan.Step::toolName)
            .filter(tool -> tool != null && !tool.isBlank())
            .toList();
        if (tools.isEmpty()) {
            return 0;
        }
        long available = tools.stream()
            .filter(tool -> toolAvailable(tool, validationContext))
            .count();
        return (int) Math.round(30.0 * available / tools.size());
    }

    private boolean toolAvailable(String toolName, PlannerValidationContext validationContext) {
        if (toolName == null || toolName.isBlank()) {
            return false;
        }
        List<String> availableTools = validationContext == null ? List.of() : normalizeList(validationContext.availableTools());
        if (!availableTools.isEmpty()) {
            return availableTools.stream().anyMatch(tool -> sameToolName(tool, toolName));
        }
        return toolRegistry != null && toolRegistry.hasTool(toolName);
    }

    private int dagValidityScore(AgentDecision decision) {
        Map<String, Object> metadata = decision == null || decision.executionPlan() == null ? Map.of() : decision.executionPlan();
        int score = 0;
        if (Boolean.TRUE.equals(metadata.get("interpretationPlanSchemaValid"))) {
            score += 10;
        }
        if (Boolean.TRUE.equals(metadata.get("interpretationPlanRuntimeRulesValid"))) {
            score += 10;
        }
        if (Boolean.TRUE.equals(metadata.get("interpretationPlanExecutable"))) {
            score += 5;
        }
        return score;
    }

    private int executionCostScore(InterpretationPlan plan) {
        long toolSteps = plan.steps().stream().filter(InterpretationPlan.Step::mcpToolAction).count();
        if (toolSteps <= 0) {
            return 0;
        }
        return (int) Math.max(3, 15 - Math.max(0, toolSteps - 1) * 3);
    }

    private int runtimePolicyFitScore(InterpretationPlan plan,
                                      AgentDecision decision,
                                      PlannerValidationContext validationContext) {
        int score = 20;
        List<String> issues = plannerIssues(decision);
        score -= Math.min(12, issues.size() * 4);
        List<String> mandatoryTools = validationContext == null ? List.of() : normalizeList(validationContext.mandatoryTools());
        for (String mandatoryTool : mandatoryTools) {
            boolean present = plan.steps().stream()
                .anyMatch(step -> step.mcpToolAction() && sameToolName(step.toolName(), mandatoryTool));
            if (!present) {
                score -= 8;
            }
        }
        return Math.max(0, score);
    }

    private Map<String, Object> coverageScoreDetails(InterpretationPlan plan,
                                                     PlannerValidationContext validationContext) {
        List<String> matched = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        if (plan == null) {
            return Map.of(
                "coverageScore", 0,
                "matchedCapabilities", matched,
                "missingCapabilities", missing
            );
        }
        List<InterpretationPlan.Step> steps = plan.steps();
        Map<Integer, InterpretationPlan.Step> stepsById = stepsById(steps);
        InterpretationPlan.Step finalStep = finalStep(steps);
        List<InterpretationPlan.Step> toolSteps = steps.stream()
            .filter(step -> step != null && step.mcpToolAction())
            .toList();

        int mandatoryCoverage = mandatoryCoverageScore(toolSteps, validationContext, matched, missing);
        int evidenceDependency = evidenceDependencyScore(plan, finalStep, toolSteps, stepsById, validationContext, matched, missing);
        int workflowCoverage = workflowCoverageScore(finalStep, stepsById, toolSteps, validationContext, matched, missing);
        int stageCoverage = stageCoverageScore(steps, toolSteps, finalStep, matched, missing);
        int goalCoverage = goalCoverageScore(plan, validationContext, matched, missing);
        int total = Math.max(0, Math.min(25, mandatoryCoverage + evidenceDependency + workflowCoverage + stageCoverage + goalCoverage));

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("coverageScore", total);
        details.put("mandatoryCoverage", mandatoryCoverage);
        details.put("evidenceDependency", evidenceDependency);
        details.put("workflowCoverage", workflowCoverage);
        details.put("stageCoverage", stageCoverage);
        details.put("goalCoverage", goalCoverage);
        details.put("matchedCapabilities", matched.stream().distinct().toList());
        details.put("missingCapabilities", missing.stream().distinct().toList());
        return details;
    }

    private int mandatoryCoverageScore(List<InterpretationPlan.Step> toolSteps,
                                       PlannerValidationContext validationContext,
                                       List<String> matched,
                                       List<String> missing) {
        List<String> mandatoryTools = validationContext == null ? List.of() : normalizeList(validationContext.mandatoryTools());
        if (mandatoryTools.isEmpty()) {
            matched.add("no_mandatory_tool_gap");
            return 5;
        }
        long present = mandatoryTools.stream()
            .filter(tool -> toolSteps.stream().anyMatch(step -> sameToolName(step.toolName(), tool)))
            .peek(tool -> matched.add("mandatory_tool:" + tool))
            .count();
        mandatoryTools.stream()
            .filter(tool -> toolSteps.stream().noneMatch(step -> sameToolName(step.toolName(), tool)))
            .forEach(tool -> missing.add("mandatory_tool:" + tool));
        return (int) Math.round(7.0 * present / mandatoryTools.size());
    }

    private int evidenceDependencyScore(InterpretationPlan plan,
                                        InterpretationPlan.Step finalStep,
                                        List<InterpretationPlan.Step> toolSteps,
                                        Map<Integer, InterpretationPlan.Step> stepsById,
                                        PlannerValidationContext validationContext,
                                        List<String> matched,
                                        List<String> missing) {
        boolean evidenceExpected = expectsToolEvidence(plan, validationContext);
        if (!evidenceExpected) {
            matched.add("direct_answer_allowed");
            return 5;
        }
        if (!toolSteps.isEmpty() && finalDependsOnAnyTool(finalStep, toolSteps, stepsById)) {
            matched.add("final_answer_depends_on_tool_evidence");
            return 5;
        }
        if (toolSteps.isEmpty()) {
            missing.add("tool_evidence_step");
        } else {
            missing.add("final_answer_tool_dependency");
        }
        return 0;
    }

    private int workflowCoverageScore(InterpretationPlan.Step finalStep,
                                      Map<Integer, InterpretationPlan.Step> stepsById,
                                      List<InterpretationPlan.Step> toolSteps,
                                      PlannerValidationContext validationContext,
                                      List<String> matched,
                                      List<String> missing) {
        if (validationContext != null && validationContext.requireDocumentWebVerification()) {
            boolean documentCovered = finalDependsOnTool(finalStep, validationContext.documentSearchTool(), stepsById, toolSteps);
            boolean webCovered = finalDependsOnTool(finalStep, validationContext.verificationWebSearchTool(), stepsById, toolSteps);
            if (documentCovered && webCovered) {
                matched.add("document_web_verification_chain");
                return 5;
            }
            if (!documentCovered) {
                missing.add("document_verification_chain");
            }
            if (!webCovered) {
                missing.add("web_verification_chain");
            }
            return documentCovered || webCovered ? 2 : 0;
        }

        int score = 0;
        if (expectsDocumentEvidence(validationContext)
            && finalDependsOnTool(finalStep, validationContext.documentSearchTool(), stepsById, toolSteps)) {
            matched.add("document_evidence_chain");
            score += 3;
        } else if (expectsDocumentEvidence(validationContext)) {
            missing.add("document_evidence_chain");
        }

        if (expectsWebEvidence(validationContext)) {
            boolean webCovered = toolSteps.stream().anyMatch(step -> isWebDiscoveryTool(step.toolName()))
                && finalDependsOnAnyTool(finalStep, toolSteps.stream().filter(step -> isWebDiscoveryTool(step.toolName())).toList(), stepsById);
            if (webCovered) {
                matched.add("web_evidence_chain");
                score += 2;
            } else {
                missing.add("web_evidence_chain");
            }
        }
        return Math.min(5, score);
    }

    private int stageCoverageScore(List<InterpretationPlan.Step> steps,
                                   List<InterpretationPlan.Step> toolSteps,
                                   InterpretationPlan.Step finalStep,
                                   List<String> matched,
                                   List<String> missing) {
        int score = 0;
        if (finalStep != null) {
            matched.add("final_answer_stage");
            score += 2;
        } else {
            missing.add("final_answer_stage");
        }
        if (!toolSteps.isEmpty()) {
            matched.add("tool_execution_stage");
            score += 2;
        }
        boolean hasIntermediateStage = steps.stream()
            .filter(step -> step != null && !step.mcpToolAction() && !step.finalAnswerAction())
            .anyMatch(step -> !normalize(step.actionType()).isBlank());
        if (hasIntermediateStage || toolSteps.size() > 1) {
            matched.add("multi_stage_plan");
            score += 1;
        }
        return Math.min(5, score);
    }

    private int goalCoverageScore(InterpretationPlan plan,
                                  PlannerValidationContext validationContext,
                                  List<String> matched,
                                  List<String> missing) {
        List<String> goalTerms = significantGoalTerms(validationContext == null ? null : validationContext.query());
        if (goalTerms.isEmpty()) {
            matched.add("no_goal_keyword_gap");
            return 3;
        }
        String planText = normalize(planText(plan));
        long covered = goalTerms.stream()
            .filter(term -> planText.contains(normalize(term)))
            .peek(term -> matched.add("goal_term:" + term))
            .count();
        goalTerms.stream()
            .filter(term -> !planText.contains(normalize(term)))
            .forEach(term -> missing.add("goal_term:" + term));
        return (int) Math.round(3.0 * covered / goalTerms.size());
    }

    private Map<Integer, InterpretationPlan.Step> stepsById(List<InterpretationPlan.Step> steps) {
        Map<Integer, InterpretationPlan.Step> values = new LinkedHashMap<>();
        for (InterpretationPlan.Step step : steps == null ? List.<InterpretationPlan.Step>of() : steps) {
            if (step != null && step.id() != null) {
                values.put(step.id(), step);
            }
        }
        return values;
    }

    private InterpretationPlan.Step finalStep(List<InterpretationPlan.Step> steps) {
        if (steps == null) {
            return null;
        }
        return steps.stream()
            .filter(step -> step != null && step.finalAnswerAction())
            .findFirst()
            .orElse(null);
    }

    private boolean finalDependsOnAnyTool(InterpretationPlan.Step finalStep,
                                          List<InterpretationPlan.Step> toolSteps,
                                          Map<Integer, InterpretationPlan.Step> stepsById) {
        if (finalStep == null || toolSteps == null || toolSteps.isEmpty()) {
            return false;
        }
        return toolSteps.stream()
            .anyMatch(step -> step != null && dependsOnStep(finalStep.id(), step.id(), stepsById, new LinkedHashSet<>()));
    }

    private boolean finalDependsOnTool(InterpretationPlan.Step finalStep,
                                       String toolName,
                                       Map<Integer, InterpretationPlan.Step> stepsById,
                                       List<InterpretationPlan.Step> toolSteps) {
        if (finalStep == null || toolName == null || toolName.isBlank()) {
            return false;
        }
        return toolSteps.stream()
            .filter(step -> step != null && sameToolName(step.toolName(), toolName))
            .anyMatch(step -> dependsOnStep(finalStep.id(), step.id(), stepsById, new LinkedHashSet<>()));
    }

    private boolean expectsToolEvidence(InterpretationPlan plan, PlannerValidationContext validationContext) {
        return validationContext != null
            && (validationContext.requireToolBeforeFinal()
            || validationContext.requireDocumentWebVerification()
            || !normalizeList(validationContext.mandatoryTools()).isEmpty()
            || expectsDocumentEvidence(validationContext)
            || expectsWebEvidence(validationContext))
            || plan != null && plan.steps().stream().anyMatch(InterpretationPlan.Step::mcpToolAction);
    }

    private boolean expectsDocumentEvidence(PlannerValidationContext validationContext) {
        String text = requestText(validationContext);
        return containsAny(text, "document", "doc", "file", "report", "paper", "knowledge", "internal",
            "文档", "文件", "报告", "论文", "知识库", "内部", "资料");
    }

    private boolean expectsWebEvidence(PlannerValidationContext validationContext) {
        String text = requestText(validationContext);
        return containsAny(text, "web", "website", "site", "online", "internet", "current", "latest", "today", "recent",
            "网页", "网站", "联网", "互联网", "当前", "最新", "今天", "近期");
    }

    private String requestText(PlannerValidationContext validationContext) {
        return validationContext == null || validationContext.query() == null
            ? ""
            : validationContext.query().toLowerCase(Locale.ROOT);
    }

    private boolean containsAny(String text, String... tokens) {
        if (text == null || text.isBlank() || tokens == null) {
            return false;
        }
        for (String token : tokens) {
            if (token != null && !token.isBlank() && text.contains(token.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private List<String> significantGoalTerms(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        Set<String> terms = new LinkedHashSet<>();
        String normalized = query.toLowerCase(Locale.ROOT)
            .replaceAll("[^\\p{IsHan}a-z0-9_]+", " ")
            .trim();
        for (String term : normalized.split("\\s+")) {
            if (term.length() >= 4 && !isStopword(term)) {
                terms.add(term);
            }
        }
        for (String keyword : List.of("文档", "文件", "报告", "论文", "知识库", "内部", "搜索", "检索", "联网", "最新", "今天", "分析", "汇总", "验证")) {
            if (query.contains(keyword)) {
                terms.add(keyword);
            }
        }
        return terms.stream().limit(6).toList();
    }

    private boolean isStopword(String term) {
        return Set.of(
            "what", "with", "from", "that", "this", "into", "about", "please", "using",
            "the", "and", "for", "are", "how"
        ).contains(term);
    }

    private String planText(InterpretationPlan plan) {
        if (plan == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        if (plan.intent() != null) {
            builder.append(plan.intent().type()).append(' ')
                .append(plan.intent().goal()).append(' ')
                .append(plan.intent().riskLevel()).append(' ');
        }
        if (plan.context() != null) {
            builder.append(plan.context().keyFacts()).append(' ')
                .append(plan.context().assumptions()).append(' ')
                .append(plan.context().missingInfo()).append(' ')
                .append(plan.context().constraints()).append(' ');
        }
        for (InterpretationPlan.Step step : plan.steps()) {
            if (step == null) {
                continue;
            }
            builder.append(step.actionType()).append(' ')
                .append(step.toolName()).append(' ')
                .append(step.input()).append(' ');
        }
        return builder.toString();
    }

    private AgentDecision attributeAndSelectBestPlan(PlanRewriteContext rewriteContext,
                                                     PlannerValidationContext validationContext,
                                                     String runId) {
        if (rewriteContext == null || rewriteContext.candidates().isEmpty()) {
            return null;
        }
        PlanCandidate selected = selectBestCandidate(rewriteContext.candidates());
        if (selected == null || selected.decision() == null) {
            return null;
        }
        GuardRepairResult repair = deterministicGuardRepair(selected.decision(), validationContext);
        AgentDecision selectedDecision = repair.decision();
        String reason = attributionReason(selected, repair);
        AgentDecision attributed = withAttributionMetadata(
            selectedDecision,
            selected,
            rewriteContext,
            reason,
            repair.applied(),
            repair.notes()
        );
        log.info("agentPlannerAttribution phase=planner_attribution runId={} source=deterministic selected={} score={} repairApplied={} candidateCount={}",
            runId == null ? "" : runId,
            selected.label(),
            selected.deterministicScore(),
            repair.applied(),
            rewriteContext.candidates().size());
        logPlannerDecision(runId == null ? "" : runId, rewriteContext.rewriteCount(), rewriteContext.rewriteCount(), attributed);
        return attributed;
    }

    private PlanCandidate selectBestCandidate(List<PlanCandidate> candidates) {
        PlanCandidate best = null;
        for (PlanCandidate candidate : candidates == null ? List.<PlanCandidate>of() : candidates) {
            if (candidate == null || candidate.decision() == null) {
                continue;
            }
            if (best == null || betterCandidate(candidate, best)) {
                best = candidate;
            }
        }
        return best;
    }

    private boolean betterCandidate(PlanCandidate candidate, PlanCandidate currentBest) {
        if (candidate.deterministicScore() != currentBest.deterministicScore()) {
            return candidate.deterministicScore() > currentBest.deterministicScore();
        }
        boolean candidateValid = !plannerPlanInvalid(candidate.decision());
        boolean bestValid = !plannerPlanInvalid(currentBest.decision());
        if (candidateValid != bestValid) {
            return candidateValid;
        }
        int candidateIssueCount = plannerIssues(candidate.decision()).size();
        int bestIssueCount = plannerIssues(currentBest.decision()).size();
        if (candidateIssueCount != bestIssueCount) {
            return candidateIssueCount < bestIssueCount;
        }
        return candidate.attempt() > currentBest.attempt();
    }

    private List<String> plannerIssues(AgentDecision decision) {
        if (decision == null || decision.executionPlan() == null || decision.executionPlan().isEmpty()) {
            return List.of();
        }
        List<String> issues = new ArrayList<>();
        Object runtimeIssues = decision.executionPlan().get("interpretationPlanRuntimeIssues");
        if (runtimeIssues instanceof List<?> list) {
            list.stream().map(String::valueOf).forEach(issues::add);
        }
        Object validationIssues = decision.executionPlan().get("interpretationPlanIssues");
        if (validationIssues instanceof List<?> list) {
            list.stream().map(String::valueOf).forEach(issues::add);
        }
        return issues.stream()
            .filter(issue -> issue != null && !issue.isBlank())
            .distinct()
            .toList();
    }

    private GuardRepairResult deterministicGuardRepair(AgentDecision decision,
                                                       PlannerValidationContext validationContext) {
        InterpretationPlan plan = decision == null ? null : decision.interpretationPlan();
        if (plan == null) {
            return new GuardRepairResult(decision, false, List.of());
        }
        List<String> unavailableTools = plan.steps().stream()
            .filter(InterpretationPlan.Step::mcpToolAction)
            .map(InterpretationPlan.Step::toolName)
            .filter(tool -> tool != null && !tool.isBlank())
            .filter(tool -> !toolAvailable(tool, validationContext))
            .distinct()
            .toList();
        if (unavailableTools.isEmpty()) {
            return new GuardRepairResult(decision, false, List.of());
        }
        List<String> replacements = candidateReplacementTools(validationContext);
        if (replacements.size() != 1) {
            return new GuardRepairResult(decision, false, List.of("Skipped repair because replacement tool was ambiguous."));
        }
        String replacement = replacements.get(0);
        if (!toolAvailable(replacement, validationContext)) {
            return new GuardRepairResult(decision, false, List.of("Skipped repair because replacement tool is unavailable."));
        }
        Map<String, String> toolReplacements = new LinkedHashMap<>();
        unavailableTools.forEach(tool -> toolReplacements.put(tool, replacement));
        InterpretationPlan repairedPlan = replaceUnavailableTools(plan, toolReplacements);
        AgentDecision repairedDecision = parseInterpretationPlanDecision(objectMapper.convertValue(repairedPlan, Map.class), validationContext);
        if (repairedDecision == null || plannerPlanInvalid(repairedDecision)) {
            return new GuardRepairResult(decision, false, List.of("Skipped repair because repaired plan did not pass validation."));
        }
        List<String> notes = unavailableTools.stream()
            .map(tool -> "Replaced unavailable tool " + tool + " with " + replacement + ".")
            .toList();
        return new GuardRepairResult(repairedDecision, true, notes);
    }

    private List<String> candidateReplacementTools(PlannerValidationContext validationContext) {
        List<String> mandatoryTools = validationContext == null ? List.of() : normalizeList(validationContext.mandatoryTools());
        List<String> availableTools = validationContext == null ? List.of() : normalizeList(validationContext.availableTools());
        List<String> mandatoryAvailable = mandatoryTools.stream()
            .filter(tool -> toolAvailable(tool, validationContext))
            .distinct()
            .toList();
        if (mandatoryAvailable.size() == 1) {
            return mandatoryAvailable;
        }
        List<String> registeredAvailable = availableTools.stream()
            .filter(tool -> toolAvailable(tool, validationContext))
            .distinct()
            .toList();
        if (registeredAvailable.size() == 1) {
            return registeredAvailable;
        }
        String documentTool = validationContext == null ? null : validationContext.documentSearchTool();
        if (documentTool != null && !documentTool.isBlank() && toolAvailable(documentTool, validationContext)) {
            return List.of(documentTool);
        }
        return registeredAvailable;
    }

    private InterpretationPlan replaceUnavailableTools(InterpretationPlan plan, Map<String, String> toolReplacements) {
        List<InterpretationPlan.Step> steps = plan.steps().stream()
            .map(step -> {
                if (step == null || !step.mcpToolAction()) {
                    return step;
                }
                String replacement = toolReplacements.get(step.toolName());
                if (replacement == null) {
                    return step;
                }
                return new InterpretationPlan.Step(
                    step.id(),
                    step.actionType(),
                    replacement,
                    step.input(),
                    step.dependsOn(),
                    step.outputContract(),
                    step.validation()
                );
            })
            .toList();
        InterpretationPlan.Plan originalPlan = plan.plan();
        InterpretationPlan.Plan repairedInnerPlan = new InterpretationPlan.Plan(
            steps,
            originalPlan == null ? List.of() : originalPlan.edgeContracts(),
            originalPlan == null ? List.of() : originalPlan.bindings(),
            originalPlan == null ? null : originalPlan.stability()
        );
        return new InterpretationPlan(
            plan.version(),
            plan.intent(),
            plan.context(),
            repairedInnerPlan,
            replacePolicyTools(plan.executionPolicy(), toolReplacements),
            plan.review()
        );
    }

    private InterpretationPlan.ExecutionPolicy replacePolicyTools(InterpretationPlan.ExecutionPolicy policy,
                                                                  Map<String, String> toolReplacements) {
        if (policy == null) {
            return null;
        }
        return new InterpretationPlan.ExecutionPolicy(
            policy.maxSteps(),
            policy.allowParallel(),
            replaceToolList(policy.allowTool(), toolReplacements),
            replaceToolList(policy.denyTool(), toolReplacements),
            policy.timeoutMs(),
            policy.maxRewriteTimes(),
            policy.fallbackMode(),
            replaceToolPriority(policy.toolPriority(), toolReplacements),
            policy.costBudget(),
            policy.latencyBudgetMs(),
            policy.accuracyVsSpeed()
        );
    }

    private List<String> replaceToolList(List<String> tools, Map<String, String> toolReplacements) {
        if (tools == null) {
            return List.of();
        }
        return tools.stream()
            .map(tool -> toolReplacements.getOrDefault(tool, tool))
            .filter(tool -> tool != null && !tool.isBlank())
            .distinct()
            .toList();
    }

    private Map<String, Double> replaceToolPriority(Map<String, Double> priorities,
                                                    Map<String, String> toolReplacements) {
        if (priorities == null || priorities.isEmpty()) {
            return priorities;
        }
        Map<String, Double> values = new LinkedHashMap<>();
        priorities.forEach((tool, priority) -> values.put(toolReplacements.getOrDefault(tool, tool), priority));
        return values;
    }

    private String attributionReason(PlanCandidate selected, GuardRepairResult repair) {
        StringBuilder reason = new StringBuilder();
        reason.append("Selected candidate ")
            .append(selected.label())
            .append(" by deterministic attribution score ")
            .append(selected.deterministicScore())
            .append("/100.");
        if (repair != null && repair.applied()) {
            reason.append(" Applied guard repair for verifiable unavailable-tool mapping.");
        }
        return reason.toString();
    }

    private AgentDecision withAttributionMetadata(AgentDecision decision,
                                                  PlanCandidate selected,
                                                  PlanRewriteContext rewriteContext,
                                                  String reason,
                                                  boolean repairApplied,
                                                  List<String> repairNotes) {
        if (decision == null) {
            return null;
        }
        Map<String, Object> executionPlan = new LinkedHashMap<>(decision.executionPlan() == null ? Map.of() : decision.executionPlan());
        executionPlan.put("plannerAttributionSelection", true);
        executionPlan.put("plannerAttributionSource", "deterministic_java");
        executionPlan.put("plannerAttributionContractVersion", "plan_attribution_v1");
        executionPlan.put("plannerGenerationLimit", MAX_PLAN_REPAIR_ATTEMPTS);
        executionPlan.put("plannerGenerationCount", rewriteContext == null ? 0 : rewriteContext.candidates().size());
        executionPlan.put("plannerAttributionCandidateCount", rewriteContext == null ? 0 : rewriteContext.candidates().size());
        executionPlan.put("plannerAttributionSelected", selected == null ? null : selected.label());
        executionPlan.put("plannerAttributionSelectedAttempt", selected == null ? null : selected.attempt());
        executionPlan.put("plannerAttributionReason", reason);
        executionPlan.put("plannerAttributionAnalysis", reason);
        executionPlan.put("plannerAttributionScores", plannerAttributionScores(rewriteContext));
        executionPlan.put("plannerAttributionCandidates", plannerAttributionCandidates(rewriteContext));
        executionPlan.put("plannerAttributionFailurePattern", rewriteContext == null ? "UNKNOWN" : rewriteContext.failurePattern());
        executionPlan.put("plannerAttributionCandidateFingerprints", rewriteContext == null ? List.of() : rewriteContext.candidates().stream()
            .map(candidate -> Map.of(
                "label", candidate.label(),
                "fingerprint", candidate.fingerprint(),
                "failurePattern", candidate.failurePattern(),
                "deterministicScore", candidate.deterministicScore()
            ))
            .toList());
        executionPlan.put("plannerAttributionRepairApplied", repairApplied);
        executionPlan.put("plannerAttributionRepairNotes", repairNotes == null ? List.of() : repairNotes);
        return new AgentDecision(
            decision.action(),
            decision.toolName(),
            decision.arguments(),
            decision.answer(),
            decision.reason(),
            executionPlan,
            decision.sufficient(),
            decision.interpretationPlan()
        );
    }

    private Map<String, Object> plannerAttributionScores(PlanRewriteContext rewriteContext) {
        Map<String, Object> scores = new LinkedHashMap<>();
        if (rewriteContext == null || rewriteContext.candidates() == null) {
            return scores;
        }
        for (PlanCandidate candidate : rewriteContext.candidates()) {
            scores.put(candidate.label(), candidate.deterministicScore());
        }
        return scores;
    }

    private List<Map<String, Object>> plannerAttributionCandidates(PlanRewriteContext rewriteContext) {
        if (rewriteContext == null || rewriteContext.candidates() == null) {
            return List.of();
        }
        return rewriteContext.candidates().stream()
            .map(candidate -> {
                Map<String, Object> record = new LinkedHashMap<>();
                record.put("label", candidate.label());
                record.put("attempt", candidate.attempt());
                record.put("failurePattern", candidate.failurePattern());
                record.put("fingerprint", candidate.fingerprint());
                record.put("deterministicScore", candidate.deterministicScore());
                record.put("scoreDetails", candidate.deterministicScoreDetails());
                record.put("issues", plannerIssues(candidate.decision()));
                record.put("valid", candidate.decision() != null && !plannerPlanInvalid(candidate.decision()));
                return record;
            })
            .toList();
    }

    private int plannerRepairAttempts(Map<String, Object> runtimeAttributes) {
        Object configured = runtimeAttributes == null ? null : runtimeAttributes.get("plannerMaxRepairAttempts");
        if (configured instanceof Number number) {
            return Math.max(1, Math.min(MAX_PLAN_REPAIR_ATTEMPTS, number.intValue()));
        }
        if (configured != null) {
            try {
                return Math.max(1, Math.min(MAX_PLAN_REPAIR_ATTEMPTS, Integer.parseInt(String.valueOf(configured))));
            } catch (NumberFormatException ignored) {
                return DEFAULT_PLAN_REPAIR_ATTEMPTS;
            }
        }
        return DEFAULT_PLAN_REPAIR_ATTEMPTS;
    }

    private List<String> normalizeList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
            .filter(value -> value != null && !value.isBlank())
            .map(String::trim)
            .distinct()
            .toList();
    }

    private String extractJson(String raw) {
        String text = raw.trim();
        int blockStart = text.indexOf("```");
        if (blockStart >= 0) {
            int firstBrace = text.indexOf('{', blockStart);
            int lastBrace = text.lastIndexOf('}');
            if (firstBrace >= 0 && lastBrace > firstBrace) {
                return text.substring(firstBrace, lastBrace + 1);
            }
        }
        int firstBrace = text.indexOf('{');
        int lastBrace = text.lastIndexOf('}');
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            return text.substring(firstBrace, lastBrace + 1);
        }
        return text;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replace('-', '_');
    }

    private boolean sameToolName(String first, String second) {
        if (first == null || second == null) {
            return false;
        }
        String left = first.trim();
        String right = second.trim();
        String leftAlias = normalizeKnownToolAlias(left);
        String rightAlias = normalizeKnownToolAlias(right);
        return left.equals(right)
            || left.equals(rightAlias)
            || leftAlias.equals(right)
            || leftAlias.equals(rightAlias)
            || toolSemanticKey(left).equals(toolSemanticKey(right));
    }

    private String normalizeKnownToolAlias(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return toolName;
        }
        String semantic = toolSemanticKey(toolName);
        if (semantic.contains("document") && semantic.contains("search")) {
            return DOCUMENT_SEARCH_TOOL;
        }
        if (semantic.equals("web_search") || semantic.endsWith("_web_search") || semantic.contains("web_search")) {
            return "web_search";
        }
        if (semantic.contains("search_and_extract")) {
            return "search_and_extract";
        }
        if ("asset_query".equals(semantic) || "asset_discovery".equals(semantic)) {
            return "asset_discovery";
        }
        if ("template_query".equals(semantic) || "template_discovery".equals(semantic)) {
            return "template_discovery";
        }
        if (semantic.endsWith("_asset_query")) {
            return "asset_discovery";
        }
        if (semantic.endsWith("_template_query")) {
            return "template_discovery";
        }
        return toolName.trim();
    }

    private String matchingAvailableTool(List<String> availableTools, String semanticToolName) {
        if (availableTools == null || semanticToolName == null) {
            return null;
        }
        for (String availableTool : availableTools) {
            String semantic = toolSemanticKey(availableTool);
            if (semanticToolName.equals(semantic)
                || ("asset_discovery".equals(semanticToolName) && "asset_query".equals(semantic))
                || ("template_discovery".equals(semanticToolName) && "template_query".equals(semantic))
                || ("asset_discovery".equals(semanticToolName) && semantic.endsWith("_asset_query"))
                || ("template_discovery".equals(semanticToolName) && semantic.endsWith("_template_query"))
                || ("asset_query".equals(semanticToolName) && semantic.endsWith("_asset_query"))
                || ("template_query".equals(semanticToolName) && semantic.endsWith("_template_query"))) {
                return availableTool;
            }
        }
        return null;
    }

    private String toolSemanticKey(String toolName) {
        if (toolName == null) {
            return "";
        }
        String normalized = toolName.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        while (normalized.startsWith("mcp_")) {
            normalized = normalized.substring(4);
        }
        String[] prefixes = {
            "chatchat_mcp_server_",
            "chatchat_",
            "xxx_"
        };
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String prefix : prefixes) {
                if (normalized.startsWith(prefix)) {
                    normalized = normalized.substring(prefix.length());
                    changed = true;
                }
            }
        }
        return normalized;
    }

    private boolean isAssetDiscoverySemantic(String semantic) {
        return "asset_discovery".equals(semantic)
            || "asset_query".equals(semantic)
            || (semantic != null && semantic.endsWith("_asset_query"));
    }

    private boolean isTemplateDiscoverySemantic(String semantic) {
        return "template_discovery".equals(semantic)
            || "template_query".equals(semantic)
            || (semantic != null && semantic.endsWith("_template_query"));
    }

    private Map<String, Object> asMap(Object data) {
        if (data instanceof Map<?, ?> map) {
            Map<String, Object> values = new LinkedHashMap<>();
            map.forEach((key, value) -> {
                if (key != null) {
                    values.put(String.valueOf(key), value);
                }
            });
            return values;
        }
        return Map.of();
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Boolean booleanObject(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value == null) {
            return null;
        }
        return Boolean.parseBoolean(String.valueOf(value));
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

    private List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream()
                .map(this::stringValue)
                .filter(item -> item != null && !item.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
        }
        if (value == null) {
            return List.of();
        }
        String text = String.valueOf(value).trim();
        return text.isBlank() ? List.of() : List.of(text);
    }

    private Object firstObject(Map<String, Object> values, String... keys) {
        if (values == null || values.isEmpty() || keys == null) {
            return null;
        }
        for (String key : keys) {
            Object value = values.get(key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second == null || second.isBlank() ? null : second;
    }
}

record PlannerValidationContext(
    List<String> mandatoryTools,
    boolean requireToolBeforeFinal,
    boolean requireDocumentWebVerification,
    String documentSearchTool,
    String verificationWebSearchTool,
    List<String> availableTools,
    String query
) {
}

record AgentDecision(
    String action,
    String toolName,
    Map<String, Object> arguments,
    String answer,
    String reason,
    Map<String, Object> executionPlan,
    Boolean sufficient,
    InterpretationPlan interpretationPlan
) {
    AgentDecision(String action,
                  String toolName,
                  Map<String, Object> arguments,
                  String answer,
                  String reason,
                  Map<String, Object> executionPlan,
                  Boolean sufficient) {
        this(action, toolName, arguments, answer, reason, executionPlan, sufficient, null);
    }
}

record PlanRewriteContext(
    int rewriteCount,
    List<PlanCandidate> candidates,
    String lastFailureReason,
    String failurePattern
) {
}

record PlanCandidate(
    int attempt,
    String label,
    String raw,
    AgentDecision decision,
    String failurePattern,
    String fingerprint,
    int deterministicScore,
    Map<String, Object> deterministicScoreDetails
) {
}

record GuardRepairResult(
    AgentDecision decision,
    boolean applied,
    List<String> notes
) {
}
