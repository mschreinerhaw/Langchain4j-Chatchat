package com.chatchat.api.runtime.coding.data;

import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class CodingFieldDefinitionsTest {
    @Test void preservesExecutedQueryDeclarationButNeverCopiesConnectionOrRuntimeSecrets() {
        var source=Map.of("description","Actual producer declaration","queryDefinition","SELECT COUNT(*) AS total FROM source",
            "queryDefinitionOrigin","MCP_SQL_EXECUTION_RECEIPT","resultComplete",true,"returnedRowCount",1,
            "password","private","jdbcUrl","private");
        assertThat(CodingFieldDefinitions.sourceFromContext(Map.of("source",source)))
            .containsEntry("queryDefinition","SELECT COUNT(*) AS total FROM source")
            .containsEntry("resultComplete",true).containsEntry("returnedRowCount",1)
            .doesNotContainKeys("password","jdbcUrl");
    }
    @Test void unverifiedOrOversizedQueryTextDoesNotBecomeAnExecutionDeclaration() {
        assertThat(CodingFieldDefinitions.sanitizeSource(Map.of("queryDefinition","SELECT 1","queryDefinitionOrigin","USER_TEXT")))
            .doesNotContainKey("queryDefinition");
        assertThat(CodingFieldDefinitions.sanitizeSource(Map.of("queryDefinition","x".repeat(16001),"queryDefinitionOrigin","MCP_SQL_EXECUTION_RECEIPT")))
            .doesNotContainKey("queryDefinition");
    }
}
