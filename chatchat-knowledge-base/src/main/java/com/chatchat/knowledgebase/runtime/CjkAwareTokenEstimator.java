package com.chatchat.knowledgebase.runtime;

import com.chatchat.common.knowledge.TokenEstimator;
import org.springframework.stereotype.Component;

/**
 * Lightweight tokenizer-independent estimate: CJK and non-ASCII symbols are approximately
 * one token, while ordinary ASCII text is approximately four characters per token.
 */
@Component
public class CjkAwareTokenEstimator implements TokenEstimator {

    @Override
    public int estimate(String value) {
        if (value == null || value.isEmpty()) {
            return 0;
        }
        long quarterTokens = 0;
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            if (isCjk(codePoint) || codePoint > 0x7f) {
                quarterTokens += 4;
            } else if (Character.isWhitespace(codePoint)) {
                quarterTokens += 1;
            } else {
                quarterTokens += 1;
            }
            offset += Character.charCount(codePoint);
        }
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1L, (quarterTokens + 3L) / 4L));
    }

    private boolean isCjk(int codePoint) {
        return codePoint >= 0x2E80 && codePoint <= 0xD7AF
            || codePoint >= 0xF900 && codePoint <= 0xFAFF
            || codePoint >= 0x20000 && codePoint <= 0x323AF;
    }
}
