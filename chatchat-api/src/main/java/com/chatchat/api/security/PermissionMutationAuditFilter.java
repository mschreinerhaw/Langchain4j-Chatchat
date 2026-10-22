package com.chatchat.api.security;
import com.chatchat.enterprise.entity.audit.SysAuditLog;
import com.chatchat.enterprise.repository.audit.SysAuditLogRepository;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

/** Records rejected permission writes without logging request bodies, tokens or credentials. */
@Component @RequiredArgsConstructor @Order(Ordered.HIGHEST_PRECEDENCE+15)
public class PermissionMutationAuditFilter extends OncePerRequestFilter {
    private final SysAuditLogRepository audits;
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(PermissionMutationAuditFilter.class);
    @Override protected boolean shouldNotFilter(HttpServletRequest request){
        if(!java.util.Set.of("POST","PUT","DELETE").contains(request.getMethod()))return true;
        String path=request.getRequestURI().substring(request.getContextPath().length());
        return !(path.startsWith("/api/v1/permission-center/tool-grants") || path.startsWith("/api/v1/enterprise/resource-grants") || path.matches("/api/v1/enterprise/roles/[^/]+/authorization"));
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws IOException,ServletException{
        boolean completed=false;
        try{chain.doFilter(request,response);completed=true;}
        finally{
            Object actor=request.getAttribute(ApiAuthenticationFilter.CURRENT_USER_ID),tenant=request.getAttribute(ApiAuthenticationFilter.CURRENT_TENANT_ID);
            if(actor!=null && tenant!=null && (!completed || response.getStatus()>=400)){
                try{var audit=new SysAuditLog();audit.setActorId(String.valueOf(actor));audit.setTenantId(String.valueOf(tenant));audit.setModuleName("permission-center");audit.setActionName("rejected");audit.setResourceType("PERMISSION_OPERATION");audit.setResult("failure");audit.setDetail("HTTP "+request.getMethod()+" "+request.getRequestURI()+"; status="+(completed?response.getStatus():500));audits.saveAndFlush(audit);}
                catch(RuntimeException failure){log.warn("Permission failure audit unavailable: {}",failure.getClass().getSimpleName());}
            }
        }
    }
}
