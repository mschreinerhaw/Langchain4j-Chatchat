package com.chatchat.api.agent.published;

import com.chatchat.api.security.ApiAuthenticationFilter;
import com.chatchat.common.response.ApiResponse;
import com.chatchat.enterprise.service.AgentApiTokenService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/enterprise/agent-api-tokens")
@Tag(name = "Agent API Token Administration")
public class AgentApiTokenAdminController {

    private final AgentApiTokenService tokenService;

    @GetMapping
    @Operation(summary = "List persisted Agent API tokens")
    public ApiResponse<List<AgentApiTokenService.TokenView>> list(
        HttpServletRequest servletRequest,
        @RequestParam(name = "userId", required = false) String userId
    ) {
        return ApiResponse.success(tokenService.list(currentUserId(servletRequest), userId));
    }

    @PostMapping
    @Operation(summary = "Issue a new Agent API token; the secret is returned once")
    public ApiResponse<AgentApiTokenService.IssuedToken> create(
        HttpServletRequest servletRequest,
        @RequestBody AgentApiTokenService.CreateTokenRequest request
    ) {
        return ApiResponse.success(tokenService.create(currentUserId(servletRequest), request),
            "Agent API token created; copy the secret now");
    }

    @PostMapping("/{tokenId}/revoke")
    @Operation(summary = "Revoke an Agent API token immediately")
    public ApiResponse<AgentApiTokenService.TokenView> revoke(
        HttpServletRequest servletRequest,
        @PathVariable("tokenId") String tokenId
    ) {
        return ApiResponse.success(tokenService.revoke(currentUserId(servletRequest), tokenId),
            "Agent API token revoked");
    }

    @PostMapping("/{tokenId}/reset")
    @Operation(summary = "Rotate an Agent API token; the replacement secret is returned once")
    public ApiResponse<AgentApiTokenService.IssuedToken> reset(
        HttpServletRequest servletRequest,
        @PathVariable("tokenId") String tokenId,
        @RequestBody(required = false) AgentApiTokenService.ResetTokenRequest request
    ) {
        return ApiResponse.success(tokenService.reset(currentUserId(servletRequest), tokenId, request),
            "Agent API token reset; copy the replacement secret now");
    }

    private String currentUserId(HttpServletRequest request) {
        Object value = request.getAttribute(ApiAuthenticationFilter.CURRENT_USER_ID);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new IllegalArgumentException("current user is required");
        }
        return String.valueOf(value);
    }
}
