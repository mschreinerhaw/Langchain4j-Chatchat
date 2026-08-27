package com.chatchat.chat.contract;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationPackageArchitectureTest {

    @Test
    void conversationRootContainsNoConcreteTypes() throws IOException {
        Path conversationRoot = mainJavaRoot().resolve("com/chatchat/chat/conversation");
        Set<String> rootSources;
        try (var paths = Files.list(conversationRoot)) {
            rootSources = paths
                .filter(path -> path.toString().endsWith(".java"))
                .map(path -> path.getFileName().toString())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        }

        assertThat(rootSources)
            .as("conversation types must be placed in a functional child package")
            .containsExactly("package-info.java");
    }

    @Test
    void conversationModelsDoNotDependOnImplementationPackages() throws IOException {
        List<Path> violations;
        try (var paths = Files.walk(mainJavaRoot().resolve("com/chatchat/chat/conversation/model"))) {
            violations = paths
                .filter(path -> path.toString().endsWith(".java"))
                .filter(path -> {
                    String source = read(path);
                    return source.contains("com.chatchat.chat.conversation.service")
                        || source.contains("com.chatchat.chat.conversation.persistence")
                        || source.contains("com.chatchat.chat.conversation.store");
                })
                .toList();
        }

        assertThat(violations)
            .as("conversation domain models must remain independent from implementations")
            .isEmpty();
    }

    private Path mainJavaRoot() {
        Path moduleLocal = Path.of("src/main/java");
        return Files.isDirectory(moduleLocal)
            ? moduleLocal.toAbsolutePath().normalize()
            : Path.of("chatchat-chat/src/main/java").toAbsolutePath().normalize();
    }

    private String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot inspect source file " + path, exception);
        }
    }
}
