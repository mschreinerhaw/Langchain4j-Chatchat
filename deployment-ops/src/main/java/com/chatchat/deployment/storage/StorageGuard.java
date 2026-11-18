package com.chatchat.deployment.storage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.List;

/** Runs before Spring can open H2, including when launched directly with java -jar. */
public final class StorageGuard {
    private static volatile boolean validatedProcess;
    private StorageGuard() {}

    private static Object dataDevice;
    public static Path dataDirectory(){return StorageOptions.data();}
    public static Path runtimeDirectory(){return StorageOptions.runtime();}
    public static synchronized void verify() throws IOException {
        Path data=StorageOptions.data();checkPolicy(data);
        if(validatedProcess){if(!Files.isDirectory(data)||!Files.getAttribute(data,"unix:dev").equals(dataDevice))throw new IOException("Selected data filesystem disappeared or changed; refusing storage fallback");return;}
        createManaged(data);if(!Files.isWritable(data))throw new IOException("Selected data directory is not writable: "+data);
        probeWritable(data);
        if(Files.getFileStore(data).getUsableSpace()<64L*1024*1024)throw new IOException("Insufficient free space for platform initialization");
        Files.setPosixFilePermissions(data,java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));dataDevice=Files.getAttribute(data,"unix:dev");validatedProcess=true;
    }
    public static void verifyRuntime()throws IOException{if(validatedProcess)verify();}
    public static Path managedDirectory(Path path)throws IOException{
        if(validatedProcess)verify();return createManaged(path);
    }
    private static Path createManaged(Path path)throws IOException{
        Path absolute=path.toAbsolutePath().normalize();
        if(!absolute.startsWith(StorageOptions.state())&&!absolute.startsWith(StorageOptions.runtime()))throw new IOException("Directory is outside the selected platform/application storage roots: "+absolute);
        checkPolicy(absolute);Files.createDirectories(absolute);return absolute.toRealPath();
    }
    public static void probeWritable(Path directory)throws IOException{
        Path probe=Files.createTempFile(directory,".ops-storage-probe-",".tmp");
        try{Files.writeString(probe,"storage-check");}finally{Files.deleteIfExists(probe);}
    }
    public static void noSymlinks(Path path)throws IOException{
        Path absolute=path.toAbsolutePath().normalize(),part=absolute.getRoot();
        for(Path item:absolute){part=part.resolve(item);if(Files.isSymbolicLink(part))throw new IOException("Storage symlink is not permitted: "+part);}
    }
    public static Path inputFile(Path path)throws IOException{
        Path absolute=path.toAbsolutePath().normalize();noSymlinks(absolute);
        if(!Files.isRegularFile(absolute,java.nio.file.LinkOption.NOFOLLOW_LINKS)||!Files.isReadable(absolute))throw new IOException("Readable regular offline file required: "+absolute);return absolute;
    }
    public static String mountPoint(Path path)throws IOException{
        Path existing=path.toAbsolutePath().normalize();while(!Files.exists(existing))existing=existing.getParent();
        Path real=existing.toRealPath();String best="/";
        for(String line:Files.readAllLines(Path.of("/proc/self/mountinfo"))){String[] f=line.split(" ");if(f.length>6){String m=decode(f[4]);if(real.startsWith(Path.of(m))&&m.length()>best.length())best=m;}}return best;
    }
    private static boolean sameFilesystemAtAnchor(Path path,String configured)throws IOException{
        Path anchor=Path.of(configured).toAbsolutePath().normalize();
        boolean present=Files.readAllLines(Path.of("/proc/self/mountinfo")).stream().map(line->line.split(" ")).anyMatch(f->f.length>6&&decode(f[4]).equals(anchor.toString()));
        if(!present||!Files.exists(anchor))return false;
        Path existing=path;while(!Files.exists(existing))existing=existing.getParent();
        return Files.getAttribute(existing.toRealPath(),"unix:dev").equals(Files.getAttribute(anchor,"unix:dev"));
    }
    public static void checkPolicy(Path path)throws IOException{
        Path absolute=path.toAbsolutePath().normalize();noSymlinks(absolute);
        String required=StorageOptions.requiredMount();
        if(!required.isBlank()){
            Path mount=Path.of(required).toAbsolutePath().normalize();validate(mount,absolute,Files.readAllLines(Path.of("/proc/self/mountinfo")));
            if(Files.getAttribute(mount,"unix:dev").equals(Files.getAttribute(Path.of("/"),"unix:dev")))throw new IOException("Configured dedicated mount is backed by the system filesystem");
        }
        String expected=StorageOptions.value("OPS_STORAGE_MOUNT","");
        if(!expected.isBlank()&&absolute.startsWith(StorageOptions.state())&&!sameFilesystemAtAnchor(absolute,expected))throw new IOException("Selected data mount is missing or changed: "+expected);
        String runtimeMount=StorageOptions.value("OPS_RUNTIME_MOUNT","");
        if(!runtimeMount.isBlank()&&absolute.startsWith(StorageOptions.runtime())&&!sameFilesystemAtAnchor(absolute,runtimeMount))throw new IOException("Selected application mount is missing or changed: "+runtimeMount);
    }
    public static void validateDatasource(org.springframework.core.env.Environment environment){
        String directory=StorageOptions.data().toAbsolutePath().normalize().toString().replace('\\','/');
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
