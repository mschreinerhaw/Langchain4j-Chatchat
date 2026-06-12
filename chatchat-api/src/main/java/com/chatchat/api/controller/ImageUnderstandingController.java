package com.chatchat.api.controller;

import com.chatchat.api.security.ApiAuthenticationFilter;
import com.chatchat.chat.image.ImageAssetEntity;
import com.chatchat.chat.image.ImageUnderstandingService;
import com.chatchat.common.constants.AppConstants;
import com.chatchat.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequiredArgsConstructor
@RequestMapping(AppConstants.API_V1 + "/images")
@Tag(name = "Image Understanding", description = "Upload images and store multimodal analysis results")
public class ImageUnderstandingController {

    private final ImageUnderstandingService imageUnderstandingService;

    @Value("${chatchat.images.max-upload-bytes:10485760}")
    private long maxUploadBytes;

    /**
     * Uploads an image into the file store.
     *
     * @param file the file value
     * @param tenantId the tenant id value
     * @param request the request value
     * @return the uploaded asset
     */
    @PostMapping("/upload")
    @Operation(summary = "Upload one image for multimodal analysis")
    public ApiResponse<ImageUnderstandingService.ImageAssetView> upload(@RequestPart("file") MultipartFile file,
                                                                        @RequestParam(value = "tenantId", required = false) String tenantId,
                                                                        HttpServletRequest request) {
        try {
            if (file == null || file.isEmpty()) {
                return ApiResponse.badRequest("image file is required");
            }
            if (file.getSize() > maxUploadBytes) {
                return ApiResponse.badRequest("image file is too large");
            }
            ImageAssetEntity asset = imageUnderstandingService.saveImage(
                firstText(tenantId, currentTenantId(request), "default"),
                firstText(currentUsername(request), currentUserId(request), "anonymous"),
                file.getOriginalFilename(),
                file.getContentType(),
                file.getBytes()
            );
            return ApiResponse.success(imageUnderstandingService.toAssetView(asset), "image uploaded");
        } catch (IllegalArgumentException ex) {
            return ApiResponse.badRequest(ex.getMessage());
        } catch (Exception ex) {
            return ApiResponse.internalError("image upload failed: " + ex.getMessage());
        }
    }

    /**
     * Analyzes one uploaded image.
     *
     * @param requestBody the request body value
     * @param servletRequest the servlet request value
     * @return the analysis result
     */
    @PostMapping("/analyze")
    @Operation(summary = "Analyze one uploaded image and store the result")
    public ApiResponse<ImageUnderstandingService.ImageAnalysisView> analyze(@org.springframework.web.bind.annotation.RequestBody AnalyzeRequest requestBody,
                                                                           HttpServletRequest servletRequest) {
        try {
            ImageUnderstandingService.ImageAnalysisView view = imageUnderstandingService.toAnalysisView(
                imageUnderstandingService.analyze(
                    requestBody == null ? null : requestBody.fileId(),
                    requestBody == null ? null : requestBody.question(),
                    requestBody == null ? "auto" : requestBody.mode(),
                    firstText(requestBody == null ? null : requestBody.tenantId(), currentTenantId(servletRequest), "default"),
                    firstText(currentUsername(servletRequest), currentUserId(servletRequest), "anonymous")
                )
            );
            return ApiResponse.success(view, "image analyzed");
        } catch (IllegalArgumentException ex) {
            return ApiResponse.badRequest(ex.getMessage());
        } catch (Exception ex) {
            return ApiResponse.internalError("image analysis failed: " + ex.getMessage());
        }
    }

    /**
     * Gets one image analysis result.
     *
     * @param analysisId the analysis id value
     * @return the analysis result
     */
    @GetMapping("/analysis/{analysisId}")
    @Operation(summary = "Get one stored image analysis result")
    public ApiResponse<ImageUnderstandingService.ImageAnalysisView> getAnalysis(@PathVariable("analysisId") String analysisId) {
        try {
            return ApiResponse.success(imageUnderstandingService.toAnalysisView(
                imageUnderstandingService.getAnalysis(analysisId)
            ));
        } catch (IllegalArgumentException ex) {
            return ApiResponse.badRequest(ex.getMessage());
        }
    }

    private String currentUserId(HttpServletRequest request) {
        return requestAttribute(request, ApiAuthenticationFilter.CURRENT_USER_ID);
    }

    private String currentUsername(HttpServletRequest request) {
        return requestAttribute(request, ApiAuthenticationFilter.CURRENT_USERNAME);
    }

    private String currentTenantId(HttpServletRequest request) {
        return requestAttribute(request, ApiAuthenticationFilter.CURRENT_TENANT_ID);
    }

    private String requestAttribute(HttpServletRequest request, String name) {
        if (request == null) {
            return null;
        }
        Object value = request.getAttribute(name);
        return value == null ? null : String.valueOf(value);
    }

    private String firstText(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    public record AnalyzeRequest(
        String fileId,
        String question,
        String mode,
        String tenantId
    ) {
    }
}
