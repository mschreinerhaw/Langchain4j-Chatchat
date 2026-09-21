package com.chatchat.mcpserver.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpToolChineseAliasResolverTest {

    @Test
    void readsEditableAliasFromDatabase() {
        McpToolAliasRepository repository = mock(McpToolAliasRepository.class);
        when(repository.findById("name:calculator"))
            .thenReturn(Optional.of(new McpToolAlias("name:calculator", "数据库维护的计算器")));
        McpToolChineseAliasResolver resolver = new McpToolChineseAliasResolver(repository, new ObjectMapper());
        try {
            assertThat(McpToolChineseAliasResolver.resolve("calculator", "原始中文标题", null))
                .isEqualTo("数据库维护的计算器");
        } finally {
            resolver.close();
        }
    }

    @Test
    void seedDoesNotOverwriteExistingDatabaseAlias() throws Exception {
        McpToolAliasRepository repository = mock(McpToolAliasRepository.class);
        when(repository.existsById("name:calculator")).thenReturn(true);
        McpToolChineseAliasResolver resolver = new McpToolChineseAliasResolver(repository, new ObjectMapper());
        try {
            resolver.seedDefaults();
            verify(repository, never()).save(argThat(alias -> "name:calculator".equals(alias.getLookupKey())));
            verify(repository, atLeastOnce()).save(any(McpToolAlias.class));
        } finally {
            resolver.close();
        }
    }
}
