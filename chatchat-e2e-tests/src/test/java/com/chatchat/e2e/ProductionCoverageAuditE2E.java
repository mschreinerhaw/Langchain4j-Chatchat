package com.chatchat.e2e;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Structural release audit: new production modules and HTTP controllers cannot be unowned by tests. */
@EnabledIfSystemProperty(named = "chatchat.e2e.coverage-audit.strict", matches = "true")
class ProductionCoverageAuditE2E {
    private static final Pattern MODULE = Pattern.compile("<module>([^<]+)</module>");
    private static final Pattern CONTROLLER = Pattern.compile(
        "(?s)@(RestController|Controller).*?(?:public\\s+)?class\\s+([A-Za-z0-9_]+)");

    @Test
    void everyProductionModuleAndControllerHasOwnedTestEvidence() throws Exception {
        Path root = repositoryRoot();
        Set<String> modules = modules(root);
        String allTestSource = testSource(root);
        List<String> modulesWithoutTests = new ArrayList<>();
        List<String> controllersWithoutEvidence = new ArrayList<>();

        for (String module : modules) {
            Path main = root.resolve(module).resolve("src/main/java");
            if (!Files.isDirectory(main)) continue;
            Path tests = root.resolve(module).resolve("src/test/java");
            if (!Files.isDirectory(tests) || javaFiles(tests).isEmpty()) {
                modulesWithoutTests.add(module);
            }
            for (Path source : javaFiles(main)) {
                String code = Files.readString(source);
                Matcher matcher = CONTROLLER.matcher(code);
                if (matcher.find() && !allTestSource.contains(matcher.group(2))) {
                    controllersWithoutEvidence.add(root.relativize(source).toString());
                }
            }
        }

        assertThat(modulesWithoutTests)
            .as("production modules without owned test source")
            .isEmpty();
        assertThat(controllersWithoutEvidence)
            .as("HTTP controllers never referenced by any test; add behavioral contract evidence")
            .isEmpty();
    }

    @Test
    void releaseSuiteContainsRealTopologyAndCriticalCrossCuttingEvidence() throws IOException {
        Path root = repositoryRoot();
        String tests = testSource(root);
        assertThat(tests).contains(
            "ProductionDeployedTopologyE2E",
            "ProductionReleaseArtifactE2E",
            "ProductionExtremeReasoningAndTemplateResultE2E",
            "TemplateExtremeResultReleaseTest",
            "EnterpriseAdminServiceIntegrationTest",
            "AgentAuthorizationIsolationTest",
            "ApiUserRoleScheduleMcpAuthorizationIntegrationTest",
            "DefaultAgentRuntimeConcurrencyTest",
            "McpCenterRecoveryServiceTest",
            "SqlSafetyServiceTest",
            "TencentWsaInferenceE2E",
            "NewsSchemaCompatibilityMigratorTest",
            "SqlDatasourceSchemaMigratorTest"
        );
    }

    private Set<String> modules(Path root) throws IOException {
        Matcher matcher = MODULE.matcher(Files.readString(root.resolve("pom.xml")));
        Set<String> modules = new LinkedHashSet<>();
        while (matcher.find()) modules.add(matcher.group(1).trim());
        return modules;
    }

    private String testSource(Path root) throws IOException {
        StringBuilder text = new StringBuilder();
        for (String module : modules(root)) {
            Path tests = root.resolve(module).resolve("src/test/java");
            for (Path file : javaFiles(tests)) text.append(Files.readString(file)).append('\n');
        }
        return text.toString();
    }

    private List<Path> javaFiles(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) return List.of();
        try (Stream<Path> paths = Files.walk(directory)) {
            return paths.filter(path -> path.toString().endsWith(".java")).toList();
        }
    }

    private Path repositoryRoot() {
        return Path.of(System.getProperty("chatchat.e2e.repository-root", "."))
            .toAbsolutePath().normalize();
    }
}
