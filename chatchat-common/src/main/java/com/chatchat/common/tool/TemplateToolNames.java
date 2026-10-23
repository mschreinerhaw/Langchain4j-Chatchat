package com.chatchat.common.tool;

/** Stable template capability identity, independent of display names and local service aliases. */
public final class TemplateToolNames {
    private TemplateToolNames() { }
    public static String remote(String value) {
        if (value == null) return null;
        var matcher = java.util.regex.Pattern.compile("(?:^|_)(tpl_[0-9a-f]{32})$").matcher(value);
        return matcher.find() ? matcher.group(1) : null;
    }
    public static String local(String value) {
        String remote = remote(value);
        if (remote == null) throw new IllegalArgumentException("Invalid template capability name");
        return "mcp_chatchat_mcp_server_" + remote;
    }
}
