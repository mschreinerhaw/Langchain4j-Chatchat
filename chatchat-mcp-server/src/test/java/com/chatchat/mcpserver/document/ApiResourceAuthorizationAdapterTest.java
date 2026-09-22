package com.chatchat.mcpserver.document;

import com.chatchat.common.retrieval.ResourceAuthorizationPort;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;

class ApiResourceAuthorizationAdapterTest {
    @Test
    void batchesLargeCandidateSetsForTheInternalEndpoint() {
        ApiDocumentEvidenceClient api = mock(ApiDocumentEvidenceClient.class);
        Set<String> candidates = IntStream.range(0, 501).mapToObj(i -> "doc-" + i)
            .collect(java.util.stream.Collectors.toSet());
        when(api.allowedResourceIds(eq(ResourceAuthorizationPort.KNOWLEDGE), eq("tenant-a"),
            eq("user-a"), anySet())).thenAnswer(invocation -> invocation.getArgument(3));

        assertThat(new ApiResourceAuthorizationAdapter(api).allowedIds(
            ResourceAuthorizationPort.KNOWLEDGE, "tenant-a", "user-a", Set.of(), candidates))
            .hasSize(501);
        verify(api, times(2)).allowedResourceIds(eq(ResourceAuthorizationPort.KNOWLEDGE),
            eq("tenant-a"), eq("user-a"), anySet());
    }

    @Test
    void usesApiGrantsAndDeniesWhenApiIsUnavailable() {
        ApiDocumentEvidenceClient api = mock(ApiDocumentEvidenceClient.class);
        Set<String> candidates = Set.of("doc-a", "doc-b");
        when(api.allowedResourceIds(ResourceAuthorizationPort.KNOWLEDGE, "tenant-a", "user-a", candidates))
            .thenReturn(Set.of("doc-a"))
            .thenThrow(new IllegalStateException("API unavailable"));
        ApiResourceAuthorizationAdapter adapter = new ApiResourceAuthorizationAdapter(api);

        assertThat(adapter.allowedIds(ResourceAuthorizationPort.KNOWLEDGE, "tenant-a", "user-a",
            Set.of("forged-role"), candidates)).containsExactly("doc-a");
        assertThat(adapter.allowedIds(ResourceAuthorizationPort.KNOWLEDGE, "tenant-a", "user-a",
            Set.of("forged-role"), candidates)).isEmpty();
    }
}
