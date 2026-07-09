package com.chatchat.mcpserver.admin;

import com.chatchat.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/login-audits")
public class AdminLoginAuditController {

    private final AdminLoginAuditService auditService;

    @GetMapping
    public ApiResponse<AdminLoginAuditService.LoginAuditPage> search(
        @RequestParam(value = "page", required = false) Integer page,
        @RequestParam(value = "pageSize", required = false) Integer pageSize,
        @RequestParam(value = "actionName", required = false) String actionName,
        @RequestParam(value = "result", required = false) String result,
        @RequestParam(value = "keyword", required = false) String keyword
    ) {
        return ApiResponse.success(auditService.search(
            new AdminLoginAuditService.LoginAuditSearchQuery(page, pageSize, actionName, result, keyword)
        ));
    }
}
