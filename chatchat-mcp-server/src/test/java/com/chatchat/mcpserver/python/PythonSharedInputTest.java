package com.chatchat.mcpserver.python;
import com.chatchat.common.security.InternalCredentialProperties;
import com.chatchat.platform.runtime.coding.CodingSharedFiles;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
class PythonSharedInputTest {
    @TempDir Path temp;
    @Test void registrationVerifiesScopeAndBytesBeforeSandboxCopy() throws Exception {
        var policy=new PythonRuntimeProperties();policy.setDataRoot(temp.resolve("shared").toString());
        var files=new PythonDataFileService(policy,new InternalCredentialProperties(),new ObjectMapper());
        var store=new CodingSharedFiles(Path.of(policy.getDataRoot()));Path staged=Files.writeString(temp.resolve("staged"),"input bytes");
        String hash=CodingSharedFiles.hash(staged),id="c"+"a".repeat(63);
        store.publish("tenant","owner",id,"input.parquet",staged,hash,11);
        assertThatThrownBy(()->files.registerShared("tenant","owner",id,"input.parquet",hash,11)).hasMessageContaining("disabled");
        policy.setSharedInputsEnabled(true);assertThat(files.inputCapabilities()).contains("SHARED_DIRECTORY","PARQUET","CSV");
        assertThat(files.registerShared("tenant","owner",id,"input.parquet",hash,11).fileHash()).isEqualTo(hash);
        assertThatThrownBy(()->files.registerShared("other","owner",id,"input.parquet",hash,11)).hasMessageContaining("verification");
        assertThatThrownBy(()->files.registerShared("tenant","owner",id,"input.parquet","b".repeat(64),11)).hasMessageContaining("verification");
        var copied=files.snapshotAuthorized("tenant","owner",id,temp.resolve("operation-input"));
        assertThat(copied.sha256()).isEqualTo(hash);assertThat(Files.readString(temp.resolve("operation-input/input.parquet"))).isEqualTo("input bytes");
    }
}
