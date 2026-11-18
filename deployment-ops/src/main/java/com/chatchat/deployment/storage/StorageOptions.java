package com.chatchat.deployment.storage;

import java.nio.file.Path;

/** Storage locations belong to an installation, not to a particular server. */
public final class StorageOptions {
    private StorageOptions() {}
    public static String value(String key,String fallback){return System.getProperty(key,System.getenv().getOrDefault(key,fallback));}
    public static Path data(){return Path.of(value("OPS_DATA_DIR","/opt/deployment-ops/data")).toAbsolutePath().normalize();}
    public static Path state(){return data().getParent();}
    public static Path runtime(){
        // Compatibility for existing installations; fresh installations use their selected state root.
        String fallback=data().startsWith(Path.of("/mnt/disk01"))?"/mnt/disk01/ops/r":state().resolve("r").toString();
        String selected=value("OPS_RUNTIME_DIR",fallback);return Path.of(selected.isBlank()?fallback:selected).toAbsolutePath().normalize();
    }
    public static String requiredMount(){return value("OPS_REQUIRED_MOUNT","");}
}
