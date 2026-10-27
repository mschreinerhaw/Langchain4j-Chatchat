package com.chatchat.agents.runtime.tool;

import com.chatchat.agents.runtime.config.McpWorkflowProperties;
import com.chatchat.agents.runtime.governance.McpPolicyProperties;
import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.mcp.capability.McpTemplateSelectionScope;
import com.chatchat.common.tool.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class PublishedDataSupplyWorkflowTest {
    static final String ASSET="generic_assets", FIXED="published_templates", OTHER="another_required_tool";

    @Test void fixedPublicationSuppliesItsOwnGenericDiscoveryBoundaryAtExecutionEntry() {
        verifyAdmission(true,"api_service",false,false,true,true);
    }
    @Test void unboundPublicationStillNeedsAssetDiscovery() {
        verifyAdmission(false,"api_service",false,false,true,false);
    }
    @Test void differentAssetFamilyDoesNotSatisfyDependency() {
        verifyAdmission(true,"ssh_host",false,false,true,false);
    }
    @Test void anotherFixedPublicationIsStillRequired() {
        verifyAdmission(true,"api_service",true,false,true,false);
    }
    @Test void unrelatedPrerequisiteCannotBeRemovedByTheBinding() {
        verifyAdmission(true,"api_service",false,true,true,false);
    }
    @Test void fixedBindingDoesNotBypassTheAllowedToolSet() {
        verifyAdmission(true,"api_service",false,false,false,false);
    }

    private void verifyAdmission(boolean bound,String family,boolean fixedDependency,
                                 boolean unrelated,boolean allowed,boolean expected) {
        var registry=mock(ToolRegistry.class);
        var provider=new LinkedHashMap<String,Object>();
        provider.put("assetType",family);
        provider.put("workflowContract",ToolWorkflowContract.declaration(
            ToolWorkflowRole.TEMPLATE_DISCOVERY,"mcp.authorized-template-query.v1","filters"));
        if(bound) provider.put("templateSelectionScope",McpTemplateSelectionScope.fixedBinding(family).toMetadata());
        var asset=new LinkedHashMap<String,Object>();
        asset.put("assetType","api_service");
        asset.put("workflowContract",ToolWorkflowContract.declaration(
            fixedDependency?ToolWorkflowRole.TEMPLATE_DISCOVERY:ToolWorkflowRole.ASSET_DISCOVERY,"asset.v1","filters"));
        if(fixedDependency) asset.put("templateSelectionScope",McpTemplateSelectionScope.fixedBinding("api_service").toMetadata());
        when(registry.getToolMetadata(FIXED)).thenReturn(metadata(FIXED,provider));
        when(registry.getToolMetadata(ASSET)).thenReturn(metadata(ASSET,asset));
        when(registry.getToolMetadata(OTHER)).thenReturn(metadata(OTHER,Map.of()));
        when(registry.executeEnhancedTool(any(),any())).thenReturn(ToolOutput.success("governed candidate result"));
        var properties=new ToolRuntimeProperties();
        properties.setDefaultRetryAttempts(0);
        properties.setEnforceAllowedTools(true);
        var service=new ToolRuntimeService(registry,new ObjectMapper(),properties,
            new McpPolicyProperties(),new McpWorkflowProperties(),List.of(),List.of());
        try {
            List<String> dependencies=unrelated?List.of(ASSET,OTHER):List.of(ASSET);
            var workflow=Map.of("enabled",true,"workflow","bound_supply","steps",List.of(
                Map.of("step",1,"tool",ASSET,"required",true),
                Map.of("step",2,"tool",FIXED,"required",true,"dependsOn",dependencies)),
                "toolDependencies",Map.of(FIXED,Map.of("dependsOn",dependencies)),
                "executionStrategy",Map.of("mode","sequential","maxSteps",5));
            var request=ToolRuntimeRequest.builder().toolName(FIXED).runtimeMode("interpretation_plan")
                .requestId(UUID.randomUUID().toString()).tenantId("tenant").userId("user")
                .allowedTools(allowed?List.of(FIXED):List.of(OTHER))
                .toolInput(ToolInput.builder().userId("user").parameters(Map.of()).build())
                .attributes(Map.of("mcpWorkflow",workflow,"dagDependencyIsolation",true,
                    "authoritativeWorkflowDag",List.of(Map.of("tool",ASSET),
                        Map.of("tool",FIXED,"dependsOnTools",dependencies))))
                .build();
            var execution=service.execute(request);
            assertThat(execution.output().isSuccess()).as(execution.output().getErrorMessage()).isEqualTo(expected);
            if(expected) {
                assertThat(execution.audit().get("matchedPolicyRules").toString()).contains("published_binding_dependency");
                verify(registry,times(1)).executeEnhancedTool(any(),any());
            } else verify(registry,never()).executeEnhancedTool(any(),any());
        } finally { service.shutdown(); }
    }

    private ToolMetadata metadata(String name,Map<String,Object> metadata) {
        return ToolMetadata.builder().id(name).title(name).operationType("read").riskLevel("low")
            .agentCompatible(true).metadata(metadata).build();
    }
}
