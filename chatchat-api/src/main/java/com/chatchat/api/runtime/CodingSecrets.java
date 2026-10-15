package com.chatchat.api.runtime;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;

/** Deployment-owned secret material, never a model/task payload. */
final class CodingSecrets {
    private CodingSecrets() { }
    static String resolve(String inline, String file) {
        if (file==null || file.isBlank()) return inline;
        if (inline!=null && !inline.isBlank()) throw new IllegalArgumentException("Configure one Coding secret source");
        try {
            Path path=Path.of(file);
            if(!path.isAbsolute() || !Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS) || Files.size(path)>4096)
                throw new IllegalArgumentException("Invalid Coding secret file");
            return Files.readString(path,StandardCharsets.UTF_8).trim();
        } catch(java.io.IOException failure){throw new IllegalStateException("Coding secret file cannot be read",failure);}
    }
}
