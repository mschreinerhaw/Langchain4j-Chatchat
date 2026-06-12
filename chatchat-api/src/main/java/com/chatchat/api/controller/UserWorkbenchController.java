package com.chatchat.api.controller;

import com.chatchat.chat.activity.UserWorkbenchService;
import com.chatchat.common.constants.AppConstants;
import com.chatchat.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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

    @GetMapping
    @Operation(summary = "Load personal workbench shortcut entries")
    public ApiResponse<UserWorkbenchService.WorkbenchPayload> getWorkbench(
        @RequestParam("tenantId") String tenantId,
        @RequestParam("userId") String userId,
        @RequestParam(value = "limit", defaultValue = "5") int limit,
        @RequestParam(value = "category", required = false) String category,
        @RequestParam(value = "targetType", required = false) String targetType,
        @RequestParam(value = "keyword", required = false) String keyword
    ) {
        try {
            return ApiResponse.success(workbenchService.shortcuts(tenantId, userId, limit, category, targetType, keyword));
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @PostMapping("/activities")
    @Operation(summary = "Record one user activity shortcut signal")
    public ApiResponse<UserWorkbenchService.ShortcutItem> recordActivity(
        @RequestBody UserWorkbenchService.ActivityRequest request
    ) {
        try {
            return ApiResponse.success(workbenchService.recordActivity(request));
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @PostMapping("/favorites")
    @Operation(summary = "Add one favorite shortcut")
    public ApiResponse<UserWorkbenchService.ShortcutItem> addFavorite(
        @RequestBody UserWorkbenchService.FavoriteRequest request
    ) {
        try {
            return ApiResponse.success(workbenchService.addFavorite(request));
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @DeleteMapping("/favorites/{favoriteId}")
    @Operation(summary = "Remove one favorite shortcut")
    public ApiResponse<Void> removeFavorite(@PathVariable("favoriteId") String favoriteId) {
        try {
            workbenchService.removeFavorite(favoriteId);
            return ApiResponse.success(null, "Favorite removed");
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }
}
