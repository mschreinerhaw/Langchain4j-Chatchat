package com.chatchat.chat.interaction.service.handler;

/** Escapes untrusted text before placing it inside a prompt markup boundary. */
final class PromptBoundaryEscaper {

    private PromptBoundaryEscaper() { }

    static String escapeMarkupText(String value) {
        if (value == null || value.isEmpty()) return "";
        StringBuilder escaped = new StringBuilder(value.length());
        value.codePoints().forEach(codePoint -> {
            switch (codePoint) {
                case '&' -> escaped.append("&amp;");
                case '<' -> escaped.append("&lt;");
                case '>' -> escaped.append("&gt;");
                default -> {
                    if (codePoint == '\n' || codePoint == '\r' || codePoint == '\t'
                        || !Character.isISOControl(codePoint)) {
                        escaped.appendCodePoint(codePoint);
                    }
                }
            }
        });
        return escaped.toString();
    }
}
