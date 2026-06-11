package com.chatchat.api.controller;

import com.chatchat.api.sidebar.SidebarCardService;
import com.chatchat.common.constants.AppConstants;
import com.chatchat.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Sidebar card APIs for the enterprise chat right panel.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping(AppConstants.API_V1 + "/data/sidebar")
@Tag(name = "Sidebar", description = "Enterprise chat sidebar card APIs")
public class SidebarController {

    private final SidebarCardService sidebarCardService;

    /**
     * Returns the sidebar.
     *
     * @param skillId the skill id value
     * @param conversationId the conversation id value
     * @return the sidebar
     */
    @GetMapping
    @Operation(summary = "Load right sidebar cards")
    public ApiResponse<SidebarCardService.SidebarPayload> getSidebar(@RequestParam(value = "skillId", required = false) String skillId,
                                                                     @RequestParam(value = "conversationId", required = false) String conversationId) {
        return ApiResponse.success(sidebarCardService.buildSidebar(skillId, conversationId));
    }

    /**
     * Executes the action.
     *
     * @param request the request value
     * @return the operation result
     */
    @PostMapping("/actions/execute")
    @Operation(summary = "Execute one sidebar quick action")
    public ApiResponse<SidebarCardService.SidebarActionResult> executeAction(
        @RequestBody SidebarCardService.SidebarActionRequest request
    ) {
        return ApiResponse.success(sidebarCardService.executeAction(request));
    }

    /**
     * Performs the rotate recommendations operation.
     *
     * @param skillId the skill id value
     * @param cursor the cursor value
     * @return the operation result
     */
    @PostMapping("/recommendations/rotate")
    @Operation(summary = "Rotate recommended services")
    public ApiResponse<List<SidebarCardService.RecommendationItem>> rotateRecommendations(
        @RequestParam(value = "skillId", required = false) String skillId,
        @RequestParam(value = "cursor", defaultValue = "1") int cursor
    ) {
        return ApiResponse.success(sidebarCardService.rotateRecommendations(skillId, cursor));
    }
}
