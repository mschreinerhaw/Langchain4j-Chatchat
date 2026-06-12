package com.chatchat.chat.activity;

import com.chatchat.chat.task.AgentTaskLatestEntity;
import com.chatchat.chat.task.AgentTaskLatestRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class UserWorkbenchService {

    private static final String AGENT = "AGENT";
    private static final String DOCUMENT = "DOCUMENT";
    private static final String DEFAULT_FAVORITE_CATEGORY = "默认";
    private static final String USE = "USE";

    private final UserActivityRepository activityRepository;
    private final UserFavoriteRepository favoriteRepository;
    private final AgentTaskLatestRepository taskLatestRepository;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public WorkbenchPayload shortcuts(String tenantId, String userId, int limit) {
        return shortcuts(tenantId, userId, limit, null, null, null);
    }

    @Transactional(readOnly = true)
    public WorkbenchPayload shortcuts(String tenantId, String userId, int limit, String category, String targetType, String keyword) {
        String normalizedTenant = requireText(tenantId, "Tenant ID cannot be empty");
        String normalizedUser = requireText(userId, "User ID cannot be empty");
        int normalizedLimit = Math.max(1, Math.min(limit <= 0 ? 5 : limit, 200));
        return new WorkbenchPayload(
            listFavorites(
                normalizedTenant,
                normalizedUser,
                normalizedLimit,
                normalizeText(category),
                normalizeText(targetType),
                normalizeText(keyword)
            ),
            listRecentAgents(normalizedTenant, normalizedUser, normalizedLimit),
            listRecentDocuments(normalizedTenant, normalizedUser, normalizedLimit)
        );
    }

    @Transactional
    public ShortcutItem recordActivity(ActivityRequest request) {
        ActivityRequest normalized = normalizeActivity(request);
        UserActivityEntity entity = new UserActivityEntity();
        entity.setTenantId(normalized.tenantId());
        entity.setUserId(normalized.userId());
        entity.setTargetType(normalized.targetType());
        entity.setTargetId(normalized.targetId());
        entity.setActionType(normalized.actionType());
        entity.setTitle(truncate(firstText(normalized.title(), normalized.targetId()), 300));
        entity.setSummary(truncate(normalized.summary(), 1000));
        entity.setExtraJson(writeJson(normalized.extra()));
        return toShortcut(activityRepository.save(entity));
    }

    @Transactional
    public ShortcutItem addFavorite(FavoriteRequest request) {
        FavoriteRequest normalized = normalizeFavorite(request);
        UserFavoriteEntity favorite = favoriteRepository
            .findByTenantIdAndUserIdAndTargetTypeAndTargetId(
                normalized.tenantId(),
                normalized.userId(),
                normalized.targetType(),
                normalized.targetId()
            )
            .orElseGet(UserFavoriteEntity::new);
        favorite.setTenantId(normalized.tenantId());
        favorite.setUserId(normalized.userId());
        favorite.setTargetType(normalized.targetType());
        favorite.setTargetId(normalized.targetId());
        favorite.setCategory(truncate(normalized.category(), 80));
        favorite.setTitle(truncate(firstText(normalized.title(), normalized.targetId()), 300));
        UserFavoriteEntity saved = favoriteRepository.save(favorite);
        recordActivity(new ActivityRequest(
            normalized.tenantId(),
            normalized.userId(),
            normalized.targetType(),
            normalized.targetId(),
            "FAVORITE",
            normalized.title(),
            null,
            Map.of("favoriteId", saved.getId(), "category", saved.getCategory())
        ));
        return toShortcut(saved);
    }

    @Transactional
    public void removeFavorite(String favoriteId) {
        favoriteRepository.deleteById(requireText(favoriteId, "Favorite ID cannot be empty"));
    }

    private List<ShortcutItem> listFavorites(
        String tenantId,
        String userId,
        int limit,
        String category,
        String targetType,
        String keyword
    ) {
        String normalizedCategory = normalizeFavoriteCategory(category);
        String normalizedType = targetType == null ? null : normalizeCode(targetType, "Target type cannot be empty");
        int fetchLimit = keyword == null ? limit : Math.min(Math.max(limit * 5, limit), 200);
        List<UserFavoriteEntity> favorites;
        if (normalizedType != null && normalizedCategory != null) {
            favorites = favoriteRepository.findByTenantIdAndUserIdAndTargetTypeAndCategoryOrderByCreatedAtDesc(
                tenantId,
                userId,
                normalizedType,
                normalizedCategory,
                PageRequest.of(0, fetchLimit)
            );
        } else if (normalizedType != null) {
            favorites = favoriteRepository.findByTenantIdAndUserIdAndTargetTypeOrderByCreatedAtDesc(
                tenantId,
                userId,
                normalizedType,
                PageRequest.of(0, fetchLimit)
            );
        } else if (normalizedCategory != null) {
            favorites = favoriteRepository.findByTenantIdAndUserIdAndCategoryOrderByCreatedAtDesc(
                tenantId,
                userId,
                normalizedCategory,
                PageRequest.of(0, fetchLimit)
            );
        } else {
            favorites = favoriteRepository.findByTenantIdAndUserIdOrderByCreatedAtDesc(tenantId, userId, PageRequest.of(0, fetchLimit));
        }
        String normalizedKeyword = keyword == null ? null : keyword.toLowerCase();
        return favorites
            .stream()
            .filter(favorite -> matchesKeyword(favorite, normalizedKeyword))
            .limit(limit)
            .map(this::toShortcut)
            .toList();
    }

    private List<ShortcutItem> listRecentDocuments(String tenantId, String userId, int limit) {
        return dedupe(activityRepository.findByTenantIdAndUserIdAndTargetTypeOrderByCreatedAtDesc(
            tenantId,
            userId,
            DOCUMENT,
            PageRequest.of(0, limit * 4)
        ), limit);
    }

    private List<ShortcutItem> listRecentAgents(String tenantId, String userId, int limit) {
        List<ShortcutItem> items = dedupe(activityRepository.findByTenantIdAndUserIdAndTargetTypeAndActionTypeOrderByCreatedAtDesc(
            tenantId,
            userId,
            AGENT,
            USE,
            PageRequest.of(0, limit * 4)
        ), limit);
        if (items.size() >= limit) {
            return items;
        }
        LinkedHashSet<String> existingIds = new LinkedHashSet<>();
        items.forEach(item -> existingIds.add(item.targetId()));
        List<ShortcutItem> merged = new ArrayList<>(items);
        for (AgentTaskLatestEntity task : taskLatestRepository.findByTenantIdOrderByCreateTimeDesc(
            tenantId,
            PageRequest.of(0, 60)
        )) {
            String agentId = normalizeText(task.getAgentId());
            if (agentId == null || !userId.equals(firstText(task.getUserId(), userId)) || existingIds.contains(agentId)) {
                continue;
            }
            existingIds.add(agentId);
            merged.add(new ShortcutItem(
                null,
                tenantId,
                userId,
                AGENT,
                agentId,
                USE,
                agentId,
                task.getQuestion(),
                Map.of("taskId", task.getTaskId(), "sessionId", task.getSessionId()),
                "Agent",
                task.getUpdateTime() == null ? task.getCreateTime() : task.getUpdateTime()
            ));
            if (merged.size() >= limit) {
                break;
            }
        }
        return merged;
    }

    private List<ShortcutItem> dedupe(List<UserActivityEntity> activities, int limit) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<ShortcutItem> items = new ArrayList<>();
        for (UserActivityEntity activity : activities) {
            String key = activity.getTargetType() + ":" + activity.getTargetId();
            if (!seen.add(key)) {
                continue;
            }
            items.add(toShortcut(activity));
            if (items.size() >= limit) {
                break;
            }
        }
        return items;
    }

    private ActivityRequest normalizeActivity(ActivityRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Activity payload cannot be empty");
        }
        return new ActivityRequest(
            requireText(request.tenantId(), "Tenant ID cannot be empty"),
            requireText(request.userId(), "User ID cannot be empty"),
            normalizeCode(request.targetType(), "Target type cannot be empty"),
            requireText(request.targetId(), "Target ID cannot be empty"),
            normalizeCode(request.actionType(), "Action type cannot be empty"),
            normalizeText(request.title()),
            normalizeText(request.summary()),
            request.extra() == null ? Map.of() : request.extra()
        );
    }

    private FavoriteRequest normalizeFavorite(FavoriteRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Favorite payload cannot be empty");
        }
        return new FavoriteRequest(
            requireText(request.tenantId(), "Tenant ID cannot be empty"),
            requireText(request.userId(), "User ID cannot be empty"),
            normalizeCode(request.targetType(), "Target type cannot be empty"),
            requireText(request.targetId(), "Target ID cannot be empty"),
            requireText(request.title(), "Favorite title cannot be empty"),
            favoriteCategoryForSave(request.category())
        );
    }

    private ShortcutItem toShortcut(UserActivityEntity activity) {
        return new ShortcutItem(
            activity.getId(),
            activity.getTenantId(),
            activity.getUserId(),
            activity.getTargetType(),
            activity.getTargetId(),
            activity.getActionType(),
            activity.getTitle(),
            activity.getSummary(),
            readJsonMap(activity.getExtraJson()),
            shortcutCategory(activity.getTargetType(), readJsonMap(activity.getExtraJson())),
            activity.getCreatedAt()
        );
    }

    private ShortcutItem toShortcut(UserFavoriteEntity favorite) {
        return new ShortcutItem(
            favorite.getId(),
            favorite.getTenantId(),
            favorite.getUserId(),
            favorite.getTargetType(),
            favorite.getTargetId(),
            "FAVORITE",
            favorite.getTitle(),
            null,
            Map.of("category", favorite.getCategory() == null ? DEFAULT_FAVORITE_CATEGORY : favorite.getCategory()),
            favorite.getCategory() == null ? DEFAULT_FAVORITE_CATEGORY : favorite.getCategory(),
            favorite.getCreatedAt()
        );
    }

    private boolean matchesKeyword(UserFavoriteEntity favorite, String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return true;
        }
        return contains(favorite.getTitle(), keyword)
            || contains(favorite.getTargetId(), keyword)
            || contains(favorite.getTargetType(), keyword)
            || contains(favorite.getCategory(), keyword);
    }

    private boolean contains(String value, String keyword) {
        return value != null && value.toLowerCase().contains(keyword);
    }

    private Map<String, Object> readJsonMap(String value) {
        if (value == null || value.isBlank()) {
            return Map.of();
        }
        try {
            Object parsed = objectMapper.readValue(value, Object.class);
            if (parsed instanceof Map<?, ?> map) {
                Map<String, Object> result = new LinkedHashMap<>();
                map.forEach((key, mapValue) -> result.put(String.valueOf(key), mapValue));
                return result;
            }
        } catch (JsonProcessingException ignored) {
            return Map.of();
        }
        return Map.of();
    }

    private String writeJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize activity extra", ex);
        }
    }

    private String normalizeCode(String value, String message) {
        return requireText(value, message).toUpperCase().replace('-', '_');
    }

    private String normalizeFavoriteCategory(String value) {
        String normalized = normalizeText(value);
        if (normalized == null || "all".equalsIgnoreCase(normalized)) {
            return null;
        }
        return normalized;
    }

    private String favoriteCategoryForSave(String value) {
        String normalized = normalizeFavoriteCategory(value);
        return normalized == null ? DEFAULT_FAVORITE_CATEGORY : normalized;
    }

    private String shortcutCategory(String targetType, Map<String, Object> extra) {
        Object category = extra == null ? null : extra.get("category");
        String normalized = normalizeText(category == null ? null : String.valueOf(category));
        if (normalized != null) {
            return normalized;
        }
        return switch ((targetType == null ? "" : targetType).toUpperCase()) {
            case "DOCUMENT" -> "文档";
            case "SESSION" -> "会话";
            case "AGENT" -> "Agent";
            default -> DEFAULT_FAVORITE_CATEGORY;
        };
    }

    private String requireText(String value, String message) {
        String normalized = normalizeText(value);
        if (normalized == null) {
            throw new IllegalArgumentException(message);
        }
        return normalized;
    }

    private String normalizeText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String firstText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, Math.max(0, maxLength));
    }

    public record WorkbenchPayload(
        List<ShortcutItem> favorites,
        List<ShortcutItem> recentAgents,
        List<ShortcutItem> recentDocuments
    ) {
    }

    public record ShortcutItem(
        String id,
        String tenantId,
        String userId,
        String targetType,
        String targetId,
        String actionType,
        String title,
        String summary,
        Map<String, Object> extra,
        String category,
        Instant createdAt
    ) {
    }

    public record ActivityRequest(
        String tenantId,
        String userId,
        String targetType,
        String targetId,
        String actionType,
        String title,
        String summary,
        Map<String, Object> extra
    ) {
    }

    public record FavoriteRequest(
        String tenantId,
        String userId,
        String targetType,
        String targetId,
        String title,
        String category
    ) {
    }
}
