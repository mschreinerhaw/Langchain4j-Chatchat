package com.chatchat.deployment.storage;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class FlexibleStorageTest {
    @TempDir Path temporary;
    final Map<String,String> previous=new HashMap<>();
    final List<String> keys=List.of("OPS_DATA_DIR","OPS_RUNTIME_DIR","OPS_REQUIRED_MOUNT","OPS_STORAGE_MOUNT","OPS_RUNTIME_MOUNT");
    @BeforeEach void selectDirectory(){for(String key:keys){previous.put(key,System.getProperty(key));System.setProperty(key,"");}System.setProperty("OPS_DATA_DIR",temporary.resolve("platform/data").toString());System.setProperty("OPS_RUNTIME_DIR",temporary.resolve("applications").toString());}
    @AfterEach void restore(){for(String key:keys){if(previous.get(key)==null)System.clearProperty(key);else System.setProperty(key,previous.get(key));}}
    @Test void ordinaryWritableDirectoryNeedsNoDedicatedDisk() throws Exception {
        Path data=StorageGuard.managedDirectory(StorageOptions.data());assertTrue(Files.isDirectory(data));assertTrue(data.startsWith(temporary.toRealPath()));
    }
    @Test void applicationDirectoryCanBeSelectedIndependently() throws Exception {
        Path apps=StorageGuard.managedDirectory(StorageOptions.runtime());assertTrue(Files.isDirectory(apps));assertEquals(temporary.resolve("applications").toRealPath(),apps);
    }
    @Test void maintenanceCommandsRetainTheRegisteredApplicationDirectory() throws Exception {
        System.setProperty("OPS_RUNTIME_DIR", "");
        Path registration=StorageOptions.state().resolve("standalone/instance.properties");
        Files.createDirectories(registration.getParent());
        Properties properties=new Properties();properties.setProperty("OPS_RUNTIME_DIR",temporary.resolve("previous-applications").toString());
        try(var out=Files.newOutputStream(registration)){properties.store(out,"selected storage");}
        assertEquals(temporary.resolve("previous-applications"),StorageOptions.runtime());
    }
    @Test void aFreshDirectoryUsesItsOwnApplicationRoot(){
        System.setProperty("OPS_RUNTIME_DIR", "");
        assertEquals(StorageOptions.state().resolve("r"),StorageOptions.runtime());
    }
    @Test void managedWritesCannotEscapeTheSelectedRoots(){assertThrows(java.io.IOException.class,()->StorageGuard.managedDirectory(temporary.resolve("unselected")));}
    @Test void normalFileImportsCanComeFromOutsideThePlatformDirectory() throws Exception {
        Path media=temporary.resolve("offline.zip");Files.writeString(media,"offline");assertEquals(media.toRealPath(),StorageGuard.inputFile(media));assertThrows(java.io.IOException.class,()->StorageGuard.inputFile(temporary));
    }
    @Test void unsafeAndRelativeInstallationPathsAreRejected(){for(String path:List.of("relative/data","/opt/app/../other","/etc/service/data","/tmp/service/data","/","/proc/app/data"))assertThrows(Exception.class,()->StorageBootstrap.safeLocation(path),path);}
}
