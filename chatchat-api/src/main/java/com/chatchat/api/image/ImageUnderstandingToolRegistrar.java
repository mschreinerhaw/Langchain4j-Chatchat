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
            .title("多模态图片理解")
            .description("使用当前 Agent 选择的多模态大模型直接理解已上传图片，可识别截图、扫描文档、表格、图表及视觉关系。"
                + "当模型不支持图片、调用失败或返回空结果时，自动回退到 Apache Tika/Tesseract OCR，并在结果中明确标记分析来源和限制。")
            .version("2.0.0")
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
            .timeoutMillis(180000L)
            .agentCompatible(true)
            .parameters(List.of(
                ToolParameter.builder()
                    .name("fileId")
                    .type("string")
                    .description("已上传图片的文件编号。")
                    .required(true)
                    .minLength(1)
                    .maxLength(128)
                    .build(),
                ToolParameter.builder()
                    .name("question")
                    .type("string")
                    .description("用户希望模型重点分析的问题；模型会结合图片文字、结构和视觉关系回答。")
                    .required(false)
                    .maxLength(2000)
                    .build(),
                ToolParameter.builder()
                    .name("mode")
                    .type("string")
                    .description("图片分析模式：auto、screenshot、document 或 chart。")
                    .required(false)
                    .defaultValue("auto")
                    .build()
            ))
            .tags(List.of("image", "ocr", "vision", "agent"))
            .metadata(Map.of(
                "engine", "current_multimodal_model_with_ocr_fallback",
                "primarySourceType", "MULTIMODAL_LLM",
                "fallbackSourceType", "OCR_TEXT",
                "behaviorRules", List.of(
                    "优先使用当前会话模型直接分析图片",
                    "仅在模型不支持图片、调用失败或空响应时回退OCR",
                    "结果必须标记 multimodal_llm 或 tika_ocr_fallback 来源",
                    "OCR回退结果不得声称具备表格重建或视觉关系推理能力"
                )
            ))
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
                String modelName = input.getContext() == null ? null : String.valueOf(input.getContext().getOrDefault("modelName", ""));
                var result = imageUnderstandingService.analyze(fileId, question, mode, tenantId, input.getUserId(), modelName);
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
