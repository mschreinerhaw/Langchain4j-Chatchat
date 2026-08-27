package com.chatchat.mcpserver.metadata.ingestion;

import com.chatchat.mcpserver.metadata.catalog.EnterpriseMetadataRecord;
import com.chatchat.mcpserver.metadata.taxonomy.MetadataStandardDictionary;
import com.chatchat.mcpserver.metadata.taxonomy.MetadataStandardDictionaryItem;
import com.chatchat.mcpserver.metadata.taxonomy.MetadataStandardDictionaryItemRepository;
import com.chatchat.mcpserver.metadata.taxonomy.MetadataStandardDictionaryRepository;
import com.chatchat.mcpserver.metadata.taxonomy.MetadataStandardField;
import com.chatchat.mcpserver.metadata.taxonomy.MetadataStandardFieldRepository;
import com.chatchat.mcpserver.metadata.taxonomy.MetadataStandardTerm;
import com.chatchat.mcpserver.metadata.taxonomy.MetadataStandardTermRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class EnterpriseMetadataDatabaseLoader {

    private final MetadataStandardFieldRepository fieldRepository;
    private final MetadataStandardTermRepository termRepository;
    private final MetadataStandardDictionaryRepository dictionaryRepository;
    private final MetadataStandardDictionaryItemRepository dictionaryItemRepository;

    public EnterpriseMetadataDatabaseLoader(MetadataStandardFieldRepository fieldRepository,
                                            MetadataStandardTermRepository termRepository,
                                            MetadataStandardDictionaryRepository dictionaryRepository,
                                            MetadataStandardDictionaryItemRepository dictionaryItemRepository) {
        this.fieldRepository = fieldRepository;
        this.termRepository = termRepository;
        this.dictionaryRepository = dictionaryRepository;
        this.dictionaryItemRepository = dictionaryItemRepository;
    }

    @Transactional(readOnly = true)
    public List<EnterpriseMetadataRecord> load() {
        List<EnterpriseMetadataRecord> records = new ArrayList<>();
        fieldRepository.findAll().stream().map(this::fieldRecord).forEach(records::add);
        termRepository.findAll().stream().map(this::termRecord).forEach(records::add);
        Map<String, MetadataStandardDictionary> dictionaries = dictionaryRepository.findAll().stream()
            .collect(java.util.stream.Collectors.toMap(
                MetadataStandardDictionary::getId,
                value -> value,
                (left, right) -> left,
                LinkedHashMap::new
            ));
        dictionaryItemRepository.findAll().stream()
            .map(item -> dictionaryRecord(dictionaries.get(item.getDictionaryId()), item))
            .forEach(records::add);
        return List.copyOf(records);
    }

    public Map<String, Long> counts() {
        return Map.of(
            "metadata_field", fieldRepository.count(),
            "metadata_term", termRepository.count(),
            "metadata_dictionary", dictionaryItemRepository.count(),
            "metadata_dictionary_header", dictionaryRepository.count()
        );
    }

    private EnterpriseMetadataRecord fieldRecord(MetadataStandardField field) {
        Map<String, Object> attributes = attributes(
            "fullPinyin", field.getFullPinyin(),
            "englishName", field.getEnglishName(),
            "abbreviation", field.getAbbreviation(),
            "dataType", field.getDataType(),
            "length", field.getDataLength(),
            "precision", field.getDataPrecision(),
            "nullable", field.getNullableFlag(),
            "repeatable", field.getRepeatableFlag(),
            "defaultValue", field.getDefaultValue(),
            "valueRange", field.getValueRange()
        );
        return new EnterpriseMetadataRecord(
            field.getId(), "metadata_field", "enterprise_field_catalog",
            field.getChineseName(), first(field.getEnglishName(), field.getAbbreviation()),
            field.getStandardDescription(), field.getStatus(), field.getSource(), attributes
        );
    }

    private EnterpriseMetadataRecord termRecord(MetadataStandardTerm term) {
        return new EnterpriseMetadataRecord(
            term.getId(), "metadata_term", "enterprise_term_dictionary",
            term.getChineseName(), term.getAbbreviation(), term.getRemark(),
            term.getStatus(), term.getSource(), attributes(
                "englishName", term.getEnglishName(),
                "abbreviation", term.getAbbreviation(),
                "remark", term.getRemark()
            )
        );
    }

    private EnterpriseMetadataRecord dictionaryRecord(MetadataStandardDictionary dictionary,
                                                      MetadataStandardDictionaryItem item) {
        String name = dictionary == null ? item.getDictionaryId() : dictionary.getName();
        String englishName = dictionary == null ? null : dictionary.getEnglishName();
        String status = first(item.getStatus(), dictionary == null ? null : dictionary.getStatus());
        String source = first(item.getSource(), dictionary == null ? null : dictionary.getSource());
        return new EnterpriseMetadataRecord(
            item.getId(), "metadata_dictionary", "enterprise_term_dictionary",
            name, englishName, item.getCodeDescription(), status, source, attributes(
                "dictionaryId", item.getDictionaryId(),
                "dictionaryEnglishName", englishName,
                "code", item.getCode(),
                "codeDescription", item.getCodeDescription()
            )
        );
    }

    private Map<String, Object> attributes(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index + 1 < values.length; index += 2) {
            Object value = values[index + 1];
            if (value != null && !String.valueOf(value).isBlank()) {
                result.put(String.valueOf(values[index]), value);
            }
        }
        return Map.copyOf(result);
    }

    private String first(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }
}
