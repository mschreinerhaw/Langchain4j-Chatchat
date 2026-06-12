package com.chatchat.chat.task;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentLearningService {

    private final AgentExperienceRepository experienceRepository;
    private final AgentExperienceIndexRepository indexRepository;
    private final AgentEventStore eventStore;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<ChatModel> chatModelProvider;
    private final AgentLearningProperties properties;

    @Transactional
    public AgentExperienceSummary.ExperienceItem recordExperience(AgentTaskLatestEntity task,
                                                                  AgentTaskFeedbackRequest feedback) {
        if (!properties.isEnabled() || task == null || feedback == null) {
            return null;
        }
        Attribution attribution = analyze(task, feedback);
        AgentExperienceEntity entity = experienceRepository
            .findByTenantIdAndTaskId(task.getTenantId(), task.getTaskId())
            .orElseGet(AgentExperienceEntity::new);
        entity.setTenantId(task.getTenantId());
        entity.setTaskId(task.getTaskId());
        entity.setAgentId(task.getAgentId());
        entity.setSessionId(task.getSessionId());
        entity.setUserId(task.getUserId());
        entity.setScenarioKey(firstText(attribution.scenarioKey(), scenarioKey(task.getQuestion())));
        entity.setScenarioName(firstText(attribution.scenarioName(), scenarioName(task.getQuestion())));
        entity.setQuestion(task.getQuestion());
        entity.setAnswerSummary(task.getAnswerSummary());
        entity.setFeedbackUseful(task.getFeedbackUseful());
        entity.setFeedbackAdopted(task.getFeedbackAdopted());
        entity.setFeedbackResolved(task.getFeedbackResolved());
        entity.setFeedbackReasonCategory(task.getFeedbackReasonCategory());
        entity.setFeedbackComment(task.getFeedbackComment());
        entity.setFeedbackScore(feedbackScore(task));
        entity.setAttributionSource(attribution.source());
        entity.setAttributionSummary(truncate(attribution.summary(), 1000));
        entity.setPrimaryFactorsJson(writeList(attribution.primaryFactors()));
        entity.setSuccessPatternJson(writeList(attribution.successPattern()));
        entity.setImprovementSuggestionsJson(writeList(attribution.improvementSuggestions()));
        entity.setModelRawOutput(truncate(attribution.rawOutput(), 4000));
        AgentExperienceEntity saved = experienceRepository.save(entity);
        rebuildExperienceIndex(saved.getTenantId());
        return toItem(saved);
    }

    public AgentExperienceSummary summarize(String tenantId, int limit) {
        String normalizedTenant = requireTenant(tenantId);
        int normalizedLimit = Math.max(1, Math.min(limit <= 0 ? properties.getExperienceLimit() : limit, 100));
        int scenarioLimit = Math.max(1, Math.min(properties.getScenarioLimit(), 20));
        List<AgentExperienceSummary.ScenarioMetric> scenarios = experienceRepository
            .summarizeScenarios(normalizedTenant, PageRequest.of(0, scenarioLimit))
            .stream()
            .map(row -> new AgentExperienceSummary.ScenarioMetric(
                row.getScenarioKey(),
                row.getScenarioName(),
                row.getTotal(),
                round(row.getAverageScore() == null ? 0D : row.getAverageScore())
            ))
            .toList();
        List<AgentExperienceSummary.ExperienceItem> experiences = experienceRepository
            .findByTenantIdOrderByUpdateTimeDesc(normalizedTenant, PageRequest.of(0, normalizedLimit))
            .stream()
            .map(this::toItem)
            .toList();
        List<AgentExperienceSummary.IndexItem> indexes = indexRepository
            .findByTenantIdOrderBySuccessRateDescUpdatedAtDesc(normalizedTenant, PageRequest.of(0, normalizedLimit))
            .stream()
            .map(this::toIndexItem)
            .toList();
        return new AgentExperienceSummary(
            normalizedTenant,
            experienceRepository.countByTenantId(normalizedTenant),
            indexRepository.countByTenantId(normalizedTenant),
            indexes,
            scenarios,
            experiences
        );
    }

    public String buildRuntimeExperienceContext(String tenantId, String agentId, String query, List<String> availableTools) {
        if (!properties.isEnabled() || tenantId == null || tenantId.isBlank()) {
            return "";
        }
        String scenario = scenarioKey(query);
        String intentType = intentType(query, null);
        List<AgentExperienceIndexEntity> candidates = indexRepository.findRuntimeCandidates(
            tenantId.trim(),
            normalizeNullable(agentId),
            scenario,
            intentType,
            PageRequest.of(0, 6)
        );
        if (candidates.isEmpty()) {
            candidates = indexRepository.findRuntimeCandidates(
                tenantId.trim(),
                normalizeNullable(agentId),
                null,
                intentType,
                PageRequest.of(0, 6)
            );
        }
        List<AgentExperienceIndexEntity> matched = candidates.stream()
            .filter(index -> matchesRuntimeFilters(index, query, availableTools))
            .limit(3)
            .toList();
        if (matched.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder("Experience hints from structured runtime index:\n");
        for (AgentExperienceIndexEntity index : matched) {
            builder.append("- Scenario: ").append(index.getScenario())
                .append(", intent: ").append(firstText(index.getIntentType(), "general"))
                .append(", successRate: ").append(round(index.getSuccessRate())).append("%")
                .append(", samples: ").append(index.getSampleCount()).append("\n");
            if (index.getBestPractice() != null && !index.getBestPractice().isBlank()) {
                builder.append("  Best practice: ").append(index.getBestPractice()).append("\n");
            }
            if (index.getAvoidPattern() != null && !index.getAvoidPattern().isBlank()) {
                builder.append("  Avoid: ").append(index.getAvoidPattern()).append("\n");
            }
        }
        builder.append("Use these hints as prior experience, but verify against the current user question and runtime evidence.");
        return builder.toString();
    }

    private Attribution analyze(AgentTaskLatestEntity task, AgentTaskFeedbackRequest feedback) {
        Attribution fallback = ruleAttribution(task);
        if (!properties.isModelAttributionEnabled()) {
            return fallback;
        }
        ChatModel chatModel = chatModelProvider.getIfAvailable();
        if (chatModel == null) {
            return fallback;
        }
        String prompt = buildAttributionPrompt(task, feedback);
        try {
            String raw = chatModel.chat(prompt);
            Attribution parsed = parseAttribution(raw, fallback);
            return parsed.withSource("model", raw);
        } catch (Exception ex) {
            log.warn("Model attribution failed for taskId={}, fallback to rule attribution: {}",
                task.getTaskId(), ex.getMessage());
            return fallback;
        }
    }

    @Transactional
    protected void rebuildExperienceIndex(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return;
        }
        String normalizedTenant = tenantId.trim();
        indexRepository.deleteByTenantId(normalizedTenant);
        Map<String, IndexAccumulator> indexes = new LinkedHashMap<>();
        for (AgentExperienceEntity experience : experienceRepository.findByTenantId(normalizedTenant)) {
            RuntimeSample sample = runtimeSample(experience);
            String key = indexKey(
                experience.getAgentId(),
                experience.getScenarioKey(),
                sample.intentType(),
                sample.workflowName(),
                sample.toolChain(),
                sample.keywords(),
                sample.toolName(),
                sample.errorCode(),
                sample.dataSource()
            );
            indexes.computeIfAbsent(key, ignored -> new IndexAccumulator(key, experience, sample))
                .add(experience);
        }
        indexes.values().stream()
            .map(IndexAccumulator::toEntity)
            .forEach(indexRepository::save);
    }

    private RuntimeSample runtimeSample(AgentExperienceEntity experience) {
        List<AgentEvent> events = eventStore.listByTask(
            experience.getTenantId(),
            experience.getSessionId(),
            experience.getTaskId(),
            200
        );
        List<String> tools = events.stream()
            .filter(event -> event.getToolName() != null && !event.getToolName().isBlank())
            .map(event -> event.getToolName().trim())
            .distinct()
            .toList();
        String errorCode = events.stream()
            .map(AgentEvent::getErrorCode)
            .filter(value -> value != null && !value.isBlank())
            .findFirst()
            .orElse(null);
        String dataSource = events.stream()
            .map(this::eventDataSource)
            .filter(value -> value != null && !value.isBlank())
            .findFirst()
            .orElse(null);
        String toolChain = String.join(">", tools);
        String toolName = tools.isEmpty() ? null : tools.get(tools.size() - 1);
        return new RuntimeSample(
            intentType(experience.getQuestion(), experience.getFeedbackReasonCategory()),
            firstText(experience.getAgentId(), "default-workflow"),
            toolChain,
            keywords(experience.getQuestion()),
            toolName,
            errorCode,
            dataSource
        );
    }

    private String eventDataSource(AgentEvent event) {
        if (event == null || event.getPayload() == null || event.getPayload().isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(event.getPayload());
            JsonNode runtime = root.path("runtime");
            String serviceId = runtime.path("serviceId").asText("");
            if (!serviceId.isBlank()) {
                return serviceId;
            }
            String serviceName = runtime.path("serviceName").asText("");
            return serviceName.isBlank() ? null : serviceName;
        } catch (JsonProcessingException ex) {
            return null;
        }
    }

    private AgentExperienceSummary.IndexItem toIndexItem(AgentExperienceIndexEntity entity) {
        return new AgentExperienceSummary.IndexItem(
            entity.getId(),
            entity.getAgentId(),
            entity.getScenario(),
            entity.getIntentType(),
            entity.getWorkflowName(),
            entity.getToolChain(),
            entity.getKeywords(),
            entity.getToolName(),
            entity.getErrorCode(),
            entity.getDataSource(),
            entity.getFeedbackResult(),
            entity.getUsefulCount(),
            entity.getAdoptedCount(),
            entity.getResolvedCount(),
            entity.getFailedCount(),
            entity.getSampleCount(),
            round(entity.getSuccessRate()),
            entity.getBestPractice(),
            entity.getAvoidPattern(),
            entity.getUpdatedAt()
        );
    }

    private boolean matchesRuntimeFilters(AgentExperienceIndexEntity index, String query, List<String> availableTools) {
        if (index == null) {
            return false;
        }
        String queryText = query == null ? "" : query.toLowerCase(Locale.ROOT);
        if (index.getKeywords() != null && !index.getKeywords().isBlank()) {
            boolean keywordMatched = List.of(index.getKeywords().split(",")).stream()
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .anyMatch(value -> queryText.contains(value.toLowerCase(Locale.ROOT)));
            if (keywordMatched) {
                return true;
            }
        }
        if (availableTools != null && index.getToolName() != null && availableTools.contains(index.getToolName())) {
            return true;
        }
        return index.getSampleCount() >= 2 || index.getSuccessRate() >= 80D;
    }

    private Attribution parseAttribution(String raw, Attribution fallback) throws JsonProcessingException {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        JsonNode root = objectMapper.readTree(extractJson(raw));
        return new Attribution(
            "model",
            firstText(text(root, "scenarioKey"), fallback.scenarioKey()),
            firstText(text(root, "scenarioName"), fallback.scenarioName()),
            firstText(text(root, "summary"), fallback.summary()),
            stringList(root, "primaryFactors", fallback.primaryFactors()),
            stringList(root, "successPattern", fallback.successPattern()),
            stringList(root, "improvementSuggestions", fallback.improvementSuggestions()),
            raw
        );
    }

    private Attribution ruleAttribution(AgentTaskLatestEntity task) {
        List<String> factors = new ArrayList<>();
        List<String> successPattern = new ArrayList<>();
        List<String> improvements = new ArrayList<>();
        if (Boolean.TRUE.equals(task.getFeedbackUseful())) {
            factors.add("答案对用户有帮助");
        } else if (Boolean.FALSE.equals(task.getFeedbackUseful())) {
            factors.add("答案未提供有效帮助");
            improvements.add("重新校验问题意图和知识来源");
        }
        if (Boolean.TRUE.equals(task.getFeedbackAdopted())) {
            factors.add("方案已被用户采纳");
            successPattern.add("保留当前解法结构");
        } else if (Boolean.FALSE.equals(task.getFeedbackAdopted())) {
            improvements.add("补充可执行步骤和环境约束");
        }
        if (Boolean.TRUE.equals(task.getFeedbackResolved())) {
            factors.add("任务已解决");
            successPattern.add("优先复用该场景的成功步骤");
        } else if (Boolean.FALSE.equals(task.getFeedbackResolved())) {
            improvements.add("追加排查步骤和验证条件");
        }
        String reason = feedbackReasonLabel(task.getFeedbackReasonCategory());
        if (reason != null) {
            factors.add(reason);
        }
        if (factors.isEmpty()) {
            factors.add("用户给出了反馈但缺少明确原因");
        }
        String summary = String.join("；", factors);
        return new Attribution(
            "rule",
            scenarioKey(task.getQuestion()),
            scenarioName(task.getQuestion()),
            summary,
            factors,
            successPattern.isEmpty() ? List.of("沉淀该问答的上下文和反馈") : successPattern,
            improvements.isEmpty() ? List.of("保持当前回答策略并观察更多样本") : improvements,
            null
        );
    }

    private String buildAttributionPrompt(AgentTaskLatestEntity task, AgentTaskFeedbackRequest feedback) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", task.getTaskId());
        payload.put("agentId", task.getAgentId());
        payload.put("question", task.getQuestion());
        payload.put("answer", task.getAnswerSummary());
        payload.put("useful", task.getFeedbackUseful());
        payload.put("adopted", task.getFeedbackAdopted());
        payload.put("resolved", task.getFeedbackResolved());
        payload.put("reasonCategory", task.getFeedbackReasonCategory());
        payload.put("comment", task.getFeedbackComment());
        return """
            You are analyzing enterprise Agent feedback. Return only one JSON object.
            The JSON schema is:
            {
              "scenarioKey":"stable_snake_case_scenario",
              "scenarioName":"short human readable scenario",
              "summary":"why this feedback happened in one sentence",
              "primaryFactors":["factor"],
              "successPattern":["reusable successful step or pattern"],
              "improvementSuggestions":["specific runtime or answer improvement"]
            }
            Prefer concise Chinese labels for values except scenarioKey.
            Feedback payload:
            """ + writeObject(payload);
    }

    private AgentExperienceSummary.ExperienceItem toItem(AgentExperienceEntity entity) {
        return new AgentExperienceSummary.ExperienceItem(
            entity.getExperienceId(),
            entity.getTaskId(),
            entity.getAgentId(),
            entity.getScenarioKey(),
            entity.getScenarioName(),
            entity.getQuestion(),
            entity.getAnswerSummary(),
            entity.getFeedbackUseful(),
            entity.getFeedbackAdopted(),
            entity.getFeedbackResolved(),
            entity.getFeedbackReasonCategory(),
            entity.getFeedbackComment(),
            entity.getFeedbackScore(),
            entity.getAttributionSource(),
            entity.getAttributionSummary(),
            readList(entity.getPrimaryFactorsJson()),
            readList(entity.getSuccessPatternJson()),
            readList(entity.getImprovementSuggestionsJson()),
            entity.getUpdateTime()
        );
    }

    private int feedbackScore(AgentTaskLatestEntity task) {
        int score = 10;
        score += signalScore(task.getFeedbackUseful(), 30);
        score += signalScore(task.getFeedbackAdopted(), 30);
        score += signalScore(task.getFeedbackResolved(), 30);
        if (task.getFeedbackReasonCategory() != null && !task.getFeedbackReasonCategory().isBlank()) {
            score += 5;
        }
        if (task.getFeedbackComment() != null && !task.getFeedbackComment().isBlank()) {
            score += 5;
        }
        return Math.max(0, Math.min(100, score));
    }

    private int signalScore(Boolean value, int weight) {
        if (Boolean.TRUE.equals(value)) {
            return weight;
        }
        if (Boolean.FALSE.equals(value)) {
            return -weight / 2;
        }
        return 0;
    }

    private String scenarioKey(String question) {
        String normalized = normalizeAscii(question);
        if (!normalized.isBlank()) {
            return normalized.length() <= 80 ? normalized : normalized.substring(0, 80);
        }
        String text = question == null ? "general_feedback" : question.trim();
        return text.isBlank() ? "general_feedback" : "scenario_" + Integer.toHexString(text.hashCode());
    }

    private String scenarioName(String question) {
        if (question == null || question.isBlank()) {
            return "通用反馈";
        }
        String normalized = question.trim().replaceAll("\\s+", " ");
        return normalized.length() <= 40 ? normalized : normalized.substring(0, 40);
    }

    private String intentType(String question, String reasonCategory) {
        String text = ((question == null ? "" : question) + " " + (reasonCategory == null ? "" : reasonCategory))
            .toLowerCase(Locale.ROOT);
        if (text.contains("install") || text.contains("安装") || text.contains("部署")) {
            return "install";
        }
        if (text.contains("error") || text.contains("exception") || text.contains("报错") || text.contains("失败")) {
            return "troubleshooting";
        }
        if (text.contains("sql") || text.contains("查询") || text.contains("数据")) {
            return "data_query";
        }
        if (text.contains("server") || text.contains("服务器") || text.contains("运维")) {
            return "ops_advice";
        }
        if (text.contains("tool_call_error")) {
            return "tool_failure";
        }
        if (text.contains("knowledge_outdated")) {
            return "knowledge_update";
        }
        return "general";
    }

    private String keywords(String question) {
        if (question == null || question.isBlank()) {
            return "";
        }
        String ascii = normalizeAscii(question);
        if (!ascii.isBlank()) {
            return List.of(ascii.split("_")).stream()
                .filter(value -> value.length() >= 3)
                .distinct()
                .limit(8)
                .reduce((left, right) -> left + "," + right)
                .orElse("");
        }
        String normalized = question.trim().replaceAll("\\s+", "");
        List<String> values = new ArrayList<>();
        for (int i = 0; i < normalized.length(); i += 4) {
            values.add(normalized.substring(i, Math.min(normalized.length(), i + 4)));
            if (values.size() >= 8) {
                break;
            }
        }
        return String.join(",", values);
    }

    private String indexKey(String agentId,
                            String scenario,
                            String intentType,
                            String workflowName,
                            String toolChain,
                            String keywords,
                            String toolName,
                            String errorCode,
                            String dataSource) {
        String seed = String.join("|",
            firstText(agentId, "default-agent"),
            firstText(scenario, "general"),
            firstText(intentType, "general"),
            firstText(workflowName, "default-workflow"),
            firstText(toolChain, ""),
            firstText(keywords, ""),
            firstText(toolName, ""),
            firstText(errorCode, ""),
            firstText(dataSource, "")
        );
        return Integer.toHexString(seed.hashCode());
    }

    private String feedbackResult(AgentExperienceEntity experience) {
        if (Boolean.TRUE.equals(experience.getFeedbackUseful())
            && Boolean.TRUE.equals(experience.getFeedbackAdopted())
            && Boolean.TRUE.equals(experience.getFeedbackResolved())) {
            return "success";
        }
        if (Boolean.FALSE.equals(experience.getFeedbackUseful())
            && Boolean.FALSE.equals(experience.getFeedbackAdopted())
            && Boolean.FALSE.equals(experience.getFeedbackResolved())) {
            return "failed";
        }
        return "partial";
    }

    private double successRate(long usefulCount, long adoptedCount, long resolvedCount, long sampleCount) {
        if (sampleCount <= 0) {
            return 0D;
        }
        return ((double) usefulCount + (double) adoptedCount + (double) resolvedCount) * 100D / ((double) sampleCount * 3D);
    }

    private String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String normalizeAscii(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKD)
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "_")
            .replaceAll("_+", "_")
            .replaceAll("^_|_$", "");
        return normalized.isBlank() ? "" : normalized;
    }

    private List<String> stringList(JsonNode root, String field, List<String> fallback) {
        JsonNode node = root.path(field);
        if (!node.isArray()) {
            return fallback;
        }
        List<String> values = new ArrayList<>();
        node.forEach(item -> {
            String value = item.asText("");
            if (!value.isBlank()) {
                values.add(value.trim());
            }
        });
        return values.isEmpty() ? fallback : values;
    }

    private String text(JsonNode root, String field) {
        JsonNode node = root.path(field);
        return node.isMissingNode() || node.isNull() ? null : node.asText(null);
    }

    private String extractJson(String raw) {
        String trimmed = raw.trim();
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1);
        }
        return trimmed;
    }

    private String writeList(List<String> values) {
        return writeObject(values == null ? List.of() : values);
    }

    private String writeObject(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize experience payload", ex);
        }
    }

    private List<String> readList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            if (!node.isArray()) {
                return List.of();
            }
            List<String> values = new ArrayList<>();
            node.forEach(item -> {
                String value = item.asText("");
                if (!value.isBlank()) {
                    values.add(value);
                }
            });
            return values;
        } catch (JsonProcessingException ex) {
            return List.of();
        }
    }

    private String feedbackReasonLabel(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return switch (value) {
            case "answer_correct" -> "答案正确";
            case "steps_clear" -> "步骤清晰";
            case "tool_result_accurate" -> "工具结果准确";
            case "environment_mismatch" -> "环境不匹配";
            case "answer_incomplete" -> "回答不完整";
            case "tool_call_error" -> "工具调用错误";
            case "knowledge_outdated" -> "知识库内容过期";
            default -> "其他";
        };
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }

    private double round(double value) {
        return Math.round(value * 100D) / 100D;
    }

    private String requireTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("Tenant ID cannot be empty");
        }
        return tenantId.trim();
    }

    private String firstText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private record RuntimeSample(
        String intentType,
        String workflowName,
        String toolChain,
        String keywords,
        String toolName,
        String errorCode,
        String dataSource
    ) {
    }

    private final class IndexAccumulator {
        private final String indexKey;
        private final AgentExperienceEntity seed;
        private final RuntimeSample sample;
        private long usefulCount;
        private long adoptedCount;
        private long resolvedCount;
        private long failedCount;
        private long sampleCount;
        private String bestPractice;
        private String avoidPattern;
        private String feedbackResult = "partial";
        private String lastExperienceId;

        private IndexAccumulator(String indexKey, AgentExperienceEntity seed, RuntimeSample sample) {
            this.indexKey = indexKey;
            this.seed = seed;
            this.sample = sample;
        }

        private void add(AgentExperienceEntity experience) {
            sampleCount += 1;
            if (Boolean.TRUE.equals(experience.getFeedbackUseful())) {
                usefulCount += 1;
            }
            if (Boolean.TRUE.equals(experience.getFeedbackAdopted())) {
                adoptedCount += 1;
            }
            if (Boolean.TRUE.equals(experience.getFeedbackResolved())) {
                resolvedCount += 1;
            }
            if ("failed".equals(feedbackResult(experience))) {
                failedCount += 1;
            }
            feedbackResult = feedbackResult(experience);
            bestPractice = firstText(joinList(readList(experience.getSuccessPatternJson())), bestPractice);
            avoidPattern = firstText(joinList(readList(experience.getImprovementSuggestionsJson())), avoidPattern);
            lastExperienceId = experience.getExperienceId();
        }

        private AgentExperienceIndexEntity toEntity() {
            AgentExperienceIndexEntity entity = new AgentExperienceIndexEntity();
            entity.setTenantId(seed.getTenantId());
            entity.setIndexKey(indexKey);
            entity.setAgentId(seed.getAgentId());
            entity.setScenario(seed.getScenarioKey());
            entity.setIntentType(sample.intentType());
            entity.setWorkflowName(sample.workflowName());
            entity.setToolChain(sample.toolChain());
            entity.setKeywords(sample.keywords());
            entity.setToolName(sample.toolName());
            entity.setErrorCode(sample.errorCode());
            entity.setDataSource(sample.dataSource());
            entity.setFeedbackResult(feedbackResult);
            entity.setUsefulCount(usefulCount);
            entity.setAdoptedCount(adoptedCount);
            entity.setResolvedCount(resolvedCount);
            entity.setFailedCount(failedCount);
            entity.setSampleCount(sampleCount);
            entity.setSuccessRate(successRate(usefulCount, adoptedCount, resolvedCount, sampleCount));
            entity.setBestPractice(bestPractice);
            entity.setAvoidPattern(avoidPattern);
            entity.setLastExperienceId(lastExperienceId);
            return entity;
        }

        private String joinList(List<String> values) {
            if (values == null || values.isEmpty()) {
                return null;
            }
            return String.join("；", values.stream().limit(5).toList());
        }
    }

    private record Attribution(
        String source,
        String scenarioKey,
        String scenarioName,
        String summary,
        List<String> primaryFactors,
        List<String> successPattern,
        List<String> improvementSuggestions,
        String rawOutput
    ) {

        Attribution withSource(String source, String rawOutput) {
            return new Attribution(
                source,
                scenarioKey,
                scenarioName,
                summary,
                primaryFactors,
                successPattern,
                improvementSuggestions,
                rawOutput
            );
        }
    }
}
