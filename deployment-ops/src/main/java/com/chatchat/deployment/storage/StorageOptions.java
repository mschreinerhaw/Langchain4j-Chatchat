package com.chatchat.deployment.storage;

import java.nio.file.Path;

/** Storage locations belong to an installation, not to a particular server. */
public final class StorageOptions {
    private StorageOptions() {}
    public static String value(String key,String fallback){return System.getProperty(key,System.getenv().getOrDefault(key,fallback));}
    public static Path data(){return Path.of(value("OPS_DATA_DIR","/opt/deployment-ops/data")).toAbsolutePath().normalize();}
    public static Path state(){return data().getParent();}
    public static Path runtime(){
        String selected=value("OPS_RUNTIME_DIR","");
        if(!selected.isBlank())return Path.of(selected).toAbsolutePath().normalize();
        Path registered=state().resolve("standalone/instance.properties");
        if(java.nio.file.Files.isRegularFile(registered)){
            java.util.Properties properties=new java.util.Properties();try(var in=java.nio.file.Files.newInputStream(registered)){properties.load(in);}catch(java.io.IOException failure){throw new IllegalStateException("Cannot read this instance's registered storage location",failure);}
            selected=properties.getProperty("OPS_RUNTIME_DIR","");if(!selected.isBlank())return Path.of(selected).toAbsolutePath().normalize();
            // Compatibility only for an existing old installation, never for a fresh directory.
            if(data().startsWith(Path.of("/mnt/disk01").toAbsolutePath().normalize()))return Path.of("/mnt/disk01/ops/r").toAbsolutePath().normalize();
        }
        return state().resolve("r");
    }
    public static String requiredMount(){return value("OPS_REQUIRED_MOUNT","");}
}
