package com.chatchat.mcpserver.python;

import com.chatchat.common.security.InternalCredentialProperties;
import com.chatchat.common.security.InternalSecretCipher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;

class PythonDataFileServiceTest {
    @TempDir Path temp;
    @Test void storesInScopedDirectoryAndReturnsStableContainerPath()throws Exception{
        PythonRuntimeProperties properties=new PythonRuntimeProperties();properties.setDataRoot(temp.toString());
        InternalCredentialProperties credentials=mock(InternalCredentialProperties.class);when(credentials.resolvedSecret()).thenReturn("secret");
        PythonDataFileService service=new PythonDataFileService(properties,credentials,new ObjectMapper());byte[] content="name,amount\nA,12\n".getBytes(StandardCharsets.UTF_8);
        String name=Base64.getUrlEncoder().withoutPadding().encodeToString("交易.csv".getBytes(StandardCharsets.UTF_8));String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        var result=service.store("tenant_1","张三@example.com","file_1",name,hash,InternalSecretCipher.encryptBytes(content,"secret"));
        assertThat(result.pythonPath()).isEqualTo("/data/input/file_1/交易.csv");assertThat(Path.of(result.storagePath())).hasContent("name,amount\nA,12\n");
        try{assertThat(Files.getPosixFilePermissions(Path.of(result.storagePath()))).contains(PosixFilePermission.OTHERS_READ).doesNotContain(PosixFilePermission.OTHERS_WRITE);}catch(UnsupportedOperationException ignored){assertThat(Path.of(result.storagePath()).toFile().canRead()).isTrue();}
        try{assertThat(Files.getPosixFilePermissions(Path.of(result.storagePath()).getParent())).contains(PosixFilePermission.OTHERS_EXECUTE).doesNotContain(PosixFilePermission.OTHERS_WRITE);}catch(UnsupportedOperationException ignored){assertThat(Path.of(result.storagePath()).getParent().toFile().canExecute()).isTrue();}
        assertThat(service.resolveFileArguments("{\"type\":\"object\",\"properties\":{\"source_file\":{\"type\":\"FILE\"}}}",Map.of("source_file","file_1"),"tenant_1","张三@example.com")).containsEntry("source_file","/data/input/file_1/交易.csv");
        assertThat(service.resolveFileArguments("{\"type\":\"object\",\"properties\":{\"source_file\":{\"type\":\"FILE\"}}}",Map.of("source_file","/data/input/file_1/交易.csv"),"tenant_1","张三@example.com")).containsEntry("source_file","/data/input/file_1/交易.csv");
        assertThatThrownBy(()->service.resolveFileArguments("{\"type\":\"object\",\"properties\":{\"source_file\":{\"type\":\"FILE\"}}}",Map.of("source_file","/data/input/file_1/other.csv"),"tenant_1","张三@example.com")).isInstanceOf(IllegalArgumentException.class);
        assertThat(service.discover("tenant_1","张三@example.com","交易.csv",20))
            .extracting(PythonDataFileService.DataFileView::fileId).containsExactly("file_1");
        assertThat(service.discover("tenant_1","张三@example.com","请分析交易.csv文件",20))
            .extracting(PythonDataFileService.DataFileView::fileId).containsExactly("file_1");
        assertThat(service.discover("tenant_1","李四@example.com","交易.csv",20)).isEmpty();
        assertThat(InternalSecretCipher.decryptBytes(service.content("tenant_1","张三@example.com","file_1"),"secret")).isEqualTo(content);
        service.delete("tenant_1","张三@example.com","file_1");assertThat(Path.of(result.storagePath())).doesNotExist();
    }
    @Test void rejectsHashMismatchAndPathTraversal(){
        PythonRuntimeProperties properties=new PythonRuntimeProperties();properties.setDataRoot(temp.toString());InternalCredentialProperties credentials=mock(InternalCredentialProperties.class);when(credentials.resolvedSecret()).thenReturn("secret");PythonDataFileService service=new PythonDataFileService(properties,credentials,new ObjectMapper());
        byte[] content="x".getBytes(StandardCharsets.UTF_8);String traversal=Base64.getUrlEncoder().withoutPadding().encodeToString("../x.csv".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(()->service.store("tenant","user","file",traversal,"bad",InternalSecretCipher.encryptBytes(content,"secret"))).isInstanceOf(IllegalArgumentException.class);
    }
}
