package com.chatchat.chat.skills.runtime;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class BuiltinCodingSkillsTest {
    @Test void existingCatalogProvidesDisplayMetadataWithoutInstructionDelivery() {
        assertThat(BuiltinCodingSkills.catalog()).hasSize(6).allSatisfy(card -> {
            assertThat(card).containsEntry("builtin", true).containsEntry("enabled", true)
                .doesNotContainKeys("instructions", "references", "resources");
            for (String key : List.of("displayName", "descriptionZh", "source", "sourceVersion"))
                assertThat(card.get(key)).as(key).isInstanceOf(String.class).isNotEqualTo("");
            assertThat(card.get("id").toString()).startsWith("builtin:");
        });
    }
    @Test void legacyImageBindingsDoNotBecomeBusinessAuthorizations() {
        assertThat(BuiltinCodingSkills.businessIds(List.of("builtin:python-data-analysis", " business-a ",
            "builtin:python-code-development", "business-a", "builtin:retired-image-id"))).containsExactly("business-a");
    }
}
