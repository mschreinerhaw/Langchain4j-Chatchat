package com.chatchat.api.license;

import com.chatchat.chat.skills.SkillCatalogService;
import com.chatchat.chat.skills.SkillDefinition;
import com.chatchat.common.mcp.license.McpLicenseEntitlementPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AgentPublicationLicenseService {

    private final SkillCatalogService skillCatalogService;
    private final McpLicenseEntitlementPort entitlementPort;
    private final JdbcTemplate jdbcTemplate;

    /** Uses a database row lock so multiple API instances cannot concurrently exceed the publication quota. */
    @Transactional
    public synchronized SkillDefinition publish(String agentId) {
        jdbcTemplate.queryForList("select id from skill_config order by id limit 1 for update", String.class);
        boolean alreadyPublished = skillCatalogService.list().stream()
            .anyMatch(agent -> agent.id().equalsIgnoreCase(agentId)
                && SkillCatalogService.MARKET_STATUS_PUBLISHED.equalsIgnoreCase(agent.marketStatus()));
        if (alreadyPublished) {
            return skillCatalogService.publishToMarket(agentId);
        }

        McpLicenseEntitlementPort.AgentPublicationLimit entitlement;
        try {
            entitlement = entitlementPort.agentPublicationLimit();
        } catch (RuntimeException ex) {
            throw new IllegalStateException("AGENT_LICENSE_CHECK_UNAVAILABLE: 无法从 MCP 服务校验 Agent 发布授权，已拒绝发布", ex);
        }
        if (!entitlement.licenseValid()) {
            throw new IllegalArgumentException("AGENT_LICENSE_INVALID: MCP License 无效，不能发布 Agent："
                + entitlement.message());
        }
        if (entitlement.limited()) {
            Integer maximum = entitlement.maxPublishedAgents();
            if (maximum == null || maximum <= 0) {
                throw new IllegalArgumentException("AGENT_LICENSE_LIMIT_INVALID: License 中的 Agent 发布数量无效");
            }
            long published = skillCatalogService.list().stream()
                .filter(agent -> SkillCatalogService.MARKET_STATUS_PUBLISHED.equalsIgnoreCase(agent.marketStatus()))
                .count();
            if (published >= maximum) {
                throw new IllegalArgumentException("AGENT_LICENSE_LIMIT_EXCEEDED: 已发布 Agent 数量已达到 License 上限 "
                    + maximum + "；仍可新建和编辑 Agent，但不能继续发布");
            }
        }
        return skillCatalogService.publishToMarket(agentId);
    }
}
