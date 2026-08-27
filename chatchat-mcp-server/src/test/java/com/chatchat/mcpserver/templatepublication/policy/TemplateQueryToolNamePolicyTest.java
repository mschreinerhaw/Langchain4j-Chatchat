package com.chatchat.mcpserver.templatepublication.policy;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TemplateQueryToolNamePolicyTest {

    @Test
    void composesToolNameFromTheOnlyUserEditablePart() {
        assertThat(TemplateQueryToolNamePolicy.toolName("customer_service"))
            .isEqualTo("customer_service_template_query");
    }

    @Test
    void rejectsBareOrUserSuppliedSuffixNames() {
        assertThatThrownBy(() -> TemplateQueryToolNamePolicy.requireToolName("template_query"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("<domain>_template_query");
        assertThatThrownBy(() -> TemplateQueryToolNamePolicy.toolName("customer_template_query"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("system managed");
    }

    @Test
    void rejectsUppercaseWhitespaceAndPunctuation() {
        assertThatThrownBy(() -> TemplateQueryToolNamePolicy.toolName("Customer"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TemplateQueryToolNamePolicy.toolName("customer-api"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TemplateQueryToolNamePolicy.toolName(" customer"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNamesReservedByBuiltInTemplateTools() {
        assertThatThrownBy(() -> TemplateQueryToolNamePolicy.toolName("api"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("reserved");
    }
}
