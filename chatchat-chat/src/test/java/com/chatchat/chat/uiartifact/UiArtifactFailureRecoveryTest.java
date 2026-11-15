package com.chatchat.chat.uiartifact;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class UiArtifactFailureRecoveryTest {
    @TempDir Path directory;

    @Test void fullStorageHasActionableCauseAndRetryUsesTheSamePathContract() {
        var properties=new UiArtifactProperties();properties.setStoragePath(directory.toString());
        var store=new FilesystemArtifactBlobStore(new ObjectMapper(),properties);
        var location=new ArtifactLocation("tenant","artifact","resources/answer.json");
        var metadata=new ArtifactObjectMetadata("application/json",2,"",Map.of());
        var full=new FileSystemException("private-host-path",null,"No space left on device");
        var unavailable=new InputStream(){@Override public int read() throws IOException {throw full;}};
        assertThatThrownBy(()->store.put(location,unavailable,metadata))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("ARTIFACT_STORAGE_FULL")
            .hasMessageNotContaining("private-host-path").hasCause(full);
        assertThat(store.exists(location)).isFalse();
        store.put(location,new ByteArrayInputStream("{}".getBytes()),metadata);
        assertThat(store.get(location)).hasValueSatisfying(content->{
            try(var input=content.stream()){assertThat(new String(input.readAllBytes())).isEqualTo("{}");}
            catch(IOException e){throw new AssertionError(e);}
        });
    }

    @Test void cleanupFailureCannotMaskThePublicationFailureOrMarkItActive() {
        var store=mock(ArtifactBlobStore.class);var repo=mock(UiArtifactRepository.class);
        when(repo.save(any(UiArtifactEntity.class))).thenAnswer(i->i.getArgument(0));
        var original=new IllegalStateException("ARTIFACT_STORAGE_FULL");
        doThrow(original).when(store).put(any(),any(),any());
        doThrow(new IllegalStateException("cleanup unavailable")).when(store).delete(any());
        var service=new UiArtifactService(store,repo,new UiArtifactProperties(),new ObjectMapper());
        assertThatThrownBy(()->service.externalizeIfNeeded("tenant","task",Map.of("answer","Preserved report"))).isSameAs(original);
        assertThat(original.getSuppressed()).isNotEmpty();
        verify(repo,atLeastOnce()).save(argThat(e->"FAILED".equals(e.getStatus())));
    }
}
