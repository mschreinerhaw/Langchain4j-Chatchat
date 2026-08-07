package com.chatchat.mcpserver.templatepublication;

import com.chatchat.common.tool.McpToolNamePolicy;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Naming contract for dynamically published template discovery tools. */
public final class TemplateQueryToolNamePolicy {

    public static final String SUFFIX = "_template_query";
    private static final Pattern DOMAIN_CODE = Pattern.compile("[a-z][a-z0-9_]{0,63}");
    private static final Set<String> RESERVED_TOOL_NAMES = Set.of(
        "api_template_query",
        "ssh_template_query",
        "sql_datasource_template_query",
        "http_endpoint_template_query",
        "database_query_template_query"
    );

    private TemplateQueryToolNamePolicy() {
    }

    public static String requireDomainCode(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("domainCode is required");
        }
        String domainCode = value.trim();
        if (!domainCode.equals(value) || !domainCode.equals(domainCode.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("domainCode must use lowercase letters, numbers, and underscores");
        }
        if (!DOMAIN_CODE.matcher(domainCode).matches()) {
            throw new IllegalArgumentException(
                "domainCode must start with a lowercase letter and contain only lowercase letters, numbers, or underscores");
        }
        if (domainCode.endsWith(SUFFIX)) {
            throw new IllegalArgumentException("Enter only the domainCode; the _template_query suffix is system managed");
        }
        return domainCode;
    }

    public static String toolName(String domainCode) {
        String toolName = McpToolNamePolicy.requirePublishableName(requireDomainCode(domainCode) + SUFFIX);
        if (RESERVED_TOOL_NAMES.contains(toolName)) {
            throw new IllegalArgumentException("Template query tool name is reserved by a system tool: " + toolName);
        }
        return toolName;
    }

    public static String requireToolName(String toolName) {
        McpToolNamePolicy.requirePublishableName(toolName);
        if ("template_query".equals(toolName) || !toolName.endsWith(SUFFIX)) {
            throw new IllegalArgumentException("Template query tool name must match <domain>_template_query");
        }
        requireDomainCode(toolName.substring(0, toolName.length() - SUFFIX.length()));
        if (RESERVED_TOOL_NAMES.contains(toolName)) {
            throw new IllegalArgumentException("Template query tool name is reserved by a system tool: " + toolName);
        }
        return toolName;
    }
}
