package com.chatchat.chat.image;

import com.chatchat.agents.orchestration.AgentChatModelResolver;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.chatchat.knowledgebase.search.DocumentTextExtractor;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
public class ImageUnderstandingService {

    private static final List<String> SUPPORTED_MODES = List.of("auto", "screenshot", "document", "chart");

    private final ImageAssetRepository assetRepository;
    private final ImageAnalysisResultRepository resultRepository;
    private final ObjectMapper objectMapper;
    private final DocumentTextExtractor documentTextExtractor;
    private final AgentChatModelResolver chatModelResolver;

    @Autowired
    public ImageUnderstandingService(ImageAssetRepository assetRepository,
                                     ImageAnalysisResultRepository resultRepository,
                                     ObjectMapper objectMapper,
                                     DocumentTextExtractor documentTextExtractor,
                                     AgentChatModelResolver chatModelResolver) {
        this.assetRepository = assetRepository;
        this.resultRepository = resultRepository;
        this.objectMapper = objectMapper;
        this.documentTextExtractor = documentTextExtractor;
        this.chatModelResolver = chatModelResolver;
    }

    ImageUnderstandingService(ImageAssetRepository assetRepository,
                              ImageAnalysisResultRepository resultRepository,
                              ObjectMapper objectMapper,
                              DocumentTextExtractor documentTextExtractor) {
        this(assetRepository, resultRepository, objectMapper, documentTextExtractor, null);
    }

    @Value("${chatchat.images.storage-dir:./data/images}")
    private String storageDir;

