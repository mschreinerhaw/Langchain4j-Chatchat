package com.chatchat.api.controller;

import com.chatchat.api.security.ApiAuthenticationFilter;
import com.chatchat.chat.activity.UserWorkbenchService;
import com.chatchat.chat.activity.PersonalTodoService;
import com.chatchat.common.constants.AppConstants;
import com.chatchat.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
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

@RestController
@RequiredArgsConstructor
@RequestMapping(AppConstants.API_V1 + "/data/workbench")
@Tag(name = "Workbench", description = "Personal workbench shortcuts")
public class UserWorkbenchController {

    private final UserWorkbenchService workbenchService;
    private final PersonalTodoService personalTodoService;

    @GetMapping("/todos")
    @Operation(summary = "List personal sticky-note todos")
    public ApiResponse<java.util.List<PersonalTodoService.TodoItem>> listTodos(
        @RequestParam("tenantId") String tenantId,
        @RequestParam("userId") String userId,
        @RequestParam(value = "includeCompleted", defaultValue = "false") boolean includeCompleted,
        @RequestParam(value = "limit", defaultValue = "20") int limit,
        HttpServletRequest servletRequest
    ) {
        try {
            return ApiResponse.success(personalTodoService.list(
                resolveTenantId(servletRequest, tenantId),
                resolveUserId(servletRequest, userId),
                includeCompleted,
                limit
            ));
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @PostMapping("/todos")
    @Operation(summary = "Create one personal sticky-note todo")
    public ApiResponse<PersonalTodoService.TodoItem> createTodo(
        @RequestBody PersonalTodoService.TodoCreateRequest request,
        HttpServletRequest servletRequest
    ) {
        try {
            return ApiResponse.success(personalTodoService.create(scopeTodoCreate(request, servletRequest)));
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @PutMapping("/todos/{todoId}")
    @Operation(summary = "Update one personal sticky-note todo")
    public ApiResponse<PersonalTodoService.TodoItem> updateTodo(
        @PathVariable("todoId") String todoId,
        @RequestBody PersonalTodoService.TodoUpdateRequest request,
        HttpServletRequest servletRequest
    ) {
        try {
            return ApiResponse.success(personalTodoService.update(todoId, scopeTodoUpdate(request, servletRequest)));
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @DeleteMapping("/todos/{todoId}")
    @Operation(summary = "Delete one personal sticky-note todo")
    public ApiResponse<Void> deleteTodo(
        @PathVariable("todoId") String todoId,
        @RequestParam("tenantId") String tenantId,
        @RequestParam("userId") String userId,
        HttpServletRequest servletRequest
    ) {
        try {
            personalTodoService.delete(
                todoId,
                resolveTenantId(servletRequest, tenantId),
                resolveUserId(servletRequest, userId)
            );
            return ApiResponse.success(null, "Todo deleted");
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @GetMapping
    @Operation(summary = "Load personal workbench shortcut entries")
    public ApiResponse<UserWorkbenchService.WorkbenchPayload> getWorkbench(
        @RequestParam("tenantId") String tenantId,
        @RequestParam("userId") String userId,
        @RequestParam(value = "limit", defaultValue = "5") int limit,
        @RequestParam(value = "category", required = false) String category,
        @RequestParam(value = "targetType", required = false) String targetType,
        @RequestParam(value = "keyword", required = false) String keyword,
        HttpServletRequest servletRequest
    ) {
        try {
            return ApiResponse.success(workbenchService.shortcuts(
                resolveTenantId(servletRequest, tenantId),
                resolveUserId(servletRequest, userId),
                limit,
                category,
                targetType,
                keyword
            ));
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @PostMapping("/activities")
    @Operation(summary = "Record one user activity shortcut signal")
    public ApiResponse<UserWorkbenchService.ShortcutItem> recordActivity(
        @RequestBody UserWorkbenchService.ActivityRequest request,
        HttpServletRequest servletRequest
    ) {
        try {
            return ApiResponse.success(workbenchService.recordActivity(scopeActivity(request, servletRequest)));
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @PostMapping("/favorites")
    @Operation(summary = "Add one favorite shortcut")
    public ApiResponse<UserWorkbenchService.ShortcutItem> addFavorite(
        @RequestBody UserWorkbenchService.FavoriteRequest request,
        HttpServletRequest servletRequest
    ) {
        try {
            return ApiResponse.success(workbenchService.addFavorite(scopeFavorite(request, servletRequest)));
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @DeleteMapping("/favorites/{favoriteId}")
    @Operation(summary = "Remove one favorite shortcut")
    public ApiResponse<Void> removeFavorite(@PathVariable("favoriteId") String favoriteId,
                                            @RequestParam(value = "tenantId", required = false) String tenantId,
                                            @RequestParam(value = "userId", required = false) String userId,
                                            HttpServletRequest servletRequest) {
        try {
            workbenchService.removeFavorite(
                favoriteId,
                resolveTenantId(servletRequest, tenantId),
                resolveUserId(servletRequest, userId)
            );
            return ApiResponse.success(null, "Favorite removed");
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @PostMapping("/favorite-categories")
    @Operation(summary = "Create one personal favorite category")
    public ApiResponse<UserWorkbenchService.FavoriteCategory> createFavoriteCategory(
        @RequestBody UserWorkbenchService.FavoriteCategoryRequest request,
        HttpServletRequest servletRequest
    ) {
        try {
            return ApiResponse.success(workbenchService.createFavoriteCategory(scopeFavoriteCategory(request, servletRequest)));
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @PutMapping("/favorites/{favoriteId}/category")
    @Operation(summary = "Move one favorite into a category")
    public ApiResponse<UserWorkbenchService.ShortcutItem> updateFavoriteCategory(
        @PathVariable("favoriteId") String favoriteId,
        @RequestBody UserWorkbenchService.FavoriteCategoryUpdateRequest request,
        HttpServletRequest servletRequest
    ) {
        try {
            return ApiResponse.success(workbenchService.updateFavoriteCategory(
                favoriteId,
                scopeFavoriteCategoryUpdate(request, servletRequest)
            ));
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    private PersonalTodoService.TodoCreateRequest scopeTodoCreate(
        PersonalTodoService.TodoCreateRequest request,
        HttpServletRequest servletRequest
    ) {
        if (request == null) {
            return null;
        }
        return new PersonalTodoService.TodoCreateRequest(
            resolveTenantId(servletRequest, request.tenantId()),
            resolveUserId(servletRequest, request.userId()),
            request.title(),
            request.notes(),
            request.dueAt(),
            request.important()
        );
    }

    private PersonalTodoService.TodoUpdateRequest scopeTodoUpdate(
        PersonalTodoService.TodoUpdateRequest request,
        HttpServletRequest servletRequest
    ) {
        if (request == null) {
            return null;
        }
        return new PersonalTodoService.TodoUpdateRequest(
            resolveTenantId(servletRequest, request.tenantId()),
            resolveUserId(servletRequest, request.userId()),
            request.title(),
            request.notes(),
            request.dueAt(),
            request.dueAtChanged(),
            request.completed(),
            request.important()
        );
    }

    private UserWorkbenchService.ActivityRequest scopeActivity(
        UserWorkbenchService.ActivityRequest request,
        HttpServletRequest servletRequest
    ) {
        if (request == null) {
            return null;
        }
        return new UserWorkbenchService.ActivityRequest(
            resolveTenantId(servletRequest, request.tenantId()),
            resolveUserId(servletRequest, request.userId()),
            request.targetType(),
            request.targetId(),
            request.actionType(),
            request.title(),
            request.summary(),
            request.extra()
        );
    }

    private UserWorkbenchService.FavoriteRequest scopeFavorite(
        UserWorkbenchService.FavoriteRequest request,
        HttpServletRequest servletRequest
    ) {
        if (request == null) {
            return null;
        }
        return new UserWorkbenchService.FavoriteRequest(
            resolveTenantId(servletRequest, request.tenantId()),
            resolveUserId(servletRequest, request.userId()),
            request.targetType(),
            request.targetId(),
            request.title(),
            request.category()
        );
    }

    private UserWorkbenchService.FavoriteCategoryRequest scopeFavoriteCategory(
        UserWorkbenchService.FavoriteCategoryRequest request,
        HttpServletRequest servletRequest
    ) {
        if (request == null) {
            return null;
        }
        return new UserWorkbenchService.FavoriteCategoryRequest(
            resolveTenantId(servletRequest, request.tenantId()),
            resolveUserId(servletRequest, request.userId()),
            request.name()
        );
    }

    private UserWorkbenchService.FavoriteCategoryUpdateRequest scopeFavoriteCategoryUpdate(
        UserWorkbenchService.FavoriteCategoryUpdateRequest request,
        HttpServletRequest servletRequest
    ) {
        if (request == null) {
            return null;
        }
        return new UserWorkbenchService.FavoriteCategoryUpdateRequest(
            resolveTenantId(servletRequest, request.tenantId()),
            resolveUserId(servletRequest, request.userId()),
            request.category()
        );
    }

    private String resolveTenantId(HttpServletRequest request, String requestedTenantId) {
        return firstText(requestAttribute(request, ApiAuthenticationFilter.CURRENT_TENANT_ID), requestedTenantId);
    }

    private String resolveUserId(HttpServletRequest request, String requestedUserId) {
        return firstText(
            requestAttribute(request, ApiAuthenticationFilter.CURRENT_USERNAME),
            requestAttribute(request, ApiAuthenticationFilter.CURRENT_USER_ID),
            requestedUserId
        );
    }

    private String requestAttribute(HttpServletRequest request, String name) {
        Object value = request == null ? null : request.getAttribute(name);
        return value == null ? null : String.valueOf(value);
    }

    private String firstText(String... values) {
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    return value.trim();
                }
            }
        }
        return "";
    }
}
