package com.chatchat.mcpserver.metadata;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class EnterpriseMetadataWorkbookLoader {

    private final ResourcePatternResolver resourceResolver;

    public List<EnterpriseMetadataRecord> load(String sourceLocationPattern) {
        if (sourceLocationPattern == null || sourceLocationPattern.isBlank()) {
            return List.of();
        }
        Map<String, EnterpriseMetadataRecord> records = new LinkedHashMap<>();
        try {
            Resource[] resources = resourceResolver.getResources(sourceLocationPattern.trim());
            for (Resource resource : resources) {
                if (!resource.exists() || !xlsx(resource)) {
                    continue;
                }
                loadWorkbook(resource, records);
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to load enterprise metadata from configured resources", ex);
        }
        return List.copyOf(records.values());
    }

    private void loadWorkbook(Resource resource, Map<String, EnterpriseMetadataRecord> target) {
        int before = target.size();
        try (InputStream input = resource.getInputStream();
             Workbook workbook = WorkbookFactory.create(input)) {
            for (Sheet sheet : workbook) {
                loadSheet(resource, sheet, target);
            }
            log.info("Enterprise metadata workbook loaded source={} records={}",
                resource.getDescription(), target.size() - before);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to read enterprise metadata workbook "
                + resource.getDescription(), ex);
        }
    }

    private void loadSheet(Resource resource, Sheet sheet, Map<String, EnterpriseMetadataRecord> target) {
        Row headerRow = sheet.getRow(sheet.getFirstRowNum());
        if (headerRow == null) {
            return;
        }
        Map<String, Integer> headers = headers(headerRow);
        WorkbookKind kind = WorkbookKind.detect(headers);
        if (kind == null) {
            log.debug("Ignoring unsupported metadata sheet source={} sheet={} headers={}",
                resource.getDescription(), sheet.getSheetName(), headers.keySet());
            return;
        }
        String source = resource.getFilename() + "#" + sheet.getSheetName();
        for (int rowIndex = headerRow.getRowNum() + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            EnterpriseMetadataRecord record = kind.read(row, headers, source);
            if (record != null) {
                target.put(record.metadataType() + ":" + record.id(), record);
            }
        }
    }

    private Map<String, Integer> headers(Row row) {
        Map<String, Integer> values = new LinkedHashMap<>();
        DataFormatter formatter = new DataFormatter(Locale.ROOT);
        for (int index = row.getFirstCellNum(); index < row.getLastCellNum(); index++) {
            String value = cleanHeader(formatter.formatCellValue(row.getCell(index)));
            if (!value.isBlank()) {
                values.put(value, index);
            }
        }
        return values;
    }

    private boolean xlsx(Resource resource) {
        String filename = resource.getFilename();
        return filename != null && filename.toLowerCase(Locale.ROOT).endsWith(".xlsx");
    }

    private String cleanHeader(String value) {
        return text(value).replace("*", "").replace(" ", "");
    }

    private enum WorkbookKind {
        FIELD {
            @Override
            EnterpriseMetadataRecord read(Row row, Map<String, Integer> headers, String source) {
                String id = value(row, headers, "内部编码");
                String name = value(row, headers, "中文名称");
                if (blank(id) || blank(name)) return null;
                Map<String, Object> attributes = attributes(
                    "fullPinyin", value(row, headers, "中文全拼"),
                    "englishName", value(row, headers, "英文名称"),
                    "abbreviation", value(row, headers, "英文缩写"),
                    "dataType", value(row, headers, "数据类型"),
                    "length", value(row, headers, "数据长度"),
                    "precision", value(row, headers, "数据精度"),
                    "nullable", value(row, headers, "是否允许空"),
                    "repeatable", value(row, headers, "是否允许重复"),
                    "defaultValue", value(row, headers, "默认值"),
                    "valueRange", value(row, headers, "取值范围")
                );
                return new EnterpriseMetadataRecord(id, "metadata_field", "enterprise_field_catalog",
                    name, first(value(row, headers, "英文名称"), value(row, headers, "英文缩写")),
                    value(row, headers, "标准说明"), value(row, headers, "状态"), source, attributes);
            }
        },
        TERM {
            @Override
            EnterpriseMetadataRecord read(Row row, Map<String, Integer> headers, String source) {
                String id = value(row, headers, "词根编码");
                String name = value(row, headers, "中文名称");
                if (blank(id) || blank(name)) return null;
                Map<String, Object> attributes = attributes(
                    "englishName", value(row, headers, "英文全称"),
                    "abbreviation", value(row, headers, "英文简称"),
                    "remark", value(row, headers, "备注")
                );
                return new EnterpriseMetadataRecord(id, "metadata_term", "enterprise_term_dictionary",
                    name, value(row, headers, "英文简称"), value(row, headers, "备注"),
                    "active", source, attributes);
            }
        },
        DICTIONARY {
            @Override
            EnterpriseMetadataRecord read(Row row, Map<String, Integer> headers, String source) {
                String dictionaryId = value(row, headers, "内部标识符");
                String code = value(row, headers, "代码");
                String name = value(row, headers, "字典名称");
                if (blank(dictionaryId) || blank(code) || blank(name)) return null;
                Map<String, Object> attributes = attributes(
                    "dictionaryId", dictionaryId,
                    "dictionaryEnglishName", value(row, headers, "英文名称"),
                    "code", code,
                    "codeDescription", value(row, headers, "代码描述")
                );
                return new EnterpriseMetadataRecord(dictionaryId + ":" + code, "metadata_dictionary",
                    "enterprise_term_dictionary", name, value(row, headers, "英文名称"),
                    value(row, headers, "代码描述"), value(row, headers, "状态"), source, attributes);
            }
        };

        abstract EnterpriseMetadataRecord read(Row row, Map<String, Integer> headers, String source);

        static WorkbookKind detect(Map<String, Integer> headers) {
            if (headers.containsKey("内部编码") && headers.containsKey("数据类型")) return FIELD;
            if (headers.containsKey("词根编码") && headers.containsKey("英文全称")) return TERM;
            if (headers.containsKey("内部标识符") && headers.containsKey("代码描述")) return DICTIONARY;
            return null;
        }

        static String value(Row row, Map<String, Integer> headers, String name) {
            if (row == null || !headers.containsKey(name)) return null;
            String value = new DataFormatter(Locale.ROOT).formatCellValue(row.getCell(headers.get(name)));
            return blank(value) ? null : value.trim();
        }

        static Map<String, Object> attributes(Object... values) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (int index = 0; index + 1 < values.length; index += 2) {
                Object value = values[index + 1];
                if (value != null && !String.valueOf(value).isBlank()) {
                    result.put(String.valueOf(values[index]), value);
                }
            }
            return result;
        }

        static String first(String... values) {
            for (String value : values) {
                if (!blank(value)) return value;
            }
            return null;
        }

        static boolean blank(String value) {
            return value == null || value.isBlank();
        }
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}
