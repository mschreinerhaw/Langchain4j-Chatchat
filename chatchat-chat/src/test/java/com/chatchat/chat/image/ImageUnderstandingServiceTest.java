package com.chatchat.chat.image;

import com.chatchat.agents.orchestration.AgentChatModelResolver;
import com.chatchat.knowledgebase.search.DocumentTextExtractor;
import com.chatchat.knowledgebase.search.SearchProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ImageUnderstandingServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void analyzeFallsBackToTikaOcrAndStoresWeakOcrEvidence() {
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
        assertThat(result.getAnalysisSource()).isEqualTo("tika_ocr_fallback");
        assertThat(result.getConfidence()).isGreaterThan(0.0D).isLessThanOrEqualTo(0.68D);
        assertThat(service.toAnalysisView(result).structuredData())
            .containsEntry("ocrEnabled", true)
            .containsEntry("ocrEngine", "tika+tesseract")
            .containsEntry("sourceType", "ocr_text")
            .containsEntry("confidenceTier", "low")
            .containsEntry("fallbackUsed", true);
        verify(resultRepository).save(any(ImageAnalysisResultEntity.class));
    }

    @Test
    void analyzeUsesSelectedMultimodalModelBeforeOcr() throws Exception {
        ImageAssetRepository assetRepository = mock(ImageAssetRepository.class);
        ImageAnalysisResultRepository resultRepository = mock(ImageAnalysisResultRepository.class);
        DocumentTextExtractor extractor = mock(DocumentTextExtractor.class);
        AgentChatModelResolver resolver = mock(AgentChatModelResolver.class);
        ChatModel chatModel = mock(ChatModel.class);
        ChatResponse response = mock(ChatResponse.class);
        Path imageFile = tempDir.resolve("chart.png");
        Files.write(imageFile, new byte[]{1, 2, 3});

        ImageAssetEntity asset = asset();
        asset.setFilePath(imageFile.toString());
        when(assetRepository.findById("file-1")).thenReturn(Optional.of(asset));
        when(resultRepository.save(any(ImageAnalysisResultEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(resolver.resolveChatModel("vision-model")).thenReturn(chatModel);
        when(response.aiMessage()).thenReturn(AiMessage.from("""
            {"imageType":"chart","summary":"图表显示收入增长。","extractedText":"收入 128","confidence":0.91,
             "observations":["收入增长"],"limitations":[]}
            """));
        when(response.modelName()).thenReturn("vision-model");
        when(chatModel.chat(any(ChatMessage[].class))).thenReturn(response);

        ImageUnderstandingService service = new ImageUnderstandingService(
            assetRepository,
            resultRepository,
            new ObjectMapper(),
            extractor,
            resolver
        );
        ImageAnalysisResultEntity result = service.analyze(
            "file-1", "分析趋势", "chart", "tenant-a", "user-a", "vision-model");

        assertThat(result.getAnalysisSource()).isEqualTo("multimodal_llm");
        assertThat(result.getSummary()).isEqualTo("图表显示收入增长。");
        assertThat(result.getExtractedText()).isEqualTo("收入 128");
        assertThat(result.getConfidence()).isEqualTo(0.91D);
        assertThat(service.toAnalysisView(result).structuredData())
            .containsEntry("visionModelEnabled", true)
            .containsEntry("fallbackUsed", false)
            .containsEntry("requestedModelName", "vision-model")
            .containsEntry("responseModelName", "vision-model");
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