    /**
     * Saves the image bytes into local file storage.
     *
     * @param tenantId the tenant id value
     * @param userId the user id value
     * @param originalFileName the original file name value
     * @param contentType the content type value
     * @param bytes the file bytes
     * @return the saved image asset
     */
    @Transactional
    public ImageAssetEntity saveImage(String tenantId,
                                      String userId,
                                      String originalFileName,
                                      String contentType,
                                      byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("image file is empty");
        }
        if (!isSupportedImage(contentType, originalFileName)) {
            throw new IllegalArgumentException("only png, jpg, jpeg, webp and gif images are supported");
        }
        ImageInfo imageInfo = readImageInfo(bytes);
        String fileId = UUID.randomUUID().toString();
        Path target = storageRoot().resolve(fileId + extensionOf(originalFileName, contentType));
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, bytes);
        } catch (IOException ex) {
            throw new IllegalStateException("failed to save image file", ex);
        }

        ImageAssetEntity entity = new ImageAssetEntity();
        entity.setFileId(fileId);
        entity.setTenantId(normalize(tenantId, "default"));
        entity.setUserId(normalize(userId, "anonymous"));
        entity.setOriginalFileName(safeFileName(originalFileName));
        entity.setContentType(normalize(contentType, "application/octet-stream"));
        entity.setFilePath(target.toAbsolutePath().normalize().toString());
        entity.setSizeBytes((long) bytes.length);
        entity.setWidth(imageInfo.width());
        entity.setHeight(imageInfo.height());
        entity.setSha256(sha256(bytes));
        entity.setCreatedAt(Instant.now());
        return assetRepository.save(entity);
    }

    /**
     * Analyzes an uploaded image and stores the result.
     *
     * @param fileId the file id value
     * @param question the user question value
     * @param mode the analysis mode value
     * @param tenantId the tenant id value
     * @param userId the user id value
     * @return the stored analysis result
     */
    @Transactional
    public ImageAnalysisResultEntity analyze(String fileId,
                                             String question,
                                             String mode,
                                             String tenantId,
                                             String userId) {
        return analyze(fileId, question, mode, tenantId, userId, null);
    }

    /**
     * Analyzes an uploaded image with the selected multimodal model and falls back to OCR.
     *
     * @param fileId the file id value
     * @param question the user question value
     * @param mode the analysis mode value
     * @param tenantId the tenant id value
     * @param userId the user id value
     * @param modelName the current conversation model name
     * @return the stored analysis result
     */
    @Transactional
    public ImageAnalysisResultEntity analyze(String fileId,
                                             String question,
                                             String mode,
                                             String tenantId,
                                             String userId,
                                             String modelName) {
        ImageAssetEntity asset = assetRepository.findById(requireText(fileId, "fileId"))
            .orElseThrow(() -> new IllegalArgumentException("image file not found: " + fileId));
        String normalizedTenant = normalize(tenantId, asset.getTenantId());
        if (!asset.getTenantId().equals(normalizedTenant)) {
            throw new IllegalArgumentException("image file does not belong to tenant");
        }
        String normalizedMode = normalizeMode(mode);
        VisionAttempt visionAttempt = analyzeWithVisionModel(asset, question, normalizedMode, modelName);
        VisionAnalysis vision = visionAttempt.analysis();
        String imageType;
        String extractedText;
        String summary;
        Map<String, Object> structuredData;
        double confidence;
        String analysisSource;

        if (vision != null) {
            imageType = firstText(vision.imageType(), inferImageType(asset, question, normalizedMode));
            extractedText = truncate(vision.extractedText(), 16000);
            summary = firstText(vision.summary(), "多模态模型已完成图片分析。");
            confidence = normalizeConfidence(vision.confidence(), 0.85D);
            structuredData = visionStructuredData(asset, imageType, normalizedMode, vision, modelName);
            analysisSource = "multimodal_llm";
        } else {
            imageType = inferImageType(asset, question, normalizedMode);
            extractedText = extractOcrText(asset);
            summary = buildFallbackSummary(asset, question, imageType, normalizedMode, extractedText);
            confidence = fallbackConfidence(normalizedMode, imageType, extractedText);
            structuredData = fallbackStructuredData(
                asset, imageType, normalizedMode, extractedText, modelName, visionAttempt.failureReason());
            analysisSource = "tika_ocr_fallback";
        }

        ImageAnalysisResultEntity result = new ImageAnalysisResultEntity();
        result.setFileId(asset.getFileId());
        result.setTenantId(asset.getTenantId());
        result.setUserId(normalize(userId, asset.getUserId()));
        result.setQuestion(trimToNull(question));
        result.setMode(normalizedMode);
        result.setImageType(imageType);
        result.setExtractedText(extractedText);
        result.setSummary(summary);
        result.setStructuredDataJson(writeJson(structuredData));
        result.setConfidence(confidence);
        result.setAnalysisSource(analysisSource);
        result.setStatus("COMPLETED");
        return resultRepository.save(result);
    }

    /**
     * Gets the analysis result.
     *
     * @param analysisId the analysis id value
     * @return the result
     */
    @Transactional(readOnly = true)
    public ImageAnalysisResultEntity getAnalysis(String analysisId) {
        return resultRepository.findById(requireText(analysisId, "analysisId"))
            .orElseThrow(() -> new IllegalArgumentException("image analysis not found: " + analysisId));
    }

    /**
     * Builds image context text for planner or LLM prompt injection.
     *
     * @param analysisIds the analysis ids value
     * @return the image context text
     */
    @Transactional(readOnly = true)
    public String buildContext(List<String> analysisIds) {
        if (analysisIds == null || analysisIds.isEmpty()) {
            return "";
        }
        List<String> ids = analysisIds.stream()
            .filter(id -> id != null && !id.isBlank())
            .map(String::trim)
            .distinct()
            .toList();
        if (ids.isEmpty()) {
            return "";
        }
        List<ImageAnalysisResultEntity> results = resultRepository.findByIdIn(ids);
        if (results.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder("Uploaded image analysis context:\n");
        for (ImageAnalysisResultEntity result : results) {
            builder.append("- analysisId=").append(result.getId())
                .append(", fileId=").append(result.getFileId())
                .append(", imageType=").append(result.getImageType())
                .append(", confidence=").append(result.getConfidence() == null ? "unknown" : result.getConfidence())
                .append("\n  summary: ").append(nullToEmpty(result.getSummary()))
                .append("\n  extractedText: ").append(nullToEmpty(result.getExtractedText()))
                .append("\n");
        }
        builder.append("Use the image analysis as contextual evidence. If confidence is low, say what should be verified.");
        return builder.toString();
    }

    /**
     * Converts the asset to API view.
     *
     * @param asset the asset value
     * @return the view
     */
    public ImageAssetView toAssetView(ImageAssetEntity asset) {
        return new ImageAssetView(
            asset.getFileId(),
            asset.getTenantId(),
            asset.getUserId(),
            asset.getOriginalFileName(),
            asset.getContentType(),
            asset.getSizeBytes(),
            asset.getWidth(),
            asset.getHeight(),
            asset.getSha256(),
            asset.getCreatedAt()
        );
    }

    /**
     * Converts the analysis to API view.
     *
     * @param result the result value
     * @return the view
     */
    public ImageAnalysisView toAnalysisView(ImageAnalysisResultEntity result) {
        return new ImageAnalysisView(
            result.getId(),
            result.getFileId(),
            result.getTenantId(),
            result.getUserId(),
            result.getQuestion(),
            result.getMode(),
            result.getImageType(),
            result.getExtractedText(),
            result.getSummary(),
            readJson(result.getStructuredDataJson()),
            result.getConfidence(),
            result.getAnalysisSource(),
            result.getStatus(),
            result.getCreatedAt(),
            result.getUpdatedAt()
        );
    }

    private Path storageRoot() {
        return Path.of(storageDir).toAbsolutePath().normalize();
    }

    private boolean isSupportedImage(String contentType, String fileName) {
        String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        return type.startsWith("image/")
            || name.endsWith(".png")
            || name.endsWith(".jpg")
            || name.endsWith(".jpeg")
            || name.endsWith(".bmp")
            || name.endsWith(".tif")
            || name.endsWith(".tiff")
            || name.endsWith(".webp")
            || name.endsWith(".gif");
    }

    private ImageInfo readImageInfo(byte[] bytes) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null) {
                return new ImageInfo(null, null);
            }
            return new ImageInfo(image.getWidth(), image.getHeight());
        } catch (IOException ex) {
            return new ImageInfo(null, null);
        }
    }

    private String inferImageType(ImageAssetEntity asset, String question, String mode) {
        if (!"auto".equals(mode)) {
            return mode;
        }
        String text = ((asset.getOriginalFileName() == null ? "" : asset.getOriginalFileName())
            + " " + (question == null ? "" : question)).toLowerCase(Locale.ROOT);
        if (containsAny(text, "chart", "graph", "trend", "dashboard", "折线", "柱状", "趋势", "看板", "图表")) {
            return "chart";
        }
        if (containsAny(text, "excel", "table", "report", "contract", "pdf", "表格", "报表", "合同", "单据")) {
            return "document";
        }
        Integer width = asset.getWidth();
        Integer height = asset.getHeight();
        if (width != null && height != null && width > 900 && height > 500) {
            return "screenshot";
        }
        return "screenshot";
    }

    private String extractOcrText(ImageAssetEntity asset) {
        if (asset == null || asset.getFilePath() == null || asset.getFilePath().isBlank()) {
            return "";
        }
        String fileName = nullToEmpty(asset.getOriginalFileName());
        if (!documentTextExtractor.supports(fileName)) {
            return "";
        }
        String text = documentTextExtractor.extractText(Path.of(asset.getFilePath()), fileName);
        return truncate(text, 16000);
    }

    private VisionAttempt analyzeWithVisionModel(ImageAssetEntity asset,
                                                 String question,
                                                 String mode,
                                                 String modelName) {
        if (chatModelResolver == null) {
            return new VisionAttempt(null, "multimodal model resolver is unavailable");
        }
        try {
            Path imagePath = Path.of(requireText(asset.getFilePath(), "image file path"));
            if (!Files.isRegularFile(imagePath)) {
                return new VisionAttempt(null, "stored image file is unavailable");
            }
            ChatModel chatModel = chatModelResolver.resolveChatModel(modelName);
            UserMessage message = UserMessage.from(
                TextContent.from(visionPrompt(question, mode)),
                ImageContent.from(imagePath, normalize(asset.getContentType(), "image/png"))
            );
            ChatResponse response = chatModel.chat(message);
            String responseText = response == null || response.aiMessage() == null
                ? null
                : trimToNull(response.aiMessage().text());
            if (responseText == null) {
                return new VisionAttempt(null, "multimodal model returned an empty response");
            }
            return new VisionAttempt(
                parseVisionResponse(responseText, response.modelName(), asset, question, mode),
                null
            );
        } catch (Exception ex) {
            log.warn("Multimodal image analysis failed for fileId={}, falling back to OCR: {}",
                asset.getFileId(), ex.getMessage());
            return new VisionAttempt(null, truncate(ex.getClass().getSimpleName() + ": " + nullToEmpty(ex.getMessage()), 500));
        }
    }

    private String visionPrompt(String question, String mode) {
        return """
            你是企业多模态图片分析助手。请直接理解随消息提供的图片，识别文字、表格、图表、界面结构和关键视觉关系。
            分析模式：%s
            用户问题：%s

            只返回一个 JSON 对象，不要使用 Markdown 代码块。字段如下：
            {
              "imageType": "screenshot|document|chart",
              "summary": "结合用户问题给出的完整中文分析",
              "extractedText": "图片中可确认的关键文字，无法确认的内容不要猜测",
              "confidence": 0.0,
              "observations": ["关键观察"],
              "limitations": ["需要人工复核的内容"]
            }
            confidence 取值范围为 0 到 1。必须区分图片中的事实与分析推断。
            """.formatted(mode, firstText(question, "请完整识别并解释图片内容。"));
    }

    private VisionAnalysis parseVisionResponse(String responseText,
                                               String responseModelName,
                                               ImageAssetEntity asset,
                                               String question,
                                               String mode) {
        String json = stripJsonFence(responseText);
        try {
            JsonNode root = objectMapper.readTree(json);
            if (root != null && root.isObject()) {
                Map<String, Object> modelData = objectMapper.convertValue(root, Map.class);
                return new VisionAnalysis(
                    text(root, "imageType"),
                    text(root, "summary"),
                    text(root, "extractedText"),
                    root.path("confidence").isNumber() ? root.path("confidence").doubleValue() : null,
                    responseModelName,
                    modelData
                );
            }
        } catch (Exception ignored) {
            // A useful natural-language model response is still preferable to OCR fallback.
        }
        return new VisionAnalysis(
            inferImageType(asset, question, mode),
            responseText,
            "",
            0.75D,
            responseModelName,
            Map.of("rawResponse", truncate(responseText, 16000))
        );
    }

    private Map<String, Object> visionStructuredData(ImageAssetEntity asset,
                                                     String imageType,
                                                     String mode,
                                                     VisionAnalysis vision,
                                                     String requestedModelName) {
        Map<String, Object> data = baseStructuredData(asset, imageType, mode);
        data.put("visionModelEnabled", true);
        data.put("visionAttempted", true);
        data.put("fallbackUsed", false);
        data.put("requestedModelName", trimToNull(requestedModelName));
        data.put("responseModelName", trimToNull(vision.responseModelName()));
        data.put("modelResult", vision.modelData() == null ? Map.of() : vision.modelData());
        return data;
    }

    private String buildFallbackSummary(ImageAssetEntity asset, String question, String imageType, String mode, String extractedText) {
        StringBuilder builder = new StringBuilder();
        builder.append("多模态模型识别不可用，已回退到 Tika/Tesseract OCR。图片类型：")
            .append(imageType).append("，分析模式：").append(mode).append("。");
        if (extractedText == null || extractedText.isBlank()) {
            builder.append("未提取到有效文字，请检查图片质量、语言包和 Tesseract 安装。");
        } else {
            builder.append("共提取 ").append(extractedText.length()).append(" 个字符。");
        }
        builder.append("文件：").append(nullToEmpty(asset.getOriginalFileName())).append("。");
        if (question != null && !question.isBlank()) {
            builder.append("用户问题：").append(question.trim());
        }
        return builder.toString();
    }

    private Map<String, Object> fallbackStructuredData(ImageAssetEntity asset,
                                                       String imageType,
                                                       String mode,
                                                       String extractedText,
                                                       String modelName,
                                                       String failureReason) {
        Map<String, Object> data = baseStructuredData(asset, imageType, mode);
        data.put("visionModelEnabled", true);
        data.put("visionAttempted", true);
        data.put("fallbackUsed", true);
        data.put("requestedModelName", trimToNull(modelName));
        data.put("fallbackReason", trimToNull(failureReason));
        data.put("ocrEnabled", true);
        data.put("ocrEngine", "tika+tesseract");
        data.put("sourceType", DocumentTextExtractor.OCR_CHUNK_TYPE);
        data.put("textLength", extractedText == null ? 0 : extractedText.length());
        data.put("confidenceTier", "low");
        data.put("limitations", List.of(
            "OCR回退仅提供纯文本",
            "不重建表格结构",
            "不提供视觉关系推理",
            "复杂中文排版、模糊、倾斜或手写内容置信度较低"
        ));
        return data;
    }

    private Map<String, Object> baseStructuredData(ImageAssetEntity asset, String imageType, String mode) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("fileId", asset.getFileId());
        data.put("fileName", asset.getOriginalFileName());
        data.put("contentType", asset.getContentType());
        data.put("imageType", imageType);
        data.put("mode", mode);
        data.put("width", asset.getWidth());
        data.put("height", asset.getHeight());
        data.put("sizeBytes", asset.getSizeBytes());
        return data;
    }

    private double fallbackConfidence(String mode, String imageType, String extractedText) {
        if (extractedText == null || extractedText.isBlank()) {
            return 0.2D;
        }
        double base = "auto".equals(mode) && imageType != null ? 0.52D : 0.6D;
        return Math.min(0.68D, base + Math.min(0.08D, extractedText.length() / 4000.0D));
    }

    private double normalizeConfidence(Double value, double fallback) {
        if (value == null || value.isNaN() || value.isInfinite()) {
            return fallback;
        }
        return Math.max(0D, Math.min(1D, value));
    }

    private String stripJsonFence(String value) {
        String text = value == null ? "" : value.trim();
        if (text.startsWith("```")) {
            int firstLine = text.indexOf('\n');
            int lastFence = text.lastIndexOf("```");
            if (firstLine >= 0 && lastFence > firstLine) {
                return text.substring(firstLine + 1, lastFence).trim();
            }
        }
        int objectStart = text.indexOf('{');
        int objectEnd = text.lastIndexOf('}');
        return objectStart >= 0 && objectEnd > objectStart
            ? text.substring(objectStart, objectEnd + 1)
            : text;
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : trimToNull(value.asText());
    }

    private String firstText(String... values) {
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    return value.trim();
                }
            }
        }
        return null;
    }

    private String normalizeMode(String mode) {
        String normalized = normalize(mode, "auto").toLowerCase(Locale.ROOT);
        return SUPPORTED_MODES.contains(normalized) ? normalized : "auto";
    }

    private String writeJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException ex) {
            return "{}";
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private boolean containsAny(String text, String... terms) {
        if (text == null || terms == null) {
            return false;
        }
        for (String term : terms) {
            if (term != null && !term.isBlank() && text.contains(term.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private String extensionOf(String fileName, String contentType) {
        String name = safeFileName(fileName).toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        if (dot >= 0 && dot < name.length() - 1) {
            String ext = name.substring(dot);
            if (List.of(".png", ".jpg", ".jpeg", ".bmp", ".tif", ".tiff", ".webp", ".gif").contains(ext)) {
                return ext;
            }
        }
        String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (type.contains("png")) {
            return ".png";
        }
        if (type.contains("webp")) {
            return ".webp";
        }
        if (type.contains("gif")) {
            return ".gif";
        }
        if (type.contains("bmp")) {
            return ".bmp";
        }
        if (type.contains("tiff") || type.contains("tif")) {
            return ".tiff";
        }
        return ".jpg";
    }

    private String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (Exception ex) {
            return null;
        }
    }

    private String safeFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "image";
        }
        return Path.of(fileName).getFileName().toString();
    }

    private String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private String nullToEmpty(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value == null ? "" : value;
        }
        return value.substring(0, Math.max(0, maxLength - 3)) + "...";
    }

    private record ImageInfo(Integer width, Integer height) {
    }

    private record VisionAttempt(VisionAnalysis analysis, String failureReason) {
    }

    private record VisionAnalysis(
        String imageType,
        String summary,
        String extractedText,
        Double confidence,
        String responseModelName,
        Map<String, Object> modelData
    ) {
    }

    public record ImageAssetView(
        String fileId,
        String tenantId,
        String userId,
        String originalFileName,
        String contentType,
        Long sizeBytes,
        Integer width,
        Integer height,
        String sha256,
        Instant createdAt
    ) {
    }

    public record ImageAnalysisView(
        String id,
        String fileId,
        String tenantId,
        String userId,
        String question,
        String mode,
        String imageType,
        String extractedText,
        String summary,
        Map<String, Object> structuredData,
        Double confidence,
        String analysisSource,
        String status,
        Instant createdAt,
        Instant updatedAt
    ) {
    }
}
