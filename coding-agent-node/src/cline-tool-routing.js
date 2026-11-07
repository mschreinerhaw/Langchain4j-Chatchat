// Generated from unmodified Cline 0.0.93 source; Apache-2.0.
// Commit 1a5c3f84e0933268a9dba14a55a6383a6578414d; source SHA256 8ee931d74ef60f9a601efbe4beb58f619fc8227dad9ab7f6454243db71c651a2
const TOOL_NAME_TO_FLAG = {
    read_files: "enableReadFiles",
    search_codebase: "enableSearch",
    run_commands: "enableBash",
    fetch_web_content: "enableWebFetch",
    apply_patch: "enableApplyPatch",
    editor: "enableEditor",
    skills: "enableSkills",
    ask_question: "enableAskQuestion",
    submit_and_exit: "enableSubmitAndExit",
};
export const DEFAULT_MODEL_TOOL_ROUTING_RULES = [
    {
        name: "openai-native-use-apply-patch",
        mode: "act",
        providerIdIncludes: ["openai-native"],
        enableTools: ["apply_patch"],
        disableTools: ["editor"],
    },
    {
        name: "codex-and-gpt-use-apply-patch",
        mode: "act",
        modelIdIncludes: ["codex", "gpt"],
        enableTools: ["apply_patch"],
        disableTools: ["editor"],
    },
];
function matchesModelId(modelId, includes) {
    if (!includes || includes.length === 0) {
        return true;
    }
    const normalizedModelId = modelId.toLowerCase();
    return includes.some((value) => normalizedModelId.includes(value.toLowerCase()));
}
function matchesRule(rule, providerId, modelId, mode) {
    if (rule.mode && rule.mode !== "any" && rule.mode !== mode) {
        return false;
    }
    return (matchesModelId(providerId, rule.providerIdIncludes) &&
        matchesModelId(modelId, rule.modelIdIncludes));
}
export function resolveToolRoutingConfig(providerId, modelId, mode, rules) {
    if (!rules || rules.length === 0) {
        return {};
    }
    const toggles = new Map();
    for (const rule of rules) {
        if (!matchesRule(rule, providerId, modelId, mode)) {
            continue;
        }
        for (const toolName of rule.disableTools ?? []) {
            toggles.set(toolName, false);
        }
        for (const toolName of rule.enableTools ?? []) {
            toggles.set(toolName, true);
        }
    }
    const config = {};
    for (const [toolName, enabled] of toggles.entries()) {
        config[TOOL_NAME_TO_FLAG[toolName]] = enabled;
    }
    return config;
}
