package com.chatchat.api.workspace;

import com.chatchat.api.testing.RuntimeSemanticPolicyFixture;
import com.chatchat.common.mcp.license.McpLicenseEntitlementPort;
import com.chatchat.enterprise.entity.identity.*;
import com.chatchat.enterprise.repository.identity.SysPermissionRepository;
import com.chatchat.enterprise.repository.workspace.WorkspaceRegistrationRepository;
import com.chatchat.enterprise.service.EnterpriseAdminService;
import com.chatchat.api.exploration.ExplorationWorkspaceMigration;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"chatchat.api.auth.enabled=true", "chatchat.enterprise.bootstrap.admin-password=Workspace-Test-Admin!",
 "spring.datasource.url=jdbc:h2:mem:exploration_application;MODE=MySQL;DB_CLOSE_DELAY=-1"})
@Import(RuntimeSemanticPolicyFixture.class)
@AutoConfigureMockMvc @ActiveProfiles("test") @DirtiesContext
class ExplorationApplicationIntegrationTest {
 @Autowired MockMvc mvc; @Autowired EnterpriseAdminService identities; @Autowired SysPermissionRepository permissions;
 @Autowired WorkspaceRegistrationRepository registry; @Autowired ObjectMapper json;
 @Autowired ExplorationApplicationMigration migration; @Autowired ExplorationWorkspaceMigration legacy;
 @MockBean ChatModel model; @MockBean McpLicenseEntitlementPort license;
 @BeforeEach void entitlement(){when(license.workspaceEntitlement()).thenReturn(new McpLicenseEntitlementPort.WorkspaceEntitlement(true,Set.of("zhice","data-science"),"VALID"));}

 @Test void aWorkspaceGrantIncludesOnlyItsInfrastructureDependenciesAndRevokesInTheSameSession() throws Exception {
  var admin=identities.login("admin","Workspace-Test-Admin!");String tenant=admin.user().tenantId();
  var role=new SysRole();role.setTenantId(tenant);role.setRoleCode("EXPLORATION_"+UUID.randomUUID());role.setRoleName("探索访问");role=identities.saveRole(role);
  var user=new SysUser();user.setTenantId(tenant);user.setUsername("exploration-"+UUID.randomUUID());user.setDisplayName("探索用户");user.setPasswordHash("Exploration-Test-User!");
  var caller=identities.saveUser(user,List.of(role.getId()));
  var entry=permissions.findByPermissionCode("workspace:data-exploration:access").orElseThrow();
  var auth=identities.saveRoleAuthorization(role.getId(),new EnterpriseAdminService.RoleAuthorizationRequest(List.of(entry.getId()),List.of(),null,List.of()));
  var codes=identities.getUserView(caller.id()).permissionCodes();
  assertThat(codes).contains("workspace:data-exploration:access","workspace:api:access","account:self:read","account:menus:read")
   .doesNotContain("capability:data-science","system:role","platform:agents:all");
  String token=identities.login(user.getUsername(),"Exploration-Test-User!").token();
  mvc.perform(get("/api/v1/workspaces").header("Authorization","Bearer "+token)).andExpect(status().isOk())
   .andExpect(jsonPath("$.data.length()").value(1)).andExpect(jsonPath("$.data[0].workspaceId").value("data-exploration"));
  mvc.perform(get("/api/v1/workspaces/data-exploration/manifest").header("Authorization","Bearer "+token)).andExpect(status().isOk())
   .andExpect(jsonPath("$.data.manifest.pages[0].componentKey").value("dataExploration"));
  mvc.perform(get("/api/v1/workspaces/data-science/manifest").header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
  mvc.perform(get("/api/v1/data-science/python/workbench").header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
  identities.saveRoleAuthorization(role.getId(),new EnterpriseAdminService.RoleAuthorizationRequest(auth.permissionIds().stream().filter(id->!id.equals(entry.getId())).toList(),List.of(),null,List.of()));
  legacy.migrate();migration.migrate();
  mvc.perform(get("/api/v1/workspaces").header("Authorization","Bearer "+token)).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(0));
  mvc.perform(get("/api/v1/workspaces/data-exploration/manifest").header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
  mvc.perform(get("/api/v1/data-science/explorations").header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
 }
 @Test void extractionIsIdempotentAndKeepsTheExistingLicenseBoundary() throws Exception {
  var extracted=registry.findById("data-exploration").orElseThrow();
  assertThat(extracted.getAccessPermissionCode()).isEqualTo("workspace:data-exploration:access");
  assertThat(json.readTree(registry.findById("data-science").orElseThrow().getManifestJson()).path("pages").toString()).doesNotContain("dataExploration");
  long version=extracted.getVersion();legacy.migrate();migration.migrate();
  assertThat(registry.findById("data-exploration").orElseThrow().getVersion()).isEqualTo(version);
  assertThat(json.readTree(registry.findById("data-science").orElseThrow().getManifestJson()).path("pages").toString()).doesNotContain("dataExploration");
  assertThat(new McpLicenseEntitlementPort.WorkspaceEntitlement(true,Set.of("zhice"),"VALID").allows("data-exploration")).isFalse();
  assertThat(new McpLicenseEntitlementPort.WorkspaceEntitlement(false,Set.of("data-science"),"INVALID").allows("data-exploration")).isFalse();
  assertThat(new McpLicenseEntitlementPort.WorkspaceEntitlement(true,Set.of("data-science"),"VALID").allows("data-exploration")).isTrue();
 }
 @Test void dataScienceGrantedAfterExtractionDoesNotImplicitlyGrantExploration() throws Exception {
  String tenant=identities.login("admin","Workspace-Test-Admin!").user().tenantId();
  var role=new SysRole();role.setTenantId(tenant);role.setRoleCode("SCIENCE_"+UUID.randomUUID());role.setRoleName("仅数据车间");role=identities.saveRole(role);
  identities.saveRoleAuthorization(role.getId(),new EnterpriseAdminService.RoleAuthorizationRequest(
   List.of(permissions.findByPermissionCode("capability:data-science").orElseThrow().getId()),List.of(),null,List.of()));
  var user=new SysUser();user.setTenantId(tenant);user.setUsername("science-"+UUID.randomUUID());user.setDisplayName("数据车间用户");user.setPasswordHash("Science-Test-Password!");
  identities.saveUser(user,List.of(role.getId()));String token=identities.login(user.getUsername(),"Science-Test-Password!").token();
  mvc.perform(get("/api/v1/workspaces/data-exploration/manifest").header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
  mvc.perform(get("/api/v1/data-science/explorations").header("Authorization","Bearer "+token).header("X-Workspace-Id","data-science")).andExpect(status().isForbidden());
 }
}
