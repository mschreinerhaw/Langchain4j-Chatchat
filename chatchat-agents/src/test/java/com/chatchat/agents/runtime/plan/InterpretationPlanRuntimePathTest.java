package com.chatchat.agents.runtime.plan;

import com.chatchat.agents.runtime.ToolRuntimeService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class InterpretationPlanRuntimePathTest {

    @Test
    void treatsStringTemplateIdsAsTemplateIdObjectsForLegacyAssetMetadata() throws Exception {
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            mock(ToolRuntimeService.class),
            new InterpretationPlanValidator(),
            mock(InterpretationPlanRuntime.DagExecutionController.class)
        );
        Method method = InterpretationPlanRuntime.class.getDeclaredMethod("valueAtPath", Object.class, String.class);
        method.setAccessible(true);
        Object output = Map.of(
            "assets", List.of(Map.of(
                "capabilities", Map.of(
                    "allowedCommandTemplates", List.of("CHECK_DISK")
                )
            ))
        );

        Object value = method.invoke(
            runtime,
            output,
            "$.assets[0].capabilities.allowedCommandTemplates[0].templateId"
        );

        assertThat(value).isEqualTo("CHECK_DISK");
    }

    @Test
    void readsAssetFieldsFromMcpTextJsonEnvelope() throws Exception {
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            mock(ToolRuntimeService.class),
            new InterpretationPlanValidator(),
            mock(InterpretationPlanRuntime.DagExecutionController.class)
        );
        Method method = InterpretationPlanRuntime.class.getDeclaredMethod("valueAtPath", Object.class, String.class);
        method.setAccessible(true);
        Object output = Map.of(
            "content", List.of(Map.of(
                "type", "text",
                "text", """
                    {
                      "success": true,
                      "assets": [
                        {
                          "asset": {
                            "name": "248测试数据库",
                            "environment": "DEV"
                          }
                        }
                      ]
                    }
                    """
            ))
        );

        Object value = method.invoke(runtime, output, "$.assets[0].asset.name");

        assertThat(value).isEqualTo("248测试数据库");
    }

    @Test
    void readsAssetFieldsFromStructuredContentEnvelope() throws Exception {
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            mock(ToolRuntimeService.class),
            new InterpretationPlanValidator(),
            mock(InterpretationPlanRuntime.DagExecutionController.class)
        );
        Method method = InterpretationPlanRuntime.class.getDeclaredMethod("valueAtPath", Object.class, String.class);
        method.setAccessible(true);
        Object output = Map.of(
            "structuredContent", Map.of(
                "success", true,
                "assets", List.of(Map.of(
                    "asset", Map.of(
                        "name", "248测试数据库",
                        "environment", "DEV"
                    )
                ))
            )
        );

        Object value = method.invoke(runtime, output, "assets[0].asset.environment");

        assertThat(value).isEqualTo("DEV");
    }

    @Test
    void resolvesLogicalAssetAliasesFromCanonicalAssetView() throws Exception {
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            mock(ToolRuntimeService.class),
            new InterpretationPlanValidator(),
            mock(InterpretationPlanRuntime.DagExecutionController.class)
        );
        Method method = InterpretationPlanRuntime.class.getDeclaredMethod(
            "canonicalProtocolValue",
            Object.class,
            String.class
        );
        method.setAccessible(true);
        Object output = Map.of(
            "structuredContent", Map.of(
                "assets", List.of(Map.of(
                    "asset", Map.of(
                        "name", "248-test-database",
                        "environment", "DEV",
                        "type", "sql_datasource"
                    )
                ))
            )
        );

        assertThat(method.invoke(runtime, output, "assets[0].assetName"))
            .isEqualTo("248-test-database");
        assertThat(method.invoke(runtime, output, "$.assets[0].name"))
            .isEqualTo("248-test-database");
        assertThat(method.invoke(runtime, output, "executionContext.env"))
            .isEqualTo("DEV");
        assertThat(method.invoke(runtime, output, "assets[0].assetType"))
            .isEqualTo("sql_datasource");
    }
}
