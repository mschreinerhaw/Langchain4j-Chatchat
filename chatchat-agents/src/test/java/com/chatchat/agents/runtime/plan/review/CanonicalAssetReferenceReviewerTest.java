package com.chatchat.agents.runtime.plan.review;

import com.chatchat.agents.runtime.plan.selection.EvidenceBasedAssetCandidateEvaluator;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CanonicalAssetReferenceReviewerTest {
    private Map<String,Object> candidate(String id,String name) {
        return Map.of("asset",Map.of("id",id,"name",name,"enabled",true));
    }
    private Map<String,Object> page(Map<String,Object>... candidates) {
        return new LinkedHashMap<>(Map.of("schemaVersion","asset_query_result.v1","success",true,
            "possiblyTruncated",false,"returnedCount",candidates.length,"assets",List.of(candidates)));
    }
    @Test void resolvesOnlyTheExplicitCanonicalNameAndProjectsTheReturnedId() {
        var page=page(candidate("a","node-alpha 集群"),candidate("b","node-beta 集群"));
        var review=CanonicalAssetReferenceReviewer.review("分析 node-alpha 集群 的状态",page).orElseThrow();
        var selection=new EvidenceBasedAssetCandidateEvaluator().evaluate(page,review.metadata());
        assertThat(selection.selectedIds()).containsExactly("a");
        assertThat(selection.output().toString()).contains("runtime_exact_canonical_asset_reference");
    }
    @Test void doesNotTreatTheSoleCandidateAsAnExplicitReference() {
        assertThat(CanonicalAssetReferenceReviewer.review("分析运行状态",page(candidate("a","node-alpha")))).isEmpty();
    }
    @Test void rejectsPrefixesFuzzyNamesAndInventedNames() {
        var page=page(candidate("a","node-alpha"));
        for(String query:List.of("inspect node-alpha-backup","inspect node-alph","inspect other-node"))
            assertThat(CanonicalAssetReferenceReviewer.review(query,page)).isEmpty();
    }
    @Test void ambiguousOrQualifiedRequestsStillRequireSemanticDisambiguation() {
        var page=page(candidate("a","node-alpha"),candidate("b","node-beta"));
        for(String query:List.of("compare node-alpha and node-beta","不要分析 node-alpha","inspect not node-alpha","排除 node-alpha","不看 node-alpha","node-alpha 以外的服务器","don't inspect node-alpha"))
            assertThat(CanonicalAssetReferenceReviewer.review(query,page)).isEmpty();
        assertThat(CanonicalAssetReferenceReviewer.review("inspect node-alpha",page(candidate("a","node-alpha"),candidate("b","node-alpha")))).isEmpty();
    }
    @Test void incompleteFailedAndDisabledCandidatesCannotSupplyIdentity() {
        var page=page(candidate("a","node-alpha"));
        page.put("possiblyTruncated",true);assertThat(CanonicalAssetReferenceReviewer.review("inspect node-alpha",page)).isEmpty();
        page.put("possiblyTruncated",false);page.put("success",false);assertThat(CanonicalAssetReferenceReviewer.review("inspect node-alpha",page)).isEmpty();
        var disabled=page(Map.of("asset",Map.of("id","a","name","node-alpha","enabled",false)));
        assertThat(CanonicalAssetReferenceReviewer.review("inspect node-alpha",disabled)).isEmpty();
    }
    @Test void duplicateIdsUnknownContractAndShortIncidentalLabelsAreDeferred() {
        assertThat(CanonicalAssetReferenceReviewer.review("inspect node-alpha",page(candidate("a","node-alpha"),candidate("a","node-alpha")))).isEmpty();
        assertThat(CanonicalAssetReferenceReviewer.review("inspect node-alpha",page(candidate("A","node-alpha"),candidate("a","node-beta")))).isEmpty();
        var page=page(candidate("a","node-alpha"));page.remove("schemaVersion");
        assertThat(CanonicalAssetReferenceReviewer.review("inspect node-alpha",page)).isEmpty();
        assertThat(CanonicalAssetReferenceReviewer.review("inspect db status",page(candidate("a","db")))).isEmpty();
    }
}
