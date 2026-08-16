package com.chatchat.chat.uiartifact;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TrendSemanticConfigServiceTest {

    private TrendSemanticConfigRepository repository;
    private TrendSemanticKeywordRepository keywordRepository;
    private TrendSemanticKeywordSchemaMigrator keywordSchemaMigrator;
    private TrendSemanticConfigService service;

    @BeforeEach
    void setUp() {
        repository = mock(TrendSemanticConfigRepository.class);
        keywordRepository = mock(TrendSemanticKeywordRepository.class);
        keywordSchemaMigrator = mock(TrendSemanticKeywordSchemaMigrator.class);
        service = new TrendSemanticConfigService(repository, keywordRepository, keywordSchemaMigrator);
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(keywordRepository.findByTenantIdOrderBySortOrderAscKeywordAsc(any())).thenReturn(List.of());
    }

    @Test
    void createsAndReturnsDatabaseBackedGlobalDefaults() {
        when(repository.findById("tenant-a")).thenReturn(Optional.empty());
        when(repository.findById(TrendSemanticConfigService.GLOBAL_TENANT_ID)).thenReturn(Optional.empty());

        var config = service.get("tenant-a");

        assertThat(config.scope()).isEqualTo("GLOBAL");
        assertThat(config.rulesetVersion()).isEqualTo(TrendSemanticConfigService.FINANCE_RULESET_VERSION);
        assertThat(config.keywords()).contains(
            "盈亏", "浮动盈亏", "收益率", "超额收益", "净流入", "市值变动",
            "利润增速", "回撤变化", "change", "pnl", "roi", "net inflow"
        );
        assertThat(config.upColor()).isEqualTo("#e5484d");
    }

    @Test
    void upgradesAndPersistsAnExistingGlobalFinanceRulesetWithoutLosingCustomKeywords() {
        TrendSemanticConfigEntity legacy = new TrendSemanticConfigEntity();
        legacy.setTenantId(TrendSemanticConfigService.GLOBAL_TENANT_ID);
        legacy.setUpColor("#e5484d");
        legacy.setDownColor("#16a36a");
        legacy.setNeutralColor("#98a2b3");
        legacy.setRulesetVersion(1);
        legacy.setRevision(4);
        when(repository.findById("tenant-a")).thenReturn(Optional.empty());
        when(repository.findById(TrendSemanticConfigService.GLOBAL_TENANT_ID)).thenReturn(Optional.of(legacy));
        List<TrendSemanticKeywordEntity> upgraded = TrendSemanticConfigService.DEFAULT_KEYWORDS.stream()
            .map(value -> keyword(value, 0))
            .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
        upgraded.add(1, keyword("客户自定义利差", 1));
        when(keywordRepository.findByTenantIdOrderBySortOrderAscKeywordAsc(TrendSemanticConfigService.GLOBAL_TENANT_ID))
            .thenReturn(List.of(keyword("盈亏", 0), keyword("客户自定义利差", 1)), upgraded);

        var config = service.get("tenant-a");

        assertThat(config.rulesetVersion()).isEqualTo(TrendSemanticConfigService.FINANCE_RULESET_VERSION);
        assertThat(config.revision()).isEqualTo(5);
        assertThat(config.keywords()).contains("客户自定义利差", "涨跌额", "净流入", "roi");
    }

    @Test
    void storesNormalizedTenantOverride() {
        when(repository.findById("tenant-a")).thenReturn(Optional.empty());

        var config = service.update("tenant-a", new TrendSemanticConfigService.UpdateRequest(
            List.of(" 净收益 ", "NET PROFIT", "净收益"), "#FF0000", "#00AA55", "#777777"
        ));

        assertThat(config.scope()).isEqualTo("TENANT");
        assertThat(config.rulesetVersion()).isEqualTo(TrendSemanticConfigService.FINANCE_RULESET_VERSION);
        assertThat(config.keywords()).containsExactly("净收益", "net profit");
        assertThat(config.upColor()).isEqualTo("#ff0000");
        assertThat(config.downColor()).isEqualTo("#00aa55");
        verify(keywordRepository).deleteByTenantId("tenant-a");
        verify(keywordRepository).saveAll(any());
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

    private TrendSemanticKeywordEntity keyword(String value, int sortOrder) {
        TrendSemanticKeywordEntity keyword = new TrendSemanticKeywordEntity();
        keyword.setTenantId(TrendSemanticConfigService.GLOBAL_TENANT_ID);
        keyword.setKeyword(value);
        keyword.setSortOrder(sortOrder);
        return keyword;
    }
}
