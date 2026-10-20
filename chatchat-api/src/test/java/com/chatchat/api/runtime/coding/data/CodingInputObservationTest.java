package com.chatchat.api.runtime.coding.data;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CodingInputObservationTest {
    @Test void boundedPrefixesRemainUntrustedIncompleteAndSeparateByExactSource() throws Exception {
        var json=new ObjectMapper();var observation=new CodingInputObservation(json,"set",true);
        for(int i=0;i<12;i++) {
            var row=Map.<String,Object>of("datasetReference","source-"+i,"record",Map.of("text","示例🙂".repeat(2000)));
            observation.accept(row);observation.accept(row);
        }
        var snapshot=observation.snapshot();
        assertThat(snapshot).containsEntry("complete",false).containsEntry("maxSources",8);
        var samples=(List<Map<String,Object>>)snapshot.get("samples");
        assertThat(samples).hasSizeLessThanOrEqualTo(8).isNotEmpty();
        assertThat(json.writeValueAsBytes(samples).length).isLessThanOrEqualTo(16384);
        assertThat(samples).allSatisfy(sample->{
            assertThat(sample.get("truncated")).isEqualTo(true);
            var prefix=sample.get("rowJsonPrefix").toString();
            assertThat(prefix.codePointCount(0,prefix.length())).isLessThanOrEqualTo(1024);
            assertThat(sample.get("datasetReference").toString()).startsWith("source-");
            assertThat(prefix).doesNotContain("\uFFFD");
        });
    }
}
