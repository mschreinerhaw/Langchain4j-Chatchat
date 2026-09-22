package com.chatchat.enterprise.entity.mcp;

import com.chatchat.enterprise.entity.mcp.McpToolAsset;
import com.chatchat.enterprise.entity.mcp.McpToolWorkflowContract;

import jakarta.persistence.Column;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

class McpToolLargePayloadMappingTest {

    @Test
    void jsonPayloadColumnsCannotRegressToHibernateTinyTextDefaults() throws Exception {
        assertLongText(McpToolAsset.class, "inputSchemaJson");
        assertLongText(McpToolAsset.class, "outputSchemaJson");
        assertLongText(McpToolWorkflowContract.class, "inputSchemaJson");
        assertLongText(McpToolWorkflowContract.class, "outputSchemaJson");
        assertLongText(McpToolWorkflowContract.class, "extensionsJson");
    }

    private void assertLongText(Class<?> entityType, String fieldName) throws Exception {
        Field field = entityType.getDeclaredField(fieldName);
        Column column = field.getAnnotation(Column.class);
        assertThat(column)
            .as("%s.%s must retain a portable large-text length", entityType.getSimpleName(), fieldName)
            .isNotNull();
        assertThat(column.length()).isEqualTo(org.hibernate.Length.LONG32);
    }
}
