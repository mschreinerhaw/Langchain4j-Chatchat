package com.chatchat.api.controller.agent;

import com.chatchat.api.security.ApiAuthenticationFilter;
import com.chatchat.chat.skills.domain.application.DomainSkillService;
import com.chatchat.chat.skills.runtime.BuiltinCodingSkills;
import com.chatchat.common.response.ApiResponse;
import com.chatchat.common.retrieval.ResourceAuthorizationPort;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/** Read-only discovery for callers. Management endpoints retain their separate permissions. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/interactions/resources")
public class AuthorizedResourceCatalogController {
    private final AgentWorkshopController workshop;
    private final DomainSkillService skills;
    private final ResourceAuthorizationPort authorization;

    @GetMapping("/agents")
    public ApiResponse<?> agents(HttpServletRequest request) {
        return ApiResponse.success(workshop.authorizedCatalog(request));
    }

    @GetMapping("/agents/{agentId}")
    public ApiResponse<?> agent(@PathVariable("agentId") String agentId, HttpServletRequest request) {
        return workshop.authorizedAgent(agentId, request);
    }

    @GetMapping("/skills")
    public ApiResponse<?> skills(HttpServletRequest request,
        @RequestParam(value="keyword", defaultValue="") String keyword,
        @RequestParam(value="category", defaultValue="") String category,
        @RequestParam(value="status", defaultValue="") String status,
        @RequestParam(value="page", defaultValue="0") int page,
        @RequestParam(value="pageSize", defaultValue="12") int pageSize) {
        String tenant = Objects.toString(request.getAttribute(ApiAuthenticationFilter.CURRENT_TENANT_ID), "");
        String user = Objects.toString(request.getAttribute(ApiAuthenticationFilter.CURRENT_USER_ID), "");
        var published = skills.publishedOptions(tenant);
        Set<String> candidates = published.stream().map(s -> s.getId()).collect(java.util.stream.Collectors.toSet());
        var allowed = authorization.allowedIds(ResourceAuthorizationPort.SKILL, tenant, user, Set.of(), candidates);
        var visible = published.stream().filter(s -> allowed.contains(s.getId())).toList();
        var filtered = visible.stream().filter(s -> category.isBlank() || category.equalsIgnoreCase(s.getCategory()))
            .filter(s -> status.isBlank() || "published".equalsIgnoreCase(status))
            .filter(s -> keyword.isBlank() || (s.getName() + " " + s.getDescription() + " " + s.getMarkdownContent())
                .toLowerCase(Locale.ROOT).contains(keyword.toLowerCase(Locale.ROOT))).toList();
        int p = Math.max(0, page), size = Math.max(1, Math.min(100, pageSize));
        var categories = visible.stream().map(s -> s.getCategory()).filter(Objects::nonNull).distinct().sorted()
            .map(name -> Map.of("name", name, "count", visible.stream().filter(s -> name.equals(s.getCategory())).count(), "manageable", false)).toList();
        return ApiResponse.success(Map.of("skills", filtered.stream().skip((long)p * size).limit(size).toList(),
            "total", filtered.size(), "skillCount", visible.size(), "page", p, "pageSize", size,
            "totalPages", (filtered.size() + size - 1) / size, "categories", categories,
            "builtinSkills", BuiltinCodingSkills.catalog()));
    }
}
