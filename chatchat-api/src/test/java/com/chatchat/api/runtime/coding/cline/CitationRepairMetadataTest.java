package com.chatchat.api.runtime.coding.cline;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CitationRepairMetadataTest {
    @Test void prioritizesAlreadyReferencedUrlsWithinTheBoundWithoutReturningBodiesOrCredentials(){
        var sources=new ArrayList<Map<String,Object>>();
        for(int i=0;i<40;i++)sources.add(Map.of("evidence_id","ref-"+i,"source_url","https://example.test/"+i,
            "title","Title "+i,"text","PRIVATE_BODY","source_metadata",Map.of("apiKey","PRIVATE_SECRET")));
        var hints=ClineModelCallbackController.citationRepairSources(sources,"Read https://example.test/39 for this claim.");
        assertThat(hints).hasSize(32);assertThat(hints.get(0)).containsEntry("evidence_id","ref-39");
        assertThat(hints.toString()).doesNotContain("PRIVATE_BODY","PRIVATE_SECRET","apiKey");
        assertThat(sources).hasSize(40); // Projection cannot alter the actual evidence inventory.
    }
}
