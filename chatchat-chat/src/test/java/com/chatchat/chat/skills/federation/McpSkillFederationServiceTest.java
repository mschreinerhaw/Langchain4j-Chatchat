package com.chatchat.chat.skills.federation;

import com.chatchat.chat.skills.domain.DomainSkillCategoryRepository;
import com.chatchat.chat.skills.domain.DomainSkillEntity;
import com.chatchat.chat.skills.domain.DomainSkillIndexService;
import com.chatchat.chat.skills.domain.DomainSkillRepository;
import com.chatchat.common.mcp.license.McpLicenseEntitlementPort;
import com.chatchat.common.skills.federation.SkillSourceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpSkillFederationServiceTest {

    @Test
    void synchronizesVerifiedRemoteSkillAsGovernedDraft() throws Exception {
        McpSkillSourceJpaRepository sources = mock(McpSkillSourceJpaRepository.class);
        DomainSkillRepository skills = mock(DomainSkillRepository.class);
        DomainSkillCategoryRepository categories = mock(DomainSkillCategoryRepository.class);
        DomainSkillIndexService index = mock(DomainSkillIndexService.class);
        McpSkillSourceRepository remote = mock(McpSkillSourceRepository.class);
        McpLicenseEntitlementPort license = mock(McpLicenseEntitlementPort.class);
        McpSkillFederationProperties properties = new McpSkillFederationProperties();
        properties.setCredentialKey("test-key");
        McpSkillFederationService service = new McpSkillFederationService(
            sources, skills, categories, index, remote, license, properties, new ObjectMapper());

        McpSkillSourceEntity source = source();
        byte[] content = """
            ---
            name: investment-analysis
            description: Analyze investments
            ---
            # Investment

            Follow the governed process.
            """.getBytes(StandardCharsets.UTF_8);
        String uri = "skill://investment-analysis/SKILL.md";
        var descriptor = new SkillSourceRepository.ResourceDescriptor(uri, "sha256:" + sha256(content), content.length);
        var manifest = new SkillSourceRepository.SkillManifest(uri,
            Map.of("name", "investment-analysis", "description", "Analyze investments"),
            List.of(descriptor), false, 60_000L, "private");

        when(license.skillFederationEntitlement()).thenReturn(
            new McpLicenseEntitlementPort.SkillFederationEntitlement(true, "VALID", "ok", "test"));
        when(sources.findByIdAndTenantId("source-1", "tenant-1")).thenReturn(Optional.of(source));
        when(remote.discover(any(), nullable(String.class), anyInt())).thenReturn(
            new SkillSourceRepository.SkillPage(List.of(manifest), null));
        when(remote.describe(any(), any())).thenReturn(manifest);
        when(remote.read(any(), any())).thenReturn(new SkillSourceRepository.SkillResource(uri, "text/markdown", content));
        when(skills.findByTenantIdAndFederatedSourceIdAndFederatedSkillUri("tenant-1", "source-1", uri))
            .thenReturn(Optional.empty());
        when(skills.findByTenantIdAndFederatedSourceId("tenant-1", "source-1")).thenReturn(List.of());

        McpSkillFederationService.SyncResult result = service.synchronize("tenant-1", "source-1");

        ArgumentCaptor<DomainSkillEntity> saved = ArgumentCaptor.forClass(DomainSkillEntity.class);
        verify(skills).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo("DRAFT");
        assertThat(saved.getValue().getSourceType()).isEqualTo("MCP");
        assertThat(saved.getValue().getFederatedSourceId()).isEqualTo("source-1");
        assertThat(saved.getValue().getFederatedSkillUri()).isEqualTo(uri);
        assertThat(saved.getValue().getFederatedDigest()).startsWith("sha256:");
        assertThat(result.created()).isEqualTo(1);
        assertThat(result.status()).isEqualTo("SYNCED");
        verify(index, never()).index(any());
    }

    private McpSkillSourceEntity source() {
        McpSkillSourceEntity source = new McpSkillSourceEntity();
        source.setId("source-1");
        source.setTenantId("tenant-1");
        source.setOwnerId("admin");
        source.setName("Group Skill Center");
        source.setEndpoint("https://example.com/mcp");
        source.setDefaultCategory("Federated");
        source.setEnabled(true);
        source.setStatus("NOT_SYNCED");
        source.setCreatedAt(Instant.now());
        source.setUpdatedAt(Instant.now());
        return source;
    }

    private String sha256(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }
}
