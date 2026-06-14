package com.chatchat.api.controller;

import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.chat.conversation.Conversation;
import com.chatchat.chat.conversation.ConversationService;
import com.chatchat.chat.skills.SkillCatalogService;
import com.chatchat.chat.skills.SkillDefinition;
import com.chatchat.chat.skills.SkillRoutingSettings;
import com.chatchat.chat.skills.SkillToolConfig;
import com.chatchat.common.constants.AppConstants;
import com.chatchat.common.response.ApiResponse;
import com.chatchat.common.config.ModelsConfig;
import com.chatchat.common.tool.ToolMetadata;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Frontend data API without demo seed data.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping(AppConstants.API_V1 + "/data")
@Tag(name = "Data Query", description = "Frontend data endpoints")
public class DataQueryController {

    private final SkillCatalogService skillCatalogService;
    private final ModelsConfig modelsConfig;
    private final ToolRegistry toolRegistry;
    private final ConversationService conversationService;

    /**
     * Returns the skills.
     *
     * @param scope the scope value
     * @param keyword the keyword value
     * @param category the category value
     * @param page the page value
     * @param pageSize the page size value
     * @return the skills
     */
    @GetMapping("/skills")
    @Operation(summary = "List skill options")
    public ApiResponse<SkillPage> getSkills(@RequestParam(value = "scope", required = false) String scope,
                                            @RequestParam(value = "keyword", required = false) String keyword,
                                            @RequestParam(value = "category", required = false) String category,
                                            @RequestParam(value = "page", required = false) Integer page,
                                            @RequestParam(value = "pageSize", required = false) Integer pageSize) {
        int normalizedPage = normalizePage(page);
        int normalizedPageSize = normalizePageSize(pageSize, 6, 100);
        String normalizedKeyword = normalizeKeyword(keyword);
        String normalizedCategory = normalizeKeyword(category);
        List<SkillDefinition> baseSkills = skillCatalogService.list().stream()
            .filter(skill -> "all".equalsIgnoreCase(scope) || "published".equalsIgnoreCase(skill.marketStatus()))
            .filter(skill -> normalizedKeyword.isEmpty() || matchesSkillKeyword(skill, normalizedKeyword))
            .toList();
        List<SkillCategoryOption> categories = buildSkillCategories(baseSkills);
        List<SkillOption> skills = baseSkills.stream()
            .filter(skill -> normalizedCategory.isEmpty() || matchesSkillCategory(skill, normalizedCategory))
            .map(this::toSkillOption)
            .toList();
        List<SkillOption> pagedSkills = skills.stream()
            .skip(pageOffset(normalizedPage, normalizedPageSize))
            .limit(normalizedPageSize)
            .toList();
        return ApiResponse.success(new SkillPage(
            pagedSkills,
            skills.size(),
            normalizedPage,
            normalizedPageSize,
            totalPages(skills.size(), normalizedPageSize),
            categories
        ));
    }

    /**
     * Creates the skill.
     *
     * @param request the request value
     * @return the created skill
     */
    @PostMapping("/skills")
    @Operation(summary = "Create skill")
    public ApiResponse<SkillOption> createSkill(@RequestBody SkillUpsertRequest request) {
        SkillDefinition saved = skillCatalogService.upsert(toSkillDefinition(request, null));
        return ApiResponse.success(toSkillOption(saved), "Skill created");
    }

    /**
     * Updates the skill.
     *
     * @param skillId the skill id value
     * @param request the request value
     * @return the updated skill
     */
    @PutMapping("/skills/{skillId}")
    @Operation(summary = "Update skill")
    public ApiResponse<SkillOption> updateSkill(@PathVariable("skillId") String skillId,
                                                @RequestBody SkillUpsertRequest request) {
        SkillDefinition saved = skillCatalogService.upsert(toSkillDefinition(request, skillId));
        return ApiResponse.success(toSkillOption(saved), "Skill updated");
    }

    /**
     * Sets the default Agent skill.
     *
     * @param skillId the skill id value
     * @return the updated skill
     */
    @PostMapping("/skills/{skillId}/default-agent")
    @Operation(summary = "Set skill as default Agent capability")
    public ApiResponse<SkillOption> setDefaultAgentSkill(@PathVariable("skillId") String skillId) {
        SkillDefinition saved = skillCatalogService.setDefaultAgent(skillId);
        return ApiResponse.success(toSkillOption(saved), "Default Agent skill updated");
    }

