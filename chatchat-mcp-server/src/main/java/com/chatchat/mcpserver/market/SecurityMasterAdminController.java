package com.chatchat.mcpserver.market;

import com.chatchat.common.response.ApiResponse;
import com.chatchat.runtime.market.storage.FinancialDataStore;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/market/security-master")
public class SecurityMasterAdminController {
    private final SecurityMasterImportService importService;
    private final FinancialDataStore store;

    @GetMapping
    public ApiResponse<Map<String, Object>> status() {
        return ApiResponse.success(Map.of("count", store.securityMasterCount()));
    }

    @PostMapping("/refresh")
    public ApiResponse<SecurityMasterImportService.ImportResult> refresh() throws Exception {
        return ApiResponse.success(importService.refresh(), "证券主数据已从交易所官网刷新");
    }
}
