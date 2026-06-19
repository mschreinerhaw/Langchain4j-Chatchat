package com.chatchat.knowledgebase.search;

import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.ocr.TesseractOCRConfig;
import org.apache.tika.parser.pdf.PDFParserConfig;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

@Component
public class DocumentTextExtractor {

    public static final String OCR_CHUNK_TYPE = "ocr_text";

    private static final Set<String> SUPPORTED_DOCUMENT_EXTENSIONS = Set.of(
        "txt", "md", "csv",
        "pdf",
        "doc", "docx",
        "xls", "xlsx",
        "ppt", "pptx"
    );
    private static final Set<String> SUPPORTED_IMAGE_EXTENSIONS = Set.of(
        "png", "jpg", "jpeg", "bmp", "gif", "tif", "tiff", "webp"
    );

    private final AutoDetectParser parser;
    private final SearchProperties properties;

    public DocumentTextExtractor() {
        this(new SearchProperties());
    }

    @Autowired
    public DocumentTextExtractor(SearchProperties properties) {
        this.parser = new AutoDetectParser();
        this.properties = properties == null ? new SearchProperties() : properties;
    }

    public String extractText(Path file, String fileName) {
        try (InputStream inputStream = Files.newInputStream(file)) {
            return extractText(inputStream, fileName);
        } catch (IOException ex) {
            throw new IllegalStateException("failed to read document: " + fileName, ex);
        }
    }

    public String extractText(InputStream inputStream, String fileName) {
        String extension = extensionOf(fileName);
        try {
            String text = parse(inputStream, fileName, shouldUseOcr(extension));
            return isImageExtension(extension) ? markOcrText(text) : text;
        } catch (Exception ex) {
            throw new IllegalStateException("failed to extract text from document: " + fileName, ex);
        }
    }

    public boolean supports(String fileName) {
        String extension = extensionOf(fileName);
        return SUPPORTED_DOCUMENT_EXTENSIONS.contains(extension)
            || (ocrEnabled() && SUPPORTED_IMAGE_EXTENSIONS.contains(extension));
    }

    private String parse(InputStream inputStream, String fileName, boolean ocr) throws Exception {
        Metadata metadata = new Metadata();
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, fileName);
        BodyContentHandler handler = new BodyContentHandler(-1);
        parser.parse(inputStream, handler, metadata, parseContext(ocr));
        return cleanText(handler.toString());
    }

    private ParseContext parseContext(boolean ocr) {
        ParseContext context = new ParseContext();
        context.set(TesseractOCRConfig.class, tesseractConfig(ocr));
        context.set(PDFParserConfig.class, pdfParserConfig(ocr));
        return context;
    }

    private TesseractOCRConfig tesseractConfig(boolean ocr) {
        SearchProperties.Ocr config = ocrConfig();
        TesseractOCRConfig tesseract = new TesseractOCRConfig();
        tesseract.setSkipOcr(!ocr);
        tesseract.setLanguage(blankToDefault(config.getLanguage(), "eng"));
        tesseract.setTimeoutSeconds(Math.max(1, config.getTimeoutSeconds()));
        tesseract.setPreserveInterwordSpacing(config.isPreserveInterwordSpacing());
        tesseract.setEnableImagePreprocessing(config.isImagePreprocessingEnabled());
        tesseract.setInlineContent(true);
        return tesseract;
    }

    private PDFParserConfig pdfParserConfig(boolean ocr) {
        PDFParserConfig pdf = new PDFParserConfig();
        pdf.setExtractInlineImages(ocr);
        pdf.setExtractUniqueInlineImagesOnly(true);
        pdf.setOcrStrategy(ocr ? pdfOcrStrategy() : PDFParserConfig.OCR_STRATEGY.NO_OCR);
        pdf.setOcrRenderingStrategy(PDFParserConfig.OCR_RENDERING_STRATEGY.ALL);
        return pdf;
    }

    private PDFParserConfig.OCR_STRATEGY pdfOcrStrategy() {
        String strategy = blankToDefault(ocrConfig().getPdfStrategy(), "auto")
            .trim()
            .toUpperCase(Locale.ROOT)
            .replace('-', '_');
        try {
            return PDFParserConfig.OCR_STRATEGY.valueOf(strategy);
        } catch (IllegalArgumentException ex) {
            return PDFParserConfig.OCR_STRATEGY.AUTO;
        }
    }

    private boolean shouldUseOcr(String extension) {
        return ocrEnabled() && ("pdf".equals(extension) || isImageExtension(extension));
    }

    private boolean ocrEnabled() {
        return ocrConfig().isEnabled();
    }

    private SearchProperties.Ocr ocrConfig() {
        return properties.getOcr() == null ? new SearchProperties.Ocr() : properties.getOcr();
    }

    private String markOcrText(String text) {
        String cleaned = cleanText(text);
        if (cleaned.isBlank()) {
            return cleaned;
        }
        String marker = blankToDefault(ocrConfig().getMarker(), "# OCR_TEXT").trim();
        return marker + "\n" + cleaned;
    }

    private boolean isImageExtension(String extension) {
        return SUPPORTED_IMAGE_EXTENSIONS.contains(extension);
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

    private String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
