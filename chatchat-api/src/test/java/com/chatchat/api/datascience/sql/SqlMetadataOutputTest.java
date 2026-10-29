package com.chatchat.api.datascience.sql;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class SqlMetadataOutputTest {
    final ObjectMapper json=new ObjectMapper();
    @ParameterizedTest @ValueSource(strings={"SELECT id FROM t","```sql\nSELECT id FROM t\n```","{\"sql\":\"SELECT id\\nFROM t\",\"explanation\":\"仅为提案\",\"executionStatus\":\"PROPOSED\"}","```JSON\n{\"notes\":\"结果\",\"files\":[{\"path\":\"sql/a.sql\",\"content\":\"SELECT \\\"id\\\" FROM t\"}]}\n```"})
    void supportedFinalRepresentationsHaveExplicitUnexecutedProposals(String response){
        var output=SqlMetadataOutput.normalize(response,json);
        assertThat((List<?>)output.get("files")).hasSize(1);assertThat(output.get("notes")).isInstanceOf(String.class);
    }
    @Test void plainExplanationDoesNotInventFilesOrRequireJson(){
        assertThat(SqlMetadataOutput.normalize("此查询读取日志表；目前没有外键证据。",json)).containsEntry("files",List.of());
    }
    @ParameterizedTest @ValueSource(strings={"{\"notes\":", "{\"sql\":\"SELECT id", "{\"notes\":\"x\"} trailing", "{'notes':'x'}", "[]", "```sql\nSELECT id", "{\"sql\":\"SELECT 1\",\"executionStatus\":\"SUCCESS\"}"})
    void damagedOrAmbiguousPayloadsCannotBecomeSuccessfulEditorChanges(String response){
        assertThatThrownBy(()->SqlMetadataOutput.normalize(response,json)).isInstanceOf(SqlMetadataOutput.Invalid.class)
            .hasMessageNotContaining("Return one valid JSON output object");
    }
    @Test void diagnosticsAreBoundedAndCredentialsLiteralsAndPersonalIdentifiersAreRedacted(){
        var preview=SqlMetadataOutput.diagnosticPreview("token=actual-secret Authorization: Bearer abc.def password=hidden user@example.com 12345678901 SELECT 'customer name';"+"x".repeat(9000));
        assertThat(preview).doesNotContain("actual-secret","abc.def","hidden","user@example.com","12345678901","customer name").hasSizeLessThanOrEqualTo(8000);
    }
    @Test void alreadyAggregatedStreamingChunksAndNonStreamingTextUseTheSameAdapter(){
        var chunks=List.of("{\"sql\":\"SELECT ","id\\nFROM t\",\"executionStatus\":\"PROPOSED\"}");
        assertThat(SqlMetadataOutput.normalize(String.join("",chunks),json)).isEqualTo(SqlMetadataOutput.normalize("{\"sql\":\"SELECT id\\nFROM t\",\"executionStatus\":\"PROPOSED\"}",json));
    }
}
