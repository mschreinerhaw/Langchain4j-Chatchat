package com.chatchat.mcpserver.news.admin;

import com.chatchat.mcpserver.news.financial.FinancialQueryCacheConfig;
import com.chatchat.mcpserver.news.financial.FinancialQueryCacheConfigService;
import com.chatchat.mcpserver.news.financial.FinancialQueryCacheService;

import com.chatchat.mcpserver.cache.redis.RedisCacheConfig;
import com.chatchat.mcpserver.cache.redis.RedisCacheConfigService;
import com.chatchat.mcpserver.cache.redis.RedisCacheStore;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FinancialQueryCacheAdminControllerTest {

    @Test
    void savesEditableRocksDbPolicy() {
        FinancialQueryCacheConfigService configService = mock(FinancialQueryCacheConfigService.class);
        FinancialQueryCacheService cacheService = mock(FinancialQueryCacheService.class);
        FinancialQueryCacheAdminController controller = new FinancialQueryCacheAdminController(configService,
            cacheService, mock(RedisCacheConfigService.class), mock(RedisCacheStore.class));
        ArgumentCaptor<FinancialQueryCacheConfig> saved = ArgumentCaptor.forClass(FinancialQueryCacheConfig.class);
        when(configService.save(saved.capture())).thenAnswer(invocation -> invocation.getArgument(0));

        var response = controller.save(new FinancialQueryCacheAdminController.ConfigRequest(
            true, "ROCKSDB", 3600L, false, 4096, 900L));

        assertThat(response.getData().storage()).isEqualTo("ROCKSDB");
        assertThat(response.getData().ttlSeconds()).isEqualTo(3600L);
        assertThat(saved.getValue().isEnabled()).isTrue();
        assertThat(saved.getValue().getMaxEntryKb()).isEqualTo(4096);
    }

    @Test
    void rejectsRedisPolicyWhenSharedRedisStorageIsDisabled() {
        FinancialQueryCacheConfigService configService = mock(FinancialQueryCacheConfigService.class);
        RedisCacheConfigService redisConfigService = mock(RedisCacheConfigService.class);
        RedisCacheConfig redisConfig = new RedisCacheConfig();
        redisConfig.setEnabled(false);
        when(redisConfigService.current()).thenReturn(redisConfig);
        FinancialQueryCacheAdminController controller = new FinancialQueryCacheAdminController(configService,
            mock(FinancialQueryCacheService.class), redisConfigService, mock(RedisCacheStore.class));

        assertThatThrownBy(() -> controller.save(new FinancialQueryCacheAdminController.ConfigRequest(
            true, "REDIS", 1800L, true, 2048, 500L)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Redis cache storage must be enabled");
        verify(configService, org.mockito.Mockito.never()).save(org.mockito.ArgumentMatchers.any());
    }
}
