package com.chatchat.api.controller;

import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.api.skills.SkillCatalogService;
import com.chatchat.api.skills.SkillDefinition;
import com.chatchat.api.skills.SkillRoutingSettings;
import com.chatchat.api.skills.SkillToolConfig;
import com.chatchat.common.constants.AppConstants;
import com.chatchat.common.response.ApiResponse;
import com.chatchat.models.config.ModelsConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

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
    private final Map<String, Deque<HistoryItem>> historyStore = new ConcurrentHashMap<>();

    @GetMapping("/skills")
    @Operation(summary = "List skill options")
    public ApiResponse<List<SkillOption>> getSkills() {
        List<SkillOption> skills = skillCatalogService.list().stream()
            .map(this::toSkillOption)
            .toList();
        return ApiResponse.success(skills);
    }

    @PostMapping("/skills")
    @Operation(summary = "Create skill")
    public ApiResponse<SkillOption> createSkill(@RequestBody SkillUpsertRequest request) {
        SkillDefinition saved = skillCatalogService.upsert(toSkillDefinition(request, null));
        return ApiResponse.success(toSkillOption(saved), "Skill created");
    }

    @PutMapping("/skills/{skillId}")
    @Operation(summary = "Update skill")
    public ApiResponse<SkillOption> updateSkill(@PathVariable("skillId") String skillId,
                                                @RequestBody SkillUpsertRequest request) {
        SkillDefinition saved = skillCatalogService.upsert(toSkillDefinition(request, skillId));
        return ApiResponse.success(toSkillOption(saved), "Skill updated");
    }

    @DeleteMapping("/skills/{skillId}")
    @Operation(summary = "Delete skill")
    public ApiResponse<Void> deleteSkill(@PathVariable("skillId") String skillId) {
        boolean deleted = skillCatalogService.delete(skillId);
        return ApiResponse.success(null, deleted ? "Skill deleted" : "Skill not found");
    }

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
                item.systemPrompt(),
                item.firstUseGreeting(),
                item.preferredToolPrefixes(),
                item.boundMcpServiceIds(),
                item.boundMcpToolNames(),
                item.toolConfigs(),
                item.routingSettings(),
                item.quickQuestions(),
                item.createdAt()
            ))
            .toList();
        return ApiResponse.success(versions);
    }

    @PostMapping("/skills/{skillId}/rollback/{versionId}")
    @Operation(summary = "Rollback skill to one version")
    public ApiResponse<SkillOption> rollbackSkill(@PathVariable("skillId") String skillId,
                                                  @PathVariable("versionId") String versionId) {
        SkillDefinition saved = skillCatalogService.rollbackToVersion(skillId, versionId);
        return ApiResponse.success(toSkillOption(saved), "Skill rolled back");
    }

    @GetMapping("/quick-questions")
    @Operation(summary = "List quick questions by skill")
    public ApiResponse<List<String>> getQuickQuestions(@RequestParam(value = "skillId", required = false) String skillId) {
        SkillDefinition skill = skillCatalogService.resolve(skillId);
        return ApiResponse.success(skill.quickQuestions());
    }

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

    @GetMapping("/tools")
    @Operation(summary = "List all available tool names")
    public ApiResponse<List<String>> getTools() {
        List<String> tools = toolRegistry.getAllToolNames().stream().sorted(Comparator.naturalOrder()).toList();
        return ApiResponse.success(tools);
    }

    @GetMapping("/history/{userId}")
    @Operation(summary = "Get user history questions")
    public ApiResponse<List<HistoryItem>> getHistory(@PathVariable("userId") String userId) {
        Deque<HistoryItem> deque = historyStore.getOrDefault(userId, new ConcurrentLinkedDeque<>());
        return ApiResponse.success(new ArrayList<>(deque));
    }

    @PostMapping("/history")
    @Operation(summary = "Save one history question")
    public ApiResponse<List<HistoryItem>> addHistory(@RequestBody HistoryRequest request) {
        if (request == null || request.getQuestion() == null || request.getQuestion().isBlank()) {
            return ApiResponse.badRequest("question is required");
        }
        String userId = request.getUserId() == null || request.getUserId().isBlank() ? "default-user" : request.getUserId();
        Deque<HistoryItem> deque = historyStore.computeIfAbsent(userId, ignored -> new ConcurrentLinkedDeque<>());
        long now = System.currentTimeMillis();
        String historyId = request.getHistoryId() == null || request.getHistoryId().isBlank()
            ? UUID.randomUUID().toString()
            : request.getHistoryId().trim();
        List<ConversationMessage> messages = request.getMessages() == null ? List.of() : request.getMessages();
        deque.removeIf(item -> item.getId() != null && item.getId().equals(historyId));
        deque.addFirst(new HistoryItem(
            historyId,
            request.getQuestion(),
            now,
            request.getConversationId(),
            request.getSkillId(),
            request.getModelName(),
            request.getMode(),
            messages
        ));
        while (deque.size() > 30) {
            deque.removeLast();
        }
        return ApiResponse.success(new ArrayList<>(deque), "History updated");
    }

    @DeleteMapping("/history/{userId}/{historyId}")
    @Operation(summary = "Delete one history conversation by id")
    public ApiResponse<Void> deleteHistoryItem(@PathVariable("userId") String userId,
                                               @PathVariable("historyId") String historyId) {
        Deque<HistoryItem> deque = historyStore.get(userId);
        if (deque == null || deque.isEmpty()) {
            return ApiResponse.success(null, "History not found");
        }
        deque.removeIf(item -> item.getId() != null && item.getId().equals(historyId));
        if (deque.isEmpty()) {
            historyStore.remove(userId);
        }
        return ApiResponse.success(null, "History deleted");
    }

    @DeleteMapping("/history/{userId}")
    @Operation(summary = "Clear history by user")
    public ApiResponse<Void> clearHistory(@PathVariable("userId") String userId) {
        historyStore.remove(userId);
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
        private String systemPrompt;
        private String firstUseGreeting;
        private List<String> preferredToolPrefixes;
        private List<String> boundMcpServiceIds;
        private List<String> boundMcpToolNames;
        private List<SkillToolConfig> toolConfigs;
        private SkillRoutingSettings routingSettings;
        private List<String> quickQuestions;
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
        String systemPrompt,
        String firstUseGreeting,
        List<String> preferredToolPrefixes,
        List<String> boundMcpServiceIds,
        List<String> boundMcpToolNames,
        List<SkillToolConfig> toolConfigs,
        SkillRoutingSettings routingSettings,
        List<String> quickQuestions,
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
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ConversationMessage {
        private String role;
        private String content;
        private Long timestamp;
        private List<Map<String, Object>> traces;
    }

    private SkillOption toSkillOption(SkillDefinition skill) {
        return new SkillOption(
            skill.id(),
            skill.label(),
            skill.description(),
            skill.usageScenarios(),
            skill.skillTags(),
            skill.defaultMode(),
            skill.systemPrompt(),
            skill.firstUseGreeting(),
            skill.preferredToolPrefixes(),
            skill.boundMcpServiceIds(),
            skill.boundMcpToolNames(),
            skill.toolConfigs(),
            skill.routingSettings(),
            skill.quickQuestions(),
            skillCatalogService.isBuiltinSkill(skill.id()),
            skillCatalogService.editableFields(skill.id())
        );
    }

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
            request.getSystemPrompt(),
            request.getFirstUseGreeting(),
            request.getPreferredToolPrefixes(),
            request.getBoundMcpServiceIds(),
            request.getBoundMcpToolNames(),
            request.getToolConfigs(),
            request.getRoutingSettings(),
            request.getQuickQuestions()
        );
    }

    public record SkillOption(
        String value,
        String label,
        String description,
        List<String> usageScenarios,
        List<String> skillTags,
        String defaultMode,
        String systemPrompt,
        String firstUseGreeting,
        List<String> preferredToolPrefixes,
        List<String> boundMcpServiceIds,
        List<String> boundMcpToolNames,
        List<SkillToolConfig> toolConfigs,
        SkillRoutingSettings routingSettings,
        List<String> quickQuestions,
        boolean builtin,
        List<String> editableFields
    ) {
    }
}
