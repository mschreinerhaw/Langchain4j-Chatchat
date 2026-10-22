package com.chatchat.api.enterprise.controller;
import com.chatchat.enterprise.service.EnterpriseAdminService;
import com.chatchat.enterprise.entity.identity.SysRole;
import com.chatchat.enterprise.repository.identity.SysRoleRepository;
import com.chatchat.api.security.ApiAuthenticationFilter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class EnterpriseRoleTenantTest {
 @Test void ordinaryRoleAdministratorCannotReadOrOverwriteForeignRoleAuthorization(){
  var admin=mock(EnterpriseAdminService.class);var controller=mock(EnterpriseAdminController.class,CALLS_REAL_METHODS);ReflectionTestUtils.setField(controller,"adminService",admin);
  var request=new MockHttpServletRequest();request.setAttribute(ApiAuthenticationFilter.CURRENT_USER_ID,"actor");
  when(admin.getUserView("actor")).thenReturn(new EnterpriseAdminService.UserView("actor","tenant-a",100001L,null,"actor","Actor",null,null,"enabled",null,List.of(),List.of(),null,null));
  var foreign=new SysRole();foreign.setId("foreign");foreign.setTenantId("tenant-b");when(admin.getRoleAuthorization("foreign")).thenReturn(new EnterpriseAdminService.RoleAuthorizationView(foreign,List.of(),List.of(),List.of(),List.of()));
  assertThatThrownBy(()->controller.getRoleAuthorization("foreign",request)).isInstanceOf(SecurityException.class);
  assertThatThrownBy(()->controller.saveRoleAuthorization("foreign",null,request)).isInstanceOf(SecurityException.class);verify(admin,never()).saveRoleAuthorization(anyString(),any());
 }
 @Test void defaultRoleListUsesAuthenticatedTenantAndRoleTenantCannotBeMoved(){
  var admin=mock(EnterpriseAdminService.class);var roles=mock(SysRoleRepository.class);var controller=mock(EnterpriseAdminController.class,CALLS_REAL_METHODS);ReflectionTestUtils.setField(controller,"adminService",admin);ReflectionTestUtils.setField(controller,"roleRepository",roles);
  var request=new MockHttpServletRequest();request.setAttribute(ApiAuthenticationFilter.CURRENT_USER_ID,"actor");when(admin.getUserView("actor")).thenReturn(new EnterpriseAdminService.UserView("actor","tenant-a",100001L,null,"actor","Actor",null,null,"enabled",null,List.of(),List.of(),null,null));
  controller.listRoles(null,request);verify(roles).findByTenantIdOrderByRoleNameAsc("tenant-a");verify(roles,never()).findAll();
  var stored=new SysRole();stored.setTenantId("tenant-a");when(roles.findById("role")).thenReturn(Optional.of(stored));var forged=new SysRole();forged.setTenantId("tenant-b");assertThatThrownBy(()->controller.updateRole("role",forged,request)).isInstanceOf(SecurityException.class);verify(admin,never()).saveRole(any());
 }
}
