package com.chatchat.api.controller;

import com.chatchat.common.constants.AppConstants;
import com.chatchat.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * Health and status endpoints
 */
@Slf4j
@RestController
@RequestMapping(AppConstants.API_V1)
@Tag(name = "Health", description = "Application health and status")
public class HealthController {

    @GetMapping("/health")
    @Operation(summary = "Get application health status")
    public ApiResponse<HealthStatus> health() {
        return ApiResponse.success(new HealthStatus("UP", "Application is running normally"));
    }

    @GetMapping("/status")
    @Operation(summary = "Get detailed application status")
    public ApiResponse<Map<String, Object>> status() {
        Map<String, Object> status = new HashMap<>();
        status.put("application", "ChatChat");
        status.put("version", "1.0.0");
        status.put("status", "UP");
        status.put("timestamp", System.currentTimeMillis());

        return ApiResponse.success(status);
    }

    /**
     * Health status record
     */
    public record HealthStatus(
        String status,
        String message
    ) {}
}
