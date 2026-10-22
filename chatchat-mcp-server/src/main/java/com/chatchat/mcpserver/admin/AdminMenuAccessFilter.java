package com.chatchat.mcpserver.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.Map;

@Component @RequiredArgsConstructor @Order(Ordered.HIGHEST_PRECEDENCE+25)
public class AdminMenuAccessFilter extends OncePerRequestFilter {
    private final AdminMenuAccessService access;
    private final ObjectMapper json;
    @Override protected boolean shouldNotFilter(HttpServletRequest request){
        String path=request.getRequestURI().substring(request.getContextPath().length());
        return "OPTIONS".equalsIgnoreCase(request.getMethod()) || !path.startsWith("/api/v1/")
            || path.equals("/api/v1/admin/auth/login") || path.equals("/api/v1/license/menu-catalog")
            || path.equals("/api/v1/mcp-services/heartbeat");
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws IOException,ServletException {
        String path=request.getRequestURI().substring(request.getContextPath().length());
        if(access.allows(request,path,request.getMethod())){chain.doFilter(request,response);return;}
        boolean enabled=access.enabled(request);
        response.setStatus(enabled?403:401);response.setContentType("application/json");response.setCharacterEncoding("UTF-8");
        json.writeValue(response.getWriter(),Map.of("code",enabled?403:401,"message",enabled?"未获授权访问此管理功能":"账号已停用，请重新登录"));
    }
}
