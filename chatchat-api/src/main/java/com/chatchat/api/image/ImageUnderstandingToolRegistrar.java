package com.chatchat.api.image;

import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.chat.image.ImageUnderstandingService;
import com.chatchat.common.tool.ToolInput;
import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.common.tool.ToolOutput;
import com.chatchat.common.tool.ToolParameter;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class ImageUnderstandingToolRegistrar {

    private final ToolRegistry toolRegistry;
    private final ImageUnderstandingService imageUnderstandingService;

    @PostConstruct
    public void register() {
        ToolMetadata metadata = ToolMetadata.builder()
            .id("image_understanding")
            .title("Image Understanding")
            .description("Analyze uploaded screenshots, document images, tables, reports, contracts and charts. "
                + "Use it when a user asks about a previously uploaded image fileId.")
            .version("1.0.0")
            .author("ChatChat System")
            .categories(List.of("multimodal", "image", "ocr", "vision"))
            .category("multimodal_image_understanding")
            .riskLevel("low")
            .operationType("read")
            .runtimeLevel("readonly")
            .userVisible(true)
            .confirmation(Map.of("default", "auto_execute", "allow_user_override", false))
            .permissions(Map.of("roles", List.of()))
            .inputPolicy(Map.of(
                "must_show_parameters", true,
                "allow_auto_fill", true,
                "sensitive_params", List.of()
            ))
            .outputPolicy(Map.of("mask_fields", List.of()))
            .outputType("json")
            .returnDirect(false)
            .timeoutMillis(15000L)
            .agentCompatible(true)
            .parameters(List.of(
                ToolParameter.builder()
                    .name("fileId")
                    .type("string")
                    .description("Uploaded image file id.")
                    .required(true)
                    .minLength(1)
                    .maxLength(128)
                    .build(),
                ToolParameter.builder()
                    .name("question")
                    .type("string")
                    .description("User question about the image.")
                    .required(false)
                    .maxLength(2000)
                    .build(),
                ToolParameter.builder()
                    .name("mode")
                    .type("string")
                    .description("Analysis mode: auto, screenshot, document or chart.")
                    .required(false)
                    .defaultValue("auto")
                    .build()
            ))
            .tags(List.of("image", "ocr", "vision", "agent"))
            .build();
        toolRegistry.registerTool("image_understanding", metadata, new ImageUnderstandingTool(metadata));
    }

    private class ImageUnderstandingTool implements ToolRegistry.EnhancedTool {

        private final ToolMetadata metadata;

        private ImageUnderstandingTool(ToolMetadata metadata) {
            this.metadata = metadata;
        }

        @Override
        public ToolMetadata getMetadata() {
            return metadata;
        }

        @Override
        public ToolOutput execute(ToolInput input) {
            try {
                String fileId = input.getParameterAsString("fileId", "");
                if (fileId == null || fileId.isBlank()) {
                    return ToolOutput.failure("fileId parameter is required");
                }
                String question = input.getParameterAsString("question", input.getRawInput());
                String mode = input.getParameterAsString("mode", "auto");
                String tenantId = input.getContext() == null ? null : String.valueOf(input.getContext().getOrDefault("tenantId", "default"));
                var result = imageUnderstandingService.analyze(fileId, question, mode, tenantId, input.getUserId());
                return ToolOutput.success(
                    imageUnderstandingService.toAnalysisView(result),
                    "Image understanding completed"
                );
            } catch (Exception ex) {
                return ToolOutput.failure(ex);
            }
        }
    }
}
