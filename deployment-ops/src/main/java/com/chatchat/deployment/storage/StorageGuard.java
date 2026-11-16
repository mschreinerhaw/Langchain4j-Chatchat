package com.chatchat.deployment.storage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.List;

/** Runs before Spring can open H2, including when launched directly with java -jar. */
public final class StorageGuard {
    private StorageGuard() {}

    public static void verify() throws IOException {
        Path mount = Path.of("/mnt/disk01");
        Path data = Path.of(System.getenv().getOrDefault("OPS_DATA_DIR", "/mnt/disk01/deployment-ops/data"));
        try {
            validate(mount, data, Files.readAllLines(Path.of("/proc/self/mountinfo")));
            Files.createDirectories(data);
        } catch(IOException error) {
            throw new IOException("Required real storage mount /mnt/disk01 is unavailable or invalid; refusing startup without system-disk fallback",error);
        }
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
