package com.chatchat.chat.image;

import com.chatchat.knowledgebase.search.DocumentTextExtractor;
import com.chatchat.knowledgebase.search.SearchProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ImageUnderstandingServiceTest {

    @Test
    void analyzeUsesTikaOcrExtractorAndStoresWeakOcrEvidence() {
        ImageAssetRepository assetRepository = mock(ImageAssetRepository.class);
        ImageAnalysisResultRepository resultRepository = mock(ImageAnalysisResultRepository.class);
        DocumentTextExtractor extractor = new FakeOcrExtractor("# OCR_TEXT\nInvoice total 128.00");
        ImageUnderstandingService service = new ImageUnderstandingService(
            assetRepository,
            resultRepository,
            new ObjectMapper(),
            extractor
        );
        ImageAssetEntity asset = asset();
        when(assetRepository.findById("file-1")).thenReturn(Optional.of(asset));
        when(resultRepository.save(any(ImageAnalysisResultEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ImageAnalysisResultEntity result = service.analyze(
            "file-1",
            "read invoice text",
            "document",
            "tenant-a",
            "user-a"
        );

        assertThat(result.getExtractedText()).contains("Invoice total 128.00");
        assertThat(result.getAnalysisSource()).isEqualTo("tika_ocr");
        assertThat(result.getConfidence()).isGreaterThan(0.0D).isLessThanOrEqualTo(0.68D);
        assertThat(service.toAnalysisView(result).structuredData())
            .containsEntry("ocrEnabled", true)
            .containsEntry("ocrEngine", "tika+tesseract")
            .containsEntry("sourceType", "ocr_text")
            .containsEntry("confidenceTier", "low");
        verify(resultRepository).save(any(ImageAnalysisResultEntity.class));
    }

    private ImageAssetEntity asset() {
        ImageAssetEntity asset = new ImageAssetEntity();
        asset.setFileId("file-1");
        asset.setTenantId("tenant-a");
        asset.setUserId("user-a");
        asset.setOriginalFileName("invoice.png");
        asset.setContentType("image/png");
        asset.setFilePath("D:/tmp/invoice.png");
        asset.setSizeBytes(1024L);
        asset.setWidth(800);
        asset.setHeight(600);
        asset.setSha256("abc");
        asset.setCreatedAt(Instant.now());
        return asset;
    }

    private static class FakeOcrExtractor extends DocumentTextExtractor {

        private final String text;

        private FakeOcrExtractor(String text) {
            super(new SearchProperties());
            this.text = text;
        }

        @Override
        public String extractText(Path file, String fileName) {
            return text;
        }

        @Override
        public boolean supports(String fileName) {
            return true;
        }
    }
}
