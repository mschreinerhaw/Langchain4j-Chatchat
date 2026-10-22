package com.chatchat.mcpserver.admin;

import com.chatchat.common.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor @RequestMapping("/api/v1/admin/users")
public class AdminUserManagementController {
    private final AdminUserManagementService users;
    private final AdminMenuAccessService access;
    @GetMapping public ApiResponse<?> list(HttpServletRequest request,
        @RequestParam(value="keyword",defaultValue="") String keyword,@RequestParam(value="status",defaultValue="") String status,
        @RequestParam(value="page",defaultValue="1") int page,@RequestParam(value="pageSize",defaultValue="20") int pageSize){return ApiResponse.success(users.list(request,keyword,status,page,pageSize));}
    @GetMapping("/menu-catalog") public ApiResponse<?> menus(HttpServletRequest request){access.require(request,"adminUsers");return ApiResponse.success(access.menus());}
    @PostMapping public ApiResponse<?> create(HttpServletRequest request,@RequestBody AdminUserManagementService.Update update){return ApiResponse.success(users.create(request,update));}
    @PutMapping("/{username}") public ApiResponse<?> update(HttpServletRequest request,@PathVariable("username") String username,@RequestBody AdminUserManagementService.Update update){return ApiResponse.success(users.update(request,username,update));}
}
