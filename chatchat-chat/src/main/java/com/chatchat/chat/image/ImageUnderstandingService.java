package com.chatchat.chat.image;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
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

@Service
@RequiredArgsConstructor
public class ImageUnderstandingService {

    private static final List<String> SUPPORTED_MODES = List.of("auto", "screenshot", "document", "chart");

    private final ImageAssetRepository assetRepository;
    private final ImageAnalysisResultRepository resultRepository;
    private final ObjectMapper objectMapper;

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
        ImageAssetEntity asset = assetRepository.findById(requireText(fileId, "fileId"))
            .orElseThrow(() -> new IllegalArgumentException("image file not found: " + fileId));
        String normalizedTenant = normalize(tenantId, asset.getTenantId());
        if (!asset.getTenantId().equals(normalizedTenant)) {
            throw new IllegalArgumentException("image file does not belong to tenant");
        }
        String normalizedMode = normalizeMode(mode);
        String imageType = inferImageType(asset, question, normalizedMode);
        String extractedText = buildExtractedText(asset, imageType);
        String summary = buildSummary(asset, question, imageType, normalizedMode);
        Map<String, Object> structuredData = structuredData(asset, imageType, normalizedMode);

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
        result.setConfidence(confidence(normalizedMode, imageType));
        result.setAnalysisSource("rule");
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

    private String buildExtractedText(ImageAssetEntity asset, String imageType) {
        return "MVP local parser: OCR/vision model is not configured yet. "
            + "Captured image metadata only. "
            + "fileName=" + nullToEmpty(asset.getOriginalFileName())
            + ", imageType=" + imageType
            + ", size=" + nullToEmpty(asset.getSizeBytes())
            + ", dimensions=" + nullToEmpty(asset.getWidth()) + "x" + nullToEmpty(asset.getHeight());
    }

    private String buildSummary(ImageAssetEntity asset, String question, String imageType, String mode) {
        StringBuilder builder = new StringBuilder();
        builder.append("Image classified as ").append(imageType)
            .append(" with mode ").append(mode).append(". ");
        if ("screenshot".equals(imageType)) {
            builder.append("Suitable for system page, error screenshot, or configuration screenshot review. ");
        } else if ("document".equals(imageType)) {
            builder.append("Suitable for OCR/table/document extraction once the OCR adapter is enabled. ");
        } else if ("chart".equals(imageType)) {
            builder.append("Suitable for chart trend and anomaly interpretation once the vision adapter is enabled. ");
        }
        builder.append("Stored file ").append(asset.getFileId())
            .append(" (").append(nullToEmpty(asset.getOriginalFileName())).append(").");
        if (question != null && !question.isBlank()) {
            builder.append(" User question: ").append(question.trim());
        }
        return builder.toString();
    }

    private Map<String, Object> structuredData(ImageAssetEntity asset, String imageType, String mode) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("fileId", asset.getFileId());
        data.put("fileName", asset.getOriginalFileName());
        data.put("contentType", asset.getContentType());
        data.put("imageType", imageType);
        data.put("mode", mode);
        data.put("width", asset.getWidth());
        data.put("height", asset.getHeight());
        data.put("sizeBytes", asset.getSizeBytes());
        data.put("ocrEnabled", false);
        data.put("visionModelEnabled", false);
        data.put("nextAdapter", imageType.equals("document") ? "ocr_table_extractor" : "vision_model");
        return data;
    }

    private double confidence(String mode, String imageType) {
        return "auto".equals(mode) && imageType != null ? 0.46D : 0.58D;
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
            if (List.of(".png", ".jpg", ".jpeg", ".webp", ".gif").contains(ext)) {
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

    private record ImageInfo(Integer width, Integer height) {
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
