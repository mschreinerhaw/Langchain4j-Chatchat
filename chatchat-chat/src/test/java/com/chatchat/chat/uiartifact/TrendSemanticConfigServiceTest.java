package com.chatchat.chat.uiartifact;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TrendSemanticConfigServiceTest {

    private TrendSemanticConfigRepository repository;
    private TrendSemanticConfigService service;

    @BeforeEach
    void setUp() {
        repository = mock(TrendSemanticConfigRepository.class);
        service = new TrendSemanticConfigService(repository, new ObjectMapper());
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void createsAndReturnsDatabaseBackedGlobalDefaults() {
        when(repository.findById("tenant-a")).thenReturn(Optional.empty());
        when(repository.findById(TrendSemanticConfigService.GLOBAL_TENANT_ID)).thenReturn(Optional.empty());

        var config = service.get("tenant-a");

        assertThat(config.scope()).isEqualTo("GLOBAL");
        assertThat(config.keywords()).contains("盈亏", "change", "pnl");
        assertThat(config.upColor()).isEqualTo("#e5484d");
    }

    @Test
    void storesNormalizedTenantOverride() {
        when(repository.findById("tenant-a")).thenReturn(Optional.empty());

        var config = service.update("tenant-a", new TrendSemanticConfigService.UpdateRequest(
            List.of(" 净收益 ", "NET PROFIT", "净收益"), "#FF0000", "#00AA55", "#777777"
        ));

        assertThat(config.scope()).isEqualTo("TENANT");
        assertThat(config.keywords()).containsExactly("净收益", "net profit");
        assertThat(config.upColor()).isEqualTo("#ff0000");
        assertThat(config.downColor()).isEqualTo("#00aa55");
    }

    @Test
    void rejectsInvalidOrEmptyConfiguration() {
        assertThatThrownBy(() -> service.update("tenant-a", new TrendSemanticConfigService.UpdateRequest(
            List.of(), "#ff0000", "#00aa55", "#777777"
        ))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("至少配置一个");

        assertThatThrownBy(() -> service.update("tenant-a", new TrendSemanticConfigService.UpdateRequest(
            List.of("盈亏"), "red", "#00aa55", "#777777"
        ))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("#RRGGBB");
    }
}
