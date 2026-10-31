package com.chatchat.api.controller.agent;

import com.chatchat.api.security.ApiAuthenticationFilter;
import com.chatchat.chat.skills.domain.application.DomainSkillService;
import com.chatchat.chat.skills.domain.catalog.DomainSkillEntity;
import com.chatchat.common.retrieval.ResourceAuthorizationPort;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AuthorizedResourceCatalogControllerTest {
    @Test void filtersSkillsBeforePaginationAndCategoryCountsWithoutExposingOtherSkills() {
        var skills = mock(DomainSkillService.class);
        var grants = mock(ResourceAuthorizationPort.class);
        var controller = new AuthorizedResourceCatalogController(mock(AgentWorkshopController.class), skills, grants);
        var hidden = skill("hidden", "secret"); var allowed = skill("allowed", "finance");
        var second = skill("second", "finance");
        when(skills.publishedOptions("tenant")).thenReturn(List.of(hidden, allowed, second));
        when(grants.allowedIds("SKILL", "tenant", "caller", Set.of(), Set.of("hidden", "allowed", "second")))
            .thenReturn(Set.of("allowed", "second"));
        var request = new MockHttpServletRequest();
        request.setAttribute(ApiAuthenticationFilter.CURRENT_TENANT_ID, "tenant");
        request.setAttribute(ApiAuthenticationFilter.CURRENT_USER_ID, "caller");
        var data = (Map<?, ?>) controller.skills(request, "", "", "", 1, 1).getData();
        assertThat((List<?>)data.get("skills")).hasSize(1);
        assertThat(((DomainSkillEntity)((List<?>)data.get("skills")).get(0)).getId()).isEqualTo("second");
        assertThat(data.get("total")).isEqualTo(2);
        assertThat(data.get("skillCount")).isEqualTo(2);
        assertThat(data.get("categories").toString()).contains("finance").doesNotContain("secret");
        var drafts = (Map<?, ?>) controller.skills(request, "", "", "DRAFT", 0, 10).getData();
        assertThat((List<?>)drafts.get("skills")).isEmpty();
    }
    private DomainSkillEntity skill(String id, String category) {
        var skill = new DomainSkillEntity(); skill.setId(id); skill.setName(id); skill.setCategory(category);
        skill.setDescription("description"); skill.setMarkdownContent("body"); return skill;
    }
}