    /**
     * Deletes the skill.
     *
     * @param skillId the skill id value
     * @return the operation result
     */
    @DeleteMapping("/skills/{skillId}")
    @Operation(summary = "Delete skill")
    public ApiResponse<Void> deleteSkill(@PathVariable("skillId") String skillId) {
        boolean deleted = skillCatalogService.delete(skillId);
        return ApiResponse.success(null, deleted ? "Skill deleted" : "Skill not found");
    }

    /**
     * Lists the skill versions.
     *
     * @param skillId the skill id value
     * @return the skill versions list
     */
    @GetMapping("/skills/{skillId}/versions")
    @Operation(summary = "List skill versions")
    public ApiResponse<List<SkillVersionItem>> listSkillVersions(@PathVariable("skillId") String skillId) {
        List<SkillVersionItem> versions = skillCatalogService.listVersions(skillId).stream()
            .map(item -> new SkillVersionItem(
                item.id(),
                item.skillId(),
                item.action(),
                item.label(),
                item.description(),
                item.usageScenarios(),
                item.skillTags(),
                item.defaultMode(),
                item.modelName(),
                item.systemPrompt(),
                item.firstUseGreeting(),
                item.preferredToolPrefixes(),
                item.boundMcpServiceIds(),
                item.boundMcpToolNames(),
                item.boundDocumentIds(),
                item.boundDocumentTags(),
                item.toolConfigs(),
                item.routingSettings(),
                item.workflowConfig(),
                item.quickQuestions(),
                item.marketStatus(),
                Boolean.TRUE.equals(item.defaultAgent()),
                item.createdAt()
            ))
            .toList();
        return ApiResponse.success(versions);
    }

    /**
     * Performs the rollback skill operation.
     *
     * @param skillId the skill id value
     * @param versionId the version id value
     * @return the operation result
     */
    @PostMapping("/skills/{skillId}/rollback/{versionId}")
    @Operation(summary = "Rollback skill to one version")
    public ApiResponse<SkillOption> rollbackSkill(@PathVariable("skillId") String skillId,
                                                  @PathVariable("versionId") String versionId) {
        SkillDefinition saved = skillCatalogService.rollbackToVersion(skillId, versionId);
        return ApiResponse.success(toSkillOption(saved), "Skill rolled back");
    }

    /**
     * Returns the quick questions.
     *
     * @param skillId the skill id value
     * @return the quick questions
     */
    @GetMapping("/quick-questions")
    @Operation(summary = "List quick questions by skill")
    public ApiResponse<List<String>> getQuickQuestions(@RequestParam(value = "skillId", required = false) String skillId) {
        SkillDefinition skill = skillCatalogService.resolve(skillId);
        return ApiResponse.success(skill.quickQuestions());
    }

    /**
     * Returns the models.
     *
     * @return the models
     */
    @GetMapping("/models")
    @Operation(summary = "List selectable chat models")
    public ApiResponse<List<ModelOption>> getModels() {
        List<String> candidates = new ArrayList<>(modelsConfig.getAvailableChatModels());
        if (modelsConfig.getDefaultChatModel() != null && !modelsConfig.getDefaultChatModel().isBlank()) {
            candidates.add(0, modelsConfig.getDefaultChatModel());
        }
        List<ModelOption> models = candidates.stream()
            .filter(name -> name != null && !name.isBlank())
            .distinct()
            .map(name -> new ModelOption(name, name))
            .toList();
        return ApiResponse.success(models);
    }

    /**
     * Returns the tools.
     *
     * @return the tools
     */
    @GetMapping("/tools")
    @Operation(summary = "List all available tool names")
    public ApiResponse<List<String>> getTools() {
        List<String> tools = toolRegistry.getAllToolNames().stream()
            .filter(this::isUserVisibleAgentTool)
            .sorted(Comparator.naturalOrder())
            .toList();
        return ApiResponse.success(tools);
    }

