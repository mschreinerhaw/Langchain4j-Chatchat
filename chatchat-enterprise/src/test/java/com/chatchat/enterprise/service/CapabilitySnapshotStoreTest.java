package com.chatchat.enterprise.service;

import com.chatchat.common.mcp.capability.McpDynamicCapabilityRoute;
import com.chatchat.common.mcp.service.McpToolDescriptor;
import com.chatchat.enterprise.entity.mcp.CapabilityRegistryEntry;
import com.chatchat.enterprise.repository.mcp.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@DataJpaTest
@ContextConfiguration(classes = CapabilitySnapshotStoreTest.Config.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CapabilitySnapshotStoreTest {
    @SpringBootConfiguration
    @EntityScan(basePackageClasses = CapabilityRegistryEntry.class)
    @EnableJpaRepositories(basePackageClasses = CapabilityRegistryRepository.class)
    @Import(CapabilitySnapshotStore.class)
    static class Config { @Bean ObjectMapper mapper() { return new ObjectMapper(); } }
    @Autowired CapabilitySnapshotStore store;
    @Autowired CapabilityRegistryRepository registry;
    @Autowired CapabilityVersionRepository versions;
    @BeforeEach void clear() { registry.deleteAll(); versions.deleteAll(); }
    private McpToolDescriptor tool(String description, Map<String, Object> metadata) {
        return new McpToolDescriptor("server", "child", "remote-child", description, "data",
            Map.of("type", "object", "properties", Map.of("query", Map.of("type", "string"))),
            Map.of("type", "object"), Map.of("operationType", "read"), metadata);
    }
    @Test void repeatedSyncIsDeduplicatedAndContractChangeCreatesImmutableVersion() {
        var first = store.synchronize(List.of(tool("first", Map.of())), Set.of("server")).get(0);
        var repeat = store.synchronize(List.of(tool("first", Map.of())), Set.of("server")).get(0);
        assertThat(repeat.version()).isEqualTo(first.version());
        assertThat(versions.count()).isEqualTo(1);
        var next = store.synchronize(List.of(tool("changed", Map.of())), Set.of("server")).get(0);
        assertThat(next.capabilityId()).isEqualTo(first.capabilityId());
        assertThat(next.version()).isNotEqualTo(first.version());
        assertThat(versions.count()).isEqualTo(2);
        assertThat(versions.findById(first.capabilityId() + ":" + first.version()).orElseThrow()
            .getManifestJson()).contains("first").doesNotContain("changed");
        assertThat(registry.findById(first.capabilityId()).orElseThrow().getCurrentVersion()).isEqualTo(next.version());
    }
    @Test void retirementRequiresAnAuthoritativeServiceSnapshotAndPreservesChildRoute() {
        var source = tool("dynamic", Map.of(McpDynamicCapabilityRoute.METADATA_KEY,
            McpDynamicCapabilityRoute.parentDelegation("parent", "_child").toMetadata()));
        var manifest = store.synchronize(List.of(source), Set.of("server")).get(0);
        store.synchronize(List.of(), Set.of()); // outage is not tool deletion
        assertThat(registry.findById(manifest.capabilityId()).orElseThrow().getStatus()).isEqualTo("PUBLISHED");
        store.synchronize(List.of(), Set.of("server"));
        var retired = registry.findById(manifest.capabilityId()).orElseThrow();
        assertThat(retired.getStatus()).isEqualTo("RETIRED");
        assertThat(versions.findById(retired.getCapabilityId() + ":" + retired.getCurrentVersion()).orElseThrow()
            .getManifestJson()).contains("parent", "_child", "RETIRED");
        store.synchronize(List.of(source), Set.of("server"));
        assertThat(registry.findById(manifest.capabilityId()).orElseThrow().getStatus()).isEqualTo("PUBLISHED");
        assertThat(versions.count()).isEqualTo(2); // content-addressed rollback reuses the original immutable snapshot
    }
    @Test void manifestUsesActualSchemasAndDeclaredCapabilitiesWithoutTransportSecrets() {
        var manifest = store.synchronize(List.of(tool("actual", Map.of("serviceToken", "secret",
            "capabilityManifest", Map.of("operations", List.of("read"), "storage", "internal",
                "apiKey", "secret")))), Set.of("server")).get(0);
        assertThat(manifest.manifestVersion()).isEqualTo("capability-manifest.v1");
        assertThat(manifest.contract().get("inputSchema")).isEqualTo(tool("", Map.of()).inputSchema());
        assertThat(manifest.publisherCapabilities()).containsEntry("operations", List.of("read"))
            .doesNotContainKeys("apiKey", "storage", "serviceToken");
    }
    @Test void hashDoesNotDependOnMapInsertionOrder() {
        var factory = new CapabilityManifestFactory(new ObjectMapper());
        Map<String, Object> one = new LinkedHashMap<>(); one.put("operations", List.of("read")); one.put("name", "data");
        Map<String, Object> two = new LinkedHashMap<>(); two.put("name", "data"); two.put("operations", List.of("read"));
        assertThat(factory.generate(tool("actual", Map.of("capabilityManifest", one)), "PUBLISHED").version())
            .isEqualTo(factory.generate(tool("actual", Map.of("capabilityManifest", two)), "PUBLISHED").version());
    }
    @Test void emptyOptionalRouteInTransportDescriptorIsNotADynamicChild() {
        var result = store.synchronize(List.of(tool("standalone", Map.of(
            McpDynamicCapabilityRoute.METADATA_KEY, Map.of()))), Set.of("server")).get(0);
        assertThat(result.provider()).doesNotContainKey("parentToolName");
    }
}
