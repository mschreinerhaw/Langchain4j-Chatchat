package com.chatchat.deployment.storage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.List;

/** Runs before Spring can open H2, including when launched directly with java -jar. */
public final class StorageGuard {
    private static volatile boolean validatedProcess;
    private StorageGuard() {}

    public static void verify() throws IOException {
        Path mount = Path.of("/mnt/disk01");
        Path data = Path.of(System.getenv().getOrDefault("OPS_DATA_DIR", "/mnt/disk01/deployment-ops/data"));
        try {
            validate(mount, data, Files.readAllLines(Path.of("/proc/self/mountinfo")));
            if(Files.getAttribute(mount,"unix:dev").equals(Files.getAttribute(Path.of("/"),"unix:dev")))throw new IOException("Data mount resolves to the system filesystem");
            managedDirectory(data);
            Files.setPosixFilePermissions(data,java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
            validatedProcess=true;
        } catch(IOException error) {
            throw new IOException("Required real storage mount /mnt/disk01 is unavailable or invalid; refusing startup without system-disk fallback",error);
        }
    }
    public static void verifyRuntime()throws IOException{if(validatedProcess)verify();}
    public static Path managedDirectory(Path path)throws IOException{
        Path mount=Path.of("/mnt/disk01"),absolute=path.toAbsolutePath().normalize();
        validate(mount,absolute,Files.readAllLines(Path.of("/proc/self/mountinfo")));
        Object systemDevice=Files.getAttribute(Path.of("/"),"unix:dev");
        if(Files.getAttribute(mount,"unix:dev").equals(systemDevice))throw new IOException("Data mount uses the system filesystem");
        Path ancestor=absolute;while(!Files.exists(ancestor))ancestor=ancestor.getParent();
        if(Files.getAttribute(ancestor.toRealPath(),"unix:dev").equals(systemDevice))throw new IOException("Data directory is backed by the system filesystem");
        Files.createDirectories(absolute);return absolute.toRealPath();
    }
    public static void validateDatasource(org.springframework.core.env.Environment environment){
        String directory=Path.of(System.getenv().getOrDefault("OPS_DATA_DIR","/mnt/disk01/deployment-ops/data")).toAbsolutePath().normalize().toString().replace('\\','/');
        String expected="jdbc:h2:file:"+directory+"/deployment-ops;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0";
        if(!expected.equals(environment.getProperty("spring.datasource.url"))||!"org.h2.Driver".equals(environment.getProperty("spring.datasource.driver-class-name")))throw new IllegalArgumentException("Only embedded H2 at the validated data directory is supported; external databases and path overrides are forbidden");
    }

    static void validate(Path mount, Path data, List<String> mountInfo) throws IOException {
        Path realMount = mount.toRealPath();
        boolean mounted = mountInfo.stream().map(line -> line.split(" "))
                .anyMatch(fields -> fields.length > 6 && decode(fields[4]).equals(realMount.toString()));
        if (!mounted || !realMount.equals(mount.toAbsolutePath().normalize())) {
            throw new IOException("Required real data mount is unavailable: " + mount);
        }
        Path absolute = data.toAbsolutePath().normalize();
        if (!absolute.startsWith(realMount) || absolute.equals(realMount)) {
            throw new IOException("Data directory must be below " + mount);
        }
        // Resolve every existing ancestor before creating anything; reject symlink escapes.
        Path ancestor = absolute;
        while (!Files.exists(ancestor)) ancestor = ancestor.getParent();
        if (!ancestor.toRealPath().startsWith(realMount)) {
            throw new IOException("Data directory resolves outside required mount");
        }
    }

    private static String decode(String value) {
        return value.replace("\\040", " ").replace("\\011", "\t")
                .replace("\\012", "\n").replace("\\134", "\\");
    }
}
