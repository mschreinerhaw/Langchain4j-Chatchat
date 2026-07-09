package com.chatchat.mcpserver.admin;

import com.chatchat.common.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/auth")
public class AdminAuthController {

    private final AdminAuthService authService;
    private final AdminLoginAuditService loginAuditService;

    /**
     * Performs the login operation.
     *
     * @param request the request value
     * @return the operation result
     */
    @PostMapping("/login")
    public ApiResponse<AdminAuthService.LoginResult> login(HttpServletRequest servletRequest, @RequestBody LoginRequest request) {
        String username = request == null ? null : request.username();
        try {
            AdminAuthService.LoginResult result = authService.login(username, request == null ? null : request.password());
            loginAuditService.recordSuccess(username, servletRequest);
            return ApiResponse.success(result, "Login success");
        } catch (RuntimeException ex) {
            loginAuditService.recordFailure(username, ex.getMessage(), servletRequest);
            throw ex;
        }
    }

    /**
     * Performs the logout operation.
     *
     * @param request the request value
     * @return the operation result
     */
    @PostMapping("/logout")
    public ApiResponse<Map<String, Object>> logout(HttpServletRequest request) {
        authService.logout(resolveBearerToken(request));
        return ApiResponse.success(Map.of("loggedOut", true), "Logout success");
    }

    /**
     * Performs the me operation.
     *
     * @param request the request value
     * @return the operation result
     */
    @GetMapping("/me")
    public ApiResponse<Map<String, Object>> me(HttpServletRequest request) {
        String username = authService.username(resolveBearerToken(request));
        return ApiResponse.success(Map.of(
            "authenticated", username != null,
            "username", username == null ? "" : username,
            "admin", "admin".equalsIgnoreCase(username)
        ));
    }

    /**
     * Changes the administrator password.
     *
     * @param request the servlet request
     * @param payload the password change payload
     * @return the operation result
     */
    @PostMapping("/password")
    public ApiResponse<Map<String, Object>> changePassword(HttpServletRequest request, @RequestBody ChangePasswordRequest payload) {
        authService.changePassword(resolveBearerToken(request), payload.currentPassword(), payload.newPassword());
        return ApiResponse.success(Map.of("changed", true, "requiresLogin", true), "密码已修改");
    }

    /**
     * Resolves the bearer token.
     *
     * @param request the request value
     * @return the resolved bearer token
     */
    private String resolveBearerToken(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization == null || authorization.isBlank()) {
            return null;
        }
        String prefix = "Bearer ";
        if (!authorization.regionMatches(true, 0, prefix, 0, prefix.length())) {
            return null;
        }
        return authorization.substring(prefix.length()).trim();
    }

    public record LoginRequest(String username, String password) {
    }

    public record ChangePasswordRequest(String currentPassword, String newPassword) {
    }
}
