package com.chatchat.chat.skills.domain.adapter;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SkillMdExternalSkillAdapterTest {
    private final SkillMdExternalSkillAdapter adapter = new SkillMdExternalSkillAdapter(new SkillFormatDetector());

    @Test
    void extractsSupportedMetadataAndRemovesExternalControlMetadata() {
        String source = """
            ---
            name: china-idea-generation
            description: Systematic A-share idea sourcing.
            triggers:
              - A-share ideas
            allowed-tools:
              - arbitrary_external_tool
            ---
            # Workflow

            Screen candidates and validate every material claim.
            """;

        AdaptedExternalSkill result = adapter.adapt(new ExternalSkillSource(
            "SKILL.md", "URL_MARKDOWN", "https://example.test/SKILL.md", source));

        assertThat(result.name()).isEqualTo("china-idea-generation");
        assertThat(result.description()).isEqualTo("Systematic A-share idea sourcing.");
        assertThat(result.instructions())
            .startsWith("# Workflow")
            .doesNotContain("triggers", "allowed-tools", "arbitrary_external_tool");
        assertThat(result.sourceFormat()).isEqualTo("ANTHROPIC_SKILL_MD");
        assertThat(result.metadata()).containsEntry("frontMatter", true);
    }

    @Test
    void rejectsMalformedFrontMatterBeforeCompilation() {
        assertThatThrownBy(() -> adapter.adapt(new ExternalSkillSource(
            "SKILL.md", "MARKDOWN", "SKILL.md", "---\nname: [broken\n---\n# Body")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Invalid SKILL.md front matter");
    }
}
