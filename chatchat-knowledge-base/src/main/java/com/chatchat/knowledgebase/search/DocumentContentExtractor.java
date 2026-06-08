package com.chatchat.knowledgebase.search;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

@Slf4j
@Component
public class DocumentContentExtractor {

    public String extract(Path file, String fileName) {
        String extension = extensionOf(fileName);
        try {
            return switch (extension) {
                case "txt", "md", "csv" -> Files.readString(file, StandardCharsets.UTF_8);
                case "pdf" -> extractPdf(file);
                case "docx" -> extractDocx(file);
                case "doc" -> extractDoc(file);
                case "xlsx", "xls" -> extractWorkbook(file);
                default -> "";
            };
        } catch (Exception ex) {
            log.warn("Failed to extract content from {}: {}", fileName, ex.getMessage());
            return "";
        }
    }

    public boolean supports(String fileName) {
        return switch (extensionOf(fileName)) {
            case "txt", "md", "csv", "pdf", "docx", "doc", "xlsx", "xls" -> true;
            default -> false;
        };
    }

    private String extractPdf(Path file) throws IOException {
        try (PDDocument document = Loader.loadPDF(file.toFile())) {
            return new PDFTextStripper().getText(document);
        }
    }

    private String extractDocx(Path file) throws IOException {
        try (InputStream input = Files.newInputStream(file);
             XWPFDocument document = new XWPFDocument(input);
             XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
            return extractor.getText();
        }
    }

    private String extractDoc(Path file) throws IOException {
        try (InputStream input = Files.newInputStream(file);
             HWPFDocument document = new HWPFDocument(input);
             WordExtractor extractor = new WordExtractor(document)) {
            return extractor.getText();
        }
    }

    private String extractWorkbook(Path file) throws Exception {
        StringBuilder text = new StringBuilder();
        DataFormatter formatter = new DataFormatter();
        try (Workbook workbook = WorkbookFactory.create(file.toFile())) {
            for (Sheet sheet : workbook) {
                text.append(sheet.getSheetName()).append('\n');
                for (Row row : sheet) {
                    for (int i = 0; i < row.getLastCellNum(); i++) {
                        text.append(formatter.formatCellValue(row.getCell(i))).append('\t');
                    }
                    text.append('\n');
                }
            }
        }
        return text.toString();
    }

    private String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
