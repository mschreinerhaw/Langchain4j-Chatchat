package com.chatchat.agents.protocol;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ModelProtocolJsonInputViewTest {
    @Test void longMaterialsAreBoundedWithoutChangingTheOriginalOrHidingTruncation() {
        String raw="Explain this log\n"+"untrusted supplied material\n".repeat(2000)+"\nOnly report observed facts";
        var view=ModelProtocolJson.userInputView(raw,6000);
        assertThat(view).containsEntry("complete",false).containsEntry("originalCharacters",raw.length())
            .containsEntry("originalSha256",ModelProtocolJson.sha256Hex(raw));
        assertThat(view.get("text").toString()).hasSizeLessThanOrEqualTo(6000).startsWith("Explain this log")
            .endsWith("Only report observed facts").doesNotContain("material\n".repeat(1000));
        assertThat(raw.length()).isGreaterThan(24000);
    }
    @Test void shortObjectivesRemainCompleteAndSurrogateBoundariesArePreserved() {
        assertThat(ModelProtocolJson.userInputView("small objective",256)).containsEntry("text","small objective").containsEntry("complete",true);
        String raw="😀".repeat(500);String text=ModelProtocolJson.userInputView(raw,257).get("text").toString();
        assertThat(text).hasSizeLessThanOrEqualTo(257);
        for(int i=0;i<text.length();i++)if(Character.isHighSurrogate(text.charAt(i)))assertThat(Character.isLowSurrogate(text.charAt(++i))).isTrue();
    }
}
