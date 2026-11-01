package com.chatchat.mcpserver.sql.admin;

import com.chatchat.mcpserver.admin.AdminMenuAccessService;
import com.chatchat.mcpserver.authorization.McpAuthorizationService;
import com.chatchat.mcpserver.sql.template.*;
import com.chatchat.mcpserver.sql.datasource.*;
import com.chatchat.mcpserver.templatepublication.admin.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StudioSqlCatalogControllerTest {
    final SqlTemplateConfigRepository templates=mock(SqlTemplateConfigRepository.class);
    final SqlDatasourceConfigRepository sources=mock(SqlDatasourceConfigRepository.class);
    final ToolReleaseRepository releases=mock(ToolReleaseRepository.class);
    final AdminMenuAccessService access=mock(AdminMenuAccessService.class);
    final McpAuthorizationService auth=mock(McpAuthorizationService.class);
    final MockHttpServletRequest request=new MockHttpServletRequest();
    final SqlTemplateAuthoringService authoring=mock(SqlTemplateAuthoringService.class);
    final StudioSqlCatalogController controller=new StudioSqlCatalogController(templates,sources,releases,access,auth,new ObjectMapper(),authoring);
    SqlTemplateConfig row;ToolRelease release;
    @BeforeEach void setup(){
        row=new SqlTemplateConfig();row.setId("revision");row.setCode("STUDIO_REVISION");row.setTitle("Published query");row.setPublicationGroupId("group");row.setPublicationVersion(1);
        row.setAuthorTenantId("tenant");row.setAuthorId("author");row.setPublicationState("PUBLISHED");row.setEnabled(true);row.setDatasourceId("source");row.setReleasedCapabilityId("release");
        row.setSqlTemplate("SELECT value FROM allowed_table");row.setParameterSchemaJson("{\"type\":\"object\",\"properties\":{\"day\":{\"type\":\"string\"}},\"required\":[\"day\"]}");row.setSqlTestEvidenceJson("{\"success\":true,\"traceId\":\"trial\"}");
        release=new ToolRelease();release.setId("release");release.setLogicalId("logical");release.setTenantId("tenant");release.setState("RUNNING");release.setReleaseNumber(2);release.setPublishedAt(Instant.now());release.setDefinitionJson("{\"binding\":{\"sqlRevisionId\":\"revision\"}}");
        when(templates.findById("revision")).thenReturn(Optional.of(row));when(releases.findById("release")).thenReturn(Optional.of(release));
        when(templates.findByPublicationGroupIdIsNotNullOrderByCreatedAtDesc()).thenReturn(List.of(row));
        when(templates.findAllByPublicationGroupIdOrderByPublicationVersionAsc("group")).thenReturn(List.of(row));
        when(releases.findByTenantIdOrderByCreatedAtDesc("tenant")).thenReturn(List.of(release));
        var source=new SqlDatasourceConfig();source.setName("Authorized database");source.setJdbcUrl("private-url");source.setPassword("private-password");when(sources.findById("source")).thenReturn(Optional.of(source));
    }
    @Test void projectsExistingReleaseWithoutCreatingQueryRecords(){
        var list=controller.list(request).getData();assertEquals(1,list.size());var view=list.get(0);
        assertEquals("SQL_STUDIO",view.get("sourceKind"));assertEquals(true,view.get("readOnly"));assertEquals("tpl_logical",view.get("toolName"));assertEquals(2,view.get("version"));
        assertTrue(view.get("parameterSummary").toString().contains("必填"));assertFalse(view.toString().contains("private-"));verify(templates,never()).save(any());
    }
    @Test void currentActiveRevisionWinsOverANewerUnpublishedDraft(){
        var draft=new SqlTemplateConfig();draft.setId("draft");draft.setPublicationGroupId("group");draft.setPublicationVersion(3);draft.setEnabled(false);
        when(templates.findByPublicationGroupIdIsNotNullOrderByCreatedAtDesc()).thenReturn(List.of(draft,row));assertEquals("revision",controller.list(request).getData().get(0).get("templateId"));
    }
    @Test void offlineStatusIsReadFreshWithoutCatalogCache(){
        assertEquals(true,controller.list(request).getData().get(0).get("enabled"));release.setState("OFFLINE");
        assertEquals(false,controller.list(request).getData().get(0).get("enabled"));assertEquals("OFFLINE",controller.list(request).getData().get(0).get("publicationState"));
    }
    @Test void restoredOlderSqlRevisionStillShowsNewestReleaseAfterOffline(){
        row.setEnabled(false);release.setState("OFFLINE");release.setReleaseNumber(3);
        var newerSql=new SqlTemplateConfig();newerSql.setId("sql-v2");newerSql.setPublicationGroupId("group");newerSql.setPublicationVersion(2);newerSql.setReleasedCapabilityId("release-v2");newerSql.setEnabled(false);
        var previous=new ToolRelease();previous.setReleaseNumber(2);when(releases.findById("release-v2")).thenReturn(Optional.of(previous));
        when(templates.findByPublicationGroupIdIsNotNullOrderByCreatedAtDesc()).thenReturn(List.of(newerSql,row));
        assertEquals("revision",controller.list(request).getData().get(0).get("templateId"));
    }
    @Test void nonSuperAdminDoesNotReceiveSqlOrExecutionPreviews(){
        var detail=controller.detail("revision",request).getData();assertEquals(false,detail.get("sqlVisible"));assertFalse(detail.toString().contains("SELECT value"));assertFalse(detail.toString().contains("trial"));verifyNoInteractions(auth);
    }
    @Test void historyIncludesEarlierReleasesOfTheSameRevision(){
        when(access.superAdmin(request)).thenReturn(true);var view=mock(McpAuthorizationService.AuthorizationSyncView.class);when(view.permissions()).thenReturn(List.of());when(auth.currentView()).thenReturn(view);
        var old=new ToolRelease();old.setId("old");old.setReleaseNumber(1);old.setState("OFFLINE");old.setDefinitionJson(release.getDefinitionJson());
        when(releases.findByTenantIdOrderByCreatedAtDesc("tenant")).thenReturn(List.of(release,old));var detail=controller.detail("revision",request).getData();
        assertEquals(row.getSqlTemplate(),detail.get("sqlTemplate"));assertEquals(2,((List<?>)detail.get("history")).size());
    }
    @Test void missingMenuPermissionFailsBeforeReadingOtherTenants(){
        doThrow(new SecurityException("denied")).when(access).require(request,"databaseMcp");assertThrows(SecurityException.class,()->controller.list(request));verifyNoInteractions(templates,releases,sources);
    }
    @Test void ordinaryMenuAccessDoesNotGrantAuthorDelegation(){
        var body=new StudioSqlCatalogController.ManagedAction(1L,"name","description","finance","test",Map.of(),"release");
        assertThrows(SecurityException.class,()->controller.test("revision",body,request));
        assertThrows(SecurityException.class,()->controller.update("revision",body,request));
        verifyNoInteractions(authoring,templates);
    }
    @Test void administratorOperationsDeriveOwnerAndSourceFromStoredRevision(){
        when(access.superAdmin(request)).thenReturn(true);
        var body=new StudioSqlCatalogController.ManagedAction(1L,"new name","new description","finance","test",Map.of("value",3),"release");
        when(authoring.test(anyString(),anyString(),anyString(),any())).thenReturn(Map.of("testEvidence",Map.of("success",true)));
        controller.test("revision",body,request);
        verify(authoring).test(eq(row.getAuthorTenantId()),eq(row.getAuthorId()),eq("revision"),argThat(input->input.parameters().equals(Map.of("value",3))&&input.expectedVersion()==1L));
        controller.update("revision",body,request);
        verify(authoring).updateMetadata(eq(row.getAuthorTenantId()),eq(row.getAuthorId()),eq("revision"),argThat(input->input.title().equals("new name")&&input.expectedActiveReleaseId().equals("release")));
        verify(templates,never()).save(any());
    }
}
