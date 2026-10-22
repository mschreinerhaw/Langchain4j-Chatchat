package com.chatchat.mcpserver.admin;

import com.chatchat.common.security.InternalCredentialProperties;
import com.chatchat.mcpserver.license.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AdminUserManagementTest {
    private final Map<String,AdminUser> rows=new HashMap<>();
    private final List<AdminMenuGrant> grantRows=new ArrayList<>();
    private final AdminUserRepository users=mock(AdminUserRepository.class);
    private final AdminMenuGrantRepository grants=mock(AdminMenuGrantRepository.class);
    private final AdminLoginAuditLogRepository logs=mock(AdminLoginAuditLogRepository.class);
    private final ObjectMapper json=new ObjectMapper();
    private final InternalCredentialProperties internal=new InternalCredentialProperties();
    private AdminAuthService auth;
    private AdminMenuAccessService access;
    private AdminUserManagementService service;
    private MockHttpServletRequest root;
    @BeforeEach void setup(){
        when(users.findByUsernameIgnoreCase(anyString())).thenAnswer(i->Optional.ofNullable(rows.get(i.getArgument(0,String.class).toLowerCase(Locale.ROOT))));
        when(users.lockByUsername(anyString())).thenAnswer(i->Optional.ofNullable(rows.get(i.getArgument(0,String.class).toLowerCase(Locale.ROOT))));
        when(users.findAll()).thenAnswer(i->new ArrayList<>(rows.values()));
        when(users.saveAndFlush(any())).thenAnswer(i->{AdminUser user=i.getArgument(0);if(user.getCreatedAt()==null)user.onCreate();rows.put(user.getUsername(),user);return user;});
        when(grants.findByUsername(any())).thenAnswer(i->grantRows.stream().filter(g->Objects.equals(g.getUsername(),i.getArgument(0))).toList());
        doAnswer(i->{grantRows.removeIf(g->Objects.equals(g.getUsername(),i.getArgument(0)));return null;}).when(grants).deleteByUsername(anyString());
        when(grants.save(any())).thenAnswer(i->{AdminMenuGrant grant=i.getArgument(0);grantRows.add(grant);return grant;});
        var passwords=new AdminPasswordStore(users);passwords.initializeDefaultUser();
        auth=new AdminAuthService(new AdminAuthProperties(),passwords,internal);
        access=new AdminMenuAccessService(auth,users,grants,new McpAdminMenuCatalog(json));
        service=new AdminUserManagementService(users,grants,passwords,auth,access,internal,new AdminLoginAuditService(logs,json));
        root=request(auth.login("admin","admin123").token(),"/api/v1/admin/users");
    }
    private MockHttpServletRequest request(String token,String path){var request=new MockHttpServletRequest("GET",path);request.addHeader("Authorization","Bearer "+token);return request;}
    private AdminUserManagementService.Update update(String name,String password,boolean enabled,String... keys){return new AdminUserManagementService.Update(name,password,enabled,Set.of(keys));}
    @Test void createsHashedAccountWithoutImplicitManagementRights(){
        var user=service.create(root,update("analyst","testPassword123",true));
        assertThat(user.menuKeys()).isEmpty();assertThat(rows.get("analyst").getPasswordHash()).doesNotContain("testPassword123");
        var request=request(auth.login("ANALYST","testPassword123").token(),"/api/v1/admin/users");
        assertThat(access.allows(request,"/api/v1/admin/auth/me","GET")).isTrue();
        assertThat(access.allows(request,"/api/v1/admin/users","POST")).isFalse();
        assertThatThrownBy(()->service.create(request,update("attacker","anotherPassword",true,"adminUsers"))).isInstanceOf(SecurityException.class);
        assertThat(service.list(root,"anal", "enabled",1,20).total()).isEqualTo(1);
    }
    @Test void grantAndRevocationAffectExistingSessionsAndBlockDirectApiCalls() throws Exception {
        service.create(root,update("analyst","testPassword123",true,"mcpServices"));
        var request=request(auth.login("analyst","testPassword123").token(),"/api/v1/mcp-services");
        assertThat(access.allows(request,"/api/v1/mcp-services","POST")).isTrue();
        assertThat(access.allows(request,"/api/v1/news","GET")).isFalse();
        service.update(root,"analyst",update(null,"",true,"adminLoginAudits"));
        assertThat(access.allows(request,"/api/v1/mcp-services","GET")).isFalse();
        assertThat(access.allows(request,"/api/v1/admin/login-audits","GET")).isTrue();
        var response=new MockHttpServletResponse();var calls=new AtomicInteger();
        new AdminMenuAccessFilter(access,json).doFilter(request,response,(a,b)->calls.incrementAndGet());
        assertThat(response.getStatus()).isEqualTo(403);assertThat(calls).hasValue(0);
    }
    @Test void disableAndResetInvalidateOnlyTargetSessions(){
        service.create(root,update("analyst","testPassword123",true,"auditLogs"));
        String first=auth.login("analyst","testPassword123").token();
        service.update(root,"analyst",update(null,"changedPassword123",true,"auditLogs"));
        assertThat(auth.isValid(first)).isFalse();assertThat(auth.isValid(AdminMenuAccessService.token(root))).isTrue();
        assertThatThrownBy(()->auth.login("analyst","testPassword123")).isInstanceOf(IllegalArgumentException.class);
        String second=auth.login("analyst","changedPassword123").token();
        service.update(root,"analyst",update(null,"",false,"auditLogs"));
        assertThat(auth.isValid(second)).isFalse();
        assertThatThrownBy(()->auth.login("analyst","changedPassword123")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rejectsInvalidGrantsDuplicateNamesWeakPasswordsAndBuiltinChanges(){
        assertThatThrownBy(()->service.create(root,update("analyst","short",true))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->service.create(root,update("analyst","testPassword123",true,"unknown"))).isInstanceOf(IllegalArgumentException.class);
        service.create(root,update("analyst","testPassword123",true));
        assertThatThrownBy(()->service.create(root,update("ANALYST","testPassword123",true))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->service.update(root,"admin",update(null,"",false))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void infrastructureManagementDoesNotRequireBusinessLicense() throws Exception {
        var license=mock(McpLicenseService.class);
        var filter=new McpMenuLicenseFilter(license,new McpAdminMenuCatalog(json),json);
        var calls=new AtomicInteger();
        for(String path:List.of("/api/v1/admin/users","/api/v1/admin/login-audits"))
            filter.doFilter(request(AdminMenuAccessService.token(root),path),new MockHttpServletResponse(),(a,b)->calls.incrementAndGet());
        assertThat(calls).hasValue(2);verifyNoInteractions(license);
    }
    @Test void unknownApisFailClosedAndAssetMenuCoversItsOwnSubmodules(){
        service.create(root,update("operator","testPassword123",true,"assetCenter"));
        var request=request(auth.login("operator","testPassword123").token(),"/");
        assertThat(access.allows(request,"/api/v1/ops/ssh-hosts","GET")).isTrue();
        assertThat(access.allows(request,"/api/v1/sql","POST")).isTrue();
        assertThat(access.allows(request,"/api/v1/admin/users","GET")).isFalse();
        assertThat(access.allows(request,"/api/v1/not-registered","GET")).isFalse();
        assertThat(access.allows(request,"/api/v1/license/status","GET")).isFalse();
    }
}
