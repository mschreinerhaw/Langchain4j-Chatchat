package com.chatchat.mcpserver.templatepublication.admin;

import com.chatchat.mcpserver.authorization.McpAuthorizationService;
import com.chatchat.mcpserver.templatepublication.publisher.TemplateQueryMcpToolPublisher;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Real version persistence and transaction locking; executor side effects are separately observed. */
@DataJpaTest(properties={"spring.jpa.hibernate.ddl-auto=create-drop","spring.jpa.database-platform=org.hibernate.dialect.H2Dialect","spring.datasource.url=jdbc:h2:mem:tool_release;DB_CLOSE_DELAY=-1"})
@ContextConfiguration(classes=ToolReleaseLifecycleTest.Config.class)
class ToolReleaseLifecycleTest {
    @Configuration @EntityScan(basePackageClasses=ToolRelease.class)
    @EnableJpaRepositories(basePackageClasses=ToolReleaseRepository.class)
    @Import(ToolReleaseService.class)
    static class Config { @Bean ObjectMapper json(){return new ObjectMapper().findAndRegisterModules();} }
    @Autowired ToolReleaseService service;
    @Autowired ToolReleaseRepository repository;
    @Autowired ObjectMapper json;
    @MockBean ControlledTemplateReleaseExecutor executor;
    @MockBean McpAuthorizationService authorization;
    @MockBean TemplateQueryMcpToolPublisher publisher;
    @BeforeEach void setup() {
        when(executor.kind()).thenReturn("CONTROLLED_TEMPLATE");
        when(executor.stableIdentity(any())).thenAnswer(call->call.<JsonNode>getArgument(0).path("domainCode").asText());
        when(executor.toolName(any())).thenAnswer(call->call.<JsonNode>getArgument(0).path("domainCode").asText());
        when(executor.activate(any(),any())).thenReturn("execution-reference");
        when(executor.test(any(),any())).thenReturn(json.valueToTree(Map.of("success",true)));
        when(authorization.roles("tenant-a")).thenReturn(List.of(new McpAuthorizationService.RoleView("role-a","tenant-a","USER","User","business","enabled")));
    }
    ToolReleaseService.Draft draft(String logical,String domain) throws Exception {
        return new ToolReleaseService.Draft(logical,"Tool","CONTROLLED_TEMPLATE",
            json.readTree("{\"domainCode\":\""+domain+"\",\"roleId\":\"role-a\"}"),
            json.readTree("{\"type\":\"object\",\"properties\":{\"limit\":{\"type\":\"integer\",\"minimum\":1}},\"required\":[\"limit\"]}"),
            json.readTree("{\"type\":\"object\",\"properties\":{\"success\":{\"const\":true}},\"required\":[\"success\"]}"));
    }
    ToolRelease tested(String logical,String domain) throws Exception {
        var row=service.draft("tenant-a","actor",draft(logical,domain));
        service.validate("tenant-a","actor",row.getId());
        return service.test("tenant-a","actor",row.getId(),new ToolReleaseService.ContractTest(json.readTree("{\"limit\":1}"),json.readTree("{\"success\":true}")));
    }
    @Test void draftsNeverRegisterAnExecutableToolAndCannotPublishWithoutTesting() throws Exception {
        var row=service.draft("tenant-a","actor",draft(null,"draft-only"));
        assertThat(row.getState()).isEqualTo("DRAFT");
        assertThatThrownBy(()->service.publish("tenant-a","actor",row.getId(),false)).hasMessageContaining("test");
        verify(executor,never()).activate(any(),any());verifyNoInteractions(publisher);
    }
    @Test void schemaFailurePreventsTestApproval() throws Exception {
        var row=service.draft("tenant-a","actor",draft(null,"invalid-sample"));service.validate("tenant-a","actor",row.getId());
        assertThatThrownBy(()->service.test("tenant-a","actor",row.getId(),new ToolReleaseService.ContractTest(json.readTree("{\"limit\":0}"),json.readTree("{\"success\":true}"))))
            .hasMessageContaining("Schema validation");
        assertThat(row.getState()).isEqualTo("VALIDATED");verify(executor,never()).activate(any(),any());
    }
    @Test void versionsAreImmutableAndRollbackReactivatesAnExactHistoricalDefinition() throws Exception {
        var first=tested(null,"versioned");service.publish("tenant-a","actor",first.getId(),false);
        assertThatThrownBy(()->service.validate("tenant-a","actor",first.getId())).hasMessageContaining("immutable");
        var second=tested(first.getLogicalId(),"versioned");service.publish("tenant-a","actor",second.getId(),false);
        assertThat(first.getState()).isEqualTo("OFFLINE");assertThat(second.getReleaseNumber()).isEqualTo(2);
        service.publish("tenant-a","actor",first.getId(),true);
        assertThat(first.getState()).isEqualTo("RUNNING");assertThat(second.getState()).isEqualTo("OFFLINE");
        verify(executor,times(3)).activate(any(),any());verify(authorization,atLeast(3)).refreshNow();
        service.offline("tenant-a","actor",first.getId());verify(executor).offline("execution-reference");
        assertThat(first.getState()).isEqualTo("OFFLINE");
    }
    @Test void tenantCannotAccessOrReviseAnotherTenantsRelease() throws Exception {
        var row=tested(null,"tenant-isolation");
        assertThatThrownBy(()->service.publish("tenant-b","other",row.getId(),false)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(()->service.draft("tenant-b","other",draft(row.getLogicalId(),"tenant-isolation"))).isInstanceOf(SecurityException.class);
        verify(executor,never()).activate(any(),any());
    }
    @Test void revocationAfterTestBlocksPublicationAndRollback() throws Exception {
        var row=tested(null,"revocation");when(authorization.roles("tenant-a")).thenReturn(List.of());
        assertThatThrownBy(()->service.publish("tenant-a","actor",row.getId(),false)).isInstanceOf(SecurityException.class);
        verify(executor,never()).activate(any(),any());
    }
    @Test void changingLogicalToolIdentityRequiresANewTool() throws Exception {
        var row=tested(null,"stable");
        assertThatThrownBy(()->service.draft("tenant-a","actor",draft(row.getLogicalId(),"different"))).hasMessageContaining("stable");
    }
    ToolReleaseService.DraftUpdate update(ToolRelease row, String domain) throws Exception {
        var value = draft(row.getLogicalId(), domain);
        return new ToolReleaseService.DraftUpdate("Updated tool", value.definition(), value.inputSchema(), value.outputSchema(), "Updated description", row.getVersion());
    }
    @Test void editingAnUnpublishedVersionPreservesItsToolAndNumberAndInvalidatesEvidence() throws Exception {
        var row = tested(null, "editable");
        String id = row.getId(), logical = row.getLogicalId();
        assertThat(row.getTestEvidenceJson()).isNotNull();
        var result = service.updateDraft("tenant-a", "editor", id, update(row, "corrected"));
        assertThat(result.getId()).isEqualTo(id); assertThat(result.getLogicalId()).isEqualTo(logical);
        assertThat(result.getReleaseNumber()).isEqualTo(1); assertThat(result.getState()).isEqualTo("DRAFT");
        assertThat(result.getTestEvidenceJson()).isNull(); assertThat(result.getTitle()).isEqualTo("Updated tool");
        assertThat(repository.count()).isEqualTo(1);
        verify(executor, never()).activate(any(), any());
    }
    @Test void staleDraftWritesAreRejectedInsteadOfOverwritingAnotherEditorsChange() throws Exception {
        var row = service.draft("tenant-a", "actor", draft(null, "concurrent"));
        var stale = update(row, "concurrent");
        service.updateDraft("tenant-a", "editor", row.getId(), stale);
        assertThatThrownBy(() -> service.updateDraft("tenant-a", "other", row.getId(), stale))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class).hasMessageContaining("409");
    }
    @Test void publishedVersionsCannotBeEditedAndRevisionsCannotChangeToolIdentity() throws Exception {
        var first = tested(null, "owned"); service.publish("tenant-a", "actor", first.getId(), false);
        var revision = service.draft("tenant-a", "actor", draft(first.getLogicalId(), "owned"));
        assertThat(revision.getReleaseNumber()).isEqualTo(2);
        assertThatThrownBy(() -> service.updateDraft("tenant-a", "editor", first.getId(), update(first, "owned"))).hasMessageContaining("immutable");
        assertThatThrownBy(() -> service.updateDraft("tenant-a", "editor", revision.getId(), update(revision, "other"))).hasMessageContaining("其他版本");
    }
    @Test void newToolsCannotStealAnExistingToolsIdentityAndUpdatesEnforceTenantIsolation() throws Exception {
        var row = service.draft("tenant-a", "actor", draft(null, "unique"));
        assertThatThrownBy(() -> service.draft("tenant-a", "actor", draft(null, "unique"))).hasMessageContaining("409");
        assertThatThrownBy(() -> service.updateDraft("tenant-b", "other", row.getId(), update(row, "unique")))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class).hasMessageContaining("404");
    }
    @Test void legacyDuplicatesDoNotAppearAsSeparateToolsAfterAVersionedPublication() throws Exception {
        var managed = tested(null, "managed"); service.publish("tenant-a", "actor", managed.getId(), false);
        var legacy = new ToolRelease(); legacy.setId("legacy"); legacy.setLogicalId("legacy"); legacy.setTenantId("tenant-a");
        legacy.setActorId("legacy-migration"); legacy.setReleaseNumber(1); legacy.setKind(managed.getKind()); legacy.setState("RUNNING");
        legacy.setTitle("Duplicate import"); legacy.setBindingId(managed.getBindingId()); legacy.setCreatedAt(managed.getCreatedAt());
        legacy.setDefinitionJson(managed.getDefinitionJson()); legacy.setInputSchemaJson(managed.getInputSchemaJson()); legacy.setOutputSchemaJson(managed.getOutputSchemaJson());
        repository.saveAndFlush(legacy);
        assertThat(service.list("tenant-a")).extracting(ToolRelease::getId).containsExactly(managed.getId());
        assertThat(repository.count()).isEqualTo(2);
        var unordered = mock(ToolReleaseRepository.class);
        when(unordered.findByState("RUNNING")).thenReturn(List.of(legacy, managed));
        assertThat(new ToolReleaseContractCatalog(unordered, json).metadata("managed_template_query"))
            .containsEntry("releaseId", managed.getId()).containsEntry("logicalToolId", managed.getLogicalId());
    }
    @Test void restartingNeverReimportsAnAlreadyVersionedBindingAsANewLogicalTool() throws Exception {
        var row = tested(null, "restart"); service.publish("tenant-a", "actor", row.getId(), false);
        var bindings = mock(com.chatchat.mcpserver.templatepublication.binding.TemplateQueryBindingRepository.class);
        var binding = new com.chatchat.mcpserver.templatepublication.binding.TemplateQueryBinding();
        binding.setId(row.getBindingId());
        when(bindings.findAllByOrderByUpdatedAtDesc()).thenReturn(List.of(binding));
        new ToolReleaseLegacyMigration(repository, bindings, publisher, json).migrate();
        assertThat(repository.count()).isEqualTo(1);
        assertThat(service.list("tenant-a")).extracting(ToolRelease::getLogicalId).containsExactly(row.getLogicalId());
    }
    @Test void genuineImportedFirstVersionsStayInTheirOwnToolsHistoryAfterPublishingARevision() throws Exception {
        var first = tested(null, "imported"); service.publish("tenant-a", "legacy-migration", first.getId(), false);
        var next = tested(first.getLogicalId(), "imported"); service.publish("tenant-a", "editor", next.getId(), false);
        assertThat(service.list("tenant-a")).extracting(ToolRelease::getId).containsExactlyInAnyOrder(first.getId(), next.getId());
        assertThat(first.getState()).isEqualTo("OFFLINE");
        assertThat(next.getReleaseNumber()).isEqualTo(2);
    }
    @Test void schemaCannotReadNetworkOrLocalFiles() throws Exception {
        for(String ref:List.of("https://example.invalid/schema","file:///etc/passwd")) {
            var schema=json.readTree("{\"type\":\"object\",\"$ref\":\""+ref+"\"}");
            assertThatThrownBy(()->ToolReleaseSchemas.compile(schema)).hasMessageContaining("local");
        }
    }
    @Test void publishedContractMaterializesThroughTheExistingPublicationPipeline() throws Exception {
        var row=tested(null,"materialized");service.publish("tenant-a","actor",row.getId(),false);
        var catalog=new ToolReleaseContractCatalog(repository,json);
        String name="materialized_template_query";
        var tool=io.modelcontextprotocol.spec.McpSchema.Tool.builder().name(name).title("Released tool").description("Controlled query")
            .inputSchema(catalog.inputSchema(name,null)).outputSchema(catalog.outputSchema(name)).meta(catalog.metadata(name)).build();
        var spec=io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification.builder().tool(tool)
            .callHandler((exchange,request)->io.modelcontextprotocol.spec.McpSchema.CallToolResult.builder().addTextContent("ok").build()).build();
        var publication=com.chatchat.mcpserver.tool.ToolPublication.from(spec);
        com.chatchat.mcpserver.tool.McpToolPublicationReviewer.review(publication);
        assertThat(publication.descriptor().status()).isEqualTo(com.chatchat.mcpserver.tool.McpToolPublicationStatus.ACTIVE);
        assertThat(publication.specification().tool().outputSchema()).containsKey("properties");
        catalog.validateInput(name,Map.of("limit",1,"userId","authenticated","tenantId","tenant-a",
            "mcpContext",Map.of(),"_templateQueryChildToolName",name));
    }
}
