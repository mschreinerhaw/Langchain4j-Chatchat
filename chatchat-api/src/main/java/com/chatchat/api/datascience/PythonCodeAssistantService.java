package com.chatchat.api.datascience;

import com.chatchat.agents.model.ConfigurableChatModelFactory;
import com.chatchat.common.config.ModelsConfig;
import dev.langchain4j.model.chat.ChatModel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class PythonCodeAssistantService {
    private static final int MAX_PROMPT_LENGTH = 4_000;
    private static final int MAX_SOURCE_LENGTH = 200_000;
    private static final int MAX_SELECTION_LENGTH = 40_000;

    private final ChatModel chatModel;
    private final ModelsConfig modelsConfig;
    private final ConfigurableChatModelFactory chatModelFactory;

    public List<ModelOption> models() {
        String defaultModel = text(modelsConfig.getDefaultChatModel()).trim();
        LinkedHashSet<String> names = new LinkedHashSet<>(modelsConfig.getAvailableChatModels());
        if (!defaultModel.isBlank()) names.add(defaultModel);
        return names.stream().filter(name -> name != null && !name.isBlank()).map(String::trim).distinct()
                .map(name -> new ModelOption(name, name, name.equalsIgnoreCase(defaultModel))).toList();
    }

    public AssistResponse assist(AssistRequest request) {
        if (request == null || blank(request.prompt())) {
            throw new IllegalArgumentException("请先描述希望 AI 完成的 Python 开发任务");
        }
        String prompt = limited(request.prompt(), MAX_PROMPT_LENGTH, "提示词");
        String source = limited(text(request.sourceCode()), MAX_SOURCE_LENGTH, "脚本源码");
        String selection = limited(text(request.selectedCode()), MAX_SELECTION_LENGTH, "选中代码");
        String action = normalizeAction(request.action());
        String modelName = resolveModelName(request.modelName());
        String instruction = switch (action) {
            case "continue" ->
                    "在保持已有实现和代码风格的前提下续写代码。只返回适合插入光标位置的新代码，不要重复上下文。";
            case "fix" -> "定位并修复选中代码或完整脚本中的问题。返回修复后的选中代码；没有选区时返回完整脚本。";
            case "optimize" ->
                    "优化选中代码或完整脚本的可读性、健壮性和性能。返回优化后的选中代码；没有选区时返回完整脚本。";
            default -> "根据需求生成可直接运行的 Python 代码。有选区时返回用于替换选区的代码，没有选区时返回完整脚本。";
        };
        String modelPrompt = """
                你是企业数据科学平台中的 Python 编程助手。代码将在受限 Docker 容器中执行。
                必须遵守：
                1. 只输出 Python 源码，不要 Markdown 代码围栏，不要解释文字。
                2. 输入参数来自环境变量 CHATCHAT_INPUT_JSON，必须解析为 JSON 对象；完整脚本必须包含可实际执行的入口调用，最终结果输出为 JSON。
                3. 不得建议安装依赖、调用 shell、访问宿主机或绕过容器限制。
                4. 未明确要求联网时，不生成网络访问代码。
                5. 优先使用当前脚本已有依赖和编码风格。
                
                操作：%s
                用户需求：%s
                
                当前完整脚本：
                ---
                %s
                ---
                
                当前选中代码：
                ---
                %s
                ---
                """.formatted(instruction, prompt, source, selection);
        ChatModel selectedModel = modelName.equalsIgnoreCase(text(modelsConfig.getDefaultChatModel()).trim())
                ? chatModel : chatModelFactory.create(modelName);
        String generated = stripCodeFence(selectedModel.chat(modelPrompt));
        if (generated.isBlank()) {
            throw new IllegalStateException("模型未生成可用的 Python 代码");
        }
        return new AssistResponse(generated, action, !selection.isBlank(), modelName);
    }

    private String resolveModelName(String requested) {
        String candidate = text(requested).trim();
        List<ModelOption> options = models();
        if (candidate.isBlank() || "default".equalsIgnoreCase(candidate)) {
            candidate = options.stream().filter(ModelOption::defaultModel).map(ModelOption::value).findFirst()
                    .orElseGet(() -> options.stream().map(ModelOption::value).findFirst().orElse(""));
        }
        String selected = candidate;
        return options.stream().map(ModelOption::value).filter(name -> name.equalsIgnoreCase(selected)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("所选模型不可用或未配置：" + selected));
    }

    private String normalizeAction(String value) {
        String action = text(value).toLowerCase(Locale.ROOT);
        return switch (action) {
            case "continue", "fix", "optimize" -> action;
            default -> "generate";
        };
    }

    private String limited(String value, int max, String label) {
        if (value.length() > max) throw new IllegalArgumentException(label + "超过长度限制 " + max);
        return value;
    }

    private String stripCodeFence(String value) {
        String result = text(value).trim();
        if (!result.startsWith("```")) return result;
        int firstLine = result.indexOf('\n');
        int lastFence = result.lastIndexOf("```");
        if (firstLine < 0 || lastFence <= firstLine) return result;
        return result.substring(firstLine + 1, lastFence).trim();
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private String text(String value) {
        return value == null ? "" : value;
    }

    public record ModelOption(String value, String label, boolean defaultModel) {
    }

    public record AssistRequest(String action, String prompt, String sourceCode, String selectedCode,
                                String modelName) {
    }

    public record AssistResponse(String code, String action, boolean replaceSelection, String modelName) {
    }
}
