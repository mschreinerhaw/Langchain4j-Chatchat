package com.chatchat.mcpserver.metadata;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EnterpriseMetadataWorkbookLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void detectsWorkbookSchemasFromHeadersWithoutDependingOnFilenames() throws Exception {
        Path workbookPath = tempDir.resolve("metadata.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            var fields = workbook.createSheet("fields");
            row(fields, 0, "中文名称*", "中文全拼", "英文名称*", "英文缩写*", "内部编码",
                "标准说明", "数据类型*", "数据长度", "数据精度", "是否允许空", "是否允许重复",
                "默认值", "取值范围", "状态");
            row(fields, 1, "客户编码", "客户编码", "CUST_NUM", "CUST_NUM", "F001",
                "客户唯一标识", "字符型", "32", "0", "否", "否", "", "", "标准");

            var terms = workbook.createSheet("terms");
            row(terms, 0, "中文名称*", "英文全称*", "英文简称*", "备注", "词根编码");
            row(terms, 1, "客户", "Customer", "CUST", "主体", "T001");

            var dictionary = workbook.createSheet("dictionary");
            row(dictionary, 0, "字典名称*", "英文名称*", "内部标识符", "状态", "代码*", "代码描述*");
            row(dictionary, 1, "客户类型", "CUSTOMER_TYPE", "D001", "启用", "1", "个人");

            try (OutputStream output = Files.newOutputStream(workbookPath)) {
                workbook.write(output);
            }
        }

        EnterpriseMetadataWorkbookLoader loader = new EnterpriseMetadataWorkbookLoader(
            new PathMatchingResourcePatternResolver());
        List<EnterpriseMetadataRecord> records = loader.load(workbookPath.toUri().toString());

        assertThat(records).hasSize(3);
        assertThat(records).extracting(EnterpriseMetadataRecord::metadataType)
            .containsExactly("metadata_field", "metadata_term", "metadata_dictionary");
        assertThat(records.get(0).toMap())
            .containsEntry("technicalName", "CUST_NUM")
            .containsEntry("dataType", "字符型")
            .containsEntry("status", "标准");
    }

    @Test
    void loadsProvidedEnterpriseCatalogOrGeneratedReleaseFixture() throws Exception {
        String configuredSource = System.getProperty("chatchat.e2e.enterprise-metadata-path", "");
        Path source;
        if (configuredSource.isBlank()) {
            source = tempDir.resolve("generated-enterprise-catalog");
            Files.createDirectories(source);
            createEnterpriseCatalogFixture(source.resolve("release-metadata.xlsx"));
        } else {
            source = Path.of(configuredSource).toAbsolutePath().normalize();
            assertThat(source).as("configured enterprise metadata directory").isDirectory();
        }
        EnterpriseMetadataWorkbookLoader loader = new EnterpriseMetadataWorkbookLoader(
            new PathMatchingResourcePatternResolver());

        List<EnterpriseMetadataRecord> records = loader.load(source.toUri() + "**/*.xlsx");

        assertThat(records).hasSizeGreaterThan(10_000);
        assertThat(records).extracting(EnterpriseMetadataRecord::metadataType)
            .contains("metadata_field", "metadata_term", "metadata_dictionary");
    }

    private void createEnterpriseCatalogFixture(Path workbookPath) throws Exception {
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(100)) {
            var fields = workbook.createSheet("fields");
            row(fields, 0, "中文名称*", "中文全拼", "英文名称*", "英文缩写*", "内部编码",
                "标准说明", "数据类型*", "数据长度", "数据精度", "是否允许空", "是否允许重复",
                "默认值", "取值范围", "状态");
            for (int index = 1; index <= 4_000; index++) {
                row(fields, index, "字段" + index, "field" + index, "FIELD_" + index,
                    "F" + index, "F" + index, "release fixture", "字符型", "64", "0",
                    "否", "否", "", "", "标准");
            }

            var terms = workbook.createSheet("terms");
            row(terms, 0, "中文名称*", "英文全称*", "英文简称", "备注", "词根编码");
            for (int index = 1; index <= 3_001; index++) {
                row(terms, index, "词根" + index, "Term " + index, "T" + index,
                    "release fixture", "T" + index);
            }

            var dictionary = workbook.createSheet("dictionary");
            row(dictionary, 0, "字典名称*", "英文名称*", "内部标识符", "状态", "代码*", "代码描述*");
            for (int index = 1; index <= 3_001; index++) {
                row(dictionary, index, "字典" + index, "DICT_" + index, "D" + index,
                    "启用", "C" + index, "代码" + index);
            }
            try (OutputStream output = Files.newOutputStream(workbookPath)) {
                workbook.write(output);
            }
            workbook.dispose();
        }
    }

    private void row(org.apache.poi.ss.usermodel.Sheet sheet, int index, String... values) {
        var row = sheet.createRow(index);
        for (int column = 0; column < values.length; column++) {
            row.createCell(column).setCellValue(values[column]);
        }
    }
}
