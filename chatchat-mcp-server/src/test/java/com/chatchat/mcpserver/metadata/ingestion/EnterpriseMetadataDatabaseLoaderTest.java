package com.chatchat.mcpserver.metadata.ingestion;

import com.chatchat.mcpserver.metadata.config.EnterpriseMetadataProperties;
import com.chatchat.mcpserver.metadata.catalog.EnterpriseMetadataRecord;
import com.chatchat.mcpserver.metadata.taxonomy.MetadataStandardDictionary;
import com.chatchat.mcpserver.metadata.taxonomy.MetadataStandardDictionaryItem;
import com.chatchat.mcpserver.metadata.taxonomy.MetadataStandardDictionaryItemRepository;
import com.chatchat.mcpserver.metadata.taxonomy.MetadataStandardDictionaryRepository;
import com.chatchat.mcpserver.metadata.taxonomy.MetadataStandardField;
import com.chatchat.mcpserver.metadata.taxonomy.MetadataStandardFieldRepository;
import com.chatchat.mcpserver.metadata.taxonomy.MetadataStandardTerm;
import com.chatchat.mcpserver.metadata.taxonomy.MetadataStandardTermRepository;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EnterpriseMetadataDatabaseLoaderTest {

    @Test
    void loadsStandardFieldsTermsAndDictionaryItemsFromDatabase() {
        MetadataStandardFieldRepository fields = mock(MetadataStandardFieldRepository.class);
        MetadataStandardTermRepository terms = mock(MetadataStandardTermRepository.class);
        MetadataStandardDictionaryRepository dictionaries =
            mock(MetadataStandardDictionaryRepository.class);
        MetadataStandardDictionaryItemRepository items =
            mock(MetadataStandardDictionaryItemRepository.class);

        MetadataStandardField field = new MetadataStandardField();
        field.setId("F001");
        field.setChineseName("客户编号");
        field.setEnglishName("customer_id");
        field.setDataType("VARCHAR");

        MetadataStandardTerm term = new MetadataStandardTerm();
        term.setId("T001");
        term.setChineseName("客户");
        term.setEnglishName("customer");
        term.setAbbreviation("cust");

        MetadataStandardDictionary dictionary = new MetadataStandardDictionary();
        dictionary.setId("D001");
        dictionary.setName("客户状态");
        dictionary.setEnglishName("customer_status");

        MetadataStandardDictionaryItem item = new MetadataStandardDictionaryItem();
        item.setId("D001:1");
        item.setDictionaryId("D001");
        item.setCode("1");
        item.setCodeDescription("正常");

        when(fields.findAll()).thenReturn(List.of(field));
        when(terms.findAll()).thenReturn(List.of(term));
        when(dictionaries.findAll()).thenReturn(List.of(dictionary));
        when(items.findAll()).thenReturn(List.of(item));

        List<EnterpriseMetadataRecord> records =
            new EnterpriseMetadataDatabaseLoader(fields, terms, dictionaries, items).load();

        assertThat(records).extracting(EnterpriseMetadataRecord::metadataType)
            .containsExactly("metadata_field", "metadata_term", "metadata_dictionary");
        assertThat(records.get(0).technicalName()).isEqualTo("customer_id");
        assertThat(records.get(1).attributes()).containsEntry("abbreviation", "cust");
        assertThat(records.get(2).attributes())
            .containsEntry("dictionaryId", "D001")
            .containsEntry("code", "1");
    }

    @Test
    void keepsWorkbookLocationsAsCodeDefaultsInsteadOfYamlEntries() {
        EnterpriseMetadataProperties properties = new EnterpriseMetadataProperties();

        assertThat(properties.resolvedSourceLocationPatterns())
            .containsExactly(
                "file:../标准字段词根/**/*.xlsx",
                "file:../../标准字段词根/**/*.xlsx"
            );
        assertThat(properties.getIndexName()).isEqualTo("enterprise_metadata_catalog");
        assertThat(properties.getKnn().getDimension()).isEqualTo(256);
    }
}
