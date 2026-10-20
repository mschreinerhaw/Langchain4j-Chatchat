package com.chatchat.agents.orchestration.analysis.graph;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CodingRequestBatchTest {
    private Map<String,Object> request(String id, String ref) {
        return Map.of("operation","RUN_CODING","requestId",id,"datasetReference",ref,"instruction","Analyze " + id,
            "outputSchema",Map.of("type","object","properties",Map.of(id,Map.of("type","integer")),"required",List.of(id)));
    }
    @Test void combinesIndependentRequestsPreservingSourcesSchemasAndStableIdentity() {
        var original=List.of(request("alpha","one#stdout"),request("beta","two#payload"));
        var result=CodingRequestBatch.compose(original,Set.of("one#stdout","two#payload"));
        assertThat(result).hasSize(1);
        var batch=result.get(0);
        assertThat(batch.get("datasetReferences")).isEqualTo(List.of("one#stdout","two#payload"));
        assertThat(batch.get("instruction").toString()).contains("alpha","beta","result_1","result_2","one#stdout","two#payload");
        var schema=(Map<?,?>)batch.get("outputSchema");
        var fields=(Map<?,?>)schema.get("properties");
        assertThat(((Map<?,?>)fields.get("result_1")).get("items")).isEqualTo(original.get(0).get("outputSchema"));
        assertThat(((Map<?,?>)fields.get("result_2")).get("items")).isEqualTo(original.get(1).get("outputSchema"));
        assertThat(CodingRequestBatch.compose(original,Set.of("two#payload","one#stdout"))).isEqualTo(result);
        assertThat(CodingRequestBatch.compose(List.of(original.get(1),original.get(0)),Set.of("one#stdout","two#payload")))
            .isNotEqualTo(result);
    }
    @Test void neverCombinesMixedDependentUnauthorizedOrConflictingRequests() {
        var first=request("alpha","one");
        for (var second:List.of(Map.<String,Object>of("operation","READ_RECORDS","datasetReference","one"),
                request("beta","not-delivered"),request("alpha","two"))) {
            var requests=List.of(first,second);
            assertThat(CodingRequestBatch.compose(requests,Set.of("one","two"))).isSameAs(requests);
        }
    }
    @Test void enforcesExistingUnionLimitWithoutDroppingInputs() {
        var first=new LinkedHashMap<>(request("alpha","one"));first.remove("datasetReference");
        first.put("datasetReferences",List.of("a","b","c","d","e","f","g","h"));
        var requests=List.<Map<String,Object>>of(first,request("beta","i"));
        assertThat(CodingRequestBatch.compose(requests,Set.of("a","b","c","d","e","f","g","h","i"))).isSameAs(requests);
    }
    @Test void doesNotNormalizeAnInvalidDuplicateInputContract() {
        var first=new LinkedHashMap<>(request("alpha","one"));first.remove("datasetReference");
        first.put("datasetReferences",List.of("one","one"));
        var requests=List.<Map<String,Object>>of(first,request("beta","two"));
        assertThat(CodingRequestBatch.compose(requests,Set.of("one","two"))).isSameAs(requests);
    }
}
