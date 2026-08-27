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

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
public class EnterpriseMetadataImportService {

    private final EnterpriseMetadataProperties properties;
    private final EnterpriseMetadataWorkbookLoader workbookLoader;
    private final MetadataStandardFieldRepository fieldRepository;
    private final MetadataStandardTermRepository termRepository;
    private final MetadataStandardDictionaryRepository dictionaryRepository;
    private final MetadataStandardDictionaryItemRepository dictionaryItemRepository;

    public EnterpriseMetadataImportService(EnterpriseMetadataProperties properties,
                                           EnterpriseMetadataWorkbookLoader workbookLoader,
                                           MetadataStandardFieldRepository fieldRepository,
                                           MetadataStandardTermRepository termRepository,
                                           MetadataStandardDictionaryRepository dictionaryRepository,
                                           MetadataStandardDictionaryItemRepository dictionaryItemRepository) {
        this.properties = properties;
        this.workbookLoader = workbookLoader;
        this.fieldRepository = fieldRepository;
        this.termRepository = termRepository;
        this.dictionaryRepository = dictionaryRepository;
        this.dictionaryItemRepository = dictionaryItemRepository;
    }

    @Order(Ordered.LOWEST_PRECEDENCE - 100)
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void bootstrapWhenEmpty() {
        if (properties.isEnabled() && totalRows() == 0L) {
            ImportResult result = importConfiguredWorkbooks();
            log.info("Enterprise metadata database bootstrap completed {}", result);
        }
    }

    @Transactional
    public ImportResult importConfiguredWorkbooks() {
        Map<String, EnterpriseMetadataRecord> records = properties.resolvedSourceLocationPatterns().stream()
            .flatMap(pattern -> workbookLoader.load(pattern).stream())
            .collect(Collectors.toMap(
                record -> record.metadataType() + ":" + record.id(),
                Function.identity(),
                (first, ignored) -> first,
                LinkedHashMap::new
            ));
        Map<String, MetadataStandardField> fields = fieldRepository.findAll().stream()
            .collect(Collectors.toMap(MetadataStandardField::getId, Function.identity()));
        Map<String, MetadataStandardTerm> terms = termRepository.findAll().stream()
            .collect(Collectors.toMap(MetadataStandardTerm::getId, Function.identity()));
        Map<String, MetadataStandardDictionary> dictionaries = dictionaryRepository.findAll().stream()
            .collect(Collectors.toMap(MetadataStandardDictionary::getId, Function.identity()));
        Map<String, MetadataStandardDictionaryItem> items = dictionaryItemRepository.findAll().stream()
            .collect(Collectors.toMap(MetadataStandardDictionaryItem::getId, Function.identity()));

        int fieldCount = 0;
        int termCount = 0;
        int itemCount = 0;
        for (EnterpriseMetadataRecord record : records.values()) {
            switch (record.metadataType()) {
                case "metadata_field" -> {
                    MetadataStandardField field = fields.computeIfAbsent(record.id(), ignored -> {
                        MetadataStandardField value = new MetadataStandardField();
                        value.setId(record.id());
                        return value;
                    });
                    apply(field, record);
                    fieldCount++;
                }
                case "metadata_term" -> {
                    MetadataStandardTerm term = terms.computeIfAbsent(record.id(), ignored -> {
                        MetadataStandardTerm value = new MetadataStandardTerm();
                        value.setId(record.id());
                        return value;
                    });
                    apply(term, record);
                    termCount++;
                }
                case "metadata_dictionary" -> {
                    String dictionaryId = attribute(record, "dictionaryId");
                    if (dictionaryId == null) break;
                    MetadataStandardDictionary dictionary = dictionaries.computeIfAbsent(dictionaryId, ignored -> {
                        MetadataStandardDictionary value = new MetadataStandardDictionary();
                        value.setId(dictionaryId);
                        return value;
                    });
                    apply(dictionary, record);
                    MetadataStandardDictionaryItem item = items.computeIfAbsent(record.id(), ignored -> {
                        MetadataStandardDictionaryItem value = new MetadataStandardDictionaryItem();
                        value.setId(record.id());
                        return value;
                    });
                    apply(item, record, dictionaryId);
                    itemCount++;
                }
                default -> {
                    // Unsupported workbook content is ignored by the loader.
                }
            }
        }
        fieldRepository.saveAll(fields.values());
        termRepository.saveAll(terms.values());
        dictionaryRepository.saveAllAndFlush(dictionaries.values());
        dictionaryItemRepository.saveAll(items.values());
        return new ImportResult(fieldCount, termCount, dictionaries.size(), itemCount,
            properties.resolvedSourceLocationPatterns());
    }

    public long totalRows() {
        return fieldRepository.count() + termRepository.count() + dictionaryItemRepository.count();
    }

    private void apply(MetadataStandardField target, EnterpriseMetadataRecord record) {
        target.setChineseName(record.name());
        target.setFullPinyin(attribute(record, "fullPinyin"));
        target.setEnglishName(attribute(record, "englishName"));
        target.setAbbreviation(attribute(record, "abbreviation"));
        if (target.getEnglishName() == null) target.setEnglishName(record.technicalName());
        target.setDataType(attribute(record, "dataType"));
        target.setDataLength(attribute(record, "length"));
        target.setDataPrecision(attribute(record, "precision"));
        target.setNullableFlag(attribute(record, "nullable"));
        target.setRepeatableFlag(attribute(record, "repeatable"));
        target.setDefaultValue(attribute(record, "defaultValue"));
        target.setValueRange(attribute(record, "valueRange"));
        target.setStandardDescription(record.description());
        target.setStatus(record.status());
        target.setSource(record.source());
    }

    private void apply(MetadataStandardTerm target, EnterpriseMetadataRecord record) {
        target.setChineseName(record.name());
        target.setEnglishName(attribute(record, "englishName"));
        target.setAbbreviation(first(attribute(record, "abbreviation"), record.technicalName()));
        target.setRemark(first(attribute(record, "remark"), record.description()));
        target.setStatus(first(record.status(), "active"));
        target.setSource(record.source());
    }

    private void apply(MetadataStandardDictionary target, EnterpriseMetadataRecord record) {
        target.setName(record.name());
        target.setEnglishName(first(attribute(record, "dictionaryEnglishName"), record.technicalName()));
        target.setStatus(record.status());
        target.setSource(record.source());
    }

    private void apply(MetadataStandardDictionaryItem target, EnterpriseMetadataRecord record,
                       String dictionaryId) {
        target.setDictionaryId(dictionaryId);
        target.setCode(attribute(record, "code"));
        target.setCodeDescription(first(attribute(record, "codeDescription"), record.description()));
        target.setStatus(record.status());
        target.setSource(record.source());
    }

    private String attribute(EnterpriseMetadataRecord record, String name) {
        Object value = record.attributes().get(name);
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value).trim();
    }

    private String first(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    public record ImportResult(int fields, int terms, int dictionaries, int dictionaryItems,
                               List<String> sources) {
        public ImportResult {
            sources = sources == null ? List.of() : List.copyOf(sources);
        }
    }
}
