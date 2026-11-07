package com.chatchat.api.runtime.coding.nativecode;

import com.chatchat.common.runtime.coding.NativeCodeWorkspace;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** The API runtime must carry its trusted helper without its test-only MCP server dependency. */
class NativeExecutorRuntimeResourceTest {
    @org.junit.jupiter.api.io.TempDir Path temporary;
    @Test void helperLoadsInChildJvmWithMcpServerRemovedFromRuntimeClasspath()throws Exception{
        String classpath=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        String runtime=Arrays.stream(classpath.split(java.util.regex.Pattern.quote(File.pathSeparator)))
            .filter(entry->!entry.replace('\\','/').contains("/chatchat-mcp-server"))
            .collect(Collectors.joining(File.pathSeparator));
        assertThat(runtime).isNotEqualTo(classpath);
        var arguments=temporary.resolve("runtime-resource-probe.args");
        Files.writeString(arguments,"-Xmx128m\n-cp\n\""+runtime.replace('\\','/')+"\"\n"+ResourceProbe.class.getName()+"\n",StandardCharsets.UTF_8);
        var command=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),
            "@"+arguments).redirectErrorStream(true).start();
        boolean stopped=command.waitFor(15,TimeUnit.SECONDS);
        if(!stopped)command.destroyForcibly();
        assertThat(stopped).as("isolated resource probe stopped").isTrue();
        String output=new String(command.getInputStream().readAllBytes(),StandardCharsets.UTF_8).trim();
        assertThat(command.exitValue()).as(output).isZero();
        try(var source=NativeCodingCommandService.class.getResourceAsStream("/python/native_command.mjs")){
            assertThat(source).isNotNull();assertThat(output).isEqualTo(NativeCodeWorkspace.sha(source.readAllBytes()));
        }
    }
    public static final class ResourceProbe {
        public static void main(String[] ignored)throws Exception{
            try(var source=NativeCodingCommandService.class.getResourceAsStream("/python/native_command.mjs")){
                if(source==null)throw new IllegalStateException("API_RUNTIME_NATIVE_HELPER_MISSING");
                System.out.println(NativeCodeWorkspace.sha(source.readAllBytes()));
            }
        }
    }
}
