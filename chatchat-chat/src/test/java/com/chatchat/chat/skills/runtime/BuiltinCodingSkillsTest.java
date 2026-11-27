package com.chatchat.chat.skills.runtime;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class BuiltinCodingSkillsTest {
    @Test void existingCatalogProvidesDisplayMetadataWithoutInstructionDelivery() {
        var catalog = BuiltinCodingSkills.catalog();
        assertThat(catalog).extracting(card -> card.get("id"))
            .contains("builtin:python-data-analysis", "builtin:runtime-report-delivery",
                "builtin:html-image-assets", "builtin:report-designer")
            .doesNotHaveDuplicates();
        assertThat(catalog).allSatisfy(card -> {
            assertThat(card).containsEntry("builtin", true).containsEntry("enabled", true)
                .doesNotContainKeys("instructions", "references", "resources");
            List<String> metadataKeys = Boolean.TRUE.equals(card.get("professionalReport"))
                ? List.of("name", "description", "source", "version")
                : List.of("displayName", "descriptionZh", "source", "sourceVersion");
            for (String key : metadataKeys)
                assertThat(card.get(key)).as(key).isInstanceOf(String.class).isNotEqualTo("");
            assertThat(card.get("id").toString()).startsWith("builtin:");
        });
    }
    @Test void legacyImageBindingsDoNotBecomeBusinessAuthorizations() {
        assertThat(BuiltinCodingSkills.businessIds(List.of("builtin:python-data-analysis", " business-a ",
            "builtin:python-code-development", "business-a", "builtin:retired-image-id"))).containsExactly("business-a");
    }
}
