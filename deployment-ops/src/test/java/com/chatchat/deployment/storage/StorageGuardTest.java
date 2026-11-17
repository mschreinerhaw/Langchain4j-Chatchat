package com.chatchat.deployment.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class StorageGuardTest {
    @Test void rejectsExternalOrRedirectedDatasource(){
        for(String url:List.of("jdbc:postgresql://localhost/business","jdbc:h2:mem:test","jdbc:h2:file:/tmp/deployment-ops")){
            var environment=new org.springframework.mock.env.MockEnvironment().withProperty("spring.datasource.url",url).withProperty("spring.datasource.driver-class-name","org.h2.Driver");
            assertThrows(IllegalArgumentException.class,()->StorageGuard.validateDatasource(environment));
        }
    }
    @TempDir Path temporary;
    @Test void rejectsDirectoryOnRootFilesystem() {
        assertThrows(java.io.IOException.class, () -> StorageGuard.validate(temporary, temporary.resolve("data"), List.of("1 0 8:0 / / rw - xfs /dev/root rw")));
    }
    @Test void acceptsMountedDataAndRejectsEscape() throws Exception {
        Path mount = temporary.toRealPath();
        List<String> info = List.of("1 0 8:0 / " + mount + " rw - xfs /dev/data rw");
        assertDoesNotThrow(() -> StorageGuard.validate(mount, mount.resolve("data"), info));
        assertThrows(java.io.IOException.class, () -> StorageGuard.validate(mount, mount.resolve("../escape"), info));
        assertThrows(java.io.IOException.class, () -> StorageGuard.validate(mount, mount, info));
        if (!System.getProperty("os.name").startsWith("Windows")) {
            Files.createSymbolicLink(mount.resolve("link"), Path.of("/tmp"));
            assertThrows(java.io.IOException.class, () -> StorageGuard.validate(mount, mount.resolve("link/data"), info));
        }
    }
}
