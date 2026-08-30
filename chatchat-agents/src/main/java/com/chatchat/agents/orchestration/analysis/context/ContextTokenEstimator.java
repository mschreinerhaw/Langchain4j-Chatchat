package com.chatchat.agents.orchestration.analysis.context;

import java.lang.reflect.Array;
import java.util.Map;

/**
 * Lightweight token estimator for mixed Chinese, English and structured JSON
 * context. It intentionally avoids serializing the complete value.
 */
public final class ContextTokenEstimator {

    public record Size(long chars, long tokens) {
        public Size plus(Size other) {
            return new Size(chars + other.chars, tokens + other.tokens);
        }
    }

    public Size estimate(Object value) {
        return estimate(value, 0);
    }

    private Size estimate(Object value, int depth) {
        if (value == null) {
            return new Size(4, 1);
        }
        if (value instanceof CharSequence text) {
            return estimateText(text.toString());
        }
        if (value instanceof Number || value instanceof Boolean || value instanceof Enum<?>) {
            String text = String.valueOf(value);
            return new Size(text.length(), Math.max(1, (text.length() + 2L) / 3L));
        }
        if (depth > 64) {
            return new Size(16, 4);
        }
        Size size = new Size(2, 1);
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                size = size.plus(estimateText(String.valueOf(entry.getKey())))
                    .plus(new Size(2, 1))
                    .plus(estimate(entry.getValue(), depth + 1));
            }
            return size;
        }
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                size = size.plus(estimate(item, depth + 1)).plus(new Size(1, 1));
            }
            return size;
        }
        if (value.getClass().isArray()) {
            for (int index = 0; index < Array.getLength(value); index++) {
                size = size.plus(estimate(Array.get(value, index), depth + 1))
                    .plus(new Size(1, 1));
            }
            return size;
        }
        return estimateText(String.valueOf(value));
    }

    private Size estimateText(String text) {
        if (text == null || text.isEmpty()) {
            return new Size(0, 0);
        }
        long cjk = 0;
        long latin = 0;
        long structural = 0;
        for (int offset = 0; offset < text.length();) {
            int codePoint = text.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (isCjk(codePoint)) {
                cjk++;
            } else if (Character.isLetterOrDigit(codePoint) || Character.isWhitespace(codePoint)) {
                latin++;
            } else {
                structural++;
            }
        }
        long tokens = divideCeil(cjk * 10L, 17L)
            + divideCeil(latin, 4L)
            + divideCeil(structural, 2L);
        return new Size(text.length(), Math.max(1, tokens));
    }

    private boolean isCjk(int codePoint) {
        Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        return script == Character.UnicodeScript.HAN
            || script == Character.UnicodeScript.HIRAGANA
            || script == Character.UnicodeScript.KATAKANA
            || script == Character.UnicodeScript.HANGUL;
    }

    private long divideCeil(long value, long divisor) {
        return value == 0 ? 0 : (value + divisor - 1) / divisor;
    }
}