    /**
     * Returns whether is user visible agent tool.
     *
     * @param toolName the tool name value
     * @return whether the condition is satisfied
     */
    private boolean isUserVisibleAgentTool(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return false;
        }
        ToolMetadata metadata = toolRegistry.getToolMetadata(toolName);
        return metadata == null || (metadata.isAgentCompatible() && metadata.isUserVisible());
    }

    /**
     * Returns the history.
     *
     * @param userId the user id value
     * @param keyword the keyword value
     * @param status the status value
     * @param limit the limit value
     * @return the history
     */
    @GetMapping("/history/{userId}")
    @Operation(summary = "Search user history conversations")
    public ApiResponse<List<HistoryItem>> getHistory(@PathVariable("userId") String userId,
                                                     @RequestParam(value = "keyword", required = false) String keyword,
                                                     @RequestParam(value = "status", required = false) String status,
                                                     @RequestParam(value = "limit", required = false) Integer limit) {
        return ApiResponse.success(loadPersistentHistory(userId, keyword, status, limit));
    }

    /**
     * Adds the history.
     *
     * @param request the request value
     * @return the operation result
     */
    @PostMapping("/history")
    @Operation(summary = "Save one history question")
    public ApiResponse<List<HistoryItem>> addHistory(@RequestBody HistoryRequest request) {
        if (request == null || request.getQuestion() == null || request.getQuestion().isBlank()) {
            return ApiResponse.badRequest("question is required");
        }
        String userId = request.getUserId() == null || request.getUserId().isBlank() ? "default-user" : request.getUserId();
        List<ConversationMessage> messages = request.getMessages() == null ? List.of() : request.getMessages();
        String status = resolveHistoryStatus(request.getStatus(), messages);
        String conversationId = firstNonBlank(request.getConversationId(), request.getHistoryId());
        if (conversationId == null) {
            return ApiResponse.badRequest("conversationId is required");
        }
        conversationService.updateConversationSummary(conversationId, userId, request.getQuestion(), status);
        conversationService.replaceMessages(conversationId, userId, toConversationMessages(messages));
        List<HistoryItem> history = loadPersistentHistory(userId, null, null, 30);
        replaceCurrentHistorySnapshot(history, new HistoryItem(
            conversationId,
            request.getQuestion(),
            System.currentTimeMillis(),
            conversationId,
            request.getSkillId(),
            request.getModelName(),
            request.getMode(),
            messages,
            status
        ));
        return ApiResponse.success(history, "History updated");
    }

    /**
     * Updates the history status.
     *
     * @param userId the user id value
     * @param historyId the history id value
     * @param request the request value
     * @return the updated history status
     */
    @PatchMapping("/history/{userId}/{historyId}/status")
    @Operation(summary = "Update one history conversation status")
    public ApiResponse<List<HistoryItem>> updateHistoryStatus(@PathVariable("userId") String userId,
                                                              @PathVariable("historyId") String historyId,
                                                              @RequestBody HistoryStatusRequest request) {
        String status = request == null ? null : request.getStatus();
        if (status == null || status.isBlank()) {
            return ApiResponse.badRequest("status is required");
        }
        String conversationId = firstNonBlank(request == null ? null : request.getConversationId(), historyId);
        Conversation conversation = conversationService.getConversation(conversationId).orElse(null);
        if (conversation == null) {
            return ApiResponse.success(loadPersistentHistory(userId, null, null, 30), "History not found");
        }
        conversationService.updateConversationSummary(conversationId, userId, conversation.getTitle(), status);
        if (request != null && request.getMessages() != null) {
            conversationService.replaceMessages(conversationId, userId, toConversationMessages(request.getMessages()));
        }
        List<HistoryItem> history = loadPersistentHistory(userId, null, null, 30);
        if (request != null && request.getMessages() != null) {
            replaceCurrentHistorySnapshot(history, new HistoryItem(
                conversationId,
                conversation.getTitle(),
                System.currentTimeMillis(),
                conversationId,
                null,
                null,
                null,
                request.getMessages(),
                status
            ));
        }
        return ApiResponse.success(history, "History status updated");
    }

    /**
     * Deletes the history item.
     *
     * @param userId the user id value
     * @param historyId the history id value
     * @return the operation result
     */
    @DeleteMapping("/history/{userId}/{historyId}")
    @Operation(summary = "Delete one history conversation by id")
    public ApiResponse<Void> deleteHistoryItem(@PathVariable("userId") String userId,
                                               @PathVariable("historyId") String historyId) {
        if (conversationService.getConversation(historyId).isEmpty()) {
            return ApiResponse.success(null, "History not found");
        }
        conversationService.deleteConversation(historyId);
        return ApiResponse.success(null, "History deleted");
    }

    /**
     * Performs the clear history operation.
     *
     * @param userId the user id value
     * @return the operation result
     */
    @DeleteMapping("/history/{userId}")
    @Operation(summary = "Clear history by user")
    public ApiResponse<Void> clearHistory(@PathVariable("userId") String userId) {
        conversationService.listUserConversations(userId).stream()
            .map(Conversation::getId)
            .forEach(conversationService::deleteConversation);
        return ApiResponse.success(null, "History cleared");
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SkillUpsertRequest {
        private String value;
        private String label;
        private String description;
        private List<String> usageScenarios;
        private List<String> skillTags;
        private String defaultMode;
        private String modelName;
        private String systemPrompt;
        private String firstUseGreeting;
        private List<String> preferredToolPrefixes;
        private List<String> boundMcpServiceIds;
        private List<String> boundMcpToolNames;
        private List<String> boundDocumentIds;
        private List<String> boundDocumentTags;
        private List<SkillToolConfig> toolConfigs;
        private SkillRoutingSettings routingSettings;
        private Map<String, Object> workflowConfig;
        private List<String> quickQuestions;
        private String marketStatus;
        private Boolean defaultAgent;
    }

    public record ModelOption(String value, String label) {
    }

    public record SkillVersionItem(
        String id,
        String skillId,
        String action,
        String label,
        String description,
        List<String> usageScenarios,
        List<String> skillTags,
        String defaultMode,
        String modelName,
        String systemPrompt,
        String firstUseGreeting,
        List<String> preferredToolPrefixes,
        List<String> boundMcpServiceIds,
        List<String> boundMcpToolNames,
        List<String> boundDocumentIds,
        List<String> boundDocumentTags,
        List<SkillToolConfig> toolConfigs,
        SkillRoutingSettings routingSettings,
        Map<String, Object> workflowConfig,
        List<String> quickQuestions,
        String marketStatus,
        boolean defaultAgent,
        Long createdAt
    ) {
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class HistoryRequest {
        private String historyId;
        private String userId;
        private String question;
        private String conversationId;
        private String skillId;
        private String modelName;
        private String mode;
        private String status;
        private List<ConversationMessage> messages;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class HistoryStatusRequest {
        private String status;
        private String conversationId;
        private List<ConversationMessage> messages;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class HistoryItem {
        private String id;
        private String question;
        private Long timestamp;
        private String conversationId;
        private String skillId;
        private String modelName;
        private String mode;
        private List<ConversationMessage> messages;
        private String status;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ConversationMessage {
        private String id;
        private String role;
        private String content;
        private Long timestamp;
        private List<Map<String, Object>> sources;
        private List<Map<String, Object>> traces;
        private Boolean streaming;
        private String status;
    }

    /**
     * Resolves the history status.
     *
     * @param status the status value
     * @param messages the messages value
     * @return the resolved history status
     */
    private String resolveHistoryStatus(String status, List<ConversationMessage> messages) {
        if (status != null && !status.isBlank()) {
            return status.trim();
        }
        if (messages == null || messages.isEmpty()) {
            return "completed";
        }
        ConversationMessage lastMessage = messages.get(messages.size() - 1);
        return "user".equalsIgnoreCase(lastMessage.getRole()) ? "pending" : "completed";
    }

    /**
     * Loads the persistent history.
     *
     * @param userId the user id value
     * @param keyword the keyword value
     * @param status the status value
     * @param limit the limit value
     * @return the operation result
     */
    private List<HistoryItem> loadPersistentHistory(String userId, String keyword, String status, Integer limit) {
        List<HistoryItem> items = conversationService.listUserConversations(userId).stream()
            .map(conversation -> conversationService.getConversation(conversation.getId()).orElse(conversation))
            .map(this::toHistoryItem)
            .toList();
        return filterHistory(items, keyword, status, limit);
    }

    /**
     * Converts the value to history item.
     *
     * @param conversation the conversation value
     * @return the converted history item
     */
    private HistoryItem toHistoryItem(Conversation conversation) {
        List<ConversationMessage> messages = conversation.getMessages() == null
            ? List.of()
            : conversation.getMessages().stream()
                .map(this::toConversationMessage)
                .toList();
        String question = firstUserMessage(messages);
        if (question == null) {
            question = conversation.getTitle();
        }
        return new HistoryItem(
            conversation.getId(),
            question,
            toEpochMillis(conversation.getUpdatedAt()),
            conversation.getId(),
            null,
            null,
            "llm_chat",
            messages,
            conversation.getStatus() == null || conversation.getStatus().isBlank()
                ? resolveHistoryStatus(null, messages)
                : conversation.getStatus()
        );
    }

    /**
     * Converts the value to conversation message.
     *
     * @param message the message value
     * @return the converted conversation message
     */
    private ConversationMessage toConversationMessage(Conversation.Message message) {
        return new ConversationMessage(
            message.getId(),
            message.getRole(),
            message.getContent(),
            toEpochMillis(message.getTimestamp()),
            safeMaps(message.getSources()),
            safeMaps(message.getTraces()),
            false,
            "completed"
        );
    }

    /**
     * Converts the value to conversation messages.
     *
     * @param messages the messages value
     * @return the converted conversation messages
     */
    private List<Conversation.Message> toConversationMessages(List<ConversationMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        return messages.stream()
            .filter(message -> message != null && message.getRole() != null && !message.getRole().isBlank())
            .map(message -> Conversation.Message.builder()
                .id(message.getId())
                .role(message.getRole())
                .content(message.getContent())
                .timestamp(fromEpochMillis(message.getTimestamp()))
                .sources(safeMaps(message.getSources()))
                .traces(safeMaps(message.getTraces()))
                .build())
            .toList();
    }

    /**
     * Performs the first user message operation.
     *
     * @param messages the messages value
     * @return the operation result
     */
    private String firstUserMessage(List<ConversationMessage> messages) {
        return messages.stream()
            .filter(message -> "user".equalsIgnoreCase(message.getRole()))
            .map(ConversationMessage::getContent)
            .filter(value -> value != null && !value.isBlank())
            .findFirst()
            .orElse(null);
    }

    /**
     * Performs the replace current history snapshot operation.
     *
     * @param history the history value
     * @param current the current value
     */
    private void replaceCurrentHistorySnapshot(List<HistoryItem> history, HistoryItem current) {
        for (int index = 0; index < history.size(); index++) {
            if (current.getId().equals(history.get(index).getId())) {
                history.set(index, current);
                return;
            }
        }
        history.add(0, current);
    }

    /**
     * Converts the value to epoch millis.
     *
     * @param value the value value
     * @return the converted epoch millis
     */
    private Long toEpochMillis(LocalDateTime value) {
        return value == null ? System.currentTimeMillis() : value.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    /**
     * Creates the value from epoch millis.
     *
     * @param value the value value
     * @return the operation result
     */
    private LocalDateTime fromEpochMillis(Long value) {
        if (value == null || value <= 0) {
            return null;
        }
        return LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(value), ZoneId.systemDefault());
    }

    /**
     * Performs the safe maps operation.
     *
     * @param values the values value
     * @return the operation result
     */
    private List<Map<String, Object>> safeMaps(List<Map<String, Object>> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
            .filter(value -> value != null && !value.isEmpty())
            .map(value -> (Map<String, Object>) new java.util.LinkedHashMap<>(value))
            .toList();
    }

    /**
     * Performs the first non blank operation.
     *
     * @param first the first value
     * @param second the second value
     * @return the operation result
     */
    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        if (second != null && !second.isBlank()) {
            return second.trim();
        }
        return null;
    }

    /**
     * Performs the filter history operation.
     *
     * @param items the items value
     * @param keyword the keyword value
     * @param status the status value
     * @param limit the limit value
     * @return the operation result
     */
    private List<HistoryItem> filterHistory(List<HistoryItem> items, String keyword, String status, Integer limit) {
        String normalizedKeyword = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        String normalizedStatus = status == null ? "" : status.trim().toLowerCase(Locale.ROOT);
        int max = limit == null || limit <= 0 ? 30 : Math.min(limit, 100);

        return new ArrayList<>(items.stream()
            .filter(item -> normalizedStatus.isEmpty()
                || normalizedStatus.equalsIgnoreCase(String.valueOf(item.getStatus())))
            .filter(item -> normalizedKeyword.isEmpty() || matchesKeyword(item, normalizedKeyword))
            .limit(max)
            .toList());
    }

    /**
     * Returns whether matches keyword.
     *
     * @param item the item value
     * @param keyword the keyword value
     * @return whether the condition is satisfied
     */
    private boolean matchesKeyword(HistoryItem item, String keyword) {
        return containsIgnoreCase(item.getQuestion(), keyword)
            || containsIgnoreCase(item.getConversationId(), keyword)
            || containsIgnoreCase(item.getMode(), keyword)
            || containsIgnoreCase(item.getStatus(), keyword)
            || (item.getMessages() != null && item.getMessages().stream()
                .anyMatch(message -> containsIgnoreCase(message.getContent(), keyword)));
    }

    /**
     * Returns whether contains ignore case.
     *
     * @param value the value value
     * @param keyword the keyword value
     * @return whether the condition is satisfied
     */
    private boolean containsIgnoreCase(String value, String keyword) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(keyword);
    }

    /**
     * Returns whether matches skill category.
     *
     * @param skill the skill value
     * @param category the category value
     * @return whether the condition is satisfied
     */
    private boolean matchesSkillCategory(SkillDefinition skill, String category) {
        return skill.skillTags() != null
            && skill.skillTags().stream().anyMatch(tag -> containsIgnoreCase(tag, category));
    }

    /**
     * Returns whether matches skill keyword.
     *
     * @param skill the skill value
     * @param keyword the keyword value
     * @return whether the condition is satisfied
     */
    private boolean matchesSkillKeyword(SkillDefinition skill, String keyword) {
        return containsIgnoreCase(skill.id(), keyword)
            || containsIgnoreCase(skill.label(), keyword)
            || containsIgnoreCase(skill.description(), keyword)
            || containsIgnoreCase(skill.defaultMode(), keyword)
            || containsIgnoreCase(skill.modelName(), keyword)
            || listContainsIgnoreCase(skill.usageScenarios(), keyword)
            || listContainsIgnoreCase(skill.skillTags(), keyword)
            || listContainsIgnoreCase(skill.quickQuestions(), keyword)
            || listContainsIgnoreCase(skill.preferredToolPrefixes(), keyword)
            || listContainsIgnoreCase(skill.boundMcpServiceIds(), keyword)
            || listContainsIgnoreCase(skill.boundMcpToolNames(), keyword);
    }

    /**
     * Returns whether list contains ignore case.
     *
     * @param values the values value
     * @param keyword the keyword value
     * @return whether the condition is satisfied
     */
    private boolean listContainsIgnoreCase(List<String> values, String keyword) {
        return values != null && values.stream().anyMatch(value -> containsIgnoreCase(value, keyword));
    }

    /**
     * Builds the skill categories.
     *
     * @param skills the skills value
     * @return the built skill categories
     */
    private List<SkillCategoryOption> buildSkillCategories(List<SkillDefinition> skills) {
        Map<String, Integer> counts = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (SkillDefinition skill : skills) {
            if (skill.skillTags() == null) {
                continue;
            }
            for (String tag : skill.skillTags()) {
                if (tag != null && !tag.isBlank()) {
                    counts.merge(tag.trim(), 1, Integer::sum);
                }
            }
        }
        List<SkillCategoryOption> categories = new ArrayList<>();
        categories.add(new SkillCategoryOption("all", "全部业务", skills.size()));
        counts.forEach((tag, count) -> categories.add(new SkillCategoryOption(tag, tag, count)));
        return categories;
    }

    /**
     * Normalizes the keyword.
     *
     * @param value the value value
     * @return the operation result
     */
    private String normalizeKeyword(String value) {
        return value == null || value.isBlank() || "all".equalsIgnoreCase(value.trim())
            ? ""
            : value.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Normalizes the page.
     *
     * @param page the page value
     * @return the operation result
     */
    private int normalizePage(Integer page) {
        return page == null || page <= 0 ? 1 : page;
    }

    /**
     * Normalizes the page size.
     *
     * @param pageSize the page size value
     * @param defaultSize the default size value
     * @param maxSize the max size value
     * @return the operation result
     */
    private int normalizePageSize(Integer pageSize, int defaultSize, int maxSize) {
        int value = pageSize == null || pageSize <= 0 ? defaultSize : pageSize;
        return Math.min(value, maxSize);
    }

    /**
     * Performs the page offset operation.
     *
     * @param page the page value
     * @param pageSize the page size value
     * @return the operation result
     */
    private long pageOffset(int page, int pageSize) {
        return (long) Math.max(0, page - 1) * Math.max(1, pageSize);
    }

    /**
     * Converts the value to tal pages.
     *
     * @param total the total value
     * @param pageSize the page size value
     * @return the converted tal pages
     */
    private int totalPages(int total, int pageSize) {
        return Math.max(1, (int) Math.ceil((double) Math.max(0, total) / Math.max(1, pageSize)));
    }

    /**
     * Converts the value to skill option.
     *
     * @param skill the skill value
     * @return the converted skill option
     */
    private SkillOption toSkillOption(SkillDefinition skill) {
        return new SkillOption(
            skill.id(),
            skill.label(),
            skill.description(),
            skill.usageScenarios(),
            skill.skillTags(),
            skill.defaultMode(),
            skill.modelName(),
            skill.systemPrompt(),
            skill.firstUseGreeting(),
            skill.preferredToolPrefixes(),
            skill.boundMcpServiceIds(),
            skill.boundMcpToolNames(),
            skill.boundDocumentIds(),
            skill.boundDocumentTags(),
            skill.toolConfigs(),
            skill.routingSettings(),
            skill.workflowConfig(),
            skill.quickQuestions(),
            skill.marketStatus(),
            Boolean.TRUE.equals(skill.defaultAgent()),
            skillCatalogService.isBuiltinSkill(skill.id()),
            skillCatalogService.editableFields(skill.id())
        );
    }

    /**
     * Converts the value to skill definition.
     *
     * @param request the request value
     * @param pathSkillId the path skill id value
     * @return the converted skill definition
     */
    private SkillDefinition toSkillDefinition(SkillUpsertRequest request, String pathSkillId) {
        if (request == null) {
            throw new IllegalArgumentException("skill payload is required");
        }
        String value = pathSkillId == null || pathSkillId.isBlank() ? request.getValue() : pathSkillId;
        return new SkillDefinition(
            value,
            request.getLabel(),
            request.getDescription(),
            request.getUsageScenarios(),
            request.getSkillTags(),
            request.getDefaultMode(),
            request.getModelName(),
            request.getSystemPrompt(),
            request.getFirstUseGreeting(),
            request.getPreferredToolPrefixes(),
            request.getBoundMcpServiceIds(),
            request.getBoundMcpToolNames(),
            request.getBoundDocumentIds(),
            request.getBoundDocumentTags(),
            request.getToolConfigs(),
            request.getRoutingSettings(),
            request.getWorkflowConfig(),
            request.getQuickQuestions(),
            request.getMarketStatus(),
            request.getDefaultAgent()
        );
    }

    public record SkillOption(
        String value,
        String label,
        String description,
        List<String> usageScenarios,
        List<String> skillTags,
        String defaultMode,
        String modelName,
        String systemPrompt,
        String firstUseGreeting,
        List<String> preferredToolPrefixes,
        List<String> boundMcpServiceIds,
        List<String> boundMcpToolNames,
        List<String> boundDocumentIds,
        List<String> boundDocumentTags,
        List<SkillToolConfig> toolConfigs,
        SkillRoutingSettings routingSettings,
        Map<String, Object> workflowConfig,
        List<String> quickQuestions,
        String marketStatus,
        boolean defaultAgent,
        boolean builtin,
        List<String> editableFields
    ) {
    }

    public record SkillPage(
        List<SkillOption> items,
        int total,
        int page,
        int pageSize,
        int totalPages,
        List<SkillCategoryOption> categories
    ) {
    }

    public record SkillCategoryOption(
        String value,
        String label,
        int count
    ) {
    }
}
