package com.chatchat.api.datascience.skill;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DomainSkillRemoteImporterTest {

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void downloadsBoundedMarkdownFromPublicHttpAddress() throws Exception {
        HttpClient client = mock(HttpClient.class);
        HttpResponse response = mock(HttpResponse.class);
        byte[] content = "# Public Skill\n\nInstructions.".getBytes(StandardCharsets.UTF_8);
        when(response.statusCode()).thenReturn(200);
        when(response.headers()).thenReturn(HttpHeaders.of(Map.of(
            "content-type", List.of("text/markdown"),
            "content-length", List.of(String.valueOf(content.length))), (a, b) -> true));
        when(response.body()).thenReturn(new ByteArrayInputStream(content));
        when(client.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(response);

        var file = new DomainSkillRemoteImporter(client)
            .download("https://93.184.216.34/skills/SKILL.md");

        assertThat(file.fileName()).isEqualTo("SKILL.md");
        assertThat(file.bytes()).isEqualTo(content);
    }

    @Test
    void rejectsLocalAndNonHttpAddressesBeforeConnecting() throws Exception {
        HttpClient client = mock(HttpClient.class);
        DomainSkillRemoteImporter importer = new DomainSkillRemoteImporter(client);

        assertThatThrownBy(() -> importer.download("http://127.0.0.1/SKILL.md"))
            .hasMessageContaining("local or private");
        assertThatThrownBy(() -> importer.download("file:///tmp/SKILL.md"))
            .hasMessageContaining("public HTTP or HTTPS");
        verify(client, never()).send(any(), any());
    }
}
