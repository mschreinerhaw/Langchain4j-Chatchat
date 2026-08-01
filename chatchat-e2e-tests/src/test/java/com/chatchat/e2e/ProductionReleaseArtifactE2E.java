package com.chatchat.e2e;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.assertj.core.api.Assertions.assertThat;

class ProductionReleaseArtifactE2E {

    private static final Pattern TENCENT_SECRET = Pattern.compile(
        "(?i)(AKID[0-9A-Za-z]{12,}|secret(key|id)\\s*[:=]\\s*[0-9A-Za-z/+]{16,})");

    @Test
    void executableApplicationsAndReleaseArchivesAreCompleteAndCredentialFree() throws Exception {
        Path root = repositoryRoot();
        assertExecutableJar(findSingle(root.resolve("chatchat-api/target"), "chatchat-api-", ".jar"));
        assertExecutableJar(findSingle(root.resolve("chatchat-mcp-server/target"), "chatchat-mcp-server-", ".jar"));
        assertExecutableJar(findSingle(root.resolve("chatchat-runtime-news/target"), "chatchat-runtime-news-", ".jar"));

        assertReleaseZip(findSingle(root.resolve("chatchat-api/target"), "chatchat-api-", "-release.zip"),
            "lib/app/chatchat.jar", "config/application.yml", "bin/start.sh", "bin/start.ps1");
        assertReleaseZip(findSingle(root.resolve("chatchat-mcp-server/target"), "chatchat-mcp-server-", "-release.zip"),
            "lib/app/chatchat-mcp-server.jar", "config/application-prod.yml", "bin/start.sh", "bin/start.ps1");
        assertReleaseZip(findSingle(root.resolve("chatchat-runtime-news/target"), "chatchat-runtime-news-", "-release.zip"),
            "lib/chatchat-runtime-news.jar", "config/application.yml", "bin/start.sh", "bin/start.ps1");
    }

    private void assertExecutableJar(Path jarPath) throws IOException {
        assertThat(Files.size(jarPath)).isGreaterThan(1_000_000);
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            Attributes attributes = jar.getManifest().getMainAttributes();
            assertThat(attributes.getValue("Main-Class")).isNotBlank();
            assertThat(jar.getEntry("BOOT-INF/classes/")).isNotNull();
        }
    }

    private void assertReleaseZip(Path archive, String... requiredSuffixes) throws IOException {
        assertThat(Files.size(archive)).isGreaterThan(1_000_000);
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            List<? extends ZipEntry> entries = zip.stream().toList();
            for (String suffix : requiredSuffixes) {
                assertThat(entries).anySatisfy(entry -> assertThat(entry.getName()).endsWith(suffix));
            }
            for (ZipEntry entry : entries) {
                if (!entry.isDirectory() && entry.getName().matches(".*\\.(yml|yaml|properties|env|sh|ps1|bat)$")) {
                    try (InputStream input = zip.getInputStream(entry)) {
                        String text = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                        assertThat(TENCENT_SECRET.matcher(text).find())
                            .as("plaintext Tencent credential in %s!%s", archive.getFileName(), entry.getName())
                            .isFalse();
                    }
                }
            }
        }
    }

    private Path findSingle(Path target, String prefix, String suffix) throws IOException {
        try (var paths = Files.list(target)) {
            List<Path> matches = paths
                .filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().startsWith(prefix))
                .filter(path -> path.getFileName().toString().endsWith(suffix))
                .filter(path -> !path.getFileName().toString().endsWith("-plain.jar"))
                .toList();
            assertThat(matches).as("artifact %s*%s", prefix, suffix).hasSize(1);
            return matches.get(0);
        }
    }

    private Path repositoryRoot() {
        String configured = System.getProperty("chatchat.e2e.repository-root", "");
        Path root = configured.isBlank()
            ? Path.of("").toAbsolutePath().normalize().getParent()
            : Path.of(configured).toAbsolutePath().normalize();
        assertThat(root.resolve("pom.xml")).isRegularFile();
        return root;
    }
}
