package com.chatchat.knowledgebase.search;

import org.apache.tika.Tika;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

@Component
public class DocumentTextExtractor {

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(
        "txt", "md", "csv",
        "pdf",
        "doc", "docx",
        "xls", "xlsx",
        "ppt", "pptx"
    );

    private final Tika tika;

    public DocumentTextExtractor() {
        this.tika = new Tika();
        this.tika.setMaxStringLength(-1);
    }

    public String extractText(Path file, String fileName) {
        try (InputStream inputStream = Files.newInputStream(file)) {
            return extractText(inputStream, fileName);
        } catch (IOException ex) {
            throw new IllegalStateException("failed to read document: " + fileName, ex);
        }
    }

    public String extractText(InputStream inputStream, String fileName) {
        try {
            Metadata metadata = new Metadata();
            metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, fileName);
            return cleanText(tika.parseToString(inputStream, metadata));
        } catch (Exception ex) {
            throw new IllegalStateException("failed to extract text from document: " + fileName, ex);
        }
    }

    public boolean supports(String fileName) {
        return SUPPORTED_EXTENSIONS.contains(extensionOf(fileName));
    }

    private String cleanText(String text) {
        if (text == null) {
            return "";
        }
        return text
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replaceAll("[ \\t]+", " ")
            .replaceAll("\\n{3,}", "\n\n")
            .trim();
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
