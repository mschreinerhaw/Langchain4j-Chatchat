package com.chatchat.mcpserver.category;

import com.chatchat.mcpserver.api.ApiServiceConfig;
import com.chatchat.mcpserver.api.ApiServiceConfigRepository;
import com.chatchat.mcpserver.database.DatabaseQueryConfig;
import com.chatchat.mcpserver.database.DatabaseQueryConfigRepository;
import com.chatchat.mcpserver.ops.HttpEndpointConfigRepository;
import com.chatchat.mcpserver.ops.HttpEndpointConfig;
import com.chatchat.mcpserver.ops.SshHostConfigRepository;
import com.chatchat.mcpserver.ops.SshHostConfig;
import com.chatchat.mcpserver.sql.SqlDatasourceConfigRepository;
import com.chatchat.mcpserver.sql.SqlDatasourceConfig;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BusinessCategoryServiceTest {

    private final BusinessCategoryRepository categories = mock(BusinessCategoryRepository.class);
    private final ApiServiceConfigRepository apiTemplates = mock(ApiServiceConfigRepository.class);
    private final DatabaseQueryConfigRepository databaseTemplates = mock(DatabaseQueryConfigRepository.class);
    private final SshHostConfigRepository sshAssets = mock(SshHostConfigRepository.class);
    private final SqlDatasourceConfigRepository databaseAssets = mock(SqlDatasourceConfigRepository.class);
    private final HttpEndpointConfigRepository apiAssets = mock(HttpEndpointConfigRepository.class);
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final BusinessCategoryService service = new BusinessCategoryService(
        categories, apiTemplates, databaseTemplates, sshAssets, databaseAssets, apiAssets, jdbcTemplate);

    @Test
    void oneCategoryUpdatePropagatesToApiAndDatabaseTemplates() {
        BusinessCategory existing = category("finance-id", "finance", "金融");
        ApiServiceConfig api = new ApiServiceConfig();
        api.setCategoryId(existing.getId());
        DatabaseQueryConfig query = new DatabaseQueryConfig();
        query.setCategoryId(existing.getId());
        when(categories.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(categories.findByCodeIgnoreCase(existing.getCode())).thenReturn(Optional.of(existing));
        when(categories.save(any(BusinessCategory.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(apiTemplates.findByCategoryId(existing.getId())).thenReturn(List.of(api));
        when(databaseTemplates.findByCategoryId(existing.getId())).thenReturn(List.of(query));

        BusinessCategory draft = category("finance-id", "finance", "金融业务");
        draft.setDescription("统一金融业务分类");
        BusinessCategory saved = service.save(draft);

        assertThat(saved.getName()).isEqualTo("金融业务");
        assertThat(api.getBusinessGroupName()).isEqualTo("金融业务");
        assertThat(query.getBusinessGroupName()).isEqualTo("金融业务");
        assertThat(query.getCapabilityCategory()).isEqualTo("finance");
        verify(apiTemplates).save(api);
        verify(databaseTemplates).save(query);
    }

    @Test
    void referencedCategoryCannotBeDeletedFromAnyEntryPoint() {
        when(categories.findById("finance-id")).thenReturn(Optional.of(category("finance-id", "finance", "金融")));
        when(databaseAssets.countByCategoryId("finance-id")).thenReturn(1L);

        assertThatThrownBy(() -> service.delete("finance-id"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("referenced by assets or templates");
    }

    @Test
    void createsAndReturnsPersistentDefaultCategoryWhenCategoryIsMissing() {
        when(categories.findByCodeIgnoreCase("default")).thenReturn(Optional.empty());
        when(categories.save(any(BusinessCategory.class))).thenAnswer(invocation -> {
            BusinessCategory category = invocation.getArgument(0);
            category.setId("default-id");
            return category;
        });

        BusinessCategory resolved = service.resolveOrDefault(null);

        assertThat(resolved.getId()).isEqualTo("default-id");
        assertThat(resolved.getCode()).isEqualTo("default");
        assertThat(resolved.getName()).isEqualTo("默认分类");
        assertThat(resolved.isEnabled()).isTrue();
    }

    @Test
    void defaultCategoryCannotBeDeleted() {
        when(categories.findById("default-id"))
            .thenReturn(Optional.of(category("default-id", "default", "默认分类")));

        assertThatThrownBy(() -> service.delete("default-id"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("default category cannot be deleted");
    }

    @Test
    void startupBackfillsExistingAssetsWithoutCategory() {
        BusinessCategory fallback = category("default-id", "default", "默认分类");
        SshHostConfig ssh = new SshHostConfig();
        SqlDatasourceConfig datasource = new SqlDatasourceConfig();
        HttpEndpointConfig endpoint = new HttpEndpointConfig();
        when(jdbcTemplate.queryForList(anyString())).thenReturn(List.of());
        when(categories.findByCodeIgnoreCase("default")).thenReturn(Optional.of(fallback));
        when(sshAssets.findAll()).thenReturn(List.of(ssh));
        when(databaseAssets.findAll()).thenReturn(List.of(datasource));
        when(apiAssets.findAll()).thenReturn(List.of(endpoint));

        service.migrateLegacyCategories();

        assertThat(ssh.getCategoryId()).isEqualTo("default-id");
        assertThat(datasource.getCategoryId()).isEqualTo("default-id");
        assertThat(endpoint.getCategoryId()).isEqualTo("default-id");
        verify(sshAssets).save(ssh);
        verify(databaseAssets).save(datasource);
        verify(apiAssets).save(endpoint);
    }

    private BusinessCategory category(String id, String code, String name) {
        BusinessCategory category = new BusinessCategory();
        category.setId(id);
        category.setCode(code);
        category.setName(name);
        category.setDomain("finance");
        category.setDescription(name);
        category.setKeywordsJson("[]");
        category.setEnabled(true);
        return category;
    }
}
